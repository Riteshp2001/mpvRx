/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

class MpvRxCastOptionsProvider : OptionsProvider {
  override fun getCastOptions(context: Context): CastOptions {
    val notificationOptions =
      NotificationOptions
        .Builder()
        .setTargetActivityClassName(CastRemoteControllerActivity::class.java.name)
        .build()
    val mediaOptions =
      CastMediaOptions
        .Builder()
        .setNotificationOptions(notificationOptions)
        .setExpandedControllerActivityClassName(CastRemoteControllerActivity::class.java.name)
        .build()

    return CastOptions
      .Builder()
      .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
      .setCastMediaOptions(mediaOptions)
      .setRemoteToLocalEnabled(true)
      .build()
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
