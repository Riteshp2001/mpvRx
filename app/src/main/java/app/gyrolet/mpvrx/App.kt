/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.di.DatabaseModule
import app.gyrolet.mpvrx.di.FileManagerModule
import app.gyrolet.mpvrx.di.PreferencesModule
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.presentation.crash.CrashActivity
import app.gyrolet.mpvrx.presentation.crash.GlobalExceptionHandler
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.player.AndroidNativeCompat
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(KoinExperimentalAPI::class)
class App :
  Application(),
  Application.ActivityLifecycleCallbacks {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val networkAutoConnectStarted = AtomicBoolean(false)
  private val metadataMaintenanceStarted = AtomicBoolean(false)
  private val fastThumbnailsStarted = AtomicBoolean(false)
  private var startedActivityCount = 0

  companion object {
    private const val TAG = "App"
    private const val POST_START_MAINTENANCE_DELAY_MS = 10_000L
    private const val THUMBNAIL_WARMUP_DELAY_MS = 1_500L
    private const val IDLE_MPV_CORE_GRACE_MS = 3L * 60L * 1000L
  }

  override fun onCreate() {
    super.onCreate()

    // Apply this before app-owned worker threads and either native MPV entry point start. Bionic's
    // fdsan level setter is intended for single-threaded setup, and the bundled libmpv's raw-clone
    // subprocess path otherwise corrupts its ownership bookkeeping on Android 16.
    AndroidNativeCompat.applyMpvSubprocessWorkaround()

    // Initialize Koin
    startKoin {
      androidContext(this@App)
      modules(
        PreferencesModule,
        DatabaseModule,
        FileManagerModule,
        app.gyrolet.mpvrx.di.domainModule,
      )
    }
    registerActivityLifecycleCallbacks(this)

    Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))
    startIdleMpvCoreReaper()

    applicationScope.launch {
      runCatching {
        val preferences: PlayerPreferences = getKoin().get()
        val enableMediaInfo = preferences.enableMediaInfoIntent.get()
        val componentName = ComponentName(this@App, "app.gyrolet.mpvrx.ui.mediainfo.MediaInfoActivityAlias")
        val newState =
          if (enableMediaInfo) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
          } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
          }
        packageManager.setComponentEnabledSetting(
          componentName,
          newState,
          PackageManager.DONT_KILL_APP,
        )
      }.onFailure { error ->
        Log.e(TAG, "Failed to initialize MediaInfoActivityAlias setting on launch", error)
      }
    }

    // TextMate grammar/theme assets for the script editor are initialized lazily on first use.
    // Metadata cache maintenance and native thumbnail startup are intentionally kept out of the
    // Application cold-start path so they cannot compete with first composition / first frame.

    // MediaStore is Android's source of truth for the normal library. Do not trigger a recursive
    // scan of the entire external-storage root from process startup: on large libraries that can
    // wake storage for minutes and duplicate work the platform already performs when media changes.
    // Explicit library refreshes and normal MediaStore notifications still invalidate app caches.
  }

  override fun onActivityStarted(activity: Activity) {
    if (startedActivityCount++ == 0) {
      getKoin().get<app.gyrolet.mpvrx.domain.syncplay.SyncplayManager>().onAppForegrounded()
      scheduleFastThumbnailWarmupOnce()
      scheduleMetadataMaintenanceOnce()
    }
  }

  override fun onActivityStopped(activity: Activity) {
    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
      getKoin().get<app.gyrolet.mpvrx.domain.syncplay.SyncplayManager>().onAppBackgrounded()
    }
  }

  override fun onActivityCreated(
    activity: Activity,
    savedInstanceState: Bundle?,
  ) {
    if (activity.javaClass.name.contains("leakcanary", ignoreCase = true)) {
      val rootView = activity.findViewById<View>(android.R.id.content)
      rootView?.let { view ->
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
          val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
          val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
          v.setPadding(
            v.paddingLeft,
            statusBarInsets.top,
            v.paddingRight,
            navBarInsets.bottom,
          )
          insets
        }
      }
    }
  }

  override fun onActivityResumed(activity: Activity) = Unit

  override fun onActivityPaused(activity: Activity) = Unit

  override fun onActivitySaveInstanceState(
    activity: Activity,
    outState: Bundle,
  ) = Unit

  override fun onActivityDestroyed(activity: Activity) = Unit

  /**
   * Keep libmpv warm for quick navigation/re-entry, but do not pin its native decoder/renderer
   * allocation forever after playback has genuinely ended. collectLatest makes this self-cancelling:
   * any new load, surface attachment, background session, or other state change aborts the grace
   * timer before destruction can run.
   */
  private fun startIdleMpvCoreReaper() {
    applicationScope.launch {
      PlaybackSession.state.collectLatest { state ->
        val isFullyIdle =
          state.phase == PlaybackPhase.IDLE &&
            state.currentItem == null &&
            !state.surfaceAttached &&
            PlaybackSession.isInitialized
        if (!isFullyIdle) return@collectLatest

        delay(IDLE_MPV_CORE_GRACE_MS)

        val latest = PlaybackSession.state.value
        val stillFullyIdle =
          latest.phase == PlaybackPhase.IDLE &&
            latest.currentItem == null &&
            !latest.surfaceAttached &&
            PlaybackSession.isInitialized
        if (stillFullyIdle) {
          Log.d(TAG, "Destroying libmpv after idle grace period")
          PlaybackSession.destroy()
        }
      }
    }
  }

  private fun scheduleFastThumbnailWarmupOnce() {
    if (!fastThumbnailsStarted.compareAndSet(false, true)) return
    applicationScope.launch(Dispatchers.Default) {
      try {
        delay(THUMBNAIL_WARMUP_DELAY_MS)
        FastThumbnails.initialize(this@App)
      } catch (cancellation: CancellationException) {
        fastThumbnailsStarted.set(false)
        throw cancellation
      } catch (error: Exception) {
        fastThumbnailsStarted.set(false)
        Log.w(TAG, "Deferred FastThumbnails initialization failed", error)
      }
    }
  }

  private fun scheduleMetadataMaintenanceOnce() {
    if (!metadataMaintenanceStarted.compareAndSet(false, true)) return
    applicationScope.launch(Dispatchers.IO) {
      try {
        delay(POST_START_MAINTENANCE_DELAY_MS)
        val metadataCache: VideoMetadataCacheRepository = getKoin().get()
        metadataCache.performMaintenance()
      } catch (cancellation: CancellationException) {
        metadataMaintenanceStarted.set(false)
        throw cancellation
      } catch (error: Exception) {
        metadataMaintenanceStarted.set(false)
        Log.w(TAG, "Deferred metadata maintenance failed", error)
      }
    }
  }

  /** Starts saved-share auto-connect in process scope so Activity recreation cannot cancel it. */
  internal fun autoConnectNetworksOnce() {
    if (!networkAutoConnectStarted.compareAndSet(false, true)) return

    applicationScope.launch {
      try {
        delay(500)
        val repository = getKoin().get<NetworkRepository>()
        repository.getAutoConnectConnections().forEach { connection ->
          Log.d(TAG, "Auto-connecting to network share: ${connection.name}")
          repository
            .connect(connection)
            .onFailure { error ->
              Log.e(TAG, "Auto-connect failed for ${connection.name}: ${error.message}")
            }
        }
      } catch (cancellation: CancellationException) {
        networkAutoConnectStarted.set(false)
        throw cancellation
      } catch (error: Exception) {
        networkAutoConnectStarted.set(false)
        Log.e(TAG, "Failed to auto-connect saved network shares", error)
      }
    }
  }

  /**
   * Resolves [org.koin.core.Koin] from the global context. Safe to call only
   * after [startKoin] has completed (which it has, synchronously, at the top
   * of [onCreate]).
   */
  private fun getKoin() = GlobalContext.get()
}
