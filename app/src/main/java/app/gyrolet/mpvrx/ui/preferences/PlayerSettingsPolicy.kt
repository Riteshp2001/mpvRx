/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.utils.LocalCurrentScreen
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive

/** The normal settings screens remain unrestricted. Only the in-player host supplies this context. */
internal val LocalPlayerSettingsPanel = staticCompositionLocalOf { false }

internal val LocalPlayerSettingsConfigOptions = staticCompositionLocalOf<Set<String>> { emptySet() }

internal data class PlayerSettingKey(@StringRes val title: Int) : Modifier.Element

internal data class PlayerSettingAvailability(
  @StringRes val reason: Int? = null,
  val visible: Boolean = true,
) {
  val enabled: Boolean get() = reason == null
}

private val livePlayerSettings = setOf(
  R.string.pref_player_save_position_on_quit,
  R.string.pref_player_close_after_eof,
  R.string.pref_video_background_playback_title,
  R.string.pref_background_playback_behavior,
  R.string.pref_player_remember_brightness,
  R.string.pref_autoplay_next_video_title,
  R.string.pref_auto_pip_title,
  R.string.pref_auto_pip_home_only_title,
  R.string.pref_enable_video_mini_player_title,
  R.string.pref_player_autoplay_after_screen_unlock_title,
  R.string.pref_player_orientation,
  R.string.pref_player_keep_screen_on_when_paused_title,
  R.string.pref_player_external_display_projection_title,
  R.string.show_splash_ovals_on_double_tap_to_seek,
  R.string.show_time_on_double_tap_to_seek,
  R.string.pref_player_show_buffered_range_title,
  R.string.pref_player_show_chapter_indicators_title,
  R.string.pref_player_use_precise_seeking,
  R.string.pref_player_custom_skip_duration_title,
  R.string.pref_online_skip_markers_title,
  R.string.pref_marker_provider_title,
  R.string.pref_chapter_detect_title,
  R.string.pref_auto_skip_intro_title,
  R.string.pref_auto_skip_outro_title,
  R.string.pref_custom_intro_keywords_enabled,
  R.string.pref_custom_intro_keywords_title,
  R.string.pref_custom_outro_keywords_enabled,
  R.string.pref_custom_outro_keywords_title,
  R.string.pref_player_controls_drawer_title,
  R.string.pref_player_display_show_status_bar,
  R.string.pref_nav_bar_title,
  R.string.pref_player_safe_area_window_title,
  R.string.pref_player_display_reduce_player_animation,
  R.string.pref_player_controls_show_loading_circle,
  R.string.pref_player_controls_allow_gestures_in_panels,
  R.string.swap_the_volume_and_brightness_slider,
  R.string.ui_image_format,
  R.string.ui_include_subtitles_in_screenshots,
  R.string.ui_filename_template,
  R.string.ui_jpeg_webp_quality,
  R.string.ui_png_compression,
  R.string.ui_webp_lossless,
  R.string.pref_volume_overlay_title,
  R.string.pref_brightness_overlay_title,
  R.string.pref_hold_speed_overlay_pref_title,
  R.string.pref_aspect_ratio_overlay_title,
  R.string.pref_zoom_overlay_title,
  R.string.pref_repeat_shuffle_overlay_title,
  R.string.pref_action_feedback_overlay_title,
  R.string.pref_resume_indicator_overlay_title,
  R.string.pref_provider_status_overlay_title,
)

private val liveAudioSettings = setOf(
  R.string.pref_audio_pitch_correction_title,
  R.string.pref_audio_volume_normalization_title,
  R.string.pref_audio_drc_title,
  R.string.pref_audio_spatial_title,
  R.string.pref_audio_channels,
  R.string.pref_audio_volume_boost_cap,
)

private val liveSubtitleSettings = setOf(
  R.string.player_sheets_sub_override_ass,
  R.string.player_sheets_sub_scale_by_window,
  R.string.pref_subtitles_secondary_pinch_zoom,
  // These configure the next manual search, which can be performed in the current video.
  R.string.pref_subtitle_search_title,
  R.string.pref_subtitles_search_mode_title,
  R.string.pref_subtitles_subhub_sources_title,
  R.string.pref_wyzie_api_key_title,
  R.string.pref_subtitle_sources_title,
  R.string.pref_subtitles_search_languages,
  R.string.pref_hearing_impaired_title,
  R.string.pref_ai_subtitles_title,
  R.string.pref_preferred_formats_title,
  R.string.pref_preferred_encodings_title,
  R.string.pref_betaseries_api_key_title,
  R.string.pref_jimaku_api_key_title,
  R.string.pref_subdl_api_key_title,
  R.string.pref_subsource_api_key_title,
  R.string.pref_subs_ro_api_key_title,
  R.string.pref_subx_api_key_title,
)

private val liveDecoderSettings = setOf(
  R.string.pref_decoder_try_hw_dec_title,
  R.string.pref_decoder_debanding_title,
)

private val settingNativeOptions = mapOf(
  R.string.pref_audio_pitch_correction_title to setOf("audio-pitch-correction"),
  R.string.pref_audio_volume_normalization_title to setOf("af"),
  R.string.pref_audio_drc_title to setOf("af"),
  R.string.pref_audio_spatial_title to setOf("af"),
  R.string.pref_audio_channels to setOf("af", "audio-channels"),
  R.string.pref_audio_volume_boost_cap to setOf("volume-max"),
  R.string.pref_player_use_precise_seeking to setOf("hr-seek", "hr-seek-framedrop"),
  R.string.player_sheets_sub_override_ass to setOf(
    "sub-ass-override", "secondary-sub-ass-override", "sub-pos", "secondary-sub-pos",
  ),
  R.string.player_sheets_sub_scale_by_window to setOf("sub-scale-by-window", "sub-use-margins"),
  R.string.pref_subtitles_secondary_pinch_zoom to setOf("sub-scale", "secondary-sub-scale"),
  R.string.pref_decoder_try_hw_dec_title to setOf("hwdec", "hwdec-codecs"),
  R.string.pref_decoder_debanding_title to setOf(
    "vf", "deband", "deband-iterations", "deband-threshold", "deband-range", "deband-grain",
  ),
)

internal fun isLivePlayerSetting(screen: app.gyrolet.mpvrx.presentation.Screen?, @StringRes key: Int?): Boolean =
  when (screen) {
    is ControlLayoutEditorScreen -> true
    PlayerControlsPreferencesScreen, PersistentStatusBarPreferencesScreen -> true
    GesturePreferencesScreen -> key !in setOf(R.string.pref_video_swipe_title, R.string.pref_nested_tab_swipes_title)
    PlayerPreferencesScreen -> key in livePlayerSettings
    AudioPreferencesScreen -> key in liveAudioSettings
    SubtitlesPreferencesScreen -> key in liveSubtitleSettings
    DecoderPreferencesScreen -> key in liveDecoderSettings
    LiquidGlassPreferencesScreen ->
      key !in setOf(R.string.pref_appearance_liquid_glass_title, R.string.pref_liquid_glass_in_hdr_title)
    else -> false
  }

@Composable
internal fun playerSettingAvailability(modifier: Modifier): PlayerSettingAvailability {
  if (!LocalPlayerSettingsPanel.current) return PlayerSettingAvailability()
  val key = modifier.foldIn<Int?>(null) { found, element -> (element as? PlayerSettingKey)?.title ?: found }
  val screen = LocalCurrentScreen.current
  if (!isLivePlayerSetting(screen, key)) return PlayerSettingAvailability(visible = false)
  val configOwned = LocalPlayerSettingsConfigOptions.current
  if (key?.let(settingNativeOptions::get)?.any(configOwned::contains) == true) {
    return PlayerSettingAvailability(R.string.player_settings_config_owned)
  }
  val reason =
    if (screen == LiquidGlassPreferencesScreen && !liquidGlassActive()) R.string.player_settings_dependency else null
  return PlayerSettingAvailability(reason)
}

@Composable
internal fun PlayerSettingSummary(
  availability: PlayerSettingAvailability,
  otherwiseEnabled: Boolean = true,
  content: (@Composable () -> Unit)? = null,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    content?.invoke()
    val reason = when {
      availability.reason == R.string.player_settings_config_owned -> availability.reason
      LocalPlayerSettingsPanel.current && !otherwiseEnabled -> R.string.player_settings_dependency
      else -> availability.reason
    }
    if (reason != null) {
      Text(
        text = stringResource(reason),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Preserve the original row height when an ordinary preference has no summary. */
@Composable
internal fun playerSettingSummary(
  availability: PlayerSettingAvailability,
  otherwiseEnabled: Boolean,
  content: (@Composable () -> Unit)?,
): (@Composable () -> Unit)? {
  val needsSummary = content != null || availability.reason != null ||
    (LocalPlayerSettingsPanel.current && !otherwiseEnabled)
  if (!needsSummary) return null
  return { PlayerSettingSummary(availability, otherwiseEnabled, content) }
}
