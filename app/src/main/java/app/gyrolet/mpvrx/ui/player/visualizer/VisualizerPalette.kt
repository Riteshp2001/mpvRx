/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

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
