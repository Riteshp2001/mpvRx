package app.gyrolet.mpvrx.exoplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun rememberSleepTimerState(
    player: Player,
): SleepTimerState {
    val scope = rememberCoroutineScope()
    val state = remember { SleepTimerState(player, scope) }
    return state
}

@Stable
class SleepTimerState(
    private val player: Player,
    private val scope: CoroutineScope,
) {
    var remainingMillis: Long by mutableLongStateOf(0L)
        private set

    var isActive: Boolean by mutableStateOf(false)
        private set

    private var countdownJob: Job? = null
    private var observeJob: Job? = null

    fun start(durationMinutes: Int) {
        countdownJob?.cancel()
        remainingMillis = durationMinutes * 60_000L
        isActive = true
        startCountdown()
        ensureObserving()
    }

    fun cancel() {
        countdownJob?.cancel()
        countdownJob = null
        observeJob?.cancel()
        observeJob = null
        remainingMillis = 0L
        isActive = false
    }

    private fun pauseCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    private fun resumeIfActive() {
        if (isActive && remainingMillis > 0L) {
            startCountdown()
        }
    }

    // 通过 player.listen 观察播放状态变化，暂停时暂停倒计时
    private fun ensureObserving() {
        if (observeJob != null) return
        observeJob = scope.launch {
            player.listen { events ->
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    if (player.isPlaying) {
                        resumeIfActive()
                    } else {
                        pauseCountdown()
                    }
                }
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (remainingMillis > 0L) {
                delay(1000L)
                remainingMillis = (remainingMillis - 1000L).coerceAtLeast(0L)
            }
            isActive = false
            player.pause()
            observeJob?.cancel()
            observeJob = null
        }
    }
}
