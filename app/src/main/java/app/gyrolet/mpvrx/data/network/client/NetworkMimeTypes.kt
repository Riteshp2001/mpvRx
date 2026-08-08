/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.client

internal object NetworkMimeTypes {
  private val genericVideoExtensions =
    setOf("m2v", "ogv", "mts", "m2ts", "vob", "divx", "xvid", "f4v", "rm", "rmvb", "asf")
  private val genericAudioExtensions =
    setOf(
      "m4a", "flac", "ogg", "oga", "opus", "wav", "wave", "wma", "amr", "awb", "ac3",
      "eac3", "dts", "mka", "aif", "aiff", "aifc", "ape", "mp1", "mp2", "mpa", "mpc",
      "tta", "tak", "caf", "au", "snd", "ra", "spx", "weba", "3ga", "dsf", "dff", "mlp",
      "truehd", "mid", "midi",
    )

  fun forFileName(fileName: String): String? =
    when (val extension = fileName.substringAfterLast('.', "").lowercase()) {
      "m3u", "m3u8" -> "application/vnd.apple.mpegurl"
      "mp4", "m4v" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "avi" -> "video/x-msvideo"
      "mov" -> "video/quicktime"
      "wmv" -> "video/x-ms-wmv"
      "flv" -> "video/x-flv"
      "webm" -> "video/webm"
      "mpeg", "mpg" -> "video/mpeg"
      "3gp" -> "video/3gpp"
      "3g2" -> "video/3gpp2"
      "ts" -> "video/mp2t"
      "m4s" -> "video/iso.segment"
      "aac" -> "audio/aac"
      "mp3" -> "audio/mpeg"
      "vtt" -> "text/vtt"
      "srt", "ass", "ssa" -> "text/plain"
      in genericVideoExtensions -> "video/*"
      in genericAudioExtensions -> "audio/*"
      else -> null
    }
}
