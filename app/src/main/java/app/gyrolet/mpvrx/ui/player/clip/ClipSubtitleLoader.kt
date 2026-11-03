/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import app.gyrolet.mpvrx.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.math.roundToLong

/** Parsed text/bitmap cues on the exported clip's zero-based timeline. */
@UnstableApi
internal data class ClipSubtitleCue(
  val startUs: Long,
  val endUs: Long,
  val cues: List<Cue>,
)

/** Use container timestamps and real subtitle decoders, including codec initialization data. */
@UnstableApi
internal object ClipSubtitleLoader {
  private const val MAX_SUBTITLE_BYTES = 16 * 1024 * 1024

  suspend fun load(
    context: Context,
    source: String,
    track: ClipTrackOption,
    headers: Map<String, String>,
    startSeconds: Double,
    endSeconds: Double,
    delaySeconds: Double,
  ): List<ClipSubtitleCue> {
    require(track.type == "sub") { "A subtitle track is required" }
    // Use the same millisecond rounding as MediaItem.ClippingConfiguration.
    val clipStartUs = (startSeconds * 1000.0).roundToLong() * 1000L
    val clipEndUs = (endSeconds * 1000.0).roundToLong() * 1000L
    val delayUs = (delaySeconds.takeIf { it.isFinite() }?.times(1_000_000.0))?.roundToLong() ?: 0L
    val samples = track.externalFile?.let { external ->
      loadExternal(context, external, track.codec, headers)
    } ?: run {
      check(!track.isExternal) { context.getString(R.string.clip_subtitle_read_failed) }
      loadEmbedded(context, source, headers, track, clipStartUs - delayUs, clipEndUs - delayUs)
    }
    check(samples.parsed) { context.getString(R.string.clip_subtitle_read_failed) }

    val ordered = samples.cues.sortedBy { it.startTimeUs }
    return ordered.mapIndexedNotNull { index, sample ->
      val nextStartUs = ordered.getOrNull(index + 1)?.startTimeUs ?: (clipEndUs - delayUs)
      var endUs = if (sample.endTimeUs == C.TIME_UNSET) nextStartUs else sample.endTimeUs
      if (samples.replacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE) {
        // Empty replacement samples also terminate the previous subtitle.
        endUs = minOf(endUs, nextStartUs)
      }
      val start = maxOf(0L, sample.startTimeUs + delayUs - clipStartUs)
      val end = minOf(clipEndUs - clipStartUs, endUs + delayUs - clipStartUs)
      if (end <= start || sample.cues.isEmpty()) null else ClipSubtitleCue(start, end, sample.cues)
    }
  }

  private suspend fun loadExternal(
    context: Context,
    source: String,
    codec: String,
    headers: Map<String, String>,
  ): SubtitleSamples {
    val dataSource = clipDataSourceFactory(context, headers).createDataSource()
    val data = try {
      dataSource.open(DataSpec(clipMediaUri(source)))
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        currentCoroutineContext().ensureActive()
        val read = dataSource.read(buffer, 0, buffer.size)
        if (read == C.RESULT_END_OF_INPUT) break
        check(output.size() + read <= MAX_SUBTITLE_BYTES) {
          context.getString(R.string.clip_subtitle_too_large)
        }
        output.write(buffer, 0, read)
      }
      output.toByteArray()
    } finally {
      DataSourceUtil.closeQuietly(dataSource)
    }
    val format = Format.Builder().setSampleMimeType(externalMimeType(source, codec, data)).build()
    val factory = DefaultSubtitleParserFactory()
    check(factory.supportsFormat(format)) { context.getString(R.string.clip_subtitle_unsupported) }
    val parser = factory.create(format)
    val result = SubtitleSamples(parser.cueReplacementBehavior)
    parser.parse(data, SubtitleParser.OutputOptions.allCues()) { sample ->
      // Standalone files use their own timestamps, with no container offset.
      result.parsed = true
      result.cues += if (sample.startTimeUs == C.TIME_UNSET) {
        CuesWithTiming(sample.cues, 0L, sample.durationUs)
      } else sample
    }
    return result
  }

  private fun externalMimeType(source: String, codec: String, data: ByteArray): String? {
    val extension = clipMediaUri(source).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
    return when {
      codec in setOf("ass", "ssa") || extension in setOf("ass", "ssa") -> MimeTypes.TEXT_SSA
      codec == "webvtt" || extension == "vtt" -> MimeTypes.TEXT_VTT
      codec == "subrip" || extension == "srt" -> MimeTypes.APPLICATION_SUBRIP
      codec == "ttml" || extension in setOf("ttml", "dfxp") -> MimeTypes.APPLICATION_TTML
      codec == "hdmv_pgs_subtitle" || extension == "sup" -> MimeTypes.APPLICATION_PGS
      else -> {
        // SAF providers need not expose the filename in their content URI.
        val header = data.copyOfRange(0, minOf(data.size, 4096)).toString(Charsets.UTF_8)
        when {
          header.contains("WEBVTT") -> MimeTypes.TEXT_VTT
          header.contains("[Script Info]", true) || header.contains("[Events]", true) -> MimeTypes.TEXT_SSA
          header.contains("-->") -> MimeTypes.APPLICATION_SUBRIP
          header.contains("<tt") -> MimeTypes.APPLICATION_TTML
          else -> null
        }
      }
    }
  }

  private suspend fun loadEmbedded(
    context: Context,
    source: String,
    headers: Map<String, String>,
    track: ClipTrackOption,
    startUs: Long,
    endUs: Long,
  ): SubtitleSamples {
    val dataSource = clipDataSourceFactory(context, headers).createDataSource()
    val candidates = DefaultExtractorsFactory().createExtractors()
    var selected: CueTrackOutput? = null
    val subtitleOutputs = mutableListOf<CueTrackOutput>()
    val outputs = mutableMapOf<Int, TrackOutput>()
    val discarded = DiscardedTrackOutput()
    try {
      var input = openInput(dataSource, source, 0L)
      val extractor = candidates.firstOrNull { candidate ->
        try {
          candidate.sniff(input)
        } catch (_: EOFException) {
          false
        } finally {
          input.resetPeekPosition()
        }
      } ?: error(context.getString(R.string.clip_subtitle_unsupported))
      extractor.init(object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = outputs.getOrPut(id) {
          if (type == C.TRACK_TYPE_TEXT) {
            CueTrackOutput(context, startUs, endUs, discarded).also { subtitleOutputs += it }
          } else discarded
        }

        override fun endTracks() {
          // Matroska/MP4 format IDs preserve container IDs, unlike the player's UI track IDs.
          val matches = subtitleOutputs.filter { output ->
            track.demuxId != null && output.trackFormat?.id?.toIntOrNull() == track.demuxId
          }
          val hasContainerIds = extractor is MatroskaExtractor || extractor is Mp4Extractor ||
            extractor is FragmentedMp4Extractor
          selected = if (track.demuxId != null && hasContainerIds) {
            matches.singleOrNull()
          } else {
            matches.singleOrNull() ?: subtitleOutputs.getOrNull(track.ordinal)
          }
          checkNotNull(selected) { context.getString(R.string.clip_subtitle_unsupported) }.select()
          subtitleOutputs.filter { it !== selected }.forEach { it.discard() }
        }

        override fun seekMap(seekMap: SeekMap) = Unit
      })
      val seekPosition = PositionHolder()
      // Scan from the beginning to retain cues that start before, but overlap, the clip.
      // Audio/video payloads and expired subtitle cues are discarded.
      while (selected?.pastEnd != true) {
        currentCoroutineContext().ensureActive()
        when (extractor.read(input, seekPosition)) {
          Extractor.RESULT_END_OF_INPUT -> break
          Extractor.RESULT_SEEK -> {
            DataSourceUtil.closeQuietly(dataSource)
            input = openInput(dataSource, source, seekPosition.position)
          }
        }
      }
      return selected?.samples ?: error(context.getString(R.string.clip_subtitle_unsupported))
    } finally {
      candidates.forEach { it.release() }
      DataSourceUtil.closeQuietly(dataSource)
    }
  }

  private fun openInput(dataSource: DataSource, source: String, position: Long): DefaultExtractorInput {
    val remaining = dataSource.open(DataSpec.Builder().setUri(clipMediaUri(source)).setPosition(position).build())
    val length = if (remaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + remaining
    return DefaultExtractorInput(dataSource, position, length)
  }

  private class SubtitleSamples(
    val replacementBehavior: Int,
    val cues: MutableList<CuesWithTiming> = mutableListOf(),
    var parsed: Boolean = false,
  )

  private class CueTrackOutput(
    private val context: Context,
    private val startUs: Long,
    private val endUs: Long,
    private val discarded: TrackOutput,
  ) : TrackOutput {
    var trackFormat: Format? = null
      private set
    private var selected = false
    private var discardedTrack = false
    var samples = SubtitleSamples(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
      private set
    var pastEnd = false
      private set
    private val pending = ByteArrayOutputStream()
    private val scratch = ByteArray(8192)
    private val decoder = CueDecoder()

    // Container ASS/SSA events carry their own durations and may overlap, so none replaces another.
    private val Format.clipCueBehavior: Int
      get() = if (codecs == MimeTypes.TEXT_SSA) Format.CUE_REPLACEMENT_BEHAVIOR_MERGE else cueReplacementBehavior

    override fun format(format: Format) {
      trackFormat = format
      if (selected) {
        validateFormat()
      } else if (!samples.parsed) {
        samples = SubtitleSamples(format.clipCueBehavior)
      }
    }

    fun select() {
      selected = true
      validateFormat()
    }

    fun discard() {
      discardedTrack = true
      pending.reset()
      samples.cues.clear()
    }

    private fun acceptsSamples(): Boolean =
      !discardedTrack && trackFormat?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES

    private fun validateFormat() {
      val format = checkNotNull(trackFormat) { context.getString(R.string.clip_subtitle_unsupported) }
      check(format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
        context.getString(R.string.clip_subtitle_unsupported)
      }
      check(!samples.parsed || samples.replacementBehavior == format.clipCueBehavior) {
        context.getString(R.string.clip_subtitle_unsupported)
      }
      if (!samples.parsed) samples = SubtitleSamples(format.clipCueBehavior)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
      if (!acceptsSamples()) return discarded.sampleData(input, length, allowEndOfInput, sampleDataPart)
      val read = input.read(scratch, 0, minOf(length, scratch.size))
      if (read == C.RESULT_END_OF_INPUT) {
        if (!allowEndOfInput) throw EOFException()
      } else {
        check(pending.size() + read <= MAX_SUBTITLE_BYTES) { context.getString(R.string.clip_subtitle_too_large) }
        pending.write(scratch, 0, read)
      }
      return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
      if (!acceptsSamples()) {
        discarded.sampleData(data, length, sampleDataPart)
        return
      }
      check(pending.size() + length <= MAX_SUBTITLE_BYTES) { context.getString(R.string.clip_subtitle_too_large) }
      val bytes = ByteArray(length)
      data.readBytes(bytes, 0, length)
      pending.write(bytes)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
      if (!acceptsSamples()) return
      check(cryptoData == null) { context.getString(R.string.clip_subtitle_unsupported) }
      val bytes = pending.toByteArray()
      val start = bytes.size - offset - size
      check(start >= 0 && offset >= 0) { context.getString(R.string.clip_subtitle_read_failed) }
      val sample = decoder.decode(timeUs, bytes, start, size)
      samples.parsed = true
      samples.cues.removeAll { it.endTimeUs != C.TIME_UNSET && it.endTimeUs <= startUs }
      if (samples.replacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE && timeUs <= startUs) {
        samples.cues.clear()
      }
      samples.cues += sample
      pending.reset()
      pending.write(bytes, bytes.size - offset, offset)
      // Keep one subsequent sample so replacement cues without a duration end at the right time.
      if (timeUs >= endUs) pastEnd = true
    }
  }

  private class DiscardedTrackOutput : TrackOutput {
    private val scratch = ByteArray(8192)
    override fun format(format: Format) = Unit
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
      val read = if (input is ExtractorInput) input.skip(length) else input.read(scratch, 0, minOf(length, scratch.size))
      if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
      return read
    }
  }
}
