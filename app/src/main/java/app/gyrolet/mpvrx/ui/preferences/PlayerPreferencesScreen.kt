package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.IntroSegmentProvider
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.player.PlayerOrientation
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.toFixed
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import app.gyrolet.mpvrx.ui.preferences.components.AdaptiveSwitchPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import androidx.compose.ui.text.AnnotatedString

@Serializable
object PlayerPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val resources = LocalResources.current
    val preferences = koinInject<PlayerPreferences>()
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(id = R.string.pref_player),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              Icon(
                Icons.Outlined.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        ) {

          // ── General ───────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_general)) }
          item {
            PreferenceCard {
              val orientation by preferences.orientation.collectAsState()
              ListPreference(
                value = orientation,
                onValueChange = preferences.orientation::set,
                values = PlayerOrientation.entries,
                valueToText = { AnnotatedString(resources.getString(it.titleRes)) },
                title = { Text(stringResource(R.string.pref_player_orientation)) },
                summary = { Text(stringResource(orientation.titleRes), color = MaterialTheme.colorScheme.outline) },
              )

              PreferenceDivider()

              val savePositionOnQuit by preferences.savePositionOnQuit.collectAsState()
              AdaptiveSwitchPreference(
                value = savePositionOnQuit,
                onValueChange = preferences.savePositionOnQuit::set,
                title = { Text(stringResource(R.string.pref_player_save_position_on_quit)) },
              )

              PreferenceDivider()

              val closeAfterEndOfVideo by preferences.closeAfterReachingEndOfVideo.collectAsState()
              AdaptiveSwitchPreference(
                value = closeAfterEndOfVideo,
                onValueChange = preferences.closeAfterReachingEndOfVideo::set,
                title = { Text(stringResource(R.string.pref_player_close_after_eof)) },
              )

              PreferenceDivider()

              val autoplayNextVideo by preferences.autoplayNextVideo.collectAsState()
              AdaptiveSwitchPreference(
                value = autoplayNextVideo,
                onValueChange = preferences.autoplayNextVideo::set,
                title = { Text(stringResource(R.string.pref_autoplay_next_video_title)) },
                summary = {
                  Text(
                    if (autoplayNextVideo) stringResource(R.string.pref_autoplay_next_video_summary)
                    else stringResource(R.string.pref_autoplay_next_video_summary_disabled),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val playlistMode by preferences.playlistMode.collectAsState()
              AdaptiveSwitchPreference(
                value = playlistMode,
                onValueChange = preferences.playlistMode::set,
                title = { Text(stringResource(R.string.pref_playlist_mode_title)) },
                summary = {
                  Text(
                    if (playlistMode) stringResource(R.string.pref_playlist_mode_summary)
                    else stringResource(R.string.pref_playlist_mode_summary_disabled),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val rememberBrightness by preferences.rememberBrightness.collectAsState()
              AdaptiveSwitchPreference(
                value = rememberBrightness,
                onValueChange = preferences.rememberBrightness::set,
                title = { Text(stringResource(R.string.pref_player_remember_brightness)) },
              )

              PreferenceDivider()

              val autoPiPOnNavigation by preferences.autoPiPOnNavigation.collectAsState()
              AdaptiveSwitchPreference(
                value = autoPiPOnNavigation,
                onValueChange = preferences.autoPiPOnNavigation::set,
                title = { Text(stringResource(R.string.pref_auto_pip_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_auto_pip_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val keepScreenOnWhenPaused by preferences.keepScreenOnWhenPaused.collectAsState()
              AdaptiveSwitchPreference(
                value = keepScreenOnWhenPaused,
                onValueChange = preferences.keepScreenOnWhenPaused::set,
                title = { Text(stringResource(R.string.pref_player_keep_screen_on_when_paused_title)) },
                summary = {
                  Text(
                    if (keepScreenOnWhenPaused) stringResource(R.string.pref_player_keep_screen_on_when_paused_summary)
                    else stringResource(R.string.pref_player_keep_screen_on_when_paused_summary_disabled),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val autoplayAfterScreenUnlock by preferences.autoplayAfterScreenUnlock.collectAsState()
              AdaptiveSwitchPreference(
                value = autoplayAfterScreenUnlock,
                onValueChange = preferences.autoplayAfterScreenUnlock::set,
                title = { Text(stringResource(R.string.pref_player_autoplay_after_screen_unlock_title)) },
                summary = {
                  Text(
                    if (autoplayAfterScreenUnlock) stringResource(R.string.pref_player_autoplay_after_screen_unlock_summary)
                    else stringResource(R.string.pref_player_autoplay_after_screen_unlock_summary_disabled),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // ── Seeking ───────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = stringResource(R.string.pref_player_seeking_title)) }
          item {
            PreferenceCard {
              val showDoubleTapOvals by preferences.showDoubleTapOvals.collectAsState()
              AdaptiveSwitchPreference(
                value = showDoubleTapOvals,
                onValueChange = preferences.showDoubleTapOvals::set,
                title = { Text(stringResource(R.string.show_splash_ovals_on_double_tap_to_seek)) },
              )

              PreferenceDivider()

              val showSeekTimeWhileSeeking by preferences.showSeekTimeWhileSeeking.collectAsState()
              AdaptiveSwitchPreference(
                value = showSeekTimeWhileSeeking,
                onValueChange = preferences.showSeekTimeWhileSeeking::set,
                title = { Text(stringResource(R.string.show_time_on_double_tap_to_seek)) },
              )

              PreferenceDivider()

              val showBufferedRange by preferences.showBufferedRange.collectAsState()
              AdaptiveSwitchPreference(
                value = showBufferedRange,
                onValueChange = preferences.showBufferedRange::set,
                title = { Text(stringResource(R.string.pref_player_show_buffered_range_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_player_show_buffered_range_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val usePreciseSeeking by preferences.usePreciseSeeking.collectAsState()
              AdaptiveSwitchPreference(
                value = usePreciseSeeking,
                onValueChange = preferences.usePreciseSeeking::set,
                title = { Text(stringResource(R.string.pref_player_use_precise_seeking)) },
              )

              PreferenceDivider()

              val customSkipDuration by preferences.customSkipDuration.collectAsState()
              SliderPreference(
                value = customSkipDuration.toFloat(),
                onValueChange = { preferences.customSkipDuration.set(it.roundToInt()) },
                title = { Text(stringResource(R.string.pref_player_custom_skip_duration_title)) },
                valueRange = 5f..180f,
                summary = {
                  Text(
                    "${stringResource(R.string.pref_player_custom_skip_duration_summary)} ($customSkipDuration s)",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onSliderValueChange = { preferences.customSkipDuration.set(it.roundToInt()) },
                sliderValue = customSkipDuration.toFloat(),
              )

              PreferenceDivider()

              val enableIntroDb by preferences.enableIntroDb.collectAsState()
              AdaptiveSwitchPreference(
                value = enableIntroDb,
                onValueChange = preferences.enableIntroDb::set,
                title = { Text(stringResource(R.string.pref_online_skip_markers_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_online_skip_markers_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              if (enableIntroDb) {
                PreferenceDivider()

                val introSegmentProvider by preferences.introSegmentProvider.collectAsState()
                ListPreference(
                  value = introSegmentProvider,
                  onValueChange = preferences.introSegmentProvider::set,
                  values = IntroSegmentProvider.entries,
                valueToText = { AnnotatedString(it.displayName) },
                title = { Text(stringResource(R.string.pref_marker_provider_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_marker_provider_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                )
              }

              PreferenceDivider()

              val detectFromChapters by preferences.detectIntroOutroFromChapters.collectAsState()
              AdaptiveSwitchPreference(
                value = detectFromChapters,
                onValueChange = preferences.detectIntroOutroFromChapters::set,
                title = { Text(stringResource(R.string.pref_chapter_detect_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_chapter_detect_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val autoSkipIntro by preferences.autoSkipIntro.collectAsState()
              AdaptiveSwitchPreference(
                value = autoSkipIntro,
                onValueChange = preferences.autoSkipIntro::set,
                title = { Text(stringResource(R.string.pref_auto_skip_intro_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_auto_skip_intro_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val autoSkipOutro by preferences.autoSkipOutro.collectAsState()
              AdaptiveSwitchPreference(
                value = autoSkipOutro,
                onValueChange = preferences.autoSkipOutro::set,
                title = { Text(stringResource(R.string.pref_auto_skip_outro_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_auto_skip_outro_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // ── Display & Controls ────────────────────────────────────────────
          item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_display_controls)) }
          item {
            PreferenceCard {
              val showSystemStatusBar by preferences.showSystemStatusBar.collectAsState()
              AdaptiveSwitchPreference(
                value = showSystemStatusBar,
                onValueChange = preferences.showSystemStatusBar::set,
                title = { Text(stringResource(R.string.pref_player_display_show_status_bar)) },
              )

              PreferenceDivider()

              val showSystemNavigationBar by preferences.showSystemNavigationBar.collectAsState()
              AdaptiveSwitchPreference(
                value = showSystemNavigationBar,
                onValueChange = preferences.showSystemNavigationBar::set,
                title = { Text(stringResource(R.string.pref_nav_bar_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_nav_bar_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val safeAreaWindow by preferences.safeAreaWindow.collectAsState()
              AdaptiveSwitchPreference(
                value = safeAreaWindow,
                onValueChange = preferences.safeAreaWindow::set,
                title = { Text(stringResource(R.string.pref_player_safe_area_window_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_player_safe_area_window_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val reduceMotion by preferences.reduceMotion.collectAsState()
              AdaptiveSwitchPreference(
                value = reduceMotion,
                onValueChange = preferences.reduceMotion::set,
                title = { Text(stringResource(R.string.pref_player_display_reduce_player_animation)) },
              )

              PreferenceDivider()

              val showLoadingCircle by preferences.showLoadingCircle.collectAsState()
              AdaptiveSwitchPreference(
                value = showLoadingCircle,
                onValueChange = preferences.showLoadingCircle::set,
                title = { Text(stringResource(R.string.pref_player_controls_show_loading_circle)) },
              )

              PreferenceDivider()

              val allowGesturesInPanels by preferences.allowGesturesInPanels.collectAsState()
              AdaptiveSwitchPreference(
                value = allowGesturesInPanels,
                onValueChange = preferences.allowGesturesInPanels::set,
                title = { Text(stringResource(R.string.pref_player_controls_allow_gestures_in_panels)) },
              )

              PreferenceDivider()

              val swapVolumeAndBrightness by preferences.swapVolumeAndBrightness.collectAsState()
              AdaptiveSwitchPreference(
                value = swapVolumeAndBrightness,
                onValueChange = preferences.swapVolumeAndBrightness::set,
                title = { Text(stringResource(R.string.swap_the_volume_and_brightness_slider)) },
              )
            }
          }

          // ── Overlays ─────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_overlays)) }
          item {
            PreferenceCard {
              val showVolumeGestureOverlay by preferences.showVolumeGestureOverlay.collectAsState()
              AdaptiveSwitchPreference(
                value = showVolumeGestureOverlay,
                onValueChange = preferences.showVolumeGestureOverlay::set,
                title = { Text(stringResource(R.string.pref_volume_overlay_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_volume_overlay_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showBrightnessGestureOverlay by preferences.showBrightnessGestureOverlay.collectAsState()
              AdaptiveSwitchPreference(
                value = showBrightnessGestureOverlay,
                onValueChange = preferences.showBrightnessGestureOverlay::set,
                title = { Text(stringResource(R.string.pref_brightness_overlay_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_brightness_overlay_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showHoldSpeedOverlay by preferences.showHoldSpeedOverlay.collectAsState()
              AdaptiveSwitchPreference(
                value = showHoldSpeedOverlay,
                onValueChange = preferences.showHoldSpeedOverlay::set,
                title = { Text(stringResource(R.string.pref_hold_speed_overlay_pref_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_hold_speed_overlay_pref_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showAspectRatioOverlay by preferences.showAspectRatioOverlay.collectAsState()
              AdaptiveSwitchPreference(
                value = showAspectRatioOverlay,
                onValueChange = preferences.showAspectRatioOverlay::set,
                title = { Text(stringResource(R.string.pref_aspect_ratio_overlay_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_aspect_ratio_overlay_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showZoomLevelOverlay by preferences.showZoomLevelOverlay.collectAsState()
              AdaptiveSwitchPreference(
                value = showZoomLevelOverlay,
                onValueChange = preferences.showZoomLevelOverlay::set,
                title = { Text(stringResource(R.string.pref_zoom_overlay_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_zoom_overlay_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showRepeatShuffleOverlay by preferences.showRepeatShuffleOverlay.collectAsState()
              AdaptiveSwitchPreference(
                value = showRepeatShuffleOverlay,
                onValueChange = preferences.showRepeatShuffleOverlay::set,
                title = { Text(stringResource(R.string.pref_repeat_shuffle_overlay_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_repeat_shuffle_overlay_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showActionFeedbackOverlay by preferences.showActionFeedbackOverlay.collectAsState()
              AdaptiveSwitchPreference(
                value = showActionFeedbackOverlay,
                onValueChange = preferences.showActionFeedbackOverlay::set,
                title = { Text(stringResource(R.string.pref_action_feedback_overlay_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_action_feedback_overlay_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }
        }
      }
    }
  }
}
