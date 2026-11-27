package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton

@Composable
fun RestartRequiredDialog(
  title: String,
  message: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(message) },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(confirmLabel)
      }
    },
  )
}
