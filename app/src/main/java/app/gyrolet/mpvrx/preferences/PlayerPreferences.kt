/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.DependentBooleanPreference
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.ui.player.AmbientStyle
import app.gyrolet.mpvrx.ui.player.ControlsAnimationStyle
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.player.PlayerOrientation
import app.gyrolet.mpvrx.ui.player.PostProcessingPreset
import app.gyrolet.mpvrx.ui.player.RepeatMode
import app.gyrolet.mpvrx.ui.player.ResumePlaybackMode
import app.gyrolet.mpvrx.ui.player.VideoAspect
import app.gyrolet.mpvrx.ui.player.VideoOpenAnimation
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotFormat

enum class IntroSegmentProvider(
  val displayName: String,
  val sourceKey: String,
) {
  INTRO_DB("IntroDB", "introdb"),
  THE_INTRO_DB("TIDB", "theintrodb"),
  ANI_SKIP("AniSkip (Anime)", "aniskip"),
  ANIME_SKIP("Anime Skip", "animeskip"),
  HYBRID("Hybrid (Fastest)", "hybrid"),
  SKIP_DB("SkipDB", "skipdb"),
}

enum class PlayerClockFormat(
  val displayName: String,
) {
  SYSTEM("System"),
  TWELVE_HOUR("12 hour"),
  TWENTY_FOUR_HOUR("24 hour"),
}

class PlayerPreferences(
  preferenceStore: PreferenceStore,
) {
  val orientation = preferenceStore.getEnum("player_orientation", PlayerOrientation.Video)
  val resumePlaybackMode = preferenceStore.getEnum("resume_playback_mode", ResumePlaybackMode.Always)
  val minimumResumeDurationSeconds = preferenceStore.getInt("resume_minimum_video_duration_seconds", 100)
  val invertDuration = preferenceStore.getBoolean("invert_duration")
  val holdForMultipleSpeed = preferenceStore.getFloat("hold_for_multiple_speed", 2f)
  val showDoubleTapOvals = preferenceStore.getBoolean("show_double_tap_ovals", true)
  val showSeekTimeWhileSeeking = preferenceStore.getBoolean("show_seek_time_while_seeking", true)
  val usePreciseSeeking = preferenceStore.getBoolean("use_precise_seeking", false)

  val showBufferedRange = preferenceStore.getBoolean("show_buffered_range", true)
  val showChapterIndicators = preferenceStore.getBoolean("show_chapter_indicators", true)

  val brightnessGesture = preferenceStore.getBoolean("gestures_brightness", true)
  val volumeGesture = preferenceStore.getBoolean("volume_brightness", true)
  val pinchToZoomGesture = preferenceStore.getBoolean("pinch_to_zoom_gesture", true)
  val horizontalSwipeToSeek = preferenceStore.getBoolean("horizontal_swipe_to_seek", true)
  val horizontalSwipeSensitivity = preferenceStore.getFloat("horizontal_swipe_sensitivity", 0.05f)

  val customAspectRatios = preferenceStore.getStringSet("custom_aspect_ratios", emptySet())
  val lastVideoAspect = preferenceStore.getEnum("last_video_aspect", VideoAspect.Fit)
  val lastCustomAspectRatio = preferenceStore.getFloat("last_custom_aspect_ratio", -1f)
  val autoCropBlackBars = preferenceStore.getBoolean("auto_crop_black_bars", false)

  val defaultSpeed = preferenceStore.getFloat("default_speed", 1f)
  val speedPresets =
    preferenceStore.getStringSet(
      "default_speed_presets",
      setOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0", "3.5", "4.0"),
    )
  val displayVolumeAsPercentage = preferenceStore.getBoolean("display_volume_as_percentage", true)
  val swapVolumeAndBrightness = preferenceStore.getBoolean("display_volume_on_right")
  val showLoadingCircle = preferenceStore.getBoolean("show_loading_circle", true)
  val savePositionOnQuit = preferenceStore.getBoolean("save_position", true)

  val closeAfterReachingEndOfVideo = preferenceStore.getBoolean("close_after_eof", true)

  val rememberBrightness = preferenceStore.getBoolean("remember_brightness")
  val defaultBrightness = preferenceStore.getFloat("default_brightness", -1f)

  val allowGesturesInPanels = preferenceStore.getBoolean("allow_gestures_in_panels")
  val showControlsDrawer = preferenceStore.getBoolean("show_controls_drawer", true)
  val showSystemStatusBar = preferenceStore.getBoolean("show_system_status_bar")
  val showSystemNavigationBar = preferenceStore.getBoolean("show_system_navigation_bar")
  val safeAreaWindow = preferenceStore.getBoolean("safe_area_window", false)
  val reduceMotion = preferenceStore.getBoolean("reduce_motion", true)
  val playerTimeToDisappear = preferenceStore.getInt("player_time_to_disappear", 4000)
  val clockFormat = preferenceStore.getEnum("player_clock_format", PlayerClockFormat.SYSTEM)

  val defaultVideoZoom = preferenceStore.getFloat("default_video_zoom", 0f)
  val panAndZoomEnabled = preferenceStore.getBoolean("pan_and_zoom_enabled", false)

  val includeSubtitlesInSnapshot = preferenceStore.getBoolean("include_subtitles_in_snapshot", false)

  /**
   * The folder the snapshot save dialog pre-selects. [NO_SNAPSHOT_FOLDER] means "none chosen yet",
   * which the dialog shows as the snapshot root.
   */
  val lastSnapshotFolderId = preferenceStore.getLong("last_snapshot_folder_id", NO_SNAPSHOT_FOLDER)
  val screenshotFormat = preferenceStore.getEnum("screenshot_format", ScreenshotFormat.PNG)
  val screenshotTemplate = preferenceStore.getString("screenshot_template", "mpv_snapshot_%Y%m%d_%H%M%S")
  val screenshotQuality = preferenceStore.getInt("screenshot_quality", 90)
  val screenshotPngCompression = preferenceStore.getInt("screenshot_png_compression", 7)
  val screenshotWebpLossless = preferenceStore.getBoolean("screenshot_webp_lossless", false)
  val mediaScopeAnalysisResolution = preferenceStore.getInt("media_scope_analysis_resolution", 360)
  val mediaScopeFrameRate = preferenceStore.getInt("media_scope_frame_rate", 5)

  val playlistMode = preferenceStore.getBoolean("playlist_mode", true)
  val playlistViewMode = preferenceStore.getBoolean("playlist_view_mode_list", true) // true = list, false = grid

  val useWavySeekbar = preferenceStore.getBoolean("use_wavy_seekbar", true)

  val customSkipDuration = preferenceStore.getInt("custom_skip_duration", 90)
  val enableIntroDb = preferenceStore.getBoolean("enable_introdb", true)
  val introSegmentProvider = preferenceStore.getEnum("intro_segment_provider", IntroSegmentProvider.HYBRID)
  val detectIntroOutroFromChapters = preferenceStore.getBoolean("detect_intro_outro_from_chapters", true)
  val autoSkipIntro = preferenceStore.getBoolean("auto_skip_intro", false)
  val autoSkipOutro = preferenceStore.getBoolean("auto_skip_outro", false)

  val customIntroKeywordsEnabled = preferenceStore.getBoolean("custom_intro_keywords_enabled", false)
  val customIntroKeywords = preferenceStore.getString("custom_intro_keywords", "")
  val customOutroKeywordsEnabled = preferenceStore.getBoolean("custom_outro_keywords_enabled", false)
  val customOutroKeywords = preferenceStore.getString("custom_outro_keywords", "")

  val repeatMode = preferenceStore.getEnum("repeat_mode", RepeatMode.OFF)
  val shuffleEnabled = preferenceStore.getBoolean("shuffle_enabled", false)

  // New: autoplay next video when current file ends
  val autoplayNextVideo = preferenceStore.getBoolean("autoplay_next_video", true)
  val autoplayNextAudio = preferenceStore.getBoolean("autoplay_next_audio", true)

  val autoPiPOnNavigation = preferenceStore.getBoolean("auto_pip_on_navigation", false)

  // MX/VLC-style: only the home gesture auto-enters PiP; Back keeps its normal close/handoff.
  val pipOnHomeGestureOnly = preferenceStore.getBoolean("auto_pip_home_gesture_only", false)
  private val videoBackgroundPlayback = preferenceStore.getBoolean("automatic_background_playback", false)
  private val storedEnableVideoMiniPlayer = preferenceStore.getBoolean("enable_video_mini_player", false)
  val enableVideoMiniPlayer = DependentBooleanPreference(storedEnableVideoMiniPlayer, videoBackgroundPlayback)

  val keepScreenOnWhenPaused = preferenceStore.getBoolean("keep_screen_on_when_paused", false)
  val externalDisplayProjection = preferenceStore.getBoolean("external_display_projection", true)
  val autoplayAfterScreenUnlock = preferenceStore.getBoolean("autoplay_after_screen_unlock", false)
  val enableMediaInfoIntent = preferenceStore.getBoolean("enable_mediainfo_intent", true)
  val enableWebStreamLinkIntents = preferenceStore.getBoolean("enable_web_stream_link_intents", true)

  // Custom Buttons - JSON List
  val customButtons = preferenceStore.getString("custom_buttons_json", "[]")

  // Ambience Mode
  val ambientStyle = preferenceStore.getEnum("ambient_style", AmbientStyle.Glow)
  val ambientBlurSamples = preferenceStore.getInt("ambient_blur_samples", 12)
  val ambientMaxRadius = preferenceStore.getFloat("ambient_max_radius", 0.15f)
  val ambientGlowIntensity = preferenceStore.getFloat("ambient_glow_intensity", 1.2f)
  val ambientSatBoost = preferenceStore.getFloat("ambient_sat_boost", 1.0f)
  val ambientVignetteStrength = preferenceStore.getFloat("ambient_vignette_strength", 0.5f)
  val ambientWarmth = preferenceStore.getFloat("ambient_warmth", 0.0f)
  val ambientFadeCurve = preferenceStore.getFloat("ambient_fade_curve", 1.5f)
  val ambientOpacity = preferenceStore.getFloat("ambient_opacity", 1.0f)
  val isAmbientEnabled = preferenceStore.getBoolean("ambient_enabled", false)
  val ambientBatterySaver = preferenceStore.getBoolean("ambient_battery_saver", false)

  // Post-Processing
  val isPostProcessingEnabled = preferenceStore.getBoolean("pp_enabled", false)
  val postProcessingPreset = preferenceStore.getEnum("pp_preset", PostProcessingPreset.None)

  // Frame Generation (LSFG)
  val isFrameGenEnabled = preferenceStore.getBoolean("frame_gen_enabled", false)
  val frameGenMultiplier = preferenceStore.getInt("frame_gen_multiplier", 2)

  // NaturalColors
  val ppNaturalLuma = preferenceStore.getFloat("pp_natural_luma", 1.2f)
  val ppNaturalChroma = preferenceStore.getFloat("pp_natural_chroma", 1.2f)
  // Levels
  val ppLevelsInputBlack = preferenceStore.getFloat("pp_levels_input_black", 0.0f)
  val ppLevelsInputWhite = preferenceStore.getFloat("pp_levels_input_white", 1.0f)
  val ppLevelsGamma = preferenceStore.getFloat("pp_levels_gamma", 1.0f)
  val ppLevelsOutputBlack = preferenceStore.getFloat("pp_levels_output_black", 0.0f)
  val ppLevelsOutputWhite = preferenceStore.getFloat("pp_levels_output_white", 1.0f)
  // Sharpen
  val ppSharpenAmount = preferenceStore.getFloat("pp_sharpen_amount", 0.6f)
  // Bloom
  val ppBloomRadius = preferenceStore.getFloat("pp_bloom_radius", 1.0f)
  val ppBloomAmount = preferenceStore.getFloat("pp_bloom_amount", 0.6f)
  // ColorGrade
  val ppCgSaturation = preferenceStore.getFloat("pp_cg_saturation", 1.0f)
  val ppCgBrightness = preferenceStore.getFloat("pp_cg_brightness", 1.0f)
  val ppCgContrast = preferenceStore.getFloat("pp_cg_contrast", 1.0f)
  val ppCgGamma = preferenceStore.getFloat("pp_cg_gamma", 1.0f)
  // Denoise
  val ppDenoiseStrength = preferenceStore.getFloat("pp_denoise_strength", 0.1f)
  val ppDenoiseRadius = preferenceStore.getFloat("pp_denoise_radius", 1.0f)
  val ppDenoiseCurve = preferenceStore.getFloat("pp_denoise_curve", 1.0f)
  // CelShading
  val ppCelBands = preferenceStore.getFloat("pp_cel_bands", 4.0f)
  val ppCelBandContrast = preferenceStore.getFloat("pp_cel_band_contrast", 0.5f)
  val ppCelBandEdge = preferenceStore.getFloat("pp_cel_band_edge", 0.15f)
  val ppCelDetail = preferenceStore.getFloat("pp_cel_detail", 0.6f)
  val ppCelOutlineStrength = preferenceStore.getFloat("pp_cel_outline_strength", 0.8f)
  val ppCelOutlineThreshold = preferenceStore.getFloat("pp_cel_outline_threshold", 0.18f)
  val ppCelSaturation = preferenceStore.getFloat("pp_cel_saturation", 1.25f)
  // FilmicCurve
  val ppFilmicExposure = preferenceStore.getFloat("pp_filmic_exposure", 1.0f)
  val ppFilmicToe = preferenceStore.getFloat("pp_filmic_toe", 1.2f)
  val ppFilmicShoulder = preferenceStore.getFloat("pp_filmic_shoulder", 1.2f)
  val ppFilmicAmount = preferenceStore.getFloat("pp_filmic_amount", 0.7f)
  // Blur
  val ppBlurRadius = preferenceStore.getFloat("pp_blur_radius", 4.0f)
  val ppBlurStrength = preferenceStore.getFloat("pp_blur_strength", 1.0f)
  val ppBlurFocusSize = preferenceStore.getFloat("pp_blur_focus_size", 0.0f)
  val ppBlurFocusSoftness = preferenceStore.getFloat("pp_blur_focus_softness", 0.4f)
  // Cartoon
  val ppCartoonEdgeStrength = preferenceStore.getFloat("pp_cartoon_edge_strength", 0.5f)
  val ppCartoonLevels = preferenceStore.getFloat("pp_cartoon_levels", 4.0f)
  // CartoonSoft
  val ppCartoonSoftEdgeStrength = preferenceStore.getFloat("pp_cartoon_soft_edge_strength", 0.45f)
  val ppCartoonSoftShadowGuard = preferenceStore.getFloat("pp_cartoon_soft_shadow_guard", 0.8f)
  val ppCartoonSoftLevels = preferenceStore.getFloat("pp_cartoon_soft_levels", 6.0f)
  val ppCartoonSoftSmoothing = preferenceStore.getFloat("pp_cartoon_soft_smoothing", 0.75f)
  val ppCartoonSoftSaturation = preferenceStore.getFloat("pp_cartoon_soft_saturation", 1.15f)
  // ChromaticAberration
  val ppCaStrength = preferenceStore.getFloat("pp_ca_strength", 1.5f)
  val ppCaFalloff = preferenceStore.getFloat("pp_ca_falloff", 2.0f)
  // Deband
  val ppDebandThreshold = preferenceStore.getFloat("pp_deband_threshold", 0.012f)
  val ppDebandRadius = preferenceStore.getFloat("pp_deband_radius", 8.0f)
  val ppDebandGrain = preferenceStore.getFloat("pp_deband_grain", 0.004f)
  // FilmGrain
  val ppGrainIntensity = preferenceStore.getFloat("pp_grain_intensity", 0.03f)
  val ppGrainSize = preferenceStore.getFloat("pp_grain_size", 1.0f)
  val ppGrainColored = preferenceStore.getFloat("pp_grain_colored", 0.0f)
  // LensDistortion
  val ppLensDistortion = preferenceStore.getFloat("pp_lens_distortion", 0.1f)
  val ppLensZoom = preferenceStore.getFloat("pp_lens_zoom", 1.0f)
  // MotionBlur
  val ppMotionLength = preferenceStore.getFloat("pp_motion_length", 5.0f)
  val ppMotionZoom = preferenceStore.getFloat("pp_motion_zoom", 1.0f)
  val ppMotionPan = preferenceStore.getFloat("pp_motion_pan", 0.0f)
  val ppMotionAngle = preferenceStore.getFloat("pp_motion_angle", 0.0f)
  val ppMotionSpin = preferenceStore.getFloat("pp_motion_spin", 0.0f)
  // Reflections
  val ppReflHorizon = preferenceStore.getFloat("pp_refl_horizon", 0.55f)
  val ppReflAmount = preferenceStore.getFloat("pp_refl_amount", 0.35f)
  val ppReflFalloff = preferenceStore.getFloat("pp_refl_falloff", 1.2f)
  val ppReflPerspective = preferenceStore.getFloat("pp_refl_perspective", 1.0f)
  val ppReflRipple = preferenceStore.getFloat("pp_refl_ripple", 0.0f)
  val ppReflRippleSpeed = preferenceStore.getFloat("pp_refl_ripple_speed", 1.0f)
  // Scanlines
  val ppScanlinesDensity = preferenceStore.getFloat("pp_scanlines_density", 340.0f)
  val ppScanlinesIntensity = preferenceStore.getFloat("pp_scanlines_intensity", 0.5f)
  val ppScanlinesTint = preferenceStore.getFloat("pp_scanlines_tint", 1.0f)
  // SplitToning
  val ppSplitShadowHue = preferenceStore.getFloat("pp_split_shadow_hue", 210.0f)
  val ppSplitShadowStrength = preferenceStore.getFloat("pp_split_shadow_strength", 0.0f)
  val ppSplitHighlightHue = preferenceStore.getFloat("pp_split_highlight_hue", 45.0f)
  val ppSplitHighlightStrength = preferenceStore.getFloat("pp_split_highlight_strength", 0.0f)
  val ppSplitBalance = preferenceStore.getFloat("pp_split_balance", 0.0f)
  // Vignette
  val ppVignetteStrength = preferenceStore.getFloat("pp_vignette_strength", 0.6f)
  val ppVignetteAspect = preferenceStore.getFloat("pp_vignette_aspect", 1.0f)
  // WhiteBalance
  val ppWbTemperature = preferenceStore.getFloat("pp_wb_temperature", 0.0f)
  val ppWbTint = preferenceStore.getFloat("pp_wb_tint", 0.0f)
  // CRT
  val ppCrtDensity = preferenceStore.getFloat("pp_crt_density", 272.0f)
  val ppCrtRollSpeed = preferenceStore.getFloat("pp_crt_roll_speed", 1.0f)
  val ppCrtBleed = preferenceStore.getFloat("pp_crt_bleed", 1.0f)


  /** Show the vertical volume pill while swiping for volume. */
  val showVolumeGestureOverlay = preferenceStore.getBoolean("show_volume_gesture_overlay", true)

  /** Show the vertical brightness pill while swiping for brightness. */
  val showBrightnessGestureOverlay = preferenceStore.getBoolean("show_brightness_gesture_overlay", true)

  /** Show any speed overlay (badge or full slider) during long-press hold-speed. */
  val showHoldSpeedOverlay = preferenceStore.getBoolean("show_hold_speed_overlay", true)

  /** Show the action pill when cycling aspect ratio. */
  val showAspectRatioOverlay = preferenceStore.getBoolean("show_aspect_ratio_overlay", true)

  /** Show the action pill when zoom level changes via pinch. */
  val showZoomLevelOverlay = preferenceStore.getBoolean("show_zoom_level_overlay", true)

  /** Show the action pill when toggling repeat mode or shuffle. */
  val showRepeatShuffleOverlay = preferenceStore.getBoolean("show_repeat_shuffle_overlay", true)

  /** Show brief text pills from custom buttons, ambient toggle, subtitle drag, and Lua scripts. */
  val showActionFeedbackOverlay = preferenceStore.getBoolean("show_action_feedback_overlay", true)

  /** Show the automatic-resume pill with its Restart action. */
  val showResumeIndicatorOverlay = preferenceStore.getBoolean("show_resume_indicator_overlay", true)

  /** Show provider/network status feedback such as online subtitle and marker lookup failures. */
  val showProviderStatusOverlay = preferenceStore.getBoolean("show_provider_status_overlay", false)

  // ── Animation settings ──────────────────────────────────────────────────

  /** Style used for controls appearing / disappearing. Default = original slide+fade behaviour. */
  val controlsAnimStyle = preferenceStore.getEnum("controls_anim_style", ControlsAnimationStyle.Default)

  /** Animation played when a video first opens. Default = no overlay. */
  val videoOpenAnimation = preferenceStore.getEnum("video_open_animation", VideoOpenAnimation.Default)

  /** Screen/pane transition style. None also disables programmatic tab animations. */
  val appNavStyle = preferenceStore.getEnum("app_nav_style", NavigationAnimStyle.Default)

  /** Animation duration multiplier (0.5 = twice as fast, 2.0 = twice as slow). */
  val animationSpeed = preferenceStore.getFloat("animation_speed", 1.0f)

  companion object {
    /** Sentinel for `lastSnapshotFolderId`; folder ids start at 1, so it cannot be a real folder. */
    const val NO_SNAPSHOT_FOLDER = -1L
  }
}
