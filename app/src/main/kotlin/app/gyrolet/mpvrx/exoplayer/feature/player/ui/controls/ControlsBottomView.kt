package app.gyrolet.mpvrx.exoplayer.feature.player.ui.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.time.Duration.Companion.milliseconds
import app.gyrolet.mpvrx.exoplayer.core.model.ControlButtonsPosition
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControl
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlZone
import app.gyrolet.mpvrx.exoplayer.core.model.VideoContentScale
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.exoplayer.core.ui.extensions.copy
import app.gyrolet.mpvrx.exoplayer.feature.player.AnimatedPlayerControlPlacement
import app.gyrolet.mpvrx.exoplayer.feature.player.LocalControlsVisibilityState
import app.gyrolet.mpvrx.exoplayer.feature.player.buttons.PlayerButton
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.formatted
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.noRippleClickable
import app.gyrolet.mpvrx.exoplayer.feature.player.playerControlDragSource
import app.gyrolet.mpvrx.exoplayer.feature.player.playerControlZoneTarget
import app.gyrolet.mpvrx.exoplayer.feature.player.state.MediaPresentationState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SleepTimerState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.durationFormatted
import app.gyrolet.mpvrx.ui.icons.Icon

@OptIn(UnstableApi::class)
@Composable
fun ControlsBottomView(
    modifier: Modifier = Modifier,
    player: Player,
    mediaPresentationState: MediaPresentationState,
    bottomLeftControls: List<PlayerControl>,
    controlButtonsPosition: ControlButtonsPosition,
    videoContentScale: VideoContentScale,
    isPipSupported: Boolean,
    pendingSeekPosition: Long?,
    itemBounds: MutableMap<PlayerControl, Rect>,
    zoneBounds: MutableMap<PlayerControlZone, Rect>,
    onPlaylistClick: () -> Unit,
    onPlaybackSpeedClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onVideoContentScaleClick: () -> Unit,
    onVideoContentScaleLongClick: () -> Unit,
    onDecoderClick: () -> Unit,
    onAmbienceModeClick: () -> Unit,
    isAmbienceModeEnabled: Boolean,
    onVideoFiltersClick: () -> Unit,
    onLockControlsClick: () -> Unit,
    onPictureInPictureClick: () -> Unit,
    onRotateClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
    isTakingScreenshot: Boolean,
    onScreenshotClick: () -> Unit,
    onCustomizeControlsClick: () -> Unit,
    onLoopClick: (() -> Unit)? = null,
    onShuffleClick: (() -> Unit)? = null,
    onSleepTimerClick: (() -> Unit)? = null,
    sleepTimerState: SleepTimerState? = null,
    isCustomizingControls: Boolean,
    shouldHideLabels: Boolean,
    draggingControl: PlayerControl? = null,
    onControlDropDragged: (PlayerControl, Offset) -> Unit = { _, _ -> },
    onControlDragStarted: (PlayerControl) -> Unit = {},
    onControlDragMoved: (PlayerControl, Offset) -> Unit = { _, _ -> },
    onControlDragCancelled: (PlayerControl) -> Unit = {},
    visiblePlayerControls: Set<PlayerControl>,
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
) {
    val systemBarsPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val controlsVisibilityState = LocalControlsVisibilityState.current
    val displayedPosition = pendingSeekPosition ?: mediaPresentationState.position
    val displayedPendingPosition = (mediaPresentationState.duration - displayedPosition).coerceAtLeast(0L)

    Column(
        modifier = modifier
            .padding(systemBarsPadding.copy(top = 0.dp))
            .padding(horizontal = 8.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var shouldShowPendingPosition by rememberSaveable { mutableStateOf(false) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.noRippleClickable {
                    shouldShowPendingPosition = !shouldShowPendingPosition
                },
            ) {
                Text(
                    text = when (shouldShowPendingPosition) {
                        true -> "-${displayedPendingPosition.milliseconds.formatted()}"
                        false -> displayedPosition.milliseconds.formatted()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    text = mediaPresentationState.durationFormatted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            PlayerButton(
                buttonSize = 30.dp,
                onClick = onRotateClick,
                shouldShowSelectionBadge = false,
                shouldDimWhenUnselected = false,
                shouldShowCustomizeFrame = false,
            ) {
                Icon(
                    imageVector = NextIcons.Rotation,
                    contentDescription = "btn_rotate",
                    modifier = Modifier.size(12.dp),
                    tint = Color.White,
                )
            }
        }
        PlayerSeekbar(
            position = displayedPosition.toFloat(),
            duration = mediaPresentationState.duration.toFloat(),
            onSeek = {
                controlsVisibilityState?.showControls()
                onSeek(it.toLong())
            },
            onSeekFinished = { onSeekEnd() },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    when (isCustomizingControls) {
                        true ->
                            Modifier
                                .playerControlZoneTarget(
                                    zone = PlayerControlZone.BOTTOM_LEFT,
                                    zoneBounds = zoneBounds,
                                )
                                .heightIn(min = 72.dp)
                        false -> Modifier
                    },
                )
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = when (isCustomizingControls) {
                true -> Alignment.Top
                false -> Alignment.CenterVertically
            },
            horizontalArrangement = when (controlButtonsPosition) {
                ControlButtonsPosition.LEFT -> Arrangement.spacedBy(8.dp, Alignment.Start)
                ControlButtonsPosition.RIGHT -> Arrangement.spacedBy(8.dp, Alignment.End)
            },
        ) {
            bottomLeftControls.forEach { control ->
                if (!isCustomizingControls && control !in visiblePlayerControls) return@forEach
                key(control) {
                    AnimatedPlayerControlPlacement(
                        control = control,
                        itemBounds = itemBounds,
                        isTracking = isCustomizingControls,
                    ) {
                        PlayerCustomizableControlButton(
                            modifier = Modifier.playerControlDragSource(
                                control = control,
                                enabled = isCustomizingControls,
                                onDropDragged = onControlDropDragged,
                                onDragStarted = onControlDragStarted,
                                onDragMoved = onControlDragMoved,
                                onDragCancelled = onControlDragCancelled,
                            ),
                            control = control,
                            isBeingDragged = draggingControl == control,
                            player = player,
                            videoContentScale = videoContentScale,
                            isPipSupported = isPipSupported,
                            isCustomizingControls = isCustomizingControls,
                            shouldHideLabel = shouldHideLabels,
                            visiblePlayerControls = visiblePlayerControls,
                            onPlaylistClick = onPlaylistClick,
                            onPlaybackSpeedClick = onPlaybackSpeedClick,
                            onAudioClick = onAudioClick,
                            onSubtitleClick = onSubtitleClick,
                            onLockControlsClick = onLockControlsClick,
                            onVideoContentScaleClick = onVideoContentScaleClick,
                            onVideoContentScaleLongClick = onVideoContentScaleLongClick,
                            onDecoderClick = onDecoderClick,
                            onAmbienceModeClick = onAmbienceModeClick,
                            isAmbienceModeEnabled = isAmbienceModeEnabled,
                            onVideoFiltersClick = onVideoFiltersClick,
                            onPictureInPictureClick = onPictureInPictureClick,
                            onRotateClick = onRotateClick,
                            isTakingScreenshot = isTakingScreenshot,
                            onScreenshotClick = onScreenshotClick,
                            onPlayInBackgroundClick = onPlayInBackgroundClick,
                            onLoopClick = onLoopClick,
                            onShuffleClick = onShuffleClick,
                            onSleepTimerClick = onSleepTimerClick,
                            sleepTimerState = sleepTimerState,
                        )
                    }
                }
            }
            if (!isCustomizingControls) {
                PlayerButton(
                    onClick = onCustomizeControlsClick,
                    isSelected = false,
                    label = stringResource(R.string.customize_player_controls).takeUnless { shouldHideLabels },
                    shouldShowSelectionBadge = false,
                    shouldDimWhenUnselected = false,
                    shouldShowCustomizeFrame = false,
                ) {
                    Icon(
                        imageVector = NextIcons.Edit,
                        contentDescription = "btn_customize_controls",
                    )
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSeekbar(
    modifier: Modifier = Modifier,
    position: Float,
    duration: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        MaterialYouSlider(
            modifier = modifier.fillMaxWidth(),
            value = position,
            valueRange = 0f..duration,
            onValueChange = onSeek,
            onValueChangeFinished = onSeekFinished,
        )
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialYouSlider(
    modifier: Modifier = Modifier,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val trackHeight = 8.dp
    val thumbWidth = 4.dp
    val trackThumbGapWidth = 12.dp

    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        modifier = modifier.height(24.dp).semantics { contentDescription = "slider_seek" },
        track = { sliderState ->
            val disabledAlpha = 0.4f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight),
            ) {
                val min = sliderState.valueRange.start
                val max = sliderState.valueRange.endInclusive
                val range = (max - min).takeIf { it > 0f } ?: 1f
                val playedFraction = ((sliderState.value - min) / range).coerceIn(0f, 1f)
                val playedPixels = size.width * playedFraction

                val endCornerRadius = size.height / 2f
                val insideCornerRadius = 2.dp.toPx()
                val gapHalf = trackThumbGapWidth.toPx() / 2f
                val leftEnd = (playedPixels - gapHalf).coerceIn(0f, size.width)
                val rightStart = (playedPixels + gapHalf).coerceIn(0f, size.width)

                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(0f, 0f),
                        size = Size(leftEnd, size.height),
                        color = primaryColor.copy(alpha = disabledAlpha),
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }

                if (rightStart < size.width) {
                    drawRoundedRect(
                        offset = Offset(rightStart, 0f),
                        size = Size(size.width - rightStart, size.height),
                        color = primaryColor.copy(alpha = disabledAlpha),
                        startCornerRadius = insideCornerRadius,
                        endCornerRadius = endCornerRadius,
                    )
                }

                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(0f, 0f),
                        size = Size(leftEnd, size.height),
                        color = primaryColor,
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }
            }
        },
        thumb = {
            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .height(20.dp)
                    .background(primaryColor, CircleShape),
            )
        },
    )
}

private fun DrawScope.drawRoundedRect(
    offset: Offset,
    size: Size,
    color: Color,
    startCornerRadius: Float,
    endCornerRadius: Float,
) {
    val startCorner = CornerRadius(startCornerRadius, startCornerRadius)
    val endCorner = CornerRadius(endCornerRadius, endCornerRadius)
    val track = RoundRect(
        rect = Rect(Offset(offset.x, 0f), size = Size(size.width, size.height)),
        topLeft = startCorner,
        topRight = endCorner,
        bottomRight = endCorner,
        bottomLeft = startCorner,
    )
    drawPath(
        path = Path().apply {
            addRoundRect(track)
        },
        color = color,
    )
}
