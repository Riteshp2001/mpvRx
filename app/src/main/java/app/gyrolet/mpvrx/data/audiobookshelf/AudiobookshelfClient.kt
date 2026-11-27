/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.audiobookshelf

import android.net.Uri
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfBook
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfChapter
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfLibrary
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfServer
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfTrack
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AudiobookshelfClient(
  private val httpClient: OkHttpClient,
  private val json: Json,
) {
  companion object {
    private const val TAG = "AudiobookshelfClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }

  suspend fun login(
    serverUrl: String,
    username: String,
    password: String,
  ): Result<AudiobookshelfServer> =
    withContext(Dispatchers.IO) {
      val cleanUrl = serverUrl.trimEnd('/')
      try {
        val payload =
          JsonObject(
            mapOf("username" to JsonPrimitive(username), "password" to JsonPrimitive(password)),
          ).toString()
        val req =
          Request
            .Builder()
            .url("$cleanUrl/login")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(req).awaitResponse().use { response ->
          if (!response.isSuccessful) {
            return@withContext Result.failure(
              Exception("Login failed (HTTP ${response.code})"),
            )
          }
          val root = json.parseToJsonElement(response.body.string()).jsonObject
          val userObj =
            root.obj("user") ?: return@withContext Result.failure(Exception("Missing user object in response"))
          val token =
            userObj.str("token") ?: root.str("token") ?: return@withContext Result.failure(Exception("Missing token"))
          Result.success(
            AudiobookshelfServer(
              name = cleanUrl.substringAfter("://").substringBefore(":").substringBefore("/"),
              serverUrl = cleanUrl,
              username = username,
              token = token,
              userId = userObj.str("id") ?: "",
              activeLibraryId = userObj.str("userDefaultLibraryId"),
              lastConnected = System.currentTimeMillis(),
            ),
          )
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Login exception", e)
        Result.failure(e)
      }
    }

  suspend fun verifyToken(
    serverUrl: String,
    token: String,
    name: String = "",
  ): Result<AudiobookshelfServer> =
    withContext(Dispatchers.IO) {
      val cleanUrl = serverUrl.trimEnd('/')
      try {
        val authReq =
          Request
            .Builder()
            .url("$cleanUrl/api/authorize")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(authReq).awaitResponse().use { response ->
          val targetResponse =
            if (response.isSuccessful) {
              response
            } else {
              val meReq =
                Request
                  .Builder()
                  .url("$cleanUrl/api/me")
                  .header("Authorization", "Bearer $token")
                  .get()
                  .build()
              httpClient.newCall(meReq).awaitResponse()
            }
          targetResponse.use { res ->
            if (!res.isSuccessful) {
              return@withContext Result.failure(
                Exception("Token verification failed (HTTP ${res.code})"),
              )
            }
            val root = json.parseToJsonElement(res.body.string()).jsonObject
            val userObj = root.obj("user") ?: root
            Result.success(
              AudiobookshelfServer(
                name = name.ifBlank { cleanUrl.substringAfter("://").substringBefore(":").substringBefore("/") },
                serverUrl = cleanUrl,
                username = userObj.str("username") ?: "user",
                token = token,
                userId = userObj.str("id") ?: "",
                activeLibraryId = userObj.str("userDefaultLibraryId"),
                lastConnected = System.currentTimeMillis(),
              ),
            )
          }
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Verify token exception", e)
        Result.failure(e)
      }
    }

  suspend fun getLibraries(server: AudiobookshelfServer): Result<List<AudiobookshelfLibrary>> =
    withContext(Dispatchers.IO) {
      try {
        val req =
          Request
            .Builder()
            .url("${server.serverUrl.trimEnd('/')}/api/libraries")
            .header("Authorization", "Bearer ${server.token}")
            .get()
            .build()
        httpClient.newCall(req).awaitResponse().use { response ->
          if (!response.isSuccessful) {
            return@withContext Result.failure(
              Exception("Failed to fetch libraries (HTTP ${response.code})"),
            )
          }
          val root = json.parseToJsonElement(response.body.string()).jsonObject
          val libraries =
            root
              .arr("libraries")
              .orEmpty()
              .mapNotNull { elem ->
                val obj = elem.jsonObject
                val id = obj.str("id") ?: return@mapNotNull null
                AudiobookshelfLibrary(
                  id = id,
                  name = obj.str("name") ?: "Library",
                  mediaType = obj.str("mediaType") ?: "book",
                  icon = obj.str("icon"),
                  displayOrder = obj["displayOrder"]?.jsonPrimitive?.intOrNull ?: 0,
                )
              }.filter { it.mediaType == "book" || it.mediaType == "podcast" }
          Result.success(libraries)
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "getLibraries exception", e)
        Result.failure(e)
      }
    }

  suspend fun getItems(
    server: AudiobookshelfServer,
    libraryId: String,
    page: Int = 0,
    limit: Int = 200,
    sort: String? = null,
    desc: Boolean = false,
    filter: String? = null,
  ): Result<List<AudiobookshelfBook>> =
    withContext(Dispatchers.IO) {
      try {
        val builder =
          Uri
            .parse("${server.serverUrl.trimEnd('/')}/api/libraries/$libraryId/items")
            .buildUpon()
            .appendQueryParameter("limit", limit.toString())
            .appendQueryParameter("page", page.toString())
            .appendQueryParameter("include", "progress,rssfeed,authors,downloads")
        if (!sort.isNullOrBlank()) builder.appendQueryParameter("sort", sort)
        if (desc) builder.appendQueryParameter("desc", "1")
        if (!filter.isNullOrBlank()) builder.appendQueryParameter("filter", filter)

        val req =
          Request
            .Builder()
            .url(builder.build().toString())
            .header("Authorization", "Bearer ${server.token}")
            .get()
            .build()
        httpClient.newCall(req).awaitResponse().use { response ->
          if (!response.isSuccessful) {
            return@withContext Result.failure(
              Exception("Failed to fetch library items (HTTP ${response.code})"),
            )
          }
          val root = json.parseToJsonElement(response.body.string()).jsonObject
          val books = root.arr("results").orEmpty().mapNotNull { parseBookItem(it.jsonObject, server, libraryId) }
          Result.success(books)
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "getItems exception", e)
        Result.failure(e)
      }
    }

  suspend fun getItemDetails(
    server: AudiobookshelfServer,
    itemId: String,
  ): Result<AudiobookshelfBook> =
    withContext(Dispatchers.IO) {
      try {
        val url = "${server.serverUrl.trimEnd(
          '/',
        )}/api/items/$itemId?expanded=1&include=progress,rssfeed,authors,downloads"
        val req =
          Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer ${server.token}")
            .get()
            .build()
        httpClient.newCall(req).awaitResponse().use { response ->
          if (!response.isSuccessful) {
            return@withContext Result.failure(
              Exception("Failed to fetch item details (HTTP ${response.code})"),
            )
          }
          val root = json.parseToJsonElement(response.body.string()).jsonObject
          val book =
            parseBookItem(root, server, root.str("libraryId") ?: "")
              ?: return@withContext Result.failure(Exception("Failed to parse book item"))
          Result.success(book)
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "getItemDetails exception", e)
        Result.failure(e)
      }
    }

  suspend fun syncProgress(
    server: AudiobookshelfServer,
    itemId: String,
    currentTimeSeconds: Double,
    durationSeconds: Double,
    isFinished: Boolean = false,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val progress =
          if (durationSeconds >
            0
          ) {
            (currentTimeSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
          } else {
            0f
          }
        val payload =
          JsonObject(
            mapOf(
              "currentTime" to JsonPrimitive(currentTimeSeconds),
              "timeListened" to JsonPrimitive(5.0),
              "duration" to JsonPrimitive(durationSeconds),
              "progress" to JsonPrimitive(progress),
              "isFinished" to JsonPrimitive(isFinished),
            ),
          ).toString()

        val url = "${server.serverUrl.trimEnd('/')}/api/me/progress/$itemId"
        val patchReq =
          Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer ${server.token}")
            .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(patchReq).awaitResponse().use { response ->
          if (response.isSuccessful) return@withContext Result.success(Unit)
          val postReq =
            Request
              .Builder()
              .url(url)
              .header("Authorization", "Bearer ${server.token}")
              .post(payload.toRequestBody(JSON_MEDIA_TYPE))
              .build()
          httpClient.newCall(postReq).awaitResponse().use { postRes ->
            if (postRes.isSuccessful) {
              Result.success(Unit)
            } else {
              Result.failure(Exception("Progress sync failed (HTTP ${response.code})"))
            }
          }
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "syncProgress exception", e)
        Result.failure(e)
      }
    }

  suspend fun updateCoverUrl(
    server: AudiobookshelfServer,
    itemId: String,
    coverUrl: String,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val payload = JsonObject(mapOf("url" to JsonPrimitive(coverUrl))).toString()
        val url = "${server.serverUrl.trimEnd('/')}/api/items/$itemId/cover/url"
        val req =
          Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer ${server.token}")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).awaitResponse().use { response ->
          if (response.isSuccessful) {
            Result.success(Unit)
          } else {
            Result.failure(Exception("Cover update failed (HTTP ${response.code})"))
          }
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "updateCoverUrl exception", e)
        Result.failure(e)
      }
    }

  suspend fun quickMatch(
    server: AudiobookshelfServer,
    itemId: String,
    provider: String = "audible",
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val payload = JsonObject(mapOf("provider" to JsonPrimitive(provider))).toString()
        val url = "${server.serverUrl.trimEnd('/')}/api/items/$itemId/quick-match"
        val req =
          Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer ${server.token}")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).awaitResponse().use { response ->
          if (response.isSuccessful) {
            Result.success(Unit)
          } else {
            Result.failure(Exception("Quick match failed (HTTP ${response.code})"))
          }
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "quickMatch exception", e)
        Result.failure(e)
      }
    }

  fun getCoverUrl(
    server: AudiobookshelfServer,
    itemId: String,
  ): String = "${server.serverUrl.trimEnd('/')}/api/items/$itemId/cover?token=${server.token}"

  fun getTrackStreamUrl(
    server: AudiobookshelfServer,
    track: AudiobookshelfTrack,
    bookId: String,
  ): String {
    val cleanUrl = server.serverUrl.trimEnd('/')
    return when {
      track.contentUrl.startsWith("http://") || track.contentUrl.startsWith("https://") -> {
        if (!track.contentUrl.contains("token=")) {
          val sep = if (track.contentUrl.contains("?")) "&" else "?"
          "${track.contentUrl}${sep}token=${server.token}"
        } else {
          track.contentUrl
        }
      }
      track.contentUrl.startsWith("/") -> "$cleanUrl${track.contentUrl}?token=${server.token}"
      track.ino.isNotBlank() -> "$cleanUrl/api/items/$bookId/file/${track.ino}?token=${server.token}"
      track.id.isNotBlank() -> "$cleanUrl/api/items/$bookId/file/${track.id}?token=${server.token}"
      else -> "$cleanUrl/api/items/$bookId/file?token=${server.token}"
    }
  }

  private fun parseBookItem(
    obj: JsonObject,
    server: AudiobookshelfServer,
    fallbackLibraryId: String,
  ): AudiobookshelfBook? {
    val id = obj.str("id") ?: return null
    val media = obj.obj("media") ?: JsonObject(emptyMap())
    val meta = media.obj("metadata") ?: obj.obj("metadata") ?: JsonObject(emptyMap())

    val title = meta.str("title") ?: obj.str("title") ?: "Unknown Title"
    val subtitle = meta.str("subtitle") ?: obj.str("subtitle") ?: ""

    val authors =
      (meta.arr("authors") ?: obj.arr("authors"))
        ?.mapNotNull {
          if (it is JsonObject) it.str("name") else it.asString()
        }?.filter { it.isNotBlank() }
    val author =
      if (!authors.isNullOrEmpty()) {
        authors.joinToString(", ")
      } else {
        (meta.str("authorName", "author") ?: obj.str("author") ?: "")
      }

    val narrators =
      (meta.arr("narrators") ?: obj.arr("narrators"))
        ?.mapNotNull {
          if (it is JsonObject) it.str("name") else it.asString()
        }?.filter { it.isNotBlank() }
    val narrator =
      if (!narrators.isNullOrEmpty()) {
        narrators.joinToString(", ")
      } else {
        (meta.str("narratorName", "narrator") ?: obj.str("narrator") ?: "")
      }

    val firstSeries = (meta.arr("series") ?: obj.arr("series"))?.firstOrNull()?.jsonObject
    val series = firstSeries?.str("name") ?: meta.str("seriesName") ?: ""
    val seriesPart = firstSeries?.str("sequence") ?: meta.str("seriesSequence") ?: ""

    val genres =
      (
        (meta.arr("genres") ?: obj.arr("genres")).orEmpty().mapNotNull { it.asString() } +
          (meta.arr("tags") ?: obj.arr("tags")).orEmpty().mapNotNull { it.asString() }
      ).distinct()

    val rawTracks = media.arr("tracks") ?: media.arr("audioFiles") ?: obj.arr("tracks") ?: obj.arr("audioFiles")
    val tracks =
      rawTracks?.mapIndexedNotNull { index, itemElem ->
        val tObj = itemElem.jsonObject
        val tMeta = tObj.obj("metadata")
        AudiobookshelfTrack(
          id = tObj.str("id", "ino") ?: "$index",
          index = index,
          ino = tObj.str("ino") ?: "",
          title = tObj.str("title") ?: tMeta?.str("filename") ?: "Track ${index + 1}",
          durationMs = ((tObj["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
          size = tObj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
          mimeType = tObj.str("mimeType") ?: "audio/mp4",
          contentUrl = tObj.str("contentUrl") ?: "",
        )
      } ?: emptyList()

    val durationSec =
      media["duration"]?.jsonPrimitive?.doubleOrNull
        ?: media["duration"]?.jsonPrimitive?.longOrNull?.toDouble()
        ?: obj["duration"]?.jsonPrimitive?.doubleOrNull
        ?: (tracks.sumOf { it.durationMs } / 1000.0)
    val durationMs = (durationSec * 1000).toLong()

    val progressObj =
      obj.obj("userMediaProgress") ?: obj.obj("mediaProgress")
        ?: media.obj("userMediaProgress") ?: media.obj("progress") ?: obj.obj("progress")
    val currentTimeSec =
      progressObj?.get("currentTime")?.jsonPrimitive?.doubleOrNull
        ?: progressObj
          ?.get("currentTime")
          ?.jsonPrimitive
          ?.longOrNull
          ?.toDouble() ?: 0.0
    val rawProgress = progressObj?.get("progress")?.jsonPrimitive?.floatOrNull ?: 0f
    val isFinished =
      progressObj?.get("isFinished")?.jsonPrimitive?.booleanOrNull
        ?: progressObj
          ?.get("isFinished")
          ?.jsonPrimitive
          ?.intOrNull
          ?.let { it == 1 }
        ?: (currentTimeSec > 0 && durationSec > 0 && currentTimeSec >= durationSec - 5)
    val progressMs = (currentTimeSec * 1000).toLong()
    val progressPercent =
      if (rawProgress >
        0f
      ) {
        rawProgress
      } else if (durationSec > 0) {
        (currentTimeSec / durationSec).toFloat().coerceIn(0f, 1f)
      } else {
        0f
      }

    val rawChapters =
      media.arr("chapters") ?: obj.arr("chapters")
        ?: media
          .arr("audioFiles")
          ?.firstOrNull()
          ?.jsonObject
          ?.arr("chapters")
    var chapters =
      rawChapters?.mapIndexedNotNull { index, chapElem ->
        val cObj = chapElem.jsonObject
        AudiobookshelfChapter(
          id = cObj["id"]?.jsonPrimitive?.longOrNull ?: index.toLong(),
          startMs = ((cObj["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
          endMs = ((cObj["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
          title = cObj.str("title") ?: "Chapter ${index + 1}",
        )
      } ?: emptyList()

    if (chapters.isEmpty()) {
      chapters = media.arr("audioFiles")?.flatMapIndexed { _, fileElem ->
        val fObj = fileElem.jsonObject
        fObj.arr("chapters").orEmpty().mapNotNull { chapElem ->
          val cObj = chapElem.jsonObject
          AudiobookshelfChapter(
            id = cObj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            startMs = ((cObj["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
            endMs = ((cObj["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
            title = cObj.str("title") ?: "Chapter",
          )
        }
      } ?: emptyList()
    }

    return AudiobookshelfBook(
      id = id,
      libraryId = obj.str("libraryId") ?: fallbackLibraryId,
      title = title,
      subtitle = subtitle,
      author = author,
      narrator = narrator,
      series = series,
      seriesPart = seriesPart,
      description = meta.str("description", "summary") ?: obj.str("description") ?: "",
      genres = genres,
      publisher = meta.str("publisher") ?: obj.str("publisher") ?: "",
      publishedYear = meta.str("publishedYear", "publishedDate") ?: obj.str("publishedYear") ?: "",
      language = meta.str("language") ?: obj.str("language") ?: "",
      isbn = meta.str("isbn") ?: obj.str("isbn") ?: "",
      asin = meta.str("asin") ?: obj.str("asin") ?: "",
      durationMs = durationMs,
      progressMs = progressMs,
      progressPercent = progressPercent,
      isFinished = isFinished,
      coverUrl = getCoverUrl(server, id),
      tracks = tracks,
      chapters = chapters,
      addedAt = obj["addedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
      updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
  }

  private fun JsonObject.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { get(it).asString() }

  private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it !is JsonNull }?.runCatching { jsonObject }?.getOrNull()

  private fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it !is JsonNull }?.runCatching { jsonArray }?.getOrNull()

  private fun JsonElement?.asString(): String? {
    if (this == null || this is JsonNull) return null
    val str = this.jsonPrimitive.contentOrNull ?: return null
    return if (str.equals("null", ignoreCase = true) || str.isBlank()) null else str
  }
}
