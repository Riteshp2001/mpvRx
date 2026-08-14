/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt

private const val MIN_SUBTITLE_POSITION = 0
private const val MAX_SUBTITLE_POSITION = 150
private const val NATIVE_ASS_POSITION = 100
private const val NATIVE_ASS_SCALE = 1f
private const val SECONDARY_SUBTITLE_POSITION_OFFSET = 10

private val subtitlesPreferences by lazy {
  GlobalContext.get().get<SubtitlesPreferences>()
}

private val HTML_TAG_REGEX = Regex("<[^>]*>")
private val ASS_TAG_REGEX = Regex("[{][^}]*[}]")

fun clampSubtitlePosition(position: Int): Int = position.coerceIn(MIN_SUBTITLE_POSITION, MAX_SUBTITLE_POSITION)

/**
 * Estimates subtitle hitbox bounds (lowerBound, upperBound) relative to subtitleScreenY.
 * Accounts for sub-text content, font size, sub-scale, and screen width to handle
 * multi-line wrapping in both portrait and landscape.
 */
fun getSubtitleHitboxBounds(
  screenWidth: Float,
  screenHeight: Float,
): Pair<Float, Float> {
  val subScale = PlaybackSession.getPropertyFloat("sub-scale") ?: subtitlesPreferences.subScale.get()
  val fontSize = (PlaybackSession.getPropertyInt("sub-font-size") ?: subtitlesPreferences.fontSize.get()).toFloat()
  val scaleMultiplier = subScale.coerceIn(0.4f, 3.0f)

  // Estimate per-line height in screen pixels.
  // sub-font-size is in "arbitrary" units scaled relative to screen height (720 reference).
  val lineHeightPx = (fontSize / 720f) * screenHeight * scaleMultiplier * 1.3f

  // Estimate how many lines the subtitle actually occupies
  val subText = PlaybackSession.getPropertyString("sub-text") ?: ""
  val estimatedLines =
    if (subText.isNotEmpty()) {
      // Count explicit newlines first
      val explicitLines = subText.split("\n")

      // Estimate wrapping per explicit line based on available width
      // Subtitles typically use ~80% of screen width (sub-margin-x on each side)
      val subMarginX = (PlaybackSession.getPropertyInt("sub-margin-x") ?: 25).toFloat()
      val availableWidth = screenWidth * (1f - 2f * subMarginX / screenWidth.coerceAtLeast(1f))

      // Estimate character width: roughly fontSize * scale * 0.55 (typical char width ratio)
      val charWidthPx = (fontSize / 720f) * screenHeight * scaleMultiplier * 0.55f
      val charsPerLine = if (charWidthPx > 0f) (availableWidth / charWidthPx).toInt().coerceAtLeast(1) else 40

      var totalLines = 0
      for (line in explicitLines) {
        val stripped = line.replace(HTML_TAG_REGEX, "").replace(ASS_TAG_REGEX, "")
        totalLines +=
          if (stripped.isEmpty()) 1 else ((stripped.length + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
      }
      totalLines.coerceAtLeast(1)
    } else {
      // No text available, assume a reasonable default
      2
    }

  // Subtitle text grows upward from the sub-pos anchor point.
  // Lower bound: small region below the anchor (padding for touch imprecision)
  val lowerBound = -50f * scaleMultiplier
  // Upper bound: covers the full estimated subtitle height + padding
  val estimatedSubtitleHeight = lineHeightPx * estimatedLines
  val upperBound = (estimatedSubtitleHeight + 80f * scaleMultiplier).coerceAtLeast(200f * scaleMultiplier)

  return Pair(lowerBound, upperBound)
}

fun calculateSecondarySubtitlePosition(
  primaryPosition: Int,
  screenWidth: Float? = null,
  screenHeight: Float? = null,
): Int {
  val primary = clampSubtitlePosition(primaryPosition)

  val width =
    screenWidth ?: PlaybackSession.getPropertyInt("osd-width")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.widthPixels
        .toFloat()
  val height =
    screenHeight ?: PlaybackSession.getPropertyInt("osd-height")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.heightPixels
        .toFloat()

  // Calculate the hitbox of the primary subtitle
  val (_, upperBound) = getSubtitleHitboxBounds(width, height)

  // Convert the hitbox height (pixels) to a percentage of the screen/OSD height
  val offsetPercent = (upperBound / height) * 100f

  // Dynamic offset, clamped to a reasonable range
  val offset = offsetPercent.roundToInt().coerceIn(8, 50)

  val abovePrimary = primary - offset

  return if (abovePrimary >= MIN_SUBTITLE_POSITION) {
    abovePrimary
  } else {
    (primary + offset).coerceIn(MIN_SUBTITLE_POSITION, MAX_SUBTITLE_POSITION)
  }
}

fun isSecondarySubtitleActive(): Boolean = getTrackSelectionId("secondary-sid") > 0

data class SubtitleAssOverrideMode(
  val primary: String,
  val secondary: String,
)

fun subtitleAssOverrideMode(
  forceAssOverride: Boolean,
  secondarySubtitleActive: Boolean = isSecondarySubtitleActive(),
): SubtitleAssOverrideMode =
  SubtitleAssOverrideMode(
    primary = if (forceAssOverride) "force" else "no",
    // mpv strips native styling from secondary subtitles. Force only that renderer when active
    // so MPVRX can keep dual subtitles separated without modifying the primary ASS track.
    secondary = if (forceAssOverride || secondarySubtitleActive) "force" else "no",
  )

fun isAssSubtitleCodec(codec: String?): Boolean =
  codec?.contains("ass", ignoreCase = true) == true ||
    codec?.contains("ssa", ignoreCase = true) == true

fun isPrimarySubtitleAss(): Boolean = isSelectedSubtitleAss("sid")

private fun isSelectedSubtitleAss(selectionProperty: String): Boolean {
  val selectedId = getTrackSelectionId(selectionProperty)
  if (selectedId <= 0) return false

  val trackCount = PlaybackSession.getPropertyInt("track-list/count") ?: return false
  for (index in 0 until trackCount) {
    if (PlaybackSession.getPropertyInt("track-list/$index/id") != selectedId) continue
    if (PlaybackSession.getPropertyString("track-list/$index/type") != "sub") return false
    return isAssSubtitleCodec(PlaybackSession.getPropertyString("track-list/$index/codec"))
  }
  return false
}

fun applySubtitleOverrides(forceAssOverride: Boolean) {
  val mode = subtitleAssOverrideMode(forceAssOverride)
  PlaybackSession.setPropertyString("sub-ass-override", mode.primary)
  PlaybackSession.setPropertyString("secondary-sub-ass-override", mode.secondary)
  PlaybackSession.setPropertyString("sub-ass-justify", if (forceAssOverride) "yes" else "no")
}

fun applySubtitlePositions(
  primaryPosition: Int,
  screenWidth: Float? = null,
  screenHeight: Float? = null,
  forceAssOverride: Boolean = subtitlesPreferences.overrideAssSubs.get(),
) {
  val primary = clampSubtitlePosition(primaryPosition)
  val preservePrimaryAssLayout = isPrimarySubtitleAss() && !forceAssOverride
  PlaybackSession.setPropertyInt("sub-pos", if (preservePrimaryAssLayout) NATIVE_ASS_POSITION else primary)
  if (preservePrimaryAssLayout) {
    PlaybackSession.setPropertyFloat("sub-scale", NATIVE_ASS_SCALE)
  }

  // Retrieve OSD or display dimensions as fallbacks if null
  val width =
    screenWidth ?: PlaybackSession.getPropertyInt("osd-width")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.widthPixels
        .toFloat()
  val height =
    screenHeight ?: PlaybackSession.getPropertyInt("osd-height")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.heightPixels
        .toFloat()

  PlaybackSession.setPropertyInt("secondary-sub-pos", calculateSecondarySubtitlePosition(primary, width, height))
}

fun applySubtitleLayout(
  primaryPosition: Int,
  forceAssOverride: Boolean,
  screenWidth: Float? = null,
  screenHeight: Float? = null,
) {
  applySubtitleOverrides(forceAssOverride)
  applySubtitlePositions(primaryPosition, screenWidth, screenHeight, forceAssOverride)
}
