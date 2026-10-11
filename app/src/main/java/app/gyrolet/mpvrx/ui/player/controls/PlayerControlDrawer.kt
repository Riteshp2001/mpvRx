/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.getPlayerButtonLabel
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.LocalHidePlayerButtonsBackground
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.panels.DraggablePanel
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.controlColor
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics

@Composable
internal fun PlayerControlDrawer(
  buttons: List<PlayerButton>,
  activeButtons: Set<PlayerButton>,
  controlsVisible: Boolean,
  panelVisible: Boolean,
  onPanelVisibilityChanged: (Boolean) -> Unit,
  renderButton: @Composable (PlayerButton) -> Unit,
) {
  val reducedMotion = AppMotion.playerReducedMotion()
  val clickEvent = LocalPlayerButtonsClickEvent.current
  val openPanel = {
    if (controlsVisible && buttons.isNotEmpty() && !panelVisible) {
      clickEvent()
      onPanelVisibilityChanged(true)
    }
  }
  val closePanel = { onPanelVisibilityChanged(false) }
  BackHandler(enabled = panelVisible, onBack = closePanel)

  Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(
      visible = controlsVisible && buttons.isNotEmpty() && !panelVisible,
      modifier = Modifier.align(Alignment.CenterEnd),
      enter =
        if (reducedMotion) {
          fadeIn(animationSpec = tween(90))
        } else {
          fadeIn(animationSpec = AppMotion.Effect.Alpha) +
            slideInHorizontally(
              animationSpec =
                spring(
                  dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                  stiffness = AppMotion.Spatial.Expressive.stiffness,
                ),
            ) { it / 2 }
        },
      exit =
        if (reducedMotion) {
          fadeOut(animationSpec = tween(70))
        } else {
          fadeOut(animationSpec = AppMotion.Effect.Alpha) +
            slideOutHorizontally(
              animationSpec =
                spring(
                  dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
                  stiffness = AppMotion.Spatial.Standard.stiffness,
                ),
            ) { it / 2 }
        },
    ) {
      PlayerControlEdgeHandle(
        enabled = controlsVisible,
        onOpen = openPanel,
      )
    }

    AnimatedVisibility(
      visible = panelVisible,
      modifier = Modifier.fillMaxSize(),
      enter =
        if (reducedMotion) {
          fadeIn(animationSpec = tween(90))
        } else {
          fadeIn(animationSpec = AppMotion.Effect.Alpha) +
            slideInHorizontally(
              animationSpec =
                spring(
                  dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                  stiffness = AppMotion.Spatial.Expressive.stiffness,
                ),
            ) { it / 3 }
        },
      exit =
        if (reducedMotion) {
          fadeOut(animationSpec = tween(70))
        } else {
          fadeOut(animationSpec = AppMotion.Effect.Alpha) +
            slideOutHorizontally(
              animationSpec =
                spring(
                  dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
                  stiffness = AppMotion.Spatial.Standard.stiffness,
                ),
            ) { it / 3 }
        },
    ) {
      PlayerControlPanel(
        buttons = buttons,
        activeButtons = activeButtons,
        renderButton = renderButton,
        onDismissRequest = closePanel,
      )
    }
  }
}

@Composable
private fun PlayerControlEdgeHandle(
  enabled: Boolean,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val haptic = rememberAppHaptics()
  val interactionSource = remember { MutableInteractionSource() }
  val thresholdPx = with(density) { EdgePullThreshold.toPx() }
  val maxPullPx = with(density) { EdgePullMaximum.toPx() }
  var pullDistancePx by remember { mutableFloatStateOf(0f) }
  val animatedPull by
    animateFloatAsState(
      targetValue = pullDistancePx,
      animationSpec =
        spring(
          dampingRatio = 0.9f,
          stiffness = 900f,
        ),
      label = "PlayerDrawerEdgePull",
    )
  val pullProgress = (animatedPull / thresholdPx).coerceIn(0f, 1f)

  Box(
    modifier =
      modifier
        .width(EdgeTouchWidth)
        .fillMaxHeight(EdgeTouchHeightFraction)
        .pointerInput(enabled, thresholdPx, maxPullPx) {
          if (!enabled) return@pointerInput
          var thresholdReached = false
          detectHorizontalDragGestures(
            onDragStart = {
              thresholdReached = false
              pullDistancePx = 0f
            },
            onDragCancel = {
              pullDistancePx = 0f
            },
            onDragEnd = {
              pullDistancePx = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
              val directedDelta = -dragAmount
              if (directedDelta > 0f || pullDistancePx > 0f) {
                change.consume()
                val resistance =
                  if (pullDistancePx >= thresholdPx && directedDelta > 0f) {
                    0.24f
                  } else {
                    1f
                  }
                pullDistancePx =
                  (pullDistancePx + directedDelta * resistance).coerceIn(0f, maxPullPx)
                if (!thresholdReached && pullDistancePx >= thresholdPx) {
                  thresholdReached = true
                  haptic.tick()
                  onOpen()
                } else if (thresholdReached && pullDistancePx < thresholdPx * 0.82f) {
                  thresholdReached = false
                }
              }
            },
          )
        }.clickable(
          enabled = enabled,
          interactionSource = interactionSource,
          indication = null,
          onClick = onOpen,
        ),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.ChevronLeft,
      contentDescription = stringResource(R.string.player_sheets_more_title),
      tint = controlColor,
      modifier =
        Modifier
          .padding(end = 4.dp)
          .size(28.dp)
          .graphicsLayer {
            translationX = -animatedPull * 0.72f
            scaleX = 1f + pullProgress * 0.16f
            scaleY = 1f + pullProgress * 0.16f
          },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerControlPanel(
  buttons: List<PlayerButton>,
  activeButtons: Set<PlayerButton>,
  renderButton: @Composable (PlayerButton) -> Unit,
  onDismissRequest: () -> Unit,
) {
  val parentClickEvent = LocalPlayerButtonsClickEvent.current
  val parentClickEventCurrent by rememberUpdatedState(parentClickEvent)

  DraggablePanel(
    modifier = Modifier.fillMaxSize(),
    header = { PlayerControlPanelHeader(onDismissRequest) },
    shape = MaterialTheme.shapes.extraLargeIncreased,
    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
    tonalElevation = 2.dp,
    shadowElevation = 10.dp,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
  ) {
    CompositionLocalProvider(
      LocalHidePlayerButtonsBackground provides true,
      LocalPlayerButtonsClickEvent provides {
        onDismissRequest()
        parentClickEventCurrent()
      },
    ) {
      PlayerControlPanelContent(
        buttons = buttons,
        activeButtons = activeButtons,
        renderButton = renderButton,
      )
    }
  }
}

@Composable
private fun PlayerControlPanelHeader(onDismissRequest: () -> Unit) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(
          start = MaterialTheme.spacing.medium,
          end = MaterialTheme.spacing.extraSmall,
          bottom = MaterialTheme.spacing.small,
        ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(R.string.pref_player_controls_drawer_title),
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.weight(1f),
    )
    IconButton(onClick = onDismissRequest) {
      Icon(
        imageVector = Icons.RoundedFilled.Close,
        contentDescription = stringResource(R.string.generic_cancel),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerControlPanelContent(
  buttons: List<PlayerButton>,
  activeButtons: Set<PlayerButton>,
  renderButton: @Composable (PlayerButton) -> Unit,
) {
  BoxWithConstraints(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(start = 14.dp, end = 14.dp, bottom = 20.dp),
  ) {
    // Floor to whole pixels: Dp rounding at some densities made 3 tiles + gaps
    // exceed the row width, wrapping the grid to 2 columns (issue #590).
    val density = LocalDensity.current
    val tileWidth =
      with(density) {
        val availablePx = (maxWidth - PanelTileSpacing * (PanelColumnCount - 1)).toPx()
        kotlin.math.floor(availablePx / PanelColumnCount).toDp()
      }
    FlowRow(
      modifier = Modifier.fillMaxWidth().tvFocusGroup(),
      horizontalArrangement = Arrangement.spacedBy(PanelTileSpacing),
      verticalArrangement = Arrangement.spacedBy(PanelTileSpacing),
      maxItemsInEachRow = PanelColumnCount,
    ) {
      buttons.forEach { button ->
        PlayerControlTile(
          button = button,
          active = button in activeButtons,
          renderButton = renderButton,
          modifier = Modifier.width(tileWidth),
        )
      }
    }
  }
}

@Composable
private fun PlayerControlTile(
  button: PlayerButton,
  active: Boolean,
  renderButton: @Composable (PlayerButton) -> Unit,
  modifier: Modifier = Modifier,
) {
  val containerColor by
    animateColorAsState(
      targetValue =
        if (active) {
          MaterialTheme.colorScheme.primaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.78f)
        },
      animationSpec = tween(durationMillis = 180),
      label = "PlayerControlTileContainer",
    )
  val contentColor by
    animateColorAsState(
      targetValue =
        if (active) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      animationSpec = tween(durationMillis = 180),
      label = "PlayerControlTileContent",
    )

  Surface(
    modifier =
      modifier
        .tvFocusHighlight(RoundedCornerShape(18.dp), focusedScale = 1.02f)
        .height(104.dp),
    shape = RoundedCornerShape(18.dp),
    color = containerColor,
    contentColor = contentColor,
    tonalElevation = if (active) 2.dp else 0.dp,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(horizontal = 6.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Box(
        modifier =
          Modifier
            .size(56.dp)
            .graphicsLayer {
              scaleX = PanelIconScale
              scaleY = PanelIconScale
            },
        contentAlignment = Alignment.Center,
      ) {
        renderButton(button)
      }
      Spacer(Modifier.height(4.dp))
      Text(
        text = getPlayerButtonLabel(button),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

private val EdgeTouchWidth = 48.dp
private val EdgePullThreshold = 64.dp
private val EdgePullMaximum = 92.dp
private val PanelTileSpacing = 8.dp
private const val PanelIconScale = 1.24f
private const val EdgeTouchHeightFraction = 0.34f
private const val PanelColumnCount = 3
