package app.gyrolet.mpvrx.domain.seekpreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import app.gyrolet.mpvrx.utils.memory.BitmapMemoryCache
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class SeekPreviewFrame(
  val targetMs: Long? = null,
  val frameMs: Long? = null,
  val bitmap: Bitmap? = null,
  val loading: Boolean = false,
)

internal class SeekPreviewStore(
  private val context: Context,
  private val source: String,
  private val headers: Map<String, String>,
  private val identity: String,
  private val durationMs: Long,
  private val isCurrent: () -> Boolean,
  private val playbackNeedsPriority: () -> Boolean,
) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val closed = AtomicBoolean(false)
  private val lock = Any()
  private val memory = BitmapMemoryCache<Long>(heapDivisor = 32, maxSizeMiB = 12)
  private val failures = LruCache<Long, Long>(64)
  private val diskTimes = ConcurrentSkipListSet<Long>()
  private val requests = Channel<Long>(Channel.CONFLATED)
  private val diskRequests = Channel<Long>(Channel.CONFLATED)
  private val _frame = MutableStateFlow(SeekPreviewFrame())
  val frame = _frame.asStateFlow()
  private val _cachedTimes = MutableStateFlow<List<Long>>(emptyList())
  val cachedTimes = _cachedTimes.asStateFlow()
  @Volatile private var target: Long? = null
  @Volatile private var bridge: SeekPreviewHttpBridge? = null
  private var retriever: MediaMetadataRetriever? = null
  private val network = source.startsWith("http://", true) || source.startsWith("https://", true)
  private val root = File(context.cacheDir, "seek_previews/v1")
  private lateinit var directory: File

  init {
    scope.launch {
      diskLock.withLock {
        val sourceUri = Uri.parse(source)
        val file = when (sourceUri.scheme?.lowercase()) {
          null -> File(source)
          "file" -> sourceUri.path?.let(::File)
          else -> null
        }
        val version = "$identity|${file?.length()}|${file?.lastModified()}|${headers.toSortedMap()}|${SeekPreviewPolicy.FRAME_DIMENSION}"
        val key = MessageDigest.getInstance("SHA-256").digest(version.toByteArray())
          .joinToString("") { "%02x".format(it) }
        directory = File(root, key)
        root.walkTopDown().filter { it.isFile && it.extension == "tmp" }.forEach { it.delete() }
        trimDisk()
        directory.listFiles()?.forEach { entry ->
          if (entry.extension == "jpg") entry.nameWithoutExtension.toLongOrNull()?.let(diskTimes::add)
        }
        publishCoverage()
      }
      launch { readCachedFrames() }
      decodeFrames()
    }
  }

  fun request(timeMs: Long?) {
    if (!active()) return
    synchronized(lock) {
      if (target == timeMs) return
      target = timeMs
      val cached = timeMs?.let(::nearbyMemoryFrame)
      _frame.value = SeekPreviewFrame(
        targetMs = timeMs,
        frameMs = cached?.first,
        bitmap = cached?.second,
        loading = timeMs != null,
      )
    }
    if (timeMs == null) {
      bridge?.cancelReads()
      return
    }
    diskRequests.trySend(timeMs)
    requests.trySend(timeMs)
  }

  private fun active(): Boolean = !closed.get() && isCurrent()

  private fun nearbyMemoryFrame(timeMs: Long): Pair<Long, Bitmap>? {
    val snapshot = memory.snapshot()
    val nearest = SeekPreviewPolicy.nearestTimeMs(timeMs, snapshot.keys.sorted(), SeekPreviewPolicy.NEARBY_TOLERANCE_MS)
      ?: return null
    return snapshot[nearest]?.let { nearest to it }
  }

  private fun nearestDiskTime(timeMs: Long, tolerance: Long): Long? =
    listOfNotNull(diskTimes.floor(timeMs), diskTimes.ceiling(timeMs))
      .minByOrNull { abs(it - timeMs) }
      ?.takeIf { abs(it - timeMs) <= tolerance }

  private suspend fun readCachedFrames() {
    for (requested in diskRequests) {
      if (!active()) return
      if (target != requested) continue
      val cachedAt = nearestDiskTime(requested, SeekPreviewPolicy.NEARBY_TOLERANCE_MS) ?: continue
      val bitmap = memory.get(cachedAt) ?: readDisk(cachedAt) ?: continue
      publish(cachedAt, bitmap)
    }
  }

  private suspend fun decodeFrames() {
    try {
      for (requested in requests) {
        if (!active()) return
        delay(if (network) 120L else 40L)
        if (target != requested) continue
        val cachedAt = nearestDiskTime(requested, SeekPreviewPolicy.EXACT_TOLERANCE_MS)
        val cached = synchronized(lock) { nearbyMemoryFrame(requested) }
          ?.takeIf { abs(it.first - requested) <= SeekPreviewPolicy.EXACT_TOLERANCE_MS }
          ?: cachedAt?.let { at -> (memory.get(at) ?: readDisk(at))?.let { at to it } }
        if (cached != null) {
          publish(cached.first, cached.second)
          finishRequest(requested)
          continue
        }
        val failureAt = failures.get(requested)
        if (failureAt != null && SystemClock.elapsedRealtime() - failureAt < 5_000L) {
          finishRequest(requested)
          continue
        }
        while (network && active() && target == requested && playbackNeedsPriority()) delay(100L)
        if (!active() || target != requested) continue
        val bitmap = try {
          decoderLock.withLock {
            if (!active() || target != requested) null else extract(requested)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          null
        }
        if (!active()) return
        if (bitmap == null) {
          failures.put(requested, SystemClock.elapsedRealtime())
        } else {
          publish(requested, bitmap)
          persist(requested, bitmap)
        }
        finishRequest(requested)
      }
    } finally {
      retriever?.let { runCatching { it.release() } }
      retriever = null
      bridge?.close()
      bridge = null
    }
  }

  private suspend fun extract(timeMs: Long): Bitmap? {
    val decodeTimeMs = timeMs.coerceAtMost((durationMs - 1L).coerceAtLeast(0L))
    if (Uri.parse(source).scheme == "content") return extractContentFrame(decodeTimeMs)
    val path = if (network) {
      bridge?.close()
      SeekPreviewHttpBridge(source, headers).also { bridge = it }.url
    } else if (source.startsWith("file://", true)) Uri.parse(source).path ?: return null else source
    return try {
      FastThumbnails.generateAsync(path, decodeTimeMs / 1_000.0, SeekPreviewPolicy.FRAME_DIMENSION, useHwDec = true)
        ?: if (active() && target == timeMs) {
          FastThumbnails.generateAsync(path, decodeTimeMs / 1_000.0, SeekPreviewPolicy.FRAME_DIMENSION, useHwDec = false)
        } else null
    } finally {
      if (network) {
        bridge?.close()
        bridge = null
      }
    }
  }

  private fun extractContentFrame(timeMs: Long): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    val decoder = retriever ?: MediaMetadataRetriever().also {
      try {
        it.setDataSource(context, Uri.parse(source))
      } catch (error: Exception) {
        it.release()
        throw error
      }
      retriever = it
    }
    val width = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
    val height = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
    val ratio = SeekPreviewPolicy.FRAME_DIMENSION.toDouble() / maxOf(width, height).coerceAtLeast(1)
    val bitmap = decoder.getScaledFrameAtTime(
      timeMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST,
      (width * ratio).roundToInt().coerceAtLeast(1), (height * ratio).roundToInt().coerceAtLeast(1),
    ) ?: return null
    val rotation = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    if (rotation == 0) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
      .also { if (it !== bitmap) bitmap.recycle() }
  }

  private fun publish(timeMs: Long, bitmap: Bitmap) {
    synchronized(lock) {
      if (!active()) return
      memory.put(timeMs, bitmap)
      val requested = target ?: return
      val nearest = nearbyMemoryFrame(requested)
      _frame.value = _frame.value.copy(
        frameMs = nearest?.first,
        bitmap = nearest?.second,
        loading = _frame.value.loading && (nearest == null || abs(nearest.first - requested) > SeekPreviewPolicy.EXACT_TOLERANCE_MS),
      )
    }
  }

  private fun finishRequest(requested: Long) {
    synchronized(lock) {
      if (active() && target == requested) _frame.update { it.copy(loading = false) }
    }
  }

  private fun readDisk(timeMs: Long): Bitmap? {
    val file = File(directory, "$timeMs.jpg")
    return BitmapFactory.decodeFile(file.path).also { bitmap ->
      if (bitmap == null) diskTimes.remove(timeMs) else file.setLastModified(System.currentTimeMillis())
    }
  }

  private suspend fun persist(timeMs: Long, bitmap: Bitmap) = diskLock.withLock {
    if (!active()) return@withLock
    if (!directory.isDirectory && !directory.mkdirs()) return@withLock
    val temporary = File(directory, "$timeMs.tmp")
    try {
      temporary.outputStream().use { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)) return@withLock
      }
      if (active() && temporary.renameTo(File(directory, "$timeMs.jpg"))) {
        diskTimes.add(timeMs)
        trimDisk()
        publishCoverage()
      }
    } catch (_: Exception) {
      Unit
    } finally {
      temporary.delete()
    }
  }

  private fun publishCoverage() {
    _cachedTimes.value = diskTimes.toList()
  }

  private fun trimDisk() {
    val entries = root.walkTopDown().filter { it.isFile && it.extension == "jpg" }.sortedBy(File::lastModified).toList()
    var bytes = entries.sumOf(File::length)
    var count = entries.size
    for (entry in entries) {
      if (bytes <= MAX_DISK_BYTES && count <= MAX_DISK_FRAMES) break
      val size = entry.length()
      if (entry.delete()) {
        bytes -= size
        count--
        if (::directory.isInitialized && entry.parentFile == directory) {
          entry.nameWithoutExtension.toLongOrNull()?.let(diskTimes::remove)
        } else {
          entry.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        }
      }
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    target = null
    bridge?.cancelReads()
    scope.cancel()
    synchronized(lock) {
      memory.evictAll()
      _frame.value = SeekPreviewFrame()
      _cachedTimes.value = emptyList()
    }
  }

  private companion object {
    val decoderLock = Mutex()
    val diskLock = Mutex()
    const val MAX_DISK_BYTES = 64L * 1024L * 1024L
    const val MAX_DISK_FRAMES = 1_024
  }
}