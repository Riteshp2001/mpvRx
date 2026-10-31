/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.ui.preferences.playerSettingAvailability

@Composable
fun AppTextFieldPreference(
  value: String,
  onValueChange: (String) -> Unit,
  textToValue: (String) -> String,
  title: @Composable () -> Unit,
  textField: @Composable (TextFieldValue, (TextFieldValue) -> Unit, Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  summary: (@Composable () -> Unit)? = null,
) {
  val availability = playerSettingAvailability(modifier)
  if (!availability.visible) return
  var editing by remember { mutableStateOf(false) }
  var draft by remember { mutableStateOf(TextFieldValue(value)) }
  AppPreference(
    title = title,
    modifier = modifier,
    enabled = enabled,
    summary = summary,
    onClick = {
      draft = TextFieldValue(value)
      editing = true
    },
    action = PreferenceAction.Dialog,
  )
  if (editing && enabled && availability.enabled) {
    AppAlertDialog(
      onDismissRequest = { editing = false },
      title = title,
      text = { textField(draft, { draft = it }, false) },
      confirmButton = {
        AppDialogTextButton(onClick = {
          onValueChange(textToValue(draft.text))
          editing = false
        }) { Text(stringResource(R.string.generic_ok)) }
      },
      dismissButton = {
        AppDialogTextButton(onClick = { editing = false }) { Text(stringResource(R.string.generic_cancel)) }
      },
    )
  }
}
