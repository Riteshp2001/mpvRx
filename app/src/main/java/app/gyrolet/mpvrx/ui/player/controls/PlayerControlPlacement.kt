/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.PlayerButton

internal const val MaxPlayerButtonsPerRegion = 4
internal val PlayerControlButtonSize = 48.dp

internal data class PlayerControlPlacement(
  val topRight: List<PlayerButton> = emptyList(),
  val bottomRight: List<PlayerButton> = emptyList(),
  val bottomLeft: List<PlayerButton> = emptyList(),
  val portraitBottom: List<PlayerButton> = emptyList(),
  /** Actions that did not fit in their configured region. */
  val overflow: List<PlayerButton> = emptyList(),
)

/** Shared by the player and editor preview. Filter unavailable actions before calling this. */
internal fun resolvePlayerControlPlacement(
  isPortrait: Boolean,
  topRight: List<PlayerButton>,
  bottomRight: List<PlayerButton>,
  bottomLeft: List<PlayerButton>,
  portraitBottom: List<PlayerButton>,
  availableWidth: Dp,
  landscapeGap: Dp,
  portraitHorizontalInset: Dp = 0.dp,
  showVideoQualitySelector: Boolean = false,
): PlayerControlPlacement {
  val used = mutableSetOf(PlayerButton.NONE, PlayerButton.BACK_ARROW, PlayerButton.VIDEO_TITLE)
  fun unique(buttons: List<PlayerButton>) = buttons.filter { used.add(it) }
  fun capacity(width: Dp, gap: Dp) =
    ((width.coerceAtLeast(0.dp) + gap) / (PlayerControlButtonSize + gap))
      .toInt().coerceIn(0, MaxPlayerButtonsPerRegion)

  if (isPortrait) {
    val buttons = unique(portraitBottom).toMutableList()
    if (showVideoQualitySelector && used.add(PlayerButton.VIDEO_QUALITY)) buttons += PlayerButton.VIDEO_QUALITY
    val count = capacity(availableWidth - portraitHorizontalInset * 2, 0.dp)
    return PlayerControlPlacement(
      portraitBottom = buttons.take(count),
      overflow = buttons.drop(count),
    )
  }

  val top = unique(topRight)
  val right = unique(bottomRight).toMutableList()
  val left = unique(bottomLeft)
  if (showVideoQualitySelector && used.add(PlayerButton.VIDEO_QUALITY)) right += PlayerButton.VIDEO_QUALITY
  val bottomWidth = if (right.isNotEmpty() && left.isNotEmpty()) availableWidth / 2 - landscapeGap * 2 else availableWidth
  val bottomCount = capacity(bottomWidth, landscapeGap)
  // Back and the title keep 96dp; narrower windows move more top-bar actions below the title.
  val topCount =
    (((availableWidth - 96.dp).coerceAtLeast(0.dp) + landscapeGap) / (PlayerControlButtonSize + landscapeGap))
      .toInt().coerceAtLeast(0)
  return PlayerControlPlacement(
    topRight = top.take(topCount),
    bottomRight = right.take(bottomCount),
    bottomLeft = left.take(bottomCount),
    overflow = top.drop(topCount) + right.drop(bottomCount) + left.drop(bottomCount),
  )
}
