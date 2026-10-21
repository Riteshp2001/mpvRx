/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ProvideLiquidGlassBackdrop
import app.gyrolet.mpvrx.presentation.components.captureLiquidGlassBackdrop
import app.gyrolet.mpvrx.presentation.components.rememberLiquidGlassBackdrop
import app.gyrolet.mpvrx.ui.browser.MainScreen
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.browser.components.MiniPlayer
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.AppWallpaperHost
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.ui.theme.rememberThemeTransitionState
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import app.gyrolet.mpvrx.ui.player.MPVPipHelper
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerSheetDestination
import app.gyrolet.mpvrx.ui.player.PlayerSheetTransitionOverlay
import app.gyrolet.mpvrx.ui.player.PlayerSheetTransitions
import app.gyrolet.mpvrx.ui.player.playerSheetBackdrop
import app.gyrolet.mpvrx.ui.player.playerSheetMiniPlacement
import app.gyrolet.mpvrx.ui.player.PlayerLifecyclePolicy
import app.gyrolet.mpvrx.ui.player.MediaPlaybackService
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.toObject
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.ScreenNavDisplay
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.device.VulkanCapabilities
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import app.gyrolet.mpvrx.utils.device.appUiScaleOverrideConfiguration
import app.gyrolet.mpvrx.utils.media.fileExtension
import app.gyrolet.mpvrx.utils.permission.PermissionRecovery
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.ui.update.UpdateSheet
import app.gyrolet.mpvrx.ui.update.UpdateUiState
import app.gyrolet.mpvrx.ui.update.UpdateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.android.inject

private const val RENDERER_NOTICE_PREFERENCES = "renderer_build_notice"
private const val NON_VULKAN_NOTICE_SHOWN = "non_vulkan_notice_shown"

/**
 * Main entry point for the application
 */
open class MainActivity : AppCompatActivity() {
  private val appearancePreferences by inject<AppearancePreferences>()
  private val playerPreferences by inject<PlayerPreferences>()
  private var appliedEdgeToEdgeDarkMode: Boolean? = null
  private lateinit var pipHelper: MPVPipHelper
  private var isPipMode by mutableStateOf(false)
  private var wasInPipMode = false
  private var pendingPipExitResolution = false
  private var isExpandingFromPip by mutableStateOf(false)

  // Register the ActivityResultLauncher at class level
  private val mediaAccessLauncher =
    registerForActivityResult(
      ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
      PermissionUtils.handleMediaAccessResult(result.resultCode)
    }

  // Re-asks for revoked runtime grants; the outcome is read back on the next resume.
  private val runtimePermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    // Match REX Player's splash handoff: use only Android's system SplashScreen and hand
    // directly into the real UI. Do not draw a second Compose logo/title layer afterwards.
    installSplashScreen()
    super.onCreate(savedInstanceState)

    if (DeviceFormFactor.isTelevision(this)) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    pipHelper = MPVPipHelper(
      activity = this,
      isAudioPlayer = { isCurrentMediaAudioOnly() },
      isVideoLoaded = { isCurrentMediaVideoLoaded() },
    )

    PermissionUtils.setMediaAccessLauncher(mediaAccessLauncher)

    applyEdgeToEdge(
      isDarkMode =
        resolveIsDarkMode(
          darkMode = appearancePreferences.darkMode.get(),
          isSystemInDarkTheme = isSystemInDarkThemeFromResources(),
        ),
    )

    setContent {
      // Set up theme and edge-to-edge display
      val dark by appearancePreferences.darkMode.collectAsState()
      val networkStreamingEnabled by appearancePreferences.showNetworkTab.collectAsState()
      val sessionState by PlaybackSession.state.collectAsState()
      val enableVideoMiniPlayer by playerPreferences.enableVideoMiniPlayer.collectAsState()
      val autoPiPOnNavigation by playerPreferences.autoPiPOnNavigation.collectAsState()
      val trackListNode by PlaybackSession.propNode["track-list"].collectAsState()
      val rendererNoticePreferences =
        remember { getSharedPreferences(RENDERER_NOTICE_PREFERENCES, MODE_PRIVATE) }
      var showRendererBuildNotice by remember {
        mutableStateOf(
          !BuildConfig.MPV_SUPPORTS_VULKAN &&
            !rendererNoticePreferences.getBoolean(NON_VULKAN_NOTICE_SHOWN, false),
        )
      }
      val deviceSupportsVulkan = remember { VulkanCapabilities.isDeviceSupported(this@MainActivity) }

      LaunchedEffect(
        sessionState,
        enableVideoMiniPlayer,
        autoPiPOnNavigation,
        trackListNode,
        NavigationBarState.isMiniPlayerVisible,
      ) {
        pipHelper.updatePictureInPictureParams()
      }

      val isSystemInDarkTheme = isSystemInDarkTheme()
      val isDarkMode =
        remember(dark, isSystemInDarkTheme) {
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
        if (networkStreamingEnabled) {
          (application as? App)?.autoConnectNetworksOnce()
        }
      }

      if (isPipMode || isExpandingFromPip) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
          contentAlignment = Alignment.Center,
        ) {
          if (isPipMode) {
            AndroidView(
              modifier = Modifier.fillMaxSize(),
              factory = { viewContext ->
                SurfaceView(viewContext).apply {
                  setZOrderMediaOverlay(true)
                  holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                      PlaybackSession.bindSurface(
                        surface = holder.surface,
                        owner = this@apply,
                        ownerIsActive = { MediaPlaybackService.isForegroundActive() },
                      )
                    }

                    override fun surfaceChanged(
                      holder: SurfaceHolder,
                      format: Int,
                      width: Int,
                      height: Int,
                    ) {
                      if (holder.surface.isValid) {
                        PlaybackSession.resizeSurface(width, height, owner = this@apply)
                      }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                      PlaybackSession.unbindSurface(this@apply)
                    }
                  })
                }
              },
            )
          }
        }
      } else {
        MpvrxTheme(transitionState = themeTransitionState) {
          AppWallpaperHost {
            // Reserve the system button area once for every destination and overlay.
            // Consuming it here lets nested Scaffolds/sheets avoid adding it twice.
            Surface(
              modifier = Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.tappableElement.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
              ),
              color = Color.Transparent,
            ) {
              Navigator()
            }
            if (showRendererBuildNotice) {
              val acknowledgeNotice = {
                rendererNoticePreferences.edit().putBoolean(NON_VULKAN_NOTICE_SHOWN, true).apply()
                showRendererBuildNotice = false
              }
              AlertDialog(
                onDismissRequest = acknowledgeNotice,
                title = { Text(getString(R.string.renderer_build_notice_title)) },
                text = {
                  Text(
                    getString(
                      if (deviceSupportsVulkan) {
                        R.string.renderer_build_notice_supported_device
                      } else {
                        R.string.renderer_build_notice_unsupported_device
                      },
                    ),
                  )
                },
                confirmButton = {
                  TextButton(onClick = acknowledgeNotice) {
                    Text(getString(R.string.generic_ok))
                  }
                },
              )
            }
          }
        }
      }
    }

  }

  override fun attachBaseContext(newBase: android.content.Context?) {
    if (newBase == null) {
      super.attachBaseContext(null)
      return
    }

    // An override configuration is re-merged by the framework on every configuration change.
    // createConfigurationContext() is not, so rotation would drop the scale on devices that
    // handle the rotation themselves instead of recreating the activity.
    applyOverrideConfiguration(appUiScaleOverrideConfiguration(newBase, appearancePreferences.appUiScale.get()))
    super.attachBaseContext(newBase)
  }

  override fun onStart() {
    super.onStart()
    pipHelper.updatePictureInPictureParams()
  }

  override fun onResume() {
    super.onResume()
    PermissionRecovery.requestRevoked(this, runtimePermissionLauncher)
    refreshLauncherIcon()
    pipHelper.updatePictureInPictureParams()
    if (!isPipMode && (pendingPipExitResolution || wasInPipMode)) {
      window.decorView.post {
        if (!isFinishing && !isDestroyed && !isPipMode && (pendingPipExitResolution || wasInPipMode)) {
          completePipExpansion()
        }
      }
    }
    // The browser is visible again; drop the frozen copy kept for in-player gestures.
    PlayerSheetTransitions.clearBrowserCache()
    // Interactive again without the player having taken over, so its expansion is void.
    PlayerSheetTransitions.abandonExpansion()
  }

  override fun onPause() {
    // Still on screen here, so this is the last point where the browser layer can be frozen
    // for the player's swipe-down and predictive-back previews.
    PlayerSheetTransitions.captureBrowser()
    super.onPause()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus && !isPipMode && (pendingPipExitResolution || wasInPipMode)) {
      completePipExpansion()
    }
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    val isServiceRunning = MediaPlaybackService.isForegroundActive()
    val sessionState = PlaybackSession.state.value
    val isMediaActive = isServiceRunning && sessionState.currentItem != null &&
      NavigationBarState.isMiniPlayerVisible &&
      sessionState.phase != PlaybackPhase.IDLE &&
      sessionState.phase != PlaybackPhase.UNINITIALIZED &&
      sessionState.phase != PlaybackPhase.ERROR
    if (
      playerPreferences.autoPiPOnNavigation.get() &&
      isMediaActive &&
      !isCurrentMediaAudioOnly() &&
      !isPipMode
    ) {
      pipHelper.enterPipMode()
    }
  }

  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    this.isPipMode = isInPictureInPictureMode
    pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode)
    if (isInPictureInPictureMode) {
      wasInPipMode = true
      pendingPipExitResolution = false
      isExpandingFromPip = false
    } else if (wasInPipMode) {
      isExpandingFromPip = true
      schedulePipExitResolution()
    }
  }

  private fun schedulePipExitResolution() {
    pendingPipExitResolution = true
    if (isFinishing || isDestroyed) {
      stopPipPlayback()
      return
    }
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus()) {
      completePipExpansion()
    }
  }

  private fun stopPipPlayback() {
    val hadPipSession = wasInPipMode || pendingPipExitResolution
    pendingPipExitResolution = false
    wasInPipMode = false
    isExpandingFromPip = false
    if (hadPipSession) MediaPlaybackService.stopForTerminalDismissal()
  }

  private fun completePipExpansion() {
    if (!pendingPipExitResolution && !wasInPipMode) return
    pendingPipExitResolution = false
    wasInPipMode = false
    this.isPipMode = false
    openPlayerFromPipMaximize()
  }

  private fun openPlayerFromPipMaximize() {
    val sessionState = PlaybackSession.state.value
    val currentItem = sessionState.currentItem ?: PlaybackSession.queue.value.currentItem
    if (
      currentItem == null ||
      sessionState.phase == PlaybackPhase.IDLE ||
      sessionState.phase == PlaybackPhase.UNINITIALIZED ||
      sessionState.phase == PlaybackPhase.ERROR
    ) {
      isExpandingFromPip = false
      return
    }

    val intent = Intent(this, PlayerActivity::class.java).apply {
      action = MediaPlaybackService.ACTION_OPEN_PLAYER
      putExtra("is_audio", isCurrentMediaAudioOnly())
      putExtra("internal_launch", true)
      putExtra("launch_source", "pip_maximize")
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    try {
      startActivity(intent)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
      } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
      }
    } catch (e: Exception) {
      Log.e("MainActivity", "Failed to launch PlayerActivity from PiP maximize", e)
      isExpandingFromPip = false
    }
  }

  override fun onStop() {
    super.onStop()
    // The fullscreen window now covers the browser; its source mini player can leave composition.
    PlayerSheetTransitions.releaseExpansionHold()
    pipHelper.onStop()
    val powerManager = getSystemService(android.os.PowerManager::class.java)
    val keyguardManager = getSystemService(android.app.KeyguardManager::class.java)
    if (
      PlayerLifecyclePolicy.shouldTreatStopAsPipDismissal(
        wasInPictureInPictureMode = wasInPipMode,
        isInPictureInPictureMode = isInPictureInPictureMode,
        isActivityFinishing = isFinishing,
        isChangingConfigurations = isChangingConfigurations,
        isScreenOffOrLocked = powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true,
        alreadyHandled = false,
      )
    ) {
      stopPipPlayback()
    }
  }

  private fun isCurrentMediaAudioOnly(): Boolean {
    val sessionState = PlaybackSession.state.value
    val currentItem = sessionState.currentItem ?: return true

    val ext = (currentItem.originalUri.ifBlank { currentItem.title.orEmpty() }).fileExtension()
    val mimeIsAudio = currentItem.mimeType?.startsWith("audio/", ignoreCase = true) == true
    val extIsAudio = ext in FileTypeUtils.AUDIO_EXTENSIONS
    val extIsVideo = ext in FileTypeUtils.VIDEO_EXTENSIONS

    if (extIsVideo) return false
    if (mimeIsAudio || extIsAudio) return true

    val trackListNode = PlaybackSession.propNode["track-list"].value
    if (trackListNode != null) {
      val json: Json = getKoin().get()
      val tracks = runCatching { trackListNode.toObject<List<TrackNode>>(json) }.getOrNull().orEmpty()
      val hasRealVideo = tracks.any { it.isVideo && !it.isAlbumArtwork }
      if (hasRealVideo) return false
    }
    return false
  }

  private fun isCurrentMediaVideoLoaded(): Boolean {
    val isServiceRunning = MediaPlaybackService.isForegroundActive()
    val sessionState = PlaybackSession.state.value
    return isServiceRunning &&
      sessionState.currentItem != null &&
      NavigationBarState.isMiniPlayerVisible &&
      sessionState.phase != PlaybackPhase.IDLE &&
      sessionState.phase != PlaybackPhase.UNINITIALIZED &&
      sessionState.phase != PlaybackPhase.ERROR
  }

  override fun onDestroy() {
    if (!isChangingConfigurations) stopPipPlayback()
    pendingPipExitResolution = false
    wasInPipMode = false
    isExpandingFromPip = false
    try {
      super.onDestroy()
    } catch (e: Exception) {
      Log.e("MainActivity", "Error during onDestroy", e)
    }
  }



  private fun resolveIsDarkMode(
    darkMode: DarkMode,
    isSystemInDarkTheme: Boolean,
  ): Boolean = darkMode == DarkMode.Dark || (darkMode == DarkMode.System && isSystemInDarkTheme)

  private fun isSystemInDarkThemeFromResources(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

  private fun applyEdgeToEdge(isDarkMode: Boolean) {
    if (appliedEdgeToEdgeDarkMode == isDarkMode) return

    val synchronizedBarStyle =
      SystemBarStyle.auto(
        // Fully transparent so the wallpaper is visible behind the status/navigation bars.
        lightScrim = android.graphics.Color.TRANSPARENT,
        darkScrim = android.graphics.Color.TRANSPARENT,
      ) { isDarkMode }
    enableEdgeToEdge(
      statusBarStyle = synchronizedBarStyle,
      navigationBarStyle = synchronizedBarStyle,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // In three-button navigation Android applies a contrast scrim behind the
      // system buttons. Gesture navigation remains transparent where supported.
      // Disabling enforcement unconditionally makes three-button controls hard
      // to see against arbitrary artwork, wallpapers and expressive surfaces.
      window.isNavigationBarContrastEnforced = true
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      // Draw into camera-cutout areas too, so the wallpaper covers the whole screen.
      window.attributes =
        window.attributes.apply {
          layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    appliedEdgeToEdgeDarkMode = isDarkMode
  }

  private companion object {
    const val SYSTEM_BAR_THEME_SWITCH_PROGRESS = 0.55f

    // Per process: alias states only change through LauncherIconManager or the repair receiver.
    val launcherIconsReconciled = java.util.concurrent.atomic.AtomicBoolean(false)
  }

  /**
   * Repairing launcher aliases reads the state of every alias, one PackageManager binder call each,
   * so it runs once per process off the main thread instead of on every return to the browser.
   */
  private fun refreshLauncherIcon() {
    if (!launcherIconsReconciled.compareAndSet(false, true)) {
      app.gyrolet.mpvrx.ui.preferences.LauncherIconManager.refreshTaskIcon(this)
      return
    }
    lifecycleScope.launch {
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { app.gyrolet.mpvrx.ui.preferences.LauncherIconManager.reconcile(applicationContext) }
          .onFailure { Log.w("LauncherIcon", "Unable to reconcile launcher aliases", it) }
      }
      app.gyrolet.mpvrx.ui.preferences.LauncherIconManager.refreshTaskIcon(this@MainActivity)
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

    val context = LocalContext.current
    val currentVersion =
      when {
        BuildConfig.IS_EXPERIMENTAL_BUILD -> stringResource(R.string.update_experimental_build_format, BuildConfig.GIT_COUNT)
        BuildConfig.IS_PREVIEW_BUILD -> stringResource(R.string.update_beta_build_format, BuildConfig.GIT_COUNT)
        else -> BuildConfig.VERSION_NAME.substringBefore('-')
      }

    // Conditionally initialize update feature based on build config
    val updateViewModel: UpdateViewModel? =
      if (BuildConfig.ENABLE_UPDATE_FEATURE) {
        viewModel(context as ComponentActivity)
      } else {
        null
      }

    val fallbackUpdateState = remember { MutableStateFlow(UpdateUiState()) }
    val updateState by (updateViewModel?.uiState ?: fallbackUpdateState).collectAsState()
    val liquidGlassEnabled by appearancePreferences.liquidGlassEnabled.collectAsState()
    val legacyNavigationBar by appearancePreferences.legacyNavigationBar.collectAsState()

    // Provide both LocalBackStack and the LazyList/Grid states to all screens
    CompositionLocalProvider(
      LocalBackStack provides typedBackstack,
    ) {
      val hasNavEntries = typedBackstack.isNotEmpty()

      LaunchedEffect(hasNavEntries) {
        if (!hasNavEntries) {
          typedBackstack.add(MainScreen)
        }
      }

      if (hasNavEntries) {
        val miniPlayerBackdrop = rememberLiquidGlassBackdrop()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
          val density = LocalDensity.current
          val layoutDirection = LocalLayoutDirection.current
          val safeInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
          val leftInset = with(density) { safeInsets.getLeft(density, layoutDirection).toDp() }
          val rightInset = with(density) { safeInsets.getRight(density, layoutDirection).toDp() }
          val isMainScreen = typedBackstack.lastOrNull() == MainScreen
          val isDualPane = isMainScreen && !legacyNavigationBar && NavigationBarState.isDualPaneLayout
          val isNavBarVisible = isMainScreen && NavigationBarState.isNavBarVisible
          val spacing = NavigationBarState.floatingBarSpacing
          val navigationStart =
            if (layoutDirection == LayoutDirection.Ltr) {
              NavigationBarState.navbarLeftOffset - leftInset
            } else {
              maxWidth - NavigationBarState.navbarLeftOffset - NavigationBarState.navbarWidth - rightInset
            }
          val besideStartSpacing = (navigationStart + NavigationBarState.navbarWidth + spacing).coerceAtLeast(spacing)
          val besideNavigation = isNavBarVisible && !legacyNavigationBar && !isDualPane &&
            NavigationBarState.canPlaceMiniPlayerBesideNavigation(maxWidth - leftInset - rightInset) &&
            maxWidth - leftInset - rightInset - besideStartSpacing - spacing >= NavigationBarState.miniPlayerMinWidth
          SideEffect {
            NavigationBarState.isMiniPlayerBesideNavigation = besideNavigation
          }

          CompositionLocalProvider(
            LocalNavigationBarHeight provides NavigationBarState.bottomOverlayClearance,
          ) {
            ScreenNavDisplay(
              modifier =
                Modifier
                  .fillMaxSize()
                  .playerSheetBackdrop()
                  .captureLiquidGlassBackdrop(miniPlayerBackdrop, liquidGlassEnabled),
              backStack = typedBackstack,
              opaqueFrom = app.gyrolet.mpvrx.ui.preferences.PreferencesScreen,
              onBack = {
                if (typedBackstack.size <= 1 || !typedBackstack.popSafely()) {
                  this@MainActivity.finish()
                }
              },
            )
          }

          val reducedMotion = AppMotion.shouldReduceMotion()
          val playerHandoff = PlayerSheetTransitions.motion != null ||
            PlayerSheetTransitions.collapsedMediaId != null || PlayerSheetTransitions.expandingMediaId != null
          val placementAnimation = if (reducedMotion || playerHandoff) snap() else tween<Float>(
            durationMillis = 320,
            easing = FastOutSlowInEasing,
          )
          val targetLift = when {
            NavigationBarState.isInSelectionMode -> NavigationBarState.selectionBarClearance - spacing
            isNavBarVisible && !besideNavigation && !isDualPane -> NavigationBarState.navigationBarClearance - spacing
            else -> 0.dp
          }
          var lastLift by remember { mutableStateOf(targetLift) }
          SideEffect {
            if (targetLift > 0.dp) lastLift = targetLift
          }
          val liftFraction by animateFloatAsState(
            targetValue = if (targetLift > 0.dp) 1f else 0f,
            animationSpec = placementAnimation,
            label = "mini_player_bottom_clearance",
          )
          // Animate visibility changes, not heights that the navigation is already animating.
          val miniPlayerLift = (if (targetLift > 0.dp) targetLift else lastLift) * liftFraction
          // Follow the measured navigation edge directly, including its alignment animation.
          val startSpacing =
            if (besideNavigation) {
              besideStartSpacing
            } else {
              spacing
            }

          val miniPlayerModifier =
            Modifier
              .align(if (isDualPane) Alignment.BottomEnd else Alignment.BottomStart)
              .fillMaxWidth(if (isDualPane) 0.6f else 1f)
              .windowInsetsPadding(
                if (isDualPane) safeInsets.only(WindowInsetsSides.End + WindowInsetsSides.Bottom) else safeInsets,
              )
              .padding(start = startSpacing, end = spacing, bottom = spacing)
              .offset(y = -miniPlayerLift)

          Box(
            modifier = miniPlayerModifier
              .height(NavigationBarState.miniPlayerHeight)
              .playerSheetMiniPlacement(
                AppShapeScale.largeIncreased,
                androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
              ),
          )
          ProvideLiquidGlassBackdrop(
            backdrop = miniPlayerBackdrop,
            enabled = liquidGlassEnabled,
          ) {
            MiniPlayer(modifier = miniPlayerModifier)
          }
          PlayerSheetTransitionOverlay(PlayerSheetDestination.MINI, Modifier.matchParentSize())
        }
      }

      // One call site preserves sheet state across loading, selection, errors and completion.
      if (BuildConfig.ENABLE_UPDATE_FEATURE && updateViewModel != null && updateState.visible) {
        UpdateSheet(
          state = updateState,
          currentVersion = currentVersion,
          onDismiss = updateViewModel::dismiss,
          onDownload = updateViewModel::downloadUpdate,
          onInstall = updateViewModel::installUpdate,
          onIgnore = updateViewModel::ignoreVersion,
          onChannelChange = updateViewModel::setUpdateChannel,
          onSelectRelease = updateViewModel::selectRelease,
          onLoadMore = updateViewModel::loadMoreHistory,
          onRetryHistory = updateViewModel::retryHistory,
          onLoadCommit = updateViewModel::loadCommitDetails,
        )
      }
    }
  }
}
