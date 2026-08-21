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

internal class BlobVisualizerView(
  context: Context,
  features: AudioFeatures,
  palette: VisualizerPalette,
  reducedMotion: Boolean = false,
) : GLSurfaceView(context),
  PaletteConsumer {
  private val blobRenderer =
    BlobRenderer(context.applicationContext, features, palette, reducedMotion)

  private var previousX = 0f
  private var previousY = 0f

  private var pinchStartDist = 0f
  private var pinchStartScale = 1f
  private var previousPointerCount = 0

  init {
    setEGLContextClientVersion(3)
    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
    holder.setFormat(PixelFormat.TRANSLUCENT)
    setZOrderOnTop(true)
    preserveEGLContextOnPause = true
    setRenderer(blobRenderer)
    renderMode = RENDERMODE_CONTINUOUSLY
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val pointerCount = event.pointerCount
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        previousX = event.x
        previousY = event.y
        pinchStartDist = 0f
        previousPointerCount = 1
        return true
      }
      MotionEvent.ACTION_POINTER_DOWN -> {
        pinchStartDist = spacing(event)
        pinchStartScale = pinchScaleFromRenderer()
        previousPointerCount = 2
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (pointerCount >= 2 && previousPointerCount >= 2) {
          val dist = spacing(event)
          if (pinchStartDist > 0f && dist > 0f) {
            val scale = pinchStartScale * (dist / pinchStartDist)
            blobRenderer.setPinchScale(scale)
          }
        } else if (pointerCount == 1) {
          val dx = event.x - previousX
          val dy = event.y - previousY
          previousX = event.x
          previousY = event.y
          blobRenderer.addTouchRotation(dx / width.coerceAtLeast(1), dy / height.coerceAtLeast(1))
        }
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
        previousPointerCount = if (pointerCount <= 1) 1 else pointerCount - 1
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  override fun updatePalette(value: VisualizerPalette) {
    blobRenderer.updatePalette(value)
  }

  fun setReducedMotion(value: Boolean) {
    blobRenderer.setReducedMotion(value)
  }

  private fun spacing(event: MotionEvent): Float {
    if (event.pointerCount < 2) return 0f
    val dx = event.getX(1) - event.getX(0)
    val dy = event.getY(1) - event.getY(0)
    return kotlin.math.sqrt(dx * dx + dy * dy)
  }

  private fun pinchScaleFromRenderer(): Float = blobRenderer.getPinchScale()
}
