/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
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
import app.gyrolet.mpvrx.ui.browser.MainScreen
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.browser.components.MiniPlayer
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.ui.theme.rememberThemeTransitionState
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import app.gyrolet.mpvrx.ui.player.MPVPipHelper
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.MediaPlaybackService
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.toObject
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.device.VulkanCapabilities
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
      fadeIn(
        spring(
          dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
          stiffness = AppMotion.Spatial.Standard.stiffness,
        ),
      ) togetherWith
        fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness))

    NavigationAnimStyle.FlipFade ->
      (
        scaleIn(
          spring(
            dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
            stiffness = AppMotion.Spatial.Expressive.stiffness,
          ),
          initialScale = 0.94f,
        ) +
          fadeIn(
            spring(
              dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
              stiffness = AppMotion.Spatial.Expressive.stiffness,
            ),
          )
      ) togetherWith
        (
          scaleOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness), targetScale = 1.06f) +
            fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness))
        )

    NavigationAnimStyle.Depth ->
      (
        slideInHorizontally(
          spring(
            dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
            stiffness = AppMotion.Spatial.Standard.stiffness,
          ),
        ) {
          it * dir
        } +
          fadeIn(
            spring(
              dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
              stiffness = AppMotion.Spatial.Standard.stiffness,
            ),
          )
      ) togetherWith
        (
          slideOutHorizontally(
            spring(stiffness = AppMotion.Spatial.Standard.stiffness),
          ) { (-it * 0.25f * dir).toInt() } +
            scaleOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness), targetScale = 0.92f) +
            fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness))
        )

    NavigationAnimStyle.Elastic ->
      (
        slideInHorizontally(
          spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f),
        ) { it * dir } + fadeIn(spring(stiffness = AppMotion.Spatial.Snappy.stiffness))
      ) togetherWith
        (
          slideOutHorizontally(spring(stiffness = AppMotion.Spatial.Standard.stiffness)) { (-it / 3 * dir) } +
            fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness))
        )

    NavigationAnimStyle.Default ->
      if (forward) {
        slideInHorizontally(
          spring(
            dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
            stiffness = AppMotion.Spatial.Expressive.stiffness,
          ),
        ) { it } togetherWith
          slideOutHorizontally(
            spring(
              dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
              stiffness = AppMotion.Spatial.Standard.stiffness,
            ),
          ) { -it / 8 }
      } else {
        slideInHorizontally(
          spring(
            dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
            stiffness = AppMotion.Spatial.Expressive.stiffness,
          ),
        ) { -it / 5 } togetherWith
          slideOutHorizontally(
            spring(
              dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
              stiffness = AppMotion.Spatial.Standard.stiffness,
            ),
          ) { it }
      }
  }
}

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

  // Register the ActivityResultLauncher at class level
  private val mediaAccessLauncher =
    registerForActivityResult(
      ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
      PermissionUtils.handleMediaAccessResult(result.resultCode)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

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

      LaunchedEffect(sessionState, enableVideoMiniPlayer, autoPiPOnNavigation, trackListNode) {
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

      if (isPipMode) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
          contentAlignment = Alignment.Center,
        ) {
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
      } else {
        MpvrxTheme(transitionState = themeTransitionState) {
          Surface(modifier = Modifier.fillMaxSize()) {
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

  override fun onStart() {
    super.onStart()
    if (!isPipMode) wasInPipMode = false
    pipHelper.updatePictureInPictureParams()
  }

  override fun onResume() {
    super.onResume()
    pipHelper.updatePictureInPictureParams()
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
    if (isInPictureInPictureMode) {
      wasInPipMode = true
    }
    pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode)
  }

  override fun onStop() {
    val isPipDismissal = wasInPipMode && !isPipMode && !isChangingConfigurations && !isDeviceScreenOffOrLocked()
    if (isPipDismissal) {
      wasInPipMode = false
      MediaPlaybackService.stopForTerminalDismissal()
    }
    super.onStop()
    pipHelper.onStop()
  }

  private fun isDeviceScreenOffOrLocked(): Boolean {
    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
    return keyguardManager?.isDeviceLocked == true || powerManager?.isInteractive == false
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

    val appNavStyle by playerPreferences.appNavStyle.collectAsState()
    val animSpeed by playerPreferences.animationSpeed.collectAsState()

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
        Box(modifier = Modifier.fillMaxSize()) {
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
            predictivePopTransitionSpec = { _: Int ->
              screenNavTransition(forward = false, style = appNavStyle, speed = animSpeed)
            },
          )

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

          MiniPlayer(modifier = miniPlayerModifier)
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
