/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.media

import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.os.Build

enum class VideoDecodeSupport {
  HARDWARE,
  SOFTWARE,
  UNSUPPORTED,
  UNKNOWN,
}

data class VideoCodecDescriptor(
  val label: String,
  val mimeType: String,
)

data class VideoCodecSupport(
  val codecLabel: String,
  val decodeSupport: VideoDecodeSupport,
)

object VideoCodecSupportInspector {
  private data class DecoderCapability(
    val isHardware: Boolean,
    val videoCapabilities: MediaCodecInfo.VideoCapabilities?,
  )

  private val decoderCapabilities by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val capabilities = mutableMapOf<String, MutableList<DecoderCapability>>()
    runCatching {
      MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .asSequence()
        .filterNot { it.isEncoder }
        .forEach { codecInfo ->
          val isHardware =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              codecInfo.isHardwareAccelerated
            } else {
              !isSoftwareDecoder(codecInfo.name)
            }
          codecInfo.supportedTypes.forEach { mimeType ->
            val key = mimeType.lowercase()
            val videoCapabilities =
              runCatching { codecInfo.getCapabilitiesForType(mimeType).videoCapabilities }.getOrNull()
            capabilities.getOrPut(key, ::mutableListOf).add(
              DecoderCapability(
                isHardware = isHardware,
                videoCapabilities = videoCapabilities,
              ),
            )
          }
        }
    }
    capabilities
  }

  fun descriptor(
    format: String,
    codecId: String,
  ): VideoCodecDescriptor {
    val signature = "$format $codecId".uppercase()
    return when {
      "DOLBY VISION" in signature || "DVHE" in signature || "DOVI" in signature ->
        VideoCodecDescriptor("DOLBY VISION", "video/dolby-vision")
      "HEVC" in signature || "H.265" in signature || "H265" in signature ->
        VideoCodecDescriptor("HEVC", "video/hevc")
      "AVC" in signature || "H.264" in signature || "H264" in signature ->
        VideoCodecDescriptor("H.264", "video/avc")
      "AV1" in signature || "AV01" in signature ->
        VideoCodecDescriptor("AV1", "video/av01")
      "VP9" in signature || "VP09" in signature ->
        VideoCodecDescriptor("VP9", "video/x-vnd.on2.vp9")
      "VP8" in signature || "VP08" in signature ->
        VideoCodecDescriptor("VP8", "video/x-vnd.on2.vp8")
      "MPEG-4 VISUAL" in signature || "MP4V" in signature ->
        VideoCodecDescriptor("MPEG-4", "video/mp4v-es")
      "MPEG VIDEO" in signature || "MPEG-2" in signature ->
        VideoCodecDescriptor("MPEG-2", "video/mpeg2")
      "H.263" in signature || "H263" in signature ->
        VideoCodecDescriptor("H.263", "video/3gpp")
      "VC-1" in signature || "WVC1" in signature ->
        VideoCodecDescriptor("VC-1", "video/wvc1")
      else ->
        VideoCodecDescriptor(
          label = format.ifBlank { codecId }.ifBlank { "UNKNOWN" }.uppercase(),
          mimeType = "",
        )
    }
  }

  fun inspect(
    codecLabel: String,
    mimeType: String,
    width: Int = 0,
    height: Int = 0,
    frameRate: Float = 0f,
  ): VideoCodecSupport {
    if (codecLabel.isBlank()) {
      return VideoCodecSupport("UNKNOWN", VideoDecodeSupport.UNKNOWN)
    }
    if (mimeType.isBlank()) {
      return VideoCodecSupport(codecLabel, VideoDecodeSupport.UNKNOWN)
    }

    val matchingDecoders =
      decoderCapabilities[mimeType.lowercase()].orEmpty().filter { decoder ->
        decoder.supports(width = width, height = height, frameRate = frameRate)
      }
    val support =
      when {
        matchingDecoders.any { it.isHardware } -> VideoDecodeSupport.HARDWARE
        matchingDecoders.any { !it.isHardware } -> VideoDecodeSupport.SOFTWARE
        else -> VideoDecodeSupport.UNSUPPORTED
      }
    return VideoCodecSupport(codecLabel, support)
  }

  private fun DecoderCapability.supports(
    width: Int,
    height: Int,
    frameRate: Float,
  ): Boolean {
    if (width <= 0 || height <= 0) return true
    val capabilities = videoCapabilities ?: return true
    return runCatching {
      if (frameRate > 0f) {
        capabilities.areSizeAndRateSupported(width, height, frameRate.toDouble()) ||
          capabilities.areSizeAndRateSupported(height, width, frameRate.toDouble())
      } else {
        capabilities.isSizeSupported(width, height) || capabilities.isSizeSupported(height, width)
      }
    }.getOrDefault(false)
  }

  private fun isSoftwareDecoder(name: String): Boolean {
    val normalized = name.lowercase()
    return normalized.startsWith("omx.google.") ||
      normalized.startsWith("c2.android.") ||
      normalized.startsWith("omx.ffmpeg.") ||
      normalized.contains(".sw.") ||
      normalized.contains("software")
  }
}