/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

internal data class VideoDimensions(
  val width: Double,
  val height: Double,
)

/** Geometry shared by playback aspect modes and the ambient background. */
internal object VideoAspectGeometry {
  private val cropDimensionsRegex = Regex("""^(\d+)x(\d+)""")

  fun currentEffectiveDimensions(
    fallbackWidth: Double = 1920.0,
    fallbackHeight: Double = 1080.0,
  ): VideoDimensions {
    val source = currentSourceDimensions() ?: VideoDimensions(fallbackWidth, fallbackHeight)
    return currentCropDimensions() ?: source
  }

  /**
   * Includes crop, pixel aspect and rotation. Ambient also respects an explicit aspect override.
   */
  fun currentEffectiveDisplayAspect(
    fallbackWidth: Double = 1920.0,
    fallbackHeight: Double = 1080.0,
    respectAspectOverride: Boolean = false,
  ): Double? {
    val source = currentSourceDimensions()
    val aspectOverride =
      if (respectAspectOverride) {
        PlaybackSession.getPropertyString("video-aspect-override")?.toDoubleOrNull()
          ?.takeIf { it.isFinite() && it > 0.0 }
      } else {
        null
      }
    val pixelAspectRatio =
      if (aspectOverride != null && source != null) {
        aspectOverride * source.height / source.width
      } else {
        PlaybackSession.getPropertyDouble("video-params/par") ?: 1.0
      }
    return effectiveDisplayAspect(
      dimensions = currentEffectiveDimensions(fallbackWidth, fallbackHeight),
      pixelAspectRatio = pixelAspectRatio,
      rotationDegrees = PlaybackSession.getPropertyInt("video-params/rotate") ?: 0,
    )
  }

  internal fun effectiveDisplayAspect(
    dimensions: VideoDimensions,
    pixelAspectRatio: Double,
    rotationDegrees: Int,
  ): Double? {
    if (!dimensions.width.isFinite() || !dimensions.height.isFinite() ||
      dimensions.width <= 0.0 || dimensions.height <= 0.0
    ) {
      return null
    }
    val par = pixelAspectRatio.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val unrotated = dimensions.width * par / dimensions.height
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    return if (normalizedRotation == 90 || normalizedRotation == 270) 1.0 / unrotated else unrotated
  }

  /**
   * Converts a desired post-crop display ratio to mpv's full-source aspect override.
   *
   * mpv applies `video-crop` to the source rectangle before anamorphic/aspect stretching. Therefore
   * passing the viewport ratio directly makes a cropped image too wide or too tall. Compensating
   * for the crop keeps the final visible rectangle at the requested viewport ratio.
   */
  fun stretchAspectOverride(viewportRatio: Double): Double {
    if (!viewportRatio.isFinite() || viewportRatio <= 0.0) return viewportRatio
    val source = currentSourceDimensions() ?: return viewportRatio
    val crop = currentCropDimensions() ?: return viewportRatio
    val widthFraction = crop.width / source.width
    val heightFraction = crop.height / source.height
    if (widthFraction <= 0.0 || heightFraction <= 0.0) return viewportRatio
    return viewportRatio * heightFraction / widthFraction
  }

  private fun currentSourceDimensions(): VideoDimensions? {
    val width = (PlaybackSession.getPropertyInt("video-params/w") ?: 0).toDouble()
    val height = (PlaybackSession.getPropertyInt("video-params/h") ?: 0).toDouble()
    return VideoDimensions(width, height).takeIf { it.width > 0.0 && it.height > 0.0 }
  }

  private fun currentCropDimensions(): VideoDimensions? {
    val match =
      PlaybackSession.getPropertyString("video-crop")
        ?.let(cropDimensionsRegex::find)
        ?: return null
    val width = match.groupValues[1].toDoubleOrNull() ?: return null
    val height = match.groupValues[2].toDoubleOrNull() ?: return null
    return VideoDimensions(width, height).takeIf { it.width > 0.0 && it.height > 0.0 }
  }
}
