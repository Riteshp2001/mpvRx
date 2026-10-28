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
import android.view.Surface
import androidx.core.view.WindowInsetsCompat
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.domain.hdr.HdrToysManager
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
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
import app.gyrolet.mpvrx.utils.device.VulkanCapabilities
import app.gyrolet.mpvrx.utils.media.VideoCodecSupportInspector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPVLib
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.abs
import kotlin.reflect.KProperty

private fun String.toMpvLanguageList(): String =
  split(',').map(String::trim).filter(String::isNotEmpty).joinToString(",")

class MPVView(
  context: Context,
  attributes: AttributeSet,
) : MpvSurfaceHost(context, attributes),
  KoinComponent {
  private val audioPreferences: AudioPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val playerPreferences: PlayerPreferences by inject()
  private val decoderPreferences: DecoderPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val mpvConfigCache: MpvConfigCache by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val ytdlPreferences: YtdlPreferences by inject()
  private val anime4kManager: Anime4KManager by inject()
  private val hdrToysManager: HdrToysManager by inject()

  var isExiting = false
  private var lastRequestedFrameRate = Float.NaN
  var forceOpenGlFallback = false
  private var renderCallbacksEnabled = true
  private var surfaceRecovery: Runnable? = null
  private var pausedFrameRedraw: Runnable? = null
  private val surfaceReadiness = MutableStateFlow(false)
  /** Prevent glass from sampling a new renderer before the native video output is ready. */
  internal val surfaceReady: StateFlow<Boolean> = surfaceReadiness.asStateFlow()
  var isSurfaceReady: Boolean
    get() = surfaceReadiness.value
    private set(value) {
      surfaceReadiness.value = value
    }
  var surfaceAttachmentGeneration = 0L
    private set
  var onSurfaceReady: (() -> Unit)? = null
  @Volatile
  var surfaceBindingEnabled = true
    set(value) {
      field = value
      if (!value) isSurfaceReady = false
    }

  init {
    setTextureRendering(
      shouldUseTextureRendering(
        liquidGlass = appearancePreferences.liquidGlassEnabled.get(),
        hdrOutput = decoderPreferences.hdrScreenOutput.get(),
        liquidGlassInHdr = appearancePreferences.liquidGlassInHdr.get(),
      ),
    )
  }

  /**
   * Suspends the media loader until this view's Surface is attached, like mpv-android's
   * `playFile()` deferring `loadfile` to `surfaceCreated`. The bind is re-attempted so a failed or
   * superseded attachment is recovered. Returns false only when binding is disabled or exiting.
   */
  internal suspend fun awaitSurfaceReady(): Boolean {
    while (true) {
      val attached =
        withContext(Dispatchers.Main.immediate) {
          when {
            ensureSurfaceAttached() -> true
            // A hidden window (backgrounded/locked) will not create a Surface until it returns.
            isExiting || !surfaceBindingEnabled || !renderCallbacksEnabled ||
              (isAttachedToWindow && windowVisibility != VISIBLE) -> false
            else -> null
          }
        }
      if (attached != null) return attached
      withTimeoutOrNull(SURFACE_RETRY_MS) { surfaceReadiness.first { it } }
    }
  }

  /**
   * Binds this view's valid Surface unless it is already the attached one. Covers a failed bind, a
   * rebuilt core and another owner (mini player/PiP) holding the session. Main thread only.
   */
  internal fun ensureSurfaceAttached(): Boolean {
    val surface = renderSurface ?: return false
    if (isExiting || !surfaceBindingEnabled || !renderCallbacksEnabled || !surface.isValid) return false
    // A Surface can be JNI-attached while mpv is still processing its nonzero wid.
    // Do not reattach it during that window: JNI replacement briefly resets wid=0.
    if (PlaybackSession.isSurfaceAttachedTo(this)) {
      if (PlaybackSession.isRendererReadyFor(this)) notifyRendererReady()
      return isSurfaceReady
    }
    onRenderSurfaceAvailable(surface, width, height)
    return isSurfaceReady
  }

  /**
   * Configures the process-wide player and binds this view as its current rendering surface.
   * Re-entering the player reuses the live core; it never creates a second native instance.
   */
  fun initializeSession(
    configDir: String,
    cacheDir: String,
  ): Result<Boolean> {
    val result = initializeCoreSession(configDir, cacheDir)
    if (result.isSuccess) attachSessionSurface()
    return result
  }

  /**
   * Configures the process-wide native core without touching Android view state.
   *
   * Keeping the Surface handoff out of this method lets [PlayerActivity] overlap the expensive
   * `MPVLib.init()` call with window attachment and system-UI setup on the main thread. The
   * caller must invoke [attachSessionSurface] on the main thread after this succeeds.
   */
  internal fun initializeCoreSession(
    configDir: String,
    cacheDir: String,
  ): Result<Boolean> {
    // The libmpv core is process-wide, so returning to the player can reuse a core created with
    // older renderer preferences. Keep fallbacks stable for the lifetime of that preference
    // selection, but recreate the core when gpu-next/Vulkan selection actually changes.
    val inputs = awaitInitInputs()
    MpvConfigOverridePolicy.configure(advancedPreferences.mpvConfOverrides.get())
    // The core key deliberately ignores forceOpenGlFallback so a failed Vulkan attempt does not
    // invalidate the very key it is retrying under.
    val requestedBackend = selectRenderBackend(inputs.anime4kEnabled, inputs.gpuNextEnabled, inputs.vulkanCapable)
    val scriptsKey = advancedPreferences.userScriptsConfigurationKey()
    val coreConfigurationKey =
      "${requestedBackend.configurationKey}|conf=${MpvConfigOverridePolicy.configurationKey()}" +
        "|mpv=${mpvConfigCache.configurationKey()}|scripts=$scriptsKey"
    return PlaybackSession.initialize(
      context = context.applicationContext,
      configDir = configDir,
      cacheDir = cacheDir,
      coreConfigurationKey = coreConfigurationKey,
      initOptions = ::initOptions,
      postInitOptions = ::postInitOptions,
      observeProperties = ::observeProperties,
      userScriptsKey = scriptsKey,
    )
  }

  /** Binds this Android view to an initialized core. Must be called on the main thread. */
  internal fun attachSessionSurface() {
    renderCallbacksEnabled = true
    ensureSurfaceAttached()
  }

  /**
   * Whether the live core was built for a different user-script selection than the current
   * preference. Answered without touching libmpv while scripts are disabled, which is the state
   * for the ordinary video open, so the check costs one preference read and no lock.
   */
  internal fun userScriptsNeedReload(): Boolean {
    if (!advancedPreferences.enableLuaScripts.get()) return false
    return PlaybackSession.userScriptsNeedReload(advancedPreferences.userScriptsConfigurationKey())
  }

  fun releaseSurface() {
    renderCallbacksEnabled = false
    cancelSurfaceRecovery()
    cancelPausedFrameRedraw()
    surfaceAttachmentGeneration++
    lastRequestedFrameRate = Float.NaN
    if (isSurfaceReady || PlaybackSession.state.value.surfaceAttached) {
      isSurfaceReady = false
      PlaybackSession.unbindSurface(this)
    }
  }

  fun rebindCurrentSurface() {
    if (!surfaceBindingEnabled || renderSurface?.isValid != true) return
    isSurfaceReady = false
    PlaybackSession.unbindSurface(this)
    ensureSurfaceAttached()
  }

  private fun cancelSurfaceRecovery() {
    surfaceRecovery?.let(::removeCallbacks)
    surfaceRecovery = null
  }

  private fun notifyRendererReady() {
    if (isSurfaceReady) return
    isSurfaceReady = true
    applyFrameRate()
    // Restore BACKGROUND -> READY and the video's saved track before asking mpv to draw.
    onSurfaceReady?.invoke()
    refreshPausedFrameAfterForegroundReturn()
  }

  private fun cancelPausedFrameRedraw() {
    pausedFrameRedraw?.let(::removeCallbacks)
    pausedFrameRedraw = null
  }

  /** Coalesces renderer-ready and Activity start/resume callbacks into a paused-frame redraw. */
  internal fun refreshPausedFrameAfterForegroundReturn() {
    if (isExiting || !isSurfaceReady) return
    cancelPausedFrameRedraw()
    val generation = surfaceAttachmentGeneration
    val redraw = Runnable {
      pausedFrameRedraw = null
      if (generation == surfaceAttachmentGeneration && isAttachedToWindow &&
        windowVisibility == VISIBLE
      ) redrawPausedFrame()
    }
    pausedFrameRedraw = redraw
    postDelayed(redraw, 150L)
  }

  private data class StartupShaderSelection(
    val backend: RenderBackendSelection,
    val hdrMode: HdrScreenMode,
    val hdrPipelineReady: Boolean,
  )

  private var pendingStartupShaders: StartupShaderSelection? = null

  private data class RenderBackendSelection(
    val vo: String,
    val gpuApi: String,
    val gpuContext: String,
    val reason: String,
  ) {
    val configurationKey: String
      get() = "$vo|$gpuApi|$gpuContext"
  }

  /**
   * Everything [initOptions] needs that does not require a live core: preference reads, the
   * MediaCodecList query, the renderer selection *ingredients* and the Pictures directory.
   *
   * Only the `PlaybackSession.setOptionString` calls actually need the core, so deriving these
   * values off the main thread removes ~15 DataStore reads, a MediaCodecList query and an mkdirs
   * from the open path without touching the MPVLib.create -> option writes -> MPVLib.init order.
   *
   * The subtitle and audio blocks are held as ready-to-write option pairs for the same reason:
   * they were the two largest preference-read groups in the whole init (about 24 and 4 reads plus
   * a font resolution and five colour conversions), and every one of those values is known well
   * before a core exists. Deriving them here leaves `setupSubtitlesOptions`/`setupAudioOptions` as
   * pure option writes, matching the cost of the other blocks instead of dominating them.
   */
  private class InitInputs(
    val profile: String,
    val anime4kEnabled: Boolean,
    val gpuNextEnabled: Boolean,
    val vulkanCapable: Boolean,
    val hdrScreenOutputEnabled: Boolean,
    val hdrScreenModePreference: HdrScreenMode,
    val boostSdrToHdr: Boolean,
    val hardwareDecoderCodecs: List<String>,
    val useYuv420p: Boolean,
    val logLevel: String,
    val screenshotDirectoryPath: String,
    val filterValues: List<Pair<String, String>>,
    val defaultSpeed: String,
    val preciseSeek: Boolean,
    val subtitleOptionValues: List<Pair<String, String>>,
    val audioOptionValues: List<Pair<String, String>>,
  )

  @Volatile
  private var preparedInitInputs: InitInputs? = null
  private var initInputsJob: Job? = null

  /** Kicks off the derivation. Safe to call more than once; only the first call starts work. */
  fun prepareInitInputs() {
    if (initInputsJob != null) return
    initInputsJob =
      CoroutineScope(Dispatchers.IO).launch {
        preparedInitInputs = runCatching(::computeInitInputs).getOrNull()
      }
  }

  /** Joins the derivation, computing it inline if it is not ready or failed. */
  private fun awaitInitInputs(): InitInputs {
    preparedInitInputs?.let { return it }
    initInputsJob?.let { job -> runBlocking { job.join() } }
    return preparedInitInputs ?: computeInitInputs().also { preparedInitInputs = it }
  }

  private fun computeInitInputs(): InitInputs {
    val screenshotDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    screenshotDirectory.mkdirs()
    return InitInputs(
      profile = decoderPreferences.profile.get(),
      // These three are the inputs to the renderer choice. The choice itself is not precomputed
      // because it also depends on forceOpenGlFallback, which differs on the Vulkan retry attempt.
      anime4kEnabled = decoderPreferences.enableAnime4K.get() && decoderPreferences.anime4kMode.get() != "OFF",
      gpuNextEnabled = decoderPreferences.gpuNext.get(),
      vulkanCapable = RendererBackendPolicy.canUseVulkan(
        buildIncludesVulkan = BuildConfig.MPV_SUPPORTS_VULKAN,
        deviceSupportsVulkan = VulkanCapabilities.isDeviceSupported(context),
        userEnabledVulkan = decoderPreferences.useVulkan.get(),
        forceOpenGlFallback = false,
      ),
      hdrScreenOutputEnabled = decoderPreferences.hdrScreenOutput.get(),
      hdrScreenModePreference = decoderPreferences.hdrScreenMode.get(),
      boostSdrToHdr = decoderPreferences.boostSdrToHdr.get(),
      hardwareDecoderCodecs = VideoCodecSupportInspector.hardwareDecoderCodecIds(),
      useYuv420p = decoderPreferences.useYUV420P.get(),
      logLevel = if (advancedPreferences.verboseLogging.get()) "v" else "warn",
      screenshotDirectoryPath = screenshotDirectory.path,
      filterValues = VideoFilters.entries.map { it.mpvProperty to it.preference(decoderPreferences).get().toString() },
      defaultSpeed = playerPreferences.defaultSpeed.get().toString(),
      preciseSeek = playerPreferences.usePreciseSeeking.get(),
      subtitleOptionValues = computeSubtitleOptionValues(),
      audioOptionValues = computeAudioOptionValues(),
    )
  }

  fun getVideoOutAspect(): Double? {
    // Try to get aspect from video-params/aspect first
    val rawAspect = PlaybackSession.getPropertyDouble("video-params/aspect")
    // Before the first decoded frame video-params is absent and width/height below are the
    // container's unrotated size, so use the container's rotation instead of assuming 0.
    val rotate = PlaybackSession.getPropertyInt("video-params/rotate") ?: rotationBeforeDecode()

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
      val normalizedRotate = ((rotate % 360) + 360) % 360
      val isRotated = normalizedRotate == 90 || normalizedRotate == 270
      val correctedAspect = if (isRotated) 1.0 / aspect else aspect
      correctedAspect
    }
  }

  /** Mirrors mpv's decoder: `video-rotate=no` drops container rotation, otherwise it is added. */
  private fun rotationBeforeDecode(): Int {
    val option = runCatching { PlaybackSession.getPropertyString("video-rotate") }.getOrNull()?.trim()
    if (option.equals("no", ignoreCase = true)) return 0
    val container =
      runCatching { PlaybackSession.getPropertyInt("current-tracks/video/demux-rotation") }.getOrNull() ?: 0
    return container + (option?.toIntOrNull() ?: 0)
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

  private fun initOptions() {
    val inputs = awaitInitInputs()
    PlaybackSession.setOptionString("profile", inputs.profile)
    val backend = selectRenderBackend(inputs.anime4kEnabled, inputs.gpuNextEnabled, inputs.vulkanCapable)
    val useVulkan = backend.gpuApi == "vulkan"
    val hwdecMode = preferredHwdecMode(useVulkan)
    PlaybackSession.setVideoOutput(backend.vo)
    PlaybackSession.setOptionString("gpu-api", backend.gpuApi)
    PlaybackSession.setOptionString("gpu-context", backend.gpuContext)

    val isLinearAvailable = useVulkan && backend.vo == "gpu-next"
    val hdrScreenMode =
      if (!inputs.hdrScreenOutputEnabled) {
        HdrScreenMode.OFF
      } else {
        if (inputs.hdrScreenModePreference == HdrScreenMode.LINEAR && !isLinearAvailable) {
          HdrScreenMode.defaultEnabledMode
        } else {
          inputs.hdrScreenModePreference
        }
      }
    val hdrPipelineReady = hdrScreenMode != HdrScreenMode.LINEAR || isLinearAvailable
    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
      applyHdrScreenOutputOptions(
        mode = hdrScreenMode,
        pipelineReady = hdrPipelineReady,
        boostSdrToHdr = inputs.boostSdrToHdr,
      )
    }

    // Fongmi can map direct MediaCodec frames into Vulkan; other Vulkan builds start with copy mode.
    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HARDWARE_DECODER)) {
      val hardwareDecoderCodecs = inputs.hardwareDecoderCodecs
      PlaybackSession.setOptionString(
        "hwdec",
        if (hardwareDecoderCodecs.isEmpty()) "no" else hwdecMode,
      )
      if (hardwareDecoderCodecs.isNotEmpty()) {
        PlaybackSession.setOptionString("hwdec-codecs", hardwareDecoderCodecs.joinToString(","))
      }
    }

    // These were forced on between the last known-good build (e3b1de8) and the first build
    // reproducing the HEVC/Main10 frame-drop regression (84f21fc). Keep mpv's normal direct-
    // rendering heuristic, matching mpv's defaults.
    PlaybackSession.setOptionString("vd-lavc-dr", "auto")

    if (inputs.useYuv420p) {
      PlaybackSession.setOptionString("vf", "format=yuv420p")
    }
    inputs.filterValues.forEach { (property, value) ->
      PlaybackSession.setOptionString(property, value)
    }

    PlaybackSession.setOptionString("speed", inputs.defaultSpeed)
    // Avoid forcing CPU-side film-grain synthesis globally; this can spike thermals on mobile SoCs.
    // Let mpv choose the safest path for the active decoder/backend.
    PlaybackSession.setOptionString("vd-lavc-film-grain", "auto")

    // Cache, TLS, cookie and reconnect settings are applied by PlaybackSession on the first
    // item that actually opens over the network, so a local file never pays for them.
    // Drop only video-output-bound late frames when rendering cannot keep up.
    // This prevents long-term jitter buildup without aggressively sacrificing smoothness.
    PlaybackSession.setOptionString("framedrop", "vo")

    // Use audio-based video sync for better frame pacing with 4K HDR content.
    // This prevents timing jitter when the display refresh rate doesn't perfectly
    // match the video frame rate (e.g., 24fps content on 60Hz display).
    PlaybackSession.setOptionString("video-sync", "audio")

    // These are native property/command operations (shader list changes), not mpv options.
    // Running them before MPVLib.init() fails with "mpv_command: core not initialized".
    // Save the selection and apply it in postInitOptions(), before the first loadfile.
    pendingStartupShaders = StartupShaderSelection(backend, hdrScreenMode, hdrPipelineReady)

    setupSubtitlesOptions()
    setupAudioOptions()
    // The built-in hook reads script-opts when MPVLib.init() creates it.
    YtdlpManager.configureMpvHookBeforeInit(context)
    // Restore 2.7.2's bridge environment and extractor options before the first load.
    // This only configures paths/options; installation is still deferred to web playback.
    setupYtdlpOptions()
    applyStartupOptions()
  }

  /**
   * Configures the bundled bridge at core startup and refreshes preferences for web playback.
   */
  fun setupYtdlpOptions() {
    YtdlpManager.setupMpvOptions(context, ytdlPreferences, subtitlesPreferences)
  }

  /**
   * Apply these before loadfile: keep-open and seek policy also affect the first file.
   */
  private fun applyStartupOptions() {
    val inputs = awaitInitInputs()
    PlaybackSession.setOptionString("msg-level", "all=${inputs.logLevel}")
    PlaybackSession.setOptionString("keep-open", "yes")
    PlaybackSession.setOptionString("input-default-bindings", "yes")
    PlaybackSession.setOptionString("screenshot-directory", inputs.screenshotDirectoryPath)
    PlaybackSession.setOptionString("hr-seek", if (inputs.preciseSeek) "yes" else "no")
    PlaybackSession.setOptionString("hr-seek-framedrop", if (inputs.preciseSeek) "no" else "yes")
  }

  fun applyStartupStatistics() {
    // The native core survives Activity recreation and playlist loads. A toggle on every
    // load would turn an already enabled statistics overlay off on the next file.
    if (PlaybackSession.getPropertyString("user-data/mpvrx/startup-stats-applied") == "yes") return
    PlaybackSession.setPropertyString("user-data/mpvrx/startup-stats-applied", "yes")
    advancedPreferences.enabledStatisticsPage.get().let {
      if (it in 1..5) {
        PlaybackSession.command("script-binding", "stats/display-stats-toggle")
        PlaybackSession.command("script-binding", "stats/display-page-$it")
      }
    }
  }

  private fun observeProperties() {
    for ((name, format) in observedProps) PlaybackSession.observeProperty(name, format)
  }

  private fun postInitOptions() {
    // Native initialization can run off the main thread. Start with a safe baseline and let the
    // Activity's WindowInsets listener apply the real cutout margins when the view is attached.
    PlaybackSession.setOptionString("osd-margin-x", DEFAULT_OSD_SAFE_MARGIN.toString())
    PlaybackSession.setOptionString("osd-margin-y", DEFAULT_OSD_SAFE_MARGIN.toString())

    // libmpv is initialized here, but no file has been loaded yet. Unlike initOptions(),
    // it is safe to run change-list and shader property commands here.
    pendingStartupShaders?.let { selection ->
      if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.ANIME4K)) {
        applyAnime4KShaders(selection.backend.vo, selection.backend.gpuApi)
      }
      // HDR Toys must be appended after Anime4K.
      if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
        applyHdrToysMode(selection.hdrMode, selection.hdrPipelineReady)
      }
    }
    pendingStartupShaders = null

    when (decoderPreferences.debanding.get()) {
      Debanding.None -> {}
      Debanding.CPU -> PlaybackSession.command("vf", "add", "@deband:gradfun=radius=12")
      Debanding.GPU -> PlaybackSession.setOptionString("deband", "yes")
    }

  }

  fun applyOsdSafeAreaMargins(insets: WindowInsetsCompat? = null) {
    val resolvedInsets =
      insets ?: androidx.core.view.ViewCompat
        .getRootWindowInsets(this)
    val cutoutInsets = resolvedInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
    val horizontalMargin =
      maxOf(cutoutInsets?.left ?: 0, cutoutInsets?.right ?: 0).coerceAtLeast(DEFAULT_OSD_SAFE_MARGIN)
    val verticalMargin = (cutoutInsets?.top ?: 0).coerceAtLeast(DEFAULT_OSD_SAFE_MARGIN)
    PlaybackSession.setOptionString("osd-margin-x", horizontalMargin.toString())
    PlaybackSession.setOptionString("osd-margin-y", verticalMargin.toString())
  }

  private companion object {
    const val DEFAULT_OSD_SAFE_MARGIN = 16
    const val SURFACE_RETRY_MS = 250L
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

  override fun onRenderSurfaceSizeChanged(
    width: Int,
    height: Int,
  ) {
    if (!PlaybackSession.resizeSurface(width, height, owner = this)) ensureSurfaceAttached()
    applyFrameRate()
    redrawPausedFrame()
  }

  /**
   * Repaint the retained video output onto a replacement presentation window while paused.
   */
  private fun redrawPausedFrame() {
    if (isExiting || !isSurfaceReady) return
    val state = PlaybackSession.state.value
    if (!state.paused || state.phase != PlaybackPhase.READY || !PlaybackSession.isSurfaceAttachedTo(this)) return
    PlaybackSession.redrawVideoFrame()
  }

  override fun onRenderSurfaceAvailable(surface: Surface, width: Int, height: Int) {
    attachRenderSurface(surface, width, height, retriesRemaining = 2)
  }

  private fun attachRenderSurface(surface: Surface, width: Int, height: Int, retriesRemaining: Int) {
    if (isExiting || !surfaceBindingEnabled || !renderCallbacksEnabled || renderSurface !== surface) return
    if (PlaybackSession.isSurfaceAttachedTo(this)) return
    cancelSurfaceRecovery()
    isSurfaceReady = false
    surfaceAttachmentGeneration++
    val attachedGeneration = surfaceAttachmentGeneration

    fun isCurrentSurface(): Boolean =
      !isExiting && surfaceBindingEnabled && renderCallbacksEnabled && surface.isValid &&
        renderSurface === surface && surfaceAttachmentGeneration == attachedGeneration

    PlaybackSession.bindSurface(
      surface, width, height, this,
      ownerIsActive = ::isCurrentSurface,
      onRendererReady = {
        if (isCurrentSurface() && PlaybackSession.isRendererReadyFor(this)) notifyRendererReady()
      },
      onRendererFailed = {
        if (isCurrentSurface() && retriesRemaining > 0 && windowVisibility == VISIBLE) {
          val retry = Runnable {
            surfaceRecovery = null
            if (isCurrentSurface() && windowVisibility == VISIBLE &&
              !PlaybackSession.state.value.surfaceAttached
            ) {
              Log.w(TAG, "Retrying playback Surface after native window activation failed")
              attachRenderSurface(surface, this.width, this.height, retriesRemaining - 1)
            }
          }
          surfaceRecovery = retry
          postDelayed(retry, SURFACE_RETRY_MS)
        }
      },
    )
  }

  override fun onRenderSurfaceDestroyed() {
    cancelSurfaceRecovery()
    cancelPausedFrameRedraw()
    surfaceAttachmentGeneration++
    isSurfaceReady = false
    lastRequestedFrameRate = Float.NaN
    PlaybackSession.unbindSurface(this)
  }

  /**
   * Applies the content FPS once per effective value. Both Surface callbacks and mpv's
   * container-fps observer converge here, avoiding duplicate vendor display-mode requests.
   */
  internal fun updateFrameRate(fps: Double? = null) {
    if (
      android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || isExiting ||
      !surfaceBindingEnabled || !isSurfaceReady || !PlaybackSession.state.value.surfaceAttached
    ) return
    val resolved = fps ?: PlaybackSession.getPropertyDouble("container-fps") ?: return
    if (!resolved.isFinite() || resolved <= 0.0 || resolved > 1000.0) return

    val surface = renderSurface ?: return
    if (!surface.isValid) return

    val requested = resolved.toFloat()
    if (lastRequestedFrameRate.isFinite() && abs(lastRequestedFrameRate - requested) < 0.01f) return

    try {
      surface.setFrameRate(
        requested,
        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
      )
      lastRequestedFrameRate = requested
      Log.d(TAG, "Requested content frame rate ${"%.3f".format(requested)} Hz")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set frame rate on surface", e)
    }
  }

  private fun applyFrameRate() = updateFrameRate()

  private val observedProps =
    mapOf(
      "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      // Network seeks wait for data too; the buffering indicator reacts to both.
      "seeking" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "cache-buffering-state" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "demuxer-cache-duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "demuxer-cache-time" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "demuxer-via-network" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "video-params/aspect" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "video-params/w" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "video-params/h" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "video-params/rotate" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
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
    )

  /**
   * Derives every `sub-*` option from preferences, off the main thread.
   *
   * The values are written to the core verbatim by [setupSubtitlesOptions]; the option *names* and
   * their order are kept identical to the previous inline block so libmpv still receives the same
   * configuration in the same sequence before `MPVLib.init()`.
   */
  private fun computeSubtitleOptionValues(): List<Pair<String, String>> {
    // Resolve preferred languages before packet reads begin, but preserve the global subtitle-off
    // preference. TrackSelector remains responsible for title/forced/hearing-impaired filtering.
    val preferredSubtitleLanguages =
      subtitlesPreferences.preferredLanguages.get().toMpvLanguageList()
        .takeIf { subtitlesPreferences.autoEnableSubtitles.get() }
        .orEmpty()

    val fontsDirPath = "${context.filesDir.path}/fonts/"

    // Both primary and secondary use the same font; blank/default choices use mpv's sans-serif.
    val preferredFont = resolveSubtitleFontFamily(subtitlesPreferences)
    val overrideAssSubs = subtitlesPreferences.overrideAssSubs.get()
    val subAssOverride = if (overrideAssSubs) "force" else "scale"

    // Note: there is no secondary-sub-speed in official mpv — sub-speed covers text subs.
    val subDelay = (subtitlesPreferences.defaultSubDelay.get() / 1000.0).toString()
    val subSpeed = subtitlesPreferences.defaultSubSpeed.get().toString()

    val scaleByWindow = if (subtitlesPreferences.scaleByWindow.get()) "yes" else "no"
    val blendMode = subtitleBlendMode()

    return buildList {
      add("slang" to preferredSubtitleLanguages)
      add("sub-auto" to "no")
      add("sub-file-paths" to "")
      add("subs-fallback" to "no")

      add("sub-fonts-dir" to fontsDirPath)
      // Auto-detect subtitle encoding
      add("sub-codepage" to "auto")
      // Allow embedded fonts from MKV/MP4 containers
      add("embeddedfonts" to "yes")
      // Auto-detect font provider (system fonts, embedded fonts, etc.)
      add("sub-font-provider" to "auto")
      add("sub-vsfilter-bidi-compat" to if (subtitlesPreferences.forceRtlSubtitles.get()) "yes" else "no")

      // Delay for both primary and secondary (secondary-sub-delay exists in official mpv).
      add("sub-delay" to subDelay)
      add("sub-speed" to subSpeed)
      add("secondary-sub-delay" to subDelay)

      add("sub-font" to preferredFont)
      add("sub-ass-override" to subAssOverride)
      // Left at mpv's default (not written) unless ASS rendering is forced on.
      if (overrideAssSubs) add("sub-ass-justify" to "yes")
      add("secondary-sub-ass-override" to subAssOverride)

      add("blend-subtitles" to blendMode)

      add("sub-font-size" to subtitlesPreferences.fontSize.get().toString())
      // Primary style. Official mpv only has secondary-sub-delay/scale/pos/ass-override —
      // secondary inherits font/bold/italic/justify/colors/border/shadow/windowing from primary.
      add("sub-bold" to if (subtitlesPreferences.bold.get()) "yes" else "no")
      add("sub-italic" to if (subtitlesPreferences.italic.get()) "yes" else "no")
      add("sub-justify" to subtitlesPreferences.justification.get().value)
      add("sub-color" to subtitlesPreferences.textColor.get().toColorHexString())
      add("sub-back-color" to subtitlesPreferences.backgroundColor.get().toColorHexString())
      add("sub-border-color" to subtitlesPreferences.borderColor.get().toColorHexString())
      add("sub-shadow-color" to subtitlesPreferences.shadowColor.get().toColorHexString())
      add("sub-border-size" to subtitlesPreferences.borderSize.get().toString())
      add("sub-border-style" to subtitlesPreferences.borderStyle.get().value)
      add("sub-shadow-offset" to subtitlesPreferences.shadowOffset.get().toString())
      add("sub-scale" to subtitlesPreferences.subScale.get().toString())
      add("sub-pos" to clampSubtitlePosition(subtitlesPreferences.subPos.get()).toString())
      add("sub-scale-by-window" to scaleByWindow)
      add("sub-use-margins" to scaleByWindow)
      // Secondary has its own position/scale only.
      add("secondary-sub-scale" to subtitlesPreferences.secondarySubScale.get().toString())
      add(
        "secondary-sub-pos" to
          clampSubtitlePosition(subtitlesPreferences.secondarySubPos.get()).toString(),
      )
    }
  }

  private fun computeAudioOptionValues(): List<Pair<String, String>> =
    buildList {
      // Let mpv resolve the common case during demuxer initialization. TrackSelector still applies
      // title-based commentary/description filtering after load when mpv's choice needs correction.
      add("alang" to audioPreferences.preferredLanguages.get().toMpvLanguageList())
      add("audio-display" to "embedded-first")
      add("audio-delay" to (audioPreferences.defaultAudioDelay.get() / 1000.0).toString())
      add("audio-pitch-correction" to audioPreferences.audioPitchCorrection.get().toString())
      add("volume-max" to (audioPreferences.volumeBoostCap.get() + 100).toString())
      // Prevent automatic volume normalization when downmixing multi-channel audio
      add("audio-normalize-downmix" to "no")
    }

  private fun setupSubtitlesOptions() {
    awaitInitInputs().subtitleOptionValues.forEach { (property, value) ->
      PlaybackSession.setOptionString(property, value)
    }
  }

  private fun setupAudioOptions() {
    awaitInitInputs().audioOptionValues.forEach { (property, value) ->
      PlaybackSession.setOptionString(property, value)
    }
  }

  fun applyAnime4KShaders() {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.ANIME4K)) return
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
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) return
    val profile = mode.hdrToysProfile
    if (!pipelineReady || profile == null) {
      // A newly initialized core has no app HDR shaders to remove. Avoid a burst of
      // redundant change-list commands (and native work) on every normal video open.
      if (PlaybackSession.getPropertyString("glsl-shaders")?.contains("hdr-toys", ignoreCase = true) == true) {
        hdrToysManager.clear()
      }
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

  private fun preferredHwdecMode(usesVulkan: Boolean): String =
    RendererBackendPolicy.preferredHwdecMode(
      hardwareDecodingEnabled = decoderPreferences.tryHWDecoding.get(),
      usesVulkan = usesVulkan,
      buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
    )

  /**
   * Pure decision over already-resolved ingredients. [initOptions] passes the precomputed values;
   * [initializeSession] resolves them itself because it needs the selection for the core key and
   * must ignore [forceOpenGlFallback] there.
   */
  private fun selectRenderBackend(
    anime4kEnabled: Boolean,
    gpuNextEnabled: Boolean,
    vulkanEnabled: Boolean,
  ): RenderBackendSelection {

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
