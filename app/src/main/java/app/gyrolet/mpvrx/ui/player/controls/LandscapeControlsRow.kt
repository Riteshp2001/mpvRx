/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.ui.player.controls.components.fadingHorizontalScroll

internal val LandscapeActionSize = 48.dp
internal val LandscapeActionGap = 8.dp

internal fun resolveLandscapeHeaderMaxWidth(width: Dp): Dp =
  (width * 0.45f - 24.dp).coerceAtLeast(LandscapeActionSize)

internal fun resolveLandscapeHeaderWidth(buttons: List<PlayerButton>, width: Dp): Dp {
  val count = buttons.count { it != PlayerButton.NONE }
  val contentWidth = if (count == 0) 0.dp else LandscapeActionSize * count + LandscapeActionGap * (count - 1)
  return minOf(contentWidth, resolveLandscapeHeaderMaxWidth(width))
}

/** Keep the original action order; excess controls scroll within their own side of the row. */
@Composable
internal fun LandscapeActionGroup(
  buttons: List<PlayerButton>,
  modifier: Modifier = Modifier,
  endAligned: Boolean = false,
  fillWidth: Boolean = true,
  renderButton: @Composable (PlayerButton) -> Unit,
) {
  Row(
    // Headers measure the actual text/pill widths instead of assuming every action is a 48dp icon.
    modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
      .fadingHorizontalScroll(rememberScrollState(), reverseScrolling = endAligned, shadowRoom = 0.dp),
    horizontalArrangement = Arrangement.spacedBy(
      LandscapeActionGap, if (endAligned) Alignment.End else Alignment.Start,
    ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    buttons.filterNot { it == PlayerButton.NONE }.forEach { button ->
      key(button) { renderButton(button) }
    }
  }
}

/** Equal side slots keep play/pause at the screen centre, regardless of either action list. */
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
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val sideWidth = (maxWidth - playbackWidth - LandscapeActionGap * 2) / 2
    if (leftButtons.isEmpty() && rightButtons.isEmpty()) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { playbackControls() }
    } else if (sideWidth < LandscapeActionSize) {
      // Very narrow multi-window surfaces cannot fit two 48dp actions beside the transport.
      // Reflow rather than shrinking touch targets or covering previous/next.
      Column(verticalArrangement = Arrangement.spacedBy(LandscapeActionGap)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { playbackControls() }
        LandscapeActionGroup(
          buttons = (leftButtons + rightButtons).distinct(),
          modifier = Modifier.fillMaxWidth(),
          renderButton = renderButton,
        )
      }
    } else {
      Row(
        horizontalArrangement = Arrangement.spacedBy(LandscapeActionGap),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        LandscapeActionGroup(
          buttons = leftButtons,
          modifier = Modifier.weight(1f).then(leftModifier),
          renderButton = renderButton,
        )
        Box(Modifier.width(playbackWidth), contentAlignment = Alignment.Center) { playbackControls() }
        LandscapeActionGroup(
          buttons = rightButtons,
          modifier = Modifier.weight(1f).then(rightModifier),
          endAligned = true,
          renderButton = renderButton,
        )
      }
    }
  }
}
