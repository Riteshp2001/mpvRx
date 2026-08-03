/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast receiver that listens for media scanner events
 * Automatically notifies the app when new media files are added to the device
 */
class MediaScanReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "MediaScanReceiver"
  }

  override fun onReceive(
    context: Context?,
    intent: Intent?,
  ) {
    if (context == null || intent == null) return

    when (intent.action) {
      Intent.ACTION_MEDIA_SCANNER_FINISHED -> {
        val data = intent.data
        Log.d(TAG, "Media scan event: ${intent.action}, data: $data")

        // Notify the app that media library has changed
        MediaLibraryEvents.notifyChanged()
      }
    }
  }
}
