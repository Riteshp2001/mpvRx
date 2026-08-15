/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.seconds

data class SettingsSearchTarget(
  val screen: Screen,
  val key: String,
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
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_system_font_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_unlimited_name_lines_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_show_unplayed_old_video_label_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_unplayed_old_video_days_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_auto_scroll_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_show_video_thumbnails_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_thumbnail_generation_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_thumbnail_quality_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_tap_thumbnail_to_select_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_show_network_thumbnails_title, itemIndex = 5),
      ),
    PlayerControlsPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_title, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_top_right_controls, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_bottom_right_controls, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_bottom_left_controls, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_layout_portrait_bottom_controls, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_appearance_hide_player_buttons_background_title, itemIndex = 7),
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
        SettingsSearchListAnchor(titleRes = R.string.pref_player_keep_screen_on_when_paused_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_autoplay_after_screen_unlock_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_video_background_playback_title, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_notification_style, itemIndex = 1),
        SettingsSearchListAnchor(titleRes = R.string.show_splash_ovals_on_double_tap_to_seek, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.show_time_on_double_tap_to_seek, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_use_precise_seeking, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_player_seek_preview_thumbfast_title, itemIndex = 3),
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
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_use_single_tap_for_center_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_media_previous, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_media_play, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_gesture_media_next, itemIndex = 5),
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
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_visualizer_style_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_orientation_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_preferred_languages, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_pitch_correction_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_volume_normalization_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_background_playback_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_channels, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_audio_volume_boost_cap, itemIndex = 5),
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
        SettingsSearchListAnchor(titleRes = R.string.search_api_key_config_title, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.search_ai_model_selection_title, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_ai_rename_title, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.pref_ai_search_title, itemIndex = 9),
        SettingsSearchListAnchor(titleRes = R.string.search_stt_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_translation_section, itemIndex = 13),
        SettingsSearchListAnchor(titleRes = R.string.search_custom_ai_prompts_title, itemIndex = 15),
      ),
    AdvancedPreferencesScreen to
      listOf(
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced, itemIndex = 0),
        SettingsSearchListAnchor(titleRes = R.string.pref_export_settings_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_import_settings_title, itemIndex = 3),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_mpv_conf_storage_location, itemIndex = 5),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_mpv_conf, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_input_conf, itemIndex = 7),
        SettingsSearchListAnchor(titleRes = R.string.pref_enable_lua_scripts_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_manage_lua_scripts_title, itemIndex = 11),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_enable_recently_played_title, itemIndex = 13),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_clear_playback_history, itemIndex = 13),
        SettingsSearchListAnchor(titleRes = R.string.pref_clear_config_cache_title, itemIndex = 15),
        SettingsSearchListAnchor(titleRes = R.string.pref_clear_thumbnail_cache_title, itemIndex = 15),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_clear_fonts_cache, itemIndex = 15),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_verbose_logging_title, itemIndex = 17),
        SettingsSearchListAnchor(titleRes = R.string.pref_advanced_dump_logs_title, itemIndex = 17),
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
  key: String,
): Modifier =
  composed {
    val requestedTarget by SettingsSearchNavigation.target.collectAsState()
    val isTarget = requestedTarget?.key == key
    val requester = remember { BringIntoViewRequester() }

    LaunchedEffect(isTarget) {
      if (isTarget) {
        delay(300)
        requester.bringIntoView()
        delay(3200)
        requestedTarget?.let { SettingsSearchNavigation.clear(it) }
      }
    }

    this
      .bringIntoViewRequester(requester)
      .highlightBackground(isTarget)
  }

fun Modifier.settingsSearchTarget(
  @StringRes titleRes: Int,
): Modifier = settingsSearchTarget("res:$titleRes")

@Composable
fun Modifier.highlightBackground(highlighted: Boolean): Modifier {
  var highlightFlag by remember { mutableStateOf(false) }
  LaunchedEffect(highlighted) {
    if (highlighted) {
      highlightFlag = true
      delay(3.seconds)
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

@Composable
fun rememberSettingsSearchList(
  screen: Screen,
  @Suppress("UnusedParameter") highlightColor: Color = Color.Unspecified,
): Pair<LazyListState, Modifier> {
  val listState = rememberLazyListState()
  val requestedTarget by SettingsSearchNavigation.target.collectAsState()

  LaunchedEffect(requestedTarget, screen) {
    val target = requestedTarget?.takeIf { it.screen == screen } ?: return@LaunchedEffect
    val targetIndex =
      settingsSearchListAnchors[screen]
        ?.firstOrNull {
          target.key == "res:${it.titleRes}" || (it.title != null && target.key == "text:${it.title}")
        }?.itemIndex
        ?: 0

    delay(200)
    listState.animateScrollToItem(targetIndex)
  }

  return listState to Modifier
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
    val targetIndex =
      settingsSearchListAnchors[screen]
        ?.firstOrNull {
          target.key == "res:${it.titleRes}" || (it.title != null && target.key == "text:${it.title}")
        }?.itemIndex
        ?: 0

    delay(200)
    listState.animateScrollToItem(targetIndex)
  }
  return Modifier
}

@Composable
fun rememberSettingsSearchHighlight(
  screen: Screen,
  scrollState: ScrollState,
  @Suppress("UnusedParameter") highlightColor: Color = Color.Unspecified,
): Modifier {
  val requestedTarget by SettingsSearchNavigation.target.collectAsState()

  LaunchedEffect(requestedTarget, screen) {
    if (requestedTarget?.screen == screen) {
      delay(200)
    }
  }
  return Modifier
}

@Composable
fun rememberSettingsSearchHighlight(
  screen: Screen,
  @Suppress("UnusedParameter") highlightColor: Color = Color.Unspecified,
): Modifier {
  val requestedTarget by SettingsSearchNavigation.target.collectAsState()

  LaunchedEffect(requestedTarget, screen) {
    if (requestedTarget?.screen == screen) {
      delay(200)
    }
  }
  return Modifier
}
