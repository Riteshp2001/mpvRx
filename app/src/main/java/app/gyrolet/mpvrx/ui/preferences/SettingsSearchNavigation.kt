/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsSearchTarget(
  val screen: Screen,
  val key: String,
)

private data class SettingsSearchListAnchor(
  @StringRes val titleRes: Int,
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
        SettingsSearchListAnchor(R.string.pref_appearance_title, 0),
        SettingsSearchListAnchor(R.string.pref_appearance_amoled_mode_title, 1),
        SettingsSearchListAnchor(R.string.pref_appearance_system_font_title, 1),
        SettingsSearchListAnchor(R.string.pref_appearance_unlimited_name_lines_title, 3),
        SettingsSearchListAnchor(R.string.pref_appearance_show_unplayed_old_video_label_title, 3),
        SettingsSearchListAnchor(R.string.pref_appearance_unplayed_old_video_days_title, 3),
        SettingsSearchListAnchor(R.string.pref_appearance_auto_scroll_title, 3),
        SettingsSearchListAnchor(R.string.pref_appearance_show_video_thumbnails_title, 5),
        SettingsSearchListAnchor(R.string.pref_appearance_thumbnail_generation_title, 5),
        SettingsSearchListAnchor(R.string.pref_appearance_thumbnail_quality_title, 5),
        SettingsSearchListAnchor(R.string.pref_gesture_tap_thumbnail_to_select_title, 5),
        SettingsSearchListAnchor(R.string.pref_appearance_show_network_thumbnails_title, 5),
      ),
    PlayerControlsPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(R.string.pref_layout_title, 0),
        SettingsSearchListAnchor(R.string.pref_layout_top_right_controls, 1),
        SettingsSearchListAnchor(R.string.pref_layout_bottom_right_controls, 1),
        SettingsSearchListAnchor(R.string.pref_layout_bottom_left_controls, 1),
        SettingsSearchListAnchor(R.string.pref_layout_portrait_bottom_controls, 3),
        SettingsSearchListAnchor(R.string.pref_appearance_hide_player_buttons_background_title, 7),
        SettingsSearchListAnchor(R.string.pref_player_display_hide_player_control_time, 7),
      ),
    PlayerPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(R.string.pref_player, 0),
        SettingsSearchListAnchor(R.string.pref_player_orientation, 1),
        SettingsSearchListAnchor(R.string.pref_player_save_position_on_quit, 1),
        SettingsSearchListAnchor(R.string.pref_player_close_after_eof, 1),
        SettingsSearchListAnchor(R.string.pref_player_remember_brightness, 1),
        SettingsSearchListAnchor(R.string.pref_autoplay_next_video_title, 1),
        SettingsSearchListAnchor(R.string.pref_auto_pip_title, 1),
        SettingsSearchListAnchor(R.string.pref_player_keep_screen_on_when_paused_title, 1),
        SettingsSearchListAnchor(R.string.pref_player_autoplay_after_screen_unlock_title, 1),
        SettingsSearchListAnchor(R.string.pref_video_background_playback_title, 1),
        SettingsSearchListAnchor(R.string.pref_advanced_notification_style, 1),
        SettingsSearchListAnchor(R.string.show_splash_ovals_on_double_tap_to_seek, 3),
        SettingsSearchListAnchor(R.string.show_time_on_double_tap_to_seek, 3),
        SettingsSearchListAnchor(R.string.pref_player_use_precise_seeking, 3),
        SettingsSearchListAnchor(R.string.pref_player_seek_preview_thumbfast_title, 3),
        SettingsSearchListAnchor(R.string.pref_player_custom_skip_duration_title, 3),
        SettingsSearchListAnchor(R.string.pref_online_skip_markers_title, 3),
        SettingsSearchListAnchor(R.string.pref_marker_provider_title, 3),
        SettingsSearchListAnchor(R.string.pref_chapter_detect_title, 3),
        SettingsSearchListAnchor(R.string.pref_auto_skip_intro_title, 3),
        SettingsSearchListAnchor(R.string.pref_auto_skip_outro_title, 3),
        SettingsSearchListAnchor(R.string.pref_player_display_show_status_bar, 5),
        SettingsSearchListAnchor(R.string.pref_nav_bar_title, 5),
        SettingsSearchListAnchor(R.string.pref_player_display_reduce_player_animation, 5),
        SettingsSearchListAnchor(R.string.pref_player_controls_show_loading_circle, 5),
        SettingsSearchListAnchor(R.string.pref_player_controls_allow_gestures_in_panels, 5),
        SettingsSearchListAnchor(R.string.swap_the_volume_and_brightness_slider, 5),
      ),
    GesturePreferencesScreen to
      listOf(
        SettingsSearchListAnchor(R.string.pref_gesture, 0),
        SettingsSearchListAnchor(R.string.pref_player_gestures_brightness, 1),
        SettingsSearchListAnchor(R.string.pref_player_gestures_volume, 1),
        SettingsSearchListAnchor(R.string.pref_player_gestures_pinch_to_zoom, 1),
        SettingsSearchListAnchor(R.string.pref_player_gestures_horizontal_swipe_to_seek, 1),
        SettingsSearchListAnchor(R.string.pref_player_gestures_horizontal_swipe_sensitivity, 1),
        SettingsSearchListAnchor(R.string.pref_player_gestures_hold_for_multiple_speed, 1),
        SettingsSearchListAnchor(R.string.pref_player_double_tap_seek_duration, 3),
        SettingsSearchListAnchor(R.string.pref_double_tap_seek_area_width_title, 3),
        SettingsSearchListAnchor(R.string.pref_gesture_double_tap_left_title, 3),
        SettingsSearchListAnchor(R.string.pref_gesture_double_tap_center_title, 3),
        SettingsSearchListAnchor(R.string.pref_gesture_double_tap_right_title, 3),
        SettingsSearchListAnchor(R.string.pref_gesture_use_single_tap_for_center_title, 3),
        SettingsSearchListAnchor(R.string.pref_gesture_media_previous, 5),
        SettingsSearchListAnchor(R.string.pref_gesture_media_play, 5),
        SettingsSearchListAnchor(R.string.pref_gesture_media_next, 5),
      ),
    DecoderPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(R.string.pref_decoder, 0),
        SettingsSearchListAnchor(R.string.pref_decoder_try_hw_dec_title, 1),
        SettingsSearchListAnchor(R.string.pref_decoder_gpu_next_title, 1),
        SettingsSearchListAnchor(R.string.pref_decoder_vulkan_experimental_title, 1),
        SettingsSearchListAnchor(R.string.pref_decoder_debanding_title, 1),
        SettingsSearchListAnchor(R.string.pref_decoder_yuv420p_title, 1),
        SettingsSearchListAnchor(R.string.pref_anime4k_title, 1),
      ),
    AudioPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(R.string.pref_audio, 0),
        SettingsSearchListAnchor(R.string.pref_audio_visualizer_style_title, 3),
        SettingsSearchListAnchor(R.string.pref_audio_orientation_title, 3),
        SettingsSearchListAnchor(R.string.pref_preferred_languages, 5),
        SettingsSearchListAnchor(R.string.pref_audio_pitch_correction_title, 5),
        SettingsSearchListAnchor(R.string.pref_audio_volume_normalization_title, 5),
        SettingsSearchListAnchor(R.string.pref_audio_background_playback_title, 5),
        SettingsSearchListAnchor(R.string.pref_audio_channels, 5),
        SettingsSearchListAnchor(R.string.pref_audio_volume_boost_cap, 5),
      ),
    SubtitlesPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(R.string.pref_subtitles, 0),
        SettingsSearchListAnchor(R.string.pref_preferred_languages, 1),
        SettingsSearchListAnchor(R.string.pref_subtitles_autoload_title, 1),
        SettingsSearchListAnchor(R.string.player_sheets_sub_override_ass, 1),
        SettingsSearchListAnchor(R.string.player_sheets_sub_scale_by_window, 1),
        SettingsSearchListAnchor(R.string.pref_subtitles_fonts_dir, 3),
        SettingsSearchListAnchor(R.string.pref_subtitles_font_title, 3),
        SettingsSearchListAnchor(R.string.pref_subtitle_search_title, 5),
        SettingsSearchListAnchor(R.string.pref_subtitle_sources_title, 5),
        SettingsSearchListAnchor(R.string.pref_subtitles_search_languages, 5),
        SettingsSearchListAnchor(R.string.pref_hearing_impaired_title, 5),
        SettingsSearchListAnchor(R.string.pref_preferred_formats_title, 5),
        SettingsSearchListAnchor(R.string.pref_preferred_encodings_title, 5),
        SettingsSearchListAnchor(R.string.pref_subtitles_clear_downloads, 5),
      ),
    AiIntegrationScreen to
      listOf(
        SettingsSearchListAnchor(R.string.pref_section_ai_title, 0),
        SettingsSearchListAnchor(R.string.pref_ai_provider_title, 3),
        SettingsSearchListAnchor(R.string.search_api_key_config_title, 5),
        SettingsSearchListAnchor(R.string.search_ai_model_selection_title, 7),
        SettingsSearchListAnchor(R.string.pref_ai_rename_title, 9),
        SettingsSearchListAnchor(R.string.pref_ai_search_title, 9),
        SettingsSearchListAnchor(R.string.search_stt_title, 11),
        SettingsSearchListAnchor(R.string.pref_translation_section, 13),
        SettingsSearchListAnchor(R.string.search_custom_ai_prompts_title, 15),
      ),
    AdvancedPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(R.string.pref_advanced, 0),
        SettingsSearchListAnchor(R.string.pref_export_settings_title, 3),
        SettingsSearchListAnchor(R.string.pref_import_settings_title, 3),
        SettingsSearchListAnchor(R.string.pref_advanced_mpv_conf_storage_location, 5),
        SettingsSearchListAnchor(R.string.pref_advanced_mpv_conf, 7),
        SettingsSearchListAnchor(R.string.pref_advanced_input_conf, 7),
        SettingsSearchListAnchor(R.string.pref_enable_lua_scripts_title, 11),
        SettingsSearchListAnchor(R.string.pref_manage_lua_scripts_title, 11),
        SettingsSearchListAnchor(R.string.pref_advanced_enable_recently_played_title, 13),
        SettingsSearchListAnchor(R.string.pref_advanced_clear_playback_history, 13),
        SettingsSearchListAnchor(R.string.pref_clear_config_cache_title, 15),
        SettingsSearchListAnchor(R.string.pref_clear_thumbnail_cache_title, 15),
        SettingsSearchListAnchor(R.string.pref_advanced_clear_fonts_cache, 15),
        SettingsSearchListAnchor(R.string.pref_advanced_verbose_logging_title, 17),
        SettingsSearchListAnchor(R.string.pref_advanced_dump_logs_title, 17),
      ),
  )

object SettingsSearchNavigation {
  private val _target = MutableStateFlow<SettingsSearchTarget?>(null)
  val target = _target.asStateFlow()

  fun open(preference: SearchablePreference) {
    _target.value = SettingsSearchTarget(preference.screen, preference.searchTargetKey)
  }

  fun clear(target: SettingsSearchTarget) {
    _target.compareAndSet(target, null)
  }
}

val SearchablePreference.searchTargetKey: String
  get() = titleRes?.let { "res:$it" } ?: "text:${title.orEmpty()}"

/** Scrolls to one concrete preference row and briefly highlights only that row. */
fun Modifier.settingsSearchTarget(
  @StringRes titleRes: Int,
): Modifier =
  composed {
    val requestedTarget by SettingsSearchNavigation.target.collectAsState()
    val requester = remember { BringIntoViewRequester() }
    var highlightVisible by remember { mutableStateOf(false) }
    val highlightColor = MaterialTheme.colorScheme.primary
    val key = "res:$titleRes"

    LaunchedEffect(requestedTarget, key) {
      val target = requestedTarget?.takeIf { it.key == key } ?: return@LaunchedEffect
      requester.bringIntoView()
      highlightVisible = true
      delay(1800)
      highlightVisible = false
      SettingsSearchNavigation.clear(target)
    }

    bringIntoViewRequester(requester)
      .drawWithContent {
        drawContent()
        if (highlightVisible) {
          drawRoundRect(
            color = highlightColor.copy(alpha = 0.14f),
            cornerRadius = CornerRadius(18f, 18f),
          )
        }
      }
  }

@Composable
fun rememberSettingsSearchList(
  screen: Screen,
  @Suppress("UnusedParameter") highlightColor: Color,
): Pair<LazyListState, Modifier> {
  val listState = rememberLazyListState()
  val requestedTarget by SettingsSearchNavigation.target.collectAsState()

  LaunchedEffect(requestedTarget, screen) {
    val target = requestedTarget?.takeIf { it.screen == screen } ?: return@LaunchedEffect
    val targetIndex =
      settingsSearchListAnchors[screen]
        ?.firstOrNull { target.key == "res:${it.titleRes}" }
        ?.itemIndex
        ?: return@LaunchedEffect

    // The containing card must be composed before its exact row can call bringIntoView().
    // Keeping the target set lets settingsSearchTarget() perform that final adjustment and pulse.
    listState.animateScrollToItem(targetIndex)
  }

  return listState to Modifier
}

// Compatibility wrappers for screens without individually indexed rows.
@Composable
fun rememberSettingsSearchHighlight(
  @Suppress("UnusedParameter") screen: Screen,
  @Suppress("UnusedParameter") listState: LazyListState,
  @Suppress("UnusedParameter") highlightColor: Color,
): Modifier = Modifier

@Composable
fun rememberSettingsSearchHighlight(
  @Suppress("UnusedParameter") screen: Screen,
  @Suppress("UnusedParameter") scrollState: ScrollState,
  @Suppress("UnusedParameter") highlightColor: Color,
): Modifier = Modifier

@Composable
fun rememberSettingsSearchHighlight(
  @Suppress("UnusedParameter") screen: Screen,
  @Suppress("UnusedParameter") highlightColor: Color,
): Modifier = Modifier
