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
  private fun shouldIgnoreCallback(): Boolean =
    activity.isDestroyed || activity.player.isExiting || !activity.isActivePlaybackOwner()

  private fun dispatchToUi(action: () -> Unit) {
    val generation = PlaybackSession.state.value.generation
    activity.runOnUiThread {
      if (!shouldIgnoreCallback() && PlaybackSession.state.value.generation == generation) action()
    }
  }

  private fun isVideoGeometryProperty(property: String): Boolean =
    property == "video-params/aspect" ||
      property == "video-params/w" ||
      property == "video-params/h"

  private fun shouldBypassUiThread(property: String): Boolean =
    isVideoGeometryProperty(property) ||
      property == "container-fps"

  override fun eventProperty(property: String) {
    if (shouldIgnoreCallback()) return
    dispatchToUi { activity.onObserverEvent(property) }
  }

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    if (shouldIgnoreCallback()) return
    if (shouldBypassUiThread(property)) {
      activity.runIfActivePlaybackOwner { activity.onObserverEvent(property, value) }
    } else {
      dispatchToUi { activity.onObserverEvent(property, value) }
    }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    if (shouldIgnoreCallback()) return
    // keep-open holds the last frame instead of emitting a natural END_FILE, so the raw
    // eof-reached edge is the only end-of-playback signal; PlayerActivity validates it by
    // position before advancing (a network stall can flip it mid-file).
    dispatchToUi { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    if (shouldIgnoreCallback()) return
    dispatchToUi { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    if (shouldIgnoreCallback()) return
    if (shouldBypassUiThread(property)) {
      activity.runIfActivePlaybackOwner { activity.onObserverEvent(property, value) }
    } else {
      dispatchToUi { activity.onObserverEvent(property, value) }
    }
  }

  @Suppress("EmptyFunctionBlock")
  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {
    if (shouldIgnoreCallback()) return
    dispatchToUi { activity.onObserverEvent(property, value) }
  }

  override fun event(
    eventId: Int,
    data: MPVNode,
  ) {
    if (shouldIgnoreCallback()) return
    val naturalEnd = eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE && PlaybackSession.isNaturalEndFile(data)
    dispatchToUi {
      activity.event(eventId)
      if (naturalEnd) {
        activity.onObserverEvent("eof-reached", true)
      }
    }
  }
}
