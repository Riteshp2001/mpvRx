/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.framegen.LosslessScalingHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameGenSheet(
    isEnabled: Boolean,
    isSupported: Boolean,
    multiplier: Int,
    onToggle: (Boolean) -> Unit,
    onMultiplierChange: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val installed by LosslessScalingHelper.installed.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var failureCode by remember { mutableStateOf<Int?>(null) }

    val dllPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                onToggle(false)
                val result = LosslessScalingHelper.install(context, uri)
                if (result != LosslessScalingHelper.RESULT_OK) failureCode = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failureCode = LosslessScalingHelper.RESULT_UNREADABLE
            } finally {
                busy = false
            }
        }
    }

    PlayerSheet(onDismissRequest, modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.RoundedFilled.FrameGen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.frame_gen_sheet_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    enabled = !busy && (isEnabled || (isSupported && installed)),
                )
            }

            if (!isSupported) {
                Text(
                    text = stringResource(R.string.frame_gen_not_supported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.RoundedFilled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.frame_gen_heat_warning),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.frame_gen_multiplier_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(
                        2 to R.string.frame_gen_multiplier_2x,
                        3 to R.string.frame_gen_multiplier_3x,
                        4 to R.string.frame_gen_multiplier_4x,
                    )
                    options.forEachIndexed { index, (value, labelRes) ->
                        SegmentedButton(
                            selected = multiplier == value,
                            onClick = { onMultiplierChange(value) },
                            enabled = isSupported && installed && !busy,
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            icon = {},
                        ) {
                            Text(stringResource(labelRes), maxLines = 1)
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.frame_gen_library_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Lossless.dll",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = stringResource(
                            if (installed) R.string.frame_gen_library_installed_short
                            else R.string.frame_gen_library_missing_short,
                        ),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { dllPicker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.RoundedFilled.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (installed) stringResource(R.string.frame_gen_replace_library)
                               else stringResource(R.string.frame_gen_install_library),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                if (installed) {
                    IconButton(
                        onClick = { showRemoveDialog = true },
                        enabled = !busy,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.RoundedFilled.Delete,
                            contentDescription = stringResource(R.string.frame_gen_remove_library),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.frame_gen_remove_library)) },
            text = { Text(stringResource(R.string.frame_gen_remove_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        busy = true
                        scope.launch {
                            try {
                                onToggle(false)
                                if (!LosslessScalingHelper.remove(context)) {
                                    failureCode = LosslessScalingHelper.RESULT_UNREADABLE
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                failureCode = LosslessScalingHelper.RESULT_UNREADABLE
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.frame_gen_remove_library), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.generic_cancel))
                }
            },
        )
    }

    failureCode?.let { code ->
        AlertDialog(
            onDismissRequest = { failureCode = null },
            title = { Text(stringResource(R.string.frame_gen_library_title)) },
            text = { Text(stringResource(R.string.frame_gen_library_update_failed, code)) },
            confirmButton = {
                TextButton(onClick = { failureCode = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}
