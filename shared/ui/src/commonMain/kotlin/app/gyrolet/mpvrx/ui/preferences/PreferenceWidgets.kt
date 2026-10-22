package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.preferences.Preference
import app.gyrolet.mpvrx.ui.util.collectAsState
import kotlin.math.roundToInt

/** Rounded, gap-separated group matching the Android settings' connected surfaces. */
@Composable
fun PreferenceGroup(
  title: String?,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    if (title != null) {
      Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
      )
    }
    Column(
      Modifier.clip(RoundedCornerShape(24.dp)),
      verticalArrangement = Arrangement.spacedBy(2.dp),
      content = content,
    )
  }
}

@Composable
fun PreferenceItem(
  title: String,
  modifier: Modifier = Modifier,
  summary: String? = null,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  onClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 20.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
      Spacer(Modifier.width(20.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
      )
      if (summary != null) {
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    if (trailing != null) {
      Spacer(Modifier.width(12.dp))
      trailing()
    }
  }
}

@Composable
fun SwitchPreference(
  preference: Preference<Boolean>,
  title: String,
  summary: String? = null,
  enabled: Boolean = true,
) {
  val checked by preference.collectAsState()
  PreferenceItem(
    title = title,
    summary = summary,
    enabled = enabled,
    onClick = { preference.set(!checked) },
    trailing = { Switch(checked = checked, onCheckedChange = { preference.set(it) }, enabled = enabled) },
  )
}

@Composable
fun <T> ListPreference(
  preference: Preference<T>,
  title: String,
  entries: List<T>,
  label: (T) -> String,
  summary: ((T) -> String)? = null,
) {
  val value by preference.collectAsState()
  var open by remember { mutableStateOf(false) }
  PreferenceItem(title = title, summary = summary?.invoke(value) ?: label(value), onClick = { open = true })
  if (open) {
    AlertDialog(
      onDismissRequest = { open = false },
      title = { Text(title) },
      text = {
        LazyColumn(Modifier.widthIn(max = 420.dp)) {
          items(entries.size) { index ->
            val entry = entries[index]
            Row(
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .selectable(selected = entry == value, role = Role.RadioButton) {
                  preference.set(entry)
                  open = false
                }.padding(vertical = 10.dp, horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = entry == value, onClick = null)
              Spacer(Modifier.width(12.dp))
              Text(label(entry), style = MaterialTheme.typography.bodyLarge)
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
  }
}

@Composable
fun SliderPreference(
  title: String,
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float>,
  steps: Int = 0,
  valueLabel: (Float) -> String = { it.roundToInt().toString() },
) {
  var dragging by remember(value) { mutableFloatStateOf(value) }
  Column(
    Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .padding(horizontal = 20.dp, vertical = 12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
          .padding(horizontal = 10.dp, vertical = 4.dp),
      ) {
        Text(valueLabel(dragging), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
      }
    }
    Slider(
      value = dragging,
      onValueChange = { dragging = it },
      onValueChangeFinished = { onValueChange(dragging) },
      valueRange = valueRange,
      steps = steps,
    )
  }
}

@Composable
fun IntSliderPreference(
  preference: Preference<Int>,
  title: String,
  range: IntRange,
  step: Int = 1,
  valueLabel: (Int) -> String = { it.toString() },
) {
  val value by preference.collectAsState()
  SliderPreference(
    title = title,
    value = value.toFloat(),
    onValueChange = { preference.set(((it / step).roundToInt() * step).coerceIn(range)) },
    valueRange = range.first.toFloat()..range.last.toFloat(),
    steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
    valueLabel = { valueLabel(((it / step).roundToInt() * step)) },
  )
}
