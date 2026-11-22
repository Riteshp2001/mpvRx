/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.utils.media.fileExtension
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import java.util.Locale

internal data class PlaybackFormatBadge(
  val label: String,
  /** Relative to media_format_logos; null displays the format name as text. */
  val asset: String? = null,
  val artwork: BadgeArtwork = BadgeArtwork.ThemeInk,
  val detail: String = label,
  val isChannelLayout: Boolean = false,
)

internal enum class BadgeArtwork { ThemeInk, Original, OnLight, OnDark }

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observePlaybackFormatBadges(): Flow<List<PlaybackFormatBadge>> =
  PlaybackSession.state.map { state ->
    state.currentItem
      ?.takeIf { !it.isDefinitelyAudioOnly() && state.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND) }
      ?.let { state.generation to it }
  }.distinctUntilChanged().flatMapLatest { media ->
    if (media == null) {
      flowOf(emptyList())
    } else {
      val (generation, item) = media
      val changes = merge(
        PlaybackSession.propNode["track-list"].map { Unit },
        PlaybackSession.propString["file-format"].map { Unit },
        PlaybackSession.propString["vid"].map { Unit },
        PlaybackSession.propString["aid"].map { Unit },
        PlaybackSession.propString["sid"].map { Unit },
        PlaybackSession.propInt["video-params/w"].map { Unit },
        PlaybackSession.propInt["video-params/h"].map { Unit },
        PlaybackSession.propString["video-params/gamma"].map { Unit },
        PlaybackSession.propString["video-params/primaries"].map { Unit },
        PlaybackSession.propString["video-params/pixelformat"].map { Unit },
        PlaybackSession.propString["video-params/hw-pixelformat"].map { Unit },
        PlaybackSession.propString["video-params/stereo-in"].map { Unit },
        PlaybackSession.propString["audio-params/channels"].map { Unit },
        PlaybackSession.propInt["audio-params/channel-count"].map { Unit },
        PlaybackSession.propString["current-tracks/audio/codec-profile"].map { Unit },
      )
      changes.conflate().map {
        PlaybackSession.readLoadedMedia(generation) { readPlaybackFormatBadges(it, item) }.orEmpty()
      }.onStart { emit(emptyList()) }
    }
  }.distinctUntilChanged().flowOn(Dispatchers.IO)

private fun readPlaybackFormatBadges(
  media: PlaybackSession.PropertyReader,
  item: PlaybackItem,
): List<PlaybackFormatBadge> {
  val video = media.node("current-tracks/video")?.asMap().orEmpty()
  if ((video["id"]?.asInt() ?: 0L) <= 0L || video.flag("albumart") || video.flag("image")) return emptyList()

  val decodedVideo = media.node("video-dec-params")?.asMap()
    ?: media.node("video-params")?.asMap().orEmpty()
  val audio = media.node("current-tracks/audio")?.asMap().orEmpty()
  val decodedAudio = media.node("audio-params")?.asMap().orEmpty()
  val subtitle = media.node("current-tracks/sub")?.asMap().orEmpty()
  val filename = media.string("filename")
  val dimensions = decodedVideo.dimensions("w", "h")
    ?: video.dimensions("demux-w", "demux-h")
    ?: validDimensions(item.videoWidth.toLong(), item.videoHeight.toLong())
  val bitDepth = sequenceOf(decodedVideo.text("hw-pixelformat"), decodedVideo.text("pixelformat"))
    .firstNotNullOfOrNull(::pixelFormatBitDepth)
  val hdrTag = video["metadata"].hdrTag() ?: media.node("metadata").hdrTag()

  return buildList {
    val source = sourceBadge(
      media.string("file-format"),
      filename,
      item.title,
      item.networkSource?.relativePath?.substringAfterLast('/'),
    )
    source?.let(::add)
    dimensions?.let { (width, height) -> add(resolutionBadge(width, height)) }
    videoCodecBadge(video.text("codec"))?.let(::add)
    hdrBadge(
      gamma = decodedVideo.text("gamma"),
      primaries = decodedVideo.text("primaries"),
      matrix = decodedVideo.text("colormatrix"),
      bitDepth = bitDepth,
      isDolbyVision = listOf("dolby-vision-profile", "source-dolby-vision-profile")
        .any { (video[it]?.asInt() ?: 0L) > 0L },
      hdrTag = hdrTag,
    )?.let(::add)
    resolveAudioBadge(audio.text("codec"), audio.text("codec-profile"))?.let(::add)
    if ((audio["id"]?.asInt() ?: 0L) > 0L) {
      audioChannelsBadge(
        decodedAudio.text("channels") ?: audio.text("demux-channels"),
        decodedAudio["channel-count"]?.asInt() ?: audio["demux-channel-count"]?.asInt(),
      )?.let(::add)
    }
    bitDepth?.let { depth ->
      add(
        when (depth) {
          8 -> PlaybackFormatBadge("8-bit", "video/8-bit.svg")
          10 -> PlaybackFormatBadge("10-bit", "video/10-bit.svg")
          else -> PlaybackFormatBadge("$depth-bit")
        },
      )
    }
    if (decodedVideo.text("stereo-in").orEmpty().lowercase(Locale.ROOT) in STEREOSCOPIC_MODES) {
      add(PlaybackFormatBadge("3D", "symbols/3d.svg"))
    }
    if (audio.flag("visual-impaired")) add(PlaybackFormatBadge("AD", "symbols/ad.svg"))
    when {
      subtitle.text("codec").orEmpty().lowercase(Locale.ROOT) in CAPTION_CODECS ->
        add(PlaybackFormatBadge("CC", "symbols/cc.svg"))
      subtitle.flag("hearing-impaired") -> add(PlaybackFormatBadge("SDH", "symbols/sdh.svg"))
    }
  }
}

private fun Map<String, MPVNode>.flag(key: String): Boolean = (get(key) as? MPVNode.BooleanNode)?.value == true

private fun Map<String, MPVNode>.text(key: String): String? =
  get(key)?.asString()?.trim()?.takeIf(String::isNotEmpty)

private fun MPVNode?.hdrTag(): String? =
  this?.asMap().orEmpty().entries.asSequence()
    .filter { (key, _) -> key.lowercase(Locale.ROOT).replace(' ', '_').replace('-', '_') in HDR_METADATA_FIELDS }
    .mapNotNull { (_, value) -> value.asString()?.take(120) }
    .joinToString(" / ").takeIf(String::isNotBlank)

private fun Map<String, MPVNode>.dimensions(width: String, height: String): Pair<Int, Int>? =
  validDimensions(get(width)?.asInt(), get(height)?.asInt())

private fun validDimensions(width: Long?, height: Long?): Pair<Int, Int>? =
  if (width != null && height != null && width in 1L..65_535L && height in 1L..65_535L) {
    width.toInt() to height.toInt()
  } else {
    null
  }

private fun resolutionBadge(width: Int, height: Int): PlaybackFormatBadge {
  val shortEdge = minOf(width, height)
  val longEdge = maxOf(width, height)
  val label = when {
    shortEdge >= 4_320 || longEdge >= 7_680 && shortEdge >= 3_240 -> "8K"
    shortEdge >= 2_160 || longEdge >= 3_840 && shortEdge >= 1_600 -> "4K"
    else -> "${shortEdge}p"
  }
  val asset = when (label) {
    "4K" -> "resolution/4k.svg"
    "1080p", "720p", "576p", "480p", "360p", "240p" -> "resolution/$label.svg"
    else -> null
  }
  return PlaybackFormatBadge(label, asset, detail = "$label ($width × $height)")
}

private fun pixelFormatBitDepth(raw: String?): Int? {
  val format = raw?.lowercase(Locale.ROOT) ?: return null
  return when {
    format in EIGHT_BIT_FORMATS || PLANAR_EIGHT_BIT.matches(format) -> 8
    format in TEN_BIT_FORMATS -> 10
    else -> LISTED_BIT_DEPTH.find(format)?.groupValues?.get(1)?.toIntOrNull()
  }
}

private fun videoCodecBadge(codec: String?): PlaybackFormatBadge? =
  when (codec?.lowercase(Locale.ROOT)) {
    "h264", "avc" -> PlaybackFormatBadge("AVC", "video/avc.svg", detail = "AVC (H.264)")
    "hevc", "h265" -> PlaybackFormatBadge("HEVC", "video/hevc.svg", detail = "HEVC (H.265)")
    "av1" -> PlaybackFormatBadge("AV1", "video/av1.svg")
    "mpeg4" -> PlaybackFormatBadge("MPEG-4", "codecs/mpeg-4.svg")
    "theora" -> PlaybackFormatBadge("Theora", "codecs/theora.svg", BadgeArtwork.Original)
    "mpeg2video" -> PlaybackFormatBadge("MPEG-2")
    "mpeg1video" -> PlaybackFormatBadge("MPEG-1")
    else -> codec?.takeIf(TECHNICAL_NAME::matches)?.let { PlaybackFormatBadge(it.uppercase(Locale.ROOT)) }
  }

private fun hdrBadge(
  gamma: String?,
  primaries: String?,
  matrix: String?,
  bitDepth: Int?,
  isDolbyVision: Boolean,
  hdrTag: String?,
): PlaybackFormatBadge? {
  val tag = hdrTag.orEmpty().lowercase(Locale.ROOT)
  val transfer = gamma.orEmpty().lowercase(Locale.ROOT)
  return when {
    isDolbyVision || matrix == "dolbyvision" || "dolby vision" in tag || "dovi" in tag ->
      PlaybackFormatBadge("Dolby Vision", "symbols/dolby-vision.svg")
    "hdr10+" in tag || "hdr10plus" in tag -> PlaybackFormatBadge("HDR10+", "symbols/hdr10-plus.svg")
    "hdr10" in tag || transfer in PQ_TRANSFERS && primaries == "bt.2020" && bitDepth == 10 ->
      PlaybackFormatBadge("HDR10", "video/hdr10.svg")
    transfer == "hlg" || transfer == "arib-std-b67" || "hlg" in tag -> PlaybackFormatBadge("HLG")
    transfer in PQ_TRANSFERS || "hdr" in tag -> PlaybackFormatBadge("HDR", "symbols/hdr.svg")
    else -> null
  }
}

private fun resolveAudioBadge(codec: String?, profile: String?): PlaybackFormatBadge? {
  val format = resolveAudioFormatInfo(codec, profile, null, null, null) ?: return null
  val isDtsX = codec.equals("dts", ignoreCase = true) && DTS_X_PROFILE.containsMatchIn(profile.orEmpty())
  if (isDtsX) return PlaybackFormatBadge("DTS:X", "symbols/dts-x.svg")
  return when (val label = format.codecLabel) {
    "DOLBY ATMOS" -> PlaybackFormatBadge("Dolby Atmos", "symbols/dolby-atmos.svg")
    "DOLBY DIGITAL" -> PlaybackFormatBadge("Dolby Digital", "audio/dolby-digital.svg")
    "DOLBY DIGITAL PLUS" -> PlaybackFormatBadge("Dolby Digital Plus", "audio/dolby-digital-plus.svg")
    "DOLBY TRUEHD" -> PlaybackFormatBadge("Dolby TrueHD", "audio/dolby-truehd.svg")
    "DTS-HD MA" -> PlaybackFormatBadge(label, "audio/dts-hd-master-audio.svg", BadgeArtwork.OnLight)
    "DTS-HD HRA" -> PlaybackFormatBadge(label, "audio/dts-hd.svg")
    "DTS" -> PlaybackFormatBadge(label, "symbols/dts.svg")
    "AAC" -> PlaybackFormatBadge(label, "codecs/aac.svg", BadgeArtwork.Original)
    "FLAC" -> PlaybackFormatBadge(label, "codecs/flac.svg", BadgeArtwork.Original)
    "MP3" -> PlaybackFormatBadge(label, "codecs/mp3.svg")
    "DSD" -> PlaybackFormatBadge(label, "codecs/dsd.svg")
    "OPUS" -> PlaybackFormatBadge("Opus", "codecs/opus.svg")
    "VORBIS" -> PlaybackFormatBadge("Vorbis", "codecs/vorbis.svg", BadgeArtwork.Original)
    "SPEEX" -> PlaybackFormatBadge("Speex", "codecs/speex.svg", BadgeArtwork.Original)
    "APE" -> PlaybackFormatBadge(label, "codecs/ape.svg", BadgeArtwork.Original)
    else -> PlaybackFormatBadge(label.take(22))
  }
}

private fun audioChannelsBadge(layout: String?, count: Long?): PlaybackFormatBadge? {
  val layoutName = layout.orEmpty().lowercase(Locale.ROOT)
  val speakers = layoutName.split('-', '+').filter(String::isNotBlank)
  val label = CHANNEL_LAYOUT.find(layoutName)?.value ?: when {
    count == 1L || layoutName == "mono" || layoutName == "fc" -> "1.0"
    count == 2L || layoutName == "stereo" -> "2.0"
    speakers.size >= 3 && speakers.all { it in SPEAKER_POSITIONS } -> {
      val subwoofers = speakers.count { it == "lfe" || it == "lfe2" }
      "${speakers.size - subwoofers}.$subwoofers"
    }
    count != null && count in 3L..64L -> "$count ch"
    else -> return null
  }
  val asset = label.takeIf { it in setOf("5.1", "6.1", "7.1") }?.let { "channels/$it.svg" }
  return PlaybackFormatBadge(label, asset, isChannelLayout = true)
}

private fun sourceBadge(fileFormat: String?, vararg filenames: String?): PlaybackFormatBadge? {
  val names = filenames.filterNotNull()
  // WEB-DL and similar marks are release tags, not information supplied by a URL or a codec.
  RELEASE_BADGES.firstOrNull { (pattern, _) -> names.any(pattern::containsMatchIn) }?.let { return it.second }
  val formats = fileFormat.orEmpty().lowercase(Locale.ROOT).split(',')
  val extension = names.firstNotNullOfOrNull { it.fileExtension().takeIf(String::isNotBlank) }
  val isMatroska = "matroska" in formats
  val isWebM = "webm" in formats
  return when {
    (isMatroska || isWebM) && extension == "webm" || isWebM && !isMatroska ->
      PlaybackFormatBadge("WebM", "codecs/webm.svg", BadgeArtwork.OnLight)
    isMatroska -> PlaybackFormatBadge("Matroska", "codecs/matroska.svg", BadgeArtwork.OnLight)
    "mp4" in formats -> PlaybackFormatBadge(
      if (extension == "mov") "MOV" else if (extension == "3gp") "3GP" else "MP4",
    )
    "ogg" in formats -> PlaybackFormatBadge("Ogg", "codecs/ogg.svg", BadgeArtwork.Original)
    "avi" in formats -> PlaybackFormatBadge("AVI")
    "mpegts" in formats -> PlaybackFormatBadge("MPEG-TS")
    "flv" in formats -> PlaybackFormatBadge("FLV")
    else -> null
  }
}

private fun releaseTag(token: String) = Regex("""(?<![\p{L}\p{N}])(?:$token)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)

private val RELEASE_BADGES = listOf(
  releaseTag("remux|bdremux") to PlaybackFormatBadge("Remux", "source/remux.svg"),
  releaseTag("web[ ._-]?dl") to PlaybackFormatBadge("WEB-DL", "source/web-dl.svg"),
  releaseTag("web[ ._-]?rip") to PlaybackFormatBadge("WEBRip", "source/web-rip.svg"),
  releaseTag("blu[ ._-]?ray|bdrip|brrip") to PlaybackFormatBadge("Blu-ray", "source/blu-ray.svg", BadgeArtwork.OnDark),
  releaseTag("hdtv") to PlaybackFormatBadge("HDTV", "source/hdtv.svg"),
  releaseTag("dvd[ ._-]?rip") to PlaybackFormatBadge("DVDRip", "source/dvd-rip.svg"),
  releaseTag("dvd[ ._-]?scr") to PlaybackFormatBadge("DVDScr", "source/dvd-screener.svg"),
)

private val HDR_METADATA_FIELDS = setOf("hdr_format", "hdr_format_commercial", "video_range_type")
private val PQ_TRANSFERS = setOf("pq", "smpte2084", "smpte-st-2084")
private val STEREOSCOPIC_MODES = setOf(
  "sbs2l", "sbs2r", "sbsl", "sbsr", "abl", "abr", "ab2l", "ab2r", "tbl", "tbr", "tb2l", "tb2r",
  "irl", "irr", "icl", "icr", "chl", "chr", "al", "ar", "ml", "mr",
)
private val CAPTION_CODECS = setOf("eia_608", "eia_708", "cea_608", "cea_708")
private val TECHNICAL_NAME = Regex("[A-Za-z0-9_+.-]{1,18}")
private val DTS_X_PROFILE = Regex("""\bdts[: -]?x\b""", RegexOption.IGNORE_CASE)
private val CHANNEL_LAYOUT = Regex("""^\d{1,2}\.\d(?:\.\d{1,2})?(?=\(|\s|$)""")
private val SPEAKER_POSITIONS = setOf(
  "fl", "fr", "fc", "lfe", "lfe2", "bl", "br", "bc", "sl", "sr", "flc", "frc",
  "tc", "tfl", "tfc", "tfr", "tbl", "tbc", "tbr", "tsl", "tsr",
)
private val EIGHT_BIT_FORMATS = setOf("nv12", "nv21", "yuyv422", "uyvy422", "rgba", "bgra", "rgb24", "bgr24", "rgb0", "bgr0")
private val TEN_BIT_FORMATS = setOf("rgb30", "bgr30", "x2rgb10le", "x2bgr10le", "x2rgb10be", "x2bgr10be")
private val PLANAR_EIGHT_BIT = Regex("""(?:yuva?\d{3}p|yuvj\d{3}p|gbrap?|gray8|gray)""")
private val LISTED_BIT_DEPTH =
  Regex("""^(?:(?:yuva?\d{3}p|gbrap?|gray|xyz|p[024])0?)(9|10|12|14|16)(?:le|be)?$""")
