package app.gyrolet.mpvrx.exoplayer.feature.player

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.ui.icons.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.data.repository.ExternalSubtitleFontSource
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControl
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlZone
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlsStyle
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerIconStyle
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.noRippleClickable
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.seekByRequestedOffset
import app.gyrolet.mpvrx.exoplayer.feature.player.input.PlayerKeyboardController
import app.gyrolet.mpvrx.exoplayer.feature.player.service.previewVideoFilters
import app.gyrolet.mpvrx.exoplayer.feature.player.service.setScreenAspectRatio
import app.gyrolet.mpvrx.exoplayer.feature.player.state.ControlsVisibilityState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.VerticalGesture
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberBrightnessState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberControlsVisibilityState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberErrorState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberMediaPresentationState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberMetadataState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberPictureInPictureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberRotationState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberSeekGestureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberSleepTimerState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberTapGestureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberVideoZoomAndContentScaleState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberVolumeAndBrightnessGestureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberVolumeState
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.AudioTrackSelectorContent
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.DecoderPrioritySelectorContent
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.DeviceStatsOverlay
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.DoubleTapIndicator
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.MenuOverlayView
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.MenuRootContent
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.MenuRoute
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.OverlayShowView
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.OverlayView
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.PlaybackSpeedSelectorContent
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.PlaylistContent
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.SleepTimerSelectorContent
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.SubtitleConfiguration
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.SubtitleSelectorContent
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.VerticalProgressView
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.VideoContentScaleSelectorContent
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.ExoPlayerControls
import app.gyrolet.mpvrx.exoplayer.core.ui.components.VideoFiltersPanel

private const val TAG = "MediaPlayerScreen"

val LocalControlsVisibilityState = compositionLocalOf<ControlsVisibilityState?> { null }
val LocalPlayerIconStyle = compositionLocalOf { PlayerIconStyle.TONAL }

internal data class LongPressOverlayUiState(
    val speedText: String,
)

internal fun resolveLongPressOverlayUiState(
    isLongPressGestureInAction: Boolean,
    isDebugLongPressOverlayVisible: Boolean,
    longPressSpeed: Float,
    shouldShowOverlay: Boolean,
): LongPressOverlayUiState? {
    if (!shouldShowOverlay && !isDebugLongPressOverlayVisible) return null
    if (!isLongPressGestureInAction && !isDebugLongPressOverlayVisible) return null

    return LongPressOverlayUiState(
        speedText = String.format(Locale.US, "%.1fx", longPressSpeed),
    )
}

@OptIn(UnstableApi::class)
@Composable
internal fun MediaPlayerScreen(
    player: Player?,
    viewModel: PlayerViewModel,
    playerPreferences: PlayerPreferences,
    externalSubtitleFontSource: ExternalSubtitleFontSource?,
    modifier: Modifier = Modifier,
    onSelectSubtitleClick: () -> Unit,
    onAddOnlineSubtitleClick: (String) -> Unit,
    onBackClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
    isTakingScreenshot: Boolean = false,
    onScreenshotClick: () -> Unit,
    onKeyboardEventHandlerChanged: ((KeyEvent) -> Boolean) -> Unit = {},
) {
    val volumeState = rememberVolumeState(
        player = player,
        shouldShowVolumePanelIfHeadsetIsOn = playerPreferences.shouldShowSystemVolumePanel,
        isVolumeBoostEnabled = playerPreferences.isVolumeBoostEnabled,
    )
    player ?: return
    val metadataState = rememberMetadataState(player)
    val mediaPresentationState = rememberMediaPresentationState(player)
    val controlsVisibilityState = rememberControlsVisibilityState(
        player = player,
        hideAfter = playerPreferences.controllerAutoHideTimeout.seconds,
    )
    val tapGestureState = rememberTapGestureState(
        player = player,
        doubleTapGesture = playerPreferences.doubleTapGesture,
        seekIncrementMillis = playerPreferences.seekIncrement.seconds.inWholeMilliseconds,
        shouldUseLongPressGesture = playerPreferences.shouldUseLongPressControls,
        shouldUseLongPressVariableSpeed = playerPreferences.shouldUseLongPressVariableSpeed,
        longPressSpeed = playerPreferences.longPressControlsSpeed,
    )
    val seekGestureState = rememberSeekGestureState(
        player = player,
        sensitivity = playerPreferences.seekSensitivity,
        isSeekGestureEnabled = playerPreferences.shouldUseSeekControls,
    )
    val pictureInPictureState = rememberPictureInPictureState(
        player = player,
        shouldAutoEnter = playerPreferences.shouldAutoEnterPip,
    )
    val videoZoomAndContentScaleState = rememberVideoZoomAndContentScaleState(
        player = player,
        initialContentScale = playerPreferences.playerVideoZoom,
        isZoomGestureEnabled = playerPreferences.shouldUseZoomControls,
        isPanGestureEnabled = playerPreferences.isPanGestureEnabled,
        onEvent = viewModel::onVideoZoomEvent,
    )
    val brightnessState = rememberBrightnessState()
    val volumeAndBrightnessGestureState = rememberVolumeAndBrightnessGestureState(
        volumeState = volumeState,
        brightnessState = brightnessState,
        isVolumeGestureEnabled = playerPreferences.isVolumeSwipeGestureEnabled,
        isBrightnessGestureEnabled = playerPreferences.isBrightnessSwipeGestureEnabled,
        volumeGestureSensitivity = playerPreferences.volumeGestureSensitivity,
        brightnessGestureSensitivity = playerPreferences.brightnessGestureSensitivity,
    )
    val rotationState = rememberRotationState(
        player = player,
        screenOrientation = playerPreferences.playerScreenOrientation,
        shouldRememberScreenOrientation = playerPreferences.shouldRememberPlayerScreenOrientation,
        lastScreenOrientation = playerPreferences.lastPlayerScreenOrientation,
        onLastScreenOrientationChange = viewModel::updateLastPlayerScreenOrientation,
    )
    var restoredVolumeMediaItemIndex by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var lastSavedVolumePercentage by remember { mutableIntStateOf(volumeState.volumePercentage) }
    var pendingRestoredVolumePercentage by remember { mutableStateOf<Int?>(null) }
    val errorState = rememberErrorState(player = player)

    LaunchedEffect(pictureInPictureState.isInPictureInPictureMode) {
        if (pictureInPictureState.isInPictureInPictureMode) {
            controlsVisibilityState.hideControls()
        }
    }

    LaunchedEffect(tapGestureState.isLongPressGestureInAction) {
        if (tapGestureState.isLongPressGestureInAction) {
            controlsVisibilityState.hideControls()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (playerPreferences.shouldRememberPlayerBrightness) {
            brightnessState.setBrightness(playerPreferences.playerBrightness)
        }
        if (playerPreferences.shouldRememberPlayerVolume && restoredVolumeMediaItemIndex != player.currentMediaItemIndex) {
            restoredVolumeMediaItemIndex = player.currentMediaItemIndex
            val savedVolumePercentage = playerPreferences.playerVolumePercentage
            val restoredVolumePercentage = savedVolumePercentage.coerceAtMost(playerPreferences.maxInitialPlayerVolumePercentage)
            Logger.debug(
                TAG,
                "Restore player volume: saved=$savedVolumePercentage, " +
                    "limit=${playerPreferences.maxInitialPlayerVolumePercentage}, applied=$restoredVolumePercentage",
            )
            volumeState.updateVolumePercentage(restoredVolumePercentage)
            pendingRestoredVolumePercentage = volumeState.volumePercentage
        }
    }

    LaunchedEffect(brightnessState.currentBrightness) {
        if (playerPreferences.shouldRememberPlayerBrightness) {
            viewModel.updatePlayerBrightness(brightnessState.currentBrightness)
        }
    }

    LaunchedEffect(volumeState.volumePercentage) {
        if (!playerPreferences.shouldRememberPlayerVolume) return@LaunchedEffect
        if (pendingRestoredVolumePercentage == volumeState.volumePercentage) {
            pendingRestoredVolumePercentage = null
            lastSavedVolumePercentage = volumeState.volumePercentage
            return@LaunchedEffect
        }
        pendingRestoredVolumePercentage = null
        if (lastSavedVolumePercentage == volumeState.volumePercentage) return@LaunchedEffect

        lastSavedVolumePercentage = volumeState.volumePercentage
        viewModel.updatePlayerVolume(volumeState.volumePercentage)
    }

    var overlayView by remember { mutableStateOf<OverlayView?>(null) }
    val isModern = playerPreferences.controlsStyle == PlayerControlsStyle.MODERN
    var menuRouteStack by remember { mutableStateOf<List<MenuRoute>>(emptyList()) }
    val isAmbienceModeEnabled = playerPreferences.isAmbienceModeEnabled

    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp) {
        val aspectRatio = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
        (player as? androidx.media3.session.MediaController)?.setScreenAspectRatio(aspectRatio)
    }

    val sleepTimerState = rememberSleepTimerState(player = player)

    var shouldShowOverlay by remember { mutableStateOf(false) }
    var videoFiltersInitialPreferences by remember { mutableStateOf<PlayerPreferences?>(null) }
    val videoFiltersUnavailableMessage = stringResource(R.string.video_filters_unavailable_software_decoder)

    fun restoreVideoFiltersPreview() {
        videoFiltersInitialPreferences?.let { initialPreferences ->
            (player as? androidx.media3.session.MediaController)?.previewVideoFilters(initialPreferences)
        }
        videoFiltersInitialPreferences = null
    }

    fun overlayViewToMenuRoute(view: OverlayView): MenuRoute = when (view) {
        OverlayView.AUDIO_SELECTOR -> MenuRoute.Audio
        OverlayView.SUBTITLE_SELECTOR -> MenuRoute.Subtitle
        OverlayView.PLAYBACK_SPEED -> MenuRoute.PlaybackSpeed
        OverlayView.VIDEO_CONTENT_SCALE -> MenuRoute.VideoContentScale
        OverlayView.VIDEO_FILTERS -> MenuRoute.VideoFilters
        OverlayView.PLAYLIST -> MenuRoute.Playlist
        OverlayView.SLEEP_TIMER -> MenuRoute.SleepTimer
        OverlayView.DECODER_PRIORITY -> MenuRoute.Decoder
    }

    fun openOverlayPanel(target: OverlayView) {
        controlsVisibilityState.hideControls()
        if (isModern) {
            menuRouteStack = listOf(overlayViewToMenuRoute(target))
        } else {
            overlayView = target
        }
    }

    val showVideoFilters = {
        if (metadataState.isVideoEffectsAvailable) {
            videoFiltersInitialPreferences = playerPreferences
            openOverlayPanel(OverlayView.VIDEO_FILTERS)
        } else {
            Toast.makeText(context, videoFiltersUnavailableMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun dismissOverlay() {
        if (overlayView == OverlayView.VIDEO_FILTERS || menuRouteStack.contains(MenuRoute.VideoFilters)) {
            restoreVideoFiltersPreview()
        }
        overlayView = null
        menuRouteStack = emptyList()
    }

    fun popMenuRoute() {
        if (menuRouteStack.lastOrNull() == MenuRoute.VideoFilters) {
            restoreVideoFiltersPreview()
        }
        if (menuRouteStack.size > 1) {
            menuRouteStack = menuRouteStack.dropLast(1)
        } else {
            menuRouteStack = emptyList()
        }
    }

    fun navigateToMenuRoute(target: MenuRoute) {
        if (target == MenuRoute.VideoFilters) {
            if (!metadataState.isVideoEffectsAvailable) {
                Toast.makeText(context, videoFiltersUnavailableMessage, Toast.LENGTH_SHORT).show()
                return
            }
            videoFiltersInitialPreferences = playerPreferences
        }
        menuRouteStack = menuRouteStack + target
    }

    var longPressOverlayAnimationStep by remember { mutableIntStateOf(0) }
    val keyboardInteractionEnabledState = rememberUpdatedState(
        overlayView == null && menuRouteStack.isEmpty() && !controlsVisibilityState.isControlsLocked
    )
    val seekIncrementState = rememberUpdatedState(playerPreferences.seekIncrement.seconds.inWholeMilliseconds)
    val currentPlayerState = rememberUpdatedState(player)
    val currentTapGestureState = rememberUpdatedState(tapGestureState)
    val currentControlsVisibilityState = rememberUpdatedState(controlsVisibilityState)
    val currentVolumeState = rememberUpdatedState(volumeState)

    val keyboardController = remember {
        PlayerKeyboardController(
            onSeekBackward = {
                currentPlayerState.value.seekByRequestedOffset(-seekIncrementState.value)
                currentControlsVisibilityState.value.showControls()
            },
            onSeekForward = {
                currentPlayerState.value.seekByRequestedOffset(seekIncrementState.value)
                currentControlsVisibilityState.value.showControls()
            },
            onIncreaseVolume = {
                currentVolumeState.value.increaseVolume(shouldShowVolumePanel = true)
                currentControlsVisibilityState.value.showControls()
            },
            onDecreaseVolume = {
                currentVolumeState.value.decreaseVolume(shouldShowVolumePanel = true)
                currentControlsVisibilityState.value.showControls()
            },
            onTogglePlayPause = {
                if (currentPlayerState.value.isPlaying) {
                    currentPlayerState.value.pause()
                } else {
                    currentPlayerState.value.play()
                }
                currentControlsVisibilityState.value.showControls()
            },
            onStartTemporarySpeed = {
                val didStart = currentTapGestureState.value.handleKeyboardLongPress()
                if (didStart) {
                    currentControlsVisibilityState.value.hideControls()
                }
                didStart
            },
            onStopTemporarySpeed = {
                currentTapGestureState.value.handleOnLongPressRelease()
            },
        )
    }

    val keyboardEventHandler: (KeyEvent) -> Boolean = keyboardHandler@{ event ->
        if (!keyboardInteractionEnabledState.value) return@keyboardHandler false
        keyboardController.handleKeyEvent(event)
    }

    SideEffect {
        onKeyboardEventHandlerChanged(keyboardEventHandler)
    }

    DisposableEffect(Unit) {
        onDispose {
            onKeyboardEventHandlerChanged { false }
        }
    }

    val longPressOverlayUiState = resolveLongPressOverlayUiState(
        isLongPressGestureInAction = tapGestureState.isLongPressGestureInAction,
        isDebugLongPressOverlayVisible = playerPreferences.isDebugLongPressOverlayVisible,
        longPressSpeed = tapGestureState.currentLongPressSpeed,
        shouldShowOverlay = shouldShowOverlay,
    )

    LaunchedEffect(tapGestureState.isLongPressGestureInAction, tapGestureState.longPressSpeedChangeCount) {
        if (!tapGestureState.isLongPressGestureInAction) {
            shouldShowOverlay = false
            return@LaunchedEffect
        }
        shouldShowOverlay = true
        delay(3.seconds)
        shouldShowOverlay = false
    }

    LaunchedEffect(longPressOverlayUiState != null) {
        if (longPressOverlayUiState == null) {
            longPressOverlayAnimationStep = 0
            return@LaunchedEffect
        }
        while (true) {
            longPressOverlayAnimationStep = 0
            delay(120)
            longPressOverlayAnimationStep = 1
            delay(120)
            longPressOverlayAnimationStep = 2
            delay(120)
            longPressOverlayAnimationStep = 3
            delay(320)
        }
    }

    CompositionLocalProvider(
        LocalControlsVisibilityState provides controlsVisibilityState,
        LocalPlayerIconStyle provides playerPreferences.playerIconStyle,
    ) {
        Box {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                if (isAmbienceModeEnabled) {
                    AmbienceBackground(artworkData = metadataState.artworkData)
                }
                
                PlayerContentFrame(
                    player = player,
                    pictureInPictureState = pictureInPictureState,
                    controlsVisibilityState = controlsVisibilityState,
                    tapGestureState = tapGestureState,
                    seekGestureState = seekGestureState,
                    videoZoomAndContentScaleState = videoZoomAndContentScaleState,
                    volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                    subtitleConfiguration = SubtitleConfiguration(
                        shouldUseSystemCaptionStyle = playerPreferences.shouldUseSystemCaptionStyle,
                        shouldShowBackground = playerPreferences.shouldShowSubtitleBackground,
                        font = playerPreferences.subtitleFont,
                        textSize = playerPreferences.subtitleTextSize,
                        shouldUseBoldText = playerPreferences.shouldUseBoldSubtitleText,
                        color = playerPreferences.subtitleColor,
                        edgeStyle = playerPreferences.subtitleEdgeStyle,
                        bottomPaddingFraction = playerPreferences.subtitleBottomPaddingFraction,
                        shouldApplyEmbeddedStyles = playerPreferences.shouldApplyEmbeddedStyles,
                        externalSubtitleFontSource = externalSubtitleFontSource,
                    ),
                    decoderPriority = playerPreferences.decoderPriority,
                )

                if (mediaPresentationState.isBuffering && mediaPresentationState.hasRenderedFirstFrame) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(72.dp),
                    )
                }
                DoubleTapIndicator(tapGestureState = tapGestureState)

                ExoPlayerControls(
                    player = player,
                    viewModel = viewModel,
                    controlsVisibilityState = controlsVisibilityState,
                    metadataState = metadataState,
                    mediaPresentationState = mediaPresentationState,
                    seekGestureState = seekGestureState,
                    videoZoomAndContentScaleState = videoZoomAndContentScaleState,
                    playerPreferences = playerPreferences,
                    onBackClick = onBackClick,
                    onOpenOverlay = ::openOverlayPanel,
                    onScreenshotClick = onScreenshotClick,
                    onPlayInBackgroundClick = onPlayInBackgroundClick,
                    onRotateClick = { rotationState.rotate() },
                    onPipClick = {
                        if (!pictureInPictureState.hasPipPermission) {
                            Toast.makeText(context, R.string.enable_pip_from_settings, Toast.LENGTH_SHORT).show()
                            pictureInPictureState.openPictureInPictureSettings()
                        } else {
                            pictureInPictureState.enterPictureInPictureMode()
                        }
                    },
                    onAspectRatioClick = {
                        videoZoomAndContentScaleState.switchToNextVideoContentScale()
                        controlsVisibilityState.showControls()
                    },
                    onVideoFiltersClick = showVideoFilters,
                    onAmbienceModeClick = {
                        viewModel.updateAmbienceMode(!isAmbienceModeEnabled)
                        controlsVisibilityState.showControls()
                    },
                    isAmbienceModeEnabled = isAmbienceModeEnabled,
                    modifier = Modifier.fillMaxSize()
                )

                if (longPressOverlayUiState != null) {
                    val safeDrawingTopPadding = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
                    val longPressOverlayTopPadding = maxOf(
                        safeDrawingTopPadding,
                        pictureInPictureState.videoViewRect?.top?.let { with(LocalDensity.current) { it.toDp() } } ?: 0.dp,
                    ) + 16.dp
                    LongPressSpeedOverlay(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = longPressOverlayTopPadding)
                            .testTag("long_press_speed_overlay"),
                        speedText = longPressOverlayUiState.speedText,
                        animationStep = longPressOverlayAnimationStep,
                    )
                }

                val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .displayCutoutPadding()
                        .padding(top = systemBarsPadding.calculateTopPadding(), bottom = systemBarsPadding.calculateBottomPadding())
                        .padding(24.dp),
                ) {
                    AnimatedVisibility(
                        modifier = Modifier.align(Alignment.CenterStart),
                        visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.VOLUME,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        VerticalProgressView(
                            value = volumeState.volumePercentage,
                            maxValue = volumeState.maxVolumePercentage,
                            icon = NextIcons.VolumeUp,
                        )
                    }

                    AnimatedVisibility(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.BRIGHTNESS,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        VerticalProgressView(
                            value = brightnessState.brightnessPercentage,
                            icon = NextIcons.Brightness,
                        )
                    }
                }
            }

            if (isModern || menuRouteStack.isNotEmpty()) {
                val currentRoute = menuRouteStack.lastOrNull()
                val canGoBack = menuRouteStack.size > 1
                if (currentRoute != null) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .noRippleClickable { dismissOverlay() },
                    )
                }
                MenuOverlayView(
                    externalRoute = currentRoute,
                    title = titleForMenuRoute(currentRoute),
                    canGoBack = canGoBack,
                    onBack = {
                        if (canGoBack) popMenuRoute() else dismissOverlay()
                    },
                ) { route ->
                    when (route) {
                        MenuRoute.Root -> MenuRootContent(
                            isLockEnabled = controlsVisibilityState.isControlsLocked,
                            isPipSupported = pictureInPictureState.isPipSupported,
                            isTakingScreenshot = isTakingScreenshot,
                            isVideoFiltersEnabled = playerPreferences.shouldApplyVideoFilters,
                            isStatsEnabled = playerPreferences.statisticsPage == 1,
                            isAmbienceModeEnabled = isAmbienceModeEnabled,
                            isShuffleEnabled = player.shuffleModeEnabled,
                            onNavigate = ::navigateToMenuRoute,
                            onLockClick = {
                                controlsVisibilityState.showControls()
                                controlsVisibilityState.lockControls()
                                dismissOverlay()
                            },
                            onAmbienceClick = {
                                viewModel.updateAmbienceMode(!isAmbienceModeEnabled)
                            },
                            onPictureInPictureClick = {
                                if (!pictureInPictureState.hasPipPermission) {
                                    Toast.makeText(context, R.string.enable_pip_from_settings, Toast.LENGTH_SHORT).show()
                                    pictureInPictureState.openPictureInPictureSettings()
                                } else {
                                    pictureInPictureState.enterPictureInPictureMode()
                                }
                                dismissOverlay()
                            },
                            onScreenshotClick = {
                                onScreenshotClick()
                                dismissOverlay()
                            },
                            onPlayInBackgroundClick = {
                                onPlayInBackgroundClick()
                                dismissOverlay()
                            },
                            onLoopClick = {
                                player.repeatMode = when (player.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                dismissOverlay()
                            },
                            onShuffleClick = {
                                player.shuffleModeEnabled = !player.shuffleModeEnabled
                            },
                            onStatsClick = {
                                viewModel.updateStatisticsPage(if (playerPreferences.statisticsPage == 1) 0 else 1)
                            },
                            onVideoFiltersToggle = {
                                viewModel.toggleVideoFilters()
                            },
                        )
                        MenuRoute.Audio -> AudioTrackSelectorContent(
                            player = player,
                            onDismiss = ::dismissOverlay,
                        )
                        MenuRoute.Subtitle -> SubtitleSelectorContent(
                            player = player,
                            onSelectSubtitleClick = onSelectSubtitleClick,
                            onAddOnlineSubtitleClick = onAddOnlineSubtitleClick,
                            preferences = playerPreferences,
                            onPreferencesChange = viewModel::updateSubtitleStyle,
                            onEvent = viewModel::onSubtitleOptionEvent,
                            onDismiss = ::dismissOverlay,
                        )
                        MenuRoute.PlaybackSpeed -> PlaybackSpeedSelectorContent(player = player)
                        MenuRoute.VideoContentScale -> VideoContentScaleSelectorContent(
                            videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                            onVideoContentScaleChanged = {
                                videoZoomAndContentScaleState.onVideoContentScaleChanged(it)
                            },
                            onShowVideoFilters = null,
                            onDismiss = ::dismissOverlay,
                        )
                        MenuRoute.VideoFilters -> VideoFiltersPanel(
                            preferences = playerPreferences,
                            onDismissRequest = ::dismissOverlay,
                            onPreviewPreferences = { previewPreferences ->
                                (player as? androidx.media3.session.MediaController)?.previewVideoFilters(previewPreferences)
                            },
                            onConfirmPreferences = viewModel::updateVideoFilters,
                        )
                        MenuRoute.Playlist -> PlaylistContent(
                            isVisible = true,
                            player = player,
                        )
                        MenuRoute.SleepTimer -> SleepTimerSelectorContent(
                            sleepTimerState = sleepTimerState,
                            onDismiss = ::dismissOverlay,
                        )
                        MenuRoute.Decoder -> DecoderPrioritySelectorContent(
                            currentDecoderPriority = playerPreferences.decoderPriority,
                            onDecoderPriorityClick = {
                                viewModel.updateDecoderPriority(it)
                                dismissOverlay()
                            },
                            onDismiss = ::dismissOverlay,
                        )
                    }
                }
            } else {
                OverlayShowView(
                    player = player,
                    overlayView = overlayView,
                    videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                    playerPreferences = playerPreferences,
                    sleepTimerState = sleepTimerState,
                    onDismiss = ::dismissOverlay,
                    onSelectSubtitleClick = onSelectSubtitleClick,
                    onAddOnlineSubtitleClick = onAddOnlineSubtitleClick,
                    onSubtitleOptionEvent = viewModel::onSubtitleOptionEvent,
                    onSubtitleStyleChanged = viewModel::updateSubtitleStyle,
                    onVideoContentScaleChanged = { videoZoomAndContentScaleState.onVideoContentScaleChanged(it) },
                    onPreviewVideoFilters = { previewPreferences ->
                        (player as? androidx.media3.session.MediaController)?.previewVideoFilters(previewPreferences)
                    },
                    onConfirmVideoFilters = viewModel::updateVideoFilters,
                    onCloseVideoFilters = { overlayView = null },
                    onShowVideoFilters = {
                        overlayView = null
                        showVideoFilters()
                    },
                    onDecoderPriorityChanged = {
                        viewModel.updateDecoderPriority(it)
                        dismissOverlay()
                    },
                )
            }
        }
    }

    errorState.error?.let { error ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(text = stringResource(R.string.error_playing_video)) },
            text = { Text(text = error.message ?: stringResource(R.string.unknown_error)) },
            confirmButton = {
                if (player.hasNextMediaItem()) {
                    TextButton(
                        onClick = {
                            errorState.dismiss()
                            player.seekToNext()
                            player.play()
                        },
                    ) { Text(text = stringResource(R.string.play_next_video)) }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        errorState.dismiss()
                        onBackClick()
                    },
                ) { Text(text = stringResource(R.string.exo_exit)) }
            },
        )
    }

    BackHandler {
        when {
            menuRouteStack.size > 1 -> popMenuRoute()
            menuRouteStack.isNotEmpty() -> dismissOverlay()
            overlayView != null -> dismissOverlay()
            else -> onBackClick()
        }
    }
}

@Composable
private fun titleForMenuRoute(route: MenuRoute?): String = when (route) {
    null, MenuRoute.Root -> stringResource(R.string.menu)
    MenuRoute.Audio -> stringResource(R.string.select_audio_track)
    MenuRoute.Subtitle -> stringResource(R.string.select_subtitle_track)
    MenuRoute.PlaybackSpeed -> stringResource(R.string.select_playback_speed)
    MenuRoute.VideoContentScale -> stringResource(R.string.video_zoom)
    MenuRoute.VideoFilters -> stringResource(R.string.video_filters)
    MenuRoute.Playlist -> stringResource(R.string.now_playing)
    MenuRoute.SleepTimer -> stringResource(R.string.exo_sleep_timer)
    MenuRoute.Decoder -> stringResource(R.string.decoder_priority)
}

@Composable
private fun AmbienceBackground(
    artworkData: ByteArray?,
    modifier: Modifier = Modifier,
) {
    artworkData ?: return
    val imageBitmap = remember(artworkData) {
        runCatching {
            BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size).asImageBitmap()
        }.getOrNull()
    } ?: return

    Image(
        bitmap = imageBitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize().blur(48.dp),
        alpha = 0.9f,
    )
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)),
    )
}

@Composable
private fun LongPressSpeedOverlay(
    speedText: String,
    animationStep: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(color = Color.Black.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LongPressSpeedIndicator(animationStep = animationStep)
        Text(
            text = speedText,
            modifier = Modifier.testTag("long_press_speed_text"),
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                shadow = Shadow(color = Color.Black.copy(alpha = 0.10f), offset = Offset(0f, 1f), blurRadius = 2f),
            ),
            color = Color.White,
        )
    }
}

@Composable
private fun LongPressSpeedIndicator(
    animationStep: Int,
    modifier: Modifier = Modifier,
) {
    val alpha1 by animateFloatAsState(targetValue = if (animationStep >= 1) 1f else 0f, label = "lp_1")
    val alpha2 by animateFloatAsState(targetValue = if (animationStep >= 2) 1f else 0f, label = "lp_2")
    val alpha3 by animateFloatAsState(targetValue = if (animationStep >= 3) 1f else 0f, label = "lp_3")

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy((-1).dp), verticalAlignment = Alignment.CenterVertically) {
        LongPressSpeedArrow(alpha = alpha1)
        LongPressSpeedArrow(alpha = alpha2)
        LongPressSpeedArrow(alpha = alpha3)
    }
}

@Composable
private fun LongPressSpeedArrow(alpha: Float) {
    Icon(
        imageVector = NextIcons.Play,
        contentDescription = null,
        modifier = Modifier.size(11.dp),
        tint = Color.White.copy(alpha = alpha),
    )
}
