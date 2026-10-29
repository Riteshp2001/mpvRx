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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.getPlayerButtonLabel
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.LocalBorderlessPlayerControls
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.panels.DraggablePanel
import app.gyrolet.mpvrx.ui.player.controls.components.panels.DraggablePanelHeader
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
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

  Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
    AnimatedVisibility(
      visible = controlsVisible && buttons.isNotEmpty() && !panelVisible,
      modifier = Modifier.align(AbsoluteAlignment.CenterRight),
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
internal fun PlayerControlEdgeHandle(
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
    contentAlignment = AbsoluteAlignment.CenterRight,
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
      LocalBorderlessPlayerControls provides true,
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
  DraggablePanelHeader(
    title = stringResource(R.string.pref_player_controls_drawer_title),
    onDismissRequest = onDismissRequest,
  )
}

@Composable
private fun PlayerControlPanelContent(
  buttons: List<PlayerButton>,
  activeButtons: Set<PlayerButton>,
  renderButton: @Composable (PlayerButton) -> Unit,
) {
  // A weighted 3-column grid never wraps because of density, font scale, or icon sizes.
  // Fixed rows preserve control order; the final row never stretches missing cells.
  val screenHeight = LocalConfiguration.current.screenHeightDp.dp
  val tileHeight = ((screenHeight - 148.dp) / 3f).coerceIn(68.dp, 104.dp)

  Column(
    modifier = Modifier.fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
      .tvFocusGroup(),
    verticalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
  ) {
    buttons.chunked(PanelColumnCount).forEachIndexed { rowIndex, rowButtons ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
      ) {
        rowButtons.forEachIndexed { columnIndex, button ->
          PlayerControlTile(
            button = button,
            active = button in activeButtons,
            renderButton = renderButton,
            tileHeight = tileHeight,
            shape = rememberConnectedShape(
              index = rowIndex * PanelColumnCount + columnIndex,
              itemCount = buttons.size,
              layout = ConnectedLayout.Grid,
              columns = PanelColumnCount,
              tokens = AppConnectedControlShapeTokens,
            ),
            modifier = Modifier.weight(1f),
          )
        }
        repeat(PanelColumnCount - rowButtons.size) {
          Spacer(Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun PlayerControlTile(
  button: PlayerButton,
  active: Boolean,
  renderButton: @Composable (PlayerButton) -> Unit,
  tileHeight: Dp,
  shape: Shape,
  modifier: Modifier = Modifier,
) {
  val compact = tileHeight < 88.dp
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
        .tvFocusHighlight(shape, focusedScale = 1.02f)
        .height(tileHeight),
    shape = shape,
    color = containerColor,
    contentColor = contentColor,
    tonalElevation = if (active) 2.dp else 0.dp,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(horizontal = 2.dp, vertical = if (compact) 2.dp else 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Box(
        modifier =
          Modifier
            .size(if (compact) 44.dp else 56.dp)
            .graphicsLayer {
              scaleX = if (compact) 1f else PanelIconScale
              scaleY = if (compact) 1f else PanelIconScale
            },
        contentAlignment = Alignment.Center,
      ) {
        renderButton(button)
      }
      Spacer(Modifier.height(if (compact) 0.dp else 4.dp))
      Text(
        text = getPlayerButtonLabel(button),
        maxLines = if (compact) 1 else 2,
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
private const val PanelIconScale = 1.24f
private const val EdgeTouchHeightFraction = 0.34f
private const val PanelColumnCount = 3
