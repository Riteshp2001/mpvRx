package app.gyrolet.mpvrx

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import app.gyrolet.mpvrx.ui.theme.AppMotion
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.utils.update.UpdateDialog
import app.gyrolet.mpvrx.utils.update.UpdateViewModel
import app.gyrolet.mpvrx.repository.NetworkLifecycleObserver
import app.gyrolet.mpvrx.ui.browser.MainScreen
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.ui.theme.rememberThemeTransitionState
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

private fun screenNavTransition(
  forward: Boolean,
  style: NavigationAnimStyle,
  speed: Float = 1f,
): ContentTransform {
  val dir = if (forward) 1 else -1

  return when (style) {
    NavigationAnimStyle.None ->
      EnterTransition.None togetherWith ExitTransition.None

    NavigationAnimStyle.Minimal ->
      fadeIn(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness)) togetherWith fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness))

    NavigationAnimStyle.FlipFade ->
      (scaleIn(spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness), initialScale = 0.94f) + fadeIn(spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness))) togetherWith
        (scaleOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness), targetScale = 1.06f) + fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness)))

    NavigationAnimStyle.Depth ->
      (slideInHorizontally(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness)) { it * dir } +
        fadeIn(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness))) togetherWith
        (slideOutHorizontally(spring(stiffness = AppMotion.Spatial.Standard.stiffness)) { (-it * 0.25f * dir).toInt() } +
          scaleOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness), targetScale = 0.92f) +
          fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness)))

    NavigationAnimStyle.Elastic ->
      (slideInHorizontally(
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f),
      ) { it * dir } + fadeIn(spring(stiffness = AppMotion.Spatial.Snappy.stiffness))) togetherWith
        (slideOutHorizontally(spring(stiffness = AppMotion.Spatial.Standard.stiffness)) { (-it / 3 * dir) } + fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness)))

    NavigationAnimStyle.Default ->
      if (forward) {
        slideInHorizontally(
          spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness),
        ) { it } togetherWith
          slideOutHorizontally(
            spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness),
          ) { -it / 8 }
      } else {
        slideInHorizontally(
          spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness),
        ) { -it / 5 } togetherWith
          slideOutHorizontally(
            spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness),
          ) { it }
      }
  }
}

/**
 * Main entry point for the application
 */
class MainActivity : ComponentActivity() {
  private val appearancePreferences by inject<AppearancePreferences>()
  private val playerPreferences by inject<PlayerPreferences>()
  private val networkRepository by inject<NetworkRepository>()
  private var appliedEdgeToEdgeDarkMode: Boolean? = null

  /**
   * Per-process flag that ensures auto-connect only runs once per cold start,
   * even if MainActivity is recreated (config change, process death + restore,
   * etc.). See issue 1.6 in the startup audit.
   */
  @Volatile
  private var autoConnectTriggered: Boolean = false

  // Create a coroutine scope tied to the activity lifecycle
  private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  // Register the ActivityResultLauncher at class level
  private val mediaAccessLauncher = registerForActivityResult(
    ActivityResultContracts.StartIntentSenderForResult()
  ) { result ->
    PermissionUtils.handleMediaAccessResult(result.resultCode)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    PermissionUtils.setMediaAccessLauncher(mediaAccessLauncher)

    val networkStreamingEnabled = appearancePreferences.showNetworkTab.get()
    if (networkStreamingEnabled) {
      lifecycle.addObserver(app.gyrolet.mpvrx.ui.browser.networkstreaming.proxy.ProxyLifecycleObserver())
    }
    lifecycle.addObserver(NetworkLifecycleObserver(networkRepository))

    applyEdgeToEdge(
      isDarkMode = resolveIsDarkMode(
        darkMode = appearancePreferences.darkMode.get(),
        isSystemInDarkTheme = isSystemInDarkThemeFromResources(),
      ),
    )

    setContent {
      // Set up theme and edge-to-edge display
      val dark by appearancePreferences.darkMode.collectAsState()
      val isSystemInDarkTheme = isSystemInDarkTheme()
      val isDarkMode = remember(dark, isSystemInDarkTheme) {
        dark == DarkMode.Dark || (dark == DarkMode.System && isSystemInDarkTheme)
      }
      val themeTransitionState = rememberThemeTransitionState()

      LaunchedEffect(isDarkMode) {
        if (themeTransitionState.isAnimating) {
          snapshotFlow {
            themeTransitionState.animationProgress.value to themeTransitionState.isAnimating
          }.first { (progress, isAnimating) ->
            !isAnimating || progress >= SYSTEM_BAR_THEME_SWITCH_PROGRESS
          }
        }
        applyEdgeToEdge(isDarkMode)
      }

      // Auto-connect to saved network connections.
      // Gated behind both the user setting and a per-process flag so we only
      // run SMB/FTP/WebDAV handshakes once per cold start, and only after the
      // first frame has drawn (post-delay(500)). Previously this fired on every
      // MainActivity recreation (config change, process restart, etc.) and
      // re-handshaked every auto-connect entry, wasting battery and bandwidth
      // even if the user never opened the Network tab.
      // See issue 1.6 in the startup audit.
      LaunchedEffect(networkStreamingEnabled) {
        if (networkStreamingEnabled && !autoConnectTriggered) {
          autoConnectTriggered = true
          autoConnectToNetworks()
        }
      }

      MpvrxTheme(transitionState = themeTransitionState) {
        Surface(modifier = Modifier.fillMaxSize()) {
          Navigator()
        }
      }
    }
  }

  override fun onDestroy() {
    try {
      super.onDestroy()
    } catch (e: Exception) {
      Log.e("MainActivity", "Error during onDestroy", e)
    }
  }

  private fun resolveIsDarkMode(
    darkMode: DarkMode,
    isSystemInDarkTheme: Boolean,
  ): Boolean =
    darkMode == DarkMode.Dark || (darkMode == DarkMode.System && isSystemInDarkTheme)

  private fun isSystemInDarkThemeFromResources(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

  private fun applyEdgeToEdge(isDarkMode: Boolean) {
    if (appliedEdgeToEdgeDarkMode == isDarkMode) return

    val synchronizedBarStyle = SystemBarStyle.auto(
      lightScrim = Color(0xFFF7F5F8).toArgb(),
      darkScrim = Color(0xFF161217).toArgb(),
    ) { isDarkMode }
    enableEdgeToEdge(
      statusBarStyle = synchronizedBarStyle,
      navigationBarStyle = synchronizedBarStyle,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    appliedEdgeToEdgeDarkMode = isDarkMode
  }

  private companion object {
    const val SYSTEM_BAR_THEME_SWITCH_PROGRESS = 0.55f
  }

  /**
   * Auto-connect to network connections that are marked for auto-connection
   */
  private suspend fun autoConnectToNetworks() {
    // Delay auto-connect to let UI settle first
    kotlinx.coroutines.delay(500)

    // Use coroutineScope for properly structured concurrency
    withContext(Dispatchers.IO) {
      try {
        val autoConnectConnections = networkRepository.getAutoConnectConnections()
        autoConnectConnections.forEach { connection ->
          withContext(Dispatchers.Main) {
            Log.d("MainActivity", "Auto-connecting to: ${connection.name}")
          }
          networkRepository.connect(connection)
            .onSuccess {
              withContext(Dispatchers.Main) {
                Log.d("MainActivity", "Auto-connected successfully: ${connection.name}")
              }
            }
            .onFailure { e ->
              withContext(Dispatchers.Main) {
                Log.e("MainActivity", "Auto-connect failed for ${connection.name}: ${e.message}")
              }
            }
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          Log.e("MainActivity", "Error during auto-connect", e)
        }
      }
    }
  }

  /**
   * Navigator that handles screen transitions and provides shared states
   */
  @Composable
  fun Navigator() {
    val backstack = rememberNavBackStack(MainScreen)

    @Suppress("UNCHECKED_CAST")
    val typedBackstack = backstack as NavBackStack<Screen>

    val appNavStyle by playerPreferences.appNavStyle.collectAsState()
    val animSpeed by playerPreferences.animationSpeed.collectAsState()

    val context = LocalContext.current
    val currentVersion = BuildConfig.VERSION_NAME.replace("-dev", "")

    // Conditionally initialize update feature based on build config
    val updateViewModel: UpdateViewModel? = if (BuildConfig.ENABLE_UPDATE_FEATURE) {
      viewModel(context as ComponentActivity)
    } else {
      null
    }
    val updateState by (updateViewModel?.updateState ?: MutableStateFlow(UpdateViewModel.UpdateState.Idle)).collectAsState()
    val isDownloading by (updateViewModel?.isDownloading ?: MutableStateFlow(false)).collectAsState()
    val downloadProgress by (updateViewModel?.downloadProgress ?: MutableStateFlow(0f)).collectAsState()

    // Provide both LocalBackStack and the LazyList/Grid states to all screens
    CompositionLocalProvider(
      LocalBackStack provides typedBackstack
    ) {
      val hasNavEntries = typedBackstack.size > 0

      LaunchedEffect(hasNavEntries) {
        if (!hasNavEntries) {
          typedBackstack.add(MainScreen)
        }
      }

      if (hasNavEntries) {
        NavDisplay(
          modifier = Modifier.fillMaxSize(),
          backStack = typedBackstack,
          onBack = {
            if (typedBackstack.size <= 1 || !typedBackstack.popSafely()) {
              this@MainActivity.finish()
            }
          },
          entryProvider = { route ->
            NavEntry(route) {
              Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
              ) {
                route.Content()
              }
            }
          },
          sizeTransform = null,
          transitionSpec = { screenNavTransition(forward = true, style = appNavStyle, speed = animSpeed) },
          popTransitionSpec = { screenNavTransition(forward = false, style = appNavStyle, speed = animSpeed) },
          predictivePopTransitionSpec = { _: Int -> screenNavTransition(forward = false, style = appNavStyle, speed = animSpeed) },
        )
      }

      // Display Update Dialog when appropriate (only if update feature is enabled)
      if (BuildConfig.ENABLE_UPDATE_FEATURE && updateViewModel != null) {
        when (updateState) {
          is UpdateViewModel.UpdateState.Available -> {
            val release = (updateState as UpdateViewModel.UpdateState.Available).release
            UpdateDialog(
              release = release,
              isDownloading = isDownloading,
              progress = downloadProgress,
              actionLabel = if (isDownloading) "Downloading..." else "Download",
              currentVersion = currentVersion,
              onDismiss = { updateViewModel.dismiss() },
              onAction = { updateViewModel.downloadUpdate(release) },
              onIgnore = { updateViewModel.ignoreVersion(release.tagName.removePrefix("v")) }
            )
          }
          is UpdateViewModel.UpdateState.ReadyToInstall -> {
            val release = (updateState as UpdateViewModel.UpdateState.ReadyToInstall).release
            UpdateDialog(
              release = release,
              isDownloading = isDownloading,
              progress = downloadProgress,
              actionLabel = "Install",
              currentVersion = currentVersion,
              onDismiss = { updateViewModel.dismiss() },
              onAction = { updateViewModel.installUpdate(release) },
              onIgnore = { updateViewModel.ignoreVersion(release.tagName.removePrefix("v")) }
            )
          }
          else -> {}
        }
      }
    }
  }
}


