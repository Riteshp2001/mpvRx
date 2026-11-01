/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

/**
 * Embedded subtitles of videos streamed from Jellyfin: the server extracts the track for us, and
 * the translation is uploaded back, so Jellyfin saves it next to the movie on its own disk.
 */
internal class JellyfinSubtitleBridge(
  private val client: OkHttpClient,
  private val json: Json,
) {
  data class Source(
    val base: String,
    val itemId: String,
    val mediaSourceId: String,
    val token: String,
  )

  data class Track(
    val index: Int,
    val codec: String,
    val language: String?,
    val isText: Boolean,
  )

  companion object {
    /** Recognizes `…/Videos/{id}/stream?…api_key=…` playback URLs. */
    fun parse(videoUri: Uri?): Source? {
      if (videoUri == null || videoUri.scheme !in setOf("http", "https")) return null
      val segments = videoUri.pathSegments
      val videos = segments.indexOfFirst { it.equals("Videos", ignoreCase = true) }
      if (videos < 0 || videos + 1 >= segments.size) return null
      val token = videoUri.getQueryParameter("api_key") ?: videoUri.getQueryParameter("ApiKey") ?: return null
      val itemId = segments[videos + 1]
      val prefix = segments.take(videos).joinToString("/") { it }.let { if (it.isEmpty()) "" else "/$it" }
      val base = "${videoUri.scheme}://${videoUri.encodedAuthority}$prefix"
      return Source(base, itemId, videoUri.getQueryParameter("MediaSourceId") ?: itemId, token)
    }
  }

  private fun request(
    source: Source,
    path: String,
  ) = Request
    .Builder()
    .url("${source.base}$path")
    .header("Authorization", "MediaBrowser Client=\"mpvRx\", Device=\"Android\", DeviceId=\"mpvrx-translate\", Version=\"1\", Token=\"${source.token}\"")

  /** The file's embedded subtitle streams, in file order. */
  suspend fun embeddedTracks(source: Source): List<Track> {
    val reply = client.fetch(request(source, "/Items/${source.itemId}").get().build(), "Jellyfin")
    reply.throwIfError("Jellyfin")
    val item = json.parseToJsonElement(reply.body).jsonObject
    val mediaSource =
      item["MediaSources"]?.jsonArray?.map { it.jsonObject }?.let { sources ->
        sources.firstOrNull { it["Id"]?.jsonPrimitive?.contentOrNull == source.mediaSourceId } ?: sources.firstOrNull()
      } ?: item
    return (mediaSource["MediaStreams"]?.jsonArray ?: return emptyList())
      .map { it.jsonObject }
      .filter { it["Type"]?.jsonPrimitive?.contentOrNull == "Subtitle" && it["IsExternal"]?.jsonPrimitive?.booleanOrNull != true }
      .mapNotNull { stream ->
        Track(
          index = stream["Index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
          codec = stream["Codec"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase(Locale.ROOT),
          language = stream["Language"]?.jsonPrimitive?.contentOrNull,
          isText = stream["IsTextSubtitleStream"]?.jsonPrimitive?.booleanOrNull ?: true,
        )
      }.sortedBy { it.index }
  }

  /** Jellyfin converts the stream itself; ASS stays ASS so styling survives. */
  suspend fun download(
    source: Source,
    track: Track,
  ): Pair<String, SubtitleFormat> {
    val format = if (track.codec == "ass" || track.codec == "ssa") SubtitleFormat.ASS else SubtitleFormat.SRT
    val ext = if (format == SubtitleFormat.ASS) "ass" else "srt"
    val reply =
      client.fetch(
        request(source, "/Videos/${source.itemId}/${source.mediaSourceId}/Subtitles/${track.index}/0/Stream.$ext").get().build(),
        "Jellyfin",
      )
    reply.throwIfError("Jellyfin")
    return reply.body.removePrefix("﻿") to format
  }

  /** Jellyfin writes the file into the movie's folder (with "save subtitles with media" on, the default). */
  suspend fun upload(
    source: Source,
    content: String,
    format: SubtitleFormat,
    languageCode: String,
  ) {
    val iso3 = runCatching { Locale(languageCode.substringBefore('-')).isO3Language }.getOrNull()?.takeIf { it.isNotBlank() } ?: languageCode
    val payload =
      buildJsonObject {
        put("Language", iso3)
        put("Format", if (format == SubtitleFormat.ASS) "ass" else "srt")
        put("IsForced", false)
        put("IsHearingImpaired", false)
        put("Data", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
      }
    val reply =
      client.fetch(
        request(source, "/Videos/${source.itemId}/Subtitles")
          .post(payload.toString().toRequestBody("application/json".toMediaType()))
          .build(),
        "Jellyfin",
      )
    if (reply.code == 403) {
      throw SubtitleTranslationException.Auth("Jellyfin refused to save the subtitle: your user needs the \"Manage subtitles\" permission (Dashboard → Users).")
    }
    reply.throwIfError("Jellyfin")
  }
}
