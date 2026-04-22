package app.gyrolet.mpvrx.ui.player

import `is`.xyz.mpv.MPVLib

private const val MIN_SUBTITLE_POSITION = 0
private const val MAX_SUBTITLE_POSITION = 150
private const val SECONDARY_SUBTITLE_POSITION_OFFSET = 10

fun clampSubtitlePosition(position: Int): Int =
  position.coerceIn(MIN_SUBTITLE_POSITION, MAX_SUBTITLE_POSITION)

fun calculateSecondarySubtitlePosition(primaryPosition: Int): Int {
  val primary = clampSubtitlePosition(primaryPosition)
  val abovePrimary = primary - SECONDARY_SUBTITLE_POSITION_OFFSET

  return if (abovePrimary >= MIN_SUBTITLE_POSITION) {
    abovePrimary
  } else {
    (primary + SECONDARY_SUBTITLE_POSITION_OFFSET).coerceIn(MIN_SUBTITLE_POSITION, MAX_SUBTITLE_POSITION)
  }
}

fun isSecondarySubtitleActive(): Boolean = getTrackSelectionId("secondary-sid") > 0

fun subtitleAssOverrideValue(
  forceAssOverride: Boolean,
  secondarySubtitleActive: Boolean = isSecondarySubtitleActive(),
): String = if (forceAssOverride || secondarySubtitleActive) "force" else "scale"

fun applySubtitleOverrides(forceAssOverride: Boolean) {
  val overrideValue = subtitleAssOverrideValue(forceAssOverride)
  MPVLib.setPropertyString("sub-ass-override", overrideValue)
  MPVLib.setPropertyString("secondary-sub-ass-override", overrideValue)
}

fun applySubtitlePositions(primaryPosition: Int) {
  val primary = clampSubtitlePosition(primaryPosition)

  // Keep the secondary subtitle offset from the primary one so dual subtitles
  // stay readable instead of drawing on the same baseline.
  MPVLib.setPropertyInt("sub-pos", primary)
  MPVLib.setPropertyInt("secondary-sub-pos", calculateSecondarySubtitlePosition(primary))
}

fun applySubtitleLayout(
  primaryPosition: Int,
  forceAssOverride: Boolean,
) {
  applySubtitleOverrides(forceAssOverride)
  applySubtitlePositions(primaryPosition)
}

