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
import `is`.xyz.mpv.MPVLib
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt

private const val MIN_SUBTITLE_POSITION = 0
private const val MAX_SUBTITLE_POSITION = 150
private const val SECONDARY_SUBTITLE_POSITION_OFFSET = 10

private val subtitlesPreferences by lazy {
  GlobalContext.get().get<SubtitlesPreferences>()
}

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
  val subScale = MPVLib.getPropertyFloat("sub-scale") ?: subtitlesPreferences.subScale.get()
  val fontSize = (MPVLib.getPropertyInt("sub-font-size") ?: subtitlesPreferences.fontSize.get()).toFloat()
  val scaleMultiplier = subScale.coerceIn(0.4f, 3.0f)

  // Estimate per-line height in screen pixels.
  // sub-font-size is in "arbitrary" units scaled relative to screen height (720 reference).
  val lineHeightPx = (fontSize / 720f) * screenHeight * scaleMultiplier * 1.3f

  // Estimate how many lines the subtitle actually occupies
  val subText = MPVLib.getPropertyString("sub-text") ?: ""
  val estimatedLines =
    if (subText.isNotEmpty()) {
      // Count explicit newlines first
      val explicitLines = subText.split("\n")

      // Estimate wrapping per explicit line based on available width
      // Subtitles typically use ~80% of screen width (sub-margin-x on each side)
      val subMarginX = (MPVLib.getPropertyInt("sub-margin-x") ?: 25).toFloat()
      val availableWidth = screenWidth * (1f - 2f * subMarginX / screenWidth.coerceAtLeast(1f))

      // Estimate character width: roughly fontSize * scale * 0.55 (typical char width ratio)
      val charWidthPx = (fontSize / 720f) * screenHeight * scaleMultiplier * 0.55f
      val charsPerLine = if (charWidthPx > 0f) (availableWidth / charWidthPx).toInt().coerceAtLeast(1) else 40

      var totalLines = 0
      for (line in explicitLines) {
        val stripped = line.replace(Regex("<[^>]*>"), "").replace(Regex("[{][^}]*[}]"), "")
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
    screenWidth ?: MPVLib.getPropertyInt("osd-width")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.widthPixels
        .toFloat()
  val height =
    screenHeight ?: MPVLib.getPropertyInt("osd-height")?.toFloat()
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

fun subtitleAssOverrideValue(
  forceAssOverride: Boolean,
  secondarySubtitleActive: Boolean = isSecondarySubtitleActive(),
): String = if (forceAssOverride || secondarySubtitleActive) "force" else "scale"

fun applySubtitleOverrides(forceAssOverride: Boolean) {
  val overrideValue = subtitleAssOverrideValue(forceAssOverride)
  MPVLib.setPropertyString("sub-ass-override", overrideValue)
  MPVLib.setPropertyString("secondary-sub-ass-override", overrideValue)
}

fun applySubtitlePositions(
  primaryPosition: Int,
  screenWidth: Float? = null,
  screenHeight: Float? = null,
) {
  val primary = clampSubtitlePosition(primaryPosition)
  MPVLib.setPropertyInt("sub-pos", primary)

  // Retrieve OSD or display dimensions as fallbacks if null
  val width =
    screenWidth ?: MPVLib.getPropertyInt("osd-width")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.widthPixels
        .toFloat()
  val height =
    screenHeight ?: MPVLib.getPropertyInt("osd-height")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.heightPixels
        .toFloat()

  MPVLib.setPropertyInt("secondary-sub-pos", calculateSecondarySubtitlePosition(primary, width, height))
}

fun applySubtitleLayout(
  primaryPosition: Int,
  forceAssOverride: Boolean,
  screenWidth: Float? = null,
  screenHeight: Float? = null,
) {
  applySubtitleOverrides(forceAssOverride)
  applySubtitlePositions(primaryPosition, screenWidth, screenHeight)
}
