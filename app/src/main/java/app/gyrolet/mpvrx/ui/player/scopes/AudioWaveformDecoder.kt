/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.scopes

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.gyrolet.mpvrx.ui.player.TrackNode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

data class AudioWaveformChannel(
  val label: String,
  val minimum: FloatArray,
  val maximum: FloatArray,
)

data class AudioWaveformData(
  val trackId: Int,
  val durationSeconds: Float,
  val channels: List<AudioWaveformChannel>,
)

internal object AudioWaveformDecoder {
  private const val DEQUEUE_TIMEOUT_US = 10_000L
  private const val SAMPLES_PER_COLUMN = 96L

  suspend fun decode(
    context: Context,
    source: String,
    track: TrackNode,
    audioTrackOrdinal: Int,
    durationSeconds: Float,
    columnCount: Int,
  ): AudioWaveformData {
    require(source.isNotBlank()) { "Media source is unavailable" }
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
      setDataSource(extractor, context, source)
      val extractorTrack = resolveTrackIndex(extractor, track, audioTrackOrdinal)
      val inputFormat = extractor.getTrackFormat(extractorTrack)
      val mime =
        inputFormat
          .getString(MediaFormat.KEY_MIME)
          ?.takeIf { it.startsWith("audio/") }
          ?: error("Selected stream is not audio")

      extractor.selectTrack(extractorTrack)
      codec = MediaCodec.createDecoderByType(mime)
      codec.configure(inputFormat, null, null, 0)
      codec.start()

      val durationUs =
        inputFormat
          .longOrNull(MediaFormat.KEY_DURATION)
          ?.takeIf { it > 0L }
          ?: (durationSeconds.coerceAtLeast(0.1f) * 1_000_000L).toLong()
      var outputFormat = inputFormat
      var accumulator: WaveformAccumulator? = null
      var inputEnded = false
      var outputEnded = false
      val bufferInfo = MediaCodec.BufferInfo()

      while (!outputEnded) {
        currentCoroutineContext().ensureActive()

        if (!inputEnded) {
          val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
          if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer unavailable")
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
              codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputEnded = true
            } else {
              codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime.coerceAtLeast(0L), 0)
              extractor.advance()
            }
          }
        }

        when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
          MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
          else -> {
            if (outputIndex >= 0) {
              val outputBuffer = codec.getOutputBuffer(outputIndex)
              if (outputBuffer != null && bufferInfo.size > 0) {
                val channelCount =
                  outputFormat.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.coerceAtLeast(1)
                    ?: track.demuxChannelCount?.toInt()?.coerceAtLeast(1)
                    ?: 2
                val sampleRate =
                  outputFormat.integerOrNull(MediaFormat.KEY_SAMPLE_RATE)?.coerceAtLeast(1)
                    ?: track.demuxSampleRate?.toInt()?.coerceAtLeast(1)
                    ?: 48_000
                val pcmEncoding =
                  outputFormat.integerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
                val currentAccumulator =
                  accumulator ?: WaveformAccumulator(
                    width = columnCount.coerceIn(256, 4_096),
                    channelCount = channelCount,
                    durationUs = durationUs,
                    sampleRate = sampleRate,
                  ).also { accumulator = it }
                currentAccumulator.consume(
                  buffer = outputBuffer,
                  offset = bufferInfo.offset,
                  size = bufferInfo.size,
                  presentationTimeUs = bufferInfo.presentationTimeUs,
                  pcmEncoding = pcmEncoding,
                )
              }
              outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
              codec.releaseOutputBuffer(outputIndex, false)
            }
          }
        }
      }

      val waveform =
        accumulator?.finish(channelLabels(track, accumulator.channelCount))
          ?: error("Decoder produced no audio samples")
      return AudioWaveformData(
        trackId = track.id,
        durationSeconds = durationUs / 1_000_000f,
        channels = waveform,
      )
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { extractor.release() }
    }
  }

  private fun setDataSource(
    extractor: MediaExtractor,
    context: Context,
    source: String,
  ) {
    val uri = Uri.parse(source)
    when (uri.scheme?.lowercase()) {
      "content", "android.resource" -> extractor.setDataSource(context, uri, null)
      "file" -> extractor.setDataSource(requireNotNull(uri.path))
      "http", "https" -> extractor.setDataSource(source, emptyMap())
      null -> extractor.setDataSource(source)
      else -> extractor.setDataSource(context, uri, null)
    }
  }

  private fun resolveTrackIndex(
    extractor: MediaExtractor,
    track: TrackNode,
    audioTrackOrdinal: Int,
  ): Int {
    track.ffIndex
      ?.toInt()
      ?.takeIf { index ->
        index in 0 until extractor.trackCount &&
          extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      }?.let { return it }

    val audioTracks =
      (0 until extractor.trackCount).filter { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      }
    return audioTracks.getOrNull(audioTrackOrdinal.coerceAtLeast(0))
      ?: audioTracks.firstOrNull()
      ?: error("No decodable audio stream found")
  }

  private fun channelLabels(
    track: TrackNode,
    count: Int,
  ): List<String> {
    val normalized = track.demuxChannels.orEmpty().lowercase()
    val known =
      when (normalized) {
        "mono" -> listOf("Mono")
        "stereo" -> listOf("Left", "Right")
        "2.1" -> listOf("Left", "Right", "LFE")
        "3.0" -> listOf("Left", "Right", "Center")
        "3.1" -> listOf("Left", "Right", "Center", "LFE")
        "quad" -> listOf("Left", "Right", "Back Left", "Back Right")
        "5.0" -> listOf("Left", "Right", "Center", "Back Left", "Back Right")
        "5.0(side)" -> listOf("Left", "Right", "Center", "Side Left", "Side Right")
        "5.1" -> listOf("Left", "Right", "Center", "LFE", "Back Left", "Back Right")
        "5.1(side)" -> listOf("Left", "Right", "Center", "LFE", "Side Left", "Side Right")
        "6.1" -> listOf("Left", "Right", "Center", "LFE", "Back Center", "Side Left", "Side Right")
        "7.1" -> listOf("Left", "Right", "Center", "LFE", "Back Left", "Back Right", "Side Left", "Side Right")
        else -> emptyList()
      }
    if (known.size == count) return known
    return List(count) { index -> "Channel ${index + 1}" }
  }

  private fun MediaFormat.integerOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

  private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

  private class WaveformAccumulator(
    private val width: Int,
    val channelCount: Int,
    private val durationUs: Long,
    private val sampleRate: Int,
  ) {
    private val minimum = Array(channelCount) { FloatArray(width) }
    private val maximum = Array(channelCount) { FloatArray(width) }
    private val touched = Array(channelCount) { BooleanArray(width) }
    private val estimatedFrameCount = max(1L, durationUs * sampleRate / 1_000_000L)
    private val sampleStride = max(1L, estimatedFrameCount / (width * SAMPLES_PER_COLUMN)).toInt()

    fun consume(
      buffer: ByteBuffer,
      offset: Int,
      size: Int,
      presentationTimeUs: Long,
      pcmEncoding: Int,
    ) {
      val bytesPerSample = bytesPerSample(pcmEncoding)
      val frameSize = bytesPerSample * channelCount
      if (frameSize <= 0 || size < frameSize) return
      val frameCount = size / frameSize
      val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

      var frame = 0
      while (frame < frameCount) {
        val timeUs = presentationTimeUs + frame * 1_000_000L / sampleRate
        val column = ((timeUs.coerceIn(0L, durationUs) * width) / durationUs).toInt().coerceIn(0, width - 1)
        for (channel in 0 until channelCount) {
          val sampleOffset = offset + (frame * channelCount + channel) * bytesPerSample
          if (sampleOffset + bytesPerSample > offset + size) break
          val sample = readSample(data, sampleOffset, pcmEncoding).coerceIn(-1f, 1f)
          if (!touched[channel][column]) {
            minimum[channel][column] = sample
            maximum[channel][column] = sample
            touched[channel][column] = true
          } else {
            minimum[channel][column] = minOf(minimum[channel][column], sample)
            maximum[channel][column] = maxOf(maximum[channel][column], sample)
          }
        }
        frame += sampleStride
      }
    }

    fun finish(labels: List<String>): List<AudioWaveformChannel> =
      List(channelCount) { channel ->
        AudioWaveformChannel(
          label = labels.getOrElse(channel) { "Channel ${channel + 1}" },
          minimum = minimum[channel],
          maximum = maximum[channel],
        )
      }

    private fun bytesPerSample(encoding: Int): Int =
      when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_8BIT -> 1
        else -> 2
      }

    private fun readSample(
      buffer: ByteBuffer,
      offset: Int,
      encoding: Int,
    ): Float =
      when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.getFloat(offset)
        AudioFormat.ENCODING_PCM_32BIT -> buffer.getInt(offset) / Int.MAX_VALUE.toFloat()
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
          val value =
            (buffer.get(offset).toInt() and 0xFF) or
              ((buffer.get(offset + 1).toInt() and 0xFF) shl 8) or
              (buffer.get(offset + 2).toInt() shl 16)
          value / 8_388_607f
        }
        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xFF) - 128) / 128f
        else -> buffer.getShort(offset) / Short.MAX_VALUE.toFloat()
      }
  }
}
