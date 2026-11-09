/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network

import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.utils.media.M3ULimits
import app.gyrolet.mpvrx.utils.media.M3UParseResult
import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.media.M3UPlaylistItem
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class XtreamCatalog(
  val serverUrl: String,
  val playlist: M3UParseResult.Success,
)

/** Authenticates an account, then imports its M3U export or the equivalent Player API catalog. */
class XtreamClient(
  httpClient: OkHttpClient,
  private val json: Json,
) {
  private val httpClient = httpClient.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()

  suspend fun loadCatalog(rawServerUrl: String, username: String, password: String): Result<XtreamCatalog> =
    withContext(Dispatchers.Default) {
      try {
        val serverUrl = normalizeServerUrl(rawServerUrl)
        require(username.isNotBlank()) { "Username is required" }
        require(password.isNotBlank()) { "Password is required" }
        val account = validateAccount(serverUrl, username, password)
        val formats = (account["allowed_output_formats"] as? JsonArray)
          ?.mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }
        val output = when {
          formats.isNullOrEmpty() || "ts" in formats -> "ts"
          "m3u8" in formats -> "m3u8"
          else -> throw IllegalArgumentException("This account does not allow TS or HLS streams")
        }
        val playlistUrl = endpoint(serverUrl, "get.php", username, password).newBuilder()
          .addQueryParameter("type", "m3u_plus").addQueryParameter("output", output).build()
        val exported = M3UParser.parseFromUrl(playlistUrl.toString(), userAgent = USER_AGENT, httpClient = httpClient)
        val playlist = if (exported is M3UParseResult.Success && exported.items.isNotEmpty()) {
          exported
        } else {
          // Some panels disable get.php even though player_api.php authenticates successfully.
          // Finish all sections before returning: a partial refresh must not erase existing items.
          loadApiCatalog(serverUrl, username, password, output)
        }
        Result.success(XtreamCatalog(serverUrl, playlist))
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        // Network exceptions can include credential-bearing query URLs. Only expose our safe errors.
        Result.failure(
          if (error is XtreamResponseException || error is IllegalArgumentException) {
            error
          } else {
            IOException("Could not reach the Xtream server. Check the address, port and connection.")
          },
        )
      }
    }

  fun normalizeServerUrl(rawServerUrl: String): String = XtreamServerAddress.parse(rawServerUrl).serverUrl

  private suspend fun validateAccount(serverUrl: String, username: String, password: String): JsonObject {
    val root = readJson(endpoint(serverUrl, "player_api.php", username, password), MAX_AUTH_RESPONSE_BYTES) as? JsonObject
      ?: throw XtreamResponseException("Xtream server returned an invalid account response")
    val userInfo = root["user_info"] as? JsonObject
      ?: throw XtreamResponseException("Xtream server returned an invalid account response")
    val authenticated = userInfo.text("auth")
    if (authenticated != "1" && !authenticated.equals("true", true)) {
      throw XtreamResponseException("Invalid Xtream username or password")
    }
    val status = userInfo.text("status")
    if (status.isNotBlank() && !status.equals("active", true)) {
      throw XtreamResponseException("Xtream account is expired, disabled or not active")
    }
    return userInfo
  }

  private suspend fun loadApiCatalog(server: String, username: String, password: String, output: String): M3UParseResult.Success {
    val items = mutableListOf<M3UPlaylistItem>()
    fun append(entries: List<M3UPlaylistItem>) {
      if (items.size + entries.size > MAX_ENTRIES) throw XtreamResponseException("Xtream catalog is too large")
      items += entries
    }
    suspend fun catalog(action: String): List<JsonObject> =
      objectList(readJson(apiUrl(server, username, password, action), MAX_CATALOG_RESPONSE_BYTES))

    for ((action, kind) in listOf("get_live_streams" to "live", "get_vod_streams" to "movie")) {
      val streams = catalog(action)
      val categories = try {
        catalog(if (kind == "live") "get_live_categories" else "get_vod_categories")
          .associate { it.text("category_id") to it.text("category_name") }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) { emptyMap() } // Category labels are optional; streams are not.
      append(streams.map { stream ->
        val id = stream.text("stream_id").toLongOrNull()?.takeIf { it > 0 }
          ?: throw XtreamResponseException("Xtream server returned an invalid stream ID")
        val extension = if (kind == "live") output else extension(stream.text("container_extension"))
        M3UPlaylistItem(
          url = streamUrl(server, username, password, kind, id, extension),
          title = stream.text("name").ifBlank { id.toString() },
          tvgId = stream.text("epg_channel_id").takeIf { it.isNotBlank() },
          tvgLogo = stream.text("stream_icon").takeIf { it.isNotBlank() },
          groupTitle = categories[stream.text("category_id")]?.takeIf { it.isNotBlank() }
            ?: if (kind == "live") "Live" else "Movies",
        )
      })
    }
    val series = catalog("get_series")
    if (series.size > MAX_SERIES) throw XtreamResponseException("Xtream series catalog is too large")
    // Bounded parallelism and per-request byte/time limits; cancellation cancels OkHttp calls.
    for (batch in series.chunked(4)) {
      val episodes = coroutineScope {
        batch.map { show ->
          async {
            val id = show.text("series_id").toLongOrNull()?.takeIf { it > 0 }
              ?: throw XtreamResponseException("Xtream server returned an invalid series ID")
            val url = apiUrl(server, username, password, "get_series_info").newBuilder()
              .addQueryParameter("series_id", id.toString()).build()
            val detail = readJson(url, MAX_SERIES_RESPONSE_BYTES) as? JsonObject
              ?: throw XtreamResponseException("Xtream server returned invalid series details")
            val seasons = when (val data = detail["episodes"]) {
              is JsonObject -> data.values.toList()
              is JsonArray -> data.toList()
              else -> throw XtreamResponseException("Xtream server returned invalid episodes")
            }
            seasons.flatMap { season ->
              objectList(season).map { episode ->
                val episodeId = episode.text("id").toLongOrNull()?.takeIf { it > 0 }
                  ?: throw XtreamResponseException("Xtream server returned an invalid episode ID")
                M3UPlaylistItem(
                  url = streamUrl(server, username, password, "series", episodeId, extension(episode.text("container_extension"))),
                  title = episode.text("title").ifBlank { "${show.text("name")} ${episode.text("episode_num")}" },
                  groupTitle = show.text("name").ifBlank { "Series" },
                  tvgLogo = show.text("cover").takeIf { it.isNotBlank() },
                )
              }
            }
          }
        }.awaitAll().flatten()
      }
      append(episodes)
    }
    if (items.isEmpty()) throw XtreamResponseException("This Xtream account has no playable streams")
    return M3UParseResult.Success("Xtream", items)
  }

  private fun objectList(element: JsonElement): List<JsonObject> = when {
    element is JsonArray -> element.map {
      it as? JsonObject ?: throw XtreamResponseException("Xtream server returned an invalid catalog")
    }
    element is JsonObject && element.isEmpty() -> emptyList() // Some panels encode empty lists as {}.
    else -> throw XtreamResponseException("Xtream server returned an invalid catalog")
  }

  private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content?.trim().orEmpty()

  private fun extension(value: String): String =
    value.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) } ?: "mp4"

  private fun streamUrl(server: String, username: String, password: String, kind: String, id: Long, extension: String): String =
    "$server/".toHttpUrlOrNull()!!.newBuilder().addPathSegment(kind)
      .addPathSegment(username).addPathSegment(password).addPathSegment("$id.$extension").build().toString()

  private fun apiUrl(server: String, username: String, password: String, action: String): HttpUrl =
    endpoint(server, "player_api.php", username, password).newBuilder().addQueryParameter("action", action).build()

  private fun endpoint(server: String, file: String, username: String, password: String): HttpUrl =
    "$server/".toHttpUrlOrNull()!!.newBuilder().addPathSegment(file)
      .addQueryParameter("username", username).addQueryParameter("password", password).build()

  private suspend fun readJson(url: HttpUrl, limit: Int): JsonElement {
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
    return httpClient.newCall(request).awaitResponse().use { response ->
      if (response.code == 401 || response.code == 403) throw XtreamResponseException("Xtream access denied. Check the account credentials and permissions.")
      if (!response.isSuccessful) throw XtreamResponseException("Xtream server returned HTTP ${response.code}")
      val content = runInterruptible(Dispatchers.IO) {
        response.body.byteStream().use { stream ->
          val output = ByteArrayOutputStream()
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          var total = 0
          while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw XtreamResponseException("Xtream response is too large")
            output.write(buffer, 0, count)
          }
          output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
        }
      }
      withContext(Dispatchers.Default) {
        try { json.parseToJsonElement(content) } catch (_: IllegalArgumentException) {
          throw XtreamResponseException("Xtream server returned invalid JSON. Check the server address and port.")
        }
      }
    }
  }

  private class XtreamResponseException(message: String) : IOException(message)

  companion object {
    private const val USER_AGENT = "mpvRx/2.7"
    private const val MAX_AUTH_RESPONSE_BYTES = 512 * 1024
    private const val MAX_CATALOG_RESPONSE_BYTES = 128 * 1024 * 1024
    private const val MAX_SERIES_RESPONSE_BYTES = 4 * 1024 * 1024
    private val MAX_ENTRIES = M3ULimits().maxEntries
    private const val MAX_SERIES = 50_000
  }
}
