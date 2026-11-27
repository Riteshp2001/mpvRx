/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.browser.medialibrary.MediaLibraryCache
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recovers ordinary videos written without a MediaStore notification (for example, extraction by
 * a file manager). Runs only on browser return or explicit refresh, never on a scroll/frame timer.
 * Directory listings are reused between passes; only missing videos are statted and sent to the
 * system scanner. No decoder or metadata probe is opened to discover a file.
 *
 * The readable file snapshot also feeds both browser modes while the system scanner catches up,
 * including formats the platform cannot index. Hidden media keeps its existing separate policy.
 */
object UnindexedVideoDiscovery {
  private const val TAG = "UnindexedVideoDiscovery"
  private const val PREFS = "unindexed_video_discovery"
  private const val HAD_UNINDEXED = "had_unindexed"
  private const val LISTING_TTL_MS = 30_000L
  private const val SCAN_RETRY_MS = 60_000L
  private val mutex = Mutex()
  private val indexingMutex = Mutex()

  private data class DirectoryListing(
    val modified: Long,
    val checkedAt: Long,
    val children: List<File>,
    val videos: List<File>,
  )

  private data class ScanRequest(
    val size: Long,
    val modified: Long,
    val requestedAt: Long,
  )

  private data class Snapshot(
    val videos: List<Video>,
    val byFolder: Map<String, List<Video>>,
  )

  private val directories = HashMap<String, DirectoryListing>()
  private val scanRequests = HashMap<String, ScanRequest>()
  private var completedFirstPass = false

  @Volatile private var snapshot = Snapshot(emptyList(), emptyMap())

  val hasVideos: Boolean get() = snapshot.videos.isNotEmpty()

  fun videos(
    options: MediaScanOptions,
    folderPath: String? = null,
  ): List<Video> {
    if (BuildConfig.SCOPED_STORAGE_ONLY) return emptyList()
    val current = snapshot
    if (current.videos.isEmpty()) return emptyList()
    val candidates =
      if (folderPath == null) {
        current.videos
      } else {
        mediaPathKey(folderPath)?.let { current.byFolder[it] }.orEmpty()
      }
    return candidates.filter {
      !options.pathFilter.shouldExcludeFile(File(it.path)) && File(it.path).isFile && options.includesVideo(it)
    }
  }

  suspend fun refresh(
    context: Context,
    force: Boolean = false,
  ): Unit =
    withContext(Dispatchers.IO) {
      // Never use a filesystem walk to bypass the Play build's scoped-storage grants.
      if (BuildConfig.SCOPED_STORAGE_ONLY ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager())
      ) {
        return@withContext
      }

      var discovered = false
      mutex.withLock {
        try {
          val indexed = readIndexedPaths(context) ?: return@withLock
          val filter = NoMediaPathFilter(MediaScanOptions())
          val queue = ArrayDeque<File>()
          // Existing media folders and common extraction destinations are checked before the tree.
          val primary = Environment.getExternalStorageDirectory()
          val priorityDirectories = indexed.mapNotNullTo(linkedSetOf<File>()) { File(it).parentFile }
          snapshot.videos.mapTo(priorityDirectories) { File(it.bucketId) }
          priorityDirectories.add(File(primary, Environment.DIRECTORY_DOWNLOADS))
          priorityDirectories.add(File(primary, Environment.DIRECTORY_MOVIES))
          priorityDirectories.forEach(queue::addLast)
          val priorityPaths =
            priorityDirectories.mapNotNullTo(hashSetOf()) {
              runCatching { it.canonicalPath }.getOrNull()
            }
          queue.addLast(primary)
          StorageVolumeUtils.getExternalStorageVolumes(context).forEach { volume ->
            StorageVolumeUtils.getVolumePath(volume)?.let { queue.addLast(File(it)) }
          }

          val visited = hashSetOf<String>()
          val found = linkedMapOf<String, Video>()
          var lastPublish = SystemClock.elapsedRealtime()
          var processed = 0
          while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.removeFirst()
            val path = runCatching { directory.canonicalPath }.getOrNull() ?: continue
            if (!visited.add(path) ||
              filter.shouldExcludeDirectory(directory) ||
              path.endsWith("/Android/data") ||
              path.contains("/Android/data/") ||
              path.endsWith("/Android/obb") ||
              path.contains("/Android/obb/")
            ) {
              continue
            }
            val stat = readFileEntryStat(directory) ?: continue
            if (!stat.isDirectory) continue
            val now = SystemClock.elapsedRealtime()
            val cached = directories[path]
            // Extractors can restore directory timestamps. Always list known media directories;
            // unchanged structural directories can reuse their children during the follow-up pass.
            val listing =
              if (!force &&
                path !in priorityPaths &&
                cached != null &&
                cached.modified == stat.lastModifiedMs &&
                now - cached.checkedAt < LISTING_TTL_MS
              ) {
                cached
              } else {
                val entries = directory.listFiles() ?: continue
                val children = ArrayList<File>()
                val videos = ArrayList<File>()
                for (entry in entries) {
                  currentCoroutineContext().ensureActive()
                  if (entry.name.startsWith('.')) continue
                  if (entry.isDirectory) {
                    children.add(entry)
                  } else if (FileTypeUtils.isVideoFile(entry)) {
                    videos.add(entry)
                  }
                }
                DirectoryListing(stat.lastModifiedMs, now, children, videos).also { directories[path] = it }
              }
            listing.children.forEach(queue::addLast)
            for (file in listing.videos) {
              currentCoroutineContext().ensureActive()
              val filePath = File(path, file.name).path
              if (filePath in indexed) continue
              val fileStat = readFileEntryStat(file) ?: continue
              if (!fileStat.isFile || fileStat.size <= 0L || !file.canRead()) continue
              found[filePath] = toVideo(filePath, fileStat)
            }
            // Keep IO cooperative on slower FUSE storage, and publish discoveries without waiting
            // for unrelated directories. Retain old entries until the complete pass can prune them.
            if (++processed % 32 == 0) delay(10)
            if (now - lastPublish >= 1_500L && found.isNotEmpty()) {
              publish(context, (snapshot.videos.associateBy { it.path } + found).values.toList())
              lastPublish = now
            }
          }
          currentCoroutineContext().ensureActive()
          directories.keys.retainAll(visited)
          val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          val invalidateSavedFallback = !completedFirstPass && prefs.getBoolean(HAD_UNINDEXED, false)
          publish(context, found.values.toList(), invalidateSavedFallback)
          completedFirstPass = true
          prefs.edit().putBoolean(HAD_UNINDEXED, found.isNotEmpty()).apply()
          discovered = true
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.w(TAG, "Could not reconcile unindexed videos", error)
        }
      }
      // System scanning can be slow. Release the discovery lock first so another refresh can
      // publish newly arrived files without waiting for metadata indexing of an earlier batch.
      if (discovered) {
        indexingMutex.withLock {
          try {
            val videos = snapshot.videos
            scanRequests.keys.retainAll(videos.mapTo(hashSetOf()) { it.path })
            requestIndexing(context, videos)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            Log.w(TAG, "Could not request video indexing", error)
          }
        }
      }
    }

  private suspend fun readIndexedPaths(context: Context): Set<String>? {
    val paths = hashSetOf<String>()
    val canonicalParents = HashMap<String, String>()
    context.contentResolver
      .query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media.DATA),
        null,
        null,
        null,
      )?.use { cursor ->
        while (cursor.moveToNext()) {
          currentCoroutineContext().ensureActive()
          val path = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
          // Resolve aliases once per parent; preserve case for actual filesystem access.
          val parent = File(path).parentFile ?: continue
          val canonicalParent =
            canonicalParents.getOrPut(parent.path) {
              runCatching { parent.canonicalPath }.getOrDefault(parent.absolutePath)
            }
          val canonical = File(canonicalParent, File(path).name).path
          paths.add(canonical)
        }
      } ?: return null
    return paths
  }

  private fun publish(
    context: Context,
    videos: List<Video>,
    force: Boolean = false,
  ) {
    val next = videos.sortedBy { it.path }
    if (!force && next == snapshot.videos) return
    // Also mark partial publications: a cancelled pass may already have populated a saved list.
    if (next.isNotEmpty()) {
      context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(HAD_UNINDEXED, true)
        .apply()
    }
    snapshot = Snapshot(next, next.groupBy { storagePathKey(it.bucketId).orEmpty() })
    // A mode may not have a collector yet. Invalidate its saved list as well as the in-memory
    // folder snapshot; the MediaStore generation does not change for these filesystem events.
    MediaLibraryCache.clear(context)
    MediaStoreGenerationGuard.invalidate(context)
    MediaLibraryEvents.notifyChanged()
  }

  private suspend fun requestIndexing(
    context: Context,
    videos: List<Video>,
  ) {
    if (videos.isEmpty()) return
    // Do not index a file that is visibly still growing after discovery.
    delay(300L)
    val now = SystemClock.elapsedRealtime()
    val pending =
      videos.filter { video ->
        val previous = scanRequests[video.path]
        val due =
          previous == null ||
            previous.size != video.size ||
            previous.modified != video.dateModified ||
            now - previous.requestedAt >= SCAN_RETRY_MS
        val stat = if (due) readFileEntryStat(File(video.path)) else null
        stat != null && stat.isFile && stat.size == video.size && stat.lastModifiedMs / 1000L == video.dateModified
      }
    for (batch in pending.chunked(32)) {
      currentCoroutineContext().ensureActive()
      val completed = CompletableDeferred<Unit>()
      val remaining = AtomicInteger(batch.size)
      val scanContext = currentCoroutineContext()
      batch.forEach { scanRequests[it.path] = ScanRequest(it.size, it.dateModified, now) }
      MediaScannerConnection.scanFile(context.applicationContext, batch.map { it.path }.toTypedArray(), null) { _, _ ->
        if (remaining.decrementAndGet() == 0) {
          // Refresh after completion, not just immediately after scheduling an asynchronous scan.
          if (scanContext.isActive) MediaLibraryEvents.notifyChanged()
          completed.complete(Unit)
        }
      }
      // A broken vendor scanner must not hold the discovery lock forever.
      if (withTimeoutOrNull(10_000L) { completed.await() } == null) break
    }
  }

  private fun toVideo(
    path: String,
    stat: FileEntryStat,
  ): Video {
    val file = File(path)
    val parent = file.parent.orEmpty()
    return Video(
      id = path.hashCode().toLong(),
      title = file.nameWithoutExtension,
      displayName = file.name,
      path = path,
      uri = Uri.fromFile(file),
      duration = 0L,
      durationFormatted = "",
      size = stat.size,
      sizeFormatted = VideoScanUtils.formatFileSize(stat.size),
      dateModified = stat.lastModifiedMs / 1000L,
      dateAdded = stat.lastModifiedMs / 1000L,
      mimeType = FileTypeUtils.getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)),
      bucketId = parent,
      bucketDisplayName = leafStorageName(parent),
      width = 0,
      height = 0,
      fps = 0f,
      resolution = "--",
    )
  }
}
