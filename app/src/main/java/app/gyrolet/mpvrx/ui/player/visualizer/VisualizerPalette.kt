package app.gyrolet.mpvrx.ui.player.visualizer

import android.graphics.Color

/** Immutable semantic colors shared safely between the Compose and OpenGL threads. */
internal data class VisualizerPalette(
  val background: Int,
  val primary: Int,
  val secondary: Int,
  val tertiary: Int,
) {
  fun backgroundRgb(): FloatArray = background.toGlRgb()
  fun primaryRgb(): FloatArray = primary.toGlRgb()
  fun secondaryRgb(): FloatArray = secondary.toGlRgb()
  fun tertiaryRgb(): FloatArray = tertiary.toGlRgb()

  private fun Int.toGlRgb(): FloatArray =
    floatArrayOf(
      Color.red(this) / 255f,
      Color.green(this) / 255f,
      Color.blue(this) / 255f,
    )
}
