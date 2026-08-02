/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.preferences

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioChannels
import app.gyrolet.mpvrx.preferences.AudioPlayerOrientation
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.AudioVisualizerStyle
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLibraryType
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.TextFieldPreference
import org.koin.compose.koinInject

@Serializable
object AudioPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val backstack = LocalBackStack.current
    val preferences = koinInject<AudioPreferences>()
    val browserPreferences = koinInject<BrowserPreferences>()
    val notificationPermissionLauncher =
      rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
      ) { _ -> }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_audio),
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
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_audio))
          }

          item {
            PreferenceCard {
              val includeAudioBrowser by browserPreferences.includeAudioBrowser.collectAsState()
              SwitchPreference(
                value = includeAudioBrowser,
                onValueChange = { enabled ->
                  browserPreferences.includeAudioBrowser.set(enabled)
                  if (!enabled) {
                    browserPreferences.mediaLibraryType.set(MediaLibraryType.Video)
                  }
                },
                title = {
                  Text(
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_include_audio_files),
                  )
                },
                summary = {
                  Text(
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.ui_show_audio_files_in_the_browser,
                    ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              if (includeAudioBrowser) {
                PreferenceDivider()
                val minimumAudioDurationSeconds by browserPreferences.minimumAudioDurationSeconds.collectAsState()
                SliderPreference(
                  value = minimumAudioDurationSeconds.toFloat(),
                  onValueChange = { browserPreferences.minimumAudioDurationSeconds.set(it.toInt()) },
                  title = {
                    Text(
                      androidx.compose.ui.res
                        .stringResource(app.gyrolet.mpvrx.R.string.ui_minimum_audio_duration),
                    )
                  },
                  valueRange = 0f..120f,
                  valueSteps = 24,
                  summary = {
                    Text(
                      when (minimumAudioDurationSeconds) {
                        0 -> "Any duration"
                        60 -> "1 min"
                        120 -> "2 min"
                        else -> "${minimumAudioDurationSeconds}s"
                      },
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  onSliderValueChange = { browserPreferences.minimumAudioDurationSeconds.set(it.toInt()) },
                  sliderValue = minimumAudioDurationSeconds.toFloat(),
                )
              }

              PreferenceDivider()
              val audioVisualizerStyle by preferences.audioVisualizerStyle.collectAsState()
              ListPreference(
                  value = audioVisualizerStyle,
                  onValueChange = { preferences.audioVisualizerStyle.set(it) },
                  values = AudioVisualizerStyle.entries,
                  valueToText = { AnnotatedString(resources.getString(it.title)) },
                  title = { Text(stringResource(R.string.pref_audio_visualizer_style_title)) },
                  summary = {
                    Column {
                      Text(
                        stringResource(audioVisualizerStyle.title),
                        color = MaterialTheme.colorScheme.outline,
                      )
                      if (audioVisualizerStyle == AudioVisualizerStyle.Galaxy ||
                        audioVisualizerStyle == AudioVisualizerStyle.Cuboid
                      ) {
                        Text(
                          text =
                            if (audioVisualizerStyle == AudioVisualizerStyle.Cuboid) {
                              "Inspired by the Cuboid Warptunnel concept by Niklas Knaack"
                            } else {
                              stringResource(R.string.pref_audio_visualizer_galaxy_credit)
                            },
                          color = MaterialTheme.colorScheme.primary,
                          style = MaterialTheme.typography.bodySmall,
                          textDecoration = TextDecoration.Underline,
                          modifier =
                            Modifier.clickable {
                              context.startActivity(
                                Intent(
                                  Intent.ACTION_VIEW,
                                  Uri.parse(
                                    if (audioVisualizerStyle == AudioVisualizerStyle.Cuboid) {
                                      "https://codepen.io/NiklasKnaack/pen/WyWqja"
                                    } else {
                                      "https://codepen.io/Zain-Raza-the-sasster/pen/ByBeKqa"
                                    },
                                  ),
                                ),
                              )
                            },
                        )
                      }
                    }
                  },
              )

              PreferenceDivider()
              val audioOrientation by preferences.audioOrientation.collectAsState()
              ListPreference(
                value = audioOrientation,
                onValueChange = { preferences.audioOrientation.set(it) },
                values = AudioPlayerOrientation.entries,
                valueToText = { AnnotatedString(resources.getString(it.titleRes)) },
                title = { Text(stringResource(R.string.pref_audio_orientation_title)) },
                summary = {
                  Text(
                    stringResource(audioOrientation.titleRes),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val preferredLanguages by preferences.preferredLanguages.collectAsState()
              TextFieldPreference(
                value = preferredLanguages,
                onValueChange = { preferences.preferredLanguages.set(it) },
                textToValue = { input ->
                  input
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .joinToString(",")
                },
                title = { Text(stringResource(R.string.pref_preferred_languages)) },
                summary = {
                  if (preferredLanguages.isNotBlank()) {
                    Text(
                      preferredLanguages,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  } else {
                    Text(
                      stringResource(R.string.not_set_video_default),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                },
                textField = { value, onValueChange, _ ->
                  Column {
                    Text(stringResource(R.string.pref_audio_preferred_language))
                    TextField(
                      value,
                      onValueChange,
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }
                },
              )

              PreferenceDivider()
              val audioPitchCorrection by preferences.audioPitchCorrection.collectAsState()
              SwitchPreference(
                value = audioPitchCorrection,
                onValueChange = { preferences.audioPitchCorrection.set(it) },
                title = { Text(stringResource(R.string.pref_audio_pitch_correction_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_pitch_correction_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val volumeNormalization by preferences.volumeNormalization.collectAsState()
              SwitchPreference(
                value = volumeNormalization,
                onValueChange = { preferences.volumeNormalization.set(it) },
                title = { Text(stringResource(R.string.pref_audio_volume_normalization_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_volume_normalization_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val drcEnabled by preferences.drcEnabled.collectAsState()
              SwitchPreference(
                value = drcEnabled,
                onValueChange = { preferences.drcEnabled.set(it) },
                title = { Text(stringResource(R.string.pref_audio_drc_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_drc_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val audioBackgroundPlayback by preferences.audioBackgroundPlayback.collectAsState()
              SwitchPreference(
                value = audioBackgroundPlayback,
                onValueChange = { enabled ->
                  preferences.audioBackgroundPlayback.set(enabled)
                  if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                      PackageManager.PERMISSION_GRANTED
                    ) {
                      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                  }
                },
                title = { Text(stringResource(R.string.pref_audio_background_playback_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_background_playback_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val audioChannel by preferences.audioChannels.collectAsState()
              ListPreference(
                value = audioChannel,
                onValueChange = { preferences.audioChannels.set(it) },
                values = AudioChannels.entries,
                valueToText = { AnnotatedString(resources.getString(it.title)) },
                title = { Text(text = stringResource(id = R.string.pref_audio_channels)) },
                summary = {
                  Text(
                    text = stringResource(audioChannel.title),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val volumeBoostCap by preferences.volumeBoostCap.collectAsState()
              SliderPreference(
                value = volumeBoostCap.toFloat(),
                onValueChange = { preferences.volumeBoostCap.set(it.toInt()) },
                title = { Text(stringResource(R.string.pref_audio_volume_boost_cap)) },
                valueRange = 0f..200f,
                summary = {
                  Text(
                    if (volumeBoostCap == 0) {
                      stringResource(R.string.generic_disabled)
                    } else {
                      volumeBoostCap.toString()
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onSliderValueChange = { preferences.volumeBoostCap.set(it.toInt()) },
                sliderValue = volumeBoostCap.toFloat(),
              )
            }
          }
        }
      }
    }
  }
}
