/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.mediainfo

import app.gyrolet.mpvrx.ui.player.PlaybackFormatBadge
import app.gyrolet.mpvrx.ui.player.audioChannelsBadge
import app.gyrolet.mpvrx.ui.player.audioFormatBadges
import app.gyrolet.mpvrx.ui.player.bitDepthBadge
import app.gyrolet.mpvrx.ui.player.hdrBadges
import app.gyrolet.mpvrx.ui.player.resolutionBadge
import app.gyrolet.mpvrx.ui.player.sourceBadge
import app.gyrolet.mpvrx.ui.player.subtitleBadge
import app.gyrolet.mpvrx.ui.player.videoCodecBadge
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import java.util.Locale

internal fun MediaInfoOps.MediaInfoData.overviewBadges(fileName: String): List<PlaybackFormatBadge> =
  buildList {
    val container = general.format.trim().lowercase(Locale.ROOT)
    val mpvContainer = when (container) {
      "mpeg-4", "quicktime" -> "mp4"
      "mpeg-ts", "mpeg ts" -> "mpegts"
      "mpeg-audio", "mpeg audio" -> "mp3"
      else -> container
    }
    sourceBadge(mpvContainer, fileName, general.completeName.substringAfterLast('/').substringAfterLast('\\'))?.let(::add)
    videoStreams.forEach { addAll(it.formatBadges()) }
    audioStreams.forEach { addAll(it.formatBadges()) }
    textStreams.forEach { addAll(it.formatBadges()) }
  }.distinctBy(PlaybackFormatBadge::label)

internal fun MediaInfoOps.VideoStreamInfo.formatBadges(): List<PlaybackFormatBadge> = buildList {
  val w = width.reportedInt()?.takeIf { it in 1..65_535 }
  val h = height.reportedInt()?.takeIf { it in 1..65_535 }
  if (w != null && h != null) add(resolutionBadge(w, h))
  val name = format.trim().lowercase(Locale.ROOT)
  val codec = when {
    codecId.equals("XVID", ignoreCase = true) -> "xvid"
    name in setOf("avc", "h.264", "video/avc") -> "h264"
    name in setOf("hevc", "h.265", "video/hevc") -> "hevc"
    name in setOf("av1", "video/av01") -> "av1"
    name == "mpeg-4 visual" -> "mpeg4"
    name == "mpeg video" && formatProfile.contains("mpeg-2", ignoreCase = true) -> "mpeg2video"
    else -> name
  }
  (videoCodecBadge(codec) ?: format.textBadge())?.let(::add)
  addAll(hdrBadges(listOf(hdrFormat, hdrFormatCommercial).joinToString(" / ")))
  bitDepth.reportedInt()?.takeIf { it in 1..64 }?.let { add(bitDepthBadge(it)) }
}

internal fun MediaInfoOps.AudioStreamInfo.formatBadges(): List<PlaybackFormatBadge> = buildList {
  val name = format.trim().lowercase(Locale.ROOT)
  val part = codecId.uppercase(Locale.ROOT)
  val codec = when {
    name in setOf("e-ac-3 joc", "audio/eac3-joc") -> "eac3-joc"
    part == "A_EAC3" || name in setOf("e-ac-3", "audio/eac3") -> "eac3"
    part == "A_AC3" || name in setOf("ac-3", "audio/ac3") -> "ac3"
    part == "A_TRUEHD" || name in setOf("mlp fba", "mlp fba 16-ch", "truehd", "audio/true-hd") -> "truehd"
    part == "A_DTS" || name.startsWith("dts") -> "dts"
    name.startsWith("aac") || name == "audio/mp4a-latm" -> "aac"
    name == "mpeg audio" && (formatProfile.contains("Layer 3", true) || part == "A_MPEG/L3") -> "mp3"
    name in setOf("monkey's audio", "monkey’s audio") -> "ape"
    name in setOf("musepack sv7", "musepack sv8") -> "mpc8"
    name == "pcm" -> "pcm"
    name.startsWith("audio/") -> name.removePrefix("audio/")
    else -> name
  }
  val dtsProfile = when (name) {
    "dts xll" -> "DTS-HD MA"
    "dts xll x" -> "DTS-HD MA / DTS:X"
    "dts xbr" -> "DTS-HD HRA"
    else -> ""
  }
  val profile = listOf(format, formatProfile, commercialFormat, additionalFeatures, dtsProfile)
    .filter(String::isNotBlank).joinToString(" / ")
  addAll(audioFormatBadges(codec, profile, additionalFeatures, title))
  val speakers = channelLayout.trim().split(Regex("\\s+"))
  val layout = if (speakers.size > 2 && speakers.all { it.lowercase(Locale.ROOT) in MEDIAINFO_SPEAKERS }) {
    val subs = speakers.count { it.startsWith("lfe", ignoreCase = true) }
    "${speakers.size - subs}.$subs"
  } else channelLayout
  audioChannelsBadge(layout, channels.reportedInt()?.toLong())?.let(::add)
  bitDepth.reportedInt()?.takeIf { it in 1..64 }?.let { add(bitDepthBadge(it)) }
}

internal fun MediaInfoOps.TextStreamInfo.formatBadges(): List<PlaybackFormatBadge> {
  val formatName = format.lowercase(Locale.ROOT)
  val closedCaptions = formatName in setOf("eia-608", "cea-608", "eia-708", "cea-708")
  val isSdh = hearingImpaired || SDH_TITLE.containsMatchIn(title)
  return listOfNotNull(
    subtitleBadge(if (closedCaptions) formatName.replace('-', '_') else formatName, isSdh) ?: format.textBadge(),
  )
}

private fun String.textBadge(): PlaybackFormatBadge? =
  trim().takeIf { it.isNotEmpty() && it != "---" }?.let { PlaybackFormatBadge(it) }

private fun String.reportedInt(): Int? =
  substringBefore(" / ").trimStart()
    .takeWhile { it.isDigit() || it.isWhitespace() || it == '\u00a0' || it == '\u202f' }
    .filter(Char::isDigit)
    .toIntOrNull()

private val MEDIAINFO_SPEAKERS = setOf(
  "l", "r", "c", "lfe", "lfe2", "ls", "rs", "lss", "rss", "lb", "rb", "cs", "lc", "rc",
  "tfl", "tfr", "tfc", "tbl", "tbr", "tbc", "tcl", "tcr", "tc", "lrs", "rrs",
)
private val SDH_TITLE = Regex("""\bSDH\b""", RegexOption.IGNORE_CASE)
