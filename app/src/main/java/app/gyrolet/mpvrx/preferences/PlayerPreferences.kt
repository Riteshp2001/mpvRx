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
import app.gyrolet.mpvrx.ui.player.RepeatMode
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
  val invertDuration = preferenceStore.getBoolean("invert_duration")
  val holdForMultipleSpeed = preferenceStore.getFloat("hold_for_multiple_speed", 2f)
  val showDoubleTapOvals = preferenceStore.getBoolean("show_double_tap_ovals", true)
  val showSeekTimeWhileSeeking = preferenceStore.getBoolean("show_seek_time_while_seeking", true)
  val usePreciseSeeking = preferenceStore.getBoolean("use_precise_seeking", false)
  val useThumbFastSeekPreview = preferenceStore.getBoolean("use_thumbfast_seek_preview", true)
  val thumbFastNetworkStreams = preferenceStore.getBoolean("thumbfast_network_streams", true)
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
  val showSystemStatusBar = preferenceStore.getBoolean("show_system_status_bar")
  val showSystemNavigationBar = preferenceStore.getBoolean("show_system_navigation_bar")
  val safeAreaWindow = preferenceStore.getBoolean("safe_area_window", false)
  val reduceMotion = preferenceStore.getBoolean("reduce_motion", true)
  val playerTimeToDisappear = preferenceStore.getInt("player_time_to_disappear", 4000)
  val clockFormat = preferenceStore.getEnum("player_clock_format", PlayerClockFormat.SYSTEM)

  val defaultVideoZoom = preferenceStore.getFloat("default_video_zoom", 0f)
  val panAndZoomEnabled = preferenceStore.getBoolean("pan_and_zoom_enabled", false)

  val includeSubtitlesInSnapshot = preferenceStore.getBoolean("include_subtitles_in_snapshot", false)
  val screenshotFormat = preferenceStore.getEnum("screenshot_format", ScreenshotFormat.PNG)
  val screenshotTemplate = preferenceStore.getString("screenshot_template", "mpv_snapshot_%Y%m%d_%H%M%S")
  val screenshotQuality = preferenceStore.getInt("screenshot_quality", 90)
  val screenshotPngCompression = preferenceStore.getInt("screenshot_png_compression", 7)
  val screenshotWebpLossless = preferenceStore.getBoolean("screenshot_webp_lossless", false)

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
  private val videoBackgroundPlayback = preferenceStore.getBoolean("automatic_background_playback", false)
  private val storedEnableVideoMiniPlayer = preferenceStore.getBoolean("enable_video_mini_player", false)
  val enableVideoMiniPlayer = DependentBooleanPreference(storedEnableVideoMiniPlayer, videoBackgroundPlayback)

  val keepScreenOnWhenPaused = preferenceStore.getBoolean("keep_screen_on_when_paused", false)
  val autoplayAfterScreenUnlock = preferenceStore.getBoolean("autoplay_after_screen_unlock", false)
  val enableMediaInfoIntent = preferenceStore.getBoolean("enable_mediainfo_intent", true)

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

  /** Show provider/network status feedback such as online subtitle and marker lookup failures. */
  val showProviderStatusOverlay = preferenceStore.getBoolean("show_provider_status_overlay", false)

  // ── Animation settings ──────────────────────────────────────────────────

  /** Style used for controls appearing / disappearing. Default = original slide+fade behaviour. */
  val controlsAnimStyle = preferenceStore.getEnum("controls_anim_style", ControlsAnimationStyle.Default)

  /** Animation played when a video first opens. Default = no overlay. */
  val videoOpenAnimation = preferenceStore.getEnum("video_open_animation", VideoOpenAnimation.Default)

  /** Screen-level (app-wide) navigation transition style. */
  val appNavStyle = preferenceStore.getEnum("app_nav_style", NavigationAnimStyle.Default)

  /** Global animation speed multiplier (0.5 = half speed, 2.0 = double speed). */
  val animationSpeed = preferenceStore.getFloat("animation_speed", 1.0f)
}
