package app.gyrolet.mpvrx.exoplayer.feature.player.state

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.round
import app.gyrolet.mpvrx.exoplayer.core.model.DoubleTapGesture
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.canSeekCurrentMediaItem
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.seekByRequestedOffset
import app.gyrolet.mpvrx.exoplayer.feature.player.service.setTransientPlaybackSpeed

@UnstableApi
@Composable
fun rememberTapGestureState(
    player: Player,
    doubleTapGesture: DoubleTapGesture,
    seekIncrementMillis: Long,
    shouldUseLongPressGesture: Boolean,
    shouldUseLongPressVariableSpeed: Boolean,
    longPressSpeed: Float,
): TapGestureState {
    val coroutineScope = rememberCoroutineScope()
    val tapGestureState = remember(
        player,
        doubleTapGesture,
        seekIncrementMillis,
        shouldUseLongPressGesture,
        shouldUseLongPressVariableSpeed,
        longPressSpeed,
        coroutineScope,
    ) {
        TapGestureState(
            player = player,
            doubleTapGesture = doubleTapGesture,
            seekIncrementMillis = seekIncrementMillis,
            shouldUseLongPressGesture = shouldUseLongPressGesture,
            shouldUseLongPressVariableSpeed = shouldUseLongPressVariableSpeed,
            longPressSpeed = longPressSpeed,
            coroutineScope = coroutineScope,
        )
    }
    return tapGestureState
}

@Stable
class TapGestureState(
    private val player: Player,
    private val seekIncrementMillis: Long,
    private val shouldUseLongPressGesture: Boolean = true,
    private val shouldUseLongPressVariableSpeed: Boolean = false,
    private val coroutineScope: CoroutineScope,
    val longPressSpeed: Float = PlayerPreferences.DEFAULT_LONG_PRESS_CONTROLS_SPEED,
    val doubleTapGesture: DoubleTapGesture,
    val interactionSource: MutableInteractionSource = MutableInteractionSource(),
) {
    var seekMillis by mutableLongStateOf(0L)
    var isLongPressGestureInAction by mutableStateOf(false)
    var currentLongPressSpeed by mutableFloatStateOf(longPressSpeed)
        private set

    val canUseLongPressVariableSpeed: Boolean
        get() = shouldUseLongPressVariableSpeed && isLongPressGestureInAction
    var isLongPressGestureCaptured: Boolean = false
        private set
    var longPressSpeedChangeCount by mutableIntStateOf(0)
        private set

    private var resetJob: Job? = null
    private var originalPlaybackSpeed: Float = player.playbackParameters.speed
    private var longPressDragAmount by mutableFloatStateOf(0f)

    fun handleDoubleTap(offset: Offset, size: IntSize) {
        if (!player.canSeekCurrentMediaItem()) return

        val action = when (doubleTapGesture) {
            DoubleTapGesture.FAST_FORWARD_AND_REWIND -> {
                val viewCenterX = size.width / 2
                when {
                    offset.x < viewCenterX -> DoubleTapAction.SEEK_BACKWARD
                    else -> DoubleTapAction.SEEK_FORWARD
                }
            }

            DoubleTapGesture.BOTH -> {
                val eventPositionX = offset.x / size.width
                when {
                    eventPositionX < 0.35 -> DoubleTapAction.SEEK_BACKWARD
                    eventPositionX > 0.65 -> DoubleTapAction.SEEK_FORWARD
                    else -> DoubleTapAction.PLAY_PAUSE
                }
            }

            DoubleTapGesture.PLAY_PAUSE -> DoubleTapAction.PLAY_PAUSE

            DoubleTapGesture.NONE -> return
        }

        when (action) {
            DoubleTapAction.SEEK_BACKWARD -> {
                player.seekByRequestedOffset(-seekIncrementMillis)
                if (seekMillis > 0L) {
                    seekMillis = 0L
                }
                seekMillis -= seekIncrementMillis
                interactionSource.tryEmit(PressInteraction.Press(offset))
            }

            DoubleTapAction.SEEK_FORWARD -> {
                player.seekByRequestedOffset(seekIncrementMillis)
                if (seekMillis < 0L) {
                    seekMillis = 0L
                }
                seekMillis += seekIncrementMillis
                interactionSource.tryEmit(PressInteraction.Press(offset))
            }

            DoubleTapAction.PLAY_PAUSE -> {
                when (player.isPlaying) {
                    true -> player.pause()
                    false -> player.play()
                }
            }
        }
        resetDoubleTapSeekState()
    }

    fun handleLongPress() {
        startTemporaryPlaybackSpeed(force = false)
    }

    fun handleKeyboardLongPress(): Boolean = startTemporaryPlaybackSpeed(force = true)

    private fun startTemporaryPlaybackSpeed(force: Boolean): Boolean {
        if (!force && !shouldUseLongPressGesture) return false
        if (!player.isPlaying) return false
        if (isLongPressGestureInAction) return false

        isLongPressGestureCaptured = true
        isLongPressGestureInAction = true
        originalPlaybackSpeed = player.playbackParameters.speed
        longPressDragAmount = 0f
        currentLongPressSpeed = longPressSpeed
        player.setPlaybackSpeedWithoutPersistence(currentLongPressSpeed)
        return true
    }

    fun handleLongPressHorizontalDrag(dragAmount: Float) {
        if (!canUseLongPressVariableSpeed) return

        longPressDragAmount += dragAmount
        val resolvedSpeed = resolveLongPressVariableSpeed(
            baseSpeed = longPressSpeed,
            accumulatedDragAmount = longPressDragAmount,
        )
        if (resolvedSpeed == currentLongPressSpeed) return

        currentLongPressSpeed = resolvedSpeed
        longPressSpeedChangeCount += 1
        player.setPlaybackSpeedWithoutPersistence(currentLongPressSpeed)
    }

    fun handleOnLongPressRelease() {
        if (!isLongPressGestureInAction) return

        isLongPressGestureCaptured = false
        isLongPressGestureInAction = false
        longPressDragAmount = 0f
        currentLongPressSpeed = longPressSpeed
        player.setPlaybackSpeedWithoutPersistence(originalPlaybackSpeed)
    }

    private fun Player.setPlaybackSpeedWithoutPersistence(speed: Float) {
        when (this) {
            is MediaController -> setTransientPlaybackSpeed(speed)
            else -> setPlaybackSpeed(speed)
        }
    }

    private fun resetDoubleTapSeekState() {
        resetJob?.cancel()
        resetJob = coroutineScope.launch {
            delay(750.milliseconds)
            seekMillis = 0L
        }
    }
}

internal fun resolveLongPressVariableSpeed(
    baseSpeed: Float,
    accumulatedDragAmount: Float,
    minSpeed: Float = PlayerPreferences.MIN_LONG_PRESS_CONTROLS_SPEED,
    maxSpeed: Float = PlayerPreferences.MAX_LONG_PRESS_CONTROLS_SPEED,
    pixelsPerStep: Float = 48f,
): Float {
    val speedDelta = (accumulatedDragAmount / pixelsPerStep) * 0.1f
    return (baseSpeed + speedDelta)
        .coerceIn(minSpeed, maxSpeed)
        .round(1)
}

enum class DoubleTapAction {
    SEEK_BACKWARD,
    SEEK_FORWARD,
    PLAY_PAUSE,
}
