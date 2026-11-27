/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import app.gyrolet.mpvrx.utils.storage.StorageVolumeUtils
import app.gyrolet.mpvrx.utils.storage.normalizeHiddenMarkerName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

data class HiddenFolderCandidate(
  val path: String,
  val relativePath: String,
  val volumeId: String,
  val markerName: String?,
)

data class HiddenFolderSuggestion(
  val key: String,
  val displayPath: String,
  val initialUri: Uri,
  val markerName: String? = null,
  val isDotFolder: Boolean = false,
  val childPaths: List<String> = emptyList(),
)

/**
 * Discovers names only, independently of the media index. Scoped storage can expose directories
 * while filtering their media and marker files from listings. A by-name marker lookup is best
 * effort; no media is opened and no discovered path is treated as a grant. All reads after Allow
 * continue through ScopedHiddenFolderScanner and the persisted document-tree permission.
 */
object HiddenFolderDiscovery {
  private const val AUTHORITY = "com.android.externalstorage.documents"
  private const val CACHE_MS = 5 * 60 * 1000L
  private const val MAX_DIRECTORIES = 20_000
  private const val MAX_DEPTH = 32
  private val mutex = Mutex()

  private data class Snapshot(
    val markers: Set<String>,
    val folders: List<HiddenFolderCandidate>,
    val completedAt: Long,
  )

  @Volatile private var snapshot: Snapshot? = null

  // MediaProvider ignores .nomedia in these public directories. Custom markers are independent.
  private val publicDirectories =
    setOf(
      "music",
      "podcasts",
      "ringtones",
      "alarms",
      "notifications",
      "pictures",
      "movies",
      "download",
      "dcim",
      "documents",
      "audiobooks",
      "recordings",
    )
  private val junkDirectories = setOf(".thumbnails", ".cache", ".tmp", ".temp", ".trash")

  private data class Visit(
    val directory: File,
    val root: File,
    val volumeId: String,
    val depth: Int,
  )

  /** Cached results appear first, followed by throttled batches from a cancellable IO walk. */
  fun discover(
    context: Context,
    markerNames: Set<String>,
    force: Boolean = false,
  ): Flow<List<HiddenFolderCandidate>> =
    flow {
      val markers = markerNames.mapNotNull(::normalizeHiddenMarkerName).toSortedSet()
      snapshot?.takeIf { it.markers == markers }?.let { emit(it.folders) }
      mutex.withLock {
        val previous = snapshot?.takeIf { it.markers == markers }
        if (!force &&
          previous != null &&
          previous.completedAt > 0 &&
          SystemClock.elapsedRealtime() - previous.completedAt < CACHE_MS
        ) {
          emit(previous.folders)
          return@withLock
        }
        val roots = linkedMapOf(Environment.getExternalStorageDirectory() to "primary")
        StorageVolumeUtils.getExternalStorageVolumes(context).forEach { volume ->
          val path = StorageVolumeUtils.getVolumePath(volume)
          val id = volume.uuid
          if (path != null && id != null) roots[File(path)] = id
        }
        val queue = ArrayDeque<Visit>()
        roots.forEach { (root, id) -> queue.addLast(Visit(root, root, id, 0)) }
        val canonicalRoots = roots.keys.associateWith { runCatching { it.canonicalPath }.getOrNull() }
        val visited = hashSetOf<String>()
        val found = mutableListOf<HiddenFolderCandidate>()
        var lastEmission = 0L
        var emittedCount = 0
        while (queue.isNotEmpty() && visited.size < MAX_DIRECTORIES) {
          currentCoroutineContext().ensureActive()
          val visit = queue.removeFirst()
          val directory = visit.directory
          val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: continue
          // Reject links out of the volume and loops, without traversing private Android trees.
          val rootPath = visit.root.absolutePath.trimEnd('/')
          val canonicalRoot = canonicalRoots[visit.root] ?: continue
          if (canonical != canonicalRoot && !canonical.startsWith("$canonicalRoot/")) continue
          if (isRestricted(canonical.removePrefix("$canonicalRoot/"))) continue
          if (!visited.add(canonical)) continue
          val relative =
            directory.absolutePath
              .removePrefix("$rootPath/")
              .takeIf { visit.depth > 0 }
              .orEmpty()
          if (isRestricted(relative) || directory.name.lowercase(Locale.ROOT) in junkDirectories) continue

          val dot = visit.depth > 0 && directory.name.startsWith('.')
          val marker =
            if (visit.depth > 0 && !dot) {
              markers.firstOrNull { name ->
                !(name == ".nomedia" && ignoresNoMedia(relative)) &&
                  runCatching { File(directory, name).exists() }.getOrDefault(false)
              }
            } else {
              null
            }
          if ((dot || marker != null) && canGrant(relative)) {
            found += HiddenFolderCandidate(directory.absolutePath, relative, visit.volumeId, marker)
            // Only the topmost hidden subtree needs a grant; do not inspect its media.
          } else if (visit.depth < MAX_DEPTH) {
            val children = runCatching { directory.listFiles() }.getOrNull()
            children?.forEach { child ->
              currentCoroutineContext().ensureActive()
              if (queue.size + visited.size < MAX_DIRECTORIES &&
                runCatching { child.isDirectory }.getOrDefault(false)
              ) {
                queue.addLast(Visit(child, visit.root, visit.volumeId, visit.depth + 1))
              }
            }
          }
          val now = SystemClock.elapsedRealtime()
          if (found.size != emittedCount && now - lastEmission >= 150L) {
            // Keep the old list stable during refresh; remove stale entries only when the walk ends.
            val batch = (found + previous?.folders.orEmpty()).distinctBy { it.path }
            snapshot = Snapshot(markers, batch, 0L)
            emit(batch)
            emittedCount = found.size
            lastEmission = now
          }
        }
        val result = found.toList()
        snapshot = Snapshot(markers, result, SystemClock.elapsedRealtime())
        emit(result)
      }
    }.flowOn(Dispatchers.IO)

  /** Filter descendants of live grants before grouping, so allowing a parent removes every child. */
  fun suggestions(
    candidates: List<HiddenFolderCandidate>,
    grantedTreeUris: Set<String>,
    revokedTreeUris: Set<String>,
  ): List<HiddenFolderSuggestion> {
    val grantedPaths = grantedTreeUris.mapNotNull { ScopedHiddenFolderScanner.localPathOf(Uri.parse(it)) }
    val revoked =
      revokedTreeUris
        .mapNotNull { tree ->
          val uri = Uri.parse(tree)
          val path = ScopedHiddenFolderScanner.localPathOf(uri)
          if (path != null && grantedPaths.any { covers(it, path) }) return@mapNotNull null
          HiddenFolderSuggestion(
            key = path ?: tree,
            displayPath = uri.lastPathSegment?.substringAfter(':')?.takeIf(String::isNotBlank) ?: tree,
            initialUri = uri,
          )
        }.distinctBy { it.key.lowercase(Locale.ROOT) }
    val pending =
      candidates.filter { candidate ->
        grantedPaths.none { covers(it, candidate.path) } && revoked.none { covers(it.key, candidate.path) }
      }
    val groups = pending.groupBy { it.volumeId to it.relativePath.substringBefore('/') }.values
    val grouped =
      groups.flatMap { folders ->
        var common = folders.first().relativePath.split('/')
        folders.drop(1).forEach { folder ->
          common =
            common
              .zip(folder.relativePath.split('/'))
              .takeWhile { (a, b) -> a.equals(b, ignoreCase = true) }
              .map { it.first }
        }
        val parent = common.joinToString("/")
        if (folders.size > 1 && canGrant(parent)) {
          val first = folders.first()
          val root = first.path.removeSuffix(first.relativePath)
          listOf(
            HiddenFolderSuggestion(
              key = root + parent,
              displayPath = displayPath(first.volumeId, parent),
              initialUri = documentUri(first.volumeId, parent),
              childPaths = folders.map { it.relativePath.substring(parent.length).trimStart('/') }.sorted(),
              markerName =
                folders.mapNotNull { it.markerName }.let { markers ->
                  markers.firstOrNull { it.equals(".nomedia", ignoreCase = true) } ?: markers.firstOrNull()
                },
              isDotFolder = folders.any { it.relativePath.substringAfterLast('/').startsWith('.') },
            ),
          )
        } else {
          folders.map { folder ->
            HiddenFolderSuggestion(
              key = folder.path,
              displayPath = displayPath(folder.volumeId, folder.relativePath),
              initialUri = documentUri(folder.volumeId, folder.relativePath),
              markerName = folder.markerName,
              isDotFolder = folder.relativePath.substringAfterLast('/').startsWith('.'),
            )
          }
        }
      }
    return (revoked + grouped).sortedWith(
      compareBy<HiddenFolderSuggestion> {
        when {
          it.markerName.equals(".nomedia", ignoreCase = true) -> 0
          it.markerName != null -> 1
          it.isDotFolder -> 2
          else -> 3
        }
      }.thenBy { it.displayPath.lowercase(Locale.ROOT) },
    )
  }

  private fun documentUri(
    volumeId: String,
    relative: String,
  ): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, "$volumeId:$relative")

  private fun displayPath(
    volumeId: String,
    relative: String,
  ): String = if (volumeId == "primary") relative else "$volumeId/$relative"

  private fun covers(
    parent: String,
    child: String,
  ): Boolean = child.equals(parent, ignoreCase = true) || child.startsWith("${parent.trimEnd('/')}/", ignoreCase = true)

  private fun isRestricted(relative: String): Boolean =
    covers("Android/data", relative) || covers("Android/obb", relative)

  private fun canGrant(relative: String): Boolean =
    relative.isNotEmpty() && !relative.equals("Download", ignoreCase = true) && !isRestricted(relative)

  private fun ignoresNoMedia(relative: String): Boolean {
    val path = relative.lowercase(Locale.ROOT)
    return path in publicDirectories ||
      path == "dcim/camera" ||
      path == "screenshots" ||
      (path.substringBefore('/') in publicDirectories && path.substringAfter('/', "") == "screenshots")
  }
}
