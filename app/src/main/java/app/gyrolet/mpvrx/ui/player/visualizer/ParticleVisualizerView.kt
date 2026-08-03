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
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.MotionEvent

internal class ParticleVisualizerView(
  context: Context,
  features: AudioFeatures,
  palette: VisualizerPalette,
  reducedMotion: Boolean = false,
) : GLSurfaceView(context), PaletteConsumer {
  private val renderer = ParticleFeedbackRenderer(
    context.applicationContext,
    features,
    palette,
    reducedMotion,
  )

  init {
    setEGLContextClientVersion(3)
    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
    holder.setFormat(PixelFormat.TRANSLUCENT)
    setZOrderOnTop(false)
    setZOrderMediaOverlay(true)
    preserveEGLContextOnPause = true
    setRenderer(renderer)
    renderMode = RENDERMODE_CONTINUOUSLY
    isClickable = true
  }

  override fun updatePalette(value: VisualizerPalette) {
    renderer.updatePalette(value)
  }

  fun setReducedMotion(reducedMotion: Boolean) {
    renderer.setReducedMotion(reducedMotion)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
      }
      MotionEvent.ACTION_UP -> {
        parent?.requestDisallowInterceptTouchEvent(false)
        performClick()
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        parent?.requestDisallowInterceptTouchEvent(false)
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }
}
