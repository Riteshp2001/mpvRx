package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.gyrolet.mpvrx.ui.components.AppSlider
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive
import app.gyrolet.mpvrx.ui.preferences.ExpressivePreferenceItem
import app.gyrolet.mpvrx.ui.preferences.PlayerSettingSummary
import app.gyrolet.mpvrx.ui.preferences.playerSettingAvailability
import app.gyrolet.mpvrx.ui.preferences.playerSettingSummary
import me.zhanghai.compose.preference.LocalPreferenceTheme
import me.zhanghai.compose.preference.Preference

@Composable
fun AppSliderPreference(
  value: Float,
  onValueChange: (Float) -> Unit,
  sliderValue: Float,
  onSliderValueChange: (Float) -> Unit,
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
  valueSteps: Int = 0,
  enabled: Boolean = true,
  icon: (@Composable () -> Unit)? = null,
  summary: (@Composable () -> Unit)? = null,
  valueText: (@Composable () -> Unit)? = null,
) {
  val availability = playerSettingAvailability(modifier)
  if (!availability.visible) return
  val canChange = enabled && availability.enabled
  val glassEnabled = liquidGlassActive()
  if (!glassEnabled) {
    ExpressivePreferenceItem(modifier = modifier) {
      me.zhanghai.compose.preference.SliderPreference(
        value = value,
        onValueChange = { if (canChange) onValueChange(it) },
        sliderValue = sliderValue,
        onSliderValueChange = { if (canChange) onSliderValueChange(it) },
        title = title,
        valueRange = valueRange,
        valueSteps = valueSteps,
        enabled = canChange,
        icon = icon,
        summary = playerSettingSummary(availability, enabled, summary),
        valueText = valueText,
      )
    }
    return
  }

  var observedValue by remember { mutableFloatStateOf(value) }
  SideEffect {
    if (observedValue != value) {
      if (canChange) onSliderValueChange(value)
      observedValue = value
    }
  }
  ExpressivePreferenceItem(modifier = modifier) {
    Preference(
      title = title,
      enabled = canChange,
      icon = icon,
      summary = {
        Column {
          PlayerSettingSummary(availability, enabled, summary)
          Row(verticalAlignment = Alignment.CenterVertically) {
            // Completion may arrive after recomposition; retain the last pointer value.
            var latestSliderValue by remember { mutableFloatStateOf(sliderValue) }
            var isUserInteracting by remember { mutableStateOf(false) }
            SideEffect { if (!isUserInteracting) latestSliderValue = sliderValue }
            AppSlider(
              value = sliderValue,
              onValueChange = { changedValue ->
                latestSliderValue = changedValue
                if (canChange) onSliderValueChange(changedValue)
              },
              modifier = Modifier.weight(1f),
              enabled = canChange,
              valueRange = valueRange,
              steps = valueSteps,
              onValueChangeFinished = { if (canChange) onValueChange(latestSliderValue) },
              onInteractionChange = { isUserInteracting = it },
            )
            if (valueText != null) {
              Box(Modifier.padding(start = LocalPreferenceTheme.current.horizontalSpacing)) {
                valueText()
              }
            }
          }
        }
      },
    )
  }
}
