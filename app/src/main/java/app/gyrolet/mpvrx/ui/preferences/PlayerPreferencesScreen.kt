package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotFormat
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import me.zhanghai.compose.preference.TextFieldPreference
import androidx.compose.material3.TextField
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp

@Serializable
object PlayerPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val resources = LocalResources.current
    val preferences = koinInject<PlayerPreferences>()
    var showTemplateDialog by remember { mutableStateOf(false) }
    var templateDraft by remember { mutableStateOf("") }
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
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backstack.popSafely() }) {
                Icon(
                  Icons.RoundedFilled.ArrowBack,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
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

              PreferenceDivider()

              val enableMediaInfoIntent by preferences.enableMediaInfoIntent.collectAsState()
              val context = LocalContext.current
              SwitchPreference(
                value = enableMediaInfoIntent,
                onValueChange = { enabled ->
                  preferences.enableMediaInfoIntent.set(enabled)
                  val componentName = ComponentName(context, "app.gyrolet.mpvrx.ui.mediainfo.MediaInfoActivityAlias")
                  val newState = if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                  } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                  }
                  try {
                    context.packageManager.setComponentEnabledSetting(
                      componentName,
                      newState,
                      PackageManager.DONT_KILL_APP
                    )
                  } catch (e: Exception) {
                    android.util.Log.e("PlayerPreferencesScreen", "Failed to set alias state", e)
                  }
                },
                title = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_show_media_info_in_chooser)) },
                summary = {
                  Text(
                    androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_show_media_info_in_system),
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

              val showBufferedRange by preferences.showBufferedRange.collectAsState()
              SwitchPreference(
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

              val showChapterIndicators by preferences.showChapterIndicators.collectAsState()
              SwitchPreference(
                value = showChapterIndicators,
                onValueChange = preferences.showChapterIndicators::set,
                title = { Text(stringResource(R.string.pref_player_show_chapter_indicators_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_player_show_chapter_indicators_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val usePreciseSeeking by preferences.usePreciseSeeking.collectAsState()
              SwitchPreference(
                value = usePreciseSeeking,
                onValueChange = preferences.usePreciseSeeking::set,
                title = { Text(stringResource(R.string.pref_player_use_precise_seeking)) },
              )

              PreferenceDivider()

              val useThumbFastSeekPreview by preferences.useThumbFastSeekPreview.collectAsState()
              SwitchPreference(
                value = useThumbFastSeekPreview,
                onValueChange = preferences.useThumbFastSeekPreview::set,
                title = { Text(stringResource(R.string.pref_player_seek_preview_thumbfast_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_player_seek_preview_thumbfast_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
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
              SwitchPreference(
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

              if (detectFromChapters) {
                PreferenceDivider()

                val customIntroKeywordsEnabled by preferences.customIntroKeywordsEnabled.collectAsState()
                SwitchPreference(
                  value = customIntroKeywordsEnabled,
                  onValueChange = preferences.customIntroKeywordsEnabled::set,
                  title = { Text(stringResource(R.string.pref_custom_intro_keywords_enabled)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_custom_intro_keywords_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                if (customIntroKeywordsEnabled) {
                  PreferenceDivider()

                  val customIntroKeywords by preferences.customIntroKeywords.collectAsState()
                  TextFieldPreference(
                    value = customIntroKeywords,
                    onValueChange = preferences.customIntroKeywords::set,
                    textToValue = { it },
                    title = { Text(stringResource(R.string.pref_custom_intro_keywords_title)) },
                    summary = {
                      Text(
                        customIntroKeywords.ifBlank { "Not set" },
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    textField = { value, onValueChange, _ ->
                      Column {
                        Text(stringResource(R.string.pref_custom_intro_keywords_dialog_desc))
                        TextField(
                          value = value,
                          onValueChange = onValueChange,
                          modifier = Modifier.fillMaxWidth(),
                          placeholder = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_e_g_intro_opening_op)) },
                          singleLine = true,
                        )
                      }
                    },
                  )
                }

                PreferenceDivider()

                val customOutroKeywordsEnabled by preferences.customOutroKeywordsEnabled.collectAsState()
                SwitchPreference(
                  value = customOutroKeywordsEnabled,
                  onValueChange = preferences.customOutroKeywordsEnabled::set,
                  title = { Text(stringResource(R.string.pref_custom_outro_keywords_enabled)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_custom_outro_keywords_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                if (customOutroKeywordsEnabled) {
                  PreferenceDivider()

                  val customOutroKeywords by preferences.customOutroKeywords.collectAsState()
                  TextFieldPreference(
                    value = customOutroKeywords,
                    onValueChange = preferences.customOutroKeywords::set,
                    textToValue = { it },
                    title = { Text(stringResource(R.string.pref_custom_outro_keywords_title)) },
                    summary = {
                      Text(
                        customOutroKeywords.ifBlank { "Not set" },
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    textField = { value, onValueChange, _ ->
                      Column {
                        Text(stringResource(R.string.pref_custom_outro_keywords_dialog_desc))
                        TextField(
                          value = value,
                          onValueChange = onValueChange,
                          modifier = Modifier.fillMaxWidth(),
                          placeholder = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_e_g_outro_ending_ed)) },
                          singleLine = true,
                        )
                      }
                    },
                  )
                }
              }

              PreferenceDivider()

              val autoSkipIntro by preferences.autoSkipIntro.collectAsState()
              SwitchPreference(
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
              SwitchPreference(
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
              SwitchPreference(
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

          // ── Screenshots ─────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = stringResource(R.string.pref_screenshots_section)) }
          item {
            PreferenceCard {
              val screenshotFormat by preferences.screenshotFormat.collectAsState()
              ListPreference(
                value = screenshotFormat,
                onValueChange = preferences.screenshotFormat::set,
                values = ScreenshotFormat.entries,
                valueToText = { AnnotatedString(it.title) },
                title = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_image_format)) },
                summary = { Text("${screenshotFormat.title} .${screenshotFormat.extension}", color = MaterialTheme.colorScheme.outline) },
              )

              PreferenceDivider()

              val includeSubtitles by preferences.includeSubtitlesInSnapshot.collectAsState()
              SwitchPreference(
                value = includeSubtitles,
                onValueChange = preferences.includeSubtitlesInSnapshot::set,
                title = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_include_subtitles_in_screenshots)) },
              )

              PreferenceDivider()

              val screenshotTemplate by preferences.screenshotTemplate.collectAsState()
              Preference(
                title = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_filename_template)) },
                summary = { Text(screenshotTemplate, color = MaterialTheme.colorScheme.outline) },
                onClick = {
                  templateDraft = screenshotTemplate
                  showTemplateDialog = true
                },
              )

              PreferenceDivider()

              val screenshotQuality by preferences.screenshotQuality.collectAsState()
              SliderPreference(
                value = screenshotQuality.toFloat(),
                onValueChange = { preferences.screenshotQuality.set(it.roundToInt().coerceIn(1, 100)) },
                title = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_jpeg_webp_quality)) },
                valueRange = 1f..100f,
                summary = { Text("$screenshotQuality", color = MaterialTheme.colorScheme.outline) },
                onSliderValueChange = { preferences.screenshotQuality.set(it.roundToInt().coerceIn(1, 100)) },
                sliderValue = screenshotQuality.toFloat(),
              )

              PreferenceDivider()

              val pngCompression by preferences.screenshotPngCompression.collectAsState()
              SliderPreference(
                value = pngCompression.toFloat(),
                onValueChange = { preferences.screenshotPngCompression.set(it.roundToInt().coerceIn(0, 9)) },
                title = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_png_compression)) },
                valueRange = 0f..9f,
                summary = { Text("$pngCompression", color = MaterialTheme.colorScheme.outline) },
                onSliderValueChange = { preferences.screenshotPngCompression.set(it.roundToInt().coerceIn(0, 9)) },
                sliderValue = pngCompression.toFloat(),
              )

              if (screenshotFormat == ScreenshotFormat.WEBP) {
                PreferenceDivider()

                val webpLossless by preferences.screenshotWebpLossless.collectAsState()
                SwitchPreference(
                  value = webpLossless,
                  onValueChange = preferences.screenshotWebpLossless::set,
                  title = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_webp_lossless)) },
                  summary = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_uses_mpv_native_lossless_output_android_fallback_uses_lossless_o), color = MaterialTheme.colorScheme.outline) },
                )
              }
            }
          }

          // ── Overlays ─────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_overlays)) }
          item {
            PreferenceCard {
              val showVolumeGestureOverlay by preferences.showVolumeGestureOverlay.collectAsState()
              SwitchPreference(
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
              SwitchPreference(
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
              SwitchPreference(
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
              SwitchPreference(
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
              SwitchPreference(
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
              SwitchPreference(
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
              SwitchPreference(
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

              PreferenceDivider()

              val showProviderStatusOverlay by preferences.showProviderStatusOverlay.collectAsState()
              SwitchPreference(
                value = showProviderStatusOverlay,
                onValueChange = preferences.showProviderStatusOverlay::set,
                title = { Text(stringResource(R.string.pref_provider_status_overlay_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_provider_status_overlay_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }
        }
      }
    }
    if (showTemplateDialog) {
      AlertDialog(
        onDismissRequest = { showTemplateDialog = false },
        title = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_filename_template)) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = templateDraft,
              onValueChange = { templateDraft = it },
              label = { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_template)) },
              modifier = Modifier.fillMaxWidth()
            )
            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_use_placeholders_to_customize_the_screenshot_filename_n) +
                  "• %f — Video title or filename\n" +
                  "• %p — Playback position (seconds)\n" +
                  "• %Y, %m, %d — Year, Month, Day\n" +
                  "• %H, %M, %S — Hour, Minute, Second\n" +
                  "• %wH, %wM, %wS, %wT — Wall-clock time (hour, min, sec, ms)",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        },
        confirmButton = {
          TextButton(
            onClick = {
              preferences.screenshotTemplate.set(templateDraft)
              showTemplateDialog = false
            },
          ) {
            Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_save))
          }
        },
        dismissButton = {
          TextButton(onClick = { showTemplateDialog = false }) {
            Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.generic_cancel))
          }
        },
      )
    }
  }
}
