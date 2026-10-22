package app.gyrolet.mpvrx.desktop.mpv

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.gyrolet.mpvrx.core.history.TextStorage
import app.gyrolet.mpvrx.core.media.MediaMetadata
import app.gyrolet.mpvrx.ui.platform.MediaInfoProvider
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Generates thumbnails and probe metadata with a dedicated, silent mpv instance so the
 * library never disturbs the main player. Results are cached on disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MpvMediaInfoProvider(
  private val lib: LibMpv?,
  private val thumbnailDir: File,
  private val metadataStorage: TextStorage,
) : MediaInfoProvider {
  private val json = Json { ignoreUnknownKeys = true }
  private val metadataSerializer = MapSerializer(String.serializer(), MediaMetadata.serializer())
  private val metadata = ConcurrentHashMap<String, MediaMetadata>()
  private val memoryThumbnails = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 300
  }
  private val probeLock = Mutex()
  private val dispatcher = Dispatchers.IO.limitedParallelism(1)
  private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var probe: Probe? = null

  init {
    metadataStorage.read(METADATA_FILE)?.let { raw ->
      runCatching { json.decodeFromString(metadataSerializer, raw) }.getOrNull()?.let(metadata::putAll)
    }
  }

  override fun cachedMetadata(path: String): MediaMetadata? = metadata[key(path)]

  override suspend fun metadata(path: String): MediaMetadata? {
    cachedMetadata(path)?.let { return it }
    probeFile(path, null)
    return cachedMetadata(path)
  }

  override suspend fun thumbnail(path: String, widthPx: Int): ImageBitmap? {
    val key = key(path)
    synchronized(memoryThumbnails) { memoryThumbnails[key] }?.let { return it }
    val file = File(thumbnailDir, "$key.jpg")
    val image =
      withContext(Dispatchers.IO) {
        if (file.isFile) {
          runCatching { Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        } else {
          null
        }
      } ?: probeFile(path, widthPx)?.let { encoded ->
        withContext(Dispatchers.IO) {
          file.writeBytes(encoded)
          Image.makeFromEncoded(encoded).toComposeImageBitmap()
        }
      }
    if (image != null) synchronized(memoryThumbnails) { memoryThumbnails[key] = image }
    return image
  }

  /** Probes [path]; when [thumbnailWidth] is set also returns an encoded JPEG frame. */
  private suspend fun probeFile(path: String, thumbnailWidth: Int?): ByteArray? =
    withContext(dispatcher) {
      probeLock.withLock {
        val api = lib ?: return@withLock null
        val instance = probe ?: runCatching { Probe(api) }.getOrNull()?.also { probe = it } ?: return@withLock null
        val result =
          runCatching { instance.run(path, thumbnailWidth) }
            .onFailure { System.err.println("mpvRx: media probe failed for $path: $it") }
            .getOrNull()
        if (result == null) {
          // A failed probe can leave mpv in an odd state; start fresh next time.
          instance.release()
          probe = null
          return@withLock null
        }
        result.metadata?.let {
          metadata[key(path)] = it
          persistMetadata()
        }
        result.thumbnail
      }
    }

  private fun persistMetadata() {
    val snapshot = HashMap(metadata)
    saveScope.launch { metadataStorage.write(METADATA_FILE, json.encodeToString(metadataSerializer, snapshot)) }
  }

  fun release() {
    probe?.release()
    probe = null
  }

  private fun key(path: String): String {
    val file = File(path)
    val identity = "$path|${file.length()}|${file.lastModified()}"
    return MessageDigest.getInstance("SHA-1").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
  }

  private class ProbeResult(
    val metadata: MediaMetadata?,
    val thumbnail: ByteArray?,
  )

  private class Probe(private val api: LibMpv) {
    private val handle: Pointer = api.mpv_create() ?: error("mpv_create failed")
    private val frames = Semaphore(0)
    private val callback = LibMpv.RenderUpdateCallback { frames.release() }
    private val render: SoftwareRenderContext

    init {
      listOf(
        "vo" to "libmpv",
        "ao" to "null",
        "aid" to "no",
        "sid" to "no",
        "pause" to "yes",
        "idle" to "yes",
        "config" to "no",
        "terminal" to "no",
        "ytdl" to "no",
        "hwdec" to "no",
        "hr-seek" to "no",
        "load-scripts" to "no",
        "vd-lavc-skiploopfilter" to "all",
        "vd-lavc-threads" to "2",
      ).forEach { (k, v) -> api.mpv_set_option_string(handle, k, v) }
      check(api.mpv_initialize(handle) >= 0)
      render = SoftwareRenderContext.create(api, handle)
      render.setUpdateCallback(callback)
    }

    fun run(path: String, thumbnailWidth: Int?): ProbeResult {
      frames.drainPermits()
      // Discard END_FILE(stop) and other leftovers from the previous probe.
      while (api.mpv_wait_event(handle, 0.0).getInt(MpvLayout.EVENT_ID) != 0) continue
      api.mpv_set_property_string(handle, "start", if (thumbnailWidth != null) "20%" else "0")
      check(api.mpv_command(handle, arrayOf("loadfile", path, "replace", null)) >= 0)
      val deadline = System.currentTimeMillis() + 10_000
      var loaded = false
      var restarted = thumbnailWidth == null
      while (System.currentTimeMillis() < deadline && !(loaded && restarted)) {
        val event = api.mpv_wait_event(handle, 0.5)
        when (event.getInt(MpvLayout.EVENT_ID)) {
          MpvEventId.FILE_LOADED -> loaded = true
          MpvEventId.PLAYBACK_RESTART -> restarted = true
          MpvEventId.END_FILE -> {
            val reason = event.getPointer(MpvLayout.EVENT_DATA)?.getInt(0)
            if (reason == MpvEndReason.ERROR) error("cannot open")
          }
        }
      }
      check(loaded)
      val meta =
        MediaMetadata(
          durationSeconds = property("duration")?.toDoubleOrNull() ?: 0.0,
          width = (property("current-tracks/video/demux-w") ?: property("width"))?.toIntOrNull() ?: 0,
          height = (property("current-tracks/video/demux-h") ?: property("height"))?.toIntOrNull() ?: 0,
          videoCodec = property("current-tracks/video/codec") ?: property("video-format"),
          audioCodec = property("current-tracks/audio/codec") ?: property("audio-codec-name"),
          title = property("metadata/by-key/title"),
          artist = property("metadata/by-key/artist"),
          album = property("metadata/by-key/album"),
        )
      var encoded: ByteArray? = null
      if (thumbnailWidth != null && restarted) {
        val dw = property("dwidth")?.toIntOrNull() ?: meta.width
        val dh = property("dheight")?.toIntOrNull() ?: meta.height
        if (dw > 0 && dh > 0) {
          val width = thumbnailWidth.coerceIn(64, 640)
          val height = (width.toLong() * dh / dw).toInt().coerceAtLeast(1)
          frames.tryAcquire(2, TimeUnit.SECONDS)
          render.update()
          val image = checkNotNull(render.render(width, height)) { "render failed at ${width}x$height" }
          image.use { encoded = checkNotNull(it.encodeToData(EncodedImageFormat.JPEG, 82)) { "JPEG encode failed" }.bytes }
        }
      }
      api.mpv_command(handle, arrayOf("stop", null))
      return ProbeResult(meta, encoded)
    }

    private fun property(name: String): String? {
      val pointer = api.mpv_get_property_string(handle, name) ?: return null
      return try {
        pointer.getString(0, "UTF-8").takeIf(String::isNotBlank)
      } finally {
        api.mpv_free(pointer)
      }
    }

    fun release() {
      runCatching { render.release() }
      api.mpv_terminate_destroy(handle)
    }
  }

  private companion object {
    const val METADATA_FILE = "media_metadata.json"
  }
}
