package app.gyrolet.mpvrx.exoplayer.feature.player.state

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.availableDurationMs
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.formatted
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.seekToRequestedPosition
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.setIsScrubbingModeEnabled

@UnstableApi
@Composable
fun rememberSeekGestureState(
    player: Player,
    sensitivity: Float = 0.5f,
    isSeekGestureEnabled: Boolean,
): SeekGestureState {
    val seekGestureState = remember {
        SeekGestureState(
            player = player,
            sensitivity = sensitivity,
            isSeekGestureEnabled = isSeekGestureEnabled,
        )
    }
    return seekGestureState
}

@Stable
class SeekGestureState(
    private val player: Player,
    private val isSeekGestureEnabled: Boolean = true,
    private val sensitivity: Float = 0.5f,
) {
    var isSeeking: Boolean by mutableStateOf(false)
        private set

    var seekStartPosition: Long? by mutableStateOf(null)
        private set

    var seekAmount: Long? by mutableStateOf(null)
        private set

    var pendingSeekPosition: Long? by mutableStateOf(null)
        private set

    private var seekStartX = 0f

    fun onSeek(value: Long) {
        val duration = player.availableDurationMs()
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val currentPosition = player.currentPosition.takeIf { it != C.TIME_UNSET } ?: 0L

        if (!isSeeking) {
            isSeeking = true
            seekStartPosition = currentPosition
            pendingSeekPosition = currentPosition
            player.setIsScrubbingModeEnabled(true)
        }

        val newPosition = value.coerceIn(0L, duration)
        pendingSeekPosition = newPosition
        seekAmount = (newPosition - seekStartPosition!!).coerceIn(
            minimumValue = 0 - seekStartPosition!!,
            maximumValue = duration - seekStartPosition!!,
        )
    }

    fun onSeekEnd() {
        commitPendingSeek()
        reset()
    }

    fun onDragStart(offset: Offset) {
        if (!isSeekGestureEnabled) return
        val duration = player.availableDurationMs()
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val currentPosition = player.currentPosition.takeIf { it != C.TIME_UNSET } ?: 0L

        isSeeking = true
        seekStartX = offset.x
        seekStartPosition = currentPosition
        pendingSeekPosition = currentPosition

        player.setIsScrubbingModeEnabled(true)
    }

    @OptIn(UnstableApi::class)
    fun onDrag(change: PointerInputChange, dragAmount: Float) {
        val seekStartPosition = seekStartPosition ?: return
        val duration = player.availableDurationMs()
        if (duration == C.TIME_UNSET) return
        if (change.isConsumed) return

        val currentPreviewPosition = pendingSeekPosition ?: seekStartPosition
        if (currentPreviewPosition <= 0L && dragAmount < 0) return
        if (currentPreviewPosition >= duration && dragAmount > 0) return

        val newPosition = (seekStartPosition + ((change.position.x - seekStartX) * (sensitivity * 100)).toInt())
            .coerceIn(0L, duration)
        pendingSeekPosition = newPosition
        seekAmount = (newPosition - seekStartPosition).coerceIn(
            minimumValue = 0 - seekStartPosition,
            maximumValue = duration - seekStartPosition,
        )
    }

    fun onDragEnd() {
        commitPendingSeek()
        reset()
    }

    private fun commitPendingSeek() {
        val pendingSeekPosition = pendingSeekPosition ?: return
        val currentPosition = player.currentPosition.takeIf { it != C.TIME_UNSET }
        if (currentPosition == null || currentPosition != pendingSeekPosition) {
            player.seekToRequestedPosition(pendingSeekPosition)
        }
    }

    private fun reset() {
        player.setIsScrubbingModeEnabled(false)
        isSeeking = false
        seekStartPosition = null
        seekAmount = null
        pendingSeekPosition = null

        seekStartX = 0f
    }
}

val SeekGestureState.seekAmountFormatted: String
    get() {
        val seekAmount = seekAmount ?: return ""
        val sign = if (seekAmount < 0) "-" else "+"
        return sign + abs(seekAmount).milliseconds.formatted()
    }

val SeekGestureState.seekToPositionFormated: String
    get() {
        val position = seekStartPosition ?: return ""
        val seekAmount = seekAmount ?: return ""
        return (position + seekAmount).milliseconds.formatted()
    }
