/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.io.IOException

/** Container metadata Android's extractors omit, without loading native MediaInfo or decoding video. */
@OptIn(UnstableApi::class)
internal object ContainerMetadataProbe {
  private const val TAG = "ContainerMetadataProbe"
  private const val MAX_PROBE_BYTES = 16 * 1024 * 1024
  private const val MAX_SEEKS = 8
  private const val FPS_SAMPLE_COUNT = 48

  suspend fun read(
    context: Context,
    uri: Uri,
    readFrameRate: Boolean,
  ): MediaInfoOps.VideoMetadata? {
    val coroutineContext = currentCoroutineContext()
    val dataSource = DefaultDataSource.Factory(context).createDataSource()
    val extractors = DefaultExtractorsFactory().setTextTrackTranscodingEnabled(false).createExtractors()
    val tracks = linkedMapOf<Int, MetadataTrack>()
    var tracksEnded = false
    var durationMs = 0L
    var sizeBytes = 0L
    var bytesRead = 0
    val reader = DataReader { buffer, offset, length ->
      coroutineContext.ensureActive()
      val remaining = MAX_PROBE_BYTES - bytesRead
      if (remaining <= 0) throw IOException("Container metadata probe reached its byte limit")
      dataSource.read(buffer, offset, minOf(length, remaining)).also { if (it > 0) bytesRead += it }
    }

    fun openInput(position: Long): DefaultExtractorInput {
      val remaining = dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
      val length = if (remaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + remaining
      if (length > 0L) sizeBytes = length
      return DefaultExtractorInput(reader, position, length)
    }

    try {
      var input = openInput(0L)
      val extractor = extractors.firstOrNull { candidate ->
        try {
          candidate.sniff(input)
        } catch (_: EOFException) {
          false
        } finally {
          input.resetPeekPosition()
        }
      }
      if (extractor == null) {
        Log.d(TAG, "No container extractor for ${uri.lastPathSegment}")
        return null
      }
      extractor.init(object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput =
          tracks.getOrPut(id) { MetadataTrack(type, readFrameRate) }

        override fun endTracks() {
          tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {
          durationMs = seekMap.durationUs.takeIf { it > 0L }?.div(1000L) ?: 0L
        }
      })
      val seekPosition = PositionHolder()
      var seeks = 0
      while (!tracksEnded || tracks.values.any { it.needsSamples }) {
        coroutineContext.ensureActive()
        when (extractor.read(input, seekPosition)) {
          Extractor.RESULT_END_OF_INPUT -> break
          Extractor.RESULT_SEEK -> {
            if (++seeks > MAX_SEEKS) throw IOException("Container metadata probe reached its seek limit")
            dataSource.close()
            input = openInput(seekPosition.position)
          }
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: IOException) {
      Log.w(TAG, "Could not finish container metadata probe for ${uri.lastPathSegment}", error)
    } catch (error: RuntimeException) {
      Log.w(TAG, "Could not parse container metadata for ${uri.lastPathSegment}", error)
    } finally {
      extractors.forEach { it.release() }
      DataSourceUtil.closeQuietly(dataSource)
    }

    val video = tracks.values.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.trackFormat != null }
    val textTracks = tracks.values.filter { it.type == C.TRACK_TYPE_TEXT }
    if (video == null && textTracks.isEmpty()) return null
    val mime = video?.trackFormat?.sampleMimeType.orEmpty()
    val codec = VideoCodecSupportInspector.descriptor(mime, mime)
    return MediaInfoOps.VideoMetadata(
      sizeBytes = sizeBytes,
      durationMs = durationMs,
      width = video?.trackFormat?.width?.coerceAtLeast(0) ?: 0,
      height = video?.trackFormat?.height?.coerceAtLeast(0) ?: 0,
      fps = video?.frameRate ?: 0f,
      hasEmbeddedSubtitles = textTracks.isNotEmpty(),
      subtitleCodec = textTracks.map { track ->
        MediaInfoOps.subtitleLabel(track.trackFormat?.sampleMimeType.orEmpty()).ifBlank { "TEXT" }
      }.distinct().joinToString(" "),
      videoCodec = codec.label,
      videoCodecMimeType = codec.mimeType,
    )
  }

  private class MetadataTrack(
    val type: Int,
    private val readFrameRate: Boolean,
  ) : TrackOutput by DiscardingTrackOutput() {
    var trackFormat: Format? = null
      private set
    private val timestamps = mutableSetOf<Long>()
    private val declaredFrameRate: Float?
      get() = trackFormat?.frameRate?.takeIf { it.isFinite() && it > 0f }
    val needsSamples: Boolean
      get() = type == C.TRACK_TYPE_VIDEO && readFrameRate &&
        declaredFrameRate == null && timestamps.size < FPS_SAMPLE_COUNT
    val frameRate: Float
      get() {
        declaredFrameRate?.let { return it }
        // Presentation timestamps can arrive out of order when the stream contains B-frames.
        val intervals = timestamps.sorted().zipWithNext { first, second -> second - first }
        if (intervals.isEmpty()) return 0f
        return (1_000_000.0 / intervals.average()).toFloat().takeIf { it.isFinite() && it > 0f } ?: 0f
      }

    override fun format(format: Format) {
      trackFormat = format
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
      if (needsSamples && timeUs >= 0L) timestamps += timeUs
    }
  }
}
