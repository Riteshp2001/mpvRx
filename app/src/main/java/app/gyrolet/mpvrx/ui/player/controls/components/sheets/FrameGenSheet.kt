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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import app.gyrolet.mpvrx.ui.icons.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.framegen.LosslessScalingHelper
import kotlinx.coroutines.launch

/**
 * OSD bottom sheet for Lossless Scaling Frame Generation (LSFG) settings.
 *
 * Mirrors Eden's QuickSettings.addFrameGen() section, adapted to mpvRx's
 * Compose PlayerSheet pattern.
 *
 * Layout:
 *   ┌─────────────────────────────────────┐
 *   │  Frame Generation      [toggle]     │
 *   │  ─────────────────────────────────  │
 *   │  [description text]                 │
 *   │                                     │
 *   │  Multiplier                         │
 *   │  [  2×  ] [  3×  ] [  4×  ]        │
 *   │                                     │
 *   │  Library                            │
 *   │  Status: Installed / Not installed  │
 *   │  [Install / Replace]  [Remove]      │
 *   └─────────────────────────────────────┘
 */
@Composable
fun FrameGenSheet(
    isEnabled: Boolean,
    isSupported: Boolean,
    multiplier: Int,              // 2, 3, or 4
    onToggle: (Boolean) -> Unit,
    onMultiplierChange: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val installed by LosslessScalingHelper.installed.collectAsState()
    val statusText by LosslessScalingHelper.statusText.collectAsState()

    var installing by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    // File picker — any file type, users pick Lossless.dll
    val dllPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        installing = true
        scope.launch {
            LosslessScalingHelper.install(context, uri)
            installing = false
        }
    }

    PlayerSheet(onDismissRequest, modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── Header row: title + toggle ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.RoundedFilled.FrameGen,
                        contentDescription = null,
                        tint = if (isEnabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(R.string.frame_gen_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = { if (isSupported && installed) onToggle(it) },
                    enabled = isSupported && installed,
                )
            }

            // ── GPU not supported warning ────────────────────────────────────
            if (!isSupported) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.RoundedFilled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.frame_gen_not_supported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            // ── Description ─────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.frame_gen_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Multiplier chips (only shown when enabled and supported) ─────
            AnimatedVisibility(
                visible = isSupported,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.frame_gen_multiplier_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2 to R.string.frame_gen_multiplier_2x,
                               3 to R.string.frame_gen_multiplier_3x,
                               4 to R.string.frame_gen_multiplier_4x).forEach { (value, labelRes) ->
                            FilterChip(
                                selected = multiplier == value,
                                enabled = isSupported,
                                onClick = { onMultiplierChange(value) },
                                label = { Text(stringResource(labelRes)) },
                                leadingIcon = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }
            }

            // ── Divider ──────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            )

            // ── Library management ───────────────────────────────────────────
            Text(
                text = "Library",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            // Status badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (installed)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (installing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (installed) Icons.RoundedFilled.CheckCircle
                                      else Icons.RoundedFilled.ErrorOutline,
                        contentDescription = null,
                        tint = if (installed) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = if (installing) stringResource(R.string.frame_gen_installing)
                           else statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (installed) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Install / Replace + Remove buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { dllPicker.launch(arrayOf("*/*")) },
                    enabled = !installing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.RoundedFilled.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (installed) stringResource(R.string.frame_gen_replace_library)
                               else stringResource(R.string.frame_gen_install_library),
                    )
                }

                if (installed) {
                    OutlinedButton(
                        onClick = { showRemoveDialog = true },
                        enabled = !installing,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.RoundedFilled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // How to get the library — brief hint
            Text(
                text = "Obtain Lossless.dll from your Lossless Scaling installation (Steam → Local Files).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Start,
            )

            Spacer(Modifier.height(4.dp))
        }
    }

    // Remove confirmation dialog
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.frame_gen_remove_library)) },
            text = { Text(stringResource(R.string.frame_gen_remove_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        scope.launch { LosslessScalingHelper.remove() }
                        // Also disable frame gen if it was on
                        if (isEnabled) onToggle(false)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.frame_gen_remove_library))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.generic_cancel))
                }
            },
        )
    }
}
