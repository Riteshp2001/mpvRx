package app.gyrolet.mpvrx.ui.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

class mpvRxCastOptionsProvider : OptionsProvider {
  override fun getCastOptions(context: Context): CastOptions {
    val notificationOptions =
      NotificationOptions.Builder()
        .setTargetActivityClassName(CastExpandedControlsActivity::class.java.name)
        .build()
    val mediaOptions =
      CastMediaOptions.Builder()
        .setNotificationOptions(notificationOptions)
        .setExpandedControllerActivityClassName(CastExpandedControlsActivity::class.java.name)
        .build()

    return CastOptions.Builder()
      .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
      .setCastMediaOptions(mediaOptions)
      .setRemoteToLocalEnabled(true)
      .build()
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
