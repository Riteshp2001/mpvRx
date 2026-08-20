/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.controls.components.MpvConfigOwnedPanel
import app.gyrolet.mpvrx.ui.player.controls.components.panels.AudioDelayPanel
import app.gyrolet.mpvrx.ui.player.controls.components.panels.HdrScreenOutputPanel
import app.gyrolet.mpvrx.ui.player.controls.components.panels.LuaScriptsPanel
import app.gyrolet.mpvrx.ui.player.controls.components.panels.SubtitleDelayPanel
import app.gyrolet.mpvrx.ui.player.controls.components.panels.SubtitleSettingsPanel
import app.gyrolet.mpvrx.ui.player.controls.components.panels.VideoSettingsPanel
import app.gyrolet.mpvrx.ui.utils.isAnyMpvOptionOwnedByConfig
import app.gyrolet.mpvrx.ui.utils.isMpvOptionOwnedByConfig

@Composable
fun PlayerPanels(
  panelShown: Panels,
  viewModel: PlayerViewModel,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedContent(
    targetState = panelShown,
    label = "panels",
    contentAlignment = Alignment.CenterEnd,
    contentKey = { it.name },
    transitionSpec = {
      fadeIn() + slideInHorizontally { it / 3 } togetherWith fadeOut() + slideOutHorizontally { it / 2 }
    },
    modifier = modifier,
  ) { currentPanel ->
    val configOwned =
      when (currentPanel) {
        Panels.AudioDelay -> isMpvOptionOwnedByConfig("audio-delay")
        Panels.HdrScreenOutput -> isAnyMpvOptionOwnedByConfig(MpvConfigControlledFeatures.HDR_OUTPUT)
        else -> false
      }
    if (configOwned) {
      MpvConfigOwnedPanel(onDismissRequest)
      return@AnimatedContent
    }
    when (currentPanel) {
      Panels.None -> {
        Box(Modifier.fillMaxHeight())
      }
      Panels.SubtitleSettings -> {
        SubtitleSettingsPanel(
          viewModel = viewModel,
          onDismissRequest = onDismissRequest,
        )
      }
      Panels.SubtitleDelay -> {
        SubtitleDelayPanel(onDismissRequest)
      }
      Panels.AudioDelay -> {
        AudioDelayPanel(onDismissRequest)
      }
      Panels.VideoFilters -> {
        VideoSettingsPanel(onDismissRequest)
      }
      Panels.LuaScripts -> {
        LuaScriptsPanel(onDismissRequest)
      }
      Panels.HdrScreenOutput -> {
        HdrScreenOutputPanel(
          viewModel = viewModel,
          onDismissRequest = onDismissRequest,
        )
      }
    }
  }
}

val CARDS_MAX_WIDTH = 420.dp
val panelCardsColors: @Composable () -> CardColors = {
  // Higher alpha for better readability in panels (less transparent)
  val alpha = 0.85f

  CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = alpha),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
  )
}
