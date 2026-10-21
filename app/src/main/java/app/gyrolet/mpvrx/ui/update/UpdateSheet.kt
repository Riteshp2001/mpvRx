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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.update.AppUpdateChannel
import app.gyrolet.mpvrx.domain.update.Release
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import com.mikepenz.markdown.m3.Markdown
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * One sheet owns the complete update flow, including history, errors and installation.
 * Its composition and sheet state survive release selection and download completion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
  state: UpdateUiState,
  currentVersion: String,
  onDismiss: () -> Unit,
  onDownload: () -> Unit,
  onInstall: () -> Unit,
  onIgnore: () -> Unit,
  onChannelChange: (AppUpdateChannel) -> Unit,
  onSelectRelease: (String) -> Unit,
  onLoadMore: () -> Unit,
  onRetryHistory: () -> Unit,
  onLoadCommit: (String) -> Unit,
) {
  val sheetState = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
  )
  var historyExpanded by rememberSaveable { mutableStateOf(false) }
  val release = state.selectedRelease
  val asset = state.asset

  ModalBottomSheet(
    onDismissRequest = { if (!state.isDownloading) onDismiss() },
    sheetState = sheetState,
    sheetGesturesEnabled = !state.isDownloading,
    properties = ModalBottomSheetProperties(
      shouldDismissOnBackPress = !state.isDownloading,
      shouldDismissOnClickOutside = !state.isDownloading,
    ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp),
    ) {
      SheetHeader(
        isInstallReady = state.readyToInstall,
        latestVersion = release?.let { releaseVersion(it) }.orEmpty(),
      )
      Spacer(Modifier.height(12.dp))
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AppUpdateChannel.entries.forEachIndexed { index, channel ->
          SegmentedButton(
            modifier = Modifier.weight(1f),
            selected = state.channel == channel,
            onClick = { onChannelChange(channel) },
            enabled = !state.isDownloading && !state.selecting,
            shape = SegmentedButtonDefaults.itemShape(index, AppUpdateChannel.entries.size),
            colors = themedSegmentedButtonColors(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            icon = {},
            label = {
              Text(
                stringResource(
                  when (channel) {
                    AppUpdateChannel.STABLE -> R.string.ui_update_channel_stable_short
                    AppUpdateChannel.PREVIEW -> R.string.ui_update_channel_preview_short
                    AppUpdateChannel.EXPERIMENTAL -> R.string.ui_update_channel_experimental_short
                  },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
              )
            },
          )
        }
      }

      Column(
        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
      ) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
          onClick = { historyExpanded = !historyExpanded },
          enabled = !state.isDownloading,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.update_choose_version), modifier = Modifier.weight(1f))
          Text(if (historyExpanded) "\u25b4" else "\u25be")
        }

        if (historyExpanded) {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(state.releases, key = { it.historyKey }) { historical ->
              LaunchedEffect(historical.historyKey) { onLoadCommit(historical.historyKey) }
              Surface(
                onClick = {
                  onSelectRelease(historical.historyKey)
                  historyExpanded = false
                },
                enabled = !state.isDownloading && !state.selecting,
                shape = RoundedCornerShape(12.dp),
                color = if (historical.historyKey == state.selectedKey) {
                  MaterialTheme.colorScheme.secondaryContainer
                } else {
                  MaterialTheme.colorScheme.surfaceContainer
                },
              ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                      releaseVersion(historical),
                      modifier = Modifier.weight(1f),
                      fontWeight = FontWeight.SemiBold,
                    )
                    historical.sourceCommit?.let {
                      Text(it.take(7), style = MaterialTheme.typography.labelMedium)
                    }
                  }
                  Text(
                    historical.displayCommitTitle ?: historical.name.ifBlank { historical.tagName },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                  val description = historical.displayCommitMessage?.substringAfter('\n', "")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: historical.body.takeIf { historical.displayCommitTitle == null && it.isNotBlank() }
                  description?.let {
                    Text(
                      it,
                      style = MaterialTheme.typography.bodySmall,
                      maxLines = 3,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                  Text(
                    formatDate(historical.publishedAt),
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
              }
            }
          }
        }

        if (state.loadingHistory) {
          Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.update_history_loading), style = MaterialTheme.typography.bodySmall)
          }
        }
        if (state.historyError) {
          Text(stringResource(R.string.update_history_failed), color = MaterialTheme.colorScheme.error)
          TextButton(onClick = onRetryHistory, enabled = !state.isDownloading) {
            Text(stringResource(R.string.ui_retry))
          }
        } else if (state.nextPage != null && !state.loadingHistory) {
          TextButton(onClick = onLoadMore, enabled = !state.isDownloading) {
            Text(stringResource(R.string.update_history_more))
          }
        }
        if (release == null && !state.loadingHistory && !state.historyError) {
          Text(stringResource(R.string.update_history_empty))
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
          if (state.olderThanInstalled) {
            Text(
              stringResource(R.string.update_downgrade_notice),
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          HorizontalDivider(Modifier.padding(vertical = 16.dp))
          release.displayCommitTitle?.let { title ->
            Text(stringResource(R.string.update_commit), style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            release.sourceCommit?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            release.displayCommitMessage?.substringAfter('\n', "")?.trim()?.takeIf { it.isNotEmpty() }?.let {
              Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
          }
          if (release.body.trim() != release.displayCommitMessage?.trim() || release.displayCommitTitle == null) {
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
          }
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
      if (state.isDownloading) DownloadProgressSection(state.progress)
      if (state.selecting) {
        Text(stringResource(R.string.update_checking_download), style = MaterialTheme.typography.bodySmall)
      }
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (!state.isDownloading && !state.readyToInstall && release != null) {
          TextButton(onClick = onIgnore, enabled = !state.selecting) { Text(stringResource(R.string.ui_ignore)) }
        }
        Spacer(Modifier.weight(1f))
        if (!state.isDownloading) {
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
          if (release != null && asset != null) {
            Button(
              onClick = if (state.readyToInstall) onInstall else onDownload,
              enabled = !state.selecting,
            ) {
              Text(stringResource(
                when {
                  state.readyToInstall -> R.string.ui_install
                  state.downloadError -> R.string.ui_retry
                  else -> R.string.ui_download
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
            if (isInstallReady) R.string.update_ready_to_install else R.string.update_history_title,
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

