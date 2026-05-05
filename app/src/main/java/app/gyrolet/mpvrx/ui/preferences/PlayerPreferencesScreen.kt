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
import me.zhanghai.compose.preference.SwitchPreference
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
          item { PreferenceSectionHeader(title = "General") }
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
              SwitchPreference(
                value = savePositionOnQuit,
                onValueChange = preferences.savePositionOnQuit::set,
                title = { Text(stringResource(R.string.pref_player_save_position_on_quit)) },
              )

              PreferenceDivider()

              val closeAfterEndOfVideo by preferences.closeAfterReachingEndOfVideo.collectAsState()
              SwitchPreference(
                value = closeAfterEndOfVideo,
                onValueChange = preferences.closeAfterReachingEndOfVideo::set,
                title = { Text(stringResource(R.string.pref_player_close_after_eof)) },
              )

              PreferenceDivider()

              val autoplayNextVideo by preferences.autoplayNextVideo.collectAsState()
              SwitchPreference(
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
              SwitchPreference(
                value = playlistMode,
                onValueChange = preferences.playlistMode::set,
                title = { Text("Enable next/previous navigation") },
                summary = {
                  Text(
                    if (playlistMode) "Show next/previous buttons for all videos in folder"
                    else "Play videos individually (select multiple for playlist)",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val rememberBrightness by preferences.rememberBrightness.collectAsState()
              SwitchPreference(
                value = rememberBrightness,
                onValueChange = preferences.rememberBrightness::set,
                title = { Text(stringResource(R.string.pref_player_remember_brightness)) },
              )

              PreferenceDivider()

              val autoPiPOnNavigation by preferences.autoPiPOnNavigation.collectAsState()
              SwitchPreference(
                value = autoPiPOnNavigation,
                onValueChange = preferences.autoPiPOnNavigation::set,
                title = { Text("Auto Picture-in-Picture") },
                summary = {
                  Text(
                    "Automatically enter PiP when pressing home or back",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val keepScreenOnWhenPaused by preferences.keepScreenOnWhenPaused.collectAsState()
              SwitchPreference(
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
              SwitchPreference(
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
              SwitchPreference(
                value = showDoubleTapOvals,
                onValueChange = preferences.showDoubleTapOvals::set,
                title = { Text(stringResource(R.string.show_splash_ovals_on_double_tap_to_seek)) },
              )

              PreferenceDivider()

              val showSeekTimeWhileSeeking by preferences.showSeekTimeWhileSeeking.collectAsState()
              SwitchPreference(
                value = showSeekTimeWhileSeeking,
                onValueChange = preferences.showSeekTimeWhileSeeking::set,
                title = { Text(stringResource(R.string.show_time_on_double_tap_to_seek)) },
              )

              PreferenceDivider()

              val usePreciseSeeking by preferences.usePreciseSeeking.collectAsState()
              SwitchPreference(
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
              SwitchPreference(
                value = enableIntroDb,
                onValueChange = preferences.enableIntroDb::set,
                title = { Text("Use online skip markers") },
                summary = {
                  Text(
                    "Fetch intro, recap, outro, credits, and preview markers from the selected provider.",
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
                  title = { Text("Online marker provider") },
                  summary = {
                    Text(
                      introSegmentProvider.displayName,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )
              }

              PreferenceDivider()

              val detectFromChapters by preferences.detectIntroOutroFromChapters.collectAsState()
              SwitchPreference(
                value = detectFromChapters,
                onValueChange = preferences.detectIntroOutroFromChapters::set,
                title = { Text("Detect intro/outro from chapter titles") },
                summary = {
                  Text(
                    "Analyze chapter name variants like opening/ending/credits and create skip markers.",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val autoSkipIntro by preferences.autoSkipIntro.collectAsState()
              SwitchPreference(
                value = autoSkipIntro,
                onValueChange = preferences.autoSkipIntro::set,
                title = { Text("Auto-skip intro") },
              )

              PreferenceDivider()

              val autoSkipOutro by preferences.autoSkipOutro.collectAsState()
              SwitchPreference(
                value = autoSkipOutro,
                onValueChange = preferences.autoSkipOutro::set,
                title = { Text("Auto-skip outro") },
              )
            }
          }

          // ── Display & Controls ────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "Display & Controls") }
          item {
            PreferenceCard {
              val showSystemStatusBar by preferences.showSystemStatusBar.collectAsState()
              SwitchPreference(
                value = showSystemStatusBar,
                onValueChange = preferences.showSystemStatusBar::set,
                title = { Text(stringResource(R.string.pref_player_display_show_status_bar)) },
              )

              PreferenceDivider()

              val showSystemNavigationBar by preferences.showSystemNavigationBar.collectAsState()
              SwitchPreference(
                value = showSystemNavigationBar,
                onValueChange = preferences.showSystemNavigationBar::set,
                title = { Text("Show navigation bar with controls") },
              )

              PreferenceDivider()

              val reduceMotion by preferences.reduceMotion.collectAsState()
              SwitchPreference(
                value = reduceMotion,
                onValueChange = preferences.reduceMotion::set,
                title = { Text(stringResource(R.string.pref_player_display_reduce_player_animation)) },
              )

              PreferenceDivider()

              val showLoadingCircle by preferences.showLoadingCircle.collectAsState()
              SwitchPreference(
                value = showLoadingCircle,
                onValueChange = preferences.showLoadingCircle::set,
                title = { Text(stringResource(R.string.pref_player_controls_show_loading_circle)) },
              )

              PreferenceDivider()

              val allowGesturesInPanels by preferences.allowGesturesInPanels.collectAsState()
              SwitchPreference(
                value = allowGesturesInPanels,
                onValueChange = preferences.allowGesturesInPanels::set,
                title = { Text(stringResource(R.string.pref_player_controls_allow_gestures_in_panels)) },
              )

              PreferenceDivider()

              val swapVolumeAndBrightness by preferences.swapVolumeAndBrightness.collectAsState()
              SwitchPreference(
                value = swapVolumeAndBrightness,
                onValueChange = preferences.swapVolumeAndBrightness::set,
                title = { Text(stringResource(R.string.swap_the_volume_and_brightness_slider)) },
              )
            }
          }

          // ── Overlays ─────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "Gesture & Action Overlays") }
          item {
            PreferenceCard {
              val showVolumeGestureOverlay by preferences.showVolumeGestureOverlay.collectAsState()
              SwitchPreference(
                value = showVolumeGestureOverlay,
                onValueChange = preferences.showVolumeGestureOverlay::set,
                title = { Text("Volume slider overlay") },
                summary = {
                  Text(
                    "Show the vertical volume pill while swiping for volume",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showBrightnessGestureOverlay by preferences.showBrightnessGestureOverlay.collectAsState()
              SwitchPreference(
                value = showBrightnessGestureOverlay,
                onValueChange = preferences.showBrightnessGestureOverlay::set,
                title = { Text("Brightness slider overlay") },
                summary = {
                  Text(
                    "Show the vertical brightness pill while swiping for brightness",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showHoldSpeedOverlay by preferences.showHoldSpeedOverlay.collectAsState()
              SwitchPreference(
                value = showHoldSpeedOverlay,
                onValueChange = preferences.showHoldSpeedOverlay::set,
                title = { Text("Hold speed overlay") },
                summary = {
                  Text(
                    "Show speed badge and slider while long-pressing to boost playback speed",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showAspectRatioOverlay by preferences.showAspectRatioOverlay.collectAsState()
              SwitchPreference(
                value = showAspectRatioOverlay,
                onValueChange = preferences.showAspectRatioOverlay::set,
                title = { Text("Aspect ratio feedback") },
                summary = {
                  Text(
                    "Show a brief pill when cycling aspect ratio (16:9, Fit, Crop…)",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showZoomLevelOverlay by preferences.showZoomLevelOverlay.collectAsState()
              SwitchPreference(
                value = showZoomLevelOverlay,
                onValueChange = preferences.showZoomLevelOverlay::set,
                title = { Text("Zoom level feedback") },
                summary = {
                  Text(
                    "Show zoom percentage pill when pinching to zoom",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showRepeatShuffleOverlay by preferences.showRepeatShuffleOverlay.collectAsState()
              SwitchPreference(
                value = showRepeatShuffleOverlay,
                onValueChange = preferences.showRepeatShuffleOverlay::set,
                title = { Text("Repeat & shuffle feedback") },
                summary = {
                  Text(
                    "Show pill when toggling repeat mode or shuffle",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showActionFeedbackOverlay by preferences.showActionFeedbackOverlay.collectAsState()
              SwitchPreference(
                value = showActionFeedbackOverlay,
                onValueChange = preferences.showActionFeedbackOverlay::set,
                title = { Text("Action feedback pills") },
                summary = {
                  Text(
                    "Show brief text pills from custom buttons, ambient toggle, subtitle drag, and Lua scripts",
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
