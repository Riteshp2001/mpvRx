/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.content.Context

internal class BlobVisualizerView(
  context: Context,
  features: AudioFeatures,
  palette: VisualizerPalette,
  reducedMotion: Boolean = false,
) : InteractiveVisualizerView(context, enablePinch = true), PaletteConsumer {
  private val blobRenderer =
    BlobRenderer(context.applicationContext, features, palette, reducedMotion, interaction)

  init {
    setRenderer(blobRenderer)
  }

  override fun onPointerDrag(normalizedDx: Float, normalizedDy: Float) {
    blobRenderer.addTouchRotation(normalizedDx, normalizedDy)
  }

  override fun onPinch(scaleFactor: Float) {
    blobRenderer.setPinchScale(blobRenderer.getPinchScale() * scaleFactor)
  }

  override fun updatePalette(value: VisualizerPalette) {
    blobRenderer.updatePalette(value)
  }

  fun setReducedMotion(value: Boolean) {
    blobRenderer.setReducedMotion(value)
  }
}
