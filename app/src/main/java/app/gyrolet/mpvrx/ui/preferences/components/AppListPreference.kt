/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import app.gyrolet.mpvrx.presentation.components.AppSingleChoiceDialog
import app.gyrolet.mpvrx.ui.preferences.playerSettingAvailability

/** Keeps preference row/storage behavior while all choice popups use the shared app dialog. */
@Composable
fun <T> AppListPreference(
  value: T,
  onValueChange: (T) -> Unit,
  values: List<T>,
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: (@Composable () -> Unit)? = null,
  summary: (@Composable () -> Unit)? = null,
  valueToText: @Composable (T) -> AnnotatedString = { AnnotatedString(it.toString()) },
) {
  val availability = playerSettingAvailability(modifier)
  if (!availability.visible) return
  val canChange = enabled && availability.enabled
  var openSelector by rememberSaveable { mutableStateOf(false) }
  if (openSelector && canChange) {
    AppSingleChoiceDialog(
      value = value,
      values = values,
      onValueChange = { if (canChange) onValueChange(it) },
      onDismissRequest = { openSelector = false },
      title = title,
      valueToText = valueToText,
    )
  }
  AppPreference(
    title = title,
    modifier = modifier,
    enabled = enabled,
    icon = icon,
    summary = summary,
    onClick = { openSelector = true },
    action = PreferenceAction.Dialog,
  )
}
