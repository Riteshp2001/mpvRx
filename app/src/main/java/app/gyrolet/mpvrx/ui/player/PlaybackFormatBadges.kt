/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.utils.media.fileExtension
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import java.util.Locale

internal data class PlaybackFormatBadge(
  val label: String,
  /** Relative to media_format_logos; null displays the format name as text. */
  val asset: String? = null,
  val artwork: BadgeArtwork = BadgeArtwork.ThemeInk,
  val detail: String = label,
  val isChannelLayout: Boolean = false,
  val lightTintArgb: Long? = null,
  val darkTintArgb: Long? = null,
)

internal enum class BadgeArtwork { ThemeInk, Original, OnLight, OnDark }

private const val HDR10_PLUS_LABEL = "HDR10+"

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
      var hdr10PlusTrackId: Long? = null
      val changes = merge(
        PlaybackSession.propNode["track-list"].map { Unit },
        PlaybackSession.propNode["metadata"].map { Unit },
        PlaybackSession.propString["file-format"].map { Unit },
        PlaybackSession.propString["vid"].map { Unit },
        PlaybackSession.propString["aid"].map { Unit },
        PlaybackSession.propString["sid"].map { Unit },
        PlaybackSession.propInt["video-params/w"].map { Unit },
        PlaybackSession.propInt["video-params/h"].map { Unit },
        PlaybackSession.propString["video-params/gamma"].map { Unit },
        PlaybackSession.propString["video-params/primaries"].map { Unit },
        PlaybackSession.propString["video-params/colormatrix"].map { Unit },
        PlaybackSession.propString["video-params/pixelformat"].map { Unit },
        PlaybackSession.propString["video-params/hw-pixelformat"].map { Unit },
        PlaybackSession.propString["video-params/stereo-in"].map { Unit },
        PlaybackSession.propString["audio-params/channels"].map { Unit },
        PlaybackSession.propInt["audio-params/channel-count"].map { Unit },
        PlaybackSession.propString["current-tracks/audio/codec-profile"].map { Unit },
      )
      val sceneProbes = combine(
        PlaybackSession.propInt["current-tracks/video/id"],
        PlaybackSession.propString["video-params/gamma"],
      ) { id, gamma ->
        id?.takeIf { gamma?.lowercase(Locale.ROOT) in PQ_TRANSFERS }
      }.distinctUntilChanged().transformLatest { id ->
        if (id != null && id > 0) {
          // Sample after the selected decoder settles; scene values are too noisy to observe continuously.
          for (waitMillis in HDR_SCENE_RETRY_DELAYS) {
            delay(waitMillis)
            emit(id.toLong())
          }
        }
      }
      merge<Long?>(changes.map { null }, sceneProbes).conflate().map { probeTrackId ->
        val snapshot = PlaybackSession.readLoadedMedia(generation) {
          readPlaybackFormatBadges(it, item, hdr10PlusTrackId, probeTrackId)
        }
        hdr10PlusTrackId = snapshot?.hdr10PlusTrackId
        snapshot?.badges.orEmpty()
      }.onStart { emit(emptyList()) }
    }
  }.distinctUntilChanged().flowOn(Dispatchers.IO)

private data class PlaybackBadgeSnapshot(
  val badges: List<PlaybackFormatBadge>,
  val hdr10PlusTrackId: Long?,
)

private fun readPlaybackFormatBadges(
  media: PlaybackSession.PropertyReader,
  item: PlaybackItem,
  hdr10PlusTrackId: Long?,
  probeTrackId: Long?,
): PlaybackBadgeSnapshot? {
  val video = media.node("current-tracks/video")?.asMap().orEmpty()
  val videoTrackId = video["id"]?.asInt() ?: return null
  if (videoTrackId <= 0L || video.flag("albumart") || video.flag("image")) return null

  val videoParams = media.node("video-params")?.asMap().orEmpty()
  val decodedVideo = media.node("video-dec-params")?.asMap() ?: videoParams
  val audio = media.node("current-tracks/audio")?.asMap().orEmpty()
  val decodedAudio = media.node("audio-params")?.asMap().orEmpty()
  val subtitle = media.node("current-tracks/sub")?.asMap().orEmpty()
  val filename = media.string("filename")
  val dimensions = decodedVideo.dimensions("w", "h")
    ?: video.dimensions("demux-w", "demux-h")
    ?: validDimensions(item.videoWidth.toLong(), item.videoHeight.toLong())
  val bitDepth = decodedVideo.reportedVideoBitDepth() ?: videoParams.reportedVideoBitDepth()
  val rangeBadges = media.selectedVideoRangeBadges(
    video, decodedVideo, videoParams, bitDepth,
    sawHdr10Plus = hdr10PlusTrackId == videoTrackId,
    sampleHdr10Plus = probeTrackId == videoTrackId,
  )

  val badges = buildList {
    val source = sourceBadge(
      media.string("file-format"),
      filename,
      item.title,
      item.networkSource?.relativePath?.substringAfterLast('/'),
    )
    source?.let(::add)
    dimensions?.let { (width, height) -> add(resolutionBadge(width, height)) }
    videoCodecBadge(video.text("codec"))?.let(::add)
    addAll(rangeBadges)
    if ((audio["id"]?.asInt() ?: 0L) > 0L) {
      val audioCodec = audio.text("codec")
      val audioProfile = audio.text("codec-profile") ?: media.string("current-tracks/audio/codec-profile")
      val audioTags = audio["metadata"].formatTags(AUDIO_FORMAT_METADATA_FIELDS)
      addAll(audioFormatBadges(audioCodec, audioProfile, audioTags, audio.text("title")))
      audioChannelsBadge(
        audio.text("demux-channels") ?: decodedAudio.text("channels"),
        audio["demux-channel-count"]?.asInt() ?: decodedAudio["channel-count"]?.asInt(),
      )?.let(::add)
    }
    bitDepth?.let { depth ->
      add(bitDepthBadge(depth))
    }
    if (decodedVideo.text("stereo-in").orEmpty().lowercase(Locale.ROOT) in STEREOSCOPIC_MODES) {
      add(PlaybackFormatBadge("3D", "symbols/3d.svg"))
    }
    if (audio.flag("visual-impaired")) add(PlaybackFormatBadge("AD", "symbols/ad.svg"))
    subtitleBadge(subtitle.text("codec"), subtitle.flag("hearing-impaired"))?.let(::add)
  }
  return PlaybackBadgeSnapshot(badges, videoTrackId.takeIf { rangeBadges.any { it.label == HDR10_PLUS_LABEL } })
}

internal fun PlaybackSession.PropertyReader.selectedVideoRangeBadges(
  track: Map<String, MPVNode>,
  decoded: Map<String, MPVNode>,
  reported: Map<String, MPVNode>,
  bitDepth: Int?,
  sawHdr10Plus: Boolean = false,
  sampleHdr10Plus: Boolean = true,
): List<PlaybackFormatBadge> {
  val tag = track["metadata"].formatTags(HDR_METADATA_FIELDS)
    ?: if (!track.flag("external")) node("metadata").formatTags(HDR_METADATA_FIELDS) else null
  val transfer = (decoded.text("gamma") ?: reported.text("gamma")).orEmpty().lowercase(Locale.ROOT)
  val dynamicHdr = sawHdr10Plus || sampleHdr10Plus && transfer in PQ_TRANSFERS &&
    (decoded.hasHdr10PlusScene() || reported.hasHdr10PlusScene() ||
      node("video-out-params")?.asMap()?.hasHdr10PlusScene() == true)
  return hdrBadges(
    hdrTag = tag,
    transfer = transfer,
    primaries = decoded.text("primaries") ?: reported.text("primaries"),
    bitDepth = bitDepth,
    isDolbyVision = (decoded.text("colormatrix") ?: reported.text("colormatrix")) == "dolbyvision" ||
      listOf("dolby-vision-profile", "source-dolby-vision-profile").any { (track[it]?.asInt() ?: 0L) > 0L },
    isHdr10Plus = dynamicHdr,
  )
}

internal fun Map<String, MPVNode>.reportedVideoBitDepth(): Int? =
  sequenceOf(text("hw-pixelformat"), text("pixelformat")).firstNotNullOfOrNull(::pixelFormatBitDepth)

private fun Map<String, MPVNode>.flag(key: String): Boolean = (get(key) as? MPVNode.BooleanNode)?.value == true

private fun Map<String, MPVNode>.text(key: String): String? =
  get(key)?.asString()?.trim()?.takeIf(String::isNotEmpty)

private fun MPVNode?.formatTags(fields: Set<String>): String? =
  this?.asMap().orEmpty().entries.asSequence()
    .filter { (key, _) -> key.lowercase(Locale.ROOT).replace(' ', '_').replace('-', '_') in fields }
    .mapNotNull { (_, value) -> value.asString()?.take(120) }
    .joinToString(" / ").takeIf(String::isNotBlank)

private fun Map<String, MPVNode>.hasHdr10PlusScene(): Boolean =
  HDR10_PLUS_SCENE_FIELDS.any { (get(it)?.asDouble() ?: 0.0) > 0.0 }

private fun Map<String, MPVNode>.dimensions(width: String, height: String): Pair<Int, Int>? =
  validDimensions(get(width)?.asInt(), get(height)?.asInt())

private fun validDimensions(width: Long?, height: Long?): Pair<Int, Int>? =
  if (width != null && height != null && width in 1L..65_535L && height in 1L..65_535L) {
    width.toInt() to height.toInt()
  } else {
    null
  }

internal fun resolutionBadge(width: Int, height: Int): PlaybackFormatBadge {
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
  val (lightTint, darkTint) = when (label) {
    "4K", "1080p" -> 0xFF946700L to 0xFFFFD56BL
    "720p" -> 0xFF176FAE to 0xFF69C4FF
    else -> 0xFF5F6C7D to 0xFFBBC7D6
  }
  return PlaybackFormatBadge(
    label,
    asset,
    detail = "$label ($width × $height)",
    lightTintArgb = lightTint,
    darkTintArgb = darkTint,
  )
}

internal fun pixelFormatBitDepth(raw: String?): Int? {
  val format = raw?.lowercase(Locale.ROOT) ?: return null
  return when {
    format in EIGHT_BIT_FORMATS || PLANAR_EIGHT_BIT.matches(format) -> 8
    format in TEN_BIT_FORMATS -> 10
    else -> LISTED_BIT_DEPTH.find(format)?.groupValues?.get(1)?.toIntOrNull()
  }
}

internal fun bitDepthBadge(depth: Int): PlaybackFormatBadge =
  when (depth) {
    8 -> PlaybackFormatBadge(
      "8-bit",
      "video/8-bit.svg",
      lightTintArgb = 0xFF5F6C7D,
      darkTintArgb = 0xFFBBC7D6,
    )
    10 -> PlaybackFormatBadge(
      "10-bit",
      "video/10-bit.svg",
      lightTintArgb = 0xFF946700,
      darkTintArgb = 0xFFFFD56B,
    )
    else -> PlaybackFormatBadge("$depth-bit")
  }

internal fun subtitleBadge(codec: String?, hearingImpaired: Boolean): PlaybackFormatBadge? =
  when {
    codec.orEmpty().lowercase(Locale.ROOT) in CAPTION_CODECS -> PlaybackFormatBadge("CC", "symbols/cc.svg")
    hearingImpaired -> PlaybackFormatBadge("SDH", "symbols/sdh.svg")
    else -> null
  }

internal fun subtitleFormatBadge(
  codec: String? = null,
  codecDescription: String? = null,
  externalFilename: String? = null,
): PlaybackFormatBadge? {
  val descriptor = listOfNotNull(codec, codecDescription).joinToString(" ").lowercase(Locale.ROOT)
  val tokens = Regex("[a-z0-9]+").findAll(descriptor).map { it.value }.toSet()
  val extension = externalFilename?.fileExtension()?.lowercase(Locale.ROOT)
  val extensionFormat = when (extension) {
    "srt" -> "srt"
    "vtt" -> "vtt"
    "ass" -> "ass"
    "ssa" -> "ssa"
    "idx" -> "idx"
    "sub" -> "sub"
    "sup" -> "sup"
    "ttml", "dfxp" -> "ttml"
    "sbv" -> "sbv"
    "stl" -> "stl"
    else -> null
  }
  val format = extensionFormat ?: when {
    "subrip" in tokens || "s_text/utf8" in descriptor || "s_text/utf-8" in descriptor -> "srt"
    "webvtt" in tokens || "vtt" in tokens -> "vtt"
    "advanced substation alpha" in descriptor || "ass" in tokens -> "ass"
    "ssa" in tokens || "substation alpha" in descriptor -> "ssa"
    "idx" in tokens -> "idx"
    "vobsub" in tokens || "microdvd" in tokens || "subviewer" in tokens -> "sub"
    "pgs" in tokens || "hdmv" in tokens || "sup" in tokens -> "sup"
    "ttml" in tokens || "dfxp" in tokens || "stpp" in tokens -> "ttml"
    "sbv" in tokens -> "sbv"
    "ebustl" in tokens || "stl" in tokens || "ebu-stl" in descriptor -> "stl"
    else -> return null
  }
  val detail = when (format) {
    "srt" -> "SubRip subtitles (SRT)"
    "vtt" -> "WebVTT subtitles (VTT)"
    "ass" -> "Advanced SubStation Alpha subtitles (ASS)"
    "ssa" -> "SubStation Alpha subtitles (SSA)"
    "sub" -> "SUB subtitles"
    "idx" -> "VobSub index subtitles (IDX)"
    "sup" -> "PGS bitmap subtitles (SUP)"
    "ttml" -> "Timed Text Markup Language subtitles (TTML)"
    "sbv" -> "SBV subtitles"
    else -> "EBU STL subtitles"
  }
  val (lightTint, darkTint) = subtitleBadgeTints(format)
  return PlaybackFormatBadge(
    label = format.uppercase(Locale.ROOT),
    asset = "subtitles/$format.svg",
    detail = detail,
    lightTintArgb = lightTint,
    darkTintArgb = darkTint,
  )
}

private fun subtitleBadgeTints(format: String): Pair<Long, Long> =
  when (format) {
    "srt" -> 0xFF4F3FB0L to 0xFFB9ACFFL
    "vtt" -> 0xFF00727DL to 0xFF6FE0E8L
    "ass" -> 0xFFA32D68L to 0xFFFF9ACBL
    "ssa" -> 0xFF5B3FADL to 0xFFC0AAFFL
    "idx" -> 0xFFA66310L to 0xFFFFC079L
    "sub" -> 0xFF4E43B0L to 0xFFB7ADFFL
    "sup" -> 0xFF1A6499L to 0xFF8FCFFFL
    "ttml" -> 0xFF0F6E5AL to 0xFF79DCC2L
    "sbv" -> 0xFFAE2636L to 0xFFFF9AA6L
    else -> 0xFF8A6117L to 0xFFFFD07AL
  }

internal fun videoCodecBadge(codec: String?): PlaybackFormatBadge? =
  when (codec?.lowercase(Locale.ROOT)) {
    "h264", "avc" -> PlaybackFormatBadge(
      "AVC",
      "video/avc.svg",
      detail = "AVC (H.264)",
      lightTintArgb = 0xFF6547C8,
      darkTintArgb = 0xFFB69AFF,
    )
    "hevc", "h265" -> PlaybackFormatBadge(
      "HEVC",
      "video/hevc.svg",
      detail = "HEVC (H.265)",
      lightTintArgb = 0xFF31763C,
      darkTintArgb = 0xFF8FDF83,
    )
    "av1" -> PlaybackFormatBadge(
      "AV1",
      "video/av1.svg",
      lightTintArgb = 0xFF006F73,
      darkTintArgb = 0xFF69DED7,
    )
    "mpeg4" -> PlaybackFormatBadge(
      "MPEG-4",
      "codecs/mpeg-4.svg",
      lightTintArgb = 0xFF52617A,
      darkTintArgb = 0xFFB7C7E0,
    )
    "theora" -> PlaybackFormatBadge("Theora", "codecs/theora.svg", BadgeArtwork.Original)
    "xvid" -> PlaybackFormatBadge(
      "XviD",
      "video/xvid.svg",
      lightTintArgb = 0xFF367D40,
      darkTintArgb = 0xFF9AE28C,
    )
    "mpeg2video" -> PlaybackFormatBadge(
      "MPEG-2",
      lightTintArgb = 0xFF52617A,
      darkTintArgb = 0xFFB7C7E0,
    )
    "mpeg1video" -> PlaybackFormatBadge(
      "MPEG-1",
      lightTintArgb = 0xFF52617A,
      darkTintArgb = 0xFFB7C7E0,
    )
    else -> codec?.takeIf(TECHNICAL_NAME::matches)?.let { PlaybackFormatBadge(it.uppercase(Locale.ROOT)) }
  }

internal fun hdrBadges(
  hdrTag: String?,
  transfer: String = "",
  primaries: String? = null,
  bitDepth: Int? = null,
  isDolbyVision: Boolean = false,
  isHdr10Plus: Boolean = false,
): List<PlaybackFormatBadge> = buildList {
  val tag = hdrTag.orEmpty()
  if (isDolbyVision || DOLBY_VISION_TAG.containsMatchIn(tag)) {
    add(PlaybackFormatBadge("Dolby Vision", "symbols/dolby-vision.svg"))
  }
  if (isHdr10Plus || HDR10_PLUS_TAG.containsMatchIn(tag)) {
    add(
      PlaybackFormatBadge(
        HDR10_PLUS_LABEL,
        "video/alternates/hdr10-plus-original-color.svg",
        BadgeArtwork.OnDark,
      ),
    )
  }
  if (transfer in HLG_TRANSFERS || HLG_TAG.containsMatchIn(tag)) {
    add(
      PlaybackFormatBadge(
        "HLG",
        lightTintArgb = 0xFF31764D,
        darkTintArgb = 0xFF7DE0A0,
      ),
    )
  }
  // Don't show a generic or inferred HDR10 badge in addition to a verified dynamic format.
  if (isEmpty()) {
    when {
      SDR_TAG.containsMatchIn(tag) || transfer in SDR_TRANSFERS ->
        add(
          PlaybackFormatBadge(
            "SDR",
            "video/sdr.svg",
            lightTintArgb = 0xFF5F6C7D,
            darkTintArgb = 0xFFBBC7D6,
          ),
        )
      HDR10_TAG.containsMatchIn(tag) || transfer in PQ_TRANSFERS && primaries == "bt.2020" && bitDepth == 10 ->
        add(
          PlaybackFormatBadge(
            "HDR10",
            "video/hdr10.svg",
            lightTintArgb = 0xFF946700,
            darkTintArgb = 0xFFFFD56B,
          ),
        )
      transfer in PQ_TRANSFERS || HDR_TAG.containsMatchIn(tag) ->
        add(
          PlaybackFormatBadge(
            "HDR",
            "symbols/hdr.svg",
            lightTintArgb = 0xFF9B6510,
            darkTintArgb = 0xFFFFC56B,
          ),
        )
      else -> Unit
    }
  }
  if (IMAX_ENHANCED.containsMatchIn(tag)) {
    add(
      PlaybackFormatBadge(
        "IMAX Enhanced",
        "video/imax-enhanced.svg",
        lightTintArgb = 0xFF345A78,
        darkTintArgb = 0xFF9BD6FF,
      ),
    )
  } else if (IMAX_TAG.containsMatchIn(tag)) {
    add(
      PlaybackFormatBadge(
        "IMAX",
        "symbols/imax.svg",
        lightTintArgb = 0xFF3D4959,
        darkTintArgb = 0xFFE0E7F0,
      ),
    )
  }
}

internal fun audioFormatBadges(
  codec: String?,
  profile: String?,
  metadata: String?,
  title: String?,
): List<PlaybackFormatBadge> {
  val codecName = codec?.trim()?.lowercase(Locale.ROOT) ?: return emptyList()
  val explicitJoc = codecName in EAC3_JOC_CODECS
  val baseCodec = if (explicitJoc) "eac3" else codecName
  val baseProfile = profile.takeUnless { baseCodec in ATMOS_CARRIER_CODECS || baseCodec == "ac3" }
  val format = resolveAudioFormatInfo(baseCodec, baseProfile, null, null, null) ?: return emptyList()
  val tags = listOfNotNull(profile, metadata, title)
  val atmos = explicitJoc ||
    codecName in ATMOS_CARRIER_CODECS && tags.any { matchesAudioFeature(it, DOLBY_ATMOS_TAG) }
  val dtsX = codecName == "dts" && tags.any { matchesAudioFeature(it, DTS_X_PROFILE) }
  return buildList {
    if (atmos) {
      add(
        PlaybackFormatBadge(
          "Dolby Atmos",
          "symbols/dolby-atmos.svg",
          lightTintArgb = 0xFF3D4959,
          darkTintArgb = 0xFFE0E7F0,
        ),
      )
    }
    if (dtsX) {
      if (matchesAudioFeature(profile.orEmpty(), DTS_X_IMAX)) {
        add(
          PlaybackFormatBadge(
            "DTS:X IMAX",
            "symbols/alternates/imax-dts-x.svg",
            lightTintArgb = 0xFF98500B,
            darkTintArgb = 0xFFFFA24D,
          ),
        )
      } else {
        add(
          PlaybackFormatBadge(
            "DTS:X",
            "symbols/dts-x.svg",
            lightTintArgb = 0xFF98500B,
            darkTintArgb = 0xFFFFA24D,
          ),
        )
      }
    }
    if (!dtsX || format.codecLabel != "DTS") add(audioCodecBadge(format.codecLabel))
  }
}

private fun matchesAudioFeature(tag: String, feature: Regex): Boolean =
  feature.containsMatchIn(tag) && !NEGATED_AUDIO_FEATURE.containsMatchIn(tag)

private fun audioCodecBadge(label: String): PlaybackFormatBadge =
  when (label) {
    "DOLBY DIGITAL" -> PlaybackFormatBadge(
      "Dolby Digital",
      "audio/dolby-digital.svg",
      lightTintArgb = 0xFF3D4959,
      darkTintArgb = 0xFFE0E7F0,
    )
    "DOLBY DIGITAL PLUS" -> PlaybackFormatBadge(
      "Dolby Digital Plus",
      "audio/dolby-digital-plus.svg",
      lightTintArgb = 0xFF3D4959,
      darkTintArgb = 0xFFE0E7F0,
    )
    "DOLBY TRUEHD" -> PlaybackFormatBadge(
      "Dolby TrueHD",
      "audio/dolby-truehd.svg",
      lightTintArgb = 0xFF3D4959,
      darkTintArgb = 0xFFE0E7F0,
    )
    "DTS-HD MA" -> PlaybackFormatBadge(
      label,
      "audio/dts-hd-master-audio.svg",
      BadgeArtwork.OnLight,
    )
    "DTS-HD HRA" -> PlaybackFormatBadge(
      label,
      "audio/dts-hd.svg",
      lightTintArgb = 0xFF94601C,
      darkTintArgb = 0xFFFFC56B,
    )
    "DTS" -> PlaybackFormatBadge(
      label,
      "symbols/dts.svg",
      lightTintArgb = 0xFF546170,
      darkTintArgb = 0xFFC8D2DF,
    )
    "AAC" -> PlaybackFormatBadge(label, "codecs/aac.svg", BadgeArtwork.Original)
    "FLAC" -> PlaybackFormatBadge(label, "codecs/flac.svg", BadgeArtwork.Original)
    "MP3" -> PlaybackFormatBadge(label, "codecs/mp3.svg")
    "DSD" -> PlaybackFormatBadge(label, "codecs/dsd.svg")
    "OPUS" -> PlaybackFormatBadge("Opus", "codecs/opus.svg")
    "VORBIS" -> PlaybackFormatBadge("Vorbis", "codecs/vorbis.svg", BadgeArtwork.Original)
    "SPEEX" -> PlaybackFormatBadge("Speex", "codecs/speex.svg", BadgeArtwork.Original)
    "APE" -> PlaybackFormatBadge(label, "codecs/ape.svg", BadgeArtwork.Original)
    "MPC7", "MPC8" -> PlaybackFormatBadge("Musepack", "codecs/musepack.svg", BadgeArtwork.OnLight)
    "WAVPACK" -> PlaybackFormatBadge(
      "WavPack",
      "codecs/wavpack.svg",
      lightTintArgb = 0xFF596779,
      darkTintArgb = 0xFFC5D0DE,
    )
    else -> PlaybackFormatBadge(label.take(22))
  }

internal fun audioChannelsBadge(layout: String?, count: Long?): PlaybackFormatBadge? {
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
  return PlaybackFormatBadge(
    label,
    asset,
    isChannelLayout = true,
    lightTintArgb = 0xFF146A80,
    darkTintArgb = 0xFF64CEDB,
  )
}

internal fun sourceBadge(fileFormat: String?, vararg filenames: String?): PlaybackFormatBadge? {
  val names = filenames.filterNotNull()
  // WEB-DL and similar marks are release tags, not information supplied by a URL or a codec.
  RELEASE_BADGES.firstOrNull { (pattern, _) -> names.any(pattern::containsMatchIn) }?.let { return it.second }
  val formats = fileFormat.orEmpty().lowercase(Locale.ROOT).split(',').map(String::trim)
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
    "ogg" in formats -> PlaybackFormatBadge("Ogg", "codecs/ogg.svg")
    "flac" in formats -> PlaybackFormatBadge("FLAC", "codecs/flac.svg", BadgeArtwork.Original)
    "avi" in formats -> PlaybackFormatBadge("AVI")
    "mpegts" in formats -> PlaybackFormatBadge("MPEG-TS")
    "flv" in formats -> PlaybackFormatBadge("FLV")
    else -> null
  }
}

private fun releaseTag(token: String) = Regex("""(?<![\p{L}\p{N}])(?:$token)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)

private val RELEASE_BADGES = listOf(
  releaseTag("remux|bdremux") to PlaybackFormatBadge(
    "Remux",
    "source/remux.svg",
    lightTintArgb = 0xFF536274,
    darkTintArgb = 0xFFC4CFDD,
  ),
  releaseTag("web[ ._-]?dl") to PlaybackFormatBadge(
    "WEB-DL",
    "source/web-dl.svg",
    lightTintArgb = 0xFF086DB2,
    darkTintArgb = 0xFF68BFFF,
  ),
  releaseTag("web[ ._-]?rip") to PlaybackFormatBadge(
    "WEBRip",
    "source/web-rip.svg",
    lightTintArgb = 0xFF007C8D,
    darkTintArgb = 0xFF61D8E5,
  ),
  releaseTag("blu[ ._-]?ray|bdrip|brrip") to PlaybackFormatBadge("Blu-ray", "source/blu-ray.svg", BadgeArtwork.OnDark),
  releaseTag("hdtv") to PlaybackFormatBadge(
    "HDTV",
    "source/hdtv.svg",
    lightTintArgb = 0xFF405F9E,
    darkTintArgb = 0xFF91AEFF,
  ),
  releaseTag("cam(?:rip)?") to PlaybackFormatBadge(
    "CAM",
    "source/cam.svg",
    lightTintArgb = 0xFF9B4242,
    darkTintArgb = 0xFFFF9992,
  ),
  releaseTag("dvd[ ._-]?rip") to PlaybackFormatBadge(
    "DVDRip",
    "source/dvd-rip.svg",
    lightTintArgb = 0xFF80621E,
    darkTintArgb = 0xFFF0C66D,
  ),
  releaseTag("dvd[ ._-]?scr") to PlaybackFormatBadge(
    "DVDScr",
    "source/dvd-screener.svg",
    lightTintArgb = 0xFF865B22,
    darkTintArgb = 0xFFE9B96B,
  ),
  releaseTag("dvd[ ._-]?video") to PlaybackFormatBadge(
    "DVD Video",
    "source/dvd-video.svg",
    lightTintArgb = 0xFF536274,
    darkTintArgb = 0xFFC4CFDD,
  ),
  releaseTag("r5") to PlaybackFormatBadge(
    "R5",
    "source/r5.svg",
    lightTintArgb = 0xFF624AAB,
    darkTintArgb = 0xFFB8A2FF,
  ),
  releaseTag("seadex") to PlaybackFormatBadge(
    "SeaDex",
    "source/seadex.svg",
    lightTintArgb = 0xFF14786F,
    darkTintArgb = 0xFF74DCCF,
  ),
)

private val HDR_METADATA_FIELDS = setOf("hdr_format", "hdr_format_commercial", "video_range_type")
private val AUDIO_FORMAT_METADATA_FIELDS = setOf(
  "format_commercial_ifany", "format_additionalfeatures", "format_additional_features", "format_profile", "codec_profile",
)
private val HDR_SCENE_RETRY_DELAYS = listOf(150L, 350L, 750L, 1_500L)
private val HDR10_PLUS_SCENE_FIELDS = listOf("scene-max-r", "scene-max-g", "scene-max-b", "scene-avg")
private val PQ_TRANSFERS = setOf("pq", "smpte2084", "smpte-st-2084")
private val HLG_TRANSFERS = setOf("hlg", "arib-std-b67")
private val DOLBY_VISION_TAG = Regex("""\bdolby[ ._-]*vision\b|\bdovi(?=\b|with)|\bdv(?:he|h1)\.\d{2}\b""", RegexOption.IGNORE_CASE)
private val HDR10_PLUS_TAG = Regex("""hdr[ ._-]?10(?:\s*\+|[ ._-]*plus\b)|\bst[ ._-]?2094(?:[ ._-]40|[ ._-]+app(?:lication)?[ ._-]+4)\b""", RegexOption.IGNORE_CASE)
private val HDR10_TAG = Regex("""\bhdr[ ._-]?10\b""", RegexOption.IGNORE_CASE)
private val HDR_TAG = Regex("""\bhdr\b""", RegexOption.IGNORE_CASE)
private val HLG_TAG = Regex("""\bhlg\b""", RegexOption.IGNORE_CASE)
private val IMAX_ENHANCED = Regex("""\bimax[ ._-]+enhanced\b""", RegexOption.IGNORE_CASE)
private val IMAX_TAG = Regex("""\bimax\b""", RegexOption.IGNORE_CASE)
private val SDR_TAG = Regex("""\bsdr\b""", RegexOption.IGNORE_CASE)
private val SDR_TRANSFERS = setOf(
  "bt.1886", "bt.709", "bt.601", "smpte-170m", "smpte-240m", "srgb", "gamma2.2", "gamma2.8",
)
private val STEREOSCOPIC_MODES = setOf(
  "sbs2l", "sbs2r", "sbsl", "sbsr", "abl", "abr", "ab2l", "ab2r", "tbl", "tbr", "tb2l", "tb2r",
  "irl", "irr", "icl", "icr", "chl", "chr", "al", "ar", "ml", "mr",
)
private val CAPTION_CODECS = setOf("eia_608", "eia_708", "cea_608", "cea_708")
private val TECHNICAL_NAME = Regex("[A-Za-z0-9_+.-]{1,18}")
private val EAC3_JOC_CODECS = setOf("eac3-joc", "eac3_joc", "e-ac-3-joc", "eac3joc")
private val ATMOS_CARRIER_CODECS = setOf("eac3", "e-ac-3", "truehd", "ac4")
private val DOLBY_ATMOS_TAG = Regex("""\b(?:atmos|joc)\b""", RegexOption.IGNORE_CASE)
private val DTS_X_PROFILE = Regex("""\bdts[ :_-]?x\b""", RegexOption.IGNORE_CASE)
private val DTS_X_IMAX = Regex("""\bdts[ :_-]?x[ ._-]+imax\b""", RegexOption.IGNORE_CASE)
private val NEGATED_AUDIO_FEATURE = Regex("""\b(?:no|non|not|without)[ ._-]+(?:dolby[ ._-]+)?(?:atmos|joc|dts[ :_-]?x)\b""", RegexOption.IGNORE_CASE)
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
