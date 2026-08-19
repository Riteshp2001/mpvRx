/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.jellyfin

import android.util.Log
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinAuthResult
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinUser
import app.gyrolet.mpvrx.utils.media.PlaybackSubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class JellyfinClient(
  private val httpClient: OkHttpClient,
  private val json: Json,
) {
  companion object {
    const val TICKS_PER_SECOND = 10_000_000L
    private const val TAG = "JellyfinClient"
    private const val CLIENT_NAME = "mpvRx"
    private const val DEVICE_NAME = "Android"
    private const val DEVICE_ID = "mpvrx-android-player"
    private const val VERSION = "2.1.0"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun normalizeUrlCandidates(rawUrl: String): List<String> {
      val trimmed = rawUrl.trim().removeSuffix("/")
      if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return listOf(trimmed)
      }
      val clean = trimmed.removePrefix("//")
      val port = clean.substringAfterLast(":", "").substringBefore("/").toIntOrNull()
      return if (port == 80 || port == 8096) {
        listOf("http://$clean", "https://$clean")
      } else {
        listOf("https://$clean", "http://$clean")
      }
    }

    fun normalizeUrl(rawUrl: String): String {
      return normalizeUrlCandidates(rawUrl).first()
    }

    fun authHeader(token: String? = null): String {
      val base = "MediaBrowser Client=\"$CLIENT_NAME\", Device=\"$DEVICE_NAME\", DeviceId=\"$DEVICE_ID\", Version=\"$VERSION\""
      return if (!token.isNullOrBlank()) "$base, Token=\"$token\"" else base
    }

    fun getStreamUrl(
      serverUrl: String,
      itemId: String,
      token: String,
      isAudio: Boolean = false,
    ): String {
      val base = normalizeUrl(serverUrl)
      val endpoint = if (isAudio) "Audio" else "Videos"
      return "$base/$endpoint/$itemId/stream?static=true&api_key=$token"
    }

    fun getImageUrl(
      serverUrl: String,
      itemId: String,
      imageTag: String? = null,
      imageType: String = "Primary",
      maxWidth: Int = 400,
      token: String? = null,
    ): String {
      val base = normalizeUrl(serverUrl)
      val tagParam = if (!imageTag.isNullOrBlank()) "&tag=$imageTag" else ""
      val tokenParam = if (!token.isNullOrBlank()) "&api_key=$token" else ""
      return "$base/Items/$itemId/Images/$imageType?maxWidth=$maxWidth&quality=90$tagParam$tokenParam"
    }

    fun getBackdropUrl(
      serverUrl: String,
      itemId: String,
      imageTag: String? = null,
      maxWidth: Int = 1280,
      token: String? = null,
    ): String {
      val base = normalizeUrl(serverUrl)
      val tagParam = if (!imageTag.isNullOrBlank()) "&tag=$imageTag" else ""
      val tokenParam = if (!token.isNullOrBlank()) "&api_key=$token" else ""
      return "$base/Items/$itemId/Images/Backdrop/0?maxWidth=$maxWidth&quality=80$tagParam$tokenParam"
    }
  }

  suspend fun authenticate(
    serverUrl: String,
    username: String,
    password: String,
  ): Result<JellyfinAuthResult> =
    withContext(Dispatchers.IO) {
      val candidates = normalizeUrlCandidates(serverUrl)
      var lastError: Throwable = IOException("No valid URL candidate for $serverUrl")

      for (candidate in candidates) {
        try {
          val endpoint = "$candidate/Users/AuthenticateByName"
          val payload =
            JsonObject(
              mapOf(
                "Username" to kotlinx.serialization.json.JsonPrimitive(username),
                "Pw" to kotlinx.serialization.json.JsonPrimitive(password),
              ),
            ).toString()

          val request =
            Request
              .Builder()
              .url(endpoint)
              .addHeader("X-Emby-Authorization", authHeader())
              .addHeader("Content-Type", "application/json")
              .post(payload.toRequestBody(JSON_MEDIA_TYPE))
              .build()

          val result =
            httpClient.newCall(request).execute().use { response ->
              if (!response.isSuccessful) {
                throw IOException("Authentication failed: HTTP ${response.code} ${response.message}")
              }
              val bodyStr = response.body.string()
              val root = json.parseToJsonElement(bodyStr).jsonObject
              val accessToken = root["AccessToken"]?.jsonPrimitive?.content ?: throw IOException("Missing AccessToken in response")
              val userObj = root["User"]?.jsonObject ?: throw IOException("Missing User object in response")
              val userId = userObj["Id"]?.jsonPrimitive?.content ?: throw IOException("Missing User.Id in response")
              val uname = userObj["Name"]?.jsonPrimitive?.content ?: username
              val serverId = root["ServerId"]?.jsonPrimitive?.content
              val configObj = userObj["Configuration"]?.jsonObject
              val audioLang = configObj?.get("AudioLanguagePreference")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
              val subLang = configObj?.get("SubtitleLanguagePreference")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

              JellyfinAuthResult(
                accessToken = accessToken,
                userId = userId,
                username = uname,
                serverId = serverId,
                audioLanguage = audioLang,
                subtitleLanguage = subLang,
                normalizedServerUrl = candidate,
              )
            }
          return@withContext Result.success(result)
        } catch (e: Throwable) {
          lastError = e
        }
      }
      Result.failure(lastError)
    }

  suspend fun validateToken(
    serverUrl: String,
    token: String,
  ): Result<JellyfinUser> =
    withContext(Dispatchers.IO) {
      val candidates = normalizeUrlCandidates(serverUrl)
      var lastError: Throwable = IOException("No valid URL candidate for $serverUrl")

      for (candidate in candidates) {
        try {
          val endpoint = "$candidate/Users/Me"
          val request =
            Request
              .Builder()
              .url(endpoint)
              .addHeader("X-Emby-Authorization", authHeader(token))
              .addHeader("X-Emby-Token", token)
              .get()
              .build()

          val result =
            httpClient.newCall(request).execute().use { response ->
              if (!response.isSuccessful) {
                throw IOException("Token validation failed: HTTP ${response.code} ${response.message}")
              }
              val bodyStr = response.body.string()
              val userObj = json.parseToJsonElement(bodyStr).jsonObject
              val userId = userObj["Id"]?.jsonPrimitive?.content ?: throw IOException("Missing User.Id")
              val uname = userObj["Name"]?.jsonPrimitive?.content ?: "User"
              val serverId = userObj["ServerId"]?.jsonPrimitive?.content

              val configObj = userObj["Configuration"]?.jsonObject
              val audioLang = configObj?.get("AudioLanguagePreference")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
              val subLang = configObj?.get("SubtitleLanguagePreference")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

              JellyfinUser(
                id = userId,
                name = uname,
                serverId = serverId,
                audioLanguage = audioLang,
                subtitleLanguage = subLang,
                normalizedServerUrl = candidate,
              )
            }
          return@withContext Result.success(result)
        } catch (e: Throwable) {
          lastError = e
        }
      }
      Result.failure(lastError)
    }

  suspend fun getUserLibraries(
    serverUrl: String,
    userId: String,
    token: String,
  ): Result<List<JellyfinItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint = "$base/Users/$userId/Views"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Failed to load libraries: HTTP ${response.code}")
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr).jsonObject
          val itemsArray = root["Items"]?.jsonArray ?: JsonArray(emptyList())
          itemsArray.map { parseItem(it.jsonObject) }
        }
      }
    }

  suspend fun getResumeItems(
    serverUrl: String,
    userId: String,
    token: String,
    limit: Int = 16,
  ): Result<List<JellyfinItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint =
          "$base/Users/$userId/Items/Resume?Limit=$limit&Fields=Overview,PrimaryImageAspectRatio,UserData,SeriesName,SeasonName,IndexNumber,ParentIndexNumber,MediaSources,MediaStreams,Genres,OfficialRating,CommunityRating,CriticRating,ProductionYear,Taglines,PremiereDate,Status"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Failed to load resume items: HTTP ${response.code}")
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr).jsonObject
          val itemsArray = root["Items"]?.jsonArray ?: JsonArray(emptyList())
          itemsArray.map { parseItem(it.jsonObject) }
        }
      }
    }

  suspend fun getLatestMedia(
    serverUrl: String,
    userId: String,
    parentId: String? = null,
    limit: Int = 16,
    token: String,
  ): Result<List<JellyfinItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val parentParam = if (!parentId.isNullOrBlank()) "&ParentId=$parentId" else ""
        val endpoint =
          "$base/Users/$userId/Items/Latest?Limit=$limit$parentParam&Fields=Overview,PrimaryImageAspectRatio,UserData,SeriesName,SeasonName,IndexNumber,ParentIndexNumber,MediaSources,MediaStreams,Genres,OfficialRating,CommunityRating,CriticRating,ProductionYear,Taglines,ChildCount,PremiereDate,Status"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Failed to load latest media: HTTP ${response.code}")
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr)
          val itemsArray =
            if (root is JsonArray) {
              root
            } else {
              root.jsonObject["Items"]?.jsonArray ?: JsonArray(emptyList())
            }
          itemsArray.map { parseItem(it.jsonObject) }
        }
      }
    }

  suspend fun getSuggestions(
    serverUrl: String,
    userId: String,
    limit: Int = 16,
    token: String,
  ): Result<List<JellyfinItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint =
          "$base/Users/$userId/Suggestions?Limit=$limit&Fields=Overview,PrimaryImageAspectRatio,UserData,SeriesName,SeasonName,IndexNumber,ParentIndexNumber,MediaSources,MediaStreams,Genres,OfficialRating,CommunityRating,CriticRating,ProductionYear,Taglines,ChildCount,PremiereDate,Status"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Failed to load suggestions: HTTP ${response.code}")
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr)
          val itemsArray =
            if (root is JsonArray) {
              root
            } else {
              root.jsonObject["Items"]?.jsonArray ?: JsonArray(emptyList())
            }
          itemsArray.map { parseItem(it.jsonObject) }
        }
      }
    }

  suspend fun getSimilarItems(
    serverUrl: String,
    userId: String,
    itemId: String,
    limit: Int = 12,
    token: String,
  ): Result<List<JellyfinItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint =
          "$base/Items/$itemId/Similar?UserId=$userId&Limit=$limit&Fields=Overview,PrimaryImageAspectRatio,UserData,SeriesName,SeasonName,IndexNumber,ParentIndexNumber,MediaSources,MediaStreams,Genres,OfficialRating,CommunityRating,CriticRating,ProductionYear,Taglines,ChildCount,PremiereDate,Status"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Failed to load similar items: HTTP ${response.code}")
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr).jsonObject
          val itemsArray = root["Items"]?.jsonArray ?: JsonArray(emptyList())
          itemsArray.map { parseItem(it.jsonObject) }
        }
      }
    }

  suspend fun getItem(
    serverUrl: String,
    userId: String,
    itemId: String,
    token: String,
  ): Result<JellyfinItem> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint =
          "$base/Users/$userId/Items/$itemId?Fields=Overview,PrimaryImageAspectRatio,UserData,SeriesName,SeasonName,IndexNumber,ParentIndexNumber,MediaSources,MediaStreams,Genres,OfficialRating,CommunityRating,CriticRating,ProductionYear,Taglines,ChildCount,PremiereDate,Status,People"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Failed to load item: HTTP ${response.code}")
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr).jsonObject
          parseItem(root)
        }
      }
    }

  suspend fun getItems(
    serverUrl: String,
    userId: String,
    parentId: String? = null,
    searchTerm: String? = null,
    sortBy: app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.NAME,
    sortOrder: app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder.ASCENDING,
    isPlayed: Boolean? = null,
    isFavorite: Boolean? = null,
    genres: String? = null,
    includeItemTypes: String? = null,
    startIndex: Int = 0,
    limit: Int = 100,
    token: String,
  ): Result<app.gyrolet.mpvrx.domain.jellyfin.JellyfinQueryResult> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val urlBuilder =
          StringBuilder(
            "$base/Users/$userId/Items?Fields=Overview,PrimaryImageAspectRatio,UserData,ChildCount,MediaSources,MediaStreams,ProductionYear,CommunityRating,CriticRating,Genres,OfficialRating,Taglines,SeriesName,SeasonName,IndexNumber,ParentIndexNumber,PremiereDate,Status&StartIndex=$startIndex&Limit=$limit&SortBy=${sortBy.apiValue}&SortOrder=${sortOrder.apiValue}",
          )

        if (!parentId.isNullOrBlank()) {
          urlBuilder.append("&ParentId=$parentId")
        }
        if (!searchTerm.isNullOrBlank()) {
          urlBuilder.append("&SearchTerm=${java.net.URLEncoder.encode(searchTerm, "UTF-8")}&Recursive=true")
        }
        if (!includeItemTypes.isNullOrBlank()) {
          urlBuilder.append("&IncludeItemTypes=$includeItemTypes&Recursive=true")
        }
        if (!genres.isNullOrBlank()) {
          urlBuilder.append("&Genres=${java.net.URLEncoder.encode(genres, "UTF-8")}")
        }
        val filters = mutableListOf<String>()
        if (isPlayed != null) {
          filters.add(if (isPlayed) "IsPlayed" else "IsUnplayed")
        }
        if (isFavorite == true) {
          filters.add("IsFavorite")
        }
        if (filters.isNotEmpty()) {
          urlBuilder.append("&Filters=${filters.joinToString(",")}")
        }

        val request =
          Request
            .Builder()
            .url(urlBuilder.toString())
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Failed to load items: HTTP ${response.code}")
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr).jsonObject
          val totalRecordCount = root["TotalRecordCount"]?.jsonPrimitive?.intOrNull ?: 0
          val itemsArray = root["Items"]?.jsonArray ?: JsonArray(emptyList())
          val items = itemsArray.map { parseItem(it.jsonObject) }
          val sortedItems =
            if (!searchTerm.isNullOrBlank()) {
              items.sortedBy { it.searchPriority }
            } else {
              items
            }
          app.gyrolet.mpvrx.domain.jellyfin.JellyfinQueryResult(
            items = sortedItems,
            totalRecordCount = if (totalRecordCount > 0) totalRecordCount else items.size,
            startIndex = startIndex,
          )
        }
      }
    }

  suspend fun getSeasons(
    serverUrl: String,
    userId: String,
    seriesId: String,
    token: String,
  ): Result<List<JellyfinItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint =
          "$base/Shows/$seriesId/Seasons?UserId=$userId&Fields=Overview,PrimaryImageAspectRatio,UserData,ChildCount,ProductionYear,CommunityRating"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Failed to load seasons: HTTP ${response.code}")
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr).jsonObject
          val itemsArray = root["Items"]?.jsonArray ?: JsonArray(emptyList())
          itemsArray.map { parseItem(it.jsonObject) }
        }
      }
    }

  suspend fun getEpisodes(
    serverUrl: String,
    userId: String,
    seriesId: String,
    seasonId: String,
    token: String,
  ): Result<List<JellyfinItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint =
          "$base/Shows/$seriesId/Episodes?SeasonId=$seasonId&UserId=$userId&Fields=Overview,PrimaryImageAspectRatio,UserData,MediaSources,MediaStreams,IndexNumber,ParentIndexNumber,CommunityRating,CriticRating,OfficialRating,PremiereDate"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Failed to load episodes: HTTP ${response.code}")
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr).jsonObject
          val itemsArray = root["Items"]?.jsonArray ?: JsonArray(emptyList())
          itemsArray.map { parseItem(it.jsonObject) }
        }
      }
    }

  fun getStreamUrl(
    serverUrl: String,
    itemId: String,
    token: String,
    isAudio: Boolean = false,
  ): String = Companion.getStreamUrl(serverUrl, itemId, token, isAudio)

  fun getImageUrl(
    serverUrl: String,
    itemId: String,
    imageTag: String? = null,
    imageType: String = "Primary",
    maxWidth: Int = 400,
    token: String? = null,
  ): String = Companion.getImageUrl(serverUrl, itemId, imageTag, imageType, maxWidth, token)

  fun getBackdropUrl(
    serverUrl: String,
    itemId: String,
    imageTag: String? = null,
    maxWidth: Int = 1280,
    token: String? = null,
  ): String = Companion.getBackdropUrl(serverUrl, itemId, imageTag, maxWidth, token)

  suspend fun getSubtitleTracks(
    serverUrl: String,
    token: String,
    userId: String,
    itemId: String,
  ): Result<List<PlaybackSubtitleTrack>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint = "$base/Users/$userId/Items/$itemId?Fields=MediaStreams"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            return@use emptyList<PlaybackSubtitleTrack>()
          }
          val bodyStr = response.body.string()
          val root = json.parseToJsonElement(bodyStr).jsonObject
          val streams = root["MediaStreams"]?.jsonArray ?: return@use emptyList<PlaybackSubtitleTrack>()
          val mediaSourceId = root["MediaSources"]?.jsonArray?.firstOrNull()?.jsonObject?.get("Id")?.jsonPrimitive?.content ?: itemId

          streams.mapNotNull { element ->
            val stream = element.jsonObject
            val type = stream["Type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!type.equals("Subtitle", ignoreCase = true)) return@mapNotNull null

            val isExternal = stream["IsExternal"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!isExternal) return@mapNotNull null

            val index = stream["Index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val codec = stream["Codec"]?.jsonPrimitive?.content ?: "srt"
            val language = stream["Language"]?.jsonPrimitive?.content
            val displayTitle = stream["DisplayTitle"]?.jsonPrimitive?.content ?: language ?: "Subtitle #$index"
            val deliveryUrl = stream["DeliveryUrl"]?.jsonPrimitive?.content

            val subUrl =
              if (!deliveryUrl.isNullOrBlank()) {
                if (deliveryUrl.startsWith("http")) deliveryUrl else "$base$deliveryUrl"
              } else {
                "$base/Videos/$itemId/$mediaSourceId/Subtitles/$index/Stream.$codec"
              }

            val finalUrl = if (subUrl.contains("?")) "$subUrl&api_key=$token" else "$subUrl?api_key=$token"
            PlaybackSubtitleTrack(
              url = finalUrl,
              label = displayTitle,
              languageCode = language,
            )
          }
        }
      }
    }

  suspend fun reportPlaybackStart(
    serverUrl: String,
    token: String,
    itemId: String,
    positionTicks: Long = 0L,
  ) = withContext(Dispatchers.IO) {
    runCatching {
      val base = normalizeUrl(serverUrl)
      val endpoint = "$base/Sessions/Playing"
      val payload =
        JsonObject(
          mapOf(
            "ItemId" to kotlinx.serialization.json.JsonPrimitive(itemId),
            "PositionTicks" to kotlinx.serialization.json.JsonPrimitive(positionTicks),
          ),
        ).toString()

      val request =
        Request
          .Builder()
          .url(endpoint)
          .addHeader("X-Emby-Authorization", authHeader(token))
          .addHeader("X-Emby-Token", token)
          .addHeader("Content-Type", "application/json")
          .post(payload.toRequestBody(JSON_MEDIA_TYPE))
          .build()

      httpClient.newCall(request).execute().close()
    }.onFailure { Log.w(TAG, "Failed reporting playback start: ${it.message}") }
  }

  suspend fun reportPlaybackProgress(
    serverUrl: String,
    token: String,
    itemId: String,
    positionTicks: Long,
    isPaused: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    runCatching {
      val base = normalizeUrl(serverUrl)
      val endpoint = "$base/Sessions/Playing/Progress"
      val payload =
        JsonObject(
          mapOf(
            "ItemId" to kotlinx.serialization.json.JsonPrimitive(itemId),
            "PositionTicks" to kotlinx.serialization.json.JsonPrimitive(positionTicks),
            "IsPaused" to kotlinx.serialization.json.JsonPrimitive(isPaused),
          ),
        ).toString()

      val request =
        Request
          .Builder()
          .url(endpoint)
          .addHeader("X-Emby-Authorization", authHeader(token))
          .addHeader("X-Emby-Token", token)
          .addHeader("Content-Type", "application/json")
          .post(payload.toRequestBody(JSON_MEDIA_TYPE))
          .build()

      httpClient.newCall(request).execute().close()
    }.onFailure { Log.w(TAG, "Failed reporting playback progress: ${it.message}") }
  }

  suspend fun reportPlaybackStopped(
    serverUrl: String,
    token: String,
    itemId: String,
    positionTicks: Long,
  ) = withContext(Dispatchers.IO) {
    runCatching {
      val base = normalizeUrl(serverUrl)
      val endpoint = "$base/Sessions/Playing/Stopped"
      val payload =
        JsonObject(
          mapOf(
            "ItemId" to kotlinx.serialization.json.JsonPrimitive(itemId),
            "PositionTicks" to kotlinx.serialization.json.JsonPrimitive(positionTicks),
          ),
        ).toString()

      val request =
        Request
          .Builder()
          .url(endpoint)
          .addHeader("X-Emby-Authorization", authHeader(token))
          .addHeader("X-Emby-Token", token)
          .addHeader("Content-Type", "application/json")
          .post(payload.toRequestBody(JSON_MEDIA_TYPE))
          .build()

      httpClient.newCall(request).execute().close()
    }.onFailure { Log.w(TAG, "Failed reporting playback stop: ${it.message}") }
  }

  suspend fun markPlayed(
    serverUrl: String,
    userId: String,
    itemId: String,
    token: String,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint = "$base/Users/$userId/PlayedItems/$itemId"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Failed to mark as played: HTTP ${response.code}")
        }
      }
    }

  suspend fun markUnplayed(
    serverUrl: String,
    userId: String,
    itemId: String,
    token: String,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint = "$base/Users/$userId/PlayedItems/$itemId"
        val request =
          Request
            .Builder()
            .url(endpoint)
            .addHeader("X-Emby-Authorization", authHeader(token))
            .addHeader("X-Emby-Token", token)
            .delete()
            .build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Failed to mark as unplayed: HTTP ${response.code}")
        }
      }
    }

  suspend fun toggleFavorite(
    serverUrl: String,
    userId: String,
    itemId: String,
    isFavorite: Boolean,
    token: String,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        val base = normalizeUrl(serverUrl)
        val endpoint = "$base/Users/$userId/FavoriteItems/$itemId"
        val request =
          if (isFavorite) {
            Request
              .Builder()
              .url(endpoint)
              .addHeader("X-Emby-Authorization", authHeader(token))
              .addHeader("X-Emby-Token", token)
              .post(ByteArray(0).toRequestBody(null))
              .build()
          } else {
            Request
              .Builder()
              .url(endpoint)
              .addHeader("X-Emby-Authorization", authHeader(token))
              .addHeader("X-Emby-Token", token)
              .delete()
              .build()
          }
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Failed to toggle favorite: HTTP ${response.code}")
        }
      }
    }

  private fun parseItem(obj: JsonObject): JellyfinItem {
    val id = obj["Id"]?.jsonPrimitive?.content ?: ""
    val name = obj["Name"]?.jsonPrimitive?.content ?: ""
    val type = obj["Type"]?.jsonPrimitive?.content ?: obj["CollectionType"]?.jsonPrimitive?.content ?: "Folder"
    val collectionType = obj["CollectionType"]?.jsonPrimitive?.content
    val overview = obj["Overview"]?.jsonPrimitive?.content
    val runTimeTicks = obj["RunTimeTicks"]?.jsonPrimitive?.longOrNull
    val isFolder = obj["IsFolder"]?.jsonPrimitive?.booleanOrNull ?: (type == "CollectionFolder" || type == "Folder" || type == "Series" || type == "Season")
    val productionYear = obj["ProductionYear"]?.jsonPrimitive?.intOrNull
    val communityRating = obj["CommunityRating"]?.jsonPrimitive?.content?.toDoubleOrNull()
    val criticRating = obj["CriticRating"]?.jsonPrimitive?.content?.toDoubleOrNull()
    val officialRating = obj["OfficialRating"]?.jsonPrimitive?.content
    val seriesName = obj["SeriesName"]?.jsonPrimitive?.content
    val seasonName = obj["SeasonName"]?.jsonPrimitive?.content
    val indexNumber = obj["IndexNumber"]?.jsonPrimitive?.intOrNull
    val parentIndexNumber = obj["ParentIndexNumber"]?.jsonPrimitive?.intOrNull
    val childCount = obj["ChildCount"]?.jsonPrimitive?.intOrNull
    val container = obj["Container"]?.jsonPrimitive?.content
    val premiereDate = obj["PremiereDate"]?.jsonPrimitive?.content
    val status = obj["Status"]?.jsonPrimitive?.content

    val genresList =
      obj["Genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.takeIf { it.isNotEmpty() }
        ?: emptyList()

    val taglinesList =
      obj["Taglines"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.takeIf { it.isNotEmpty() }
        ?: emptyList()

    val imageTagsObj = obj["ImageTags"]?.jsonObject
    val primaryImageTag = imageTagsObj?.get("Primary")?.jsonPrimitive?.content
    val backdropImageTags = obj["BackdropImageTags"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content

    val userDataObj = obj["UserData"]?.jsonObject
    val playbackPositionTicks = userDataObj?.get("PlaybackPositionTicks")?.jsonPrimitive?.longOrNull
    val isPlayed = userDataObj?.get("Played")?.jsonPrimitive?.booleanOrNull ?: false
    val isFavorite = userDataObj?.get("IsFavorite")?.jsonPrimitive?.booleanOrNull ?: false
    val lastPlayedDate = userDataObj?.get("LastPlayedDate")?.jsonPrimitive?.content

    var videoCodec: String? = null
    var videoResolution: String? = null
    var videoHdrType: String? = null
    var audioCodec: String? = null
    var audioChannels: String? = null

    val mediaStreams = obj["MediaStreams"]?.jsonArray
    if (mediaStreams != null) {
      for (streamElement in mediaStreams) {
        val stream = streamElement.jsonObject
        val streamType = stream["Type"]?.jsonPrimitive?.content
        if (streamType.equals("Video", ignoreCase = true) && videoCodec == null) {
          videoCodec = stream["Codec"]?.jsonPrimitive?.content?.uppercase()
          val width = stream["Width"]?.jsonPrimitive?.intOrNull ?: 0
          val height = stream["Height"]?.jsonPrimitive?.intOrNull ?: 0
          videoResolution =
            when {
              width >= 3800 || height >= 2100 -> "4K"
              width >= 1900 || height >= 1000 -> "1080p"
              width >= 1200 || height >= 700 -> "720p"
              width > 0 -> "${height}p"
              else -> null
            }
          val videoRange = stream["VideoRange"]?.jsonPrimitive?.content
          val videoRangeType = stream["VideoRangeType"]?.jsonPrimitive?.content
          videoHdrType =
            when {
              videoRangeType?.contains("DOVI", ignoreCase = true) == true || videoRange?.contains("DOVI", ignoreCase = true) == true -> "Dolby Vision"
              videoRangeType?.contains("HDR10+", ignoreCase = true) == true -> "HDR10+"
              videoRangeType?.contains("HDR10", ignoreCase = true) == true || videoRange?.contains("HDR", ignoreCase = true) == true -> "HDR"
              else -> null
            }
        } else if (streamType.equals("Audio", ignoreCase = true) && audioCodec == null) {
          val rawAudioCodec = stream["Codec"]?.jsonPrimitive?.content?.uppercase()
          audioCodec =
            when (rawAudioCodec) {
              "TRUEHD" -> "TrueHD"
              "DTS-HD", "DTSHD" -> "DTS-HD"
              "EAC3", "E-AC-3" -> "E-AC-3"
              "AC3" -> "Dolby Digital"
              "FLAC" -> "FLAC"
              "AAC" -> "AAC"
              "OPUS" -> "Opus"
              else -> rawAudioCodec
            }
          val channels = stream["Channels"]?.jsonPrimitive?.intOrNull
          audioChannels =
            when (channels) {
              8 -> "7.1"
              6 -> "5.1"
              2 -> "2.0"
              1 -> "1.0"
              else -> channels?.let { "${it}ch" }
            }
        }
      }
    }

    return JellyfinItem(
      id = id,
      name = name,
      type = type,
      collectionType = collectionType,
      overview = overview,
      runTimeTicks = runTimeTicks,
      playbackPositionTicks = playbackPositionTicks,
      isPlayed = isPlayed,
      isFavorite = isFavorite,
      seriesName = seriesName,
      seasonName = seasonName,
      indexNumber = indexNumber,
      parentIndexNumber = parentIndexNumber,
      productionYear = productionYear,
      communityRating = communityRating,
      criticRating = criticRating,
      officialRating = officialRating,
      taglines = taglinesList,
      genres = genresList,
      isFolder = isFolder,
      primaryImageTag = primaryImageTag,
      backdropImageTag = backdropImageTags,
      childCount = childCount,
      container = container,
      videoCodec = videoCodec,
      videoResolution = videoResolution,
      videoHdrType = videoHdrType,
      audioCodec = audioCodec,
      audioChannels = audioChannels,
      premiereDate = premiereDate,
      status = status,
      lastPlayedDate = lastPlayedDate,
    )
  }
}
