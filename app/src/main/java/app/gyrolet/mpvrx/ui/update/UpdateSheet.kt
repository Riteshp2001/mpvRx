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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.update.Release
import app.gyrolet.mpvrx.presentation.components.MarkdownText
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Bottom sheet shown when a new release is available or downloaded. Release notes arrive as
 * GitHub-flavoured Markdown and are rendered through [MarkdownText].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
  release: Release,
  isDownloading: Boolean,
  progress: Float,
  isInstallReady: Boolean,
  currentVersion: String,
  onDismiss: () -> Unit,
  onAction: () -> Unit,
  onIgnore: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val latestVersion = release.tagName.removePrefix("v")
  val downloadSize = release.assets.find { it.name.endsWith(".apk") }?.size ?: 0L

  ModalBottomSheet(
    onDismissRequest = { if (!isDownloading) onDismiss() },
    sheetState = sheetState,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 24.dp),
    ) {
      SheetHeader(
        isInstallReady = isInstallReady,
        latestVersion = latestVersion,
      )

      Spacer(modifier = Modifier.height(16.dp))

      Column(
        modifier =
          Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
      ) {
        VersionTransitionRow(currentVersion = currentVersion, latestVersion = latestVersion)
        Spacer(modifier = Modifier.height(8.dp))
        ReleaseMetaRow(publishedAt = release.publishedAt, sizeBytes = downloadSize)

        HorizontalDivider(
          modifier = Modifier.padding(vertical = 16.dp),
          color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
          text = "What's New",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MarkdownText(
          markdown = release.body.ifBlank { "No release notes provided." },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
      }

      if (isDownloading) {
        DownloadProgressSection(progress = progress)
      }

      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (!isDownloading && !isInstallReady) {
          TextButton(onClick = onIgnore) {
            Text(stringResource(R.string.ui_ignore))
          }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (!isDownloading) {
          TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.generic_cancel))
          }
          Button(onClick = onAction) {
            Text(
              if (isInstallReady) {
                stringResource(R.string.ui_install)
              } else {
                stringResource(R.string.ui_download)
              },
            )
          }
        }
      }
    }
  }
}

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
        text = if (isInstallReady) "Ready to Install" else "Update Available",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "Version $latestVersion",
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
    MetaItem(label = "Release Date", value = formatDate(publishedAt))
    MetaItem(label = "Size", value = formatFileSize(sizeBytes))
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
      Text(
        text = stringResource(R.string.update_progress_percent, progress.toInt()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    LinearProgressIndicator(
      progress = { if (progress >= 0) progress / 100f else 0f },
      modifier = Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.primary,
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
  }
}

private fun formatFileSize(size: Long): String {
  if (size <= 0) return "Unknown size"
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
