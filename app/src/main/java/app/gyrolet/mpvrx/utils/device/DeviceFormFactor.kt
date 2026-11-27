/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.device

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceFormFactor {
  @Volatile private var cachedTelevisionFeature: Boolean? = null

  @Volatile private var cachedPictureInPictureFeature: Boolean? = null

  // TV PiP is reserved for communication, smart-home, health and ticker use cases,
  // not media playback. Ignore a restored phone PiP preference on a television.
  fun supportsPlaybackPictureInPicture(context: Context): Boolean {
    if (isTelevision(context)) return false
    return cachedPictureInPictureFeature ?: synchronized(this) {
      cachedPictureInPictureFeature ?: context.packageManager
        .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        .also { cachedPictureInPictureFeature = it }
    }
  }

  fun isTelevision(context: Context): Boolean {
    // Configuration.uiMode carries the same mode type as UiModeManager.currentModeType, but as a
    // local field read. The manager call is a binder IPC, and this runs while composing every list
    // card and on every player seekbar update.
    val modeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    if (modeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

    cachedTelevisionFeature?.let { return it }
    return synchronized(this) {
      cachedTelevisionFeature ?: context.packageManager.let { packageManager ->
        (
          packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY)
        ).also { cachedTelevisionFeature = it }
      }
    }
  }
}
