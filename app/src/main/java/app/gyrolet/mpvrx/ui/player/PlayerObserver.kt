/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.pm.ActivityInfo
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PlayerObserver(
  private val activity: PlayerActivity,
) : MPVLib.EventObserver,
  KoinComponent {
  private val playerPreferences: PlayerPreferences by inject()

  private fun isVideoGeometryProperty(property: String): Boolean =
    property == "video-params/aspect" ||
      property == "video-params/w" ||
      property == "video-params/h"

  private fun shouldBypassUiThread(property: String): Boolean =
    isVideoGeometryProperty(property) ||
      property == "container-fps"

  /**
   * Stretch deliberately keeps a positive video-aspect-override so the picture fills the current
   * viewport. PlayerActivity's normal "Video" orientation refresh ignores positive overrides to
   * avoid treating custom aspect ratios as source geometry, which unintentionally also excluded
   * Stretch. Read the source video geometry from MPVView instead and only restore auto-orientation
   * for the built-in Stretch mode; custom aspect ratios remain untouched.
   */
  private fun requestStretchVideoOrientationUpdate(property: String? = null) {
    if (property != null && !isVideoGeometryProperty(property)) return

    activity.runOnUiThread {
      if (activity.player.isExiting || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
      if (playerPreferences.orientation.get() != PlayerOrientation.Video) return@runOnUiThread
      if (playerPreferences.lastCustomAspectRatio.get() > 0f) return@runOnUiThread
      if (playerPreferences.lastVideoAspect.get() != VideoAspect.Stretch) return@runOnUiThread

      val sourceAspect =
        runCatching { activity.player.getVideoOutAspect() }
          .getOrNull()
          ?.takeIf { it > 0.0 }
          ?: return@runOnUiThread

      val targetOrientation =
        if (sourceAspect > 1.0) {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

      if (activity.requestedOrientation != targetOrientation) {
        activity.requestedOrientation = targetOrientation
      }
    }
  }

  override fun eventProperty(property: String) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property) }
  }

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    if (activity.player.isExiting) return
    if (shouldBypassUiThread(property)) {
      activity.onObserverEvent(property, value)
      requestStretchVideoOrientationUpdate(property)
    } else {
      activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    if (activity.player.isExiting) return
    if (shouldBypassUiThread(property)) {
      activity.onObserverEvent(property, value)
      requestStretchVideoOrientationUpdate(property)
    } else {
      activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }
  }

  @Suppress("EmptyFunctionBlock")
  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property, value) }
  }

  override fun event(
    eventId: Int,
    data: MPVNode,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread {
      activity.event(eventId)
      if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
        requestStretchVideoOrientationUpdate()
      }
    }
  }
}
