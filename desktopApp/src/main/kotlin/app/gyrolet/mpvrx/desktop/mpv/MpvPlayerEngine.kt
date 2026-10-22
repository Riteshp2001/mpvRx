package app.gyrolet.mpvrx.desktop.mpv

import app.gyrolet.mpvrx.core.media.MediaExtensions
import app.gyrolet.mpvrx.core.player.AspectMode
import app.gyrolet.mpvrx.core.player.Chapter
import app.gyrolet.mpvrx.core.player.LoadOptions
import app.gyrolet.mpvrx.core.player.MediaTrack
import app.gyrolet.mpvrx.core.player.PlayerEngine
import app.gyrolet.mpvrx.core.player.PlayerEvent
import app.gyrolet.mpvrx.core.player.PlayerState
import app.gyrolet.mpvrx.core.player.TrackType
import com.sun.jna.Pointer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Image
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min

data class MpvEngineConfig(
  val configDir: File,
  val screenshotDir: File,
  val hardwareDecoding: Boolean,
  /** Raw mpv.conf lines from settings, applied before initialization. */
  val extraOptions: String,
  val logFile: File?,
  /** Bundled yt-dlp executable; null leaves mpv searching PATH. */
  val ytdlPath: File? = null,
)

/**
 * libmpv-backed [PlayerEngine] for desktop. Video is rendered with the software render API
 * on a dedicated thread; frames are handed to Compose through [takeFrame].
 */
class MpvPlayerEngine(
  private val lib: LibMpv,
  config: MpvEngineConfig,
) : PlayerEngine {
  private val handle: Pointer = lib.mpv_create() ?: error("mpv_create returned null")
  private val render: SoftwareRenderContext
  private val closed = AtomicBoolean(false)
  private val json = Json { ignoreUnknownKeys = true }

  private val mutableState = MutableStateFlow(PlayerState())
  override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

  private val mutableEvents = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  override val events: Flow<PlayerEvent> = mutableEvents

  private val renderSignal = Semaphore(0)
  private val pendingFrame = AtomicReference<Image?>(null)
  private val mutableFrameVersion = MutableStateFlow(0L)

  /** Bumped whenever a new frame is ready; the video surface collects it. */
  val frameVersion: StateFlow<Long> = mutableFrameVersion.asStateFlow()

  @Volatile private var surfaceWidth = 0

  @Volatile private var surfaceHeight = 0

  @Volatile private var forceRender = false
  private val pendingSubtitles = AtomicReference<List<String>>(emptyList())

  // Kept as a field so JNA does not garbage-collect the native callback.
  private val updateCallback = LibMpv.RenderUpdateCallback { renderSignal.release() }

  private val eventThread: Thread
  private val renderThread: Thread

  init {
    setOption("vo", "libmpv")
    setOption("config", "yes")
    setOption("config-dir", config.configDir.absolutePath)
    setOption("terminal", "no")
    setOption("idle", "yes")
    setOption("keep-open", "no")
    setOption("osc", "no")
    setOption("osd-level", "0")
    setOption("input-default-bindings", "no")
    setOption("input-vo-keyboard", "no")
    setOption("save-position-on-quit", "no")
    setOption("audio-client-name", "mpvRx")
    setOption("ytdl", "yes")
    config.ytdlPath?.let { setOption("script-opts", "ytdl_hook-ytdl_path=${it.absolutePath}") }
    setOption("hwdec", if (config.hardwareDecoding) HW_DECODING else "no")
    setOption("screenshot-directory", config.screenshotDir.absolutePath)
    setOption("screenshot-format", "png")
    setOption("demuxer-max-bytes", "150MiB")
    setOption("demuxer-max-back-bytes", "75MiB")
    config.logFile?.let {
      setOption("log-file", it.absolutePath)
      setOption("msg-level", "all=v")
    }
    config.extraOptions
      .lineSequence()
      .map(String::trim)
      .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
      .forEach { line -> setOption(line.substringBefore('=').trim().removePrefix("--"), line.substringAfter('=').trim()) }

    check(lib.mpv_initialize(handle) >= 0) { "mpv_initialize failed" }
    render = SoftwareRenderContext.create(lib, handle)
    render.setUpdateCallback(updateCallback)
    OBSERVED.forEachIndexed { index, (name, format) -> lib.mpv_observe_property(handle, index.toLong() + 1, name, format) }
    lib.mpv_request_log_messages(handle, "error")
    mutableState.update { it.copy(isInitialized = true) }

    eventThread = Thread(::eventLoop, "mpv-events").apply { isDaemon = true; start() }
    renderThread = Thread(::renderLoop, "mpv-render").apply { isDaemon = true; start() }
  }

  fun setSurfaceSize(width: Int, height: Int) {
    if (width == surfaceWidth && height == surfaceHeight) return
    surfaceWidth = width
    surfaceHeight = height
    forceRender = true
    renderSignal.release()
  }

  /** Returns the newest frame not yet taken; the caller owns and must close it. */
  fun takeFrame(): Image? = pendingFrame.getAndSet(null)

  private fun renderLoop() {
    while (!closed.get()) {
      if (!renderSignal.tryAcquire(250, TimeUnit.MILLISECONDS)) continue
      renderSignal.drainPermits()
      if (closed.get()) break
      val flags = render.update()
      val width = min(surfaceWidth, MAX_RENDER_WIDTH)
      val height = min(surfaceHeight, MAX_RENDER_HEIGHT)
      if ((flags and MPV_RENDER_UPDATE_FRAME) == 0L && !forceRender) continue
      forceRender = false
      val image = render.render(width, height) ?: continue
      pendingFrame.getAndSet(image)?.close()
      mutableFrameVersion.update { it + 1 }
    }
  }

  private fun eventLoop() {
    while (!closed.get()) {
      val event = lib.mpv_wait_event(handle, -1.0)
      when (event.getInt(MpvLayout.EVENT_ID)) {
        MpvEventId.SHUTDOWN -> return
        MpvEventId.PROPERTY_CHANGE -> event.getPointer(MpvLayout.EVENT_DATA)?.let(::onPropertyChange)
        MpvEventId.START_FILE -> mutableState.update { it.copy(isBuffering = true, errorMessage = null) }
        MpvEventId.FILE_LOADED -> onFileLoaded()
        MpvEventId.PLAYBACK_RESTART -> mutableState.update { it.copy(isSeeking = false) }
        MpvEventId.END_FILE -> {
          val data = event.getPointer(MpvLayout.EVENT_DATA)
          val reason = data?.getInt(0) ?: -1
          val error = data?.getInt(4) ?: 0
          mutableEvents.tryEmit(
            PlayerEvent.EndOfFile(
              path = mutableState.value.path,
              reachedEnd = reason == MpvEndReason.EOF,
              error = if (reason == MpvEndReason.ERROR && error < 0) lib.mpv_error_string(error) else null,
            ),
          )
          if (reason == MpvEndReason.ERROR) {
            mutableState.update { it.copy(errorMessage = "Cannot play this file: ${lib.mpv_error_string(error)}", isBuffering = false) }
          }
        }
        MpvEventId.LOG_MESSAGE -> Unit
      }
    }
  }

  private fun onFileLoaded() {
    val path = getProperty("path").orEmpty()
    pendingSubtitles.getAndSet(emptyList()).forEach { command("sub-add", it, "auto") }
    mutableState.update { it.copy(isBuffering = false, isIdle = false) }
    mutableEvents.tryEmit(PlayerEvent.FileLoaded(path))
  }

  private fun onPropertyChange(property: Pointer) {
    val name = property.getPointer(MpvLayout.PROPERTY_NAME)?.getString(0, "UTF-8") ?: return
    val format = property.getInt(MpvLayout.PROPERTY_FORMAT)
    val data = property.getPointer(MpvLayout.PROPERTY_DATA)
    fun double() = if (format == MpvFormat.DOUBLE && data != null) data.getDouble(0) else null
    fun flag() = if (format == MpvFormat.FLAG && data != null) data.getInt(0) != 0 else null
    fun long() = if (format == MpvFormat.INT64 && data != null) data.getLong(0) else null
    fun string() = if (format == MpvFormat.STRING && data != null) data.getPointer(0)?.getString(0, "UTF-8") else null

    when (name) {
      "time-pos" -> {
        val value = double() ?: 0.0
        // Per-frame updates would recompose controls at video frame rate; quarter seconds suffice.
        if (abs(value - mutableState.value.positionSeconds) >= 0.25 || value == 0.0) {
          mutableState.update { it.copy(positionSeconds = value) }
        }
      }
      "duration" -> mutableState.update { it.copy(durationSeconds = double() ?: 0.0) }
      "pause" -> mutableState.update { it.copy(isPaused = flag() ?: true) }
      "paused-for-cache" -> mutableState.update { it.copy(isBuffering = flag() ?: false) }
      "seeking" -> mutableState.update { it.copy(isSeeking = flag() ?: false) }
      "media-title" -> mutableState.update { it.copy(mediaTitle = string().orEmpty()) }
      "volume" -> mutableState.update { it.copy(volume = (double() ?: 100.0).toInt()) }
      "volume-max" -> mutableState.update { it.copy(maxVolume = (double() ?: 130.0).toInt()) }
      "mute" -> mutableState.update { it.copy(isMuted = flag() ?: false) }
      "speed" -> mutableState.update { it.copy(speed = double() ?: 1.0) }
      "demuxer-cache-time" -> mutableState.update { it.copy(bufferedSeconds = double() ?: 0.0) }
      "hwdec-current" -> mutableState.update { it.copy(hwdecCurrent = string()) }
      "dwidth" -> mutableState.update { it.copy(videoWidth = (long() ?: 0L).toInt()) }
      "dheight" -> mutableState.update { it.copy(videoHeight = (long() ?: 0L).toInt()) }
      "sub-delay" -> mutableState.update { it.copy(subtitleDelaySeconds = double() ?: 0.0) }
      "audio-delay" -> mutableState.update { it.copy(audioDelaySeconds = double() ?: 0.0) }
      "loop-file" -> mutableState.update { it.copy(loopFile = string().let { v -> v != null && v != "no" }) }
      "ab-loop-a" -> mutableState.update { it.copy(abLoopA = string()?.toDoubleOrNull()) }
      "ab-loop-b" -> mutableState.update { it.copy(abLoopB = string()?.toDoubleOrNull()) }
      "idle-active" -> mutableState.update { it.copy(isIdle = flag() ?: true) }
      "path" -> mutableState.update { it.copy(path = string()) }
      "chapter" -> mutableState.update { it.copy(currentChapter = (long() ?: -1L).toInt()) }
      "track-list" -> updateTracks(string())
      "chapter-list" -> updateChapters(string())
    }
  }

  private fun updateTracks(raw: String?) {
    val items = raw?.let { runCatching { json.parseToJsonElement(it) as JsonArray }.getOrNull() } ?: JsonArray(emptyList())
    var hasRealVideo = false
    val tracks =
      items.mapNotNull { element ->
        val obj = element.jsonObject
        val type =
          when (obj.str("type")) {
            "video" -> TrackType.Video
            "audio" -> TrackType.Audio
            "sub" -> TrackType.Subtitle
            else -> return@mapNotNull null
          }
        if (type == TrackType.Video && obj.bool("albumart") != true && obj.bool("image") != true) hasRealVideo = true
        MediaTrack(
          id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
          type = type,
          title = obj.str("title"),
          language = obj.str("lang"),
          codec = obj.str("codec"),
          isDefault = obj.bool("default") == true,
          isExternal = obj.bool("external") == true,
          isSelected = obj.bool("selected") == true,
          channels = obj["demux-channel-count"]?.jsonPrimitive?.intOrNull,
          width = obj["demux-w"]?.jsonPrimitive?.intOrNull,
          height = obj["demux-h"]?.jsonPrimitive?.intOrNull,
        )
      }
    val path = mutableState.value.path.orEmpty()
    val audioOnly = tracks.isNotEmpty() && !hasRealVideo && (tracks.any { it.type == TrackType.Audio } || MediaExtensions.isAudio(path))
    mutableState.update { it.copy(tracks = tracks, isAudioOnly = audioOnly) }
  }

  private fun updateChapters(raw: String?) {
    val items = raw?.let { runCatching { json.parseToJsonElement(it) as JsonArray }.getOrNull() } ?: JsonArray(emptyList())
    val chapters =
      items.mapIndexed { index, element ->
        val obj = element.jsonObject
        Chapter(index, obj.str("title").orEmpty(), obj["time"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
      }
    mutableState.update { it.copy(chapters = chapters) }
  }

  private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

  private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull

  override fun load(path: String, options: LoadOptions) {
    pendingSubtitles.set(options.externalSubtitles)
    mutableState.update {
      it.copy(
        path = path,
        mediaTitle = File(path).name.substringBeforeLast('.'),
        positionSeconds = options.startSeconds ?: 0.0,
        durationSeconds = 0.0,
        isBuffering = true,
        isIdle = false,
        errorMessage = null,
        tracks = emptyList(),
        chapters = emptyList(),
      )
    }
    val fileOptions =
      buildList {
        options.startSeconds?.let { add("start=$it") }
        if (options.paused) add("pause=yes")
        if (options.httpHeaders.isNotEmpty()) {
          add("http-header-fields=" + options.httpHeaders.entries.joinToString(",") { (k, v) -> "$k: $v".replace(",", "\\,") })
        }
      }.joinToString(",")
    if (!options.paused) setProperty("pause", "no")
    // mpv >= 0.38 takes an insert index before per-file options; older builds do not.
    val modern = lib.mpv_command(handle, arrayOf("loadfile", path, "replace", "-1", fileOptions, null))
    if (modern < 0) lib.mpv_command(handle, arrayOf("loadfile", path, "replace", fileOptions, null))
  }

  override fun stop() = command("stop")

  override fun setPaused(paused: Boolean) = setProperty("pause", if (paused) "yes" else "no")

  override fun seekTo(seconds: Double, precise: Boolean) {
    mutableState.update { it.copy(positionSeconds = seconds.coerceAtLeast(0.0), isSeeking = true) }
    command("seek", seconds.coerceAtLeast(0.0).toString(), if (precise) "absolute+exact" else "absolute+keyframes")
  }

  override fun seekBy(seconds: Double, precise: Boolean) {
    command("seek", seconds.toString(), if (precise) "relative+exact" else "relative")
  }

  override fun setVolume(volume: Int) = setProperty("volume", volume.toString())

  override fun setMuted(muted: Boolean) = setProperty("mute", if (muted) "yes" else "no")

  override fun setSpeed(speed: Double) = setProperty("speed", speed.toString())

  override fun selectTrack(type: TrackType, id: Int?) {
    val property =
      when (type) {
        TrackType.Video -> "vid"
        TrackType.Audio -> "aid"
        TrackType.Subtitle -> "sid"
      }
    setProperty(property, id?.toString() ?: "no")
  }

  override fun addSubtitle(path: String) = command("sub-add", path, "select")

  override fun setSubtitleDelay(seconds: Double) = setProperty("sub-delay", seconds.toString())

  override fun setAudioDelay(seconds: Double) = setProperty("audio-delay", seconds.toString())

  override fun setChapter(index: Int) = setProperty("chapter", index.toString())

  override fun setLoopFile(loop: Boolean) = setProperty("loop-file", if (loop) "inf" else "no")

  override fun setAbLoop(a: Double?, b: Double?) {
    setProperty("ab-loop-a", a?.toString() ?: "no")
    setProperty("ab-loop-b", b?.toString() ?: "no")
  }

  override fun setVideoAspect(aspect: AspectMode) {
    when (aspect) {
      AspectMode.Fit -> {
        setProperty("keepaspect", "yes")
        setProperty("panscan", "0")
        setProperty("video-aspect-override", "no")
      }
      AspectMode.Crop -> {
        setProperty("keepaspect", "yes")
        setProperty("panscan", "1")
        setProperty("video-aspect-override", "no")
      }
      AspectMode.Stretch -> {
        setProperty("panscan", "0")
        setProperty("video-aspect-override", "no")
        setProperty("keepaspect", "no")
      }
      is AspectMode.Ratio -> {
        setProperty("keepaspect", "yes")
        setProperty("panscan", "0")
        setProperty("video-aspect-override", aspect.value.toString())
      }
    }
    forceRender = true
    renderSignal.release()
  }

  override fun setVideoZoom(zoom: Double) = setProperty("video-zoom", zoom.toString())

  override fun frameStep(forward: Boolean) = command(if (forward) "frame-step" else "frame-back-step")

  override fun screenshot(directory: String, includeSubtitles: Boolean): Boolean {
    val name = "mpvrx_" + SimpleDateFormat("yyyyMMdd_HHmmss").format(Date()) + ".png"
    val target = File(directory).apply { mkdirs() }.resolve(name).absolutePath
    return lib.mpv_command(handle, arrayOf("screenshot-to-file", target, if (includeSubtitles) "subtitles" else "video", null)) >= 0
  }

  override fun setProperty(name: String, value: String) {
    if (!closed.get()) lib.mpv_set_property_string(handle, name, value)
  }

  override fun getProperty(name: String): String? {
    if (closed.get()) return null
    val pointer = lib.mpv_get_property_string(handle, name) ?: return null
    return try {
      pointer.getString(0, "UTF-8")
    } finally {
      lib.mpv_free(pointer)
    }
  }

  override fun command(vararg args: String) {
    if (!closed.get()) lib.mpv_command(handle, arrayOf(*args, null))
  }

  private fun setOption(name: String, value: String) {
    lib.mpv_set_option_string(handle, name, value)
  }

  override fun release() {
    if (!closed.compareAndSet(false, true)) return
    renderSignal.release()
    renderThread.join(1000)
    render.release()
    lib.mpv_wakeup(handle)
    // terminate_destroy makes the event thread observe SHUTDOWN and exit.
    lib.mpv_terminate_destroy(handle)
    pendingFrame.getAndSet(null)?.close()
  }

  companion object {
    const val HW_DECODING = "auto-copy-safe"
    private const val MAX_RENDER_WIDTH = 3840
    private const val MAX_RENDER_HEIGHT = 2160

    private val OBSERVED =
      listOf(
        "time-pos" to MpvFormat.DOUBLE,
        "duration" to MpvFormat.DOUBLE,
        "pause" to MpvFormat.FLAG,
        "paused-for-cache" to MpvFormat.FLAG,
        "seeking" to MpvFormat.FLAG,
        "media-title" to MpvFormat.STRING,
        "volume" to MpvFormat.DOUBLE,
        "volume-max" to MpvFormat.DOUBLE,
        "mute" to MpvFormat.FLAG,
        "speed" to MpvFormat.DOUBLE,
        "demuxer-cache-time" to MpvFormat.DOUBLE,
        "hwdec-current" to MpvFormat.STRING,
        "dwidth" to MpvFormat.INT64,
        "dheight" to MpvFormat.INT64,
        "sub-delay" to MpvFormat.DOUBLE,
        "audio-delay" to MpvFormat.DOUBLE,
        "loop-file" to MpvFormat.STRING,
        "ab-loop-a" to MpvFormat.STRING,
        "ab-loop-b" to MpvFormat.STRING,
        "idle-active" to MpvFormat.FLAG,
        "path" to MpvFormat.STRING,
        "chapter" to MpvFormat.INT64,
        "track-list" to MpvFormat.STRING,
        "chapter-list" to MpvFormat.STRING,
      )
  }
}
