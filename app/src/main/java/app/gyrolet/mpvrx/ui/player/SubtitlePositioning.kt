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
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import org.koin.core.context.GlobalContext

private const val MIN_SUBTITLE_POSITION = 0
private const val MAX_SUBTITLE_POSITION = 150

private val subtitlesPreferences by lazy {
  GlobalContext.get().get<SubtitlesPreferences>()
}

fun clampSubtitlePosition(position: Int): Int = position.coerceIn(MIN_SUBTITLE_POSITION, MAX_SUBTITLE_POSITION)

fun getSubtitleGestureRegion(
  target: SubtitleGestureTarget,
  screenWidth: Float,
  screenHeight: Float,
  renderInVideoFrame: Boolean,
  includeWhenTextEmpty: Boolean = false,
): SubtitleGestureRegion? {
  val trackId = getTrackSelectionId(target.selectionProperty)
  if (trackId <= 0 || PlaybackSession.getPropertyBoolean("${target.prefix}-visibility") == false) return null
  val secondary = target == SubtitleGestureTarget.Secondary
  val position = PlaybackSession.getPropertyFloat("${target.prefix}-pos")
    ?: (if (secondary) subtitlesPreferences.secondarySubPos.get() else subtitlesPreferences.subPos.get()).toFloat()
  val scale = PlaybackSession.getPropertyFloat("${target.prefix}-scale")
    ?: if (secondary) subtitlesPreferences.secondarySubScale.get() else subtitlesPreferences.subScale.get()
  val aspect = PlaybackSession.getPropertyDouble("video-params/aspect")?.toFloat() ?: 0f
  val videoHeight = if (aspect > 0f) minOf(screenHeight, screenWidth / aspect) else screenHeight
  val renderHeight = if (renderInVideoFrame) videoHeight else screenHeight
  val renderTop = if (renderInVideoFrame) (screenHeight - renderHeight) / 2f else 0f
  val fontReferenceHeight = when {
    PlaybackSession.getPropertyString("sub-scale-by-window") == "no" -> 720f
    PlaybackSession.getPropertyString("sub-scale-with-window") == "no" -> videoHeight
    else -> screenHeight
  }
  return estimateSubtitleGestureRegion(
    target = target,
    trackId = trackId,
    // Center holds need position geometry even between subtitle lines. This placeholder is
    // only used for geometry; it is never rendered or included in subtitle hit testing.
    text = PlaybackSession.getPropertyString("${target.prefix}-text").orEmpty().let {
      if (includeWhenTextEmpty && it.isBlank()) "M" else it
    },
    // Center drags must also recover a subtitle positioned beyond the visible frame.
    position = if (includeWhenTextEmpty) position.coerceIn(0f, 100f) else position,
    scale = scale,
    screenWidth = screenWidth,
    screenHeight = screenHeight,
    renderTop = renderTop,
    renderHeight = renderHeight,
    fontSize = (PlaybackSession.getPropertyInt("sub-font-size") ?: subtitlesPreferences.fontSize.get()).toFloat(),
    marginX = (PlaybackSession.getPropertyInt("sub-margin-x") ?: 19).toFloat(),
    marginY = (PlaybackSession.getPropertyInt("sub-margin-y") ?: 34).toFloat(),
    fontReferenceHeight = fontReferenceHeight,
  )?.let { estimatedRegion ->
    val region = if (includeWhenTextEmpty) estimatedRegion.copy(position = position) else estimatedRegion
    if (!overlaysFollowVideoZoom()) return@let region
    region.zoomedAroundCenter(
      videoZoomMultiplier(PlaybackSession.videoZoom.value),
      PlaybackSession.videoPanX.value, PlaybackSession.videoPanY.value, screenWidth, screenHeight,
    )
  }
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

/** True when the SurfaceView itself is zoomed, so subtitles and mpv's OSD move with the video. */
fun overlaysFollowVideoZoom(): Boolean = GlobalContext.get().get<PlayerPreferences>().overlaysFollowVideoZoom.get()

fun applySubtitleBlendMode() {
  val blendMode = subtitleBlendMode()
  if (PlaybackSession.getPropertyString("blend-subtitles") != blendMode) {
    PlaybackSession.setPropertyString("blend-subtitles", blendMode)
  }
}

fun applySubtitleScales() {
  PlaybackSession.setPropertyFloat("sub-scale", subtitlesPreferences.subScale.get())
  PlaybackSession.setPropertyFloat("secondary-sub-scale", subtitlesPreferences.secondarySubScale.get())
}

/** Blending into the video would bake subtitle pixels into mpv's own zoom and pan. */
fun subtitleBlendMode(
  ambientActive: Boolean = PlaybackSession.isAmbientShaderActive,
): String {
  val transformed = !overlaysFollowVideoZoom() && (
    PlaybackSession.videoZoom.value != 0f ||
      PlaybackSession.videoPanX.value != 0f || PlaybackSession.videoPanY.value != 0f
    )
  // An OUTPUT Ambient pass remaps video pixels non-uniformly. Subtitle pixels must never enter
  // that pass (gpu and gpu-next blend at different stages). Restore the preference on teardown.
  return if (!ambientActive && !transformed && subtitlesPreferences.blendSubtitlesWithVideo.get()) "video" else "no"
}
