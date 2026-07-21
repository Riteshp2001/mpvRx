package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.os.Environment
import android.util.AttributeSet
import android.util.Log

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.core.view.WindowInsetsCompat
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.ui.player.anime4k.applyAnime4KShaderChain
import app.gyrolet.mpvrx.ui.player.anime4k.applyAnime4KStabilityOptions
import app.gyrolet.mpvrx.ui.player.anime4k.clearAnime4KShaders
import app.gyrolet.mpvrx.ui.player.anime4k.selectRuntimeStableAnime4K
import app.gyrolet.mpvrx.domain.hdr.HdrToysManager
import app.gyrolet.mpvrx.ui.player.PlayerActivity.Companion.TAG
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.player.controls.components.panels.toColorHexString
import app.gyrolet.mpvrx.ui.preferences.VulkanUtils
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPVLib
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.reflect.KProperty
import android.os.SystemClock
import android.app.ActivityManager

class MPVView(
  context: Context,
  attributes: AttributeSet,
) : BaseMPVView(context, attributes),
  KoinComponent {
  private val audioPreferences: AudioPreferences by inject()
  private val playerPreferences: PlayerPreferences by inject()
  private val decoderPreferences: DecoderPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val ytdlPreferences: YtdlPreferences by inject()
  private val anime4kManager: Anime4KManager by inject()
  private val hdrToysManager: HdrToysManager by inject()

  var isExiting = false
  var sessionId: Long = 0L
  var forceOpenGlFallback = false
  var isSurfaceReady = false
    private set
  var onSurfaceReady: (() -> Unit)? = null
  private var activeVo = "gpu"
  private var lastRequestedFrameRate = 0f
  private var lastFrameRateRequestAt = 0L

  private data class RenderBackendSelection(
    val vo: String,
    val gpuApi: String,
    val gpuContext: String,
    val reason: String,
  )

  fun getVideoOutAspect(): Double? {
    // Try to get aspect from video-params/aspect first
    val rawAspect = MPVLib.getPropertyDouble("video-params/aspect")
    val rotate = MPVLib.getPropertyInt("video-params/rotate") ?: 0

    // If aspect is not available or 0, calculate from width and height
    val finalAspect = if (rawAspect == null || rawAspect < 0.001) {
      val width = runCatching {
        MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      if (width > 0 && height > 0) {
        width.toDouble() / height.toDouble()
      } else {
        null
      }
    } else {
      rawAspect
    }

    return finalAspect?.let { aspect ->
      if (aspect <= 0.001) {
        return null
      }
      val isRotated = (rotate % 180 == 90)
      val correctedAspect = if (isRotated) 1.0 / aspect else aspect
      correctedAspect
    }
  }

  class TrackDelegate(
    private val name: String,
  ) {
    operator fun getValue(
      thisRef: Any?,
      property: KProperty<*>,
    ): Int {
      val v = MPVLib.getPropertyString(name)
      // we can get null here for "no" or other invalid value
      return v?.toIntOrNull() ?: -1
    }

    operator fun setValue(
      thisRef: Any?,
      property: KProperty<*>,
      value: Int,
    ) {
      if (value == -1) {
        MPVLib.setPropertyString(name, "no")
      } else {
        MPVLib.setPropertyString(name, value.toString())
      }
    }
  }

  var sid: Int by TrackDelegate("sid")
  var secondarySid: Int by TrackDelegate("secondary-sid")
  var aid: Int by TrackDelegate("aid")

  override fun initOptions() {
    val profile = decoderPreferences.profile.get()
    MPVLib.setOptionString("profile", profile)
    val backend = selectRenderBackend()
    val useVulkan = backend.gpuApi == "vulkan"
    val hwdecMode = preferredHwdecMode()
    activeVo = backend.vo
    setVo(backend.vo)
    MPVLib.setOptionString("gpu-api", backend.gpuApi)
    MPVLib.setOptionString("gpu-context", backend.gpuContext)

    val hdrScreenMode = decoderPreferences.hdrScreenMode.get().let { mode ->
      if (mode == HdrScreenMode.OFF && decoderPreferences.hdrScreenOutput.get()) HdrScreenMode.defaultEnabledMode else mode
    }
    val hdrPipelineReady = useVulkan && backend.vo == "gpu-next"
    applyHdrScreenOutputOptions(
      mode = hdrScreenMode,
      pipelineReady = hdrPipelineReady,
      boostSdrToHdr = decoderPreferences.boostSdrToHdr.get(),
    )

    // Set hwdec with fallback order: HW+ (mediacodec) -> HW (mediacodec-copy) -> SW (no)
    MPVLib.setOptionString(
      "hwdec",
      hwdecMode,
    )
    MPVLib.setOptionString("hwdec-codecs", "all")

    val memoryClassMb =
      (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
    val forwardCacheMb = (memoryClassMb / 8).coerceIn(32, 128)
    val backwardCacheMb = (memoryClassMb / 32).coerceIn(8, 32)
    MPVLib.setOptionString("demuxer-max-bytes", "${forwardCacheMb}MiB")
    MPVLib.setOptionString("demuxer-max-back-bytes", "${backwardCacheMb}MiB")

    if (decoderPreferences.useYUV420P.get()) {
      MPVLib.setOptionString("vf", "format=yuv420p")
    }
    val logLevel = if (advancedPreferences.verboseLogging.get()) "v" else "warn"
    MPVLib.setOptionString("msg-level", "all=$logLevel")

    MPVLib.setPropertyBoolean("keep-open", true)
    MPVLib.setPropertyBoolean("input-default-bindings", true)

    MPVLib.setOptionString("tls-verify", "yes")
    MPVLib.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")

    val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    screenshotDir.mkdirs()
    MPVLib.setOptionString("screenshot-directory", screenshotDir.path)

    VideoFilters.entries.forEach {
      MPVLib.setOptionString(it.mpvProperty, it.preference(decoderPreferences).get().toString())
    }

    MPVLib.setOptionString("speed", playerPreferences.defaultSpeed.get().toString())
    // Avoid forcing CPU-side film-grain synthesis globally; this can spike thermals on mobile SoCs.
    // Let mpv choose the safest path for the active decoder/backend.
    MPVLib.setOptionString("vd-lavc-film-grain", "auto")

    // Streaming improvements
    // Use adaptive HLS bitrate selection to avoid forcing the heaviest stream profile.
    // This reduces thermal load and helps prevent jitter/rebuffering on long sessions.
    MPVLib.setOptionString("hls-bitrate", "no")
    MPVLib.setOptionString("http-allow-redirect", "yes")
    // Drop only video-output-bound late frames when rendering cannot keep up.
    // This prevents long-term jitter buildup without aggressively sacrificing smoothness.
    MPVLib.setOptionString("framedrop", "vo")

    val preciseSeek = playerPreferences.usePreciseSeeking.get()
    MPVLib.setOptionString("hr-seek", if (preciseSeek) "yes" else "no")
    MPVLib.setOptionString("hr-seek-framedrop", if (preciseSeek) "no" else "yes")

    // Anime4K shader initialization (MUST be in initOptions, not after file load!)
    applyAnime4KShaders(backend.vo, backend.gpuApi)
    // HDR Toys shaders (loaded after Anime4K so they append in the correct order)
    applyHdrToysMode(hdrScreenMode, hdrPipelineReady)

    setupSubtitlesOptions()
    setupAudioOptions()
    YtdlpManager.setupMpvOptions(context, ytdlPreferences, subtitlesPreferences)
  }

  override fun observeProperties() {
    for ((name, format) in observedProps) MPVLib.observeProperty(name, format)
  }

  override fun postInitOptions() {
    applyOsdSafeAreaMargins()

    when (decoderPreferences.debanding.get()) {
      Debanding.None -> {}
      Debanding.CPU -> MPVLib.command("vf", "add", "@deband:gradfun=radius=12")
      Debanding.GPU -> MPVLib.setOptionString("deband", "yes")
    }

    advancedPreferences.enabledStatisticsPage.get().let {
      if (it in 1..5) {
        MPVLib.command("script-binding", "stats/display-stats-toggle")
        MPVLib.command("script-binding", "stats/display-page-$it")
      }
    }
    // applyUserMpvConf()
  }

  private fun applyUserMpvConf() {
    val mpvConfFile = java.io.File(context.filesDir, "mpv.conf")
    if (!mpvConfFile.exists()) return

    val content = runCatching { mpvConfFile.readText() }.getOrNull() ?: return
    for (line in content.lines()) {
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
      val eqIndex = trimmed.indexOf('=')
      if (eqIndex <= 0) continue
      val key = trimmed.substring(0, eqIndex).trim()
      val value = trimmed.substring(eqIndex + 1).trim()
      if (key.isNotBlank() && value.isNotBlank()) {
        runCatching { MPVLib.setOptionString(key, value) }
      }
    }
  }

  fun applyOsdSafeAreaMargins(insets: WindowInsetsCompat? = null) {
    val resolvedInsets = insets ?: androidx.core.view.ViewCompat.getRootWindowInsets(this)
    val cutoutInsets = resolvedInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
    val horizontalMargin = maxOf(cutoutInsets?.left ?: 0, cutoutInsets?.right ?: 0).coerceAtLeast(16)
    val verticalMargin = (cutoutInsets?.top ?: 0).coerceAtLeast(16)
    MpvSessionCoordinator.execute(sessionId, "osd_safe_area") {
      MPVLib.setOptionString("osd-margin-x", horizontalMargin.toString())
      MPVLib.setOptionString("osd-margin-y", verticalMargin.toString())
    }
  }

  @Suppress("ReturnCount", "DEPRECATION")
  fun onKey(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_MULTIPLE || KeyEvent.isModifierKey(event.keyCode)) {
      return false
    }

    var mapped = KeyMapping[event.keyCode]
    if (mapped == null) {
      // Fallback to produced glyph
      if (!event.isPrintingKey) {
        return false
      }

      val ch = event.unicodeChar
      if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0) {
        return false // dead key
      }
      mapped = ch.toChar().toString()
    }

    if (event.repeatCount > 0) {
      return true
    }

    val mod: MutableList<String> = mutableListOf()
    event.isShiftPressed && mod.add("shift")
    event.isCtrlPressed && mod.add("ctrl")
    event.isAltPressed && mod.add("alt")
    event.isMetaPressed && mod.add("meta")

    val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
    mod.add(mapped)
    MpvSessionCoordinator.execute(sessionId, "key:$action") {
      MPVLib.command(action, mod.joinToString("+"))
    }

    return true
  }

  override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
    MpvSessionCoordinator.execute(sessionId, "surface_changed") {
      MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }
    applyFrameRate(lastRequestedFrameRate.toDouble())
  }

  override fun surfaceCreated(holder: android.view.SurfaceHolder) {
    isSurfaceReady = true
    val surface = holder.surface
    MpvSessionCoordinator.execute(sessionId, "surface_created") {
      MPVLib.attachSurface(surface)
      MPVLib.setOptionString("force-window", "yes")
      MPVLib.setPropertyString("vo", activeVo)
    }
    applyFrameRate(lastRequestedFrameRate.toDouble())
    post {
      if (isSurfaceReady && surface.isValid && MpvSessionCoordinator.acceptsCallbacks(sessionId)) {
        onSurfaceReady?.invoke()
      }
    }
  }

  override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
    isSurfaceReady = false
    clearFrameRate()
    MpvSessionCoordinator.execute(sessionId, "surface_destroyed") {
      MPVLib.setPropertyString("vo", "null")
      MPVLib.setPropertyString("force-window", "no")
      MPVLib.detachSurface()
    }
  }

  fun applyFrameRate(fps: Double) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
      !fps.isFinite() ||
      fps <= 0.0
    ) return
    val requested = fps.toFloat()
    val now = SystemClock.elapsedRealtime()
    if (kotlin.math.abs(requested - lastRequestedFrameRate) < 0.01f &&
      now - lastFrameRateRequestAt < FRAME_RATE_DEBOUNCE_MS
    ) return
    val surface = holder?.surface ?: return
    if (!surface.isValid) return
    try {
      surface.setFrameRate(requested, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
      lastRequestedFrameRate = requested
      lastFrameRateRequestAt = now
    } catch (error: IllegalArgumentException) {
      Log.w(TAG, "Rejected invalid frame rate: $requested", error)
    } catch (error: IllegalStateException) {
      Log.w(TAG, "Surface became invalid while requesting frame rate", error)
    }
  }

  fun clearFrameRate() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
    val surface = holder?.surface ?: return
    if (surface.isValid) {
      runCatching {
        surface.setFrameRate(0f, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
      }
    }
    lastRequestedFrameRate = 0f
    lastFrameRateRequestAt = 0L
  }

  fun useLegacyGpuForSession() {
    activeVo = "gpu"
  }

  private val observedProps =
    mapOf(
      "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "demuxer-cache-time" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "video-params/aspect" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "video-params/w" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "video-params/h" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "container-fps" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "user-data/mpvrx/show_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/toggle_ui" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/show_panel" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/set_button_title" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/reset_button_title" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/toggle_button" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/seek_by" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/seek_to" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/seek_by_with_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/seek_to_with_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvrx/software_keyboard" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      // Curl bridge: scripts write a JSON request here; response is written to curl_response
      "user-data/mpvrx/curl_request" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      // curl_response is written by the bridge; scripts observe this property for results
      "user-data/mpvrx/curl_response" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      // Track console visibility state
      "user-data/mpv/console/open" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "sub-text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "sub-scale" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
    )

  private fun setupAudioOptions() {
    // Disable MPV's automatic audio selection
    // App will handle track selection manually via TrackSelector to respect user choices
    MPVLib.setOptionString("alang", "")
    MPVLib.setOptionString("audio-display", "embedded-first")
    MPVLib.setOptionString("audio-delay", (audioPreferences.defaultAudioDelay.get() / 1000.0).toString())
    MPVLib.setOptionString("audio-pitch-correction", audioPreferences.audioPitchCorrection.get().toString())
    MPVLib.setOptionString("volume-max", (audioPreferences.volumeBoostCap.get() + 100).toString())
    // Prevent automatic volume normalization when downmixing multi-channel audio
    MPVLib.setOptionString("audio-normalize-downmix", "no")

    // Volume normalization using dynamic audio normalization filter
    if (audioPreferences.volumeNormalization.get()) {
      MPVLib.setOptionString("af", "dynaudnorm")
    }
  }

  // Setup
  private fun setupSubtitlesOptions() {
    // Disable MPV's automatic subtitle selection
    // App will handle track selection manually via TrackSelector to respect user choices
    MPVLib.setOptionString("slang", "")
    MPVLib.setOptionString("sub-auto", "no")
    MPVLib.setOptionString("sub-file-paths", "")
    MPVLib.setOptionString("subs-fallback", "no")

    val fontsDirPath = "${context.filesDir.path}/fonts/"
    MPVLib.setOptionString("sub-fonts-dir", fontsDirPath)
    // Auto-detect subtitle encoding
    MPVLib.setOptionString("sub-codepage", "auto")
    // Allow embedded fonts from MKV/MP4 containers
    MPVLib.setOptionString("embeddedfonts", "yes")
    // Auto-detect font provider (system fonts, embedded fonts, etc.)
    MPVLib.setOptionString("sub-font-provider", "auto")

    // Delay and speed for both primary and secondary
    val subDelay = (subtitlesPreferences.defaultSubDelay.get() / 1000.0).toString()
    val subSpeed = subtitlesPreferences.defaultSubSpeed.get().toString()
    MPVLib.setOptionString("sub-delay", subDelay)
    MPVLib.setOptionString("sub-speed", subSpeed)
    MPVLib.setOptionString("secondary-sub-delay", subDelay)
    MPVLib.setOptionString("secondary-sub-speed", subSpeed)

    val preferredFont = subtitlesPreferences.font.get()
    if (preferredFont.isNotBlank()) {
      MPVLib.setOptionString("sub-font", preferredFont)
    }
    // If blank, MPV uses its default font

    if (subtitlesPreferences.overrideAssSubs.get()) {
      MPVLib.setOptionString("sub-ass-override", "force")
      MPVLib.setOptionString("sub-ass-justify", "yes")
      MPVLib.setOptionString("secondary-sub-ass-override", "force")
    } else {
      MPVLib.setOptionString("sub-ass-override", "no")
      MPVLib.setOptionString("secondary-sub-ass-override", "no")
    }

    // Typography and styling for both primary and secondary
    val fontSize = subtitlesPreferences.fontSize.get().toString()
    val bold = if (subtitlesPreferences.bold.get()) "yes" else "no"
    val italic = if (subtitlesPreferences.italic.get()) "yes" else "no"
    val justify = subtitlesPreferences.justification.get().value
    val textColor = subtitlesPreferences.textColor.get().toColorHexString()
    val backgroundColor = subtitlesPreferences.backgroundColor.get().toColorHexString()
    val borderColor = subtitlesPreferences.borderColor.get().toColorHexString()
    val shadowColor = subtitlesPreferences.shadowColor.get().toColorHexString()
    val borderSize = subtitlesPreferences.borderSize.get().toString()
    val borderStyle = subtitlesPreferences.borderStyle.get().value
    val shadowOffset = subtitlesPreferences.shadowOffset.get().toString()
    val subPos = clampSubtitlePosition(subtitlesPreferences.subPos.get())
    val w = width.takeIf { it > 0 }?.toFloat() ?: context.resources.displayMetrics.widthPixels.toFloat()
    val h = height.takeIf { it > 0 }?.toFloat() ?: context.resources.displayMetrics.heightPixels.toFloat()
    val secondarySubPos = calculateSecondarySubtitlePosition(subPos, w, h)
    val subScale = subtitlesPreferences.subScale.get().toString()

    val scaleByWindow = if (subtitlesPreferences.scaleByWindow.get()) "yes" else "no"
    val blendMode = if (subtitlesPreferences.blendSubtitlesWithVideo.get() && playerPreferences.isAmbientEnabled.get()) "video" else "no"
    MPVLib.setOptionString("blend-subtitles", blendMode)

    for ((prefix, pos) in listOf("sub-" to subPos.toString(), "secondary-sub-" to secondarySubPos.toString())) {
      MPVLib.setOptionString("${prefix}font-size", fontSize)
      MPVLib.setOptionString("${prefix}bold", bold)
      MPVLib.setOptionString("${prefix}italic", italic)
      MPVLib.setOptionString("${prefix}justify", justify)
      MPVLib.setOptionString("${prefix}color", textColor)
      MPVLib.setOptionString("${prefix}back-color", backgroundColor)
      MPVLib.setOptionString("${prefix}border-color", borderColor)
      MPVLib.setOptionString("${prefix}shadow-color", shadowColor)
      MPVLib.setOptionString("${prefix}border-size", borderSize)
      MPVLib.setOptionString("${prefix}border-style", borderStyle)
      MPVLib.setOptionString("${prefix}shadow-offset", shadowOffset)
      MPVLib.setOptionString("${prefix}scale", subScale)
      MPVLib.setOptionString("${prefix}pos", pos)
      MPVLib.setOptionString("${prefix}scale-by-window", scaleByWindow)
      MPVLib.setOptionString("${prefix}use-margins", scaleByWindow)
    }

  }


  fun applyAnime4KShaders() {
    applyAnime4KShaders(
      activeVo = MPVLib.getPropertyString("vo") ?: "",
      activeGpuApi = MPVLib.getPropertyString("gpu-api") ?: "",
    )
  }

  /**
   * Copies bundled hdr-toys GLSL shaders to filesDir on first use, then appends
   * the chosen profile's shader chain to mpv's glsl-shaders list.
   * Safe to call on every init — clears previous hdr-toys shaders before re-applying.
   */
  fun applyHdrToysMode(mode: HdrScreenMode, pipelineReady: Boolean) {
    val profile = mode.hdrToysProfile
    if (!pipelineReady || profile == null) {
      hdrToysManager.clear()
      return
    }
    if (!hdrToysManager.apply(profile)) {
      Log.w(TAG, "Skipping HDR Toys mode — bundled shaders unavailable: ${mode.name}")
    }
  }

  private fun applyAnime4KShaders(
    activeVo: String,
    activeGpuApi: String,
  ) {
    runCatching {
      val isGpuNext = activeVo == "gpu-next"
      val useVulkan = activeGpuApi == "vulkan"

      // ── Standard Anime4K (requires master switch) ─────────────────────────
      val enabled = decoderPreferences.enableAnime4K.get()
      if (!enabled) {
        clearAnime4KShaders()
        return
      }

      // Standard mode needs legacy gpu OR gpu-next+Vulkan
      if (isGpuNext && !useVulkan) {
        Log.w(TAG, "Skipping standard Anime4K — gpu-next without Vulkan")
        return
      }

      val modeStr = decoderPreferences.anime4kMode.get()
      if (modeStr == "OFF") {
        clearAnime4KShaders()
        return
      }

      // Parse user's selected mode
      val mode = try {
        Anime4KManager.Mode.valueOf(modeStr)
      } catch (e: IllegalArgumentException) {
        Anime4KManager.Mode.OFF
      }

      val selection = selectRuntimeStableAnime4K(mode, decoderPreferences.anime4kQuality.get(), context)
      selection.reason?.let { reason ->
        Log.i(TAG, "Anime4K thermal guard: $reason")
      }
      if (selection.mode == Anime4KManager.Mode.OFF) {
        clearAnime4KShaders()
        return
      }

      anime4kManager.setPostFilters(
        darken = decoderPreferences.anime4kDarken.get(),
        thin = decoderPreferences.anime4kThin.get(),
        deblur = decoderPreferences.anime4kDeblur.get(),
      )
      if (applyAnime4KShaderChain(anime4kManager, selection.mode, selection.quality)) {
        applyAnime4KStabilityOptions(useVulkan = useVulkan)
      } else {
        Log.w(
          TAG,
          "Anime4K shader chain is empty for mode=${selection.mode} quality=${selection.quality}",
        )
      }
    }.onFailure {
      Log.w(TAG, "Failed to apply Anime4K shaders", it)
    }
  }

  private fun shouldUseVulkan(): Boolean {
    if (forceOpenGlFallback) {
      return false
    }
    if (!decoderPreferences.useVulkan.get()) {
      return false
    }

    val supported = VulkanUtils.isVulkanSupported(context)
    if (!supported) {
      Log.w(TAG, "Vulkan support checks failed. Falling back to OpenGL.")
    }
    return supported
  }

  private fun preferredHwdecMode(): String {
    if (!decoderPreferences.tryHWDecoding.get()) {
      return "no"
    }

    return "mediacodec,mediacodec-copy,no"
  }

  private fun selectRenderBackend(): RenderBackendSelection {
    val anime4kEnabled = decoderPreferences.enableAnime4K.get() && 
        (decoderPreferences.anime4kMode.get() != "OFF")
    val gpuNextEnabled = decoderPreferences.gpuNext.get()
    val vulkanEnabled = shouldUseVulkan()

    if (anime4kEnabled && gpuNextEnabled && !vulkanEnabled) {
      return RenderBackendSelection(
        vo = "gpu",
        gpuApi = "opengl",
        gpuContext = "android",
        reason = "Anime4K with gpu-next but without Vulkan is unsupported: fallback to legacy gpu/opengl",
      )
    }

    if (gpuNextEnabled && vulkanEnabled) {
      return RenderBackendSelection(
        vo = "gpu-next",
        gpuApi = "vulkan",
        gpuContext = "androidvk",
        reason =
          if (anime4kEnabled) {
            "Anime4K active with gpu-next and Vulkan enabled: keep gpu-next/vulkan path"
          } else {
            "gpu-next and Vulkan enabled: use gpu-next/vulkan"
          },
      )
    }

    if (gpuNextEnabled) {
      return RenderBackendSelection(
        vo = "gpu-next",
        gpuApi = "opengl",
        gpuContext = "android",
        reason = "gpu-next enabled without Vulkan: use gpu-next/opengl",
      )
    }

    if (vulkanEnabled) {
      return RenderBackendSelection(
        vo = "gpu",
        gpuApi = "vulkan",
        gpuContext = "androidvk",
        reason =
          if (anime4kEnabled) {
            "Anime4K active with legacy gpu and Vulkan enabled: use gpu/vulkan"
          } else {
            "Vulkan enabled with legacy gpu selected: use gpu/vulkan"
          },
      )
    }

    return RenderBackendSelection(
      vo = "gpu",
      gpuApi = "opengl",
      gpuContext = "android",
      reason =
        if (anime4kEnabled) {
          "Anime4K active with legacy gpu selected: use gpu/opengl"
        } else {
          "gpu-next and Vulkan disabled: use gpu/opengl"
        },
    )
  }

  companion object {
    private const val FRAME_RATE_DEBOUNCE_MS = 1_000L
  }
}
