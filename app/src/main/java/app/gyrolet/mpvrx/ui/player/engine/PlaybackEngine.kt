/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.engine

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import app.gyrolet.mpvrx.ui.player.DolbyVisionDirect
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode

/**
 * The playback library behind PlaybackSession, with MPVLib's API: libmpv, or Kodi in the kodi
 * distribution. Every engine takes mpv's commands, properties, events and observers.
 */
interface PlaybackEngine {
  /** Extra time the first load may take while the engine itself is still starting. */
  val startupAllowanceMs: Long get() = 0L

  fun create(context: Context)

  fun init()

  fun destroy()

  fun attachSurface(surface: Surface)

  fun detachSurface()

  /** Whether subtitles and OSD can be drawn on a transparent overlay Surface above the video. */
  val supportsOsdOverlay: Boolean

  /** Whether video goes straight to the video Surface, with subtitles and OSD on the overlay. */
  fun wantsOsdOverlay(
    dolbyVisionDirect: Boolean,
    mediaCodecEmbed: Boolean,
  ): Boolean

  fun attachOsdSurface(
    surface: Surface,
    width: Int,
    height: Int,
  )

  fun detachOsdSurface()

  fun command(vararg cmd: String)

  fun commandNode(vararg cmd: String): MPVNode?

  fun setOptionString(
    name: String,
    value: String,
  ): Int

  fun getPropertyInt(property: String): Int?

  fun setPropertyInt(
    property: String,
    value: Int,
  )

  fun getPropertyDouble(property: String): Double?

  fun setPropertyDouble(
    property: String,
    value: Double,
  )

  fun getPropertyBoolean(property: String): Boolean?

  fun setPropertyBoolean(
    property: String,
    value: Boolean,
  )

  fun getPropertyString(property: String): String?

  fun setPropertyString(
    property: String,
    value: String,
  )

  fun getPropertyNode(property: String): MPVNode?

  fun getPropertyFloat(property: String): Float? = getPropertyDouble(property)?.toFloat()

  fun setPropertyFloat(
    property: String,
    value: Float,
  ) = setPropertyDouble(property, value.toDouble())

  fun observeProperty(
    property: String,
    format: Int,
  )

  fun addObserver(observer: MPVLib.EventObserver)

  fun removeObserver(observer: MPVLib.EventObserver)

  fun addLogObserver(observer: MPVLib.LogObserver)

  fun removeLogObserver(observer: MPVLib.LogObserver)

  /** The current video frame, or null when the engine cannot capture it. */
  fun grabThumbnail(dimension: Int): Bitmap?
}

/** The engine of this distribution. All player-core calls go through it, never to MPVLib. */
object PlayerCore : PlaybackEngine by distributionPlaybackEngine

/** libmpv, through mpv-android's MPVLib. */
object MpvPlaybackEngine : PlaybackEngine {
  override fun create(context: Context) {
    MPVLib.create(context)
  }

  override fun init() {
    MPVLib.init()
  }

  override fun destroy() {
    MPVLib.destroy()
  }

  override fun attachSurface(surface: Surface) {
    MPVLib.attachSurface(surface)
  }

  override fun detachSurface() {
    MPVLib.detachSurface()
  }

  override val supportsOsdOverlay: Boolean
    get() = DolbyVisionDirect.isBuildSupported

  override fun wantsOsdOverlay(
    dolbyVisionDirect: Boolean,
    mediaCodecEmbed: Boolean,
  ): Boolean = DolbyVisionDirect.wantsOsdOverlay(dolbyVisionDirect, mediaCodecEmbed)

  override fun attachOsdSurface(
    surface: Surface,
    width: Int,
    height: Int,
  ) = DolbyVisionDirect.attachOsdSurface(surface)

  override fun detachOsdSurface() = DolbyVisionDirect.detachOsdSurface()

  override fun command(vararg cmd: String) {
    MPVLib.command(*cmd)
  }

  override fun commandNode(vararg cmd: String): MPVNode? = MPVLib.commandNode(*cmd)

  override fun setOptionString(
    name: String,
    value: String,
  ): Int = MPVLib.setOptionString(name, value)

  override fun getPropertyInt(property: String): Int? = MPVLib.getPropertyInt(property)

  override fun setPropertyInt(
    property: String,
    value: Int,
  ) {
    MPVLib.setPropertyInt(property, value)
  }

  override fun getPropertyDouble(property: String): Double? = MPVLib.getPropertyDouble(property)

  override fun setPropertyDouble(
    property: String,
    value: Double,
  ) {
    MPVLib.setPropertyDouble(property, value)
  }

  override fun getPropertyBoolean(property: String): Boolean? = MPVLib.getPropertyBoolean(property)

  override fun setPropertyBoolean(
    property: String,
    value: Boolean,
  ) {
    MPVLib.setPropertyBoolean(property, value)
  }

  override fun getPropertyString(property: String): String? = MPVLib.getPropertyString(property)

  override fun setPropertyString(
    property: String,
    value: String,
  ) {
    MPVLib.setPropertyString(property, value)
  }

  override fun getPropertyNode(property: String): MPVNode? = MPVLib.getPropertyNode(property)

  override fun observeProperty(
    property: String,
    format: Int,
  ) {
    MPVLib.observeProperty(property, format)
  }

  override fun addObserver(observer: MPVLib.EventObserver) {
    MPVLib.addObserver(observer)
  }

  override fun removeObserver(observer: MPVLib.EventObserver) {
    MPVLib.removeObserver(observer)
  }

  override fun addLogObserver(observer: MPVLib.LogObserver) {
    MPVLib.addLogObserver(observer)
  }

  override fun removeLogObserver(observer: MPVLib.LogObserver) {
    MPVLib.removeLogObserver(observer)
  }

  override fun grabThumbnail(dimension: Int): Bitmap? = MPVLib.grabThumbnail(dimension)
}
