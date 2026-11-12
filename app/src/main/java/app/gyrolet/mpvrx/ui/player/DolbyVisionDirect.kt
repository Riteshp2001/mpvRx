/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.view.Surface
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import `is`.xyz.mpv.MPVLib
import java.lang.reflect.Method

/**
 * FongMi's mpv can hand Dolby Vision tracks to MediaCodec on the video SurfaceView
 * (`android-dolby-vision-output=direct`) and draw subtitles and OSD on a transparent overlay
 * Surface (`android-osd-wid`). Only FongMi's mpvlib has the overlay JNI calls, so they are
 * resolved at runtime; the other builds compile against libraries without them.
 */
internal object DolbyVisionDirect {
  private val attachMethod: Method? by lazy { lookup("attachOsdSurface", Surface::class.java) }
  private val detachMethod: Method? by lazy { lookup("detachOsdSurface") }

  val isBuildSupported: Boolean
    get() = BuildConfig.MPV_SUPPORTS_DOLBY_VISION_DIRECT && attachMethod != null && detachMethod != null

  fun isEnabled(preferences: DecoderPreferences): Boolean = isBuildSupported && preferences.dolbyVisionDirect.get()

  fun attachOsdSurface(surface: Surface) {
    attachMethod?.invoke(MPVLib, surface)
  }

  fun detachOsdSurface() {
    detachMethod?.invoke(MPVLib)
  }

  private fun lookup(
    name: String,
    vararg parameterTypes: Class<*>,
  ): Method? =
    if (!BuildConfig.MPV_SUPPORTS_DOLBY_VISION_DIRECT) {
      null
    } else {
      runCatching { MPVLib::class.java.getMethod(name, *parameterTypes) }.getOrNull()
    }
}