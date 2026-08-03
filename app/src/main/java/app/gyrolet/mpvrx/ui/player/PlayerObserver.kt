/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode

class PlayerObserver(
  private val activity: PlayerActivity,
) : MPVLib.EventObserver {
  private fun shouldBypassUiThread(property: String): Boolean =
    property == "video-params/aspect" ||
      property == "video-params/w" ||
      property == "video-params/h"

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
    activity.runOnUiThread { activity.event(eventId) }
  }
}
