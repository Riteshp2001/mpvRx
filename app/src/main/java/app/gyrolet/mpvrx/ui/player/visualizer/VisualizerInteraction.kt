/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import kotlin.math.exp

/** A single immutable input snapshot crosses from the UI thread to the renderer. */
internal class VisualizerInteraction {
  private data class Pointer(val x: Float, val y: Float, val active: Boolean, val pressed: Boolean)

  @Volatile private var pointer = Pointer(0f, 0f, false, false)

  var x = 0f
    private set
  var y = 0f
    private set
  var strength = 0f
    private set
  val pressed: Boolean get() = pointer.pressed

  fun update(normalizedX: Float, normalizedY: Float, active: Boolean, pressed: Boolean) {
    pointer = Pointer(
      normalizedX.coerceIn(0f, 1f) * 2f - 1f,
      1f - normalizedY.coerceIn(0f, 1f) * 2f,
      active,
      active && pressed,
    )
  }

  fun release() {
    pointer = pointer.copy(active = false, pressed = false)
  }

  fun advance(deltaSeconds: Float) {
    val target = pointer
    val dt = deltaSeconds.coerceIn(0f, 0.05f)
    val follow = 1f - exp(-dt * (if (target.active) 14f else 5f))
    x += ((if (target.active) target.x else 0f) - x) * follow
    y += ((if (target.active) target.y else 0f) - y) * follow
    val targetStrength = if (target.active) (if (target.pressed) 1f else 0.65f) else 0f
    strength += (targetStrength - strength) * (1f - exp(-dt * 10f))
  }
}
