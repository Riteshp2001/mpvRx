/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal data class RealtimeMediaInput(
  val source: String,
  val headers: Map<String, String> = emptyMap(),
  val audioTrackIndex: Int? = null,
  val audioTrackOrdinal: Int = 0,
  val strictAudioTrack: Boolean = false,
)

internal class RealtimeAudioChunkExtractor(
  private val context: Context,
) {
  suspend fun extract(
    input: RealtimeMediaInput,
    startMs: Long,
    endMs: Long,
  ): File =
    withContext(Dispatchers.IO) {
      require(input.source.isNotBlank()) { "Media source is unavailable" }
      require(endMs > startMs) { "Audio chunk has an invalid time range" }

      val outputFile = File.createTempFile("realtime_audio_", ".wav", context.cacheDir)
      val extractor = MediaExtractor()
      var codec: MediaCodec? = null
      try {
        setDataSource(extractor, input)
        val extractorTrack = resolveTrackIndex(extractor, input)
        val inputFormat = extractor.getTrackFormat(extractorTrack)
        val mime =
          inputFormat.getString(MediaFormat.KEY_MIME)
            ?.takeIf { it.startsWith("audio/") }
            ?: error("Selected stream is not audio")

        extractor.selectTrack(extractorTrack)
        extractor.seekTo(startMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val pcm = ByteArrayOutputStream()
        val targetSampleCount = ((endMs - startMs) * TARGET_SAMPLE_RATE / 1_000L).coerceAtLeast(1L)
        var outputFormat = inputFormat
        var inputEnded = false
        var outputEnded = false
        var lastTargetSample = -1L
        val bufferInfo = MediaCodec.BufferInfo()

        while (!outputEnded) {
          currentCoroutineContext().ensureActive()

          if (!inputEnded) {
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
              val sampleTimeUs = extractor.sampleTime
              if (sampleTimeUs < 0L || sampleTimeUs >= endMs * 1_000L) {
                codec.queueInputBuffer(inputIndex, 0, 0, endMs * 1_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEnded = true
              } else {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer unavailable")
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                  codec.queueInputBuffer(inputIndex, 0, 0, sampleTimeUs.coerceAtLeast(0L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                  inputEnded = true
                } else {
                  codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs.coerceAtLeast(0L), extractor.sampleFlags)
                  extractor.advance()
                }
              }
            }
          }

          when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            else -> {
              if (outputIndex >= 0) {
                codec.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                  if (bufferInfo.size > 0) {
                    lastTargetSample =
                      appendMonoPcm16(
                        destination = pcm,
                        buffer = outputBuffer,
                        offset = bufferInfo.offset,
                        size = bufferInfo.size,
                        presentationTimeUs = bufferInfo.presentationTimeUs,
                        outputFormat = outputFormat,
                        startUs = startMs * 1_000L,
                        endUs = endMs * 1_000L,
                        targetSampleCount = targetSampleCount,
                        lastTargetSample = lastTargetSample,
                      )
                  }
                }
                outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(outputIndex, false)
              }
            }
          }
        }

        check(pcm.size() > 0) { "Decoder produced no audio in the selected time range" }
        writeWav(outputFile, pcm.toByteArray())
        outputFile
      } catch (error: Throwable) {
        outputFile.delete()
        throw error
      } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
      }
    }

  private fun setDataSource(
    extractor: MediaExtractor,
    input: RealtimeMediaInput,
  ) {
    val uri = Uri.parse(input.source)
    when (uri.scheme?.lowercase()) {
      "content", "android.resource" -> extractor.setDataSource(context, uri, input.headers)
      "file" -> extractor.setDataSource(requireNotNull(uri.path))
      "http", "https" -> extractor.setDataSource(input.source, input.headers)
      null -> extractor.setDataSource(input.source)
      else -> extractor.setDataSource(context, uri, input.headers)
    }
  }

  private fun resolveTrackIndex(
    extractor: MediaExtractor,
    input: RealtimeMediaInput,
  ): Int {
    input.audioTrackIndex?.takeIf { index ->
      index in 0 until extractor.trackCount &&
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }?.let { return it }

    check(!input.strictAudioTrack) { "Selected audio track is not available to the device decoder" }
    val audioTracks =
      (0 until extractor.trackCount).filter { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      }
    return audioTracks.getOrNull(input.audioTrackOrdinal.coerceAtLeast(0))
      ?: audioTracks.firstOrNull()
      ?: error("No decodable audio stream found")
  }

  private fun appendMonoPcm16(
    destination: ByteArrayOutputStream,
    buffer: ByteBuffer,
    offset: Int,
    size: Int,
    presentationTimeUs: Long,
    outputFormat: MediaFormat,
    startUs: Long,
    endUs: Long,
    targetSampleCount: Long,
    lastTargetSample: Long,
  ): Long {
    val channelCount = outputFormat.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.coerceAtLeast(1) ?: 1
    val sampleRate = outputFormat.integerOrNull(MediaFormat.KEY_SAMPLE_RATE)?.coerceAtLeast(1) ?: TARGET_SAMPLE_RATE
    val pcmEncoding = outputFormat.integerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
    val bytesPerSample = bytesPerSample(pcmEncoding)
    val frameSize = bytesPerSample * channelCount
    if (frameSize <= 0 || size < frameSize) return lastTargetSample

    val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val frameCount = size / frameSize
    var writtenTargetSample = lastTargetSample

    for (frame in 0 until frameCount) {
      val frameTimeUs = presentationTimeUs + frame * 1_000_000L / sampleRate
      if (frameTimeUs < startUs) continue
      if (frameTimeUs >= endUs) break

      val targetSample = ((frameTimeUs - startUs) * TARGET_SAMPLE_RATE / 1_000_000L).coerceAtMost(targetSampleCount - 1L)
      if (targetSample <= writtenTargetSample) continue

      var mixedSample = 0f
      for (channel in 0 until channelCount) {
        val sampleOffset = offset + (frame * channelCount + channel) * bytesPerSample
        if (sampleOffset + bytesPerSample > offset + size) break
        mixedSample += readSample(data, sampleOffset, pcmEncoding)
      }
      val pcm16 = ((mixedSample / channelCount).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
      while (writtenTargetSample < targetSample) {
        destination.write(pcm16.toInt() and 0xFF)
        destination.write((pcm16.toInt() ushr 8) and 0xFF)
        writtenTargetSample++
      }
    }

    return writtenTargetSample
  }

  private fun writeWav(
    outputFile: File,
    pcm: ByteArray,
  ) {
    DataOutputStream(FileOutputStream(outputFile)).use { output ->
      output.writeBytes("RIFF")
      output.writeLittleEndianInt(36 + pcm.size)
      output.writeBytes("WAVE")
      output.writeBytes("fmt ")
      output.writeLittleEndianInt(16)
      output.writeLittleEndianShort(1)
      output.writeLittleEndianShort(TARGET_CHANNEL_COUNT)
      output.writeLittleEndianInt(TARGET_SAMPLE_RATE)
      output.writeLittleEndianInt(TARGET_SAMPLE_RATE * TARGET_CHANNEL_COUNT * BYTES_PER_TARGET_SAMPLE)
      output.writeLittleEndianShort(TARGET_CHANNEL_COUNT * BYTES_PER_TARGET_SAMPLE)
      output.writeLittleEndianShort(BITS_PER_TARGET_SAMPLE)
      output.writeBytes("data")
      output.writeLittleEndianInt(pcm.size)
      output.write(pcm)
    }
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

  private fun MediaFormat.integerOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

  private fun DataOutputStream.writeLittleEndianInt(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value ushr 8) and 0xFF)
    writeByte((value ushr 16) and 0xFF)
    writeByte((value ushr 24) and 0xFF)
  }

  private fun DataOutputStream.writeLittleEndianShort(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value ushr 8) and 0xFF)
  }

  private companion object {
    const val DEQUEUE_TIMEOUT_US = 10_000L
    const val TARGET_SAMPLE_RATE = 16_000
    const val TARGET_CHANNEL_COUNT = 1
    const val BITS_PER_TARGET_SAMPLE = 16
    const val BYTES_PER_TARGET_SAMPLE = BITS_PER_TARGET_SAMPLE / 8
  }
}
