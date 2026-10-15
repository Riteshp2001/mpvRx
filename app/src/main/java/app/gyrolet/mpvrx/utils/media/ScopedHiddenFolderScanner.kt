/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.MediaScanOptions
import app.gyrolet.mpvrx.utils.storage.StorageVolumeUtils
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Play Store-safe explicit folder grants. Never opens a SAF item through a raw file path:
 * every playable item retains its content:// URI. A single user-picked tree grant also covers
 * its nested directories. Each directory with playable media appears as its own folder, using a
 * document URI under the original grant; no raw paths or broad storage permissions are needed.
 * Metadata is read through the same grants, not filesystem paths or MediaStore permissions.
 * The player also uses this scan to rebuild sibling queues for history and other single-item launches.
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

  /**
   * ExternalStorageProvider defines volume:path document IDs. This location is used ONLY to
   * compare against the normal browser's MediaStore results; playback always keeps its SAF URI.
   * Other providers have opaque IDs and must never be guessed from display names.
   */
  private fun mediaStoreLocation(context: Context, uri: Uri): String? = runCatching {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val id = DocumentsContract.getDocumentId(uri)
    val separator = id.indexOf(':')
    if (separator < 1) return null
    val volumeId = id.substring(0, separator)
    val relativePath = id.substring(separator + 1)
    if (relativePath.startsWith('/') || relativePath.split('/').any { it == ".." || it == "." }) return null
    val root = when {
      volumeId.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().absolutePath
      volumeId == "home" -> File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS).path
      else -> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volume = manager.storageVolumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) } ?: return null
        StorageVolumeUtils.getVolumePath(volume) ?: return null
      }
    }
    "$root/$relativePath"
  }.getOrNull()

  private suspend fun visibleMediaStorePaths(
    context: Context,
    children: Array<DocumentFile>,
    includeAudio: Boolean,
    minimumAudioDurationSeconds: Int,
    audioOnly: Boolean,
  ): Set<String> {
    if (!app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) return emptySet()
    val parents = children.mapNotNull { mediaStoreLocation(context, it.uri)?.let { path -> File(path).parent } }.toSet()
    val visible = mutableSetOf<String>()
    for (parent in parents) {
      currentCoroutineContext().ensureActive()
      // Use the same scanner/filters as the normal browser. A grant or a matching name is not
      // evidence of a duplicate: only rows actually readable through MediaStore are excluded.
      // Denied video permission leaves videos visible through SAF; audio is handled separately.
      VideoScanUtils.getVideosInFolder(
        context, parent,
        MediaScanOptions(includeAudio = includeAudio, minimumAudioDurationSeconds = minimumAudioDurationSeconds),
      ).filter { !audioOnly || it.isAudio }.forEach { visible += it.path }
    }
    return visible
  }

  // Keep walks bounded even if a document provider exposes cyclic or excessively deep trees.
  private const val MAX_DIRECTORY_DEPTH = 64

  private data class DirectoryVisit(
    val document: DocumentFile,
    val bucketId: String,
    val displayName: String,
    val depth: Int,
    val parentBucketId: String? = null,
  )

  private data class IndexedDirectory(
    val document: DocumentFile,
    val grantedTreeUri: String,
    val displayName: String,
    val parentBucketId: String?,
  )

  // A folder list scan already visited every child. Reuse those DocumentFile handles for the
  // library's per-folder counts and video pages instead of traversing the tree once per folder.
  // listFiles() still queries current contents; the cache only holds directory locations.
  @Volatile private var indexedDirectories: Map<String, IndexedDirectory> = emptyMap()

  fun isIndexedDescendant(bucketId: String, ancestorBucketId: String): Boolean {
    var current = bucketId
    val visited = hashSetOf<String>()
    while (visited.add(current)) {
      if (current == ancestorBucketId) return true
      current = indexedDirectories[current]?.parentBucketId ?: return false
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
    minimumAudioDurationSeconds: Int = 0,
    audioOnly: Boolean = false,
    includeMarkedFolders: Boolean = true,
    includeDotFolders: Boolean = true,
    markerNames: Set<String> = setOf(".nomedia"),
    ignoredMarkerNames: Set<String> = emptySet(),
  ): List<VideoFolder> = withContext(Dispatchers.IO) {
    // SAF permissions are explicit: inspect only the documents inside user-granted trees.
    // Normalize marker names exactly like the filesystem scanner.
    val normalizedMarkers = markerNames.mapNotNull { app.gyrolet.mpvrx.utils.storage.normalizeHiddenMarkerName(it) }.toSet()
    val ignoredMarkers = ignoredMarkerNames.mapNotNull {
      app.gyrolet.mpvrx.utils.storage.normalizeHiddenMarkerName(it)
    }.toSet()
    val excludedMarkers = if (includeMarkedFolders) normalizedMarkers.intersect(ignoredMarkers) else normalizedMarkers
    val folders = mutableListOf<VideoFolder>()
    val discovered = mutableMapOf<String, IndexedDirectory>()
    for (treeUri in grantedTreeUris.sorted()) {
      currentCoroutineContext().ensureActive()
      val root = openPersistedTreeDocument(context, treeUri) ?: continue
      val rootName = root.name ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast(':') ?: "Selected folder"
      val pending = ArrayDeque<DirectoryVisit>()
      val visited = hashSetOf<String>()
      pending.addLast(DirectoryVisit(root, treeUri, rootName, 0))

      while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val folder = pending.removeFirst()
        if (!visited.add(documentKey(folder.document.uri))) continue
        val children = listTreeFilesSafely(folder.document)
        if (!includeDotFolders && folder.document.name?.startsWith(".") == true) continue
        if (children.any { it.isFile && it.name in excludedMarkers }) continue
        discovered[folder.bucketId] =
          IndexedDirectory(folder.document, treeUri, folder.displayName, folder.parentBucketId)
        val videos = readVideosFromFiles(
          context, children, folder.bucketId, folder.displayName, includeAudio,
          minimumAudioDurationSeconds, audioOnly, excludeMediaStoreItems = true,
        ).filter { includeDotFolders || !it.displayName.startsWith(".") }
        if (videos.isNotEmpty()) {
          folders += VideoFolder(
            bucketId = folder.bucketId,
            name = folder.displayName,
            path = folder.bucketId,
            videoCount = videos.size,
            totalSize = videos.sumOf { it.size },
            totalDuration = videos.sumOf { it.duration },
            lastModified = videos.maxOfOrNull { it.dateModified } ?: 0L,
          )
        }

        if (folder.depth < MAX_DIRECTORY_DEPTH) {
          for (child in children) {
            currentCoroutineContext().ensureActive()
            if (!child.isDirectory) continue
            val childName = child.name ?: continue
            pending.addLast(
              DirectoryVisit(
                child,
                child.uri.toString(),
                "${folder.displayName} / $childName",
                folder.depth + 1,
                folder.bucketId,
              ),
            )
          }
        }
      }
    }
    // Retain every grant alias in the index so removing one grant or using a saved folder link
    // still resolves correctly. Only the displayed folder list collapses equivalent documents.
    indexedDirectories = discovered.toMap()
    folders.distinctBy { documentKey(Uri.parse(it.bucketId)) }.sortedBy { it.name.lowercase(Locale.ROOT) }
  }

  /**
   * Recursively collect playable files for the explicit SAF folder-swipe action. Unlike a
   * filesystem File.walkTopDown, traversal is rooted inside the persisted user-selected tree.
   */
  suspend fun getVideosRecursively(
    context: Context,
    bucketId: String,
    grantedTreeUris: Set<String>,
    includeAudio: Boolean,
    audioOnly: Boolean = false,
    minimumAudioDurationSeconds: Int = 0,
  ): List<Video> = withContext(Dispatchers.IO) {
    val start = findGrantedDirectory(context, bucketId, grantedTreeUris) ?: return@withContext emptyList()
    val queue = ArrayDeque<DirectoryVisit>()
    val visited = hashSetOf<String>()
    val videos = mutableListOf<Video>()
    queue.addLast(start)
    while (queue.isNotEmpty()) {
      currentCoroutineContext().ensureActive()
      val folder = queue.removeFirst()
      if (!visited.add(documentKey(folder.document.uri))) continue
      val children = listTreeFilesSafely(folder.document)
      videos += readVideosFromFiles(
        context, children, folder.bucketId, folder.displayName, includeAudio,
        minimumAudioDurationSeconds, audioOnly, excludeMediaStoreItems = true,
      )
      if (folder.depth < MAX_DIRECTORY_DEPTH) {
        for (child in children) {
          currentCoroutineContext().ensureActive()
          if (!child.isDirectory) continue
          val name = child.name ?: continue
          queue.addLast(
            DirectoryVisit(
              child, child.uri.toString(), "${folder.displayName} / $name", folder.depth + 1,
            ),
          )
        }
      }
    }
    videos.distinctBy { documentKey(it.uri) }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
  }

  /** Only return a real directory visited beneath a persisted SAF grant. */
  private suspend fun findGrantedDirectory(
    context: Context,
    bucketId: String,
    grantedTreeUris: Set<String>,
    requireWrite: Boolean = false,
  ): DirectoryVisit? {
    if (!isGrantedFolder(bucketId, grantedTreeUris)) return null
    for (treeUri in grantedTreeUris.sorted()) {
      currentCoroutineContext().ensureActive()
      if (!isGrantedFolder(bucketId, setOf(treeUri))) continue
      val root = openPersistedTreeDocument(context, treeUri, requireWrite) ?: continue
      val queue = ArrayDeque<DirectoryVisit>()
      val seen = hashSetOf<String>()
      queue.addLast(DirectoryVisit(root, treeUri, root.name ?: "Selected folder", 0))
      while (queue.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val current = queue.removeFirst()
        if (!seen.add(documentKey(current.document.uri))) continue
        if (current.bucketId == bucketId) {
          return current.takeIf { it.document.isDirectory && (!requireWrite || it.document.canWrite()) }
        }
        if (current.depth >= MAX_DIRECTORY_DEPTH) continue
        for (child in listTreeFilesSafely(current.document)) {
          currentCoroutineContext().ensureActive()
          if (!child.isDirectory) continue
          queue.addLast(
            DirectoryVisit(
              child, child.uri.toString(), child.name ?: "Selected folder", current.depth + 1,
            ),
          )
        }
      }
    }
    return null
  }

  suspend fun getVideos(
    context: Context,
    bucketId: String,
    includeAudio: Boolean,
    grantedTreeUris: Set<String>,
    minimumAudioDurationSeconds: Int = 0,
    excludeMediaStoreItems: Boolean = false,
  ): List<Video> = withContext(Dispatchers.IO) {
    if (!isGrantedFolder(bucketId, grantedTreeUris)) return@withContext emptyList()

    val cached = indexedDirectories[bucketId]
    if (cached != null && cached.grantedTreeUri in grantedTreeUris &&
      openPersistedTreeDocument(context, cached.grantedTreeUri) != null
    ) {
      return@withContext readVideosFromFiles(
        context, listTreeFilesSafely(cached.document), bucketId, cached.displayName, includeAudio,
        minimumAudioDurationSeconds, excludeMediaStoreItems = excludeMediaStoreItems,
      )
    }

    // Resolve the requested directory from the actual persisted grant rather than treating its
    // URI as a separate tree permission. Only directories discovered beneath the grant are used.
    for (treeUri in grantedTreeUris.sorted()) {
      if (!isGrantedFolder(bucketId, setOf(treeUri))) continue
      val root = openPersistedTreeDocument(context, treeUri) ?: continue
      val rootName = root.name ?: "Selected folder"
      val pending = ArrayDeque<DirectoryVisit>()
      val visited = hashSetOf<String>()
      pending.addLast(DirectoryVisit(root, treeUri, rootName, 0))

      while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val folder = pending.removeFirst()
        if (!visited.add(documentKey(folder.document.uri))) continue
        val children = listTreeFilesSafely(folder.document)
        if (folder.bucketId == bucketId) {
          return@withContext readVideosFromFiles(
            context, children, folder.bucketId, folder.displayName, includeAudio,
            minimumAudioDurationSeconds, excludeMediaStoreItems = excludeMediaStoreItems,
          )
        }
        if (folder.depth < MAX_DIRECTORY_DEPTH) {
          for (child in children) {
            currentCoroutineContext().ensureActive()
            if (child.isDirectory) {
              val childName = child.name ?: continue
              pending.addLast(
                DirectoryVisit(
                  child,
                  child.uri.toString(),
                  "${folder.displayName} / $childName",
                  folder.depth + 1,
                ),
              )
            }
          }
        }
      }
    }
    emptyList()
  }

  private suspend fun readVideosFromFiles(
    context: Context,
    children: Array<DocumentFile>,
    bucketId: String,
    folderName: String,
    includeAudio: Boolean,
    minimumAudioDurationSeconds: Int = 0,
    audioOnly: Boolean = false,
    excludeMediaStoreItems: Boolean = false,
  ): List<Video> {
    val indexedPaths = if (excludeMediaStoreItems) {
      visibleMediaStorePaths(context, children, includeAudio, minimumAudioDurationSeconds, audioOnly)
    } else emptySet()
    val seenDocuments = hashSetOf<String>()
    return children.mapNotNull { document ->
      currentCoroutineContext().ensureActive()
      run {
        val name = document.name ?: return@run null
        try {
          if (!document.isFile) return@run null
          val fakeFile = File(name)
          val mimeType = document.type.orEmpty()
          val isAudio = mimeType.startsWith("audio/") ||
            (!mimeType.startsWith("video/") && FileTypeUtils.isAudioFile(fakeFile))
          val isVideo = mimeType.startsWith("video/") || (!isAudio && FileTypeUtils.isVideoFile(fakeFile))
          if (audioOnly && !isAudio) return@run null
          if (!(isVideo || (includeAudio && isAudio))) return@run null
          val documentUri = document.uri
          if (!seenDocuments.add(documentKey(documentUri))) return@run null
          if (indexedPaths.isNotEmpty() &&
            mediaStoreLocation(context, documentUri)?.let { it in indexedPaths } == true
          ) return@run null
          val uriString = documentUri.toString()
          val length = document.length().coerceAtLeast(0L)
          val modified = (document.lastModified() / 1_000L).coerceAtLeast(0L)
          val metadata =
            MediaInfoOps.extractBasicMetadata(context, documentUri, name)
              .onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Cannot read metadata in a selected folder: $name", error)
              }.getOrNull()
          val duration = metadata?.durationMs ?: 0L
          val size = length.takeIf { it > 0L } ?: metadata?.sizeBytes ?: 0L
          if (isAudio && duration < minimumAudioDurationSeconds.coerceAtLeast(0).toLong() * 1_000L) return@run null
          if (metadata != null && duration <= 0L) {
            Log.w(TAG, "No duration available for an item in a selected folder: $name")
          }
          Video(
            id = uriString.hashCode().toLong(),
            title = name.substringBeforeLast('.', name),
            displayName = name,
            path = uriString,
            uri = documentUri,
            duration = duration,
            durationFormatted = VideoScanUtils.formatDuration(duration),
            size = size,
            sizeFormatted = VideoScanUtils.formatFileSize(size),
            dateModified = modified,
            dateAdded = modified,
            mimeType = mimeType.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
              ?: if (isAudio) "audio/*" else "video/*",
            bucketId = bucketId,
            bucketDisplayName = folderName,
            width = metadata?.width ?: 0,
            height = metadata?.height ?: 0,
            fps = metadata?.fps ?: 0f,
            resolution =
              metadata?.let { VideoScanUtils.formatResolutionWithFps(it.width, it.height, it.fps) }.orEmpty(),
            hasEmbeddedSubtitles = metadata?.hasEmbeddedSubtitles ?: false,
            subtitleCodec = metadata?.subtitleCodec.orEmpty(),
            videoCodec = metadata?.videoCodec.orEmpty(),
            videoCodecMimeType = metadata?.videoCodecMimeType.orEmpty(),
            isAudio = isAudio,
          )
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          Log.w(TAG, "Cannot read an item in a selected folder: $name", error)
          null
        }
      }
    }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
  }
}
