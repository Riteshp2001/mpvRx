package app.gyrolet.mpvrx.ui.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AudioEngineKind {
  ExoPlayer,
  Mpv,
}

internal object AudioPlaybackPolicy {
  const val DEFAULT_CROSSFADE_MS = 5_000
  const val MAX_CROSSFADE_MS = 12_000

  fun engine(
    preferred: AudioEngineKind,
    audioOnly: Boolean,
    requiresNativePlayback: Boolean = false,
  ): AudioEngineKind =
    if (audioOnly && !requiresNativePlayback) preferred else AudioEngineKind.Mpv

  fun canCrossfade(
    audioOnly: Boolean,
    audiobook: Boolean,
    seekable: Boolean,
    durationMs: Long,
    spatialOrDirect: Boolean,
    autoplay: Boolean,
    repeatOne: Boolean,
    transitionBlocked: Boolean,
    requestedDurationMs: Int,
  ): Boolean =
    audioOnly && !audiobook && seekable && durationMs > 0 && !spatialOrDirect &&
      autoplay && !repeatOne && !transitionBlocked && requestedDurationMs > 0

  fun overlapDurationMs(requestedMs: Int, outgoingDurationMs: Long, incomingDurationMs: Long): Long =
    minOf(
      requestedMs.coerceIn(0, MAX_CROSSFADE_MS).toLong(),
      outgoingDurationMs.coerceAtLeast(0) / 2,
      incomingDurationMs.coerceAtLeast(0) / 2,
    )

  fun gains(progress: Double): CrossfadeGains {
    val angle = progress.coerceIn(0.0, 1.0) * PI / 2.0
    return CrossfadeGains(cos(angle).toFloat(), sin(angle).toFloat())
  }
}

internal data class CrossfadeGains(val outgoing: Float, val incoming: Float)