package app.gyrolet.mpvrx

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.util.Log
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.di.DatabaseModule
import app.gyrolet.mpvrx.di.FileManagerModule
import app.gyrolet.mpvrx.di.PreferencesModule
import app.gyrolet.mpvrx.presentation.crash.CrashActivity
import app.gyrolet.mpvrx.presentation.crash.GlobalExceptionHandler
import app.gyrolet.mpvrx.ui.player.AndroidNativeCompat
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.annotation.KoinExperimentalAPI
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import android.content.ComponentName
import android.content.pm.PackageManager
import org.koin.core.context.GlobalContext
import `is`.xyz.mpv.FastThumbnails

@OptIn(KoinExperimentalAPI::class)
class App : Application(), Application.ActivityLifecycleCallbacks {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var startedActivityCount = 0

  companion object {
    private const val LAUNCH_SCAN_PREFS = "launch_media_scan"
    private const val LAST_LAUNCH_SCAN_MS = "last_launch_scan_ms"
    private const val LAUNCH_SCAN_INTERVAL_MS = 24L * 60L * 60L * 1000L
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

    // Native libmpv thumbnail engine used by browser, folder, playlist, and network previews.
    FastThumbnails.initialize(this)

    applicationScope.launch {
      runCatching {
        val preferences: PlayerPreferences = getKoin().get()
        val enableMediaInfo = preferences.enableMediaInfoIntent.get()
        val componentName = ComponentName(this@App, "app.gyrolet.mpvrx.ui.mediainfo.MediaInfoActivityAlias")
        val newState = if (enableMediaInfo) {
          PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
          componentName,
          newState,
          PackageManager.DONT_KILL_APP
        )
      }.onFailure { error ->
        Log.e("App", "Failed to initialize MediaInfoActivityAlias setting on launch", error)
      }
    }

    // Perform cache maintenance on app startup (non-blocking).
    // Resolve the repository lazily inside the coroutine so Room DB construction
    // (which registers 8 migrations and opens the SQLite file) happens on the
    // background dispatcher instead of contending with first-frame work on the
    // main thread. See issue 1.1 in the startup audit.
    applicationScope.launch {
      runCatching {
        val metadataCache: VideoMetadataCacheRepository = getKoin().get()
        metadataCache.performMaintenance()
      }
    }

    // Note: TextMate grammar/theme assets for the script editor are no longer
    // pre-loaded here. They are initialized lazily on first use by
    // ScriptEditorTextMate.ensureInitialized() in MpvScriptEditor.kt, which
    // already has a thread-safe double-checked init. Loading them on every
    // cold start wasted I/O + JSON parse time for a feature most users never
    // open. See issue 1.2 in the startup audit.

    applicationScope.launch {
      runCatching {
        triggerMediaScanOnLaunch()
      }
    }
  }

  override fun onActivityStarted(activity: Activity) {
    if (startedActivityCount++ == 0) {
      getKoin().get<app.gyrolet.mpvrx.domain.syncplay.SyncplayManager>().onAppForegrounded()
    }
  }

  override fun onActivityStopped(activity: Activity) {
    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
      getKoin().get<app.gyrolet.mpvrx.domain.syncplay.SyncplayManager>().onAppBackgrounded()
    }
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
  override fun onActivityResumed(activity: Activity) = Unit
  override fun onActivityPaused(activity: Activity) = Unit
  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
  override fun onActivityDestroyed(activity: Activity) = Unit

  /**
   * Resolves [org.koin.core.Koin] from the global context. Safe to call only
   * after [startKoin] has completed (which it has, synchronously, at the top
   * of [onCreate]).
   */
  private fun getKoin() = GlobalContext.get()

  private fun triggerMediaScanOnLaunch() {
    try {
      if (!shouldRunLaunchMediaScan()) {
        android.util.Log.d("App", "Skipped launch media scan; last scan was recent")
        return
      }

      val externalStorage = android.os.Environment.getExternalStorageDirectory()

      android.media.MediaScannerConnection.scanFile(
        this,
        arrayOf(externalStorage.absolutePath),
        null,
      ) { path, _ ->
        android.util.Log.d("App", "Launch media scan completed for: $path")
        MediaLibraryEvents.notifyChanged()
      }

      android.util.Log.d("App", "Triggered media scan on app launch")
    } catch (error: Exception) {
      android.util.Log.e("App", "Failed to trigger media scan on launch", error)
    }
  }

  private fun shouldRunLaunchMediaScan(): Boolean {
    val now = System.currentTimeMillis()
    val prefs = getSharedPreferences(LAUNCH_SCAN_PREFS, MODE_PRIVATE)
    val lastScan = prefs.getLong(LAST_LAUNCH_SCAN_MS, 0L)
    if (now - lastScan < LAUNCH_SCAN_INTERVAL_MS) {
      return false
    }
    prefs.edit().putLong(LAST_LAUNCH_SCAN_MS, now).apply()
    return true
  }

}

