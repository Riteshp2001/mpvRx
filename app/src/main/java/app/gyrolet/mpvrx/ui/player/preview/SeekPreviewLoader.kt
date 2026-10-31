/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import app.gyrolet.mpvrx.network.SharedHttpClient
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class SeekPreviewFrame(val second: Int = -1, val bitmap: Bitmap? = null, val loading: Boolean = false)

/** One conflated worker per media item, and at most one native preview decode across item changes. */
internal class SeekPreviewLoader(context: Context, private val generation: Long) {
  private val context = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val requests = Channel<Pair<Long, Int>>(Channel.CONFLATED)
  private val serial = AtomicLong()
  private val mutableFrame = MutableStateFlow(SeekPreviewFrame())
  val frame = mutableFrame.asStateFlow()
  private val cache = object : LruCache<Int, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
  }
  @Volatile private var closed = false
  @Volatile private var proxy: SeekPreviewHttpProxy? = null
  @Volatile private var imageCall: Call? = null
  private var source: SeekPreviewSource? = null
  private var sourceResolved = false
  private var nativePath: String? = null
  private var sheetUrl: String? = null
  private var sheetBitmap: Bitmap? = null
  @Volatile private var failures = 0
  private val client = SharedHttpClient.derive {
    connectTimeout(5, TimeUnit.SECONDS)
    readTimeout(5, TimeUnit.SECONDS)
    callTimeout(8, TimeUnit.SECONDS)
  }

  init {
    scope.launch {
      for (request in requests) {
        // Sample the latest target at a bounded rate instead of starving while a finger moves.
        delay(90)
        var latest = request
        while (true) latest = requests.tryReceive().getOrNull() ?: break
        val (id, second) = latest
        if (!isCurrent(id)) continue
        val cached = cache.get(second)
        val bitmap = cached ?: decodeLock.withLock {
          if (!isCurrent(id) || failures >= 3) return@withLock null
          runCatching {
            if (!sourceResolved) {
              source = PlaybackSession.seekPreviewInput(generation)?.resolve()
              sourceResolved = true
            }
            source?.let { decode(it, second) }
          }.getOrNull()
        }
        if (closed) break
        if (bitmap != null) {
          cache.put(second, bitmap)
          failures = 0
        } else if (isCurrent(id)) failures++
        if (isCurrent(id)) mutableFrame.value = SeekPreviewFrame(second, bitmap)
      }
      cache.evictAll()
      sheetBitmap = null
    }
  }

  fun request(position: Float) {
    if (closed || !position.isFinite()) return
    val second = position.coerceAtLeast(0f).toInt()
    if (mutableFrame.value.second == second) return
    val id = serial.incrementAndGet()
    val cached = cache.get(second)
    mutableFrame.value = SeekPreviewFrame(second, cached, loading = cached == null)
    if (cached == null) requests.trySend(id to second)
  }

  fun hide() {
    serial.incrementAndGet()
    failures = 0
    mutableFrame.value = SeekPreviewFrame()
  }

  fun close() {
    closed = true
    hide()
    imageCall?.cancel()
    proxy?.close()
    requests.close()
    scope.cancel()
    cache.evictAll()
    // Do not recycle published bitmaps: Compose may still be drawing the previous frame.
    sheetBitmap = null
  }

  private fun isCurrent(id: Long): Boolean =
    !closed && serial.get() == id && PlaybackSession.isCurrentGeneration(generation)

  private fun decode(source: SeekPreviewSource, second: Int): Bitmap? {
    source.storyboard?.let { board ->
      runCatching { storyboardFrame(board, second) }.getOrNull()?.let { return it }
    }
    if (closed || source.path.isBlank()) return null
    val uri = Uri.parse(source.path)
    // Content URIs need Android's permission-aware resolver, not FFmpeg's URL protocols.
    if (uri.scheme == "content") {
      FastThumbnails.initialize(context)
      val native = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
          FastThumbnails.generate("/proc/self/fd/${descriptor.fd}", second.toDouble(), 320, useHwDec = false)
        }
      }.getOrNull()
      if (native != null) return rotate(native, source.rotation)
      val retriever = MediaMetadataRetriever()
      return try {
        retriever.setDataSource(context, uri)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
          retriever.getScaledFrameAtTime(second * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
        } else {
          retriever.getFrameAtTime(second * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
            val scale = minOf(320f / frame.width, 180f / frame.height, 1f)
            Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
              .also { if (it !== frame) frame.recycle() }
          }
        }
      } finally { retriever.release() }
    }
    val path = nativePath ?: when {
      isPreviewHttpUrl(source.path) -> {
        val relay = SeekPreviewHttpProxy(source.headers)
        proxy = relay
        if (closed) { relay.close(); return null }
        relay.url(source.path)
      }
      uri.scheme == "file" -> uri.path ?: return null
      else -> source.path
    }.also { nativePath = it }
    FastThumbnails.initialize(context)
    val bitmap = FastThumbnails.generate(path, second.toDouble(), 320, useHwDec = false) ?: return null
    return rotate(bitmap, source.rotation)
  }

  private fun rotate(bitmap: Bitmap, rotation: Int): Bitmap {
    if (rotation % 360 == 0) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
      .also { if (it !== bitmap) bitmap.recycle() }
  }

  private fun storyboardFrame(board: Storyboard, second: Int): Bitmap? {
    val (sheet, index) = board.frameAt(second.toDouble()) ?: return null
    val bitmap = if (sheetUrl == sheet.url) sheetBitmap else null
    val image = bitmap ?: run {
      val builder = Request.Builder().url(sheet.url)
      board.headers.forEach { (name, value) -> builder.header(name, value) }
      val call = client.newCall(builder.build())
      imageCall = call
      if (closed) { call.cancel(); return null }
      val bytes = try {
        call.execute().use { response ->
          if (!response.isSuccessful) return null
          response.body.byteStream().readBytesLimited(8 * 1024 * 1024)
        }
      } finally { imageCall = null }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      // Retain one downsampled sheet, capped near 8 MiB, even on low-memory devices.
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 16384 || bounds.outHeight > 16384) return null
      val options = BitmapFactory.Options()
      while (bounds.outWidth.toLong() * bounds.outHeight / (options.inSampleSize.coerceAtLeast(1).toLong() * options.inSampleSize.coerceAtLeast(1)) > 2_000_000) {
        options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
      }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also {
        sheetUrl = sheet.url
        sheetBitmap = it
      } ?: return null
    }
    val width = image.width / board.columns
    val height = (board.frameHeight.toLong() * width / board.frameWidth).toInt()
    if (width <= 0 || height <= 0) return null
    val x = index % board.columns * width
    val y = index / board.columns * height
    if (x + width > image.width || y + height > image.height) return null
    return Bitmap.createBitmap(image, x, y, width, height)
  }

  companion object {
    private val decodeLock = Mutex()
  }
}
