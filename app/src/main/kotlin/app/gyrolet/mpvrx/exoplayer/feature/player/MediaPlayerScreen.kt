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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.data.repository.ExternalSubtitleFontSource
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControl
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlZone
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlsLayout
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlsStyle
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerIconStyle
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.feature.player.buttons.NextButton
import app.gyrolet.mpvrx.exoplayer.feature.player.buttons.PlayPauseButton
import app.gyrolet.mpvrx.exoplayer.feature.player.buttons.PlayerButton
import app.gyrolet.mpvrx.exoplayer.feature.player.buttons.PreviousButton
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.noRippleClickable
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.seekByRequestedOffset
import app.gyrolet.mpvrx.exoplayer.feature.player.input.PlayerKeyboardController
import app.gyrolet.mpvrx.exoplayer.feature.player.service.previewVideoFilters
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
import app.gyrolet.mpvrx.exoplayer.feature.player.state.seekAmountFormatted
import app.gyrolet.mpvrx.exoplayer.feature.player.state.seekToPositionFormated
import app.gyrolet.mpvrx.exoplayer.feature.player.dropControl
import app.gyrolet.mpvrx.exoplayer.feature.player.dropDraggedControl
import app.gyrolet.mpvrx.exoplayer.feature.player.previewReorder
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.AudioTrackSelectorContent
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.DecoderPrioritySelectorContent
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
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.controls.ControlsBottomModernView
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.controls.ControlsBottomView
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.controls.ControlsTopModernView
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.controls.ControlsTopView
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.controls.PlayerCustomizableControlButton
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.nameRes
import app.gyrolet.mpvrx.exoplayer.core.ui.extensions.copy

private const val TAG = "MediaPlayerScreen"

val LocalControlsVisibilityState = compositionLocalOf<ControlsVisibilityState?> { null }
val LocalPlayerIconStyle = compositionLocalOf { PlayerIconStyle.TONAL }

internal data class LongPressOverlayUiState(
    val speedText: String,
)

internal data class DraggingPlayerControlUiState(
    val control: PlayerControl,
    val sourceBounds: Rect,
    val dragOffset: Offset,
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
    var isCustomizingControls by remember { mutableStateOf(false) }
    var customizingHiddenPlayerControls by remember { mutableStateOf(playerPreferences.hiddenPlayerControls) }
    var customizingPlayerControlsLayout by remember { mutableStateOf(playerPreferences.playerControlsLayout) }
    var draggingPlayerControlUiState by remember { mutableStateOf<DraggingPlayerControlUiState?>(null) }
    var previewPlayerControlsLayout by remember { mutableStateOf<PlayerControlsLayout?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val shouldShowPlayerTitle = configuration.orientation != Configuration.ORIENTATION_PORTRAIT
    val sleepTimerState = rememberSleepTimerState(player = player)
    val permanentlyVisibleControls = remember {
        setOf(
            PlayerControl.BACK,
            PlayerControl.PREVIOUS,
            PlayerControl.PLAY_PAUSE,
            PlayerControl.NEXT,
            PlayerControl.ROTATE,
        )
    }
    val hiddenPlayerControls = when (isCustomizingControls) {
        true -> customizingHiddenPlayerControls
        false -> playerPreferences.hiddenPlayerControls
    }
    val playerControlsLayout = when {
        isCustomizingControls -> previewPlayerControlsLayout ?: customizingPlayerControlsLayout
        else -> playerPreferences.playerControlsLayout
    }
    val controlsByZone = remember(playerControlsLayout) {
        PlayerControlZone.entries.associateWith(playerControlsLayout::controlsIn)
    }
    val topRightControls = controlsByZone.getValue(PlayerControlZone.TOP_RIGHT)
    val bottomLeftControls = controlsByZone.getValue(PlayerControlZone.BOTTOM_LEFT)
    val visiblePlayerControls = remember(hiddenPlayerControls) {
        PlayerControl.entries.toSet() - hiddenPlayerControls
    }
    var shouldShowOverlay by remember { mutableStateOf(false) }
    var videoFiltersInitialPreferences by remember { mutableStateOf<PlayerPreferences?>(null) }
    var isAmbienceModeEnabled by remember { mutableStateOf(false) }
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
    fun closeVideoFiltersOverlay() {
        restoreVideoFiltersPreview()
        overlayView = null
        menuRouteStack = emptyList()
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
        overlayView == null &&
            menuRouteStack.isEmpty() &&
            !isCustomizingControls &&
            !controlsVisibilityState.isControlsLocked,
    )
    val seekIncrementState = rememberUpdatedState(playerPreferences.seekIncrement.seconds.inWholeMilliseconds)
    val currentPlayerState = rememberUpdatedState(player)
    val currentTapGestureState = rememberUpdatedState(tapGestureState)
    val currentControlsVisibilityState = rememberUpdatedState(controlsVisibilityState)
    val currentVolumeState = rememberUpdatedState(volumeState)
    val keyboardController = remember {
        PlayerKeyboardController(
            onSeekBackward = {
                Logger.debug(TAG, "Keyboard seek: offsetMs=${-seekIncrementState.value}")
                currentPlayerState.value.seekByRequestedOffset(-seekIncrementState.value)
                currentControlsVisibilityState.value.showControls()
            },
            onSeekForward = {
                Logger.debug(TAG, "Keyboard seek: offsetMs=${seekIncrementState.value}")
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
    val playerControlItemBounds = remember { mutableMapOf<PlayerControl, Rect>() }
    val playerControlZoneBounds = remember { mutableMapOf<PlayerControlZone, Rect>() }
    val longPressOverlayUiState = resolveLongPressOverlayUiState(
        isLongPressGestureInAction = tapGestureState.isLongPressGestureInAction,
        isDebugLongPressOverlayVisible = playerPreferences.isDebugLongPressOverlayVisible,
        longPressSpeed = tapGestureState.currentLongPressSpeed,
        shouldShowOverlay = shouldShowOverlay,
    )

    LaunchedEffect(
        playerPreferences.hiddenPlayerControls,
        playerPreferences.playerControlsLayout,
        isCustomizingControls,
    ) {
        if (!isCustomizingControls) {
            customizingHiddenPlayerControls = playerPreferences.hiddenPlayerControls - permanentlyVisibleControls
            customizingPlayerControlsLayout = playerPreferences.playerControlsLayout
        }
    }

    LaunchedEffect(
        tapGestureState.isLongPressGestureInAction,
        tapGestureState.longPressSpeedChangeCount,
    ) {
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

    SideEffect {
        onKeyboardEventHandlerChanged(keyboardEventHandler)
    }

    DisposableEffect(Unit) {
        onDispose {
            onKeyboardEventHandlerChanged { false }
        }
    }

    fun isControlVisible(control: PlayerControl): Boolean = control in permanentlyVisibleControls || isCustomizingControls || control !in hiddenPlayerControls

    fun isControlSelected(control: PlayerControl): Boolean = isCustomizingControls && control !in permanentlyVisibleControls && control !in hiddenPlayerControls

    fun toggleControlVisibility(control: PlayerControl) {
        val updatedControls = hiddenPlayerControls.toMutableSet().apply {
            if (!add(control)) remove(control)
        }
        if (isCustomizingControls) {
            customizingHiddenPlayerControls = updatedControls
            controlsVisibilityState.showControls(duration = kotlin.time.Duration.INFINITE)
        } else {
            controlsVisibilityState.showControls()
            viewModel.updatePlayerControlsCustomization(
                hiddenControls = updatedControls,
                layout = playerPreferences.playerControlsLayout,
            )
        }
    }

    fun startDraggingControl(control: PlayerControl) {
        if (!isCustomizingControls) return

        val sourceBounds = playerControlItemBounds[control] ?: return
        draggingPlayerControlUiState = DraggingPlayerControlUiState(
            control = control,
            sourceBounds = sourceBounds,
            dragOffset = Offset.Zero,
        )
        previewPlayerControlsLayout = customizingPlayerControlsLayout
        controlsVisibilityState.showControls(duration = kotlin.time.Duration.INFINITE)
    }

    fun moveDraggingControl(
        control: PlayerControl,
        dragOffset: Offset,
    ) {
        if (!isCustomizingControls) return
        val draggingState = draggingPlayerControlUiState ?: return
        if (draggingState.control != control) return

        draggingPlayerControlUiState = draggingState.copy(dragOffset = dragOffset)
        previewPlayerControlsLayout = customizingPlayerControlsLayout.previewReorder(
            control = control,
            dropPosition = draggingState.sourceBounds.center + dragOffset,
            itemBounds = playerControlItemBounds,
        )
        controlsVisibilityState.showControls(duration = kotlin.time.Duration.INFINITE)
    }

    fun clearDraggingControl() {
        draggingPlayerControlUiState = null
        previewPlayerControlsLayout = null
        controlsVisibilityState.showControls(duration = kotlin.time.Duration.INFINITE)
    }

    fun dropDraggedControl(
        control: PlayerControl,
        dragOffset: Offset,
    ) {
        if (!isCustomizingControls) return

        val dropPosition = draggingPlayerControlUiState
            ?.takeIf { it.control == control }
            ?.sourceBounds
            ?.center
            ?.plus(dragOffset)
        val updatedLayout = when (dropPosition) {
            null -> customizingPlayerControlsLayout.dropDraggedControl(
                control = control,
                dragOffset = dragOffset,
                itemBounds = playerControlItemBounds,
                zoneBounds = playerControlZoneBounds,
            )
            else -> customizingPlayerControlsLayout.dropControl(
                control = control,
                dropPosition = dropPosition,
                itemBounds = playerControlItemBounds,
                zoneBounds = playerControlZoneBounds,
            )
        }
        playerControlItemBounds.remove(control)
        customizingPlayerControlsLayout = updatedLayout
        clearDraggingControl()
        controlsVisibilityState.showControls(duration = kotlin.time.Duration.INFINITE)
    }

    fun enterControlCustomization() {
        player.pause()
        customizingHiddenPlayerControls = playerPreferences.hiddenPlayerControls - permanentlyVisibleControls
        customizingPlayerControlsLayout = playerPreferences.playerControlsLayout
        clearDraggingControl()
        isCustomizingControls = true
        controlsVisibilityState.showControls(duration = kotlin.time.Duration.INFINITE)
    }

    fun exitControlCustomization() {
        clearDraggingControl()
        isCustomizingControls = false
        controlsVisibilityState.showControls()
        viewModel.updatePlayerControlsCustomization(
            hiddenControls = customizingHiddenPlayerControls,
            layout = customizingPlayerControlsLayout,
        )
    }

    fun cancelControlCustomization() {
        clearDraggingControl()
        customizingHiddenPlayerControls = playerPreferences.hiddenPlayerControls - permanentlyVisibleControls
        customizingPlayerControlsLayout = playerPreferences.playerControlsLayout
        isCustomizingControls = false
        controlsVisibilityState.showControls()
    }

    fun handleDebugPlayerAction(action: String, extras: android.os.Bundle?): Boolean {
        if (isCustomizingControls && action != "ACTION_TOGGLE_CUSTOMIZE_CONTROLS") return false
        when (action) {
            "ACTION_BACK" -> onBackClick()
            "ACTION_ROTATE" -> rotationState.rotate()
            "ACTION_TOGGLE_AMBIENCE" -> {
                isAmbienceModeEnabled = !isAmbienceModeEnabled
                controlsVisibilityState.showControls()
            }
            "ACTION_SHOW_CONTROLS" -> controlsVisibilityState.showControls()
            "ACTION_HIDE_CONTROLS" -> controlsVisibilityState.hideControls()
            "ACTION_SHOW_PLAYLIST" -> openOverlayPanel(OverlayView.PLAYLIST)
            "ACTION_SHOW_SPEED" -> openOverlayPanel(OverlayView.PLAYBACK_SPEED)
            "ACTION_SHOW_AUDIO" -> openOverlayPanel(OverlayView.AUDIO_SELECTOR)
            "ACTION_SHOW_SUBTITLE" -> openOverlayPanel(OverlayView.SUBTITLE_SELECTOR)
            "ACTION_LOCK" -> {
                controlsVisibilityState.showControls()
                controlsVisibilityState.lockControls()
            }
            "ACTION_UNLOCK" -> {
                controlsVisibilityState.showControls()
                controlsVisibilityState.unlockControls()
            }
            "ACTION_TOGGLE_LOCK" -> {
                controlsVisibilityState.showControls()
                if (controlsVisibilityState.isControlsLocked) controlsVisibilityState.unlockControls() else controlsVisibilityState.lockControls()
            }
            "ACTION_CYCLE_SCALE" -> {
                videoZoomAndContentScaleState.switchToNextVideoContentScale()
                controlsVisibilityState.showControls()
            }
            "ACTION_SHOW_SCALE" -> openOverlayPanel(OverlayView.VIDEO_CONTENT_SCALE)
            "ACTION_SHOW_DECODER" -> openOverlayPanel(OverlayView.DECODER_PRIORITY)
            "ACTION_SHOW_VIDEO_FILTERS" -> showVideoFilters()
            "ACTION_PIP" -> {
                if (!pictureInPictureState.hasPipPermission) {
                    pictureInPictureState.openPictureInPictureSettings()
                } else {
                    pictureInPictureState.enterPictureInPictureMode()
                }
            }
            "ACTION_SCREENSHOT" -> onScreenshotClick()
            "ACTION_BACKGROUND" -> onPlayInBackgroundClick()
            "ACTION_SHOW_SLEEP_TIMER" -> openOverlayPanel(OverlayView.SLEEP_TIMER)
            "ACTION_SHOW_MENU" -> {
                if (isModern) {
                    controlsVisibilityState.hideControls()
                    menuRouteStack = listOf(MenuRoute.Root)
                }
            }
            "ACTION_MENU_BACK" -> {
                if (menuRouteStack.size > 1) {
                    popMenuRoute()
                } else {
                    dismissOverlay()
                }
            }
            "ACTION_TOGGLE_CUSTOMIZE_CONTROLS" -> {
                if (isModern) return false
                if (isCustomizingControls) exitControlCustomization() else enterControlCustomization()
            }
            else -> return false
        }
        return true
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
                val safeDrawingTopPadding = WindowInsets.safeDrawing
                    .asPaddingValues()
                    .calculateTopPadding()
                val longPressOverlayTopPadding = maxOf(
                    safeDrawingTopPadding,
                    pictureInPictureState.videoViewRect
                        ?.top
                        ?.let { with(LocalDensity.current) { it.toDp() } }
                        ?: 0.dp,
                ) + 16.dp
                PlayerContentFrame(
                    player = player,
                    pictureInPictureState = pictureInPictureState,
                    controlsVisibilityState = controlsVisibilityState,
                    tapGestureState = tapGestureState,
                    seekGestureState = seekGestureState,
                    videoZoomAndContentScaleState = videoZoomAndContentScaleState,
                    volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                    isGesturesEnabled = !isCustomizingControls,
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

                AnimatedVisibility(
                    visible = controlsVisibilityState.isControlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier = modifier
                            .fillMaxSize()
                            .background(
                                Color.Black.copy(
                                    alpha = when (isCustomizingControls) {
                                        true -> 0.75f
                                        false -> 0.3f
                                    },
                                ),
                            ),
                    )
                }

                if (mediaPresentationState.isBuffering && mediaPresentationState.hasRenderedFirstFrame) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(72.dp),
                    )
                }
                DoubleTapIndicator(tapGestureState = tapGestureState)

                if (longPressOverlayUiState != null) {
                    LongPressSpeedOverlay(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = longPressOverlayTopPadding)
                            .testTag("long_press_speed_overlay"),
                        speedText = longPressOverlayUiState.speedText,
                        animationStep = longPressOverlayAnimationStep,
                    )
                }

                if (controlsVisibilityState.isControlsVisible && controlsVisibilityState.isControlsLocked) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(top = 24.dp),
                    ) {
                        PlayerButton(onClick = { controlsVisibilityState.unlockControls() }) {
                            Icon(
                                imageVector = NextIcons.Lock,
                                contentDescription = stringResource(R.string.controls_unlock),
                            )
                        }
                    }
                } else {
                    PlayerControlsView(
                        topView = {
                            AnimatedVisibility(
                                visible = controlsVisibilityState.isControlsVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                if (isModern) {
                                    ControlsTopModernView(
                                        title = (metadataState.title ?: "").takeIf { shouldShowPlayerTitle }.orEmpty(),
                                        onBackClick = { onBackClick() },
                                        onMenuClick = {
                                            controlsVisibilityState.hideControls()
                                            menuRouteStack = listOf(MenuRoute.Root)
                                        },
                                    )
                                } else {
                                    ControlsTopView(
                                        title = (metadataState.title ?: "").takeIf { shouldShowPlayerTitle }.orEmpty(),
                                        player = player,
                                        topRightControls = topRightControls,
                                        controlButtonsPosition = playerPreferences.controlButtonsPosition,
                                        visiblePlayerControls = visiblePlayerControls,
                                        videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                                        isPipSupported = pictureInPictureState.isPipSupported,
                                        isTakingScreenshot = isTakingScreenshot,
                                        itemBounds = playerControlItemBounds,
                                        zoneBounds = playerControlZoneBounds,
                                        isCustomizingControls = isCustomizingControls,
                                        shouldHideLabels = playerPreferences.shouldHidePlayerControlLabels,
                                        draggingControl = draggingPlayerControlUiState?.control,
                                        onControlDropDragged = ::dropDraggedControl,
                                        onControlDragStarted = ::startDraggingControl,
                                        onControlDragMoved = ::moveDraggingControl,
                                        onControlDragCancelled = { clearDraggingControl() },
                                        isBackVisible = isControlVisible(PlayerControl.BACK),
                                        isBackSelected = isControlSelected(PlayerControl.BACK),
                                        isBackInteractive = !isCustomizingControls,
                                        onAudioClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.AUDIO)
                                            } else {
                                                openOverlayPanel(OverlayView.AUDIO_SELECTOR)
                                            }
                                        },
                                        onSubtitleClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.SUBTITLE)
                                            } else {
                                                openOverlayPanel(OverlayView.SUBTITLE_SELECTOR)
                                            }
                                        },
                                        onPlaybackSpeedClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.PLAYBACK_SPEED)
                                            } else {
                                                openOverlayPanel(OverlayView.PLAYBACK_SPEED)
                                            }
                                        },
                                        onPlaylistClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.PLAYLIST)
                                            } else {
                                                openOverlayPanel(OverlayView.PLAYLIST)
                                            }
                                        },
                                        onBackClick = {
                                            if (!isCustomizingControls) {
                                                onBackClick()
                                            }
                                        },
                                        onSleepTimerClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.SLEEP_TIMER)
                                            } else {
                                                openOverlayPanel(OverlayView.SLEEP_TIMER)
                                            }
                                        },
                                        onLockControlsClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.LOCK)
                                            } else {
                                                controlsVisibilityState.showControls()
                                                controlsVisibilityState.lockControls()
                                            }
                                        },
                                        onVideoContentScaleClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.SCALE)
                                            } else {
                                                controlsVisibilityState.showControls()
                                                videoZoomAndContentScaleState.switchToNextVideoContentScale()
                                            }
                                        },
                                        onVideoContentScaleLongClick = {
                                            if (!isCustomizingControls) {
                                                openOverlayPanel(OverlayView.VIDEO_CONTENT_SCALE)
                                            }
                                        },
                                        onDecoderClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.DECODER)
                                            } else {
                                                openOverlayPanel(OverlayView.DECODER_PRIORITY)
                                            }
                                        },
                                        onAmbienceModeClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.AMBIENCE_MODE)
                                            } else {
                                                isAmbienceModeEnabled = !isAmbienceModeEnabled
                                                controlsVisibilityState.showControls()
                                            }
                                        },
                                        isAmbienceModeEnabled = isAmbienceModeEnabled,
                                        onVideoFiltersClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.VIDEO_FILTERS)
                                            } else {
                                                showVideoFilters()
                                            }
                                        },
                                        onPictureInPictureClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.PIP)
                                            } else if (!pictureInPictureState.hasPipPermission) {
                                                Toast.makeText(context, R.string.enable_pip_from_settings, Toast.LENGTH_SHORT).show()
                                                pictureInPictureState.openPictureInPictureSettings()
                                            } else {
                                                pictureInPictureState.enterPictureInPictureMode()
                                            }
                                        },
                                        onRotateClick = {
                                            rotationState.rotate()
                                        },
                                        onScreenshotClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.SCREENSHOT)
                                            } else {
                                                onScreenshotClick()
                                            }
                                        },
                                        onPlayInBackgroundClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.BACKGROUND_PLAY)
                                            } else {
                                                onPlayInBackgroundClick()
                                            }
                                        },
                                        onLoopClick = {
                                            toggleControlVisibility(PlayerControl.LOOP)
                                        }.takeIf { isCustomizingControls },
                                        onShuffleClick = {
                                            toggleControlVisibility(PlayerControl.SHUFFLE)
                                        }.takeIf { isCustomizingControls },
                                        sleepTimerState = sleepTimerState,
                                    )
                                }
                            }
                        },
                        middleView = {
                            when {
                                seekGestureState.seekAmount != null -> InfoView(info = "${seekGestureState.seekAmountFormatted}\n[${seekGestureState.seekToPositionFormated}]")
                                videoZoomAndContentScaleState.isZooming -> InfoView(info = "${(videoZoomAndContentScaleState.zoom * 100).toInt()}%")
                                videoZoomAndContentScaleState.shouldShowContentScaleIndicator -> InfoView(info = stringResource(videoZoomAndContentScaleState.videoContentScale.nameRes()))
                                !isModern && controlsVisibilityState.isControlsVisible -> ControlsMiddleView(
                                    player = player,
                                    isCustomizingControls = isCustomizingControls,
                                    isPreviousVisible = isControlVisible(PlayerControl.PREVIOUS),
                                    isPreviousSelected = isControlSelected(PlayerControl.PREVIOUS),
                                    isPlayPauseVisible = isControlVisible(PlayerControl.PLAY_PAUSE),
                                    isPlayPauseSelected = isControlSelected(PlayerControl.PLAY_PAUSE),
                                    isNextVisible = isControlVisible(PlayerControl.NEXT),
                                    isNextSelected = isControlSelected(PlayerControl.NEXT),
                                    onPreviousClick = { },
                                    onPlayPauseClick = { },
                                    onNextClick = { },
                                )
                                else -> Unit
                            }
                        },
                        bottomView = {
                            AnimatedVisibility(
                                visible = controlsVisibilityState.isControlsVisible && !controlsVisibilityState.isControlsLocked,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                if (isModern) {
                                    ControlsBottomModernView(
                                        mediaPresentationState = mediaPresentationState,
                                        pendingSeekPosition = seekGestureState.pendingSeekPosition,
                                        isPlaying = mediaPresentationState.isPlaying,
                                        hasPrevious = player.hasPreviousMediaItem(),
                                        hasNext = player.hasNextMediaItem(),
                                        onPlayPauseClick = {
                                            if (player.isPlaying) player.pause() else player.play()
                                        },
                                        onPreviousClick = { player.seekToPrevious() },
                                        onNextClick = { player.seekToNext() },
                                        onRotateClick = { rotationState.rotate() },
                                        onPlaylistClick = { openOverlayPanel(OverlayView.PLAYLIST) },
                                        onPlaybackSpeedClick = { openOverlayPanel(OverlayView.PLAYBACK_SPEED) },
                                        onSeek = seekGestureState::onSeek,
                                        onSeekEnd = seekGestureState::onSeekEnd,
                                    )
                                } else {
                                    ControlsBottomView(
                                        player = player,
                                        mediaPresentationState = mediaPresentationState,
                                        bottomLeftControls = bottomLeftControls,
                                        controlButtonsPosition = playerPreferences.controlButtonsPosition,
                                        videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                                        isPipSupported = pictureInPictureState.isPipSupported,
                                        pendingSeekPosition = seekGestureState.pendingSeekPosition,
                                        itemBounds = playerControlItemBounds,
                                        zoneBounds = playerControlZoneBounds,
                                        isCustomizingControls = isCustomizingControls,
                                        shouldHideLabels = playerPreferences.shouldHidePlayerControlLabels,
                                        draggingControl = draggingPlayerControlUiState?.control,
                                        onControlDropDragged = ::dropDraggedControl,
                                        onControlDragStarted = ::startDraggingControl,
                                        onControlDragMoved = ::moveDraggingControl,
                                        onControlDragCancelled = { clearDraggingControl() },
                                        visiblePlayerControls = visiblePlayerControls,
                                        onSeek = seekGestureState::onSeek,
                                        onSeekEnd = seekGestureState::onSeekEnd,
                                        onPlaylistClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.PLAYLIST)
                                            } else {
                                                openOverlayPanel(OverlayView.PLAYLIST)
                                            }
                                        },
                                        onPlaybackSpeedClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.PLAYBACK_SPEED)
                                            } else {
                                                openOverlayPanel(OverlayView.PLAYBACK_SPEED)
                                            }
                                        },
                                        onAudioClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.AUDIO)
                                            } else {
                                                openOverlayPanel(OverlayView.AUDIO_SELECTOR)
                                            }
                                        },
                                        onSubtitleClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.SUBTITLE)
                                            } else {
                                                openOverlayPanel(OverlayView.SUBTITLE_SELECTOR)
                                            }
                                        },
                                        onRotateClick = {
                                            rotationState.rotate()
                                        },
                                        onPlayInBackgroundClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.BACKGROUND_PLAY)
                                            } else {
                                                onPlayInBackgroundClick()
                                            }
                                        },
                                        isTakingScreenshot = isTakingScreenshot,
                                        onScreenshotClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.SCREENSHOT)
                                            } else {
                                                onScreenshotClick()
                                            }
                                        },
                                        onCustomizeControlsClick = {
                                            if (isCustomizingControls) {
                                                exitControlCustomization()
                                            } else {
                                                enterControlCustomization()
                                            }
                                        },
                                        onLoopClick = {
                                            toggleControlVisibility(PlayerControl.LOOP)
                                        }.takeIf { isCustomizingControls },
                                        onShuffleClick = {
                                            toggleControlVisibility(PlayerControl.SHUFFLE)
                                        }.takeIf { isCustomizingControls },
                                        onSleepTimerClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.SLEEP_TIMER)
                                            } else {
                                                openOverlayPanel(OverlayView.SLEEP_TIMER)
                                            }
                                        },
                                        sleepTimerState = sleepTimerState,
                                        onLockControlsClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.LOCK)
                                            } else {
                                                controlsVisibilityState.showControls()
                                                controlsVisibilityState.lockControls()
                                            }
                                        },
                                        onVideoContentScaleClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.SCALE)
                                            } else {
                                                controlsVisibilityState.showControls()
                                                videoZoomAndContentScaleState.switchToNextVideoContentScale()
                                            }
                                        },
                                        onVideoContentScaleLongClick = {
                                            if (!isCustomizingControls) {
                                                openOverlayPanel(OverlayView.VIDEO_CONTENT_SCALE)
                                            }
                                        },
                                        onDecoderClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.DECODER)
                                            } else {
                                                openOverlayPanel(OverlayView.DECODER_PRIORITY)
                                            }
                                        },
                                        onAmbienceModeClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.AMBIENCE_MODE)
                                            } else {
                                                isAmbienceModeEnabled = !isAmbienceModeEnabled
                                                controlsVisibilityState.showControls()
                                            }
                                        },
                                        isAmbienceModeEnabled = isAmbienceModeEnabled,
                                        onVideoFiltersClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.VIDEO_FILTERS)
                                            } else {
                                                showVideoFilters()
                                            }
                                        },
                                        onPictureInPictureClick = {
                                            if (isCustomizingControls) {
                                                toggleControlVisibility(PlayerControl.PIP)
                                            } else if (!pictureInPictureState.hasPipPermission) {
                                                Toast.makeText(context, R.string.enable_pip_from_settings, Toast.LENGTH_SHORT).show()
                                                pictureInPictureState.openPictureInPictureSettings()
                                            } else {
                                                pictureInPictureState.enterPictureInPictureMode()
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )

                    draggingPlayerControlUiState?.let { draggingState ->
                        Box(
                            modifier = Modifier
                                .offset {
                                    androidx.compose.ui.unit.IntOffset(
                                        x = (draggingState.sourceBounds.left + draggingState.dragOffset.x).toInt(),
                                        y = (draggingState.sourceBounds.top + draggingState.dragOffset.y).toInt(),
                                    )
                                }
                                .shadow(16.dp, RoundedCornerShape(16.dp)),
                        ) {
                            PlayerCustomizableControlButton(
                                control = draggingState.control,
                                player = player,
                                videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                                isPipSupported = pictureInPictureState.isPipSupported,
                                isCustomizingControls = true,
                                visiblePlayerControls = visiblePlayerControls,
                                onPlaylistClick = { },
                                onPlaybackSpeedClick = { },
                                onAudioClick = { },
                                onSubtitleClick = { },
                                onLockControlsClick = { },
                                onVideoContentScaleClick = { },
                                onVideoContentScaleLongClick = { },
                                onDecoderClick = { },
                                onAmbienceModeClick = { },
                                isAmbienceModeEnabled = isAmbienceModeEnabled,
                                onVideoFiltersClick = { },
                                onPictureInPictureClick = { },
                                onRotateClick = { },
                                isTakingScreenshot = isTakingScreenshot,
                                onScreenshotClick = { },
                                onPlayInBackgroundClick = { },
                                onLoopClick = { },
                                onShuffleClick = { },
                                onSleepTimerClick = { },
                            )
                        }
                    }
                }

                val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .displayCutoutPadding()
                        .padding(systemBarsPadding.copy(top = 0.dp, bottom = 0.dp))
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

                    AnimatedVisibility(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 132.dp),
                        visible = isCustomizingControls,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            FilledTonalButton(
                                modifier = Modifier.testTag("btn_customize_controls_confirm"),
                                onClick = ::exitControlCustomization,
                            ) {
                                Text(text = stringResource(R.string.exo_done))
                            }
                            TextButton(
                                modifier = Modifier.testTag("btn_customize_controls_cancel"),
                                onClick = ::cancelControlCustomization,
                            ) {
                                Text(text = stringResource(R.string.exo_cancel))
                            }
                        }
                    }
                }
            }

            if (isModern) {
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
                            onNavigate = ::navigateToMenuRoute,
                            onLockClick = {
                                controlsVisibilityState.showControls()
                                controlsVisibilityState.lockControls()
                                dismissOverlay()
                            },
                            onAmbienceClick = {
                                isAmbienceModeEnabled = !isAmbienceModeEnabled
                                dismissOverlay()
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
                                dismissOverlay()
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
                        MenuRoute.VideoFilters -> {
                            // Assuming VideoFiltersPanel is ported or replaced
                            Box(modifier = Modifier.fillMaxSize())
                        }
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
                    onCloseVideoFilters = ::closeVideoFiltersOverlay,
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
            title = {
                Text(text = stringResource(R.string.error_playing_video))
            },
            text = {
                Text(text = error.message ?: stringResource(R.string.unknown_error))
            },
            confirmButton = {
                if (player.hasNextMediaItem()) {
                    TextButton(
                        onClick = {
                            errorState.dismiss()
                            player.seekToNext()
                            player.play()
                        },
                    ) {
                        Text(text = stringResource(R.string.play_next_video))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        errorState.dismiss()
                        onBackClick()
                    },
                ) {
                    Text(text = stringResource(R.string.exo_exit))
                }
            },
        )
    }

    BackHandler {
        when {
            menuRouteStack.size > 1 -> popMenuRoute()
            menuRouteStack.isNotEmpty() -> dismissOverlay()
            overlayView != null -> dismissOverlay()
            isCustomizingControls -> cancelControlCustomization()
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
fun InfoView(
    modifier: Modifier = Modifier,
    info: String,
    textStyle: TextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = info,
            style = textStyle,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
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
        modifier = modifier
            .fillMaxSize()
            .blur(48.dp),
        alpha = 0.9f,
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f)),
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
            .background(
                color = Color.Black.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp),
            )
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
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.10f),
                    offset = Offset(0f, 1f),
                    blurRadius = 2f,
                ),
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
    val alpha1 by animateFloatAsState(
        targetValue = if (animationStep >= 1) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "long_press_arrow_1",
    )
    val alpha2 by animateFloatAsState(
        targetValue = if (animationStep >= 2) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "long_press_arrow_2",
    )
    val alpha3 by animateFloatAsState(
        targetValue = if (animationStep >= 3) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "long_press_arrow_3",
    )

    Row(
        modifier = modifier.testTag("long_press_speed_indicator"),
        horizontalArrangement = Arrangement.spacedBy((-1).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

@Composable
fun ControlsMiddleView(
    modifier: Modifier = Modifier,
    player: Player,
    isCustomizingControls: Boolean = false,
    isPreviousVisible: Boolean = true,
    isPreviousSelected: Boolean = false,
    isPlayPauseVisible: Boolean = true,
    isPlayPauseSelected: Boolean = false,
    isNextVisible: Boolean = true,
    isNextSelected: Boolean = false,
    onPreviousClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(40.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPreviousVisible) {
            if (isCustomizingControls) {
                PreviousButton(
                    player = player,
                    onClick = onPreviousClick,
                    isInteractive = false,
                )
            } else {
                PreviousButton(player = player)
            }
        }
        if (isPlayPauseVisible) {
            if (isCustomizingControls) {
                PlayPauseButton(
                    player = player,
                    onClick = onPlayPauseClick,
                    isInteractive = false,
                )
            } else {
                PlayPauseButton(player = player)
            }
        }
        if (isNextVisible) {
            if (isCustomizingControls) {
                NextButton(
                    player = player,
                    onClick = onNextClick,
                    isInteractive = false,
                )
            } else {
                NextButton(player = player)
            }
        }
    }
}

@Composable
fun PlayerControlsView(
    modifier: Modifier = Modifier,
    topView: @Composable () -> Unit,
    middleView: @Composable BoxScope.() -> Unit,
    bottomView: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            topView()
            Spacer(modifier = Modifier.weight(1f))
            bottomView()
        }

        middleView()
    }
}
