/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.core.view.WindowInsetsCompat
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.domain.hdr.HdrToysManager
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.ui.player.PlayerActivity.Companion.TAG
import app.gyrolet.mpvrx.ui.player.anime4k.applyAnime4KShaderChain
import app.gyrolet.mpvrx.ui.player.anime4k.applyAnime4KStabilityOptions
import app.gyrolet.mpvrx.ui.player.anime4k.clearAnime4KShaders
import app.gyrolet.mpvrx.ui.player.anime4k.selectRuntimeStableAnime4K
import app.gyrolet.mpvrx.ui.player.controls.components.panels.toColorHexString
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.preferences.VulkanUtils
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPVLib
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.reflect.KProperty

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
  var forceOpenGlFallback = false
  var isSurfaceReady = false
    private set
  var onSurfaceReady: (() -> Unit)? = null

  /**
   * Configures the process-wide player and binds this view as its current rendering surface.
   * Re-entering the player reuses the live core; it never creates a second native instance.
   */
  fun initializeSession(
    configDir: String,
    cacheDir: String,
  ): Result<Boolean> {
    val result =
      PlaybackSession.initialize(
        context = context.applicationContext,
        configDir = configDir,
        cacheDir = cacheDir,
        initOptions = ::initOptions,
        postInitOptions = ::postInitOptions,
        observeProperties = ::observeProperties,
      )
    if (result.isSuccess) {
      holder.removeCallback(this)
      holder.addCallback(this)
      if (holder.surface.isValid && !isSurfaceReady) surfaceCreated(holder)
    }
    return result
  }

  fun releaseSurface() {
    holder.removeCallback(this)
    if (isSurfaceReady || PlaybackSession.state.value.surfaceAttached) {
      isSurfaceReady = false
      PlaybackSession.unbindSurface()
    }
  }

  private data class RenderBackendSelection(
    val vo: String,
    val gpuApi: String,
    val gpuContext: String,
    val reason: String,
  )

  fun getVideoOutAspect(): Double? {
    // Try to get aspect from video-params/aspect first
    val rawAspect = PlaybackSession.getPropertyDouble("video-params/aspect")
    val rotate = PlaybackSession.getPropertyInt("video-params/rotate") ?: 0

    // If aspect is not available or 0, calculate from width and height
    val finalAspect =
      if (rawAspect == null || rawAspect < 0.001) {
        val width =
          runCatching {
            PlaybackSession.getPropertyInt("width") ?: PlaybackSession.getPropertyInt("video-params/w") ?: 0
          }.getOrDefault(0)

        val height =
          runCatching {
            PlaybackSession.getPropertyInt("height") ?: PlaybackSession.getPropertyInt("video-params/h") ?: 0
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
      val v = PlaybackSession.getPropertyString(name)
      // we can get null here for "no" or other invalid value
      return v?.toIntOrNull() ?: -1
    }

    operator fun setValue(
      thisRef: Any?,
      property: KProperty<*>,
      value: Int,
    ) {
      if (value == -1) {
        PlaybackSession.setPropertyString(name, "no")
      } else {
        PlaybackSession.setPropertyString(name, value.toString())
      }
    }
  }

  var sid: Int by TrackDelegate("sid")
  var secondarySid: Int by TrackDelegate("secondary-sid")
  var aid: Int by TrackDelegate("aid")

  override fun initOptions() {
    val profile = decoderPreferences.profile.get()
    PlaybackSession.setOptionString("profile", profile)
    val backend = selectRenderBackend()
    val useVulkan = backend.gpuApi == "vulkan"
    val hwdecMode = preferredHwdecMode()
    PlaybackSession.setVideoOutput(backend.vo)
    PlaybackSession.setOptionString("gpu-api", backend.gpuApi)
    PlaybackSession.setOptionString("gpu-context", backend.gpuContext)

    val hdrScreenOutputEnabled = decoderPreferences.hdrScreenOutput.get()
    val isLinearAvailable = useVulkan && backend.vo == "gpu-next"
    val hdrScreenMode =
      if (!hdrScreenOutputEnabled) {
        HdrScreenMode.OFF
      } else {
        val mode = decoderPreferences.hdrScreenMode.get()
        if (mode == HdrScreenMode.LINEAR && !isLinearAvailable) {
          HdrScreenMode.defaultEnabledMode
        } else {
          mode
        }
      }
    val hdrPipelineReady = hdrScreenMode != HdrScreenMode.LINEAR || isLinearAvailable
    applyHdrScreenOutputOptions(
      mode = hdrScreenMode,
      pipelineReady = hdrPipelineReady,
      boostSdrToHdr = decoderPreferences.boostSdrToHdr.get(),
    )

    // Set hwdec with fallback order: HW+ (mediacodec) -> HW (mediacodec-copy) -> SW (no)
    PlaybackSession.setOptionString(
      "hwdec",
      hwdecMode,
    )
    PlaybackSession.setOptionString("hwdec-codecs", "all")

    // Enable direct rendering for hardware decoding (reduces memory copies)
    PlaybackSession.setOptionString("vd-lavc-dr", "yes")
    // Queue extra frames to absorb decode jitter on 4K content
    PlaybackSession.setOptionString("vd-lavc-queue", "yes")

    if (decoderPreferences.useYUV420P.get()) {
      PlaybackSession.setOptionString("vf", "format=yuv420p")
    }
    val logLevel = if (advancedPreferences.verboseLogging.get()) "v" else "warn"
    PlaybackSession.setOptionString("msg-level", "all=$logLevel")

    PlaybackSession.setPropertyBoolean("keep-open", true)
    PlaybackSession.setPropertyBoolean("input-default-bindings", true)

    PlaybackSession.setOptionString("tls-verify", "yes")
    PlaybackSession.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")

    val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    screenshotDir.mkdirs()
    PlaybackSession.setOptionString("screenshot-directory", screenshotDir.path)

    VideoFilters.entries.forEach {
      PlaybackSession.setOptionString(it.mpvProperty, it.preference(decoderPreferences).get().toString())
    }

    PlaybackSession.setOptionString("speed", playerPreferences.defaultSpeed.get().toString())
    // Avoid forcing CPU-side film-grain synthesis globally; this can spike thermals on mobile SoCs.
    // Let mpv choose the safest path for the active decoder/backend.
    PlaybackSession.setOptionString("vd-lavc-film-grain", "auto")

    // Streaming improvements
    // Use adaptive HLS bitrate selection to avoid forcing the heaviest stream profile.
    // This reduces thermal load and helps prevent jitter/rebuffering on long sessions.
    PlaybackSession.setOptionString("hls-bitrate", "no")
    PlaybackSession.setOptionString("http-allow-redirect", "yes")
    // Drop only video-output-bound late frames when rendering cannot keep up.
    // This prevents long-term jitter buildup without aggressively sacrificing smoothness.
    PlaybackSession.setOptionString("framedrop", "vo")

    val preciseSeek = playerPreferences.usePreciseSeeking.get()
    PlaybackSession.setOptionString("hr-seek", if (preciseSeek) "yes" else "no")
    PlaybackSession.setOptionString("hr-seek-framedrop", if (preciseSeek) "no" else "yes")

    // Use audio-based video sync for better frame pacing with 4K HDR content.
    // This prevents timing jitter when the display refresh rate doesn't perfectly
    // match the video frame rate (e.g., 24fps content on 60Hz display).
    PlaybackSession.setOptionString("video-sync", "audio")

    // Anime4K shader initialization (MUST be in initOptions, not after file load!)
    applyAnime4KShaders(backend.vo, backend.gpuApi)
    // HDR Toys shaders (loaded after Anime4K so they append in the correct order)
    applyHdrToysMode(hdrScreenMode, hdrPipelineReady)

    setupSubtitlesOptions()
    setupAudioOptions()
    YtdlpManager.setupMpvOptions(context, ytdlPreferences, subtitlesPreferences)
  }

  override fun observeProperties() {
    for ((name, format) in observedProps) PlaybackSession.observeProperty(name, format)
  }

  override fun postInitOptions() {
    applyOsdSafeAreaMargins()

    when (decoderPreferences.debanding.get()) {
      Debanding.None -> {}
      Debanding.CPU -> PlaybackSession.command("vf", "add", "@deband:gradfun=radius=12")
      Debanding.GPU -> PlaybackSession.setOptionString("deband", "yes")
    }

    advancedPreferences.enabledStatisticsPage.get().let {
      if (it in 1..5) {
        PlaybackSession.command("script-binding", "stats/display-stats-toggle")
        PlaybackSession.command("script-binding", "stats/display-page-$it")
      }
    }
  }

  fun applyOsdSafeAreaMargins(insets: WindowInsetsCompat? = null) {
    val resolvedInsets =
      insets ?: androidx.core.view.ViewCompat
        .getRootWindowInsets(this)
    val cutoutInsets = resolvedInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
    val horizontalMargin = maxOf(cutoutInsets?.left ?: 0, cutoutInsets?.right ?: 0).coerceAtLeast(16)
    val verticalMargin = (cutoutInsets?.top ?: 0).coerceAtLeast(16)
    PlaybackSession.setOptionString("osd-margin-x", horizontalMargin.toString())
    PlaybackSession.setOptionString("osd-margin-y", verticalMargin.toString())
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
    PlaybackSession.command(action, mod.joinToString("+"))

    return true
  }

  override fun surfaceChanged(
    holder: android.view.SurfaceHolder,
    format: Int,
    width: Int,
    height: Int,
  ) {
    PlaybackSession.resizeSurface(width, height)
    applyFrameRate()
  }

  override fun surfaceCreated(holder: android.view.SurfaceHolder) {
    isSurfaceReady = PlaybackSession.bindSurface(holder.surface, width, height)
    applyFrameRate()
    post {
      if (isSurfaceReady && holder.surface.isValid) {
        onSurfaceReady?.invoke()
      }
    }
  }

  override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
    isSurfaceReady = false
    PlaybackSession.unbindSurface()
  }

  private fun applyFrameRate() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      val fps = PlaybackSession.getPropertyDouble("container-fps") ?: 0.0
      if (fps > 0.0 && holder?.surface?.isValid == true) {
        try {
          holder.surface.setFrameRate(
            fps.toFloat(),
            android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
          )
        } catch (e: Exception) {
          Log.e(TAG, "Failed to set frame rate on surface", e)
        }
      }
    }
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
    PlaybackSession.setOptionString("alang", "")
    PlaybackSession.setOptionString("audio-display", "embedded-first")
    PlaybackSession.setOptionString("audio-delay", (audioPreferences.defaultAudioDelay.get() / 1000.0).toString())
    PlaybackSession.setOptionString("audio-pitch-correction", audioPreferences.audioPitchCorrection.get().toString())
    PlaybackSession.setOptionString("volume-max", (audioPreferences.volumeBoostCap.get() + 100).toString())
    // Prevent automatic volume normalization when downmixing multi-channel audio
    PlaybackSession.setOptionString("audio-normalize-downmix", "no")
  }

  // Setup
  private fun setupSubtitlesOptions() {
    // Disable MPV's automatic subtitle selection
    // App will handle track selection manually via TrackSelector to respect user choices
    PlaybackSession.setOptionString("slang", "")
    PlaybackSession.setOptionString("sub-auto", "no")
    PlaybackSession.setOptionString("sub-file-paths", "")
    PlaybackSession.setOptionString("subs-fallback", "no")

    val fontsDirPath = "${context.filesDir.path}/fonts/"
    PlaybackSession.setOptionString("sub-fonts-dir", fontsDirPath)
    // Auto-detect subtitle encoding
    PlaybackSession.setOptionString("sub-codepage", "auto")
    // Allow embedded fonts from MKV/MP4 containers
    PlaybackSession.setOptionString("embeddedfonts", "yes")
    // Auto-detect font provider (system fonts, embedded fonts, etc.)
    PlaybackSession.setOptionString("sub-font-provider", "auto")

    // Delay and speed for both primary and secondary
    val subDelay = (subtitlesPreferences.defaultSubDelay.get() / 1000.0).toString()
    val subSpeed = subtitlesPreferences.defaultSubSpeed.get().toString()
    PlaybackSession.setOptionString("sub-delay", subDelay)
    PlaybackSession.setOptionString("sub-speed", subSpeed)
    PlaybackSession.setOptionString("secondary-sub-delay", subDelay)
    PlaybackSession.setOptionString("secondary-sub-speed", subSpeed)

    val preferredFont = subtitlesPreferences.font.get()
    if (preferredFont.isNotBlank()) {
      PlaybackSession.setOptionString("sub-font", preferredFont)
    }
    // If blank, MPV uses its default font

    if (subtitlesPreferences.overrideAssSubs.get()) {
      PlaybackSession.setOptionString("sub-ass-override", "force")
      PlaybackSession.setOptionString("sub-ass-justify", "yes")
      PlaybackSession.setOptionString("secondary-sub-ass-override", "force")
    } else {
      PlaybackSession.setOptionString("sub-ass-override", "no")
      PlaybackSession.setOptionString("secondary-sub-ass-override", "no")
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
    val w =
      width.takeIf { it > 0 }?.toFloat() ?: context.resources.displayMetrics.widthPixels
        .toFloat()
    val h =
      height.takeIf { it > 0 }?.toFloat() ?: context.resources.displayMetrics.heightPixels
        .toFloat()
    val secondarySubPos = calculateSecondarySubtitlePosition(subPos, w, h)
    val subScale = subtitlesPreferences.subScale.get().toString()

    val scaleByWindow = if (subtitlesPreferences.scaleByWindow.get()) "yes" else "no"
    val blendMode =
      if (subtitlesPreferences.blendSubtitlesWithVideo.get() &&
        playerPreferences.isAmbientEnabled.get()
      ) {
        "video"
      } else {
        "no"
      }
    PlaybackSession.setOptionString("blend-subtitles", blendMode)

    for ((prefix, pos) in listOf("sub-" to subPos.toString(), "secondary-sub-" to secondarySubPos.toString())) {
      PlaybackSession.setOptionString("${prefix}font-size", fontSize)
      PlaybackSession.setOptionString("${prefix}bold", bold)
      PlaybackSession.setOptionString("${prefix}italic", italic)
      PlaybackSession.setOptionString("${prefix}justify", justify)
      PlaybackSession.setOptionString("${prefix}color", textColor)
      PlaybackSession.setOptionString("${prefix}back-color", backgroundColor)
      PlaybackSession.setOptionString("${prefix}border-color", borderColor)
      PlaybackSession.setOptionString("${prefix}shadow-color", shadowColor)
      PlaybackSession.setOptionString("${prefix}border-size", borderSize)
      PlaybackSession.setOptionString("${prefix}border-style", borderStyle)
      PlaybackSession.setOptionString("${prefix}shadow-offset", shadowOffset)
      PlaybackSession.setOptionString("${prefix}scale", subScale)
      PlaybackSession.setOptionString("${prefix}pos", pos)
      PlaybackSession.setOptionString("${prefix}scale-by-window", scaleByWindow)
      PlaybackSession.setOptionString("${prefix}use-margins", scaleByWindow)
    }
  }

  fun applyAnime4KShaders() {
    applyAnime4KShaders(
      activeVo = PlaybackSession.getPropertyString("vo") ?: "",
      activeGpuApi = PlaybackSession.getPropertyString("gpu-api") ?: "",
    )
  }

  /**
   * Copies bundled hdr-toys GLSL shaders to filesDir on first use, then appends
   * the chosen profile's shader chain to mpv's glsl-shaders list.
   * Safe to call on every init — clears previous hdr-toys shaders before re-applying.
   */
  fun applyHdrToysMode(
    mode: HdrScreenMode,
    pipelineReady: Boolean,
  ) {
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
      val mode =
        try {
          Anime4KManager.Mode.valueOf(modeStr)
        } catch (e: IllegalArgumentException) {
          Anime4KManager.Mode.OFF
        }

      val selection =
        selectRuntimeStableAnime4K(
          mode = mode,
          quality = decoderPreferences.anime4kQuality.get(),
          context = context,
          enableIn4k = decoderPreferences.anime4kIn4k.get(),
        )
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
    val anime4kEnabled =
      decoderPreferences.enableAnime4K.get() &&
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
}
