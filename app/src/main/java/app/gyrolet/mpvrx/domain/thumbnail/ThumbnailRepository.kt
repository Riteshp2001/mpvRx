package app.gyrolet.mpvrx.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import app.gyrolet.mpvrx.domain.media.model.Video
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ThumbnailRepository(
  private val context: Context,
  private val imageLoader: ImageLoader,
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
  private val ongoingOperations = ConcurrentHashMap<String, Deferred<Bitmap?>>()
  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val maxConcurrentFolders = 3

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

      val deferred =
        async {
          try {
            getCachedThumbnail(video, widthPx, heightPx)?.let { cached ->
              synchronized(memoryCache) {
                memoryCache.put(key, cached)
              }
              _thumbnailReadyKeys.tryEmit(key)
              return@async cached
            }

            // Coil's video thumbnail decoder only supports file/content sources. For network videos,
            // extract a frame directly via MediaMetadataRetriever's HTTP data source.
            if (isHttpUrl(video.path)) {
              getOrCreateNetworkVideoThumbnail(video, widthPx, heightPx)?.let { bmp ->
                synchronized(memoryCache) { memoryCache.put(key, bmp) }
                _thumbnailReadyKeys.tryEmit(key)
                return@async bmp
              }
            }

            val result =
              runCatching {
                imageLoader.execute(buildRequest(video))
              }.getOrNull() as? SuccessResult ?: return@async null

            val bitmap = scaleBitmap(result.image.toBitmap(), widthPx, heightPx)
            synchronized(memoryCache) {
              memoryCache.put(key, bitmap)
            }
            _thumbnailReadyKeys.tryEmit(key)
            bitmap
          } finally {
            ongoingOperations.remove(key)
          }
        }

      ongoingOperations[key] = deferred
      deferred.await()
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

      val snapshot = imageLoader.diskCache?.openSnapshot(diskCacheKey(video)) ?: return@withContext null
      snapshot.use {
        val decoded =
          runCatching {
            BitmapFactory.decodeStream(it.data.toFile().inputStream())
          }.getOrNull() ?: return@withContext null

        val scaled = scaleBitmap(decoded, widthPx, heightPx)
        synchronized(memoryCache) {
          memoryCache.put(key, scaled)
        }
        return@withContext scaled
      }
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
    ongoingOperations.clear()
    networkThumbnailFailed.clear()

    synchronized(memoryCache) {
      memoryCache.evictAll()
    }

    imageLoader.memoryCache?.clear()
    imageLoader.diskCache?.clear()
  }

  fun startFolderThumbnailGeneration(
    folderId: String,
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ) {
    val filteredVideos =
      if (appearancePreferences.showNetworkThumbnails.get()) {
        videos
      } else {
        videos.filterNot { isNetworkUrl(it.path) }
      }

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
    val shouldRestart = existingState == null || existingState.signature != signature

    val state =
      folderStates.compute(folderId) { _, existing ->
        if (existing == null || existing.signature != signature) {
          FolderState(signature = signature, nextIndex = 0)
        } else {
          existing
        }
      }!!

    // Only cancel and restart if the signature changed (video list changed)
    // Otherwise, let the existing job continue to avoid overhead
    if (shouldRestart) {
      folderJobs.remove(folderId)?.cancel()
      folderJobs[folderId] =
        repositoryScope.launch {
          var i = state.nextIndex
          while (i < filteredVideos.size) {
            getThumbnail(filteredVideos[i], widthPx, heightPx)
            i++
            state.nextIndex = i
          }
        }
    }
  }

  fun thumbnailKey(
    video: Video,
    width: Int,
    height: Int,
  ): String = "${videoBaseKey(video)}|$width|$height|${thumbnailModeKey()}"

  fun diskCacheKey(video: Video): String = "video-thumb|${videoBaseKey(video)}|${thumbnailModeKey()}"

  private fun videoBaseKey(video: Video): String {
    if (isNetworkUrl(video.path)) {
      val base = video.path.ifBlank { video.uri.toString() }
      return "$base|network"
    }

    return "${video.size}|${video.dateModified}|${video.duration}"
  }

  private fun buildRequest(video: Video): ImageRequest =
    ImageRequest.Builder(context)
      .data(requestData(video))
      .memoryCacheKey(diskCacheKey(video))
      .diskCacheKey(diskCacheKey(video))
      .build()

  private fun requestData(video: Video): Any =
    when {
      isNetworkUrl(video.path) -> video.path
      video.uri.scheme == "content" || video.uri.scheme == "file" -> video.uri
      video.path.isNotBlank() -> File(video.path)
      else -> video.uri
    }

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

  private fun getOrCreateNetworkVideoThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    // Disk cache hit (reuses the same key as local thumbnails).
    imageLoader.diskCache?.openSnapshot(diskCacheKey(video))?.use { snapshot ->
      BitmapFactory.decodeStream(snapshot.data.toFile().inputStream())?.let { bmp ->
        return scaleBitmap(bmp, widthPx, heightPx)
      }
    }

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
      ) ?: return null

    // Write full-size (or retriever-scaled) bitmap to disk cache, then return a per-request scaled copy.
    imageLoader.diskCache?.openEditor(diskCacheKey(video))?.let { editor ->
      try {
        editor.data.toFile().outputStream().use { out ->
          rotated.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        editor.commit()
      } catch (_: Exception) {
        runCatching { editor.abort() }
      }
    }

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

        val embeddedPicture =
          if (strategy.prefersEmbeddedPicture()) {
            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }?.let { bytes ->
              BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
          } else {
            null
          }

        val timeUs =
          when (strategy) {
            ThumbnailStrategy.FirstFrame -> 0L
            is ThumbnailStrategy.FrameAtPercentage -> frameTimeMicros(retriever, strategy.percentage)
            is ThumbnailStrategy.Hybrid -> 0L
            ThumbnailStrategy.EmbeddedOrFirstFrame -> 0L
          }

        val raw =
          when (strategy) {
            ThumbnailStrategy.EmbeddedOrFirstFrame -> embeddedPicture ?: getFrameAt(retriever, timeUs, targetWidth, targetHeight)
            ThumbnailStrategy.FirstFrame -> getFrameAt(retriever, 0L, targetWidth, targetHeight)
            is ThumbnailStrategy.FrameAtPercentage -> getFrameAt(retriever, timeUs, targetWidth, targetHeight)
            is ThumbnailStrategy.Hybrid -> {
              val first = getFrameAt(retriever, 0L, targetWidth, targetHeight) ?: return@runCatching null
              if (isMostlySolid(first)) {
                first.recycle()
                getFrameAt(retriever, frameTimeMicros(retriever, strategy.percentage), targetWidth, targetHeight)
              } else {
                first
              }
            }
          } ?: return@runCatching null

        rotateBitmapIfNeeded(retriever, raw)
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

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
      "User-Agent" to "Mozilla/5.0 (Android) MpvRx",
      "Accept" to "*/*",
    )

  private fun isMostlySolid(
    bitmap: Bitmap,
    threshold: Float = 0.7f,
  ): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) {
      return false
    }

    val marginX = width / 10
    val marginY = height / 10
    val sampleAreaRight = width - marginX
    val sampleAreaBottom = height - marginY
    val gridSize = 10
    val stepX = (sampleAreaRight - marginX) / gridSize
    val stepY = (sampleAreaBottom - marginY) / gridSize

    if (stepX <= 0 || stepY <= 0) {
      return false
    }

    val sampledColors = ArrayList<Int>(gridSize * gridSize)
    for (x in 0 until gridSize) {
      for (y in 0 until gridSize) {
        val pixelX = marginX + x * stepX
        val pixelY = marginY + y * stepY
        if (pixelX in 0 until width && pixelY in 0 until height) {
          sampledColors += bitmap.getPixel(pixelX, pixelY)
        }
      }
    }

    if (sampledColors.isEmpty()) {
      return false
    }

    val referenceColor = sampledColors.first()
    val referenceR = (referenceColor shr 16) and 0xFF
    val referenceG = (referenceColor shr 8) and 0xFF
    val referenceB = referenceColor and 0xFF
    val tolerance = 30

    val similarCount =
      sampledColors.count { color ->
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        abs(r - referenceR) <= tolerance &&
          abs(g - referenceG) <= tolerance &&
          abs(b - referenceB) <= tolerance
      }

    return similarCount.toFloat() / sampledColors.size >= threshold
  }

  /**
   * Retrieve a thumbnail for a raw network file path (for use from [NetworkVideoCard]).
   * Only works for HTTP/HTTPS URLs — other protocols return null.
   * Respects the [showNetworkThumbnails] preference gate.
   */
  suspend fun getThumbnailForNetworkPath(
    path: String,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? = withContext(Dispatchers.IO) {
    if (!appearancePreferences.showNetworkThumbnails.get()) return@withContext null
    if (!isHttpUrl(path)) return@withContext null

    // Check if this network URL has previously failed all extraction strategies
    val videoKey = path.hashCode().toString()
    if (networkThumbnailFailed.containsKey(videoKey)) {
      android.util.Log.d("ThumbnailRepository", "Skipping network thumbnail (previously failed): $path")
      return@withContext null
    }

    val memKey  = "$path|network|$widthPx|$heightPx|${thumbnailModeKey()}"
    val diskKey = "video-thumb|$path|network|${thumbnailModeKey()}"

    // Memory cache hit
    synchronized(memoryCache) { memoryCache.get(memKey) }?.let { return@withContext it }

    // Disk cache hit
    imageLoader.diskCache?.openSnapshot(diskKey)?.use { snapshot ->
      BitmapFactory.decodeStream(snapshot.data.toFile().inputStream())?.let { bmp ->
        val scaled = scaleBitmap(bmp, widthPx, heightPx)
        synchronized(memoryCache) { memoryCache.put(memKey, scaled) }
        return@withContext scaled
      }
    }

    val strategy =
      browserPreferences.thumbnailMode.get().toThumbnailStrategy(
        browserPreferences.thumbnailFramePosition.get(),
      )

    // Extract directly via MediaMetadataRetriever HTTP streaming (efficient — only seeks header bytes)
    val bitmap =
      extractNetworkVideoFrame(
        url = path,
        strategy = strategy,
        targetWidth = widthPx.takeIf { it > 0 },
        targetHeight = heightPx.takeIf { it > 0 },
      )?.let { scaleBitmap(it, widthPx, heightPx) }

    if (bitmap == null) {
      android.util.Log.w("ThumbnailRepository", "All strategies failed for network stream $path")
      networkThumbnailFailed[videoKey] = true
      return@withContext null
    }

    // Write to disk cache
    imageLoader.diskCache?.openEditor(diskKey)?.let { editor ->
      try {
        editor.data.toFile().outputStream().use { out ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        editor.commit()
      } catch (_: Exception) {
        runCatching { editor.abort() }
      }
    }

    synchronized(memoryCache) { memoryCache.put(memKey, bitmap) }
    _thumbnailReadyKeys.tryEmit(memKey)
    bitmap
  }

  /** The memory-cache key used by [getThumbnailForNetworkPath]. */
  fun thumbnailKeyForNetworkPath(path: String, widthPx: Int, heightPx: Int): String =
    "$path|network|$widthPx|$heightPx|${thumbnailModeKey()}"

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
    md.update("$widthPx|$heightPx|${thumbnailModeKey()}|".toByteArray())
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
}


