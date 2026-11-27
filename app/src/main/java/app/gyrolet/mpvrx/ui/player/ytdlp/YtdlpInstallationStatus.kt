/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.gyrolet.mpvrx.ui.player.ytdlp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppShapeScale

@Composable
fun YtdlpInstallationStatus(
  info: YtdlpInstallationInfo?,
  isRunning: Boolean,
  modifier: Modifier = Modifier,
) {
  val isInstalled = info?.isInstalled == true
  val version = info?.version
  val shortCommitHash = info?.shortCommitHash
  val targetContainerColor =
    when {
      info == null -> MaterialTheme.colorScheme.surfaceContainerHigh
      !isInstalled -> MaterialTheme.colorScheme.errorContainer
      info.channel == YtdlpReleaseChannel.NIGHTLY -> MaterialTheme.colorScheme.tertiaryContainer
      info.channel == YtdlpReleaseChannel.MASTER -> MaterialTheme.colorScheme.secondaryContainer
      else -> MaterialTheme.colorScheme.primaryContainer
    }
  val targetContentColor =
    when {
      info == null -> MaterialTheme.colorScheme.onSurface
      !isInstalled -> MaterialTheme.colorScheme.onErrorContainer
      info.channel == YtdlpReleaseChannel.NIGHTLY -> MaterialTheme.colorScheme.onTertiaryContainer
      info.channel == YtdlpReleaseChannel.MASTER -> MaterialTheme.colorScheme.onSecondaryContainer
      else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
  val containerColor by animateColorAsState(
    targetValue = targetContainerColor,
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "ytdlpStatusContainer",
  )
  val contentColor by animateColorAsState(
    targetValue = targetContentColor,
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "ytdlpStatusContent",
  )
  val title =
    when {
      info == null -> stringResource(R.string.ui_checking_yt_dlp)
      !isInstalled -> stringResource(R.string.ui_yt_dlp_not_installed)
      info.channel == YtdlpReleaseChannel.STABLE -> stringResource(R.string.ui_yt_dlp_stable_release)
      info.channel == YtdlpReleaseChannel.NIGHTLY -> stringResource(R.string.ui_yt_dlp_nightly_build)
      info.channel == YtdlpReleaseChannel.MASTER -> stringResource(R.string.ui_yt_dlp_master_build)
      info.channel == YtdlpReleaseChannel.CUSTOM -> stringResource(R.string.ui_yt_dlp_custom_build)
      else -> stringResource(R.string.ui_yt_dlp_installed_build)
    }
  val details =
    when {
      info == null -> stringResource(R.string.ui_reading_installed_version)
      !isInstalled -> stringResource(R.string.ui_install_stable_to_play_web_links)
      version != null &&
        shortCommitHash != null &&
        info.channel in setOf(YtdlpReleaseChannel.NIGHTLY, YtdlpReleaseChannel.MASTER) ->
        stringResource(R.string.ui_yt_dlp_build_and_commit, version, shortCommitHash)
      version != null -> stringResource(R.string.ui_yt_dlp_version, version)
      shortCommitHash != null -> stringResource(R.string.ui_yt_dlp_commit, shortCommitHash)
      else -> stringResource(R.string.ui_version_unavailable)
    }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = AppShapeScale.extraLarge,
    color = containerColor,
    border = BorderStroke(1.dp, contentColor.copy(alpha = 0.18f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(
        modifier =
          Modifier
            .size(46.dp)
            .background(contentColor.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        if (isRunning) {
          CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = contentColor,
          )
        } else {
          Icon(
            imageVector = if (isInstalled) Icons.RoundedFilled.CheckCircle else Icons.RoundedFilled.CloudDownload,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
          )
        }
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMediumEmphasized,
          color = contentColor,
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = details,
          style = MaterialTheme.typography.bodySmall,
          color = contentColor.copy(alpha = 0.82f),
        )
      }
    }
  }
}
