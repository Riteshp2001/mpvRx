package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.preferences.AudioOutputSampleRate
import app.gyrolet.mpvrx.preferences.ReplayGainMode
import java.util.concurrent.atomic.AtomicBoolean

data class AudioEngineTrack(
  val id: Int,
  val title: String?,
  val language: String?,
  val codec: String?,
  val selected: Boolean,
)

data class AudioEngineChapter(val title: String, val positionMs: Long)

data class AudioOutputInfo(
  val sourceMimeType: String? = null,
  val sourceSampleRate: Int = 0,
  val sourceChannels: Int = 0,
  val sourceBitrate: Int = 0,
  val dolbyAtmosSource: Boolean = false,
  val dolbyAtmosSupported: Boolean = false,
  val decoderName: String? = null,
  val decoderSupportsAtmos: Boolean = false,
  val outputSampleRate: Int = 0,
  val outputEncoding: Int = 0,
  val outputChannels: Int = 0,
  val offloaded: Boolean = false,
  val spatializationAvailable: Boolean = false,
  val spatializationEnabled: Boolean = false,
  val spatializationEligible: Boolean = false,
  val processingBypassed: Boolean = false,
  val processingActive: Boolean = false,
)

data class AudioEngineSnapshot(
  val generation: Long = 0L,
  val item: PlaybackItem? = null,
  val ready: Boolean = false,
  val paused: Boolean = true,
  val buffering: Boolean = false,
  val playing: Boolean = false,
  val live: Boolean = false,
  val ended: Boolean = false,
  val seekable: Boolean = false,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val volume: Float = 100f,
  val muted: Boolean = false,
  val speed: Float = 1f,
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
  val metadata: Map<String, String> = emptyMap(),
  val tracks: List<AudioEngineTrack> = emptyList(),
  val chapters: List<AudioEngineChapter> = emptyList(),
  val audioSessionIds: List<Int> = emptyList(),
  val output: AudioOutputInfo = AudioOutputInfo(),
  val crossfading: Boolean = false,
  val crossfadeAvailable: Boolean = false,
  val skipSilenceEnabled: Boolean = false,
  val replayGainDb: Float? = null,
  val loopStartMs: Long? = null,
  val loopEndMs: Long? = null,
  val error: String? = null,
)

enum class AudioChannelMix {
  Auto,
  Mono,
  Stereo,
  ReverseStereo,
}

data class AudioProcessingSettings(
  val equalizerEnabled: Boolean = false,
  val bandGains: List<Int> = List(5) { 0 },
  val boostDb: Int = 0,
  val normalize: Boolean = false,
  val compress: Boolean = false,
  val channelMix: AudioChannelMix = AudioChannelMix.Auto,
  val skipSilence: Boolean = false,
  val replayGain: ReplayGainMode = ReplayGainMode.Off,
) {
  val needsProcessing: Boolean
    get() = equalizerEnabled || normalize || compress || channelMix != AudioChannelMix.Auto ||
      skipSilence || replayGain != ReplayGainMode.Off
}

internal data class AudioOutputSettings(
  val preferDolbyAtmos: Boolean = true,
  val spatialAudio: Boolean = true,
  val wifiMaxBitrate: Int = 0,
  val mobileMaxBitrate: Int = 256_000,
)

internal class AudioPlaybackSource(
  val item: PlaybackItem,
  val uri: String,
  val mimeType: String? = item.mimeType,
  private val cleanup: () -> Unit = {},
) : AutoCloseable {
  private val closed = AtomicBoolean()

  override fun close() {
    if (closed.compareAndSet(false, true)) cleanup()
  }
}

internal data class PreparedAudioNext(
  val source: AudioPlaybackSource,
  val generation: Long,
  val before: PlaybackQueueState,
  val after: PlaybackQueueState,
)

internal interface AudioPlaybackEngine {
  fun load(source: AudioPlaybackSource, generation: Long, positionMs: Long)
  fun prepareNext(next: PreparedAudioNext)
  fun cancelTransition()
  fun setPaused(paused: Boolean)
  fun seekTo(positionMs: Long)
  fun setVolume(volume: Float)
  fun setDuckGain(gain: Float)
  fun setMuted(muted: Boolean)
  fun silence()
  fun setSpeed(speed: Float, preservePitch: Boolean)
  fun selectTrack(id: Int)
  fun setProcessing(settings: AudioProcessingSettings)
  fun setOutputSettings(settings: AudioOutputSettings)
  fun setOutputSampleRate(mode: AudioOutputSampleRate)
  fun setCrossfade(durationMs: Int, allowed: Boolean)
  fun setLoop(startMs: Long?, endMs: Long?)
  fun release(onReleased: () -> Unit = {})
}

sealed interface AudioPlaybackEvent {
  data class Ready(val generation: Long) : AudioPlaybackEvent
  data class Leaving(val snapshot: AudioEngineSnapshot) : AudioPlaybackEvent
  data class Finished(val generation: Long) : AudioPlaybackEvent
}