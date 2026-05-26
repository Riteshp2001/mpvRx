package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.media3.common.Player
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.feature.player.PlayerViewModel
import app.gyrolet.mpvrx.exoplayer.feature.player.state.ControlsVisibilityState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.MediaPresentationState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SeekGestureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.VideoZoomAndContentScaleState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.MetadataState
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.controls.components.SeekbarWithTimers
import app.gyrolet.mpvrx.ui.player.controls.components.ControlsButton
import app.gyrolet.mpvrx.ui.player.controls.components.AnimatedPlayPauseIcon
import app.gyrolet.mpvrx.ui.player.controls.components.SlideToUnlock
import app.gyrolet.mpvrx.ui.player.controls.playerControlsExitAnimationSpec
import app.gyrolet.mpvrx.ui.player.controls.playerControlsEnterAnimationSpec
import app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.icons.Icon as AppSymbolIcon
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.theme.controlColor
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.exoplayer.feature.player.buttons.NextButton
import app.gyrolet.mpvrx.exoplayer.feature.player.buttons.PreviousButton

@Composable
fun ExoPlayerControls(
    player: Player,
    viewModel: PlayerViewModel,
    controlsVisibilityState: ControlsVisibilityState,
    metadataState: MetadataState,
    mediaPresentationState: MediaPresentationState,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    playerPreferences: PlayerPreferences,
    onBackClick: () -> Unit,
    onOpenOverlay: (OverlayView) -> Unit,
    onScreenshotClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
    onRotateClick: () -> Unit,
    onPipClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onVideoFiltersClick: () -> Unit,
    onAmbienceModeClick: () -> Unit,
    isAmbienceModeEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val appearancePreferences = koinInject<AppearancePreferences>()
    val spacing = MaterialTheme.spacing
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    
    val hideBackground by appearancePreferences.hidePlayerButtonsBackground.collectAsState()
    val topRightControlsPref by appearancePreferences.topRightControls.collectAsState()
    val bottomRightControlsPref by appearancePreferences.bottomRightControls.collectAsState()
    val bottomLeftControlsPref by appearancePreferences.bottomLeftControls.collectAsState()
    val portraitBottomControlsPref by appearancePreferences.portraitBottomControls.collectAsState()

    val (topRightButtons, bottomRightButtons, bottomLeftButtons) =
        remember(topRightControlsPref, bottomRightControlsPref, bottomLeftControlsPref) {
            val usedButtons = mutableSetOf<PlayerButton>()
            val topR = appearancePreferences.parseButtons(topRightControlsPref, usedButtons)
            val bottomR = appearancePreferences.parseButtons(bottomRightControlsPref, usedButtons)
            val bottomL = appearancePreferences.parseButtons(bottomLeftControlsPref, usedButtons)
            Triple(topR, bottomR, bottomL)
        }

    val portraitBottomButtons = remember(portraitBottomControlsPref) {
        val buttons = appearancePreferences.parseButtons(portraitBottomControlsPref, mutableSetOf())
        // Ensure stats button is always present in portrait
        if (buttons.none { it == PlayerButton.TIME_NETWORK }) {
            buttons + PlayerButton.TIME_NETWORK
        } else buttons
    }

    // Ensure stats button is available in landscape (add to bottom-right if not in any group)
    val hasStatsInLandscape = remember(topRightButtons, bottomRightButtons, bottomLeftButtons) {
        topRightButtons.contains(PlayerButton.TIME_NETWORK) ||
            bottomRightButtons.contains(PlayerButton.TIME_NETWORK) ||
            bottomLeftButtons.contains(PlayerButton.TIME_NETWORK)
    }

    val transparentOverlay by animateFloatAsState(
        if (controlsVisibilityState.isControlsVisible && !controlsVisibilityState.isControlsLocked) 0.8f else 0f,
        animationSpec = playerControlsExitAnimationSpec(),
        label = "controls_transparent_overlay",
    )

    Box(modifier = modifier.fillMaxSize()) {
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        Pair(0f, Color.Black),
                        Pair(0.4f, Color.Transparent),
                        Pair(0.6f, Color.Transparent),
                        Pair(1f, Color.Black),
                    ),
                    alpha = transparentOverlay,
                )
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        ) {
            val (topLeft, topRight, centerControls, bottomControls, seekbarRef, unlockBtn) = createRefs()

            // Top Left
            AnimatedVisibility(
                visible = controlsVisibilityState.isControlsVisible && !controlsVisibilityState.isControlsLocked,
                enter = fadeIn(playerControlsEnterAnimationSpec()),
                exit = fadeOut(playerControlsExitAnimationSpec()),
                modifier = Modifier.constrainAs(topLeft) {
                    top.linkTo(parent.top, spacing.medium)
                    start.linkTo(parent.start, spacing.large)
                    end.linkTo(topRight.start, spacing.medium)
                    width = Dimension.fillToConstraints
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.small)
                ) {
                    ControlsButton(
                        icon = Icons.Default.ArrowBack,
                        onClick = onBackClick,
                        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(45.dp)
                    )
                    
                    Surface(
                        shape = CircleShape,
                        color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                        border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.height(45.dp).clickable { onOpenOverlay(OverlayView.PLAYLIST) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = metadataState.title ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }
            }

            // Top Right
            AnimatedVisibility(
                visible = controlsVisibilityState.isControlsVisible && !controlsVisibilityState.isControlsLocked && !isPortrait,
                enter = fadeIn(playerControlsEnterAnimationSpec()),
                exit = fadeOut(playerControlsExitAnimationSpec()),
                modifier = Modifier.constrainAs(topRight) {
                    top.linkTo(parent.top, spacing.medium)
                    start.linkTo(topLeft.end, spacing.medium)
                    end.linkTo(parent.end, spacing.large)
                    width = Dimension.preferredWrapContent
                }
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.extraSmall)
                ) {
                    topRightButtons.forEach { button ->
                        RenderExoPlayerButton(
                            button = button,
                            player = player,
                            viewModel = viewModel,
                            controlsVisibilityState = controlsVisibilityState,
                            mediaPresentationState = mediaPresentationState,
                            onOpenOverlay = onOpenOverlay,
                            onScreenshotClick = onScreenshotClick,
                            onPlayInBackgroundClick = onPlayInBackgroundClick,
                            onRotateClick = onRotateClick,
                            onPipClick = onPipClick,
                            onAspectRatioClick = onAspectRatioClick,
                            onVideoFiltersClick = onVideoFiltersClick,
                            onAmbienceModeClick = onAmbienceModeClick,
                            isAmbienceModeEnabled = isAmbienceModeEnabled,
                            hideBackground = hideBackground,
                            playerPreferences = playerPreferences
                        )
                    }
                }
            }

            // Center Controls
            AnimatedVisibility(
                visible = controlsVisibilityState.isControlsVisible && !controlsVisibilityState.isControlsLocked,
                enter = fadeIn(playerControlsEnterAnimationSpec()),
                exit = fadeOut(playerControlsExitAnimationSpec()),
                modifier = Modifier.constrainAs(centerControls) {
                    if (isPortrait) {
                        bottom.linkTo(bottomControls.top, spacing.medium)
                    } else {
                        centerTo(parent)
                    }
                }
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PreviousButton(player = player)
                    
                    Surface(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = { if (player.isPlaying) player.pause() else player.play() }
                            ),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                        contentColor = Color.White,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        AnimatedPlayPauseIcon(
                            isPlaying = mediaPresentationState.isPlaying,
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        )
                    }

                    NextButton(player = player)
                }
            }

            // Seekbar
            AnimatedVisibility(
                visible = controlsVisibilityState.isControlsVisible && !controlsVisibilityState.isControlsLocked,
                enter = fadeIn(playerControlsEnterAnimationSpec()),
                exit = fadeOut(playerControlsExitAnimationSpec()),
                modifier = Modifier.constrainAs(seekbarRef) {
                    if (isPortrait) {
                        bottom.linkTo(centerControls.top, spacing.large)
                    } else {
                        bottom.linkTo(bottomControls.top, spacing.medium)
                    }
                    start.linkTo(parent.start, spacing.large)
                    end.linkTo(parent.end, spacing.large)
                }
            ) {
                SeekbarWithTimers(
                    position = (seekGestureState.pendingSeekPosition ?: mediaPresentationState.position).toFloat(),
                    duration = mediaPresentationState.duration.toFloat(),
                    onValueChange = { seekGestureState.onSeek(it.toLong()) },
                    onValueChangeFinished = { seekGestureState.onSeekEnd() },
                    timersInverted = Pair(false, false),
                    positionTimerOnClick = {},
                    durationTimerOnCLick = {},
                    chapters = persistentListOf(),
                    skipSegments = persistentListOf(),
                    paused = !mediaPresentationState.isPlaying,
                    isPortrait = isPortrait
                )
            }

            // Bottom Controls
            AnimatedVisibility(
                visible = controlsVisibilityState.isControlsVisible && !controlsVisibilityState.isControlsLocked,
                enter = fadeIn(playerControlsEnterAnimationSpec()),
                exit = fadeOut(playerControlsExitAnimationSpec()),
                modifier = Modifier.constrainAs(bottomControls) {
                    bottom.linkTo(parent.bottom, spacing.large)
                    start.linkTo(parent.start, spacing.large)
                    end.linkTo(parent.end, spacing.large)
                    width = Dimension.fillToConstraints
                }
            ) {
                if (isPortrait) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.medium, Alignment.CenterHorizontally)
                    ) {
                        portraitBottomButtons.forEach { button ->
                            RenderExoPlayerButton(
                                button = button,
                                player = player,
                                viewModel = viewModel,
                                controlsVisibilityState = controlsVisibilityState,
                                mediaPresentationState = mediaPresentationState,
                                onOpenOverlay = onOpenOverlay,
                                onScreenshotClick = onScreenshotClick,
                                onPlayInBackgroundClick = onPlayInBackgroundClick,
                                onRotateClick = onRotateClick,
                                onPipClick = onPipClick,
                                onAspectRatioClick = onAspectRatioClick,
                                onVideoFiltersClick = onVideoFiltersClick,
                                onAmbienceModeClick = onAmbienceModeClick,
                                isAmbienceModeEnabled = isAmbienceModeEnabled,
                                hideBackground = hideBackground,
                                playerPreferences = playerPreferences
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.extraSmall)
                        ) {
                            bottomLeftButtons.forEach { button ->
                                RenderExoPlayerButton(
                                    button = button,
                                    player = player,
                                    viewModel = viewModel,
                                    controlsVisibilityState = controlsVisibilityState,
                                    mediaPresentationState = mediaPresentationState,
                                    onOpenOverlay = onOpenOverlay,
                                    onScreenshotClick = onScreenshotClick,
                                    onPlayInBackgroundClick = onPlayInBackgroundClick,
                                    onRotateClick = onRotateClick,
                                    onPipClick = onPipClick,
                                    onAspectRatioClick = onAspectRatioClick,
                                    onVideoFiltersClick = onVideoFiltersClick,
                                    onAmbienceModeClick = onAmbienceModeClick,
                                    isAmbienceModeEnabled = isAmbienceModeEnabled,
                                    hideBackground = hideBackground,
                                    playerPreferences = playerPreferences
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.extraSmall)
                        ) {
                            bottomRightButtons.forEach { button ->
                                RenderExoPlayerButton(
                                    button = button,
                                    player = player,
                                    viewModel = viewModel,
                                    controlsVisibilityState = controlsVisibilityState,
                                    mediaPresentationState = mediaPresentationState,
                                    onOpenOverlay = onOpenOverlay,
                                    onScreenshotClick = onScreenshotClick,
                                    onPlayInBackgroundClick = onPlayInBackgroundClick,
                                    onRotateClick = onRotateClick,
                                    onPipClick = onPipClick,
                                    onAspectRatioClick = onAspectRatioClick,
                                    onVideoFiltersClick = onVideoFiltersClick,
                                    onAmbienceModeClick = onAmbienceModeClick,
                                    isAmbienceModeEnabled = isAmbienceModeEnabled,
                                    hideBackground = hideBackground,
                                    playerPreferences = playerPreferences
                                )
                            }
                            // Guaranteed stats button if not in any landscape group
                            if (!hasStatsInLandscape) {
                                RenderExoPlayerButton(
                                    button = PlayerButton.TIME_NETWORK,
                                    player = player,
                                    viewModel = viewModel,
                                    controlsVisibilityState = controlsVisibilityState,
                                    mediaPresentationState = mediaPresentationState,
                                    onOpenOverlay = onOpenOverlay,
                                    onScreenshotClick = onScreenshotClick,
                                    onPlayInBackgroundClick = onPlayInBackgroundClick,
                                    onRotateClick = onRotateClick,
                                    onPipClick = onPipClick,
                                    onAspectRatioClick = onAspectRatioClick,
                                    onVideoFiltersClick = onVideoFiltersClick,
                                    onAmbienceModeClick = onAmbienceModeClick,
                                    isAmbienceModeEnabled = isAmbienceModeEnabled,
                                    hideBackground = hideBackground,
                                    playerPreferences = playerPreferences
                                )
                            }
                        }
                    }
                }
            }

            // Unlock Button
            AnimatedVisibility(
                visible = controlsVisibilityState.isControlsVisible && controlsVisibilityState.isControlsLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.constrainAs(unlockBtn) {
                    bottom.linkTo(parent.bottom, spacing.extraLarge)
                    centerHorizontallyTo(parent)
                }
            ) {
                SlideToUnlock(onUnlock = { controlsVisibilityState.unlockControls() })
            }
        }

        // Stats Overlay
        DeviceStatsOverlay(
            visible = playerPreferences.statisticsPage == 1,
            player = player,
            onDismiss = { viewModel.updateStatisticsPage(0) },
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        )
    }
}

@Composable
fun RenderExoPlayerButton(
    button: PlayerButton,
    player: Player,
    viewModel: PlayerViewModel,
    controlsVisibilityState: ControlsVisibilityState,
    mediaPresentationState: MediaPresentationState,
    onOpenOverlay: (OverlayView) -> Unit,
    onScreenshotClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
    onRotateClick: () -> Unit,
    onPipClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onVideoFiltersClick: () -> Unit,
    onAmbienceModeClick: () -> Unit,
    isAmbienceModeEnabled: Boolean,
    hideBackground: Boolean,
    playerPreferences: PlayerPreferences,
) {
    val color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface

    when (button) {
        PlayerButton.PLAYBACK_SPEED -> {
            ControlsButton(icon = Icons.Default.Speed, onClick = { onOpenOverlay(OverlayView.PLAYBACK_SPEED) }, color = color)
        }
        PlayerButton.DECODER -> {
            ControlsButton(icon = Icons.Default.DeveloperBoard, onClick = { onOpenOverlay(OverlayView.DECODER_PRIORITY) }, color = color)
        }
        PlayerButton.SCREEN_ROTATION -> {
            ControlsButton(icon = Icons.Default.ScreenRotation, onClick = onRotateClick, color = color)
        }
        PlayerButton.FRAME_NAVIGATION -> {
            ControlsButton(icon = Icons.Default.Screenshot, onClick = onScreenshotClick, color = color)
        }
        PlayerButton.VIDEO_ZOOM -> {
            ControlsButton(icon = Icons.Default.ZoomIn, onClick = { onOpenOverlay(OverlayView.VIDEO_CONTENT_SCALE) }, color = color)
        }
        PlayerButton.PICTURE_IN_PICTURE -> {
            ControlsButton(icon = Icons.Default.PictureInPictureAlt, onClick = onPipClick, color = color)
        }
        PlayerButton.ASPECT_RATIO -> {
            ControlsButton(icon = Icons.Default.AspectRatio, onClick = onAspectRatioClick, color = color)
        }
        PlayerButton.LOCK_CONTROLS -> {
            ControlsButton(icon = Icons.Default.LockOpen, onClick = { controlsVisibilityState.lockControls() }, color = color)
        }
        PlayerButton.AUDIO_TRACK -> {
            ControlsButton(icon = Icons.Default.Audiotrack, onClick = { onOpenOverlay(OverlayView.AUDIO_SELECTOR) }, color = color)
        }
        PlayerButton.SUBTITLES -> {
            ControlsButton(icon = Icons.Default.Subtitles, onClick = { onOpenOverlay(OverlayView.SUBTITLE_SELECTOR) }, color = color)
        }
        PlayerButton.REPEAT_MODE -> {
            val icon = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Icons.Filled.Repeat
                Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                else -> Icons.Filled.Repeat
            }
            ControlsButton(icon = icon, onClick = {
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
            }, color = color)
        }
        PlayerButton.SHUFFLE -> {
            ControlsButton(icon = if (player.shuffleModeEnabled) Icons.Default.ShuffleOn else Icons.Default.Shuffle, onClick = {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }, color = color)
        }
        PlayerButton.BACKGROUND_PLAYBACK -> {
            ControlsButton(icon = Icons.Default.Headset, onClick = onPlayInBackgroundClick, color = color)
        }
        PlayerButton.AMBIENT_MODE -> {
            ControlsButton(icon = if (isAmbienceModeEnabled) Icons.Default.BlurOn else Icons.Default.BlurOff, onClick = onAmbienceModeClick, color = if (isAmbienceModeEnabled) MaterialTheme.colorScheme.primary else color)
        }
        PlayerButton.TIME_NETWORK -> {
            ControlsButton(icon = Icons.Default.AccessTime, onClick = {
                viewModel.updateStatisticsPage(if (playerPreferences.statisticsPage == 1) 0 else 1)
            }, color = if (playerPreferences.statisticsPage == 1) MaterialTheme.colorScheme.primary else color)
        }
        PlayerButton.VIDEO_FILTERS -> {
            ControlsButton(icon = Icons.Default.Tune, onClick = onVideoFiltersClick, color = color)
        }
        PlayerButton.MORE_OPTIONS -> {
            ControlsButton(icon = Icons.Default.MoreVert, onClick = onVideoFiltersClick, onLongClick = { onOpenOverlay(OverlayView.VIDEO_FILTERS) }, color = color)
        }
        PlayerButton.HDR_MODE -> {
            ControlsButton(icon = Icons.Default.HdrOn, onClick = {}, color = color)
        }
        PlayerButton.MIRROR -> {
            ControlsButton(icon = Icons.Default.Flip, onClick = {}, color = color)
        }
        PlayerButton.VERTICAL_FLIP -> {
            ControlsButton(icon = Icons.Default.Flip, onClick = {}, color = color) // Need a vertical flip icon ideally
        }
        PlayerButton.AB_LOOP -> {
            ControlsButton(icon = Icons.Default.Repeat, onClick = {}, color = color)
        }
        PlayerButton.CUSTOM_SKIP -> {
            ControlsButton(icon = Icons.Default.FastForward, onClick = {}, color = color)
        }
        PlayerButton.BOOKMARKS_CHAPTERS -> {
            ControlsButton(icon = Icons.Default.Bookmarks, onClick = { onOpenOverlay(OverlayView.PLAYLIST) }, color = color)
        }
        PlayerButton.CURRENT_CHAPTER -> {
            ControlsButton(icon = Icons.Default.Bookmarks, onClick = { onOpenOverlay(OverlayView.PLAYLIST) }, color = color)
        }
        else -> {}
    }
}
