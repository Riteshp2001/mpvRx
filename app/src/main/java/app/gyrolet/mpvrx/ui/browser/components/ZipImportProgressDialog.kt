/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.components

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
import kotlin.math.roundToInt

/**
 * Progress state for adding a read-only ZIP archive to the browser. [progress] is 0f..1f while the
 * archive entries are being scanned, or -1f for an indeterminate phase (locating the file).
 */
data class ZipImportProgress(
  val label: String,
  val detail: String = "",
  val progress: Float = -1f,
)

/**
 * Progress UI shown while a ZIP archive is registered as a folder. The archive is never copied or
 * extracted, so the only measurable work is reading its entry table, which drives the percentage.
 *
 * Not dismissable by back-press or outside-tap, matching the other in-progress dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZipImportProgressDialog(
  isOpen: Boolean,
  progress: ZipImportProgress,
  onCancel: () -> Unit,
) {
  if (!isOpen) return

  Dialog(
    onDismissRequest = { /* no-op: cancel is the only exit while busy */ },
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
          progress.label,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = AlertDialogDefaults.titleContentColor,
        )

        if (progress.detail.isNotBlank()) {
          Text(
            progress.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = AlertDialogDefaults.textContentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        if (progress.progress in 0f..1f) {
          LinearProgressIndicator(
            progress = { progress.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            "${(progress.progress.coerceIn(0f, 1f) * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
