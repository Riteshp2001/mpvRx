/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls

import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlaybackControlsPlacement
import app.gyrolet.mpvrx.preferences.SeekbarPlacement
import app.gyrolet.mpvrx.ui.theme.Spacing

internal enum class BottomSlot { Buttons, Seekbar, Playback }

// Keep portrait gaps relative to the visible button surface, excluding glass shadow padding.
internal val PortraitSectionGap = 12.dp
internal val PortraitActionShadowRoom = 6.dp
internal val PlaybackShadowRoom = 8.dp

internal fun portraitGlassShadowRoom(slot: BottomSlot?, enabled: Boolean): Dp {
  if (!enabled) return 0.dp
  return when (slot) {
    BottomSlot.Buttons -> PortraitActionShadowRoom
    BottomSlot.Playback -> PlaybackShadowRoom
    else -> 0.dp
  }
}

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
  landscape: Boolean = false,
): PlaybackControlsGeometry {
  if (landscape) return PlaybackControlsGeometry(56.dp, 48.dp, 8.dp)
  val compact = width < 360.dp
  return PlaybackControlsGeometry(
    playPauseSize = if (compact) 56.dp else 68.dp,
    skipButtonSize = if (compact) 48.dp else 60.dp,
    buttonGap = if (compact) spacing.smaller else spacing.small,
  )
}

/** Playback controls stay beside the seekbar without changing the action-row preset. */
internal fun resolveBottomControlsLayout(
  seekbarPlacement: SeekbarPlacement,
  hasButtons: Boolean = true,
  playbackControlsPlacement: PlaybackControlsPlacement = PlaybackControlsPlacement.Center,
  sharedLandscapeRow: Boolean = false,
): BottomControlsLayout =
  BottomControlsLayout(
    bottomToTop = buildList {
      if (sharedLandscapeRow) {
        if (seekbarPlacement == SeekbarPlacement.AboveButtons) {
          add(BottomSlot.Playback)
          add(BottomSlot.Seekbar)
        } else {
          add(BottomSlot.Seekbar)
          add(BottomSlot.Playback)
        }
        return@buildList
      }
      if (hasButtons && seekbarPlacement == SeekbarPlacement.AboveButtons) add(BottomSlot.Buttons)
      if (playbackControlsPlacement == PlaybackControlsPlacement.BelowSeekbar) add(BottomSlot.Playback)
      add(BottomSlot.Seekbar)
      if (playbackControlsPlacement == PlaybackControlsPlacement.AboveSeekbar) add(BottomSlot.Playback)
      if (hasButtons && seekbarPlacement == SeekbarPlacement.BelowButtons) add(BottomSlot.Buttons)
    },
  )

