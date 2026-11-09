/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.update.Release
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.tv.tvScrollWhenFocusCannotMove
import com.mikepenz.markdown.m3.Markdown
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * One sheet owns the complete update flow, including progress, errors and installation.
 * Its composition and sheet state survive update checks and download completion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
  state: UpdateUiState,
  currentVersion: String,
  onDismiss: () -> Unit,
  onDownload: () -> Unit,
  onCancelDownload: () -> Unit,
  onInstall: () -> Unit,
  onIgnore: () -> Unit,
  onRetryCheck: () -> Unit,
) {
  val sheetState = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
  )
  val release = state.release
  val asset = state.asset
  val busy = state.isDownloading || state.isInstalling

  ModalBottomSheet(
    onDismissRequest = { if (!busy) onDismiss() },
    sheetState = sheetState,
    sheetGesturesEnabled = !busy,
    properties = ModalBottomSheetProperties(
      shouldDismissOnBackPress = !busy,
      shouldDismissOnClickOutside = !busy,
    ),
  ) {
    val notesScroll = rememberScrollState()
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 24.dp)
          // TV: Up/Down scroll the release notes once focus has nowhere else to go.
          .tvScrollWhenFocusCannotMove(notesScroll),
    ) {
      SheetHeader(
        isInstallReady = state.readyToInstall,
        latestVersion = release?.let { releaseVersion(it) }.orEmpty(),
      )
      Column(
        modifier = Modifier.weight(1f, fill = false).verticalScroll(notesScroll),
      ) {
        Spacer(Modifier.height(12.dp))
        if (state.checking) {
          Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.ui_check_for_updates_now), style = MaterialTheme.typography.bodySmall)
          }
        }
        if (state.checkError) {
          Text(stringResource(R.string.update_check_failed), color = MaterialTheme.colorScheme.error)
          TextButton(onClick = onRetryCheck, enabled = !state.isDownloading) {
            Text(stringResource(R.string.ui_retry))
          }
        }
        if (state.noUpdate) {
          Text(stringResource(R.string.ui_already_using_latest_version))
        }

        if (release != null && asset != null) {
          Spacer(Modifier.height(8.dp))
          VersionTransitionRow(currentVersion, releaseVersion(release))
          Spacer(Modifier.height(8.dp))
          ReleaseMetaRow(
            publishedAt = if (release.isExperimental) release.updatedAt ?: release.publishedAt else release.publishedAt,
            sizeBytes = asset.size,
          )
          Spacer(Modifier.height(8.dp))
          MetaItem(stringResource(R.string.update_package), asset.variantLabel)
          HorizontalDivider(Modifier.padding(vertical = 16.dp))
          Text(
            stringResource(R.string.update_whats_new),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(8.dp))
          Markdown(
            content = release.body.ifBlank { stringResource(R.string.update_no_release_notes) },
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(16.dp))
        }
      }

      if (state.downloadError) {
        Text(stringResource(R.string.update_download_failed), color = MaterialTheme.colorScheme.error)
      }
      if (state.installError) {
        Text(stringResource(R.string.update_install_failed), color = MaterialTheme.colorScheme.error)
      }
      if (state.installPermissionRequired) {
        Text(stringResource(R.string.update_install_permission), style = MaterialTheme.typography.bodySmall)
      }
      if (state.awaitingApproval) {
        Text(stringResource(R.string.update_approval_required), style = MaterialTheme.typography.bodySmall)
      }
      if (state.isInstalling) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodySmall)
      }
      if (state.queued) {
        Text(stringResource(R.string.update_download_waiting), style = MaterialTheme.typography.bodySmall)
      }
      if (state.isDownloading) DownloadProgressSection(if (state.queued) -1f else state.progress)
      if (state.selecting) {
        Text(stringResource(R.string.update_checking_download), style = MaterialTheme.typography.bodySmall)
      }
      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (state.isDownloading) {
          TextButton(onClick = onCancelDownload) { Text(stringResource(R.string.generic_cancel)) }
        }
        if (!busy && !state.readyToInstall && release != null) {
          TextButton(onClick = onIgnore, enabled = !state.selecting) { Text(stringResource(R.string.ui_ignore)) }
        }
        if (!busy) {
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
          if (release != null && asset != null) {
            Button(
              onClick = if (state.readyToInstall) onInstall else onDownload,
              enabled = !state.selecting && !state.checking,
            ) {
              Text(stringResource(
                when {
                  state.awaitingApproval -> R.string.update_confirm_install
                  state.readyToInstall -> R.string.ui_install
                  state.downloadError -> R.string.ui_retry
                  else -> R.string.update_download_and_install
                },
              ))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun releaseVersion(release: Release): String =
  release.experimentalBuildNumber?.let { stringResource(R.string.update_experimental_build_format, it) }
    ?: release.previewBuildNumber?.let { stringResource(R.string.update_beta_build_format, it) }
    ?: release.tagName.removePrefix("v")

@Composable
private fun SheetHeader(
  isInstallReady: Boolean,
  latestVersion: String,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
      modifier =
        Modifier
          .size(44.dp)
          .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector =
          if (isInstallReady) Icons.RoundedFilled.SystemUpdate else Icons.RoundedFilled.NewReleases,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(24.dp),
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
    Column {
      Text(
        text =
          stringResource(
            when {
              isInstallReady -> R.string.update_ready_to_install
              latestVersion.isBlank() -> R.string.pref_section_updates
              else -> R.string.update_available
            },
          ),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = if (latestVersion.isBlank()) "" else stringResource(R.string.update_version_format, latestVersion),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun VersionTransitionRow(
  currentVersion: String,
  latestVersion: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    VersionChip(label = currentVersion, emphasized = false)
    Text(
      text = "\u2192",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    VersionChip(label = latestVersion, emphasized = true)
  }
}

@Composable
private fun VersionChip(
  label: String,
  emphasized: Boolean,
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color =
      if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color =
        if (emphasized) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun ReleaseMetaRow(
  publishedAt: String,
  sizeBytes: Long,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    MetaItem(label = stringResource(R.string.update_release_date), value = formatDate(publishedAt))
    MetaItem(
      label = stringResource(R.string.update_size),
      value = formatFileSize(sizeBytes, stringResource(R.string.update_unknown_size)),
    )
  }
}

@Composable
private fun MetaItem(
  label: String,
  value: String,
) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun DownloadProgressSection(progress: Float) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = stringResource(R.string.ui_downloading),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (progress >= 0f) {
        Text(
          text = stringResource(R.string.update_progress_percent, progress.toInt().coerceIn(0, 100)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (progress < 0f) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
      LinearProgressIndicator(
        progress = { (progress / 100f).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
  }
}

private fun formatFileSize(
  size: Long,
  unknownLabel: String,
): String {
  if (size <= 0) return unknownLabel
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
  return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatDate(dateString: String): String {
  return try {
    // GitHub API returns ISO 8601 format: "2024-01-15T10:30:00Z"
    val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    inputFormat.timeZone = TimeZone.getTimeZone("UTC")
    val date = inputFormat.parse(dateString) ?: return dateString

    val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    outputFormat.format(date)
  } catch (e: Exception) {
    dateString
  }
}
