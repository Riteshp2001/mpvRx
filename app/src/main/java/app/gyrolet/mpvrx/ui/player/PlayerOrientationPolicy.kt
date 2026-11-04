/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.content.pm.ActivityInfo
import app.gyrolet.mpvrx.preferences.AudioPlayerOrientation

/**
 * Single source of truth for which `requestedOrientation` the player should hold.
 *
 * Orientation used to be decided in three places (a pre-`super.onCreate()` launch guess,
 * `PlayerActivity.setOrientation`, and a separate stretch path in `PlayerObserver`), each with its
 * own aspect-to-rotation math. They could disagree, and every extra `requestedOrientation`
 * assignment forces a window relayout. Keeping the decision pure here means the call sites only
 * supply inputs and apply the result once.
 */
internal object PlayerOrientationPolicy {
  private const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  private const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

  /** Display aspect of the live session's video, kept for the next player Activity instance. */
  @Volatile
  private var lastVideoAspect: Pair<String, Double>? = null

  /**
   * Re-entry from the mini player or notification attaches to the running session, and its intent
   * carries no geometry. Remembering the aspect lets "Video" mode open the right way round again.
   */
  fun rememberVideoAspect(
    mediaId: String?,
    aspect: Double?,
  ) {
    if (mediaId == null || aspect == null || !aspect.isFinite() || aspect <= 0.0) return
    lastVideoAspect = mediaId to aspect
  }

  fun rememberedVideoAspect(mediaId: String?): Double? =
    lastVideoAspect?.takeIf { mediaId != null && it.first == mediaId }?.second

  /**
   * Orientation picked with the rotation button. It outranks the video preference for later videos
   * and re-entry until the next media launch, without changing the preference itself.
   */
  @Volatile
  var manualOrientation: Int? = null

  /**
   * Maps a user preference to an orientation, or null when the preference needs video geometry that
   * mpv has not reported yet.
   *
   * [aspect] is read lazily so callers whose preference cannot depend on geometry never pay for the
   * mpv round trip.
   */
  fun forPreference(
    preference: PlayerOrientation,
    aspect: () -> Double?,
  ): Int? =
    when (preference) {
      PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
      PlayerOrientation.Video -> orientationForAspect(aspect())
      PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
      PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
      PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

  fun forAudioPreference(preference: AudioPlayerOrientation): Int =
    when (preference) {
      AudioPlayerOrientation.Auto -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
      AudioPlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      AudioPlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

  /**
   * Orientation for a video whose dimensions are already known, e.g. from launch intent extras.
   *
   * [rotationDegrees] is the container rotation that has not been applied to [width]/[height] yet,
   * so a quarter turn swaps which axis is the long one. Returns null when the geometry is unusable,
   * which leaves the caller on whatever orientation it already had.
   */
  fun forSourceGeometry(
    width: Int,
    height: Int,
    rotationDegrees: Int,
  ): Int? {
    if (width <= 0 || height <= 0) return null
    val quarterTurned = normalizeDegrees(rotationDegrees)
    val isQuarterTurned = quarterTurned == 90 || quarterTurned == 270
    val aspect = if (isQuarterTurned) height.toDouble() / width else width.toDouble() / height
    return orientationForAspect(aspect)
  }

  /** mpv reports source geometry before rotation is applied, matching [forSourceGeometry]. */
  private fun orientationForAspect(aspect: Double?): Int? {
    if (aspect == null || !aspect.isFinite() || aspect <= 0.0) return null
    return if (aspect > 1.0) LANDSCAPE else PORTRAIT
  }

  private fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
