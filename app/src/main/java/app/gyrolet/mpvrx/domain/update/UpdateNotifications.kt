// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.domain.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import app.gyrolet.mpvrx.R
import java.util.UUID

object UpdateNotifications {
  private const val CHANNEL = "app_updates"
  private const val PROGRESS_ID = 6301
  private const val STATUS_ID = 6302

  private fun builder(context: Context): NotificationCompat.Builder {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(
          CHANNEL,
          context.getString(R.string.pref_section_updates),
          NotificationManager.IMPORTANCE_LOW,
        ),
      )
    }
    val launch =
      context.packageManager
        .getLaunchIntentForPackage(context.packageName)
        ?.putExtra("open_update_sheet", true)
        ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return NotificationCompat
      .Builder(context, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle(context.getString(R.string.app_name))
      .setContentIntent(
        launch?.let {
          PendingIntent.getActivity(
            context,
            STATUS_ID,
            it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
        },
      ).setOnlyAlertOnce(true)
  }

  private fun download(
    context: Context,
    id: UUID,
    progress: Float,
  ) = builder(context)
    .setSmallIcon(android.R.drawable.stat_sys_download)
    .setContentText(context.getString(R.string.update_background_downloading))
    .setProgress(100, progress.toInt().coerceIn(0, 100), progress < 0)
    .setOngoing(true)
    .addAction(
      android.R.drawable.ic_menu_close_clear_cancel,
      context.getString(R.string.generic_cancel),
      WorkManager.getInstance(context).createCancelPendingIntent(id),
    ).build()

  fun foreground(
    context: Context,
    id: UUID,
    progress: Float,
  ): ForegroundInfo {
    val notification = download(context, id, progress)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(PROGRESS_ID, notification)
    }
  }

  fun progress(
    context: Context,
    id: UUID,
    progress: Float,
  ) {
    notify(context, PROGRESS_ID, download(context, id, progress))
  }

  fun status(
    context: Context,
    stage: UpdateStage,
    confirmation: Intent? = null,
  ) {
    val text =
      when (stage) {
        UpdateStage.INSTALLING -> R.string.update_installing
        UpdateStage.APPROVAL -> R.string.update_approval_required
        UpdateStage.PERMISSION -> R.string.update_install_permission
        UpdateStage.COMPLETE -> R.string.update_install_complete
        UpdateStage.FAILED ->
          if (UpdateWork
              .state(
                context,
              ).value.downloadError
          ) {
            R.string.update_download_failed
          } else {
            R.string.update_install_failed
          }
        else -> R.string.update_ready_to_install
      }
    val builder =
      builder(context)
        .setContentText(context.getString(text))
        .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
        .setAutoCancel(true)
    if (confirmation != null) {
      builder.setContentIntent(
        PendingIntent.getActivity(
          context,
          STATUS_ID,
          Intent(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
      )
    }
    if (stage == UpdateStage.COMPLETE) {
      context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        builder.setContentIntent(
          PendingIntent.getActivity(
            context,
            STATUS_ID + 1,
            it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          ),
        )
      }
    }
    notify(context, STATUS_ID, builder.build())
  }

  private fun notify(
    context: Context,
    id: Int,
    notification: android.app.Notification,
  ) {
    // Notification denial must not lose the durable state; the update sheet remains available.
    runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
  }

  fun cancelProgress(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(PROGRESS_ID)
  }
}
