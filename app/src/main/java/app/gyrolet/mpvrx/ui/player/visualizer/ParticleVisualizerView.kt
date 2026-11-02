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

internal class ParticleVisualizerView(
  context: Context,
  features: AudioFeatures,
  palette: VisualizerPalette,
  reducedMotion: Boolean = false,
) : InteractiveVisualizerView(context), PaletteConsumer {
  private val renderer = ParticleFeedbackRenderer(
    context.applicationContext,
    features,
    palette,
    reducedMotion,
    interaction,
  )

  init {
    setRenderer(renderer)
  }

  override fun updatePalette(value: VisualizerPalette) {
    renderer.updatePalette(value)
  }

  fun setReducedMotion(reducedMotion: Boolean) {
    renderer.setReducedMotion(reducedMotion)
  }
}
