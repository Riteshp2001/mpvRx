package app.gyrolet.mpvrx.ui.preferences

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.haptics.HapticsManager
import app.gyrolet.mpvrx.preferences.HapticsEngineMode
import app.gyrolet.mpvrx.preferences.HapticsPreferences
import app.gyrolet.mpvrx.preferences.HapticsPreset
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import org.koin.compose.koinInject

@Serializable
object HapticsPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val resources = LocalResources.current
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val preferences = koinInject<HapticsPreferences>()
    val hapticsManager = koinInject<HapticsManager>()

    fun hasAudioPermission(): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
          preferences.enabled.set(false)
          Toast.makeText(
            context,
            context.getString(R.string.haptics_permission_denied),
            Toast.LENGTH_LONG,
          ).show()
        }
      }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_haptics),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              Icon(
                Icons.Default.ArrowBack,
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
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_haptics))
          }

          // --- Main toggle + info -------------------------------------------
          item {
            PreferenceCard {
              val enabled by preferences.enabled.collectAsState()
              SwitchPreference(
                value = enabled,
                onValueChange = { wantOn ->
                  preferences.enabled.set(wantOn)
                  if (wantOn && !hasAudioPermission()) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                  }
                },
                title = { Text(stringResource(R.string.pref_haptics_enable_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_haptics_enable_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              Preference(
                title = { Text(stringResource(R.string.pref_haptics_permission_note_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_haptics_permission_note_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // --- Engine mode + preset -----------------------------------------
          item {
            PreferenceCard {
              val engineMode by preferences.engineMode.collectAsState()
              ListPreference(
                value = engineMode,
                onValueChange = { preferences.engineMode.set(it) },
                values = HapticsEngineMode.entries,
                valueToText = { AnnotatedString(resources.getString(it.title)) },
                title = { Text(stringResource(R.string.pref_haptics_engine_title)) },
                summary = {
                  Text(
                    stringResource(engineMode.title),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val preset by preferences.preset.collectAsState()
              ListPreference(
                value = preset,
                onValueChange = { preferences.preset.set(it) },
                values = HapticsPreset.entries,
                valueToText = { AnnotatedString(resources.getString(it.title)) },
                title = { Text(stringResource(R.string.pref_haptics_preset_title)) },
                summary = {
                  Text(
                    stringResource(preset.title),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // --- Strength + custom sliders ------------------------------------
          item {
            PreferenceCard {
              val masterIntensity by preferences.masterIntensity.collectAsState()
              SliderPreference(
                value = masterIntensity.toFloat(),
                sliderValue = masterIntensity.toFloat(),
                onValueChange = { preferences.masterIntensity.set(it.toInt()) },
                onSliderValueChange = { preferences.masterIntensity.set(it.toInt()) },
                valueRange = 0f..100f,
                title = { Text(stringResource(R.string.pref_haptics_master_intensity)) },
                summary = { Text("$masterIntensity%", color = MaterialTheme.colorScheme.outline) },
              )

              val preset by preferences.preset.collectAsState()
              if (preset == HapticsPreset.Custom) {
                PreferenceDivider()
                val bassGain by preferences.bassGain.collectAsState()
                SliderPreference(
                  value = bassGain.toFloat(),
                  sliderValue = bassGain.toFloat(),
                  onValueChange = { preferences.bassGain.set(it.toInt()) },
                  onSliderValueChange = { preferences.bassGain.set(it.toInt()) },
                  valueRange = 0f..100f,
                  title = { Text(stringResource(R.string.pref_haptics_bass_gain)) },
                  summary = { Text("$bassGain%", color = MaterialTheme.colorScheme.outline) },
                )

                PreferenceDivider()
                val impact by preferences.impactSensitivity.collectAsState()
                SliderPreference(
                  value = impact.toFloat(),
                  sliderValue = impact.toFloat(),
                  onValueChange = { preferences.impactSensitivity.set(it.toInt()) },
                  onSliderValueChange = { preferences.impactSensitivity.set(it.toInt()) },
                  valueRange = 0f..100f,
                  title = { Text(stringResource(R.string.pref_haptics_impact_sensitivity)) },
                  summary = { Text("$impact%", color = MaterialTheme.colorScheme.outline) },
                )

                PreferenceDivider()
                val noise by preferences.noiseThreshold.collectAsState()
                SliderPreference(
                  value = noise.toFloat(),
                  sliderValue = noise.toFloat(),
                  onValueChange = { preferences.noiseThreshold.set(it.toInt()) },
                  onSliderValueChange = { preferences.noiseThreshold.set(it.toInt()) },
                  valueRange = 0f..100f,
                  title = { Text(stringResource(R.string.pref_haptics_noise_threshold)) },
                  summary = { Text("$noise%", color = MaterialTheme.colorScheme.outline) },
                )

                PreferenceDivider()
                val maxAmp by preferences.maxAmplitude.collectAsState()
                SliderPreference(
                  value = maxAmp.toFloat(),
                  sliderValue = maxAmp.toFloat(),
                  onValueChange = { preferences.maxAmplitude.set(it.toInt().coerceIn(1, 100)) },
                  onSliderValueChange = { preferences.maxAmplitude.set(it.toInt().coerceIn(1, 100)) },
                  valueRange = 1f..100f,
                  title = { Text(stringResource(R.string.pref_haptics_max_amplitude)) },
                  summary = { Text("$maxAmp%", color = MaterialTheme.colorScheme.outline) },
                )
              }
            }
          }

          // --- Test button --------------------------------------------------
          item {
            PreferenceCard {
              Preference(
                title = { Text(stringResource(R.string.pref_haptics_test_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_haptics_test_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onClick = {
                  val played = hapticsManager.previewTest()
                  if (!played) {
                    Toast.makeText(
                      context,
                      context.getString(R.string.haptics_no_vibrator),
                      Toast.LENGTH_SHORT,
                    ).show()
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}
