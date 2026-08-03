/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.os.Build
import android.util.Log

/** Process-level compatibility switches required by the currently bundled libmpv. */
internal object AndroidNativeCompat {
  private const val TAG = "MpvAndroidCompat"
  private const val ANDROID_16_API = 36

  @Volatile
  private var mpvSubprocessWorkaroundApplied = false

  /**
   * Applies the Android 16 fdsan workaround before mpv can start yt-dlp or a script subprocess.
   * fdsan must stay disabled after the first raw clone because the child shares and mutates the
   * parent's ownership metadata; restoring the fatal level would only turn this into a later crash.
   */
  fun applyMpvSubprocessWorkaround() {
    if (Build.VERSION.SDK_INT < ANDROID_16_API || mpvSubprocessWorkaroundApplied) return

    synchronized(this) {
      if (mpvSubprocessWorkaroundApplied) return

      runCatching {
        System.loadLibrary("android_compat")
      }.onSuccess {
        mpvSubprocessWorkaroundApplied = true
        Log.w(TAG, "Applied Android 16 libmpv subprocess compatibility workaround")
      }.onFailure { error ->
        Log.e(TAG, "Failed to apply Android 16 libmpv subprocess workaround", error)
      }
    }
  }
}
