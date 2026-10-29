/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.utils.LocalCurrentScreen
import app.gyrolet.mpvrx.ui.tv.tvContentFocus
import app.gyrolet.mpvrx.ui.tv.tvFocusMemory
import app.gyrolet.mpvrx.ui.tv.isTelevision
import app.gyrolet.mpvrx.ui.tv.tryRequestFocus
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.seconds

data class SettingsSearchTarget(
  val screen: Screen,
  val key: String,
  val anchorItemIndex: Int? = null,
  val requestId: Long = System.nanoTime(),
)

private data class SettingsSearchListAnchor(
  @StringRes val titleRes: Int? = null,
  val title: String? = null,
  val itemIndex: Int,
)

/**
 * Top-level lazy-list positions for searchable rows. A preference card is one lazy item, so a
 * row inside an off-screen card cannot use [BringIntoViewRequester] until its card is first
 * brought into composition. These anchors perform that first jump; the row modifier then makes
 * the final adjustment and pulses the exact matching row.
 */
private val settingsSearchListAnchors: Map<Screen, List<SettingsSearchListAnchor>> =
  mapOf(
    AppearancePreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_title, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_amoled_mode_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_app_icon_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_system_font_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.navbar_glow_title, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.legacy_navigation_bar_title, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.progressive_navigation_blur_title, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.connected_mini_player_navigation_title, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_unlimited_name_lines_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_show_unplayed_old_video_label_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_unplayed_old_video_days_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_auto_scroll_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_show_video_thumbnails_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_thumbnail_generator_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_thumbnail_generation_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_thumbnail_quality_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_tap_thumbnail_to_select_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_show_network_thumbnails_title, itemIndex = 5),
      ),
    LiquidGlassPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_liquid_glass_title, itemIndex = 2),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_in_hdr_title, itemIndex = 2),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_frost, itemIndex = 4),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_opacity, itemIndex = 6),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_refraction_height, itemIndex = 6),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_refraction_amount, itemIndex = 6),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_depth, itemIndex = 6),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_chromatic, itemIndex = 6),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_vibrancy, itemIndex = 8),
        SettingsSearchListAnchor(titleRes = R.string.player_sheets_filters_Saturation, itemIndex = 8),
        SettingsSearchListAnchor(titleRes = R.string.player_sheets_filters_brightness, itemIndex = 8),
        SettingsSearchListAnchor(titleRes = R.string.player_sheets_filters_contrast, itemIndex = 8),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_highlight_style, itemIndex = 10),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_highlight_strength, itemIndex = 10),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_highlight_width, itemIndex = 10),
        SettingsSearchListAnchor(titleRes = R.string.pref_liquid_glass_highlight_blur, itemIndex = 10),
      ),
    PlayerControlsPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_title, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_top_right_controls, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_bottom_right_controls, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_bottom_left_controls, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_portrait_bottom_controls, itemIndex = 3),
        SettingsSearchListAnchor(
          titleRes = R.string.pref_appearance_force_dark_player_buttons_background_title,
          itemIndex = 7,
        ),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_display_hide_player_control_time, itemIndex = 7),
      ),
    PlayerPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_player, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_orientation, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_save_position_on_quit, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_close_after_eof, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_remember_brightness, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_autoplay_next_video_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_auto_pip_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_auto_pip_home_only_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_external_display_projection_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_keep_screen_on_when_paused_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_autoplay_after_screen_unlock_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_video_background_playback_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_background_playback_behavior, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_notification_style, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.show_splash_ovals_on_double_tap_to_seek, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.show_time_on_double_tap_to_seek, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_use_precise_seeking, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_custom_skip_duration_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_online_skip_markers_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_marker_provider_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_chapter_detect_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_auto_skip_intro_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_auto_skip_outro_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_display_show_status_bar, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_nav_bar_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_display_reduce_player_animation, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_controls_show_loading_circle, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_controls_allow_gestures_in_panels, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.swap_the_volume_and_brightness_slider, itemIndex = 5),
      ),
    GesturePreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_haptic_feedback_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_video_swipe_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_gestures_brightness, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_gestures_volume, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_gestures_pinch_to_zoom, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_gestures_horizontal_swipe_to_seek, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_gestures_horizontal_swipe_sensitivity, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_gestures_hold_for_multiple_speed, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_double_tap_seek_duration, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_double_tap_seek_area_width_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_double_tap_left_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_double_tap_center_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_double_tap_right_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_use_single_tap_for_left_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_use_single_tap_for_center_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_use_single_tap_for_right_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_media_previous, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_media_play, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_media_next, itemIndex = 5),
      ),
    VideoSwipePreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_video_swipe_right, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_video_swipe_left, itemIndex = 1),
      ),
    DecoderPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_decoder, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_decoder_try_hw_dec_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_decoder_gpu_next_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_decoder_vulkan_experimental_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_decoder_debanding_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_decoder_yuv420p_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_anime4k_title, itemIndex = 1),
      ),
    AudioPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_audio, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_music_tabs_title, itemIndex = 2),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_visualizer_style_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_orientation_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_ambient_mode_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_wavy_seekbar_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_animated_artwork_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_preferred_languages, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_pitch_correction_title, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_volume_normalization_title, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_ignore_audio_focus_title, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_background_playback_title, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_autoplay_next_audio_title, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_drc_title, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_channels, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_volume_boost_cap, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_lyrics_auto_fetch_online, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.pref_lyrics_auto_translate, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_lyrics_target_language, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_lyrics_display_mode, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_paxsenix_api_key, itemIndex = 11),
      ),
    SubtitlesPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_subtitles, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_preferred_languages, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_subtitles_autoload_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.player_sheets_sub_override_ass, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.player_sheets_sub_scale_by_window, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_subtitles_fonts_dir, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_subtitles_font_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_subtitle_search_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_subtitle_sources_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_subtitles_search_languages, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_hearing_impaired_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_preferred_formats_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_preferred_encodings_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_subtitles_clear_downloads, itemIndex = 5),
      ),
    AiIntegrationScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_section_ai_title, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_ai_provider_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_ai_enabled_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.search_api_key_config_title, itemIndex = 4),
        SettingsSearchListAnchor(titleRes = R.string.search_ai_model_selection_title, itemIndex = 6),
        SettingsSearchListAnchor(titleRes = R.string.pref_ai_rename_title, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.pref_ai_search_title, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.search_stt_title, itemIndex = 10),
        SettingsSearchListAnchor(titleRes = R.string.pref_stt_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_stt_output_format_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_stt_provider_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_language_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_translation_section, itemIndex = 12),
        SettingsSearchListAnchor(titleRes = R.string.pref_enable_translation_title, itemIndex = 13),
        SettingsSearchListAnchor(titleRes = R.string.search_custom_ai_prompts_title, itemIndex = 14),
      ),
    AdvancedPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced, itemIndex = 8),
        SettingsSearchListAnchor(titleRes = R.string.pref_app_language_title, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_notification_style, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.pref_export_settings_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_import_settings_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_mpv_conf_storage_location, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_mpv_conf, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_input_conf, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_auto_backup_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_enable_lua_scripts_title, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_manage_lua_scripts_title, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_enable_recently_played_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_clear_playback_history, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_clear_config_cache_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_clear_thumbnail_cache_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_clear_fonts_cache, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_verbose_logging_title, itemIndex = 13),
        SettingsSearchListAnchor(titleRes = R.string.pref_debug_log_size_title, itemIndex = 13),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_dump_logs_title, itemIndex = 13),
      ),
    MediaServersPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_media_servers_title, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_jellyfin_server_management, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_seerr_server_management, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_navidrome_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audiobookshelf_title, itemIndex = 7),
      ),
    NetworkConfigurationPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.ui_yt_dlp_manager, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.ui_release_channel, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.ytdlp_subtitles_language, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.ytdlp_playlist_behavior, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_section_p2p_streaming, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_enable_p2p_streaming_title, itemIndex = 0),
      ),
  )

object SettingsSearchNavigation {
  private val _target = MutableStateFlow<SettingsSearchTarget?>(null)
  val target = _target.asStateFlow()
  private var locatedTarget: SettingsSearchTarget? = null

  fun open(preference: SearchablePreference) {
    locatedTarget = null
    _target.value =
      SettingsSearchTarget(
        screen = preference.screen,
        key = preference.searchTargetKey,
        anchorItemIndex = preference.anchorItemIndex,
      )
  }

  fun clear(target: SettingsSearchTarget) {
    _target.compareAndSet(target, null)
  }

  internal fun located(target: SettingsSearchTarget) {
    if (_target.value == target) locatedTarget = target
  }

  internal fun isLocated(target: SettingsSearchTarget): Boolean = locatedTarget == target
}

val SearchablePreference.searchTargetKey: String
  get() = (targetRes ?: titleRes)?.let { "res:$it" } ?: "text:${title.orEmpty()}"

/** Scrolls to one concrete preference row and briefly highlights only that row. */
fun Modifier.settingsSearchTarget(
  key: String,
  fallback: Boolean = false,
): Modifier =
  composed {
    val requestedTarget by SettingsSearchNavigation.target.collectAsState()
    val currentScreen = LocalCurrentScreen.current
    val isTarget = requestedTarget != null && requestedTarget?.screen == currentScreen &&
      (requestedTarget?.key == key || fallback)
    val requester = remember { BringIntoViewRequester() }
    val television = isTelevision()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isTarget, requestedTarget) {
      if (isTarget) {
        requestedTarget?.let(SettingsSearchNavigation::located)
        delay(300)
        requester.bringIntoView()
        if (television) focusRequester.tryRequestFocus()
        delay(3200)
        requestedTarget?.let { SettingsSearchNavigation.clear(it) }
      }
    }

    this
      .tvFocusMemory(key)
      .then(if (television) Modifier.focusRequester(focusRequester) else Modifier)
      .bringIntoViewRequester(requester)
      .highlightBackground(isTarget)
  }

fun Modifier.settingsSearchTarget(
  @StringRes titleRes: Int,
  fallback: Boolean = false,
): Modifier = then(PlayerSettingKey(titleRes)).settingsSearchTarget("res:$titleRes", fallback)

@Composable
fun Modifier.highlightBackground(highlighted: Boolean): Modifier {
  var highlightFlag by remember { mutableStateOf(false) }
  LaunchedEffect(highlighted) {
    if (highlighted) {
      highlightFlag = true
      delay(3.seconds)
      highlightFlag = false
    } else {
      highlightFlag = false
    }
  }
  val highlight by animateColorAsState(
    targetValue =
      if (highlightFlag) {
        MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.12f)
      } else {
        Color.Transparent
      },
    animationSpec =
      if (highlightFlag) {
        repeatable(
          iterations = 5,
          animation = tween(durationMillis = 200),
          repeatMode = RepeatMode.Reverse,
          initialStartOffset =
            StartOffset(
              offsetMillis = 600,
              offsetType = StartOffsetType.Delay,
            ),
        )
      } else {
        tween(200)
      },
    label = "highlight",
  )
  return this.background(color = highlight)
}

private suspend fun scrollToSettingsSearchTarget(
  screen: Screen,
  listState: LazyListState,
  target: SettingsSearchTarget,
) {
  delay(200)
  if (SettingsSearchNavigation.isLocated(target)) return
  val count = listState.layoutInfo.totalItemsCount
  if (count == 0) {
    Log.w("SettingsSearch", "No settings rows available for $screen")
    return
  }
  val hint = settingsSearchListAnchors[screen]
    ?.firstOrNull {
      target.key == "res:${it.titleRes}" || (it.title != null && target.key == "text:${it.title}")
    }?.itemIndex ?: target.anchorItemIndex ?: 0
  val start = hint.coerceIn(0, count - 1)
  // Indices are only hints: conditional cards and reordered sections must not break deep links.
  val candidates = sequenceOf(start) + (0 until count).asSequence().filter { it != start }
  for (index in candidates) {
    if (SettingsSearchNavigation.target.value != target || SettingsSearchNavigation.isLocated(target)) return
    listState.scrollToItem(index)
    withFrameNanos { }
    withFrameNanos { }
    delay(80)
  }
  if (!SettingsSearchNavigation.isLocated(target) && SettingsSearchNavigation.target.value == target) {
    listState.scrollToItem(start)
    Log.w("SettingsSearch", "Requested setting ${target.key} is not displayed on $screen; it may require another option to be enabled")
  }
}

@Composable
fun rememberSettingsSearchList(
  screen: Screen,
  @Suppress("UnusedParameter") highlightColor: Color = Color.Unspecified,
): Pair<LazyListState, Modifier> {
  val listState = rememberLazyListState()
  val requestedTarget by SettingsSearchNavigation.target.collectAsState()

  LaunchedEffect(requestedTarget, screen) {
    val target = requestedTarget?.takeIf { it.screen == screen } ?: return@LaunchedEffect
    scrollToSettingsSearchTarget(screen, listState, target)
  }

  return listState to Modifier.tvContentFocus()
}

@Composable
fun rememberSettingsSearchHighlight(
  screen: Screen,
  listState: LazyListState,
  @Suppress("UnusedParameter") highlightColor: Color = Color.Unspecified,
): Modifier {
  val requestedTarget by SettingsSearchNavigation.target.collectAsState()

  LaunchedEffect(requestedTarget, screen) {
    val target = requestedTarget?.takeIf { it.screen == screen } ?: return@LaunchedEffect
    scrollToSettingsSearchTarget(screen, listState, target)
  }
  return Modifier.tvContentFocus()
}
