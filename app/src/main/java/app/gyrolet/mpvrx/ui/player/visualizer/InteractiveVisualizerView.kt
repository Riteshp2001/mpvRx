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
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration

/** Touch and mouse/stylus hover use the same coordinates over the whole visualizer viewport. */
internal abstract class InteractiveVisualizerView(
  context: Context,
  enablePinch: Boolean = false,
) : VisualizerTextureView(context) {
  protected val interaction = VisualizerInteraction()
  private var activePointerId = MotionEvent.INVALID_POINTER_ID
  private var previousX = 0f
  private var previousY = 0f
  private var downX = 0f
  private var downY = 0f
  private var moved = false
  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
  private val scaleDetector = if (enablePinch) {
    ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScale(detector: ScaleGestureDetector): Boolean {
        onPinch(detector.scaleFactor)
        return true
      }
    })
  } else {
    null
  }

  init {
    isClickable = true
  }

  protected open fun onPointerDrag(normalizedDx: Float, normalizedDy: Float) = Unit

  protected open fun onPinch(scaleFactor: Float) = Unit

  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector?.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        activePointerId = event.getPointerId(0)
        downX = event.x
        downY = event.y
        moved = false
        updatePointer(event, 0)
        parent?.requestDisallowInterceptTouchEvent(true)
      }
      MotionEvent.ACTION_POINTER_DOWN -> moved = true
      MotionEvent.ACTION_MOVE -> {
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) {
          releaseInteraction()
          return true
        }
        val x = event.getX(index)
        val y = event.getY(index)
        if ((x - downX) * (x - downX) + (y - downY) * (y - downY) > touchSlop * touchSlop) moved = true
        if (event.pointerCount == 1 && scaleDetector?.isInProgress != true) {
          onPointerDrag((x - previousX) / width.coerceAtLeast(1), (y - previousY) / height.coerceAtLeast(1))
        }
        updatePointer(event, index)
      }
      MotionEvent.ACTION_POINTER_UP -> {
        // Rebase even when a secondary finger lifts, so ending a pinch cannot jump the rotation.
        val index = (0 until event.pointerCount).firstOrNull {
          it != event.actionIndex && event.getPointerId(it) == activePointerId
        } ?: (0 until event.pointerCount).firstOrNull { it != event.actionIndex }
        if (index != null) {
          activePointerId = event.getPointerId(index)
          updatePointer(event, index)
        }
      }
      MotionEvent.ACTION_UP -> {
        releaseInteraction()
        if (!moved) performClick()
      }
      MotionEvent.ACTION_CANCEL -> releaseInteraction()
      else -> return super.onTouchEvent(event)
    }
    return true
  }

  private fun updatePointer(event: MotionEvent, index: Int) {
    previousX = event.getX(index)
    previousY = event.getY(index)
    interaction.update(
      previousX / width.coerceAtLeast(1),
      previousY / height.coerceAtLeast(1),
      active = true,
      pressed = true,
    )
  }

  override fun onHoverEvent(event: MotionEvent): Boolean {
    val handled = when (event.actionMasked) {
      MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
          interaction.update(event.x / width.coerceAtLeast(1), event.y / height.coerceAtLeast(1), true, false)
        }
        true
      }
      MotionEvent.ACTION_HOVER_EXIT -> {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) interaction.release()
        true
      }
      else -> false
    }
    return super.onHoverEvent(event) || handled
  }

  private fun releaseInteraction() {
    interaction.release()
    activePointerId = MotionEvent.INVALID_POINTER_ID
    parent?.requestDisallowInterceptTouchEvent(false)
  }

  override fun onVisibilityChanged(changedView: View, visibility: Int) {
    super.onVisibilityChanged(changedView, visibility)
    if (visibility != VISIBLE) releaseInteraction()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    if (visibility != VISIBLE) releaseInteraction()
  }

  override fun onDetachedFromWindow() {
    releaseInteraction()
    super.onDetachedFromWindow()
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }
}
