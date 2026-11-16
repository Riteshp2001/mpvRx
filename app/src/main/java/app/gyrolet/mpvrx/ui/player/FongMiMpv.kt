/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.BuildConfig
import `is`.xyz.mpv.MPVLib
import java.lang.reflect.Method

/**
 * Options, properties and commands that only FongMi's mpv provides. The other builds link mpvlib
 * variants without them, so every caller checks [isBuildSupported], and the extra JNI call is
 * looked up at runtime instead of being compiled against.
 */
internal object FongMiMpv {
  val isBuildSupported: Boolean
    get() = BuildConfig.MPV_SUPPORTS_FONGMI_EXTENSIONS

  const val MEDIA_LIVE = "media-live"
  const val AUDIO_PASSTHROUGH_FAILED = "audio-passthrough-failed"

  private val byteArrayGetter: Method? by lazy {
    if (!isBuildSupported) {
      null
    } else {
      runCatching { MPVLib::class.java.getMethod("getPropertyByteArray", String::class.java) }.getOrNull()
    }
  }

  /** Bytes of a byte-array property such as `albumart-data`. Callers hold the native lock. */
  fun getPropertyByteArray(property: String): ByteArray? =
    byteArrayGetter?.let { method -> runCatching { method.invoke(MPVLib, property) as? ByteArray }.getOrNull() }
}
