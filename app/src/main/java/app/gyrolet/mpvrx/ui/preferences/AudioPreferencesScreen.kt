package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioChannels
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLibraryType
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import me.zhanghai.compose.preference.TextFieldPreference
import org.koin.compose.koinInject

@Serializable
object AudioPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val resources = LocalResources.current
    val backstack = LocalBackStack.current
    val preferences = koinInject<AudioPreferences>()
    val browserPreferences = koinInject<BrowserPreferences>()

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
                title = { Text("Include audio files") },
                summary = {
                  Text(
                    "Show audio files in the browser",
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
                  title = { Text("Minimum audio duration") },
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
              val audioBlobEnabled by preferences.audioBlobEnabled.collectAsState()
              SwitchPreference(
                value = audioBlobEnabled,
                onValueChange = { preferences.audioBlobEnabled.set(it) },
                title = { Text("Audio blob visualizer") },
                summary = {
                  Text(
                    "Show OpenGL blob visualizer when playing audio",
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
                  input.split(",")
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
              val backgroundPlayback by preferences.backgroundPlayback.collectAsState()
              SwitchPreference(
                value = backgroundPlayback,
                onValueChange = { preferences.backgroundPlayback.set(it) },
                title = { Text(stringResource(R.string.background_playback_title)) },
                summary = {
                  Text(
                    "Keep audio and video playing when leaving the player or locking the screen",
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
