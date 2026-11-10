/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.navidrome.NavidromeSong
import java.util.Locale

enum class AudioQuality {
  HiRes,
  Lossless,
  Lossy,
  Dsd,
  Unknown,
}

internal enum class AudioFormatLogo(
  val asset: String,
  val aspectRatio: Float,
) {
  HiRes("HiResAudiologo.svg", 1f),
  Lossless("AppleLosslesslogo.svg", 15f / 9f),
  Lossy("LossyAudiologo.svg", 1f),
  Cd("CdAudiologo.svg", 1f),
  Dsd("DSDlogo.svg", 384f / 195f),
  DolbyAtmos("DolbyAtmoslogo.svg", 308.25f / 76.25f),
  DolbyDigital("DolbyDigitallogo.svg", 384f / 95f),
  Dts("DtsLogo.svg", 384f / 154f),
  Mpegh("MpeghLogo.svg", 2f),
  Mqa("MqaLogo.svg", 384f / 168f),
}

data class AudioFormatInfo(
  val codecLabel: String,
  val quality: AudioQuality,
  val sampleRate: Int?,
  val sourceBitDepth: Int?,
  val bitrate: Long?,
) {
  internal val artworkLogo: AudioFormatLogo?
    get() = when {
      codecLabel == "DOLBY ATMOS" -> AudioFormatLogo.DolbyAtmos
      codecLabel == "DOLBY DIGITAL" -> AudioFormatLogo.DolbyDigital
      codecLabel.startsWith("DTS") -> AudioFormatLogo.Dts
      codecLabel == "MPEG-H" -> AudioFormatLogo.Mpegh
      quality == AudioQuality.Dsd -> AudioFormatLogo.Dsd
      quality == AudioQuality.HiRes -> AudioFormatLogo.HiRes
      quality == AudioQuality.Lossless && sampleRate == 44100 && sourceBitDepth == 16 -> AudioFormatLogo.Cd
      quality == AudioQuality.Lossless -> AudioFormatLogo.Lossless
      quality == AudioQuality.Lossy -> AudioFormatLogo.Lossy
      else -> null
    }

  fun details(qualityLabel: String): String =
    buildList {
      add(codecLabel)
      if (quality != AudioQuality.Unknown) add(qualityLabel)
      sourceBitDepth?.let { add("$it-bit") }
      sampleRate?.let { rate ->
        add(if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format(Locale.US, "%.1f kHz", rate / 1000.0))
      }
      bitrate?.let { add("${it / 1000} kbps") }
    }.joinToString(" · ")
}

/** Stream codec/profile are authoritative; filenames and decoded PCM storage do not prove quality. */
internal fun resolveAudioFormatInfo(
  codec: String?,
  profile: String?,
  sampleRate: Int?,
  bitsPerSample: String?,
  bitrate: Long?,
  trackMetadata: Map<String, String?>? = null,
): AudioFormatInfo? {
  val name = codec?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return null
  val profileName = profile.orEmpty().lowercase(Locale.ROOT)
  val dolby = name in setOf("ac3", "eac3", "e-ac-3", "truehd", "ac4")
  // An AC-3/E-AC-3/TrueHD codec alone does not establish the presence of Atmos objects.
  val atmos = dolby && (profileName.contains("atmos") || profileName.contains("joc"))
  val dtsMasterAudio = name == "dts" && (profileName.contains("dts-hd ma") || profileName.contains("master audio"))
  val dsd = name.startsWith("dsd")
  val uncompressedPcm = name.startsWith("pcm_") && !name.contains("alaw") && !name.contains("mulaw")
  val lossless =
    name in setOf("flac", "alac", "ape", "tta", "tak", "truehd", "mlp", "wmalossless") ||
      uncompressedPcm || dtsMasterAudio
  val lossy =
    name in setOf(
      "mp1", "mp2", "mp3", "aac", "aac_latm", "opus", "vorbis", "ac3", "eac3", "e-ac-3", "ac4",
      "wmav1", "wmav2", "wmapro", "amr_nb", "amr_wb", "speex", "atrac3", "atrac3p",
      "gsm", "gsm_ms", "ilbc", "pcm_alaw", "pcm_mulaw",
    ) || (name == "dts" && profileName.isNotBlank() && !dtsMasterAudio)
  val taggedBitDepth =
    trackMetadata.orEmpty().asSequence()
      .filter { (key, _) ->
        key.lowercase(Locale.ROOT) in setOf("bits_per_sample", "bits_per_raw_sample", "bit_depth", "bitdepth")
      }.map { it.value }
      .plus(sequenceOf(bitsPerSample))
      .mapNotNull { it?.trim()?.toIntOrNull() }
      .firstOrNull { it in 1..64 }
  // pcm_s24le describes the source. audio-params/format=s32/flt describes decoder storage only.
  val pcmBitDepth = if (uncompressedPcm) PCM_BIT_DEPTH.find(name)?.groupValues?.get(1)?.toIntOrNull() else null
  val sourceBitDepth = if (lossless) pcmBitDepth ?: taggedBitDepth else null
  val validSampleRate = sampleRate?.takeIf { it > 0 }
  val quality = when {
    dsd -> AudioQuality.Dsd
    lossless && ((validSampleRate ?: 0) > 48000 || (sourceBitDepth ?: 0) > 16) -> AudioQuality.HiRes
    lossless -> AudioQuality.Lossless
    lossy -> AudioQuality.Lossy
    else -> AudioQuality.Unknown
  }
  val label = when {
    atmos -> "DOLBY ATMOS"
    name == "ac3" -> "DOLBY DIGITAL"
    name == "eac3" || name == "e-ac-3" -> "DOLBY DIGITAL PLUS"
    name == "truehd" -> "DOLBY TRUEHD"
    name == "ac4" -> "DOLBY AC-4"
    name == "mpegh_3d_audio" || name == "mpegh" -> "MPEG-H"
    dtsMasterAudio -> "DTS-HD MA"
    name == "dts" && profileName.contains("dts-hd hra") -> "DTS-HD HRA"
    name == "dts" -> "DTS"
    dsd -> "DSD"
    name.startsWith("pcm_") -> if (uncompressedPcm) "PCM" else "G.711"
    name == "aac_latm" -> "AAC"
    name == "wmav1" || name == "wmav2" -> "WMA"
    name == "wmapro" -> "WMA PRO"
    name == "wmalossless" -> "WMA LOSSLESS"
    else -> name.replace('_', ' ').uppercase(Locale.ROOT)
  }
  return AudioFormatInfo(label, quality, validSampleRate, sourceBitDepth, bitrate?.takeIf { it >= 1000 })
}

private val PCM_BIT_DEPTH = Regex("^pcm_[suf](8|16|24|32|64)")

internal fun indexedAudioFormatInfo(
  mimeType: String?,
  sampleRate: Int? = null,
  bitsPerSample: Int? = null,
  bitrate: Long? = null,
): AudioFormatInfo? {
  val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
  val codec = when (mime) {
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/aac", "audio/aac-adts" -> "aac"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/alac", "audio/x-alac" -> "alac"
    "audio/opus" -> "opus"
    "audio/vorbis" -> "vorbis"
    "audio/ac3", "audio/ac-3" -> "ac3"
    "audio/eac3", "audio/eac3-joc", "audio/e-ac-3" -> "eac3"
    "audio/true-hd", "audio/truehd" -> "truehd"
    "audio/dsd", "audio/dsf", "audio/dff", "audio/x-dsf", "audio/x-dff" -> "dsd"
    "audio/mpegh", "audio/mha1", "audio/mhm1" -> "mpegh"
    else -> return null
  }
  val format = resolveAudioFormatInfo(
    codec = codec,
    profile = if (mime == "audio/eac3-joc") "Dolby Atmos JOC" else null,
    sampleRate = sampleRate,
    bitsPerSample = bitsPerSample?.toString(),
    bitrate = bitrate,
  ) ?: return null
  return if (mime == "audio/mpeg") format.copy(codecLabel = "MPEG AUDIO") else format
}

internal fun navidromeAudioFormatInfo(song: NavidromeSong): AudioFormatInfo? =
  indexedAudioFormatInfo(
    mimeType = song.contentType,
    sampleRate = song.samplingRate,
    bitsPerSample = song.bitDepth,
    bitrate = song.bitRate?.toLong()?.times(1000),
  )

internal fun jellyfinAudioFormatInfo(item: JellyfinItem): AudioFormatInfo? {
  if (!item.isAudio) return null
  val codec = when (val name = item.audioCodec?.lowercase(Locale.ROOT)) {
    "dolby digital" -> "ac3"
    "e-ac-3" -> "eac3"
    "dts-hd", "dtshd" -> "dts"
    else -> name
  }
  return resolveAudioFormatInfo(
    codec = codec,
    profile = item.audioCodecProfile,
    sampleRate = item.audioSampleRate,
    bitsPerSample = item.audioBitDepth?.toString(),
    bitrate = item.audioBitRate,
  )
}
