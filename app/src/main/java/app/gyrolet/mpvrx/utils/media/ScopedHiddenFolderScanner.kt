/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.utils.storage.MediaScanOptions
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import app.gyrolet.mpvrx.utils.storage.normalizeHiddenMarkerName
import app.gyrolet.mpvrx.utils.storage.storagePathKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Play Store folder grants (SAF tree URIs). Each granted tree is listed like a plain file browser:
 * one provider query per directory returns names, types, sizes and dates, so no file is opened or
 * probed for metadata and MediaStore is not consulted. Items keep their content:// document URIs,
 * which is also how the player rebuilds sibling queues.
 */
object ScopedHiddenFolderScanner {
  private const val TAG = "ScopedHiddenFolderScanner"

  fun isScopedFolder(bucketId: String): Boolean =
    bucketId.startsWith("content://") && Uri.parse(bucketId).path?.startsWith("/tree/") == true

  /** A document keeps its identity when reached through a parent grant or a child grant. */
  internal fun documentKey(uri: Uri): String = runCatching {
    val id = runCatching { DocumentsContract.getDocumentId(uri) }
      .getOrElse { DocumentsContract.getTreeDocumentId(uri) }
    DocumentsContract.buildDocumentUri(uri.authority, id).toString()
  }.getOrDefault(uri.toString())

  fun localPathOf(uri: Uri): String? {
    if (!isPathBasedDocumentAuthority(uri.authority)) return null
    val id = runCatching { DocumentsContract.getDocumentId(uri) }
      .recoverCatching { DocumentsContract.getTreeDocumentId(uri) }
      .getOrNull() ?: return null
    val volume = id.substringBefore(':', "")
    if (volume.isEmpty()) return null
    val relative = id.substringAfter(':').trim('/')
    val root =
      if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory().absolutePath
      } else {
        "/storage/$volume"
      }
    return if (relative.isEmpty()) root else "$root/$relative"
  }

  /** Drops granted folders that are the same directory as a MediaStore/filesystem folder. */
  fun withoutLocalDuplicates(localFolders: List<VideoFolder>, scopedFolders: List<VideoFolder>): List<VideoFolder> {
    if (scopedFolders.isEmpty()) return scopedFolders
    val localKeys = localFolders.mapNotNullTo(hashSetOf()) { folder ->
      if (isScopedFolder(folder.path)) null else storagePathKey(folder.path)
    }
    if (localKeys.isEmpty()) return scopedFolders
    return scopedFolders.filterNot { folder ->
      localPathOf(Uri.parse(folder.bucketId))?.let(::storagePathKey) in localKeys
    }
  }

  // Keep walks bounded even if a document provider exposes cyclic or excessively deep trees.
  private const val MAX_DIRECTORY_DEPTH = 64

  private data class DirectoryVisit(
    val uri: Uri,
    val bucketId: String,
    val name: String,
    val displayName: String,
    val depth: Int,
    val parentBucketId: String?,
  )

  private data class IndexedDirectory(
    val visit: DirectoryVisit,
    val grantedTreeUri: String,
  )

  private enum class WalkStep { Descend, SkipSubtree, Stop }

  // Filled by getFolders so a folder can be opened without walking its grant again.
  @Volatile private var indexedDirectories: Map<String, IndexedDirectory> = emptyMap()

  fun isIndexedDescendant(bucketId: String, ancestorBucketId: String): Boolean {
    var current = bucketId
    val visited = hashSetOf<String>()
    while (visited.add(current)) {
      if (current == ancestorBucketId) return true
      current = indexedDirectories[current]?.visit?.parentBucketId ?: return false
    }
    return false
  }

  /**
   * Child folder buckets are document URIs anchored to the *original* granted tree, not new
   * grants. URI matching here is only a fast filter; getVideos still finds the child by walking
   * an accessible persisted tree, so a fabricated document URI cannot bypass the grant.
   */
  fun isGrantedFolder(bucketId: String, grantedTreeUris: Set<String>): Boolean {
    if (!isScopedFolder(bucketId)) return false
    val requested = Uri.parse(bucketId)
    val parts = requested.pathSegments
    if (parts.size != 2 && !(parts.size == 4 && parts[2] == "document")) return false
    return grantedTreeUris.any { granted ->
      val root = Uri.parse(granted)
      val rootParts = root.pathSegments
      rootParts.size == 2 &&
        rootParts[0] == "tree" &&
        requested.scheme == root.scheme &&
        requested.authority == root.authority &&
        parts[1] == rootParts[1]
    }
  }

  suspend fun getFolders(
    context: Context,
    grantedTreeUris: Set<String>,
    includeAudio: Boolean,
    audioOnly: Boolean = false,
    includeMarkedFolders: Boolean = true,
    includeDotFolders: Boolean = true,
    markerNames: Set<String> = setOf(".nomedia"),
    ignoredMarkerNames: Set<String> = emptySet(),
    scanOptions: MediaScanOptions = MediaScanOptions(),
  ): List<VideoFolder> = withContext(Dispatchers.IO) {
    val normalizedMarkers = markerNames.mapNotNull(::normalizeHiddenMarkerName).toSet()
    val ignoredMarkers = ignoredMarkerNames.mapNotNull(::normalizeHiddenMarkerName).toSet()
    val excludedMarkers = if (includeMarkedFolders) normalizedMarkers.intersect(ignoredMarkers) else normalizedMarkers
    val folders = mutableListOf<VideoFolder>()
    val discovered = mutableMapOf<String, IndexedDirectory>()
    for (treeUri in grantedTreeUris.sorted()) {
      walkGrant(context, treeUri, skipDotDirectories = !includeDotFolders) { visit, children ->
        if (children.any { it.isFile && it.name in excludedMarkers }) {
          return@walkGrant WalkStep.SkipSubtree
        }
        discovered[visit.bucketId] = IndexedDirectory(visit, treeUri)
        val items = mediaItems(visit.bucketId, visit.displayName, children, includeAudio, audioOnly)
          .filter { (includeDotFolders || !it.displayName.startsWith(".")) && scanOptions.includesVideo(it) }
        // Keep the directory in the traversal index, but only show folders with playable items.
        // Empty parents must still be descended into so their non-empty children remain visible.
        if (items.isNotEmpty()) {
          folders += VideoFolder(
            bucketId = visit.bucketId,
            name = visit.displayName,
            path = visit.bucketId,
            videoCount = items.size,
            totalSize = items.sumOf { it.size },
            totalDuration = 0L,
            lastModified = items.maxOfOrNull { it.dateModified } ?: 0L,
          )
        }
        WalkStep.Descend
      }
    }
    // Retain every grant alias in the index so removing one grant or using a saved folder link
    // still resolves correctly. Only the displayed folder list collapses equivalent documents.
    indexedDirectories = discovered.toMap()
    folders.distinctBy { documentKey(Uri.parse(it.bucketId)) }.sortedBy { it.name.lowercase(Locale.ROOT) }
  }

  /**
   * Collects every playable item beneath a folder for the explicit folder-swipe action. Unlike a
   * filesystem File.walkTopDown, traversal is rooted inside the persisted user-selected tree.
   */
  suspend fun getVideosRecursively(
    context: Context,
    bucketId: String,
    grantedTreeUris: Set<String>,
    includeAudio: Boolean,
    audioOnly: Boolean = false,
  ): List<Video> = withContext(Dispatchers.IO) {
    val start = resolveDirectory(context, bucketId, grantedTreeUris) ?: return@withContext emptyList()
    val videos = mutableListOf<Video>()
    walkFrom(context, start, skipDotDirectories = false) { visit, children ->
      videos += mediaItems(visit.bucketId, visit.displayName, children, includeAudio, audioOnly)
      WalkStep.Descend
    }
    videos.distinctBy { documentKey(it.uri) }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
  }

  /** The folder behind [bucketId] inside a persisted grant, reusing the last folder-list walk when still valid. */
  private suspend fun resolveDirectory(
    context: Context,
    bucketId: String,
    grantedTreeUris: Set<String>,
  ): DirectoryVisit? {
    if (!isGrantedFolder(bucketId, grantedTreeUris)) return null
    val cached = indexedDirectories[bucketId]
    if (cached != null && cached.grantedTreeUri in grantedTreeUris &&
      openPersistedTreeDocument(context, cached.grantedTreeUri) != null
    ) {
      return cached.visit
    }
    var found: DirectoryVisit? = null
    for (treeUri in grantedTreeUris.sorted()) {
      if (!isGrantedFolder(bucketId, setOf(treeUri))) continue
      walkGrant(context, treeUri, skipDotDirectories = false) { visit, _ ->
        if (visit.bucketId != bucketId) return@walkGrant WalkStep.Descend
        found = visit
        WalkStep.Stop
      }
      if (found != null) break
    }
    return found
  }

  private suspend fun walkGrant(
    context: Context,
    treeUri: String,
    skipDotDirectories: Boolean,
    visit: suspend (DirectoryVisit, List<ChildEntry>) -> WalkStep,
  ) {
    val root = openPersistedTreeDocument(context, treeUri) ?: return
    val rootName = root.name ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast(':') ?: "Selected folder"
    walkFrom(context, DirectoryVisit(root.uri, treeUri, rootName, rootName, 0, null), skipDotDirectories, visit)
  }

  /** Breadth-first walk from [start]: one provider query per directory, and [visit] can prune or stop. */
  private suspend fun walkFrom(
    context: Context,
    start: DirectoryVisit,
    skipDotDirectories: Boolean,
    visit: suspend (DirectoryVisit, List<ChildEntry>) -> WalkStep,
  ) {
    val queue = ArrayDeque<DirectoryVisit>()
    val visited = hashSetOf<String>()
    queue.addLast(start)
    while (queue.isNotEmpty()) {
      currentCoroutineContext().ensureActive()
      val directory = queue.removeFirst()
      if (skipDotDirectories && directory.name.startsWith(".")) continue
      if (!visited.add(documentKey(directory.uri))) continue
      val children = listChildren(context, directory.uri)
      val step = visit(directory, children)
      if (step == WalkStep.Stop) return
      if (step == WalkStep.SkipSubtree || directory.depth >= MAX_DIRECTORY_DEPTH) continue
      for (child in children) {
        if (!child.isDirectory) continue
        queue.addLast(
          DirectoryVisit(
            uri = child.uri,
            bucketId = child.uri.toString(),
            name = child.name,
            displayName = "${directory.displayName} / ${child.name}",
            depth = directory.depth + 1,
            parentBucketId = directory.bucketId,
          ),
        )
      }
    }
  }

  suspend fun getVideos(
    context: Context,
    bucketId: String,
    includeAudio: Boolean,
    grantedTreeUris: Set<String>,
    scanOptions: MediaScanOptions = MediaScanOptions(),
  ): List<Video> = withContext(Dispatchers.IO) {
    val directory = resolveDirectory(context, bucketId, grantedTreeUris) ?: return@withContext emptyList()
    mediaItems(bucketId, directory.displayName, listChildren(context, directory.uri), includeAudio, audioOnly = false)
      .filter(scanOptions::includesVideo)
  }

  /** Playable items of one listing. Names and MIME types decide the kind; nothing is opened or probed. */
  private fun mediaItems(
    bucketId: String,
    folderName: String,
    children: List<ChildEntry>,
    includeAudio: Boolean,
    audioOnly: Boolean,
  ): List<Video> =
    children.mapNotNull { child ->
      if (!child.isFile) return@mapNotNull null
      val mimeType = child.mimeType
      val file = File(child.name)
      val isAudio = mimeType.startsWith("audio/") ||
        (!mimeType.startsWith("video/") && FileTypeUtils.isAudioFile(file))
      val isVideo = mimeType.startsWith("video/") || (!isAudio && FileTypeUtils.isVideoFile(file))
      if (audioOnly && !isAudio) return@mapNotNull null
      if (!(isVideo || (includeAudio && isAudio))) return@mapNotNull null
      val size = child.size.coerceAtLeast(0L)
      val modified = (child.lastModified / 1_000L).coerceAtLeast(0L)
      Video(
        id = child.uri.toString().hashCode().toLong(),
        title = child.name.substringBeforeLast('.', child.name),
        displayName = child.name,
        path = child.uri.toString(),
        uri = child.uri,
        // Probed later in the background; blank keeps the card from showing a bogus "0s".
        duration = 0L,
        durationFormatted = "",
        size = size,
        sizeFormatted = VideoScanUtils.formatFileSize(size),
        dateModified = modified,
        dateAdded = modified,
        mimeType = mimeType.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
          ?: if (isAudio) "audio/*" else "video/*",
        bucketId = bucketId,
        bucketDisplayName = folderName,
        width = 0,
        height = 0,
        fps = 0f,
        resolution = "",
        isAudio = isAudio,
      )
    }.sortedBy { it.displayName.lowercase(Locale.ROOT) }

  private data class ChildEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
  ) {
    val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    val isFile: Boolean get() = !isDirectory && mimeType.isNotEmpty()
  }

  private val childColumns = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
  )

  // One cursor per directory returns every column a row needs. DocumentFile would cost a separate
  // provider call for the name, type and size of each child.
  private fun listChildren(context: Context, directoryUri: Uri): List<ChildEntry> =
    runCatching {
      val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        directoryUri,
        DocumentsContract.getDocumentId(directoryUri),
      )
      context.contentResolver.query(childrenUri, childColumns, null, null, null)?.use { cursor ->
        val entries = ArrayList<ChildEntry>(cursor.count)
        while (cursor.moveToNext()) {
          val id = cursor.getString(0) ?: continue
          val name = cursor.getString(1) ?: continue
          entries += ChildEntry(
            uri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, id),
            name = name,
            mimeType = cursor.getString(2).orEmpty(),
            size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
            lastModified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
          )
        }
        entries
      }.orEmpty()
    }.onFailure { error ->
      Log.w(TAG, "Cannot list a selected folder", error)
    }.getOrDefault(emptyList())
}
