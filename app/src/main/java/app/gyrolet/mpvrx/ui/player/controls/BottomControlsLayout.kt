/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls

import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlaybackControlsPosition
import app.gyrolet.mpvrx.preferences.SeekbarPlacement
import app.gyrolet.mpvrx.ui.theme.Spacing

internal enum class BottomSlot { Buttons, Playback, Seekbar }

internal enum class VideoControlsLayoutPreset(
  @StringRes val titleRes: Int,
  val seekbarPlacement: SeekbarPlacement,
  val playbackPosition: PlaybackControlsPosition,
) {
  CenterSeekbarAboveActions(
    R.string.pref_layout_center_seekbar_above,
    SeekbarPlacement.AboveButtons,
    PlaybackControlsPosition.Center,
  ),
  CenterSeekbarBelowActions(
    R.string.pref_layout_center_seekbar_below,
    SeekbarPlacement.BelowButtons,
    PlaybackControlsPosition.Center,
  ),
  PlaybackAboveSeekbar(
    R.string.pref_layout_playback_above,
    SeekbarPlacement.BelowButtons,
    PlaybackControlsPosition.AboveSeekbar,
  ),
  PlaybackBelowSeekbar(
    R.string.pref_layout_playback_below,
    SeekbarPlacement.AboveButtons,
    PlaybackControlsPosition.BelowSeekbar,
  ),
  ;

  companion object {
    // Keep existing saved keys; older three-row combinations collapse toward their playback position.
    fun fromPreferences(
      seekbarPlacement: SeekbarPlacement,
      playbackPosition: PlaybackControlsPosition,
    ): VideoControlsLayoutPreset = when (playbackPosition) {
      PlaybackControlsPosition.AboveSeekbar -> PlaybackAboveSeekbar
      PlaybackControlsPosition.BelowSeekbar -> PlaybackBelowSeekbar
      PlaybackControlsPosition.Center ->
        if (seekbarPlacement == SeekbarPlacement.AboveButtons) CenterSeekbarAboveActions else CenterSeekbarBelowActions
    }
  }
}

internal data class BottomControlsLayout(
  val bottomToTop: List<BottomSlot>,
  val buttonsBesidePlayback: Boolean,
)

internal data class PlaybackControlsGeometry(
  val playPauseSize: Dp,
  val skipButtonSize: Dp,
  val buttonGap: Dp,
  val rowHalfWidth: Dp,
  val sideGap: Dp,
  val canShareRow: Boolean,
)

/** Shared by playback and its editor preview so compact sizing and narrow-window fallback agree. */
internal fun resolvePlaybackControlsGeometry(
  width: Dp,
  playbackPosition: PlaybackControlsPosition,
  hasPlaylistControls: Boolean,
  hasActions: Boolean,
  spacing: Spacing,
  horizontalInsets: Dp = 0.dp,
): PlaybackControlsGeometry {
  val compact = width < 360.dp || (playbackPosition != PlaybackControlsPosition.Center && width < 480.dp)
  val playPauseSize = if (compact) 56.dp else 68.dp
  val skipButtonSize = if (compact) 48.dp else 60.dp
  val buttonGap = if (compact) 4.dp else spacing.large
  val rowHalfWidth =
    (playPauseSize + if (hasPlaylistControls) (skipButtonSize + buttonGap) * 2 else 0.dp) / 2
  val sideGap = if (compact) spacing.smaller else spacing.medium
  val minimumWidth =
    rowHalfWidth * 2 + spacing.large * 2 + horizontalInsets +
      if (hasActions) 96.dp + sideGap * 2 else 0.dp
  return PlaybackControlsGeometry(playPauseSize, skipButtonSize, buttonGap, rowHalfWidth, sideGap, width >= minimumWidth)
}

/**
 * Android video controls have at most two bottom rows. An exceptionally narrow window keeps
 * playback in the centre rather than clipping action touch targets beside it.
 */
internal fun resolveBottomControlsLayout(
  seekbarPlacement: SeekbarPlacement,
  playbackPosition: PlaybackControlsPosition,
  hasButtons: Boolean = true,
  allowSharedRow: Boolean = true,
): BottomControlsLayout {
  val preset = VideoControlsLayoutPreset.fromPreferences(seekbarPlacement, playbackPosition)
  val buttonsBesidePlayback = allowSharedRow && preset.playbackPosition != PlaybackControlsPosition.Center
  val includeButtonsRow = hasButtons || buttonsBesidePlayback
  val bottomToTop = buildList {
    if (includeButtonsRow && preset.seekbarPlacement == SeekbarPlacement.AboveButtons) add(BottomSlot.Buttons)
    add(BottomSlot.Seekbar)
    if (includeButtonsRow && preset.seekbarPlacement == SeekbarPlacement.BelowButtons) add(BottomSlot.Buttons)
  }
  return BottomControlsLayout(bottomToTop, buttonsBesidePlayback)
}
