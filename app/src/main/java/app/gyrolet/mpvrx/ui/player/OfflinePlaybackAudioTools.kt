package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.repository.ai.RealtimeAudioChunkExtractor
import app.gyrolet.mpvrx.repository.ai.RealtimeMediaInput
import app.gyrolet.mpvrx.repository.audio.AudioTimeRange
import app.gyrolet.mpvrx.repository.audio.OfflineAudioTimeline
import app.gyrolet.mpvrx.repository.audio.SubtitleAlignmentResult
import app.gyrolet.mpvrx.repository.audio.fitSubtitleAlignment
import app.gyrolet.mpvrx.repository.audio.matchSubtitleTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

internal data class OfflineAudioToolsState(
  val skipping: Boolean = false,
  val analyzingSilence: Boolean = false,
  val thresholdDb: Float = -45f,
  val minimumSilenceMs: Long = 1000,
  val speechPaddingMs: Long = 200,
  val skippedMs: Long = 0,
  val aligning: Boolean = false,
  val alignment: SubtitleAlignmentResult? = null,
  val alignmentApplied: Boolean = false,
  val error: String? = null,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object OfflinePlaybackAudioTools {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mutableState = MutableStateFlow(OfflineAudioToolsState())
  val state = mutableState.asStateFlow()
  private var skippingJob: Job? = null
  private var skippingOperation = 0L
  private var skippingAudioId: String? = null
  private var alignmentJob: Job? = null
  private var alignmentOperation = 0L
  private var alignmentTarget: AlignmentTarget? = null
  private var observedGeneration = PlaybackSession.state.value.generation

  private data class AlignmentTarget(
    val generation: Long,
    val subtitleId: String?,
    val audioId: String?,
    val oldDelay: Double,
    val oldSpeed: Double,
  )

  init {
    scope.launch {
      PlaybackSession.state.map { it.generation }.distinctUntilChanged().collect { generation ->
        if (generation != observedGeneration) {
          observedGeneration = generation
          skippingJob?.cancel()
          alignmentJob?.cancel()
          alignmentTarget = null
          mutableState.update { it.copy(skipping = false, analyzingSilence = false, aligning = false,
            alignment = null, alignmentApplied = false, skippedMs = 0, error = null) }
        }
      }
    }
    scope.launch {
      while (true) {
        delay(250)
        if (mutableState.value.skipping &&
          (PlaybackSession.getPropertyString("aid") != skippingAudioId || !hasUnshiftedAudio())) stopSkipping()
        val target = alignmentTarget
        if (target != null && !isCurrent(target)) {
          if (mutableState.value.alignmentApplied) restoreAlignment(target)
          cancelAlignment()
          alignmentTarget = null
          mutableState.update { it.copy(alignment = null, alignmentApplied = false) }
        }
      }
    }
  }

  fun reportError(message: String) {
    mutableState.update { it.copy(error = message) }
  }

  fun configureSilence(thresholdDb: Float, minimumMs: Long, paddingMs: Long) {
    mutableState.update {
      it.copy(thresholdDb = thresholdDb.coerceIn(-70f, -20f), minimumSilenceMs = minimumMs.coerceIn(500, 5000),
        speechPaddingMs = paddingMs.coerceIn(100, 500))
    }
  }

  fun stopSkipping() {
    skippingOperation++
    skippingJob?.cancel()
    skippingJob = null
    skippingAudioId = null
    mutableState.update { it.copy(skipping = false, analyzingSilence = false) }
  }

  fun startSkipping(context: Context, input: RealtimeMediaInput) {
    stopSkipping()
    val durationMs = ((PlaybackSession.getPropertyDouble("duration") ?: 0.0) * 1000).toLong()
    if (!isLocal(input.source) || durationMs <= 0 || PlaybackSession.state.value.currentItem?.audiobook != null ||
      PlaybackSession.getPropertyBoolean("seekable") != true) {
      reportError(context.getString(app.gyrolet.mpvrx.R.string.offline_audio_local_media_required))
      return
    }
    if (input.audioTrackIndex == null || !hasUnshiftedAudio()) {
      reportError(context.getString(app.gyrolet.mpvrx.R.string.offline_audio_track_required))
      return
    }
    val generation = PlaybackSession.state.value.generation
    val operation = skippingOperation
    val audioId = PlaybackSession.getPropertyString("aid")
    skippingAudioId = audioId
    mutableState.update { it.copy(skipping = true, skippedMs = 0, error = null) }
    skippingJob = scope.launch {
      try {
        val extractor = RealtimeAudioChunkExtractor(context.applicationContext)
        var timeline: OfflineAudioTimeline? = null
        var lastSeekAt = 0L
        while (PlaybackSession.state.value.generation == generation && PlaybackSession.getPropertyString("aid") == audioId && hasUnshiftedAudio()) {
          currentCoroutineContext().ensureActive()
          if (PlaybackSession.state.value.paused || PlaybackSession.getPropertyBoolean("seeking") == true) {
            delay(250)
            continue
          }
          val positionMs = ((PlaybackSession.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong()
          if (positionMs >= durationMs - 300 || PlaybackSession.getPropertyBoolean("eof-reached") == true) break
          val cached = timeline
          if (cached == null || positionMs < cached.startMs || positionMs >= cached.endMs - 500) {
            mutableState.update { it.copy(analyzingSilence = true) }
            val startMs = positionMs.coerceAtLeast(0)
            timeline = analyzeChunk(extractor, input, startMs, (startMs + 30000).coerceAtMost(durationMs))
            mutableState.update { it.copy(analyzingSilence = false) }
            continue
          }
          val settings = mutableState.value
          val range = cached.silenceRanges(settings.thresholdDb, settings.minimumSilenceMs, settings.speechPaddingMs)
            .firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 150 }
          val now = android.os.SystemClock.elapsedRealtime()
          if (range != null && now - lastSeekAt > 1000) {
            val command = arrayOf("seek", (range.endMs / 1000.0).toString(), "absolute+exact")
            if (PlaybackSession.state.value.paused || PlaybackSession.getPropertyBoolean("seeking") == true ||
              PlaybackSession.getPropertyString("aid") != audioId || !hasUnshiftedAudio()) continue
            if (!MpvConfigOverridePolicy.shouldSuppress(command) && PlaybackSession.commandForGeneration(generation, *command)) {
              mutableState.update { it.copy(skippedMs = it.skippedMs + range.endMs - positionMs) }
              lastSeekAt = now
            }
          }
          delay(200)
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        if (PlaybackSession.state.value.generation == generation && skippingOperation == operation) {
          reportError(context.getString(app.gyrolet.mpvrx.R.string.offline_audio_analysis_failed))
        }
      } finally {
        if (PlaybackSession.state.value.generation == generation && skippingOperation == operation) {
          mutableState.update { it.copy(skipping = false, analyzingSilence = false) }
        }
      }
    }
  }

  fun cancelAlignment() {
    alignmentOperation++
    alignmentJob?.cancel()
    alignmentJob = null
    mutableState.update { it.copy(aligning = false) }
  }

  fun analyzeAlignment(context: Context, input: RealtimeMediaInput, subtitleSource: String) {
    cancelAlignment()
    if (mutableState.value.alignmentApplied) undoAlignment()
    val durationMs = ((PlaybackSession.getPropertyDouble("duration") ?: 0.0) * 1000).toLong()
    if (!isLocal(input.source) || !isLocal(subtitleSource) || durationMs < 60000 ||
      PlaybackSession.state.value.currentItem?.audiobook != null || PlaybackSession.getPropertyBoolean("seekable") != true) {
      reportError(context.getString(app.gyrolet.mpvrx.R.string.offline_alignment_local_subtitle_required))
      return
    }
    if (input.audioTrackIndex == null || !hasUnshiftedAudio()) {
      reportError(context.getString(app.gyrolet.mpvrx.R.string.offline_audio_track_required))
      return
    }
    val operation = alignmentOperation
    val target = AlignmentTarget(PlaybackSession.state.value.generation, PlaybackSession.getPropertyString("sid"),
      PlaybackSession.getPropertyString("aid"), PlaybackSession.getPropertyDouble("sub-delay") ?: 0.0,
      PlaybackSession.getPropertyDouble("sub-speed") ?: 1.0)
    alignmentTarget = target
    mutableState.update { it.copy(aligning = true, alignment = null, alignmentApplied = false, error = null) }
    alignmentJob = scope.launch {
      try {
        val result = withContext(Dispatchers.IO) {
          val cues = readSubtitleTimings(context, subtitleSource)
          val extractor = RealtimeAudioChunkExtractor(context.applicationContext)
          val first = cues.first().startMs.coerceIn(0, durationMs - 40000)
          val last = (cues.last().endMs - 40000).coerceIn(first, durationMs - 40000)
          val starts = listOf(first, (first + last) / 2, last).distinct()
          val samples = starts.mapNotNull { start ->
            currentCoroutineContext().ensureActive()
            check(isCurrent(target)) { context.getString(app.gyrolet.mpvrx.R.string.offline_audio_media_changed) }
            matchSubtitleTiming(analyzeChunk(extractor, input, start, start + 40000), cues)
          }
          fitSubtitleAlignment(samples)
            ?: error(context.getString(app.gyrolet.mpvrx.R.string.offline_alignment_uncertain))
        }
        if (isCurrent(target)) mutableState.update { it.copy(alignment = result) }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        if (isCurrent(target)) reportError(error.message ?: context.getString(app.gyrolet.mpvrx.R.string.offline_audio_analysis_failed))
      } finally {
        if (alignmentOperation == operation) mutableState.update { it.copy(aligning = false) }
      }
    }
  }

  fun applyAlignment() {
    val target = alignmentTarget ?: return
    val result = mutableState.value.alignment ?: return
    if (!isCurrent(target) || MpvConfigOverridePolicy.isOwnedByMpvConf("sub-delay") ||
      MpvConfigOverridePolicy.isOwnedByMpvConf("sub-speed")) return
    val delayApplied = PlaybackSession.commandForGeneration(target.generation, "set", "file-local-options/sub-delay", result.delaySeconds.toString())
    val speedApplied = PlaybackSession.commandForGeneration(target.generation, "set", "file-local-options/sub-speed", result.speed.toString())
    if (delayApplied && speedApplied && isCurrent(target)) mutableState.update { it.copy(alignmentApplied = true) }
  }

  fun undoAlignment() {
    val target = alignmentTarget ?: return
    if (!isCurrent(target)) return
    restoreAlignment(target)
    mutableState.update { it.copy(alignmentApplied = false) }
  }

  private fun restoreAlignment(target: AlignmentTarget) {
    if (PlaybackSession.state.value.generation != target.generation) return
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("sub-delay")) {
      PlaybackSession.commandForGeneration(target.generation, "set", "file-local-options/sub-delay", target.oldDelay.toString())
    }
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("sub-speed")) {
      PlaybackSession.commandForGeneration(target.generation, "set", "file-local-options/sub-speed", target.oldSpeed.toString())
    }
  }

  private fun hasUnshiftedAudio(): Boolean =
    (PlaybackSession.getPropertyString("aid")?.toIntOrNull() ?: 0) > 0 &&
      kotlin.math.abs(PlaybackSession.getPropertyDouble("audio-delay") ?: 0.0) < 0.001

  private fun isCurrent(target: AlignmentTarget): Boolean =
    alignmentTarget === target && PlaybackSession.state.value.generation == target.generation &&
      PlaybackSession.getPropertyString("sid") == target.subtitleId && PlaybackSession.getPropertyString("aid") == target.audioId && hasUnshiftedAudio()

  private fun isLocal(source: String): Boolean = source.isNotBlank() && Uri.parse(source).scheme?.lowercase() in listOf(null, "file", "content")

  private suspend fun analyzeChunk(
    extractor: RealtimeAudioChunkExtractor,
    input: RealtimeMediaInput,
    startMs: Long,
    endMs: Long,
  ): OfflineAudioTimeline {
    val file = extractor.extract(input, startMs, endMs)
    return try {
      withContext(Dispatchers.IO) { OfflineAudioTimeline.fromMonoPcmWav(file, startMs) }
    } finally {
      file.delete()
    }
  }

  private suspend fun readSubtitleTimings(context: Context, source: String): List<AudioTimeRange> {
    val uri = Uri.parse(source)
    val name = if (uri.scheme == "content") {
      androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
    } else uri.path.orEmpty()
    val mime = when (name.substringAfterLast('.').lowercase()) {
      "srt" -> MimeTypes.APPLICATION_SUBRIP
      "vtt" -> MimeTypes.TEXT_VTT
      else -> error(context.getString(app.gyrolet.mpvrx.R.string.offline_alignment_local_subtitle_required))
    }
    val stream = if (uri.scheme == "content") context.contentResolver.openInputStream(uri) else File(uri.path ?: source).inputStream()
    val bytes = requireNotNull(stream).use { input ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= 8 * 1024 * 1024) { context.getString(app.gyrolet.mpvrx.R.string.offline_subtitle_too_large) }
        output.write(buffer, 0, count)
      }
      output.toByteArray()
    }
    val parser = DefaultSubtitleParserFactory().create(Format.Builder().setSampleMimeType(mime).build())
    val subtitle = parser.parseToLegacySubtitle(bytes, 0, bytes.size)
    return (0 until subtitle.eventTimeCount - 1).mapNotNull { index ->
      val start = subtitle.getEventTime(index)
      val end = subtitle.getEventTime(index + 1)
      if (end > start && subtitle.getCues(start).any { !it.text.isNullOrBlank() }) AudioTimeRange(start / 1000, end / 1000) else null
    }.also { require(it.size >= 6) { context.getString(app.gyrolet.mpvrx.R.string.offline_alignment_uncertain) } }
  }
}