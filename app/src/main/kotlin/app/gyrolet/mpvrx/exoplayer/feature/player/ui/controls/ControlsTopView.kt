package app.gyrolet.mpvrx.exoplayer.feature.player.ui.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.gyrolet.mpvrx.exoplayer.core.model.ControlButtonsPosition
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControl
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlZone
import app.gyrolet.mpvrx.exoplayer.core.model.VideoContentScale
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.exoplayer.core.ui.extensions.copy
import app.gyrolet.mpvrx.exoplayer.feature.player.AnimatedPlayerControlPlacement
import app.gyrolet.mpvrx.exoplayer.feature.player.buttons.PlayerButton
import app.gyrolet.mpvrx.exoplayer.feature.player.playerControlDragSource
import app.gyrolet.mpvrx.exoplayer.feature.player.playerControlZoneTarget
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SleepTimerState
import app.gyrolet.mpvrx.ui.icons.Icon

@OptIn(UnstableApi::class)
@Composable
fun ControlsTopView(
    modifier: Modifier = Modifier,
    title: String,
    player: Player,
    topRightControls: List<PlayerControl>,
    controlButtonsPosition: ControlButtonsPosition,
    visiblePlayerControls: Set<PlayerControl>,
    videoContentScale: VideoContentScale,
    isPipSupported: Boolean,
    isTakingScreenshot: Boolean,
    itemBounds: MutableMap<PlayerControl, Rect>,
    zoneBounds: MutableMap<PlayerControlZone, Rect>,
    isCustomizingControls: Boolean = false,
    shouldHideLabels: Boolean = false,
    draggingControl: PlayerControl? = null,
    onControlDropDragged: (PlayerControl, Offset) -> Unit = { _, _ -> },
    onControlDragStarted: (PlayerControl) -> Unit = {},
    onControlDragMoved: (PlayerControl, Offset) -> Unit = { _, _ -> },
    onControlDragCancelled: (PlayerControl) -> Unit = {},
    isBackVisible: Boolean = true,
    isBackSelected: Boolean = false,
    isBackInteractive: Boolean = true,
    onAudioClick: () -> Unit = {},
    onSubtitleClick: () -> Unit = {},
    onPlaybackSpeedClick: () -> Unit = {},
    onPlaylistClick: () -> Unit = {},
    onLockControlsClick: () -> Unit = {},
    onVideoContentScaleClick: () -> Unit = {},
    onVideoContentScaleLongClick: () -> Unit = {},
    onDecoderClick: () -> Unit = {},
    onAmbienceModeClick: () -> Unit = {},
    isAmbienceModeEnabled: Boolean = false,
    onVideoFiltersClick: () -> Unit = {},
    onPictureInPictureClick: () -> Unit = {},
    onRotateClick: () -> Unit = {},
    onScreenshotClick: () -> Unit = {},
    onPlayInBackgroundClick: () -> Unit = {},
    onLoopClick: (() -> Unit)? = null,
    onShuffleClick: (() -> Unit)? = null,
    onSleepTimerClick: (() -> Unit)? = null,
    sleepTimerState: SleepTimerState? = null,
    onBackClick: () -> Unit,
) {
    val systemBarsPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val maxVisibleCount = if (isLandscape) 6 else 4
    val buttonSlotWidth = if (isCustomizingControls) 72.dp else 48.dp
    val maxRowWidth = buttonSlotWidth * maxVisibleCount

    @Composable
    fun BackButton() {
        if (!isBackVisible) return

        PlayerButton(
            onClick = onBackClick,
            isSelected = isBackSelected,
            label = stringResource(R.string.player_controls_exit).takeIf { isCustomizingControls },
            shouldShowSelectionBadge = false,
            shouldDimWhenUnselected = false,
            shouldShowCustomizeFrame = false,
            isInteractive = isBackInteractive,
        ) {
            Icon(
                imageVector = NextIcons.ArrowBack,
                contentDescription = "btn_back",
                tint = Color.White,
            )
        }
    }

    @Composable
    fun RowScope.Title() {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }

    @Composable
    fun TopControls() {
        Row(
            modifier = Modifier
                .widthIn(max = maxRowWidth)
                .heightIn(min = 72.dp)
                .then(
                    when (isCustomizingControls) {
                        true -> Modifier.playerControlZoneTarget(
                            zone = PlayerControlZone.TOP_RIGHT,
                            zoneBounds = zoneBounds,
                        )
                        false -> Modifier
                    },
                )
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            topRightControls.forEach { control ->
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
        }
    }

    Row(
        modifier = modifier
            .padding(systemBarsPadding.copy(bottom = 0.dp))
            .padding(horizontal = 8.dp)
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (controlButtonsPosition) {
            ControlButtonsPosition.LEFT -> {
                BackButton()
                Title()
                TopControls()
            }
            ControlButtonsPosition.RIGHT -> {
                TopControls()
                Title()
                BackButton()
            }
        }
    }
}
