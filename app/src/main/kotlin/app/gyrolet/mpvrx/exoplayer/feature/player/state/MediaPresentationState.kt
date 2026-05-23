package app.gyrolet.mpvrx.exoplayer.feature.player.state

import androidx.annotation.IntRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.formatted
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.hasRenderedFirstFrame

@UnstableApi
@Composable
fun rememberMediaPresentationState(player: Player): MediaPresentationState {
    val mediaPresentationState = remember { MediaPresentationState(player) }
    LaunchedEffect(player) { mediaPresentationState.observe() }
    return mediaPresentationState
}

@Stable
class MediaPresentationState(
    private val player: Player,
    @param:IntRange(from = 0) private val tickIntervalMs: Long = 500,
) {
    private var pauseDiagnosticsJob: Job? = null
    var position: Long by mutableLongStateOf(0L)
        private set

    var duration: Long by mutableLongStateOf(0L)
        private set

    var isPlaying: Boolean by mutableStateOf(false)
        private set

    var isLoading: Boolean by mutableStateOf(true)
        private set

    var isBuffering: Boolean by mutableStateOf(false)
        private set

    var hasRenderedFirstFrame: Boolean by mutableStateOf(false)
        private set

    suspend fun observe() {
        updatePosition()
        updateDuration()
        isPlaying = player.isPlaying
        isLoading = player.isLoading
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        hasRenderedFirstFrame = player.mediaMetadata.hasRenderedFirstFrame

        coroutineScope {
            val diagnosticsScope = this
            launch {
                player.listen { events ->
                    if (events.containsAny(
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_MEDIA_METADATA_CHANGED,
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                        )
                    ) {
                        updateDuration()
                    }

                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        this@MediaPresentationState.isBuffering = player.playbackState == Player.STATE_BUFFERING
                        logPlaybackDiagnostics("playbackState")
                    }

                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        this@MediaPresentationState.hasRenderedFirstFrame = false
                        logPlaybackDiagnostics("mediaItemTransition")
                    }

                    if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
                        this@MediaPresentationState.hasRenderedFirstFrame = true
                        logPlaybackDiagnostics("renderedFirstFrame")
                    }

                    if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) {
                        logVideoSize(player.videoSize)
                    }

                    if (events.contains(Player.EVENT_PLAYER_ERROR)) {
                        logPlayerError(player.playerError)
                    }

                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                        this@MediaPresentationState.isPlaying = player.isPlaying
                        logPlaybackDiagnostics("isPlayingChanged")
                        schedulePauseDiagnostics(diagnosticsScope)
                    }

                    if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                        updatePosition()
                        logPlaybackDiagnostics("positionDiscontinuity")
                    }

                    if (events.containsAny(Player.EVENT_IS_LOADING_CHANGED)) {
                        this@MediaPresentationState.isLoading = player.isLoading
                        logPlaybackDiagnostics("loadingChanged")
                    }
                }
            }

            while (true) {
                delay(tickIntervalMs)
                if (player.isPlaying) {
                    updatePosition()
                }
                if (duration == 0L) {
                    updateDuration()
                }
            }
        }
    }

    private fun updatePosition() {
        position = player.currentPosition.coerceAtLeast(0L)
    }

    private fun updateDuration() {
        duration = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?: player.mediaMetadata.durationMs?.takeIf { it > 0L }
            ?: 0L
    }

    private fun schedulePauseDiagnostics(scope: CoroutineScope) {
        pauseDiagnosticsJob?.cancel()
        if (player.isPlaying) return
        pauseDiagnosticsJob = scope.launch {
            delay(PAUSE_DIAGNOSTICS_DELAY_MS)
            logPlaybackDiagnostics("pausedFor7s")
        }
    }

    private fun logPlaybackDiagnostics(reason: String) {
        updatePosition()
        updateDuration()
        Logger.info(
            TAG,
            "Playback diagnostics reason=$reason state=${player.playbackState} isPlaying=${player.isPlaying} playWhenReady=${player.playWhenReady} " +
                "isLoading=${player.isLoading} hasRenderedFirstFrame=$hasRenderedFirstFrame positionMs=$position durationMs=$duration " +
                "videoSize=${player.videoSize.width}x${player.videoSize.height} unappliedRotation=${player.videoSize.unappliedRotationDegrees}",
        )
    }

    private fun logVideoSize(videoSize: VideoSize) {
        Logger.info(
            TAG,
            "Video size changed width=${videoSize.width} height=${videoSize.height} unappliedRotation=${videoSize.unappliedRotationDegrees} pixelRatio=${videoSize.pixelWidthHeightRatio}",
        )
    }

    private fun logPlayerError(error: PlaybackException?) {
        if (error == null) return
        Logger.error(TAG, "Player error code=${error.errorCode} name=${error.errorCodeName}", error)
    }

    private companion object {
        private const val TAG = "MediaPresentationState"
        private const val PAUSE_DIAGNOSTICS_DELAY_MS = 7_000L
    }
}

val MediaPresentationState.positionFormatted: String
    get() = position.milliseconds.formatted()

val MediaPresentationState.durationFormatted: String
    get() = duration.milliseconds.formatted()

val MediaPresentationState.pendingPositionFormatted: String
    get() = (duration - position).milliseconds.formatted()
