/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import kotlinx.serialization.Serializable

enum class CodecFilter {
  ALL, HARDWARE, SOFTWARE, VIDEO, AUDIO
}

enum class CodecMediaType {
  VIDEO, AUDIO
}

data class CodecCapabilitiesInfo(
  val name: String,
  val canonicalName: String,
  val isHardware: Boolean,
  val mediaType: CodecMediaType,
  val mimeType: String,
  val formatName: String,
  val maxResolution: String? = null,
  val minResolution: String? = null,
  val maxFrameRate: Int? = null,
  val bitrateRange: String? = null,
  val profilesAndLevels: List<String> = emptyList(),
  val colorFormats: List<String> = emptyList(),
  val features: List<String> = emptyList(),
  val sampleRates: List<String> = emptyList(),
  val maxChannels: Int? = null,
  val isVendor: Boolean = false,
  val isAlias: Boolean = false,
  val maxInstances: Int? = null,
  val isHdrSupported: Boolean = false,
  val alignment: String? = null,
)

data class KeyCodecStatus(
  val formatName: String,
  val mimeType: String,
  val hasHardware: Boolean,
  val hasSoftware: Boolean,
  val decoderName: String? = null,
  val systemDefaultDecoder: String? = null,
  val maxResolution: String? = null,
  val isHdrSupported: Boolean = false,
)

object CodecInspector {
  fun inspectCodecs(context: Context): List<CodecCapabilitiesInfo> {
    val results = mutableListOf<CodecCapabilitiesInfo>()
    try {
      val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
      val infos = codecList.codecInfos

      for (info in infos) {
        if (info.isEncoder) continue // Only inspect decoders used for playback

        val isHw = isHardwareDecoder(info)
        val isVendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          info.isVendor
        } else {
          !isSoftwareName(info.name)
        }
        val isAlias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isAlias else false

        for (mime in info.supportedTypes) {
          val mediaType = when {
            mime.startsWith("video/", ignoreCase = true) -> CodecMediaType.VIDEO
            mime.startsWith("audio/", ignoreCase = true) -> CodecMediaType.AUDIO
            else -> continue
          }

          var maxRes: String? = null
          var minRes: String? = null
          var maxFps: Int? = null
          var maxInst: Int? = null
          var bitrateStr: String? = null
          var alignStr: String? = null
          var isHdr = false
          var maxChan: Int? = null

          val profLevelList = mutableListOf<String>()
          val colorList = mutableListOf<String>()
          val featureList = mutableListOf<String>()
          val sampleRateList = mutableListOf<String>()

          try {
            val caps = info.getCapabilitiesForType(mime)

            // Hardware Features inspection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
              if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)) {
                featureList.add("Adaptive Res")
              }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
              if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) {
                featureList.add("Direct Tunneling")
              }
              if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)) {
                featureList.add("Hardware DRM (L1)")
              }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
              if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                featureList.add("Low Latency")
              }
            }

            if (mediaType == CodecMediaType.VIDEO) {
              val videoCaps = caps.videoCapabilities
              if (videoCaps != null) {
                val maxW = videoCaps.supportedWidths.upper
                val maxH = videoCaps.supportedHeights.upper
                val minW = videoCaps.supportedWidths.lower
                val minH = videoCaps.supportedHeights.lower
                val maxF = videoCaps.supportedFrameRates.upper.toInt()

                maxRes = "${maxW}x${maxH} @ ${maxF}fps"
                minRes = "${minW}x${minH}"
                maxFps = maxF
                alignStr = "${videoCaps.widthAlignment}x${videoCaps.heightAlignment}"

                val bitRange = videoCaps.bitrateRange
                if (bitRange != null) {
                  bitrateStr = "${formatBitrate(bitRange.lower)} - ${formatBitrate(bitRange.upper)}"
                }
              }

              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                maxInst = caps.maxSupportedInstances
              }

              // Color formats
              val colors = caps.colorFormats
              if (colors != null) {
                for (cf in colors) {
                  val cfName = getColorFormatName(cf)
                  if (cfName !in colorList) {
                    colorList.add(cfName)
                  }
                }
              }

              // Profiles and levels
              val profLevels = caps.profileLevels
              if (profLevels != null) {
                for (pl in profLevels) {
                  val pName = getProfileAndLevelName(mime, pl.profile, pl.level)
                  if (pName != null && pName !in profLevelList) {
                    profLevelList.add(pName)
                  }
                  if (checkIsHdrProfile(mime, pl.profile)) {
                    isHdr = true
                  }
                }
              }

              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)) {
                  isHdr = true
                }
              }
            } else if (mediaType == CodecMediaType.AUDIO) {
              val audioCaps = caps.audioCapabilities
              if (audioCaps != null) {
                maxChan = audioCaps.maxInputChannelCount
                val rates = audioCaps.supportedSampleRates
                if (rates != null && rates.isNotEmpty()) {
                  sampleRateList.addAll(rates.map { "${it / 1000.0} kHz" })
                }
                val bitRange = audioCaps.bitrateRange
                if (bitRange != null) {
                  bitrateStr = "${formatBitrate(bitRange.lower)} - ${formatBitrate(bitRange.upper)}"
                }
              }
            }
          } catch (_: Exception) {
            // Ignore capability query exceptions for vendor decoders
          }

          results.add(
            CodecCapabilitiesInfo(
              name = info.name,
              canonicalName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.canonicalName else info.name,
              isHardware = isHw,
              mediaType = mediaType,
              mimeType = mime,
              formatName = getFormatName(mime),
              maxResolution = maxRes,
              minResolution = minRes,
              maxFrameRate = maxFps,
              bitrateRange = bitrateStr,
              profilesAndLevels = profLevelList,
              colorFormats = colorList,
              features = featureList,
              sampleRates = sampleRateList,
              maxChannels = maxChan,
              isVendor = isVendor,
              isAlias = isAlias,
              maxInstances = maxInst,
              isHdrSupported = isHdr,
              alignment = alignStr,
            )
          )
        }
      }
    } catch (_: Exception) {
      // Fallback
    }

    return results.sortedWith(
      compareBy({ !it.isHardware }, { it.mediaType }, { it.formatName }, { it.name })
    )
  }

  fun getKeyVideoCodecs(codecs: List<CodecCapabilitiesInfo>): List<KeyCodecStatus> {
    val keyFormats = listOf(
      "video/avc" to "H.264 / AVC",
      "video/hevc" to "H.265 / HEVC",
      "video/av01" to "AV1",
      "video/x-vnd.on2.vp9" to "VP9",
    )

    return keyFormats.map { (mime, label) ->
      val matching = codecs.filter { it.mimeType.equals(mime, ignoreCase = true) }
      val hwMatch = matching.firstOrNull { it.isHardware }
      val anyMatch = matching.firstOrNull()
      val sysDefault = getSystemDefaultDecoder(mime)

      KeyCodecStatus(
        formatName = label,
        mimeType = mime,
        hasHardware = hwMatch != null,
        hasSoftware = matching.any { !it.isHardware },
        decoderName = hwMatch?.name ?: anyMatch?.name,
        systemDefaultDecoder = sysDefault,
        maxResolution = hwMatch?.maxResolution ?: anyMatch?.maxResolution,
        isHdrSupported = matching.any { it.isHdrSupported },
      )
    }
  }

  private fun getSystemDefaultDecoder(mime: String): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      try {
        val format = if (mime.startsWith("video/")) {
          MediaFormat.createVideoFormat(mime, 1920, 1080)
        } else {
          MediaFormat.createAudioFormat(mime, 48000, 2)
        }
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecList.findDecoderForFormat(format)
      } catch (_: Exception) {
      }
    }
    return null
  }

  private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return info.isHardwareAccelerated
    }
    return !isSoftwareName(info.name)
  }

  private fun isSoftwareName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("omx.google.") ||
      lower.startsWith("c2.android.") ||
      lower.startsWith("omx.ffmpeg.") ||
      lower.contains(".sw.") ||
      lower.contains("software")
  }

  private fun getFormatName(mime: String): String {
    return when (mime.lowercase()) {
      "video/avc" -> "H.264 / AVC"
      "video/hevc" -> "H.265 / HEVC"
      "video/av01" -> "AV1"
      "video/x-vnd.on2.vp9" -> "VP9"
      "video/x-vnd.on2.vp8" -> "VP8"
      "video/mp4v-es" -> "MPEG-4 Part 2"
      "video/3gpp" -> "H.263"
      "video/mpeg2" -> "MPEG-2"
      "video/vc1", "video/x-ms-wmv", "video/wvc1" -> "VC-1 / WMV"
      "video/divx" -> "DivX"
      "video/x-flv" -> "FLV Video"
      "video/raw" -> "Raw Uncompressed Video"
      "video/mjpeg" -> "Motion JPEG"
      "video/dolby-vision" -> "Dolby Vision"
      "audio/mp4a-latm" -> "AAC"
      "audio/mpeg" -> "MP3"
      "audio/mpeg-l2" -> "MP2"
      "audio/flac" -> "FLAC"
      "audio/opus" -> "Opus"
      "audio/vorbis" -> "Vorbis"
      "audio/ac3" -> "AC-3 (Dolby Digital)"
      "audio/eac3" -> "E-AC-3 (Dolby Digital Plus)"
      "audio/eac3-joc" -> "E-AC-3 JOC (Dolby Atmos)"
      "audio/ac4" -> "AC-4"
      "audio/dts" -> "DTS"
      "audio/dts-hd" -> "DTS-HD"
      "audio/dts-uhd" -> "DTS:X / DTS-UHD"
      "audio/raw" -> "PCM (Uncompressed Audio)"
      "audio/alac" -> "ALAC (Apple Lossless)"
      "audio/amr-wb" -> "AMR-WB"
      "audio/3gpp" -> "AMR-NB"
      "audio/g711-alaw" -> "G.711 a-law"
      "audio/g711-mlaw" -> "G.711 µ-law"
      "audio/gsm" -> "GSM Audio"
      "audio/wma", "audio/x-ms-wma" -> "WMA"
      "audio/wma-lossless" -> "WMA Lossless"
      "audio/truehd" -> "Dolby TrueHD"
      "image/vnd.android.heic" -> "HEIC Image"
      "image/avif" -> "AVIF Image"
      else -> mime.removePrefix("video/").removePrefix("audio/").removePrefix("image/").uppercase()
    }
  }

  private fun getProfileAndLevelName(mime: String, profile: Int, level: Int): String? {
    val pName = getProfileName(mime, profile) ?: "Profile $profile"
    val lName = getLevelName(mime, level)
    return if (lName != null) "$pName ($lName)" else pName
  }

  private fun getProfileName(mime: String, profile: Int): String? {
    if (mime.equals("video/hevc", ignoreCase = true)) {
      return when (profile) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main 10 (10-bit)"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
        else -> null
      }
    }
    if (mime.equals("video/avc", ignoreCase = true)) {
      return when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High 10"
        else -> null
      }
    }
    if (mime.equals("video/av01", ignoreCase = true)) {
      return when (profile) {
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8 -> "Main (8-bit)"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 -> "Main 10 (10-bit)"
        else -> null
      }
    }
    if (mime.equals("video/x-vnd.on2.vp9", ignoreCase = true)) {
      return when (profile) {
        MediaCodecInfo.CodecProfileLevel.VP9Profile0 -> "Profile 0 (8-bit)"
        MediaCodecInfo.CodecProfileLevel.VP9Profile2 -> "Profile 2 (10-bit)"
        else -> null
      }
    }
    return null
  }

  private fun getLevelName(mime: String, level: Int): String? {
    if (mime.equals("video/hevc", ignoreCase = true)) {
      return when (level) {
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1 -> "Level 1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2 -> "Level 2"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21 -> "Level 2.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3 -> "Level 3"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31 -> "Level 3.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4 -> "Level 4"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41 -> "Level 4.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5 -> "Level 5"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51 -> "Level 5.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52 -> "Level 5.2"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6 -> "Level 6"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61 -> "Level 6.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62 -> "Level 6.2"
        else -> null
      }
    }
    if (mime.equals("video/avc", ignoreCase = true)) {
      return when (level) {
        MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> "Level 1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> "Level 1.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> "Level 1.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> "Level 1.3"
        MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> "Level 2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> "Level 2.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> "Level 2.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> "Level 3"
        MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> "Level 3.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> "Level 3.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> "Level 4"
        MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> "Level 4.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> "Level 4.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel5 -> "Level 5"
        MediaCodecInfo.CodecProfileLevel.AVCLevel51 -> "Level 5.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel52 -> "Level 5.2"
        else -> null
      }
    }
    return null
  }

  @Suppress("DEPRECATION")
  private fun getColorFormatName(format: Int): String {
    return when (format) {
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "YUV 420 Planar"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> "YUV 420 Packed Planar"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "YUV 420 Semi-Planar (NV12)"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> "YUV 420 Packed Semi-Planar"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Planar -> "YUV 422 Planar"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedPlanar -> "YUV 422 Packed Planar"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422SemiPlanar -> "YUV 422 Semi-Planar"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Interleaved -> "YUV 444 Interleaved"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> "Surface (Hardware Texture)"
      MediaCodecInfo.CodecCapabilities.COLOR_Format24bitRGB888 -> "24-bit RGB 888"
      MediaCodecInfo.CodecCapabilities.COLOR_Format32bitARGB8888 -> "32-bit ARGB 8888"
      MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR8888 -> "32-bit ABGR 8888"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "YUV 420 Flexible"
      0x7F000789 -> "P010 (10-bit YUV)"
      else -> "0x${Integer.toHexString(format).uppercase()}"
    }
  }

  private fun formatBitrate(bps: Int): String {
    return when {
      bps >= 1_000_000 -> "${bps / 1_000_000} Mbps"
      bps >= 1_000 -> "${bps / 1_000} kbps"
      else -> "$bps bps"
    }
  }

  private fun checkIsHdrProfile(mime: String, profile: Int): Boolean {
    if (mime.equals("video/hevc", ignoreCase = true)) {
      if (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) return true
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
        profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
      ) return true
    }
    if (mime.equals("video/av01", ignoreCase = true)) {
      if (profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10) return true
    }
    if (mime.equals("video/x-vnd.on2.vp9", ignoreCase = true)) {
      if (profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2) return true
    }
    return false
  }
}

@Serializable
object CodecCapabilitiesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current

    val codecs = remember { CodecInspector.inspectCodecs(context) }
    val keyVideoCodecs = remember(codecs) { CodecInspector.getKeyVideoCodecs(codecs) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(CodecFilter.ALL) }

    val hwCount = remember(codecs) { codecs.count { it.isHardware } }
    val swCount = remember(codecs) { codecs.count { !it.isHardware } }
    val videoCount = remember(codecs) { codecs.count { it.mediaType == CodecMediaType.VIDEO } }
    val audioCount = remember(codecs) { codecs.count { it.mediaType == CodecMediaType.AUDIO } }

    val filteredCodecs = remember(codecs, searchQuery, selectedFilter) {
      codecs.filter { item ->
        val matchesFilter = when (selectedFilter) {
          CodecFilter.ALL -> true
          CodecFilter.HARDWARE -> item.isHardware
          CodecFilter.SOFTWARE -> !item.isHardware
          CodecFilter.VIDEO -> item.mediaType == CodecMediaType.VIDEO
          CodecFilter.AUDIO -> item.mediaType == CodecMediaType.AUDIO
        }

        val matchesSearch = if (searchQuery.isBlank()) {
          true
        } else {
          val query = searchQuery.lowercase().trim()
          item.name.lowercase().contains(query) ||
            item.mimeType.lowercase().contains(query) ||
            item.formatName.lowercase().contains(query) ||
            item.profilesAndLevels.any { it.lowercase().contains(query) } ||
            item.features.any { it.lowercase().contains(query) }
        }

        matchesFilter && matchesSearch
      }
    }

    val copiedToastMsg = stringResource(R.string.pref_codecs_report_copied)

    val copyReportToClipboard = {
      val sb = StringBuilder()
      sb.appendLine("=== mpvRx Hardware vs Software Codec Diagnostics ===")
      sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
      sb.appendLine("Decoders: $hwCount Hardware Accelerated, $swCount Software Fallback ($videoCount Video, $audioCount Audio)")
      sb.appendLine()
      sb.appendLine("--- Core Video Formats ---")
      for (k in keyVideoCodecs) {
        val decoderType =
          when {
            k.hasHardware -> "Hardware"
            k.hasSoftware -> "Software"
            else -> "Unsupported"
          }
        val decoderText = k.decoderName?.let { "$decoderType ($it)" } ?: decoderType
        val sysDefText = if (k.systemDefaultDecoder != null) " [Sys Default: ${k.systemDefaultDecoder}]" else ""
        val resText = if (k.maxResolution != null) " [Max: ${k.maxResolution}]" else ""
        val hdrText = if (k.isHdrSupported) " [10-Bit HDR Supported]" else ""
        sb.appendLine("${k.formatName}: $decoderText$sysDefText$resText$hdrText")
      }
      sb.appendLine()
      sb.appendLine("--- Full Decoder Registry ---")
      for (c in codecs) {
        val hwTag = if (c.isHardware) "[HW]" else "[SW]"
        val resStr = c.maxResolution?.let { " ($it)" } ?: ""
        val bitStr = c.bitrateRange?.let { " Bitrate: $it" } ?: ""
        val featStr = if (c.features.isNotEmpty()) " Features: ${c.features.joinToString(", ")}" else ""
        sb.appendLine("$hwTag ${c.formatName} (${c.mimeType}) -> ${c.name}$resStr$bitStr$featStr")
      }

      SafeClipboard.copyPlainText(context, "Codec Report", sb.toString())
      Toast.makeText(context, copiedToastMsg, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text(
                modifier = Modifier.settingsSearchTarget(R.string.pref_codecs_title),
                text = stringResource(R.string.pref_codecs_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} • API ${Build.VERSION.SDK_INT}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
              )
            }
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backstack.popSafely() }) {
                Icon(
                  imageVector = Icons.RoundedFilled.ArrowBack,
                  contentDescription = stringResource(id = R.string.generic_cancel),
                  tint = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          },
          actions = {
            IconButton(onClick = copyReportToClipboard) {
              Icon(
                imageVector = Icons.RoundedFilled.ContentCopy,
                contentDescription = "Copy Spec Report",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
          ),
        )
      },
    ) { innerPadding ->
      val (settingsListState, settingsHighlight) =
        rememberSettingsSearchList(CodecCapabilitiesScreen, MaterialTheme.colorScheme.primary)
      LazyColumn(
        state = settingsListState,
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 16.dp)
          .then(settingsHighlight),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Hero Diagnostics Summary Banner with Live Stat Pills
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            shape = RoundedCornerShape(20.dp),
          ) {
            Column(modifier = Modifier.padding(18.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                    modifier = Modifier
                      .size(42.dp)
                      .clip(CircleShape)
                      .background(
                        Brush.linearGradient(
                          colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                          )
                        )
                      ),
                    contentAlignment = Alignment.Center,
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Info,
                      contentDescription = null,
                      tint = Color.White,
                      modifier = Modifier.size(24.dp),
                    )
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Column {
                    Text(
                      text = stringResource(R.string.pref_codecs_banner_title),
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                      text = stringResource(R.string.pref_codecs_decoders_summary, hwCount, swCount),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }

              Spacer(modifier = Modifier.height(14.dp))

              // Live Counter Badges Row
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                StatCounterChip(
                  modifier = Modifier.weight(1f),
                  count = hwCount,
                  label = "Hardware",
                  icon = Icons.RoundedFilled.DeveloperBoard,
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                StatCounterChip(
                  modifier = Modifier.weight(1f),
                  count = swCount,
                  label = "Software",
                  icon = Icons.RoundedFilled.Code,
                  containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                  contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                StatCounterChip(
                  modifier = Modifier.weight(1f),
                  count = videoCount,
                  label = "Video",
                  icon = Icons.RoundedFilled.Videocam,
                  containerColor = MaterialTheme.colorScheme.secondaryContainer,
                  contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }

              Spacer(modifier = Modifier.height(12.dp))

              Text(
                text = stringResource(R.string.pref_codecs_banner_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
              )
            }
          }
        }

        // Essential Core Video Codecs Overview Cards Section
        item {
          Column {
            Text(
              text = stringResource(R.string.pref_codecs_core_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(bottom = 10.dp),
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              for (k in keyVideoCodecs) {
                KeyCodecStatusCard(status = k)
              }
            }
          }
        }

        // Search Field & Filter Chips
        item {
          Column {
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              modifier = Modifier.fillMaxWidth(),
              placeholder = { Text(stringResource(R.string.pref_codecs_search_placeholder)) },
              leadingIcon = {
                Icon(
                  imageVector = Icons.RoundedFilled.Search,
                  contentDescription = "Search",
                )
              },
              trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                  IconButton(onClick = { searchQuery = "" }) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Close,
                      contentDescription = "Clear",
                    )
                  }
                }
              },
              singleLine = true,
              shape = RoundedCornerShape(16.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              ),
            )

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              FilterChip(
                selected = selectedFilter == CodecFilter.ALL,
                onClick = { selectedFilter = CodecFilter.ALL },
                label = { Text(stringResource(R.string.pref_codecs_filter_all, codecs.size)) },
                shape = RoundedCornerShape(12.dp),
              )
              FilterChip(
                selected = selectedFilter == CodecFilter.HARDWARE,
                onClick = { selectedFilter = CodecFilter.HARDWARE },
                label = { Text(stringResource(R.string.pref_codecs_filter_hardware, hwCount)) },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
              )
              FilterChip(
                selected = selectedFilter == CodecFilter.SOFTWARE,
                onClick = { selectedFilter = CodecFilter.SOFTWARE },
                label = { Text(stringResource(R.string.pref_codecs_filter_software, swCount)) },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
              )
              FilterChip(
                selected = selectedFilter == CodecFilter.VIDEO,
                onClick = { selectedFilter = CodecFilter.VIDEO },
                label = { Text(stringResource(R.string.pref_codecs_filter_video, videoCount)) },
                shape = RoundedCornerShape(12.dp),
              )
              FilterChip(
                selected = selectedFilter == CodecFilter.AUDIO,
                onClick = { selectedFilter = CodecFilter.AUDIO },
                label = { Text(stringResource(R.string.pref_codecs_filter_audio, audioCount)) },
                shape = RoundedCornerShape(12.dp),
              )
            }
          }
        }

        // Section Title for Filtered Codecs List
        item {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = stringResource(R.string.pref_codecs_list_header, filteredCodecs.size),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            if (searchQuery.isNotEmpty()) {
              Text(
                text = "${filteredCodecs.size} matches",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }

        if (filteredCodecs.isEmpty()) {
          item {
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
              colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              ),
              shape = RoundedCornerShape(16.dp),
            ) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(32.dp),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = stringResource(R.string.pref_codecs_empty),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else {
          items(
            items = filteredCodecs,
            key = { "${it.name}_${it.mimeType}" },
            contentType = { "codec_card" },
          ) { codec ->
            CodecDetailCard(codec = codec)
          }
        }

        item {
          Spacer(modifier = Modifier.height(32.dp))
        }
      }
    }
  }
}

@Composable
private fun StatCounterChip(
  modifier: Modifier = Modifier,
  count: Int,
  label: String,
  icon: AppIcon,
  containerColor: Color,
  contentColor: Color,
) {
  Surface(
    modifier = modifier,
    color = containerColor,
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(15.dp),
        )
        Text(
          text = "$count",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Black,
          color = contentColor,
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = contentColor.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun KeyCodecStatusCard(status: KeyCodecStatus) {
  val isHw = status.hasHardware
  val isSw = !isHw && status.hasSoftware
  val badgeBg =
    when {
      isHw -> MaterialTheme.colorScheme.primary
      isSw -> MaterialTheme.colorScheme.tertiary
      else -> MaterialTheme.colorScheme.error
    }
  val badgeText =
    when {
      isHw -> MaterialTheme.colorScheme.onPrimary
      isSw -> MaterialTheme.colorScheme.onTertiary
      else -> MaterialTheme.colorScheme.onError
    }
  val cardBg =
    when {
      isHw -> MaterialTheme.colorScheme.surfaceContainerHigh
      isSw -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
      else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    }

  val noDecoderText = stringResource(R.string.pref_codecs_no_decoder)

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = cardBg),
    shape = RoundedCornerShape(16.dp),
    border = borderStrokeForHw(isHw),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = status.formatName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          if (status.isHdrSupported) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
              Text(
                text = stringResource(R.string.pref_codecs_hdr_tag),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }
        }

        Surface(
          color = badgeBg,
          shape = RoundedCornerShape(20.dp),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector =
                when {
                  isHw -> Icons.RoundedFilled.DeveloperBoard
                  isSw -> Icons.RoundedFilled.Code
                  else -> Icons.RoundedFilled.Block
                },
              contentDescription = null,
              tint = badgeText,
              modifier = Modifier.size(14.dp),
            )
            Text(
              text =
                when {
                  isHw -> stringResource(R.string.pref_codecs_badge_hardware)
                  isSw -> stringResource(R.string.pref_codecs_badge_software)
                  else -> "Unsupported"
                },
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Black,
              color = badgeText,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      Text(
        text = status.decoderName ?: noDecoderText,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )

      if (status.maxResolution != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = stringResource(R.string.pref_codecs_max_limit, status.maxResolution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

@Composable
private fun borderStrokeForHw(isHw: Boolean) = if (isHw) {
  androidx.compose.foundation.BorderStroke(
    1.dp,
    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
  )
} else {
  androidx.compose.foundation.BorderStroke(
    1.dp,
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodecDetailCard(codec: CodecCapabilitiesInfo) {
  var expanded by remember { mutableStateOf(false) }
  val arrowRotation by animateFloatAsState(
    targetValue = if (expanded) 180f else 0f,
    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
    label = "ArrowRotation",
  )

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize()
      .clickable { expanded = !expanded },
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
    shape = RoundedCornerShape(16.dp),
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          FlowRow(
            verticalArrangement = Arrangement.Center,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = codec.formatName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )

            Box(
              modifier = Modifier
                .clip(CircleShape)
                .background(
                  if (codec.mediaType == CodecMediaType.VIDEO) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                  } else {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                  }
                )
                .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
              Text(
                text = codec.mediaType.name,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                color = if (codec.mediaType == CodecMediaType.VIDEO) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.secondary
                },
              )
            }

            Surface(
              color = if (codec.isHardware) {
                MaterialTheme.colorScheme.primaryContainer
              } else {
                MaterialTheme.colorScheme.tertiaryContainer
              },
              shape = RoundedCornerShape(12.dp),
            ) {
              Text(
                text = if (codec.isHardware) {
                  stringResource(R.string.pref_codecs_badge_hw_short)
                } else {
                  stringResource(R.string.pref_codecs_badge_sw_short)
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (codec.isHardware) {
                  MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                  MaterialTheme.colorScheme.onTertiaryContainer
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }

          Spacer(modifier = Modifier.height(2.dp))

          Text(
            text = codec.name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) 3 else 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
          imageVector = Icons.RoundedFilled.KeyboardArrowDown,
          contentDescription = "Toggle Details",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .size(24.dp)
            .rotate(arrowRotation),
        )
      }

      if (codec.maxResolution != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = stringResource(R.string.pref_codecs_max_resolution, codec.maxResolution),
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (codec.bitrateRange != null) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = stringResource(R.string.pref_codecs_bitrate_range, codec.bitrateRange),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
      }

      AnimatedVisibility(visible = expanded) {
        Column(modifier = Modifier.padding(top = 12.dp)) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(1.dp)
              .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
          )

          Spacer(modifier = Modifier.height(10.dp))

          Text(
            text = stringResource(R.string.pref_codecs_mime_type, codec.mimeType),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (codec.canonicalName != codec.name) {
            Text(
              text = stringResource(R.string.pref_codecs_canonical_name, codec.canonicalName),
              style = MaterialTheme.typography.labelMedium,
              fontFamily = FontFamily.Monospace,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          if (codec.minResolution != null) {
            Text(
              text = stringResource(R.string.pref_codecs_min_resolution, codec.minResolution),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          if (codec.alignment != null) {
            val alignParts = codec.alignment.split("x")
            val wAlign = alignParts.firstOrNull()?.toIntOrNull() ?: 1
            val hAlign = alignParts.lastOrNull()?.toIntOrNull() ?: 1
            Text(
              text = stringResource(R.string.pref_codecs_alignment, wAlign, hAlign),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          if (codec.maxInstances != null && codec.maxInstances > 0) {
            Text(
              text = stringResource(R.string.pref_codecs_max_instances, codec.maxInstances),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          if (codec.maxChannels != null && codec.maxChannels > 0) {
            Text(
              text = stringResource(R.string.pref_codecs_max_channels, codec.maxChannels),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          if (codec.features.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = stringResource(R.string.pref_codecs_hardware_features),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              for (feat in codec.features) {
                Surface(
                  color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text(
                    text = feat,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                  )
                }
              }
            }
          }

          if (codec.colorFormats.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = stringResource(R.string.pref_codecs_color_formats),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              for (cf in codec.colorFormats) {
                Surface(
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text(
                    text = cf,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                  )
                }
              }
            }
          }

          if (codec.sampleRates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = stringResource(R.string.pref_codecs_sample_rates),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              for (sr in codec.sampleRates) {
                Surface(
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text(
                    text = sr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                  )
                }
              }
            }
          }

          if (codec.profilesAndLevels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = stringResource(R.string.pref_codecs_supported_profiles),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              for (prof in codec.profilesAndLevels) {
                Surface(
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text(
                    text = prof,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
