/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls

import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.SeekbarPlacement
import app.gyrolet.mpvrx.ui.theme.Spacing

internal enum class BottomSlot { Buttons, Seekbar }

internal enum class VideoControlsLayoutPreset(
  @StringRes val titleRes: Int,
  val seekbarPlacement: SeekbarPlacement,
) {
  SeekbarAbove(R.string.pref_layout_seekbar_above, SeekbarPlacement.AboveButtons),
  SeekbarBelow(R.string.pref_layout_seekbar_below, SeekbarPlacement.BelowButtons),
  ;

  companion object {
    fun fromPreferences(seekbarPlacement: SeekbarPlacement): VideoControlsLayoutPreset =
      if (seekbarPlacement == SeekbarPlacement.AboveButtons) SeekbarAbove else SeekbarBelow
  }
}

internal data class BottomControlsLayout(
  val bottomToTop: List<BottomSlot>,
)

internal data class PlaybackControlsGeometry(
  val playPauseSize: Dp,
  val skipButtonSize: Dp,
  val buttonGap: Dp,
)

/** Shared by playback and its editor preview to keep compact button sizing consistent. */
internal fun resolvePlaybackControlsGeometry(
  width: Dp,
  spacing: Spacing,
): PlaybackControlsGeometry {
  val compact = width < 360.dp
  return PlaybackControlsGeometry(
    playPauseSize = if (compact) 56.dp else 68.dp,
    skipButtonSize = if (compact) 48.dp else 60.dp,
    buttonGap = if (compact) 4.dp else spacing.large,
  )
}

/** Play/pause stays centred on the video; only the action row and seekbar change order. */
internal fun resolveBottomControlsLayout(
  seekbarPlacement: SeekbarPlacement,
  hasButtons: Boolean = true,
): BottomControlsLayout =
  BottomControlsLayout(
    bottomToTop = buildList {
      if (hasButtons && seekbarPlacement == SeekbarPlacement.AboveButtons) add(BottomSlot.Buttons)
      add(BottomSlot.Seekbar)
      if (hasButtons && seekbarPlacement == SeekbarPlacement.BelowButtons) add(BottomSlot.Buttons)
    },
  )
