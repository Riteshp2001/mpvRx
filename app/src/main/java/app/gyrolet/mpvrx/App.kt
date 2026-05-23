package app.gyrolet.mpvrx

import android.app.Application
import android.util.Log
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.di.DatabaseModule
import app.gyrolet.mpvrx.di.FileManagerModule
import app.gyrolet.mpvrx.di.PreferencesModule
import app.gyrolet.mpvrx.presentation.crash.CrashActivity
import app.gyrolet.mpvrx.presentation.crash.GlobalExceptionHandler
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eclipse.tm4e.core.registry.IThemeSource
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.annotation.KoinExperimentalAPI

@OptIn(KoinExperimentalAPI::class)
class App : Application() {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val metadataCache: VideoMetadataCacheRepository by inject()

  companion object {
    private const val LAUNCH_SCAN_PREFS = "launch_media_scan"
    private const val LAST_LAUNCH_SCAN_MS = "last_launch_scan_ms"
    private const val LAUNCH_SCAN_INTERVAL_MS = 24L * 60L * 60L * 1000L
  }

  override fun onCreate() {
    super.onCreate()

    try {
        android.util.Log.d("App", "Starting Koin...")
        startKoin {
          androidContext(this@App)
          modules(
            PreferencesModule,
            DatabaseModule,
            FileManagerModule,
            app.gyrolet.mpvrx.di.domainModule,
            app.gyrolet.mpvrx.exoplayer.di.exoPlayerModule,
          )
        }
        android.util.Log.d("App", "Koin initialized successfully")
    } catch (e: Exception) {
        android.util.Log.e("App", "Koin initialization failed!", e)
    }

    Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))

    // Perform cache maintenance on app startup (non-blocking)
    applicationScope.launch {
      runCatching {
        metadataCache.performMaintenance()
      }
    }

    applicationScope.launch {
      initializeScriptEditorAssets()
    }

    applicationScope.launch {
      runCatching {
        triggerMediaScanOnLaunch()
      }
    }
  }

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

  private fun initializeScriptEditorAssets() {
    runCatching {
      FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
      GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

      val themeRegistry = ThemeRegistry.getInstance()
      listOf("darcula", "quietlight").forEach { themeName ->
        val path = "textmate/$themeName.json"
        themeRegistry.loadTheme(
          ThemeModel(
            IThemeSource.fromInputStream(
              FileProviderRegistry.getInstance().tryGetInputStream(path),
              path,
              null,
            ),
            themeName,
          ),
        )
      }
      themeRegistry.setTheme("darcula")
    }.onFailure { error ->
      Log.w("App", "Failed to initialize script editor assets", error)
    }
  }
}

