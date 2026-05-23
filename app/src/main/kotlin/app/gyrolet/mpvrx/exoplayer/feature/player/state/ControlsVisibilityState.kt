package app.gyrolet.mpvrx.exoplayer.feature.player.state

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listen
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.togglePlayerSystemBars

@Composable
fun rememberControlsVisibilityState(player: Player, hideAfter: Duration): ControlsVisibilityState {
    val activity = LocalActivity.current
    val coroutineScope = rememberCoroutineScope()
    val controlsVisibilityState = remember { ControlsVisibilityState(player, hideAfter, coroutineScope) }
    LaunchedEffect(player) { controlsVisibilityState.observe() }
    LaunchedEffect(controlsVisibilityState.isControlsVisible, controlsVisibilityState.isControlsLocked) {
        if (controlsVisibilityState.isControlsLocked) {
            activity?.togglePlayerSystemBars(shouldShowControls = false)
            return@LaunchedEffect
        }
        activity?.togglePlayerSystemBars(
            shouldShowControls = controlsVisibilityState.isControlsVisible,
        )
    }
    return controlsVisibilityState
}

@Stable
class ControlsVisibilityState(
    private val player: Player,
    private val hideAfter: Duration,
    private val scope: CoroutineScope,
) {
    private var autoHideControlsJob: Job? = null

    var isControlsVisible: Boolean by mutableStateOf(true)
        private set

    var isControlsLocked: Boolean by mutableStateOf(false)
        private set

    fun showControls(duration: Duration = hideAfter) {
        isControlsVisible = true
        autoHideControls(duration)
    }

    fun hideControls() {
        autoHideControlsJob?.cancel()
        isControlsVisible = false
    }

    fun toggleControlsVisibility() {
        if (isControlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    fun lockControls() {
        isControlsLocked = true
    }

    fun unlockControls() {
        isControlsLocked = false
        showControls()
    }

    suspend fun observe() {
        player.listen { events ->
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) {
                    autoHideControls()
                }
            }
        }
    }

    private fun autoHideControls(duration: Duration = hideAfter) {
        autoHideControlsJob?.cancel()
        autoHideControlsJob = scope.launch {
            delay(duration)
            if (player.isPlaying) {
                isControlsVisible = false
            }
        }
    }
}
