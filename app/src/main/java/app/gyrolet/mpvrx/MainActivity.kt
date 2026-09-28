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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ProvideFrozenBackdrop
import app.gyrolet.mpvrx.presentation.components.captureFrozenBackdrop
import app.gyrolet.mpvrx.presentation.components.rememberFrozenBackdrop
import app.gyrolet.mpvrx.ui.browser.MainScreen
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.browser.components.MiniPlayer
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
import app.gyrolet.mpvrx.ui.player.PlayerLifecyclePolicy
import app.gyrolet.mpvrx.ui.player.MediaPlaybackService
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.toObject
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.ScreenNavDisplay
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.device.VulkanCapabilities
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import app.gyrolet.mpvrx.utils.device.appUiScaleOverrideConfiguration
import app.gyrolet.mpvrx.utils.media.fileExtension
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.ui.update.UpdateSheet
import app.gyrolet.mpvrx.ui.update.UpdateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.android.inject

private const val RENDERER_NOTICE_PREFERENCES = "renderer_build_notice"
private const val NON_VULKAN_NOTICE_SHOWN = "non_vulkan_notice_shown"

/**
 * Main entry point for the application
 */
class MainActivity : AppCompatActivity() {
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

  override fun onCreate(savedInstanceState: Bundle?) {
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
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
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
    pipHelper.updatePictureInPictureParams()
    if (!isPipMode && (pendingPipExitResolution || wasInPipMode)) {
      window.decorView.post {
        if (!isFinishing && !isDestroyed && !isPipMode && (pendingPipExitResolution || wasInPipMode)) {
          completePipExpansion()
        }
      }
    }
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
   * Navigator that handles screen transitions and provides shared states
   */
  @Composable
  fun Navigator() {
    val backstack = rememberNavBackStack(MainScreen)

    @Suppress("UNCHECKED_CAST")
    val typedBackstack = backstack as NavBackStack<Screen>

    val context = LocalContext.current
    val currentVersion =
      if (BuildConfig.IS_PREVIEW_BUILD) {
        stringResource(R.string.update_beta_build_format, BuildConfig.GIT_COUNT)
      } else {
        BuildConfig.VERSION_NAME.substringBefore('-')
      }

    // Conditionally initialize update feature based on build config
    val updateViewModel: UpdateViewModel? =
      if (BuildConfig.ENABLE_UPDATE_FEATURE) {
        viewModel(context as ComponentActivity)
      } else {
        null
      }

    // These flows are only fallback state for builds where the updater is compiled out. Remember
    // them once so navigator recompositions do not allocate three new StateFlow instances and
    // create fresh collectAsState subscriptions.
    val fallbackUpdateState = remember { MutableStateFlow<UpdateViewModel.UpdateState>(UpdateViewModel.UpdateState.Idle) }
    val fallbackIsDownloading = remember { MutableStateFlow(false) }
    val fallbackDownloadProgress = remember { MutableStateFlow(0f) }
    val updateState by (updateViewModel?.updateState ?: fallbackUpdateState).collectAsState()
    val isDownloading by (updateViewModel?.isDownloading ?: fallbackIsDownloading).collectAsState()
    val downloadProgress by (updateViewModel?.downloadProgress ?: fallbackDownloadProgress).collectAsState()
    val frozenSurfacesEnabled by appearancePreferences.frozenSurfacesEnabled.collectAsState()

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
        val miniPlayerBackdrop = rememberFrozenBackdrop()
        Box(modifier = Modifier.fillMaxSize()) {
          ProvideFrozenBackdrop(
            backdrop = miniPlayerBackdrop,
            enabled = frozenSurfacesEnabled,
          ) {
            ScreenNavDisplay(
              modifier =
                Modifier
                  .fillMaxSize()
                  .captureFrozenBackdrop(miniPlayerBackdrop, frozenSurfacesEnabled),
              backStack = typedBackstack,
              opaqueBackground = typedBackstack.any { it == app.gyrolet.mpvrx.ui.preferences.PreferencesScreen },
              onBack = {
                if (typedBackstack.size <= 1 || !typedBackstack.popSafely()) {
                  this@MainActivity.finish()
                }
              },
            )
          }

          val miniPlayerConfig = LocalConfiguration.current
          val isPortrait = miniPlayerConfig.orientation == Configuration.ORIENTATION_PORTRAIT
          val isTablet = miniPlayerConfig.smallestScreenWidthDp >= 600
          val isDualPane = NavigationBarState.isDualPaneFolderSelected

          val miniPlayerModifier =
            when {
              // Dual-pane tablets: the mini player lives inside the 2nd (right) pane.
              isDualPane ->
                Modifier
                  .align(Alignment.BottomEnd)
                  .fillMaxWidth(0.6f)
                  .windowInsetsPadding(WindowInsets.navigationBars)
                  .padding(bottom = 12.dp, start = 12.dp, end = 12.dp)

              // Portrait phones: full width with a small side margin. During selection,
              // lift the mini player above the edit actions instead of covering them.
              isPortrait && !isTablet ->
                Modifier
                  .align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .windowInsetsPadding(WindowInsets.navigationBars)
                  .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom =
                      (if (NavigationBarState.isNavBarVisible) {
                        NavigationBarState.navigationBarClearance
                      } else {
                        12.dp
                      }) +
                        (if (NavigationBarState.isInSelectionMode) {
                          NavigationBarState.selectionBarClearance
                        } else {
                          0.dp
                        }),
                  )

              // Landscape/tablet single-pane: sit on the right side of the nav bar,
              // which slides left when the mini player appears.
              else -> {
                val isNavBarOnScreen = NavigationBarState.isNavBarVisible
                val navBarLeft = if (NavigationBarState.navbarLeftOffset > 0.dp) NavigationBarState.navbarLeftOffset else 16.dp
                val navBarWidth = if (NavigationBarState.navbarWidth > 0.dp) NavigationBarState.navbarWidth else 320.dp
                val startPadding = if (isNavBarOnScreen) (navBarLeft + navBarWidth + 12.dp) else 12.dp
                val bottomPadding = if (NavigationBarState.isInSelectionMode) {
                  NavigationBarState.selectionBarClearance
                } else {
                  12.dp
                }

                Modifier
                  .align(Alignment.BottomStart)
                  .padding(
                    start = startPadding,
                    end = 12.dp,
                  )
                  .fillMaxWidth()
                  .windowInsetsPadding(WindowInsets.navigationBars)
                  .padding(bottom = bottomPadding)
              }
            }

          ProvideFrozenBackdrop(
            backdrop = miniPlayerBackdrop,
            enabled = frozenSurfacesEnabled,
          ) {
            MiniPlayer(modifier = miniPlayerModifier)
          }
        }
      }

      // Display the update sheet when appropriate (only if update feature is enabled)
      if (BuildConfig.ENABLE_UPDATE_FEATURE && updateViewModel != null) {
        when (updateState) {
          is UpdateViewModel.UpdateState.Available -> {
            val release = (updateState as UpdateViewModel.UpdateState.Available).release
            UpdateSheet(
              release = release,
              isDownloading = isDownloading,
              progress = downloadProgress,
              isInstallReady = false,
              currentVersion = currentVersion,
              onDismiss = { updateViewModel.dismiss() },
              onAction = { updateViewModel.downloadUpdate(release) },
              onIgnore = { updateViewModel.ignoreVersion(release.tagName) },
            )
          }
          is UpdateViewModel.UpdateState.ReadyToInstall -> {
            val release = (updateState as UpdateViewModel.UpdateState.ReadyToInstall).release
            UpdateSheet(
              release = release,
              isDownloading = isDownloading,
              progress = downloadProgress,
              isInstallReady = true,
              currentVersion = currentVersion,
              onDismiss = { updateViewModel.dismiss() },
              onAction = { updateViewModel.installUpdate(release) },
              onIgnore = { updateViewModel.ignoreVersion(release.tagName) },
            )
          }
          else -> {}
        }
      }
    }
  }
}
