/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.ui.player.controls.components.fadingHorizontalScroll

internal val LandscapeActionSize = 48.dp
internal val LandscapeActionGap = 8.dp

@Composable
internal fun LandscapePlayerHeader(
  modifier: Modifier = Modifier,
  title: @Composable () -> Unit,
  actions: @Composable () -> Unit,
) {
  Layout(
    modifier = modifier.fillMaxWidth(),
    content = {
      Box { title() }
      Box { actions() }
    },
  ) { measurables, constraints ->
    val width = constraints.maxWidth
    val backButtonWidth = LandscapeActionSize.roundToPx().coerceAtMost(width)
    val gap = LandscapeActionGap.roundToPx().coerceAtMost((width - backButtonWidth).coerceAtLeast(0))
    val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
    val actions = measurables[1].measure(
      childConstraints.copy(maxWidth = (width - backButtonWidth - gap).coerceAtLeast(0)),
    )
    val title = measurables[0].measure(
      childConstraints.copy(maxWidth = (width - actions.width - if (actions.width > 0) gap else 0).coerceAtLeast(0)),
    )
    val height = maxOf(title.height, actions.height).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) {
      title.placeRelative(0, 0)
      actions.placeRelative(width - actions.width, 0)
    }
  }
}

@Composable
internal fun LandscapeActionGroup(
  buttons: List<PlayerButton>,
  modifier: Modifier = Modifier,
  endAligned: Boolean = false,
  fillWidth: Boolean = true,
  renderButton: @Composable (PlayerButton) -> Unit,
) {
  FlowRow(
    modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier),
    horizontalArrangement = Arrangement.spacedBy(
      LandscapeActionGap, if (endAligned) Alignment.End else Alignment.Start,
    ),
    verticalArrangement = Arrangement.spacedBy(LandscapeActionGap),
  ) {
    buttons.filterNot { it == PlayerButton.NONE }.forEach { button ->
      key(button) {
        Box(Modifier.align(Alignment.CenterVertically)) { renderButton(button) }
      }
    }
  }
}

@Composable
internal fun LandscapeControlsRow(
  leftButtons: List<PlayerButton>,
  rightButtons: List<PlayerButton>,
  playbackWidth: Dp,
  modifier: Modifier = Modifier,
  leftModifier: Modifier = Modifier,
  rightModifier: Modifier = Modifier,
  renderButton: @Composable (PlayerButton) -> Unit,
  playbackControls: @Composable () -> Unit,
) {
  if (playbackWidth > 0.dp) {
    Row(
      modifier = modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(LandscapeActionGap),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      LandscapeActionGroup(
        buttons = leftButtons,
        modifier = Modifier.weight(1f).then(leftModifier)
          .fadingHorizontalScroll(rememberScrollState(), shadowRoom = 0.dp),
        renderButton = renderButton,
      )
      Box(Modifier.width(playbackWidth), contentAlignment = Alignment.Center) { playbackControls() }
      LandscapeActionGroup(
        buttons = rightButtons,
        modifier = Modifier.weight(1f).then(rightModifier)
          .fadingHorizontalScroll(rememberScrollState(), reverseScrolling = true, shadowRoom = 0.dp),
        endAligned = true,
        renderButton = renderButton,
      )
    }
    return
  }

  Layout(
    modifier = modifier.fillMaxWidth(),
    content = {
      LandscapeActionGroup(
        buttons = leftButtons,
        modifier = leftModifier,
        fillWidth = false,
        renderButton = renderButton,
      )
      Box(Modifier.width(playbackWidth), contentAlignment = Alignment.Center) { playbackControls() }
      LandscapeActionGroup(
        buttons = rightButtons,
        modifier = rightModifier,
        endAligned = true,
        fillWidth = false,
        renderButton = renderButton,
      )
    },
  ) { measurables, constraints ->
    val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
    val left = measurables[0].measure(childConstraints)
    val playback = measurables[1].measure(childConstraints)
    val right = measurables[2].measure(childConstraints)
    val gap = LandscapeActionGap.roundToPx()
    val actionsWidth = left.width + right.width + if (left.width > 0 && right.width > 0) gap else 0
    val rowWidth = if (playback.width == 0) actionsWidth else {
      left.width + playback.width + right.width +
        (if (left.width > 0) gap else 0) + (if (right.width > 0) gap else 0)
    }
    val width = constraints.maxWidth
    if (rowWidth <= width) {
      val height = maxOf(left.height, playback.height, right.height)
      layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
        left.placeRelative(0, (height - left.height) / 2)
        val playbackStart = if (playback.width == 0) width / 2 else {
          ((width - playback.width) / 2).coerceIn(
            left.width + if (left.width > 0) gap else 0,
            width - right.width - playback.width - if (right.width > 0) gap else 0,
          )
        }
        playback.placeRelative(playbackStart, (height - playback.height) / 2)
        right.placeRelative(width - right.width, (height - right.height) / 2)
      }
    } else {
      val playbackHeight = if (playback.width > 0) playback.height else 0
      val actionsTop = playbackHeight + if (playbackHeight > 0) gap else 0
      val sharedActions = actionsWidth <= width
      val actionsHeight = if (sharedActions) maxOf(left.height, right.height) else {
        left.height + right.height + gap
      }
      layout(width, (actionsTop + actionsHeight).coerceIn(constraints.minHeight, constraints.maxHeight)) {
        playback.placeRelative((width - playback.width) / 2, 0)
        left.placeRelative(0, actionsTop)
        right.placeRelative(width - right.width, actionsTop + if (sharedActions) 0 else left.height + gap)
      }
    }
  }
}
