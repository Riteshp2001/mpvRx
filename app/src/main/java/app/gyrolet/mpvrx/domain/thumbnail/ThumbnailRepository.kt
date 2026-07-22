package app.gyrolet.mpvrx.domain.thumbnail

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.preferences.ThumbnailMode
import app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.java.KoinJavaComponent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

class ThumbnailRepository(
  private val context: Context,
) {
  private val appearancePreferences by lazy {
    KoinJavaComponent.get<app.gyrolet.mpvrx.preferences.AppearancePreferences>(
      app.gyrolet.mpvrx.preferences.AppearancePreferences::class.java,
    )
  }
  private val browserPreferences by lazy {
    KoinJavaComponent.get<app.gyrolet.mpvrx.preferences.BrowserPreferences>(
      app.gyrolet.mpvrx.preferences.BrowserPreferences::class.java,
    )
  }

  private val memoryCache: LruCache<String, Bitmap>
  private val localDiskDir = File(context.filesDir, "thumbnails/local").apply { mkdirs() }
  private val networkDiskDir = File(context.filesDir, "thumbnails/network").apply { mkdirs() }
  private val diskCacheLock = ReentrantReadWriteLock()
  private val ongoingOperations = ConcurrentHashMap<String, Deferred<Bitmap?>>()
  private val diskVideoBaseKeyCache = ConcurrentHashMap<String, String>()
  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val maxConcurrentFolders = 3
  private val localGenerationParallelism = resolveLocalGenerationParallelism()
  private val localGenerationSemaphore = Semaphore(localGenerationParallelism)
  private val networkGenerationSemaphore = Semaphore(1)
  private val maxFolderBatchSize = 48

  private data class FolderState(
    val signature: String,
    @Volatile var nextIndex: Int = 0,
  )

  private val folderStates = ConcurrentHashMap<String, FolderState>()
  private val folderJobs = ConcurrentHashMap<String, Job>()

  // Track network URLs where all extraction strategies have failed – avoids endless retries while scrolling
  private val networkThumbnailFailed = ConcurrentHashMap<String, Boolean>()

  private val _thumbnailReadyKeys =
    MutableSharedFlow<String>(
      extraBufferCapacity = 256,
    )
  val thumbnailReadyKeys: SharedFlow<String> = _thumbnailReadyKeys.asSharedFlow()

  init {
    val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
    val cacheSizeKb = maxMemoryKb / 6
    memoryCache =
      object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(
          key: String,
          value: Bitmap,
        ): Int = value.byteCount / 1024
      }
  }

  suspend fun getThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      val key = thumbnailKey(video, widthPx, heightPx)

      if (isNetworkUrl(video.path) && !appearancePreferences.showNetworkThumbnails.get()) {
        return@withContext null
      }

      synchronized(memoryCache) {
        memoryCache.get(key)
      }?.let { return@withContext it }

      ongoingOperations[key]?.let { return@withContext it.await() }

      val candidate =
        async(start = CoroutineStart.LAZY) {
          getCachedThumbnail(video, widthPx, heightPx)?.let { cached ->
            synchronized(memoryCache) {
              memoryCache.put(key, cached)
            }
            _thumbnailReadyKeys.tryEmit(key)
            return@async cached
          }

          val bitmap =
            when {
              isHttpUrl(video.path) ->
                networkGenerationSemaphore.withPermit {
                  getOrCreateNetworkVideoThumbnail(video, widthPx, heightPx)
                }
              isNetworkUrl(video.path) -> null
              else ->
                localGenerationSemaphore.withPermit {
                  generateLocalThumbnail(video, widthPx, heightPx)
                }
            } ?: return@async null

          currentCoroutineContext().ensureActive()
          synchronized(memoryCache) {
            memoryCache.put(key, bitmap)
          }
          writeBitmapToDisk(diskCacheKey(video), bitmap, isNetworkUrl(video.path))
          _thumbnailReadyKeys.tryEmit(key)
          bitmap
        }

      val operation =
        ongoingOperations.putIfAbsent(key, candidate)?.also {
          candidate.cancel()
        } ?: candidate.also { owned ->
          owned.invokeOnCompletion {
            ongoingOperations.remove(key, owned)
          }
          owned.start()
        }

      operation.await()
    }

  suspend fun getCachedThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      if (isNetworkUrl(video.path) && !appearancePreferences.showNetworkThumbnails.get()) {
        return@withContext null
      }

      val key = thumbnailKey(video, widthPx, heightPx)
      synchronized(memoryCache) {
        memoryCache.get(key)
      }?.let { return@withContext it }

      val decoded = readBitmapFromDisk(diskCacheKey(video), isNetworkUrl(video.path))
        ?: return@withContext null
      val scaled = scaleBitmap(decoded, widthPx, heightPx)
      synchronized(memoryCache) {
        memoryCache.put(key, scaled)
      }
      return@withContext scaled
    }

  fun getThumbnailFromMemory(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    if (isNetworkUrl(video.path) && !appearancePreferences.showNetworkThumbnails.get()) {
      return null
    }

    val key = thumbnailKey(video, widthPx, heightPx)
    return synchronized(memoryCache) {
      memoryCache.get(key)
    }
  }

  fun clearThumbnailCache() {
    folderJobs.values.forEach { it.cancel() }
    folderJobs.clear()
    folderStates.clear()
    ongoingOperations.values.forEach { it.cancel() }
    ongoingOperations.clear()
    diskVideoBaseKeyCache.clear()
    networkThumbnailFailed.clear()

    synchronized(memoryCache) {
      memoryCache.evictAll()
    }

    diskCacheLock.write {
      runCatching { File(context.cacheDir, "thumbnails").deleteRecursively() }
      runCatching { File(context.filesDir, "thumbnails").deleteRecursively() }
      localDiskDir.mkdirs()
      networkDiskDir.mkdirs()
    }
  }

  fun startFolderThumbnailGeneration(
    folderId: String,
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ) {
    val videoSequence = videos.asSequence()
    val filteredVideos =
      (if (appearancePreferences.showNetworkThumbnails.get()) {
         videoSequence
       } else {
         videoSequence.filterNot { isNetworkUrl(it.path) }
       })
        .take(maxFolderBatchSize)
        .toList()

    if (filteredVideos.isEmpty()) {
      return
    }

    folderJobs.entries.removeAll { !it.value.isActive }

    if (folderJobs.size >= maxConcurrentFolders && !folderJobs.containsKey(folderId)) {
      folderJobs.entries.firstOrNull()?.let { (oldestId, job) ->
        job.cancel()
        folderJobs.remove(oldestId)
        folderStates.remove(oldestId)
      }
    }

    val signature = folderSignature(filteredVideos, widthPx, heightPx)
    val existingState = folderStates[folderId]
    val state =
      folderStates.compute(folderId) { _, existing ->
        if (existing == null || existing.signature != signature) {
          FolderState(signature = signature, nextIndex = 0)
        } else {
          existing
        }
      }!!

    val existingJob = folderJobs[folderId]
    val shouldRestart =
      existingState == null ||
        existingState.signature != signature ||
        (existingJob?.isActive != true && state.nextIndex < filteredVideos.size)

    // Keep an active matching batch, but resume one that was cancelled before completing.
    if (shouldRestart) {
      folderJobs.remove(folderId)?.cancel()
      folderJobs[folderId] =
        repositoryScope.launch {
          var i = state.nextIndex
          while (i < filteredVideos.size) {
            val batchEnd = (i + localGenerationParallelism).coerceAtMost(filteredVideos.size)
            coroutineScope {
              (i until batchEnd)
                .map { index ->
                  async {
                    getThumbnail(filteredVideos[index], widthPx, heightPx)
                  }
                }
                .awaitAll()
            }
            i = batchEnd
            state.nextIndex = i
            yield()
          }
        }
    }
  }

  fun cancelFolderThumbnailGeneration(folderId: String) {
    folderJobs.remove(folderId)?.cancel()
    folderStates.remove(folderId)
  }

  fun thumbnailKey(
    video: Video,
    width: Int,
    height: Int,
  ): String = "${videoBaseKey(video)}|$width|$height|${thumbnailModeKey()}|${thumbnailQualityKey()}"

  /**
   * Folder prefetch and a visible card may request different sizes for the same source.
   * The disk entry is size-independent, so either completion can wake the card and let it
   * decode the cached bitmap at its own target dimensions.
   */
  fun isThumbnailKeyForVideo(
    key: String,
    video: Video,
  ): Boolean =
    key.startsWith("${videoBaseKey(video)}|") &&
      key.endsWith("|${thumbnailModeKey()}|${thumbnailQualityKey()}")

  // Keep extraction-quality changes from reusing smaller legacy images that were
  // cached without their requested dimensions in the key.
  fun diskCacheKey(video: Video): String =
    "video-thumb-v2|${diskVideoBaseKey(video)}|${thumbnailModeKey()}|${thumbnailQualityKey()}"

  private fun videoBaseKey(video: Video): String {
    if (isNetworkUrl(video.path)) {
      val base = video.path.ifBlank { video.uri.toString() }
      return "$base|network"
    }

    val source = video.path.ifBlank { video.uri.toString() }
    return "$source|${video.size}|${video.dateModified}|${video.duration}"
  }

  /** Sidecar artwork probing is disk I/O, so keep it out of keys evaluated during composition. */
  private fun diskVideoBaseKey(video: Video): String {
    val baseKey = videoBaseKey(video)
    if (isNetworkUrl(video.path)) return baseKey
    diskVideoBaseKeyCache[baseKey]?.let { return it }

    val artworkSignature =
      EmbeddedArtworkCandidates.forVideoPath(video.path)
        .asSequence()
        .map(::File)
        .firstOrNull { it.isFile && it.canRead() }
        ?.let { artwork -> "|art:${artwork.name}:${artwork.length()}:${artwork.lastModified()}" }
        .orEmpty()
    val resolvedKey = "$baseKey$artworkSignature"
    return diskVideoBaseKeyCache.putIfAbsent(baseKey, resolvedKey) ?: resolvedKey
  }

  private suspend fun generateLocalThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    val mode = browserPreferences.thumbnailMode.get()
    val dimension = maxOf(widthPx, heightPx, MAX_THUMBNAIL_SIZE).coerceAtMost(thumbnailMaxSize())

    if (video.isAudio || mode == ThumbnailMode.Smart || mode == ThumbnailMode.EmbeddedThumbnail) {
      generateEmbeddedArtwork(video)?.let { return scaleBitmap(it, widthPx, heightPx) }
      if (video.isAudio) return null
    }

    generateWithFastThumbnails(video, mode, dimension)?.let {
      return scaleBitmap(it, widthPx, heightPx)
    }

    return extractLocalVideoFrame(video, widthPx, heightPx)
  }

  private fun generateEmbeddedArtwork(video: Video): Bitmap? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        setLocalDataSource(retriever, video)
        EmbeddedArtworkResolver.decodeEmbeddedArtwork(video.path, retriever)?.scaleToThumbnailMax(thumbnailMaxSize())
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

  private suspend fun generateWithFastThumbnails(
    video: Video,
    mode: ThumbnailMode,
    dimension: Int,
  ): Bitmap? {
    if (video.isAudio || video.path.isBlank()) return null

    val durationSeconds = video.duration.coerceAtLeast(0L) / 1000.0
    val requestedPosition = when (mode) {
      ThumbnailMode.FirstFrame, ThumbnailMode.EmbeddedThumbnail -> 0.0
      ThumbnailMode.FrameAtPosition ->
        durationSeconds * (browserPreferences.thumbnailFramePosition.get() / 100.0).coerceIn(0.0, 1.0)
      ThumbnailMode.Smart -> durationSeconds * 0.33
    }
    val lastSafePosition = (durationSeconds - 0.1).coerceAtLeast(0.0)
    val positions = when (mode) {
      ThumbnailMode.Smart -> listOf(requestedPosition, 10.0, 20.0, 30.0)
      else -> listOf(requestedPosition)
    }.map { position ->
      if (durationSeconds > 0.0) position.coerceIn(0.0, lastSafePosition) else position.coerceAtLeast(0.0)
    }.distinct()

    var lastSolidBitmap: Bitmap? = null
    for (position in positions) {
      val bitmap =
        try {
          FastThumbnails.generateAsync(
            video.path,
            position,
            dimension,
            useHwDec = false,
          )
        } catch (cancellation: CancellationException) {
          lastSolidBitmap?.takeUnless { it.isRecycled }?.recycle()
          throw cancellation
        } catch (_: Exception) {
          continue
        } ?: continue

      if (mode == ThumbnailMode.Smart && isMostlySolidThumbnail(bitmap)) {
        lastSolidBitmap?.takeUnless { it.isRecycled }?.recycle()
        lastSolidBitmap = bitmap
        continue
      }

      lastSolidBitmap?.takeUnless { it.isRecycled }?.recycle()
      return rotateNativeThumbnail(video, bitmap)
    }

    return lastSolidBitmap?.let { rotateNativeThumbnail(video, it) }
  }

  private suspend fun rotateNativeThumbnail(video: Video, bitmap: Bitmap): Bitmap {
    val rotation =
      try {
        app.gyrolet.mpvrx.utils.media.MediaInfoOps.getRotation(context, video.uri, video.displayName)
      } catch (cancellation: CancellationException) {
        bitmap.takeUnless { it.isRecycled }?.recycle()
        throw cancellation
      } catch (_: Exception) {
        0
      }
    if (rotation == 0) return bitmap

    val rotated = Bitmap.createBitmap(
      bitmap,
      0,
      0,
      bitmap.width,
      bitmap.height,
      Matrix().apply { postRotate(rotation.toFloat()) },
      true,
    )
    if (rotated !== bitmap && !bitmap.isRecycled) bitmap.recycle()
    return rotated
  }

  private fun extractLocalVideoFrame(video: Video, widthPx: Int, heightPx: Int): Bitmap? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        setLocalDataSource(retriever, video)
        extractFrameWithStrategy(
          retriever = retriever,
          strategy = browserPreferences.thumbnailMode.get().toThumbnailStrategy(
            browserPreferences.thumbnailFramePosition.get(),
          ),
          targetWidth = widthPx.takeIf { it > 0 },
          targetHeight = heightPx.takeIf { it > 0 },
          videoPath = video.path,
        )
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

  private fun setLocalDataSource(retriever: MediaMetadataRetriever, video: Video) {
    when {
      video.path.isNotBlank() && !video.path.contains("://") -> retriever.setDataSource(video.path)
      else -> retriever.setDataSource(context, video.uri)
    }
  }

  private fun readBitmapFromDisk(key: String, network: Boolean): Bitmap? =
    diskCacheLock.read {
      val file = File(if (network) networkDiskDir else localDiskDir, keyToFileName(key))
      if (!file.isFile) return@read null

      runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        BitmapFactory.decodeFile(
          file.absolutePath,
          BitmapFactory.Options().apply {
            inSampleSize = calculateThumbnailSampleSize(bounds.outWidth, bounds.outHeight, thumbnailMaxSize())
            inPreferredConfig = Bitmap.Config.RGB_565
          },
        )
      }.getOrNull()
    }

  private fun writeBitmapToDisk(key: String, bitmap: Bitmap, network: Boolean) {
    val file = File(if (network) networkDiskDir else localDiskDir, keyToFileName(key))
    val encoded =
      runCatching {
        ByteArrayOutputStream().use { buffer ->
          if (bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, buffer)) {
            buffer.toByteArray()
          } else {
            null
          }
        }
      }.getOrNull() ?: return

    diskCacheLock.write {
      runCatching {
        FileOutputStream(file).use { output ->
          output.write(encoded)
        }
      }
    }
  }

  private fun keyToFileName(key: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(key.toByteArray())
      .joinToString("") { byte -> "%02x".format(byte) } + ".jpg"

  private fun scaleBitmap(
    bitmap: Bitmap,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap {
    if (widthPx <= 0 || heightPx <= 0 || bitmap.isRecycled) {
      return bitmap
    }

    val scale = max(widthPx / bitmap.width.toFloat(), heightPx / bitmap.height.toFloat())
    if (scale >= 1f && bitmap.width <= widthPx * 2 && bitmap.height <= heightPx * 2) {
      return bitmap
    }

    val scaledWidth = max(1, (bitmap.width * scale).roundToInt())
    val scaledHeight = max(1, (bitmap.height * scale).roundToInt())
    val scaled = try {
      Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    } catch (_: IllegalArgumentException) {
      // Bitmap was recycled between the check and the scale call
      return bitmap
    }
    if (scaled != bitmap && !bitmap.isRecycled) {
      bitmap.recycle()
    }
    return scaled
  }

  private fun isNetworkUrl(path: String): Boolean =
    path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true) ||
      path.startsWith("rtmp://", ignoreCase = true) ||
      path.startsWith("rtsp://", ignoreCase = true) ||
      path.startsWith("ftp://", ignoreCase = true) ||
      path.startsWith("sftp://", ignoreCase = true) ||
      path.startsWith("smb://", ignoreCase = true)

  private fun isHttpUrl(path: String): Boolean =
    path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true)

  private suspend fun getOrCreateNetworkVideoThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    val strategy =
      browserPreferences.thumbnailMode.get().toThumbnailStrategy(
        browserPreferences.thumbnailFramePosition.get(),
      )

    val rotated =
      extractNetworkVideoFrame(
        url = video.path,
        strategy = strategy,
        targetWidth = widthPx.takeIf { it > 0 },
        targetHeight = heightPx.takeIf { it > 0 },
      ) ?: generateFastNetworkThumbnail(video.path, widthPx, heightPx) ?: return null

    return scaleBitmap(rotated, widthPx, heightPx)
  }

  private fun extractNetworkVideoFrame(
    url: String,
    strategy: ThumbnailStrategy,
    targetWidth: Int?,
    targetHeight: Int?,
  ): Bitmap? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(url, networkVideoHeaders())

        extractFrameWithStrategy(retriever, strategy, targetWidth, targetHeight)
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

  private fun extractFrameWithStrategy(
    retriever: MediaMetadataRetriever,
    strategy: ThumbnailStrategy,
    targetWidth: Int?,
    targetHeight: Int?,
    videoPath: String? = null,
  ): Bitmap? {
    val embeddedPicture =
      if (strategy.prefersEmbeddedPicture()) {
        EmbeddedArtworkResolver.decodeEmbeddedArtwork(videoPath, retriever)
      } else {
        null
      }
    val timeUs = when (strategy) {
      ThumbnailStrategy.FirstFrame -> 0L
      is ThumbnailStrategy.FrameAtPercentage -> frameTimeMicros(retriever, strategy.percentage)
      is ThumbnailStrategy.Hybrid, is ThumbnailStrategy.EmbeddedOrHybrid -> 0L
      ThumbnailStrategy.EmbeddedOrFirstFrame -> 0L
    }

    var shouldRotate = true
    val raw = when (strategy) {
      ThumbnailStrategy.EmbeddedOrFirstFrame ->
        embeddedPicture?.also { shouldRotate = false }
          ?: getFrameAt(retriever, timeUs, targetWidth, targetHeight)
      ThumbnailStrategy.FirstFrame -> getFrameAt(retriever, 0L, targetWidth, targetHeight)
      is ThumbnailStrategy.FrameAtPercentage -> getFrameAt(retriever, timeUs, targetWidth, targetHeight)
      is ThumbnailStrategy.Hybrid -> decodeHybridFrame(retriever, strategy.percentage, targetWidth, targetHeight)
      is ThumbnailStrategy.EmbeddedOrHybrid ->
        embeddedPicture?.also { shouldRotate = false }
          ?: decodeHybridFrame(retriever, strategy.percentage, targetWidth, targetHeight)
    } ?: return null

    return if (shouldRotate) rotateBitmapIfNeeded(retriever, raw) else raw
  }

  private fun decodeHybridFrame(
    retriever: MediaMetadataRetriever,
    percentage: Float,
    targetWidth: Int?,
    targetHeight: Int?,
  ): Bitmap? {
    val first = getFrameAt(retriever, 0L, targetWidth, targetHeight) ?: return null
    if (!isMostlySolidThumbnail(first)) return first
    first.recycle()
    return getFrameAt(
      retriever,
      frameTimeMicros(retriever, percentage),
      targetWidth,
      targetHeight,
    )
  }

  private fun getFrameAt(
    retriever: MediaMetadataRetriever,
    timeUs: Long,
    targetWidth: Int?,
    targetHeight: Int?,
  ): Bitmap? {
    val w = targetWidth ?: return retriever.getFrameAtTime(timeUs)
    val h = targetHeight ?: return retriever.getFrameAtTime(timeUs)
    if (w <= 0 || h <= 0) {
      return retriever.getFrameAtTime(timeUs)
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      runCatching { retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h) }.getOrNull()
        ?: retriever.getFrameAtTime(timeUs)
    } else {
      retriever.getFrameAtTime(timeUs)
    }
  }

  private fun frameTimeMicros(
    retriever: MediaMetadataRetriever,
    percentage: Float,
  ): Long {
    val durationMs =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    return (durationMs * percentage.coerceIn(0f, 1f) * 1000).toLong()
  }

  private fun rotateBitmapIfNeeded(
    retriever: MediaMetadataRetriever,
    bitmap: Bitmap,
  ): Bitmap {
    val rotation =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        ?: return bitmap
    if (rotation == 0) {
      return bitmap
    }

    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated != bitmap) {
      bitmap.recycle()
    }
    return rotated
  }

  private fun networkVideoHeaders(): Map<String, String> =
    mapOf(
      // Some servers refuse requests without a UA. MediaMetadataRetriever handles the rest.
      "User-Agent" to "Mozilla/5.0 (Android) mpvRx",
      "Accept" to "*/*",
    )

  /**
   * Retrieve a thumbnail for a raw network file path (for use from [NetworkVideoCard]).
   * For HTTP/HTTPS URLs, uses [MediaMetadataRetriever]'s built-in HTTP streaming.
   * For other protocols (SMB, FTP, WebDAV), uses [NetworkStreamingProxy] to create
   * a local HTTP stream and then extracts the frame.
   * Respects the [showNetworkThumbnails] preference gate.
   */
  suspend fun getThumbnailForNetworkPath(
    path: String,
    widthPx: Int,
    heightPx: Int,
    connection: NetworkConnection? = null,
  ): Bitmap? = withContext(Dispatchers.IO) {
    if (!appearancePreferences.showNetworkThumbnails.get()) return@withContext null

    // For non-HTTP paths (SMB, FTP, WebDAV), use the proxy to create a local HTTP stream
    if (!isHttpUrl(path)) {
      return@withContext getNonHttpNetworkThumbnail(path, connection, widthPx, heightPx)
    }

    // Check if this network URL has previously failed all extraction strategies
    val videoKey = path.hashCode().toString()
    if (networkThumbnailFailed.containsKey(videoKey)) {
      android.util.Log.d("ThumbnailRepository", "Skipping network thumbnail (previously failed): $path")
      return@withContext null
    }

    val memKey  = "$path|network|$widthPx|$heightPx|${thumbnailModeKey()}|${thumbnailQualityKey()}"
    val diskKey = "video-thumb-v2|$path|network|${thumbnailModeKey()}|${thumbnailQualityKey()}"

    // Memory cache hit
    synchronized(memoryCache) { memoryCache.get(memKey) }?.let { return@withContext it }

    // Disk cache hit
    readBitmapFromDisk(diskKey, network = true)?.let { bitmap ->
      val scaled = scaleBitmap(bitmap, widthPx, heightPx)
      synchronized(memoryCache) { memoryCache.put(memKey, scaled) }
      return@withContext scaled
    }

    val strategy =
      browserPreferences.thumbnailMode.get().toThumbnailStrategy(
        browserPreferences.thumbnailFramePosition.get(),
      )

    // Extract directly via MediaMetadataRetriever HTTP streaming (efficient — only seeks header bytes)
    val bitmap = networkGenerationSemaphore.withPermit {
      (extractNetworkVideoFrame(
        url = path,
        strategy = strategy,
        targetWidth = widthPx.takeIf { it > 0 },
        targetHeight = heightPx.takeIf { it > 0 },
      ) ?: generateFastNetworkThumbnail(path, widthPx, heightPx))
        ?.let { scaleBitmap(it, widthPx, heightPx) }
    }

    if (bitmap == null) {
      android.util.Log.w("ThumbnailRepository", "All strategies failed for network stream $path")
      networkThumbnailFailed[videoKey] = true
      return@withContext null
    }

    // Write to disk cache
    writeBitmapToDisk(diskKey, bitmap, network = true)

    synchronized(memoryCache) { memoryCache.put(memKey, bitmap) }
    _thumbnailReadyKeys.tryEmit(memKey)
    bitmap
  }

  private suspend fun getNonHttpNetworkThumbnail(
    path: String,
    connection: NetworkConnection?,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    val videoKey = path.hashCode().toString()
    if (networkThumbnailFailed.containsKey(videoKey)) {
      android.util.Log.d("ThumbnailRepository", "Skipping network thumbnail (previously failed): $path")
      return null
    }

    val memKey = "$path|network|$widthPx|$heightPx|${thumbnailModeKey()}|${thumbnailQualityKey()}"
    val diskKey = "video-thumb-v2|$path|network|${thumbnailModeKey()}|${thumbnailQualityKey()}"

    // Memory cache hit
    synchronized(memoryCache) { memoryCache.get(memKey) }?.let { return it }

    // Disk cache hit
    readBitmapFromDisk(diskKey, network = true)?.let { bitmap ->
      val scaled = scaleBitmap(bitmap, widthPx, heightPx)
      synchronized(memoryCache) { memoryCache.put(memKey, scaled) }
      return scaled
    }

    val strategy =
      browserPreferences.thumbnailMode.get().toThumbnailStrategy(
        browserPreferences.thumbnailFramePosition.get(),
      )

    val bitmap = networkGenerationSemaphore.withPermit {
      (if (connection != null) {
          extractNetworkVideoFrameViaProxy(path, connection, strategy, widthPx, heightPx)
        } else {
          generateFastNetworkThumbnail(path, widthPx, heightPx)
        })
        ?.let { scaleBitmap(it, widthPx, heightPx) }
    }

    if (bitmap == null) {
      android.util.Log.w("ThumbnailRepository", "All strategies failed for network path $path")
      networkThumbnailFailed[videoKey] = true
      return null
    }

    // Write to disk cache
    writeBitmapToDisk(diskKey, bitmap, network = true)

    synchronized(memoryCache) { memoryCache.put(memKey, bitmap) }
    _thumbnailReadyKeys.tryEmit(memKey)
    return bitmap
  }

  private suspend fun extractNetworkVideoFrameViaProxy(
    path: String,
    connection: NetworkConnection,
    strategy: ThumbnailStrategy,
    targetWidth: Int,
    targetHeight: Int,
  ): Bitmap? {
    val proxy = NetworkStreamingProxy.getInstance()
    val streamId = "thumb_${path.hashCode()}_${System.nanoTime()}"

    return try {
      val localUrl = proxy.registerStream(
        streamId = streamId,
        connection = connection,
        filePath = path,
      )

      extractNetworkVideoFrame(
        url = localUrl,
        strategy = strategy,
        targetWidth = targetWidth.takeIf { it > 0 },
        targetHeight = targetHeight.takeIf { it > 0 },
      ) ?: generateFastNetworkThumbnail(localUrl, targetWidth, targetHeight)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      null
    } finally {
      proxy.unregisterStream(streamId)
    }
  }

  /** The memory-cache key used by [getThumbnailForNetworkPath]. */
  fun thumbnailKeyForNetworkPath(path: String, widthPx: Int, heightPx: Int): String =
    "$path|network|$widthPx|$heightPx|${thumbnailModeKey()}|${thumbnailQualityKey()}"

  /**
   * Get a thumbnail for a folder using the first video in the folder.
   * Returns null if the folder has no videos or thumbnail generation fails.
   */
  suspend fun getFolderThumbnail(
    folderId: String,
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? = withContext(Dispatchers.IO) {
    if (videos.isEmpty()) return@withContext null

    // Filter out network videos if network thumbnails are disabled
    val filteredVideos =
      if (appearancePreferences.showNetworkThumbnails.get()) {
        videos
      } else {
        videos.filterNot { isNetworkUrl(it.path) }
      }

    if (filteredVideos.isEmpty()) return@withContext null

    // Use the first video as the folder thumbnail
    getThumbnail(filteredVideos.first(), widthPx, heightPx)
  }

  private fun folderSignature(
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ): String {
    val md = MessageDigest.getInstance("MD5")
    md.update("$widthPx|$heightPx|${thumbnailModeKey()}|${thumbnailQualityKey()}|".toByteArray())
    for (video in videos) {
      md.update(video.path.toByteArray())
      md.update("|".toByteArray())
      md.update(video.size.toString().toByteArray())
      md.update("|".toByteArray())
      md.update(video.dateModified.toString().toByteArray())
      md.update(";".toByteArray())
    }
    return md.digest().joinToString("") { byte -> "%02x".format(byte) }
  }

  private fun thumbnailModeKey(): String =
    browserPreferences.thumbnailMode.get().thumbnailModeCacheKey(browserPreferences.thumbnailFramePosition.get())

  private fun thumbnailQualityKey(): String = browserPreferences.thumbnailQuality.get().name

  private fun thumbnailMaxSize(): Int = browserPreferences.thumbnailQuality.get().maxSizePx

  private fun resolveLocalGenerationParallelism(): Int {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val processorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return when {
      activityManager?.isLowRamDevice == true || processorCount <= 2 -> 1
      processorCount <= 4 -> 2
      processorCount <= 6 -> 3
      else -> 4
    }
  }

  private suspend fun generateFastNetworkThumbnail(
    path: String,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    try {
      FastThumbnails.generateAsync(
        path,
        10.0,
        maxOf(widthPx, heightPx, MAX_THUMBNAIL_SIZE).coerceAtMost(thumbnailMaxSize()),
        useHwDec = false,
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      null
    }
}


