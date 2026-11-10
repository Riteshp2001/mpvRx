/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.navidrome

import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.domain.navidrome.NavidromeAlbum
import app.gyrolet.mpvrx.domain.navidrome.NavidromeArtist
import app.gyrolet.mpvrx.domain.navidrome.NavidromePlaylist
import app.gyrolet.mpvrx.domain.navidrome.NavidromeServer
import app.gyrolet.mpvrx.domain.navidrome.NavidromeSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

data class NavidromeSearchResult(
  val artists: List<NavidromeArtist> = emptyList(),
  val albums: List<NavidromeAlbum> = emptyList(),
  val songs: List<NavidromeSong> = emptyList(),
)

class NavidromeClient(
  private val httpClient: OkHttpClient,
  private val json: Json,
) {
  companion object {
    private const val TAG = "NavidromeClient"
    private const val CLIENT_NAME = "mpvRx"
    private const val API_VERSION = "1.16.1"

    fun md5(input: String): String {
      val md = MessageDigest.getInstance("MD5")
      val digest = md.digest(input.toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }
    }

    fun generateSalt(length: Int = 8): String {
      val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
      val random = SecureRandom()
      return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    fun extractUsername(token: String): String? {
      val trimmed = token.trim()
      if (trimmed.contains(":")) {
        return trimmed.substringBefore(":").trim().ifBlank { null }
      }
      return try {
        val parts = trimmed.split(".")
        if (parts.size >= 2) {
          val payloadJson = String(
            android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING),
            Charsets.UTF_8,
          )
          val jsonElement = Json.parseToJsonElement(payloadJson).jsonObject
          jsonElement["sub"]?.jsonPrimitive?.content
            ?: jsonElement["username"]?.jsonPrimitive?.content
            ?: jsonElement["user"]?.jsonPrimitive?.content
        } else null
      } catch (_: Exception) {
        null
      }
    }
  }

  fun extractUsername(token: String): String? = Companion.extractUsername(token)

  fun buildSubsonicUrl(
    server: NavidromeServer,
    endpoint: String,
    extraParams: Map<String, String> = emptyMap(),
  ): String {
    val cleanBase = server.serverUrl.trimEnd('/')
    val secret = if (server.authMode == app.gyrolet.mpvrx.domain.navidrome.NavidromeAuthMode.TOKEN && server.token.isNotBlank()) {
      server.token
    } else {
      server.password
    }
    val salt = generateSalt()
    val token = md5(secret + salt)

    val uriBuilder = Uri.parse("$cleanBase/rest/$endpoint").buildUpon()
      .appendQueryParameter("u", server.username)
      .appendQueryParameter("t", token)
      .appendQueryParameter("s", salt)
      .appendQueryParameter("v", API_VERSION)
      .appendQueryParameter("c", CLIENT_NAME)
      .appendQueryParameter("f", "json")

    extraParams.forEach { (key, value) ->
      uriBuilder.appendQueryParameter(key, value)
    }

    return uriBuilder.build().toString()
  }

  fun getStreamUrl(server: NavidromeServer, songId: String): String {
    return buildSubsonicUrl(server, "stream.view", mapOf("id" to songId))
  }

  fun getCoverArtUrl(server: NavidromeServer, coverArtId: String?, size: Int = 500): String? {
    if (coverArtId.isNullOrBlank()) return null
    if (coverArtId.startsWith("http://", ignoreCase = true) || coverArtId.startsWith("https://", ignoreCase = true)) {
      return coverArtId
    }
    if (coverArtId.startsWith("/")) {
      return "${server.serverUrl.trimEnd('/')}$coverArtId"
    }
    return buildSubsonicUrl(server, "getCoverArt.view", mapOf("id" to coverArtId, "size" to size.toString()))
  }

  fun getArtistImageUrl(server: NavidromeServer, artist: NavidromeArtist, size: Int = 500): String? {
    val urlOrId = artist.artistImageUrl?.takeIf { it.isNotBlank() }
      ?: artist.coverArtId?.takeIf { it.isNotBlank() }
      ?: artist.id.takeIf { it.isNotBlank() }
    return getCoverArtUrl(server, urlOrId, size)
  }

  fun getSongCoverArtUrl(server: NavidromeServer, song: NavidromeSong, size: Int = 500): String? {
    val urlOrId = song.coverArtId?.takeIf { it.isNotBlank() }
      ?: song.albumId.takeIf { it.isNotBlank() }
      ?: song.id.takeIf { it.isNotBlank() }
    return getCoverArtUrl(server, urlOrId, size)
  }

  private suspend fun executeGet(url: String): Result<JsonObject> = withContext(Dispatchers.IO) {
    try {
      val request = Request.Builder().url(url).build()
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
        }

        val body = response.body.string()
        val root = json.parseToJsonElement(body).jsonObject
        val subsonicResponse = root["subsonic-response"]?.jsonObject
          ?: return@withContext Result.failure(Exception("Invalid Subsonic response format"))

        val status = subsonicResponse["status"]?.jsonPrimitive?.content ?: "unknown"
        if (status != "ok") {
          val errorObj = subsonicResponse["error"]?.jsonObject
          val errorCode = errorObj?.get("code")?.jsonPrimitive?.intOrNull ?: -1
          val errorMsg = errorObj?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
          return@withContext Result.failure(Exception("Subsonic error $errorCode: $errorMsg"))
        }

        Result.success(subsonicResponse)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Request failed: $url", e)
      Result.failure(e)
    }
  }

  suspend fun ping(
    serverUrl: String,
    username: String,
    password: String = "",
    token: String = "",
    authMode: app.gyrolet.mpvrx.domain.navidrome.NavidromeAuthMode = app.gyrolet.mpvrx.domain.navidrome.NavidromeAuthMode.CREDENTIALS,
  ): Result<Boolean> = withContext(Dispatchers.IO) {
    val tempServer = NavidromeServer(
      id = 0,
      name = "Temp",
      serverUrl = serverUrl,
      username = username,
      password = password,
      token = token,
      authMode = authMode,
    )
    val url = buildSubsonicUrl(tempServer, "ping.view")
    executeGet(url).map { true }
  }

  suspend fun ping(server: NavidromeServer): Result<Boolean> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(server, "ping.view")
    executeGet(url).map { true }
  }

  suspend fun getArtists(server: NavidromeServer): Result<List<NavidromeArtist>> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(server, "getArtists.view")
    executeGet(url).map { response ->
      val artistsContainer = response["artists"]?.jsonObject
      val indexList = artistsContainer?.get("index")?.jsonArray ?: emptyList()
      val result = mutableListOf<NavidromeArtist>()
      for (indexItem in indexList) {
        val artistList = indexItem.jsonObject["artist"]?.jsonArray ?: emptyList()
        for (artistItem in artistList) {
          val artistObj = artistItem.jsonObject
          result.add(parseArtist(artistObj))
        }
      }
      result
    }
  }

  suspend fun getArtist(server: NavidromeServer, artistId: String): Result<NavidromeArtist> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(server, "getArtist.view", mapOf("id" to artistId))
    executeGet(url).map { response ->
      val artistObj = response["artist"]?.jsonObject ?: JsonObject(emptyMap())
      val baseArtist = parseArtist(artistObj)
      val albumsList = artistObj["album"]?.jsonArray?.map { parseAlbum(it.jsonObject) } ?: emptyList()
      baseArtist.copy(albums = albumsList)
    }
  }

  suspend fun getAlbums(
    server: NavidromeServer,
    type: String = "alphabeticalByName",
    size: Int = 500,
    offset: Int = 0,
  ): Result<List<NavidromeAlbum>> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(
      server,
      "getAlbumList2.view",
      mapOf("type" to type, "size" to size.toString(), "offset" to offset.toString()),
    )
    val responseResult = executeGet(url)
    if (responseResult.isSuccess) {
      val response = responseResult.getOrThrow()
      val albumList = response["albumList2"]?.jsonObject?.get("album")?.jsonArray
        ?: response["albumList"]?.jsonObject?.get("album")?.jsonArray
        ?: emptyList()
      Result.success(albumList.map { parseAlbum(it.jsonObject) })
    } else {
      // Fallback to getAlbumList.view
      val fallbackUrl = buildSubsonicUrl(
        server,
        "getAlbumList.view",
        mapOf("type" to type, "size" to size.toString(), "offset" to offset.toString()),
      )
      executeGet(fallbackUrl).map { response ->
        val albumList = response["albumList"]?.jsonObject?.get("album")?.jsonArray ?: emptyList()
        albumList.map { parseAlbum(it.jsonObject) }
      }
    }
  }

  suspend fun getAlbum(server: NavidromeServer, albumId: String): Result<NavidromeAlbum> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(server, "getAlbum.view", mapOf("id" to albumId))
    executeGet(url).map { response ->
      val albumObj = response["album"]?.jsonObject ?: JsonObject(emptyMap())
      val baseAlbum = parseAlbum(albumObj)
      val songsList = albumObj["song"]?.jsonArray?.map { parseSong(it.jsonObject) } ?: emptyList()
      baseAlbum.copy(songs = songsList)
    }
  }

  suspend fun getSong(server: NavidromeServer, songId: String): Result<NavidromeSong> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(server, "getSong.view", mapOf("id" to songId))
    executeGet(url).map { response ->
      val songObj = response["song"]?.jsonObject ?: JsonObject(emptyMap())
      parseSong(songObj)
    }
  }

  suspend fun getRandomSongs(server: NavidromeServer, size: Int = 50): Result<List<NavidromeSong>> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(server, "getRandomSongs.view", mapOf("size" to size.toString()))
    executeGet(url).map { response ->
      val songList = response["randomSongs"]?.jsonObject?.get("song")?.jsonArray ?: emptyList()
      songList.map { parseSong(it.jsonObject) }
    }
  }

  suspend fun getPlaylists(server: NavidromeServer): Result<List<NavidromePlaylist>> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(server, "getPlaylists.view")
    executeGet(url).map { response ->
      val playlistContainer = response["playlists"]?.jsonObject
      val playlistArray = playlistContainer?.get("playlist")?.jsonArray ?: emptyList()
      playlistArray.map { parsePlaylist(it.jsonObject) }
    }
  }

  suspend fun getPlaylist(server: NavidromeServer, playlistId: String): Result<NavidromePlaylist> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(server, "getPlaylist.view", mapOf("id" to playlistId))
    executeGet(url).map { response ->
      val playlistObj = response["playlist"]?.jsonObject ?: JsonObject(emptyMap())
      val basePlaylist = parsePlaylist(playlistObj)
      val entries = playlistObj["entry"]?.jsonArray?.map { parseSong(it.jsonObject) } ?: emptyList()
      basePlaylist.copy(songs = entries)
    }
  }

  suspend fun search(server: NavidromeServer, query: String): Result<NavidromeSearchResult> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(
      server,
      "search3.view",
      mapOf("query" to query, "artistCount" to "20", "albumCount" to "20", "songCount" to "50"),
    )
    executeGet(url).map { response ->
      val searchResult = response["searchResult3"]?.jsonObject ?: JsonObject(emptyMap())
      val artists = searchResult["artist"]?.jsonArray?.map { parseArtist(it.jsonObject) } ?: emptyList()
      val albums = searchResult["album"]?.jsonArray?.map { parseAlbum(it.jsonObject) } ?: emptyList()
      val songs = searchResult["song"]?.jsonArray?.map { parseSong(it.jsonObject) } ?: emptyList()
      NavidromeSearchResult(artists = artists, albums = albums, songs = songs)
    }
  }

  suspend fun starItem(
    server: NavidromeServer,
    id: String? = null,
    albumId: String? = null,
    artistId: String? = null,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    val params = mutableMapOf<String, String>()
    if (!id.isNullOrBlank()) params["id"] = id
    if (!albumId.isNullOrBlank()) params["albumId"] = albumId
    if (!artistId.isNullOrBlank()) params["artistId"] = artistId
    val url = buildSubsonicUrl(server, "star.view", params)
    executeGet(url).map { }
  }

  suspend fun unstarItem(
    server: NavidromeServer,
    id: String? = null,
    albumId: String? = null,
    artistId: String? = null,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    val params = mutableMapOf<String, String>()
    if (!id.isNullOrBlank()) params["id"] = id
    if (!albumId.isNullOrBlank()) params["albumId"] = albumId
    if (!artistId.isNullOrBlank()) params["artistId"] = artistId
    val url = buildSubsonicUrl(server, "unstar.view", params)
    executeGet(url).map { }
  }

  suspend fun getStarred(server: NavidromeServer): Result<List<NavidromeSong>> = withContext(Dispatchers.IO) {
    val url = buildSubsonicUrl(server, "getStarred2.view")
    val res = executeGet(url)
    val finalRes = if (res.isFailure) {
      executeGet(buildSubsonicUrl(server, "getStarred.view"))
    } else res

    finalRes.map { response ->
      val starredObj = response["starred2"]?.jsonObject ?: response["starred"]?.jsonObject
      val songArray = starredObj?.get("song")?.jsonArray
      songArray?.map { parseSong(it.jsonObject).copy(isFavorite = true) } ?: emptyList()
    }
  }

  private fun parseSong(obj: JsonObject): NavidromeSong {
    val starred = obj["starred"]?.jsonPrimitive?.content
    return NavidromeSong(
      id = obj["id"]?.jsonPrimitive?.content ?: "",
      title = obj["title"]?.jsonPrimitive?.content ?: obj["name"]?.jsonPrimitive?.content ?: "Unknown Track",
      album = obj["album"]?.jsonPrimitive?.content ?: "",
      albumId = obj["albumId"]?.jsonPrimitive?.content ?: "",
      artist = obj["artist"]?.jsonPrimitive?.content ?: "",
      artistId = obj["artistId"]?.jsonPrimitive?.content ?: "",
      trackNumber = obj["track"]?.jsonPrimitive?.intOrNull,
      discNumber = obj["discNumber"]?.jsonPrimitive?.intOrNull,
      year = obj["year"]?.jsonPrimitive?.intOrNull,
      genre = obj["genre"]?.jsonPrimitive?.content,
      durationSeconds = obj["duration"]?.jsonPrimitive?.intOrNull ?: 0,
      bitRate = obj["bitRate"]?.jsonPrimitive?.intOrNull,
      coverArtId = obj["coverArt"]?.jsonPrimitive?.content,
      suffix = obj["suffix"]?.jsonPrimitive?.content,
      isFavorite = !starred.isNullOrBlank(),
      contentType = obj["contentType"]?.jsonPrimitive?.content,
      samplingRate = obj["samplingRate"]?.jsonPrimitive?.intOrNull,
      bitDepth = obj["bitDepth"]?.jsonPrimitive?.intOrNull,
    )
  }

  private fun parseAlbum(obj: JsonObject): NavidromeAlbum {
    val starred = obj["starred"]?.jsonPrimitive?.content
    return NavidromeAlbum(
      id = obj["id"]?.jsonPrimitive?.content ?: "",
      title = obj["title"]?.jsonPrimitive?.content ?: obj["name"]?.jsonPrimitive?.content ?: "Unknown Album",
      artist = obj["artist"]?.jsonPrimitive?.content ?: "",
      artistId = obj["artistId"]?.jsonPrimitive?.content ?: "",
      year = obj["year"]?.jsonPrimitive?.intOrNull,
      genre = obj["genre"]?.jsonPrimitive?.content,
      songCount = obj["songCount"]?.jsonPrimitive?.intOrNull ?: 0,
      durationSeconds = obj["duration"]?.jsonPrimitive?.intOrNull ?: 0,
      coverArtId = obj["coverArt"]?.jsonPrimitive?.content,
      isFavorite = !starred.isNullOrBlank(),
    )
  }

  private fun parseArtist(obj: JsonObject): NavidromeArtist {
    val starred = obj["starred"]?.jsonPrimitive?.content
    return NavidromeArtist(
      id = obj["id"]?.jsonPrimitive?.content ?: "",
      name = obj["name"]?.jsonPrimitive?.content ?: "Unknown Artist",
      albumCount = obj["albumCount"]?.jsonPrimitive?.intOrNull ?: 0,
      artistImageUrl = obj["artistImageUrl"]?.jsonPrimitive?.content,
      coverArtId = obj["coverArt"]?.jsonPrimitive?.content,
      isFavorite = !starred.isNullOrBlank(),
    )
  }

  private fun parsePlaylist(obj: JsonObject): NavidromePlaylist {
    return NavidromePlaylist(
      id = obj["id"]?.jsonPrimitive?.content ?: "",
      name = obj["name"]?.jsonPrimitive?.content ?: "Untitled Playlist",
      songCount = obj["songCount"]?.jsonPrimitive?.intOrNull ?: 0,
      durationSeconds = obj["duration"]?.jsonPrimitive?.intOrNull ?: 0,
      coverArtId = obj["coverArt"]?.jsonPrimitive?.content,
    )
  }
}
