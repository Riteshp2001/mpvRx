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

private const val MIN_SUBTITLE_POSITION = 0
private const val MAX_SUBTITLE_POSITION = 150

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
  val subText = if (getTrackSelectionId("sid") > 0) PlaybackSession.getPropertyString("sub-text").orEmpty() else ""
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

fun isSecondarySubtitleActive(): Boolean = getTrackSelectionId("secondary-sid") > 0

fun subtitleAssOverrideValue(
  forceAssOverride: Boolean,
): String = if (forceAssOverride) "force" else "scale"

fun applySubtitleOverrides(forceAssOverride: Boolean) {
  val primaryOverride = subtitleAssOverrideValue(forceAssOverride)
  val secondaryOverride = if (forceAssOverride || isSecondarySubtitleActive()) "force" else "scale"
  PlaybackSession.setPropertyString("sub-ass-override", primaryOverride)
  PlaybackSession.setPropertyString("secondary-sub-ass-override", secondaryOverride)
}

fun applySubtitlePositions(
  primaryPosition: Int,
  secondaryPosition: Int = subtitlesPreferences.secondarySubPos.get(),
) {
  val primary = clampSubtitlePosition(primaryPosition)
  PlaybackSession.setPropertyInt("sub-pos", primary)
  PlaybackSession.setPropertyInt("secondary-sub-pos", clampSubtitlePosition(secondaryPosition))
}

fun applySubtitleLayout(
  primaryPosition: Int,
  forceAssOverride: Boolean,
  secondaryPosition: Int = subtitlesPreferences.secondarySubPos.get(),
) {
  applySubtitleOverrides(forceAssOverride)
  applySubtitlePositions(primaryPosition, secondaryPosition)
}
