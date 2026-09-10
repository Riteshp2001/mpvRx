/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.gyrolet.mpvrx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale

/**
 * Foreground service that runs the in-app direct download queue, mirroring progress and
 * speed into its notification (same treatment as the yt-dlp download service). Stops
 * itself when the queue drains.
 */
class DirectDownloadService : Service() {
  private val downloadManager: AppDownloadManager by inject()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var drainJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    if (intent?.action == ACTION_CANCEL_ACTIVE) {
      downloadManager.cancelActive()
    }
    ensureChannel()
    startAsForeground(buildNotification(null))
    if (drainJob?.isActive != true) {
      drainJob =
        scope.launch {
          downloadManager.drainQueue { snapshot -> notify(buildNotification(snapshot)) }
          stopSelf()
        }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  override fun onTimeout(
    startId: Int,
    fgsType: Int,
  ) {
    drainJob?.cancel()
    downloadManager.cancelActive()
    stopSelf(startId)
  }

  private fun startAsForeground(notification: android.app.Notification) {
    val type =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      } else {
        0
      }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
  }

  private fun notify(notification: android.app.Notification) {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, notification)
  }

  private fun buildNotification(snapshot: AppDownloadManager.ActiveSnapshot?): android.app.Notification {
    val queued = downloadManager.queuedCount()
    val title = snapshot?.title ?: getString(R.string.downloads_notification_channel)
    val progress = snapshot?.progress ?: 0
    val text =
      if (snapshot != null) {
        buildString {
          append("$progress%")
          if (snapshot.totalBytes > 0) {
            append("  ${formatBytes(snapshot.downloadedBytes)}/${formatBytes(snapshot.totalBytes)}")
          } else if (snapshot.downloadedBytes > 0) {
            append("  ${formatBytes(snapshot.downloadedBytes)}")
          }
          if (snapshot.speedBytesPerSec > 0) append("  ${formatBytes(snapshot.speedBytesPerSec)}/s")
          if (queued > 0) append("  (+$queued)")
        }
      } else {
        getString(R.string.downloads_preparing)
      }

    val cancelIntent =
      PendingIntent.getService(
        this,
        2,
        Intent(this, DirectDownloadService::class.java).setAction(ACTION_CANCEL_ACTIVE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat
      .Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(text)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setProgress(100, progress, snapshot == null || snapshot.totalBytes <= 0)
      .addAction(0, getString(android.R.string.cancel), cancelIntent)
      .build()
  }

  private fun ensureChannel() {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.downloads_notification_channel),
          NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.downloads_notification_channel_description) },
      )
    }
  }

  private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(Locale.US, kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(Locale.US, mb)
    return "%.2f GB".format(Locale.US, mb / 1024.0)
  }

  companion object {
    private const val CHANNEL_ID = "direct_downloads"
    private const val NOTIFICATION_ID = 0x59D2
    private const val ACTION_CANCEL_ACTIVE = "app.gyrolet.mpvrx.download.CANCEL_DIRECT_ACTIVE"

    fun start(context: Context) {
      val intent = Intent(context, DirectDownloadService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }
}
