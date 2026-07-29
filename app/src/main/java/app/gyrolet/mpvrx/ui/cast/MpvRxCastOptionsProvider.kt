/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
