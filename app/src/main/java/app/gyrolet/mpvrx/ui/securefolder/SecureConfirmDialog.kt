/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.securefolder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog

/**
 * Confirm-before-destructive-action dialog used in front of restore/delete-forever/move-to-secure,
 * with an optional "don't ask again" checkbox that persists into a [Preference] flag
 * (one of [app.gyrolet.mpvrx.preferences.SecureFolderPreferences.dontAskBeforeMove]/
 * `dontAskBeforeRestore`/`dontAskBeforeDelete]`).
 *
 * Callers are expected to check `dontAskAgain.get()` themselves before opening this dialog at
 * all (see [SecureFolderScreen]) — this composable only handles showing the prompt and, if the
 * user opts in, persisting the flag so future calls can be skipped entirely.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecureConfirmDialog(
  isOpen: Boolean,
  title: String,
  subtitle: String,
  dontAskAgain: Preference<Boolean>,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (!isOpen) return

  // Keyed on isOpen so the checkbox resets every time the dialog is (re)opened.
  var dontAskChecked by rememberSaveable(isOpen) { mutableStateOf(false) }

  ConfirmDialog(
    title = title,
    subtitle = subtitle,
    onConfirm = {
      if (dontAskChecked) dontAskAgain.set(true)
      onConfirm()
    },
    onCancel = onDismiss,
    customContent = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Checkbox(
          checked = dontAskChecked,
          onCheckedChange = { dontAskChecked = it },
          colors = CheckboxDefaults.colors(),
        )
        Text(
          "Don't ask again",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    },
  )
}
