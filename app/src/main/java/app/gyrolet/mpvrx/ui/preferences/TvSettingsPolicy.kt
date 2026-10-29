/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor

/** One availability policy for the visible settings and their search results. Never writes prefs. */
internal object TvSettingsPolicy {
  private val mobileTitles = setOf(
    R.string.pref_player_orientation,
    R.string.pref_audio_orientation_title,
    R.string.pref_player_remember_brightness,
    R.string.pref_auto_pip_home_only_title,
    R.string.pref_auto_pip_title,
    R.string.pref_enable_video_mini_player_title,
    R.string.pref_player_autoplay_after_screen_unlock_title,
    R.string.pref_player_external_display_projection_title,
    R.string.pref_advanced_notification_style,
    R.string.pref_player_controls_drawer_title,
    R.string.pref_player_display_show_status_bar,
    R.string.pref_nav_bar_title,
    R.string.pref_player_safe_area_window_title,
    R.string.pref_player_controls_allow_gestures_in_panels,
    R.string.swap_the_volume_and_brightness_slider,
    R.string.pref_brightness_overlay_title,
    R.string.pref_hold_speed_overlay_pref_title,
    R.string.pref_aspect_ratio_overlay_title,
    R.string.pref_zoom_overlay_title,
    R.string.pref_repeat_shuffle_overlay_title,
    R.string.pref_action_feedback_overlay_title,
    R.string.pref_resume_indicator_overlay_title,
    R.string.pref_provider_status_overlay_title,
    R.string.pref_layout_top_right_controls,
    R.string.pref_layout_bottom_right_controls,
    R.string.pref_layout_bottom_left_controls,
    R.string.pref_layout_portrait_bottom_controls,
    R.string.pref_section_seekbar_style,
    R.string.ui_landscape_seekbar_position,
    R.string.ui_portrait_seekbar_position,
    R.string.pref_player_buttons_opacity,
    R.string.pref_appearance_force_dark_player_buttons_background_title,
    R.string.pref_app_icon_title,
    R.string.pref_nav_actions_section,
    R.string.pref_quick_play_fab_title,
    R.string.pref_quick_play_fab_direct_title,
    R.string.navbar_glow_title,
    R.string.legacy_navigation_bar_title,
    R.string.progressive_navigation_blur_title,
    R.string.pref_custom_buttons_title,
    R.string.show_splash_ovals_on_double_tap_to_seek,
    R.string.show_time_on_double_tap_to_seek,
    R.string.pref_persistent_status_micro_battery,
    R.string.pref_persistent_status_battery,
    R.string.secure_folder_biometric_title,
  )

  fun supportsScreen(screen: Screen): Boolean =
    screen != GesturePreferencesScreen && screen != VideoSwipePreferencesScreen &&
      screen != AppIconPreferencesScreen && screen != CustomButtonScreen &&
      screen !is ControlLayoutEditorScreen

  fun supportsTitle(@StringRes title: Int?): Boolean = title !in mobileTitles

  fun supportsSearch(preference: SearchablePreference): Boolean =
    supportsScreen(preference.screen) && supportsTitle(preference.titleRes) &&
      supportsTitle(preference.targetRes) &&
      // The mobile seekbar style entries use literal names instead of resource titles.
      !(preference.screen == PlayerControlsPreferencesScreen && preference.title != null)

  fun searchEntry(preference: SearchablePreference): SearchablePreference =
    if (preference.screen == PlayerControlsPreferencesScreen && preference.titleRes == R.string.pref_layout_title) {
      preference.copy(
        titleRes = R.string.pref_section_display_controls,
        summaryRes = R.string.pref_persistent_status_summary,
        targetRes = R.string.pref_persistent_status_title,
        keywords = listOf("tv", "television", "remote", "dpad", "controls", "hud", "clock", "timeout"),
      )
    } else preference
}

/** Omits an inapplicable row before composition, including state collection and side effects. */
@Composable
internal fun DevicePreference(
  @StringRes title: Int,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val television = DeviceFormFactor.isTelevision(context)
  if (television && !TvSettingsPolicy.supportsTitle(title)) return
  content()
}
