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
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.StrictMode
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.di.DatabaseModule
import app.gyrolet.mpvrx.di.FileManagerModule
import app.gyrolet.mpvrx.di.PreferencesModule
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.presentation.crash.CrashActivity
import app.gyrolet.mpvrx.presentation.crash.CrashReportStore
import app.gyrolet.mpvrx.domain.network.NetworkImageRepository
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.player.MediaPlayerWidget
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackPerformanceTrace
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.theme.AppTheme
import app.gyrolet.mpvrx.ui.theme.DarkMode
import com.developer.crashx.config.CrashConfig
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
  private val imageCacheCleanupStarted = AtomicBoolean(false)
  private var startedActivityCount = 0

  private data class WidgetAppearanceState(
    val darkMode: DarkMode,
    val appTheme: AppTheme,
    val customTheme: String,
    val selectedCustomThemeName: String,
    val amoledMode: Boolean,
  )

  companion object {
    private const val TAG = "App"
    private const val POST_START_MAINTENANCE_DELAY_MS = 10_000L
    private const val THUMBNAIL_WARMUP_DELAY_MS = 1_500L
    private const val IDLE_MPV_CORE_GRACE_MS = 3L * 60L * 1000L
  }

  override fun onCreate() {
    super.onCreate()

    val processName =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
      } else {
        getSystemService(ActivityManager::class.java).runningAppProcesses
          ?.firstOrNull { it.pid == Process.myPid() }?.processName
      }
    if (processName == "$packageName:crash" || processName == "$packageName:crashx_error") {
      startKoin {
        androidContext(this@App)
        modules(PreferencesModule)
      }
      return
    }

    CrashConfig.Builder.create()
      .enabled(true)
      .errorActivity(CrashActivity::class.java)
      .restartActivity(MainActivity::class.java)
      .backgroundMode(CrashConfig.BACKGROUND_MODE_SHOW_CUSTOM)
      .minTimeBetweenCrashesMs(5_000)
      .maxStackTraceSize(96 * 1024)
      .trackActivities(true)
      .maxActivityLogEntries(32)
      .showErrorDetails(true)
      .showReportButton(true)
      .showCloseButton(true)
      .logErrorOnRestart(false)
      .includeStackTrace(true)
      .includeBuildDate(false)
      .crashIdPrefix("MPVRX")
      .additionalReportInfo("mpvRx ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
      .apply()
    CrashReportStore.install(this)

    configureDebugStrictMode()

    // Initialize Koin
    startKoin {
      androidContext(this@App)
      modules(
        PreferencesModule,
        DatabaseModule,
        FileManagerModule,
        app.gyrolet.mpvrx.di.domainModule,
        app.gyrolet.mpvrx.di.DownloadModule,
      )
    }
    if (!BuildConfig.MPV_SUPPORTS_VULKAN) {
      getKoin().get<DecoderPreferences>().useVulkan.set(false)
    }
    registerActivityLifecycleCallbacks(this)
    PlaybackSession.addObserver(PlaybackPerformanceTrace)
    startPlaybackPerformanceTracing()
    startIdleMpvCoreReaper()
    startWidgetUpdates()

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
      runCatching {
        val preferences: PlayerPreferences = getKoin().get()
        val enableWebLinks = preferences.enableWebStreamLinkIntents.get()
        val componentName = ComponentName(this@App, "app.gyrolet.mpvrx.ui.player.WebStreamLinksActivityAlias")
        val newState =
          if (enableWebLinks) {
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
        Log.e(TAG, "Failed to initialize WebStreamLinksActivityAlias setting on launch", error)
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

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    MediaPlayerWidget.requestUpdate(this)
  }

  private fun startWidgetUpdates() {
    val appearancePreferences: AppearancePreferences = getKoin().get()
    applicationScope.launch {
      combine(
        appearancePreferences.darkMode.changes(),
        appearancePreferences.appTheme.changes(),
        appearancePreferences.customTheme.changes(),
        appearancePreferences.selectedCustomThemeName.changes(),
        appearancePreferences.amoledMode.changes(),
      ) { darkMode, appTheme, customTheme, selectedCustomThemeName, amoledMode ->
        WidgetAppearanceState(darkMode, appTheme, customTheme, selectedCustomThemeName, amoledMode)
      }.distinctUntilChanged().collect {
        MediaPlayerWidget.requestUpdate(this@App)
      }
    }
    applicationScope.launch {
      combine(PlaybackSession.state, PlaybackSession.queue) { state, queue -> state to queue }
        .distinctUntilChanged()
        .collect { MediaPlayerWidget.requestUpdate(this@App) }
    }
  }

  override fun onActivityStarted(activity: Activity) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.mark("PLAYER_ACTIVITY_STARTED")
    if (startedActivityCount++ == 0) {
      getKoin().get<app.gyrolet.mpvrx.domain.syncplay.SyncplayManager>().onAppForegrounded()
      scheduleFastThumbnailWarmupOnce()
      scheduleMetadataMaintenanceOnce()
      scheduleImageCacheCleanupOnce()
    }
  }

  override fun onActivityStopped(activity: Activity) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.mark("PLAYER_ACTIVITY_STOPPED")
    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
      pauseVideoWhenBackgroundPlaybackDisabled(activity)
      getKoin().get<app.gyrolet.mpvrx.domain.syncplay.SyncplayManager>().onAppBackgrounded()
    }
  }

  private fun pauseVideoWhenBackgroundPlaybackDisabled(activity: Activity) {
    val playerActivity = activity as? PlayerActivity ?: return
    if (playerActivity.isCurrentMediaKnownAudio()) return

    val isInPictureInPicture =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playerActivity.isInPictureInPictureMode
    if (isInPictureInPicture) return

    val videoBackgroundPlaybackEnabled = PlaybackSession.isVideoBackgroundPlaybackEnabled()
    if (videoBackgroundPlaybackEnabled) return

    val state = PlaybackSession.state.value
    if (state.currentItem == null || state.phase == PlaybackPhase.IDLE || state.phase == PlaybackPhase.UNINITIALIZED) return

    PlaybackSession.setPropertyBoolean("pause", true)
    playerActivity.abandonAudioFocus()
    Log.d(TAG, "Paused video because video background playback is disabled")
  }

  override fun onActivityPreCreated(
    activity: Activity,
    savedInstanceState: Bundle?,
  ) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.mark("PLAYER_ACTIVITY_CREATE_START")
  }

  override fun onActivityCreated(
    activity: Activity,
    savedInstanceState: Bundle?,
  ) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.mark("PLAYER_ACTIVITY_CREATE_END")
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

  override fun onActivityResumed(activity: Activity) {
    when (activity) {
      is PlayerActivity -> {
        PlaybackPerformanceTrace.mark("PLAYER_ACTIVITY_RESUMED")
        activity.window.decorView.postOnAnimation {
          PlaybackPerformanceTrace.mark("PLAYER_FIRST_FRAME")
        }
      }
      is MainActivity -> {
        PlaybackPerformanceTrace.mark("MAIN_ACTIVITY_RESUMED")
        activity.window.decorView.postOnAnimation {
          PlaybackPerformanceTrace.mark("BROWSER_FIRST_FRAME")
        }
      }
    }
  }

  override fun onActivityPrePaused(activity: Activity) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.begin("ON_PAUSE")
  }

  override fun onActivityPaused(activity: Activity) = Unit

  override fun onActivityPostPaused(activity: Activity) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.end("ON_PAUSE")
  }

  override fun onActivityPreStopped(activity: Activity) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.begin("ON_STOP")
  }

  override fun onActivityPostStopped(activity: Activity) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.end("ON_STOP")
  }

  override fun onActivitySaveInstanceState(
    activity: Activity,
    outState: Bundle,
  ) = Unit

  override fun onActivityPreDestroyed(activity: Activity) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.begin("ON_DESTROY")
  }

  override fun onActivityDestroyed(activity: Activity) = Unit

  override fun onActivityPostDestroyed(activity: Activity) {
    if (activity is PlayerActivity) PlaybackPerformanceTrace.end("ON_DESTROY")
  }

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

  private fun startPlaybackPerformanceTracing() {
    applicationScope.launch {
      var previousPhase: PlaybackPhase? = null
      var previousSurfaceAttached: Boolean? = null
      var previousGeneration = -1L
      PlaybackSession.state.collect { state ->
        if (state.phase != previousPhase) {
          PlaybackPerformanceTrace.mark("SESSION_PHASE", state.phase.name)
          previousPhase = state.phase
        }
        if (state.surfaceAttached != previousSurfaceAttached) {
          PlaybackPerformanceTrace.mark(
            if (state.surfaceAttached) "SURFACE_BOUND" else "SURFACE_UNBOUND",
            "generation=${state.generation}",
          )
          previousSurfaceAttached = state.surfaceAttached
        }
        if (state.generation != previousGeneration) {
          PlaybackPerformanceTrace.mark("SESSION_GENERATION", state.generation.toString())
          previousGeneration = state.generation
        }
      }
    }
  }

  private fun configureDebugStrictMode() {
    if (!BuildConfig.DEBUG) return

    StrictMode.setThreadPolicy(
      StrictMode.ThreadPolicy.Builder()
        .detectAll()
        .penaltyLog()
        .build(),
    )
    StrictMode.setVmPolicy(
      StrictMode.VmPolicy.Builder()
        .detectAll()
        .penaltyLog()
        .build(),
    )
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

  private fun scheduleImageCacheCleanupOnce() {
    if (!imageCacheCleanupStarted.compareAndSet(false, true)) return
    applicationScope.launch(Dispatchers.IO) {
      try {
        delay(POST_START_MAINTENANCE_DELAY_MS)
        app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia.clearLegacyCache(
          this@App,
          PlaybackSession.state.value.currentItem?.originalUri,
        )
        val imageRepository: NetworkImageRepository = getKoin().get()
        imageRepository.evictStaleImageCaches()
        Log.d(TAG, "Network image cache cleanup completed")
      } catch (cancellation: CancellationException) {
        imageCacheCleanupStarted.set(false)
        throw cancellation
      } catch (error: Exception) {
        imageCacheCleanupStarted.set(false)
        Log.w(TAG, "Network image cache cleanup failed", error)
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
