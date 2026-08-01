/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.securefolder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.repository.SecureFolderRepository

/**
 * Progress UI shown while a restore / delete-forever / move-in batch is running, driven by
 * [SecureFolderRepository.SecureOperationProgress] ([SecureFolderViewModel.operationProgress]).
 *
 * Deliberately not dismissable by back-press or outside-tap — [onCancel] (wired to
 * [SecureFolderViewModel.cancelCurrentOperation]) is the only way out, same as
 * [app.gyrolet.mpvrx.ui.browser.dialogs.VideoCompressorOverlay]'s in-progress state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecureFolderProgressDialog(
  isOpen: Boolean,
  progress: SecureFolderRepository.SecureOperationProgress,
  label: String,
  onCancel: () -> Unit,
) {
  if (!isOpen) return

  Dialog(
    onDismissRequest = { /* no-op: cancel button is the only exit while busy */ },
    properties =
      DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
      ),
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = AlertDialogDefaults.containerColor,
      tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
      Column(
        modifier = Modifier.padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          label,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = AlertDialogDefaults.titleContentColor,
        )

        if (progress.currentFile.isNotBlank()) {
          Text(
            progress.currentFile,
            style = MaterialTheme.typography.bodyMedium,
            color = AlertDialogDefaults.textContentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        LinearProgressIndicator(
          progress = { progress.overallProgress.coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth(),
        )

        val totalFiles = progress.totalFiles
        Text(
          text =
            if (totalFiles > 0) {
              stringResource(
                R.string.secure_folder_progress_format,
                progress.currentFileIndex.coerceAtMost(totalFiles),
                totalFiles,
                (progress.overallProgress * 100).toInt(),
              )
            } else {
              "${(progress.overallProgress * 100).toInt()}%"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (progress.error != null) {
          Text(
            progress.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        TextButton(
          onClick = onCancel,
          shape = MaterialTheme.shapes.extraLarge,
        ) {
          Text(
            stringResource(R.string.generic_cancel),
            fontWeight = FontWeight.Medium,
          )
        }
      }
    }
  }
}
