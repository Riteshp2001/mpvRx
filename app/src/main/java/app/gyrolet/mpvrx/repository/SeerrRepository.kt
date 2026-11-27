/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.data.network.ServerUrlUtils
import app.gyrolet.mpvrx.domain.seerr.CreateRequestBody
import app.gyrolet.mpvrx.domain.seerr.JellyseerrRequest
import app.gyrolet.mpvrx.domain.seerr.JellyseerrSearchResult
import app.gyrolet.mpvrx.domain.seerr.JellyseerrUser
import app.gyrolet.mpvrx.domain.seerr.MediaDetails
import app.gyrolet.mpvrx.domain.seerr.MediaResultsResponse
import app.gyrolet.mpvrx.domain.seerr.MediaType
import app.gyrolet.mpvrx.domain.seerr.RequestsResponse
import app.gyrolet.mpvrx.domain.seerr.SearchResultItem
import app.gyrolet.mpvrx.domain.seerr.SeerrRadarrServer
import app.gyrolet.mpvrx.domain.seerr.SeerrRadarrServerResponse
import app.gyrolet.mpvrx.domain.seerr.SeerrSonarrServer
import app.gyrolet.mpvrx.domain.seerr.SeerrSonarrServerResponse
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.preferences.SeerrPreferences
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SeerrRepository(
  private val httpClient: OkHttpClient,
  private val json: Json,
  private val preferences: SeerrPreferences,
) {
  private val mediaDetailsCache = ConcurrentHashMap<Pair<String, Int>, MediaDetails>()
  private val mediaDetailsEnrichmentSemaphore = Semaphore(MAX_CONCURRENT_MEDIA_ENRICHMENTS)

  companion object {
    private const val TAG = "SeerrRepository"
    private const val MAX_CONCURRENT_MEDIA_ENRICHMENTS = 4
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }

  private val _isAuthenticated = MutableStateFlow(preferences.isLoggedIn.get())
  val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

  private val _currentUser = MutableStateFlow<JellyseerrUser?>(null)
  val currentUser: StateFlow<JellyseerrUser?> = _currentUser.asStateFlow()

  private val shortClient =
    httpClient
      .newBuilder()
      .connectTimeout(6, TimeUnit.SECONDS)
      .readTimeout(8, TimeUnit.SECONDS)
      .build()

  fun generateCandidateUrls(input: String): List<String> =
    ServerUrlUtils.generateCandidateUrls(input, defaultPort = 5055)

  suspend fun verifyServer(url: String): Boolean =
    withContext(Dispatchers.IO) {
      try {
        var cleanUrl = url.trim().removeSuffix("/")
        if (!cleanUrl.endsWith("/api/v1/status", ignoreCase = true)) {
          cleanUrl = "$cleanUrl/api/v1/status"
        }
        val request =
          Request
            .Builder()
            .url(cleanUrl)
            .header("User-Agent", "mpvRx Android")
            .get()
            .build()
        shortClient.newCall(request).awaitResponse().use { it.isSuccessful }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        Log.d(TAG, "verifyServer failed for $url: ${e.message}")
        false
      }
    }

  private fun buildRequest(
    path: String,
    method: String = "GET",
    bodyJson: String? = null,
    queryParameters: Map<String, String?> = emptyMap(),
    baseUrlOverride: String? = null,
  ): Request {
    val baseUrl =
      baseUrlOverride ?: preferences.serverUrl
        .get()
        .trim()
        .removeSuffix("/")
    val cleanPath = path.removePrefix("/")
    val fullUrlString = "$baseUrl/$cleanPath"
    val httpUrlBuilder =
      fullUrlString.toHttpUrlOrNull()?.newBuilder()
        ?: throw IllegalArgumentException("Invalid Seerr URL: $fullUrlString")

    queryParameters.forEach { (k, v) ->
      if (!v.isNullOrBlank()) {
        httpUrlBuilder.addQueryParameter(k, v)
      }
    }

    val requestBuilder =
      Request
        .Builder()
        .url(httpUrlBuilder.build())
        .header("User-Agent", "mpvRx Android")

    val apiKey = preferences.apiKey.get().trim()
    if (apiKey.isNotBlank()) {
      requestBuilder.header("X-Api-Key", apiKey)
    }

    val requestBody = bodyJson?.toRequestBody(JSON_MEDIA_TYPE)
    when (method.uppercase()) {
      "GET" -> requestBuilder.get()
      "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(JSON_MEDIA_TYPE))
      "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(JSON_MEDIA_TYPE))
      "DELETE" -> {
        if (requestBody != null) requestBuilder.delete(requestBody) else requestBuilder.delete()
      }
    }
    return requestBuilder.build()
  }

  private suspend inline fun <reified T> executeCall(
    request: Request,
    errorMessage: String,
  ): Result<T> =
    withContext(Dispatchers.IO) {
      try {
        httpClient.newCall(request).awaitResponse().use { response ->
          val bodyStr = response.body.string()
          if (response.isSuccessful) {
            val parsed = json.decodeFromString<T>(bodyStr)
            Result.success(parsed)
          } else {
            Log.w(TAG, "$errorMessage: HTTP ${response.code} - $bodyStr")
            Result.failure(Exception("$errorMessage (HTTP ${response.code}): $bodyStr"))
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        Log.e(TAG, "$errorMessage: ${e.message}", e)
        Result.failure(e)
      }
    }

  suspend fun login(
    serverUrl: String,
    email: String,
    password: String,
    useJellyfinAuth: Boolean = true,
  ): Result<JellyseerrUser> =
    withContext(Dispatchers.IO) {
      val cleanUrl = serverUrl.trim().removeSuffix("/")
      val endpoint = if (useJellyfinAuth) "api/v1/auth/jellyfin" else "api/v1/auth/local"
      val payload =
        buildJsonObject {
          if (useJellyfinAuth) {
            put("username", email)
            put("password", password)
          } else {
            put("email", email)
            put("password", password)
          }
        }.toString()

      val req =
        buildRequest(
          path = endpoint,
          method = "POST",
          bodyJson = payload,
          baseUrlOverride = cleanUrl,
        )

      try {
        httpClient.newCall(req).awaitResponse().use { resp ->
          val bodyStr = resp.body.string()
          if (resp.isSuccessful) {
            val user = json.decodeFromString<JellyseerrUser>(bodyStr)
            preferences.serverUrl.set(cleanUrl)
            preferences.userEmail.set(user.email ?: email)
            preferences.userDisplayName.set(user.displayName ?: user.username ?: email)
            preferences.username.set(user.username ?: email)
            preferences.userAvatar.set(user.avatar ?: "")
            preferences.userId.set(user.id)
            preferences.userPermissions.set(user.permissions)
            preferences.useJellyfinAuth.set(useJellyfinAuth)
            preferences.isLoggedIn.set(true)
            _isAuthenticated.value = true
            _currentUser.value = user
            Result.success(user)
          } else {
            Result.failure(Exception("Login failed (HTTP ${resp.code}): $bodyStr"))
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  suspend fun loginWithApiKey(
    serverUrl: String,
    apiKey: String,
  ): Result<JellyseerrUser> =
    withContext(Dispatchers.IO) {
      val cleanUrl = serverUrl.trim().removeSuffix("/")
      val cleanApiKey = apiKey.trim()
      preferences.serverUrl.set(cleanUrl)
      preferences.apiKey.set(cleanApiKey)

      val req =
        buildRequest(
          path = "api/v1/auth/me",
          method = "GET",
          baseUrlOverride = cleanUrl,
        )

      try {
        httpClient.newCall(req).awaitResponse().use { resp ->
          val bodyStr = resp.body.string()
          if (resp.isSuccessful) {
            val user = json.decodeFromString<JellyseerrUser>(bodyStr)
            preferences.userEmail.set(user.email ?: "")
            preferences.userDisplayName.set(user.displayName ?: user.username ?: "Admin")
            preferences.username.set(user.username ?: "Admin")
            preferences.userAvatar.set(user.avatar ?: "")
            preferences.userId.set(user.id)
            preferences.userPermissions.set(user.permissions)
            preferences.isLoggedIn.set(true)
            _isAuthenticated.value = true
            _currentUser.value = user
            Result.success(user)
          } else {
            preferences.apiKey.set("")
            Result.failure(Exception("API Key validation failed (HTTP ${resp.code}): $bodyStr"))
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        preferences.apiKey.set("")
        Result.failure(e)
      }
    }

  suspend fun logout(): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val req = buildRequest(path = "api/v1/auth/logout", method = "POST")
        httpClient.newCall(req).awaitResponse().close()
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        Log.d(TAG, "Logout network call failed: ${e.message}")
      } finally {
        preferences.clearSession()
        _isAuthenticated.value = false
        _currentUser.value = null
      }
      Result.success(Unit)
    }

  suspend fun getCurrentUser(): Result<JellyseerrUser> {
    val req = buildRequest(path = "api/v1/auth/me")
    val res = executeCall<JellyseerrUser>(req, "Failed to get current user")
    res.onSuccess { user ->
      _currentUser.value = user
      _isAuthenticated.value = true
      preferences.userEmail.set(user.email ?: "")
      preferences.userDisplayName.set(user.displayName ?: user.username ?: "")
      preferences.username.set(user.username ?: "")
      preferences.userAvatar.set(user.avatar ?: "")
      preferences.userId.set(user.id)
      preferences.userPermissions.set(user.permissions)
      preferences.isLoggedIn.set(true)
    }
    return res
  }

  suspend fun getRecentlyAdded(take: Int = 20): Result<List<SearchResultItem>> {
    val req =
      buildRequest(
        path = "api/v1/media",
        queryParameters =
          mapOf(
            "take" to take.toString(),
            "filter" to "available",
            "sort" to "mediaAddedAt",
          ),
      )
    val res = executeCall<MediaResultsResponse>(req, "Failed to get recently added media")
    return res.map { resp ->
      resp.results.map { media ->
        val item = media.toSearchResultItem()
        val tmdbId = item.id
        if (item.title.isNullOrBlank() && item.name.isNullOrBlank() && tmdbId > 0) {
          val cached = mediaDetailsCache[item.mediaType.lowercase() to tmdbId]
          if (cached != null) {
            item.copy(
              title = cached.title,
              name = cached.name,
              posterPath = cached.posterPath ?: item.posterPath,
              backdropPath = cached.backdropPath ?: item.backdropPath,
              releaseDate = cached.releaseDate ?: item.releaseDate,
              firstAirDate = cached.firstAirDate ?: item.firstAirDate,
              voteAverage = cached.voteAverage,
            )
          } else {
            item
          }
        } else {
          item
        }
      }
    }
  }

  suspend fun getTrending(page: Int = 1): Result<JellyseerrSearchResult> {
    val req =
      buildRequest(
        path = "api/v1/discover/trending",
        queryParameters = mapOf("page" to page.toString()),
      )
    return executeCall<JellyseerrSearchResult>(req, "Failed to get trending media")
  }

  suspend fun getDiscoverMovies(
    page: Int = 1,
    sortBy: String = "popularity.desc",
    studio: Int? = null,
    genreId: Int? = null,
  ): Result<JellyseerrSearchResult> {
    val queryParams =
      mutableMapOf(
        "page" to page.toString(),
        "sortBy" to sortBy,
      )
    if (studio != null) queryParams["studio"] = studio.toString()
    if (genreId != null) queryParams["genre"] = genreId.toString()

    val req = buildRequest(path = "api/v1/discover/movies", queryParameters = queryParams)
    return executeCall<JellyseerrSearchResult>(req, "Failed to discover movies")
  }

  suspend fun getDiscoverTv(
    page: Int = 1,
    sortBy: String = "popularity.desc",
    network: Int? = null,
    genreId: Int? = null,
  ): Result<JellyseerrSearchResult> {
    val queryParams =
      mutableMapOf(
        "page" to page.toString(),
        "sortBy" to sortBy,
      )
    if (network != null) queryParams["network"] = network.toString()
    if (genreId != null) queryParams["genre"] = genreId.toString()

    val req = buildRequest(path = "api/v1/discover/tv", queryParameters = queryParams)
    return executeCall<JellyseerrSearchResult>(req, "Failed to discover TV shows")
  }

  suspend fun getUpcomingMovies(page: Int = 1): Result<JellyseerrSearchResult> {
    val req =
      buildRequest(
        path = "api/v1/discover/movies/upcoming",
        queryParameters = mapOf("page" to page.toString()),
      )
    return executeCall<JellyseerrSearchResult>(req, "Failed to get upcoming movies")
  }

  suspend fun getUpcomingTv(page: Int = 1): Result<JellyseerrSearchResult> {
    val req =
      buildRequest(
        path = "api/v1/discover/tv/upcoming",
        queryParameters = mapOf("page" to page.toString()),
      )
    return executeCall<JellyseerrSearchResult>(req, "Failed to get upcoming TV")
  }

  suspend fun searchMedia(
    query: String,
    page: Int = 1,
  ): Result<JellyseerrSearchResult> {
    val req =
      buildRequest(
        path = "api/v1/search",
        queryParameters = mapOf("query" to query, "page" to page.toString()),
      )
    return executeCall<JellyseerrSearchResult>(req, "Search failed for '$query'")
  }

  suspend fun getMovieDetails(
    tmdbId: Int,
    forceRefresh: Boolean = false,
  ): Result<MediaDetails> {
    if (!forceRefresh) {
      val cached = mediaDetailsCache["movie" to tmdbId]
      if (cached != null) return Result.success(cached)
    }
    val req = buildRequest(path = "api/v1/movie/$tmdbId")
    val res = executeCall<MediaDetails>(req, "Failed to get movie details for $tmdbId")
    res.onSuccess { mediaDetailsCache["movie" to tmdbId] = it }
    return res
  }

  suspend fun getTvDetails(
    tmdbId: Int,
    forceRefresh: Boolean = false,
  ): Result<MediaDetails> {
    if (!forceRefresh) {
      val cached = mediaDetailsCache["tv" to tmdbId]
      if (cached != null) return Result.success(cached)
    }
    val req = buildRequest(path = "api/v1/tv/$tmdbId")
    val res = executeCall<MediaDetails>(req, "Failed to get TV details for $tmdbId")
    res.onSuccess { mediaDetailsCache["tv" to tmdbId] = it }
    return res
  }

  suspend fun enrichRequests(requests: List<JellyseerrRequest>): List<JellyseerrRequest> =
    withContext(Dispatchers.IO) {
      val keys = requests.mapNotNull { request -> request.mediaDetailsKey() }.distinct()
      val detailsByKey =
        coroutineScope {
          keys
            .map { key ->
              async {
                mediaDetailsEnrichmentSemaphore.withPermit {
                  key to fetchMediaDetails(key)
                }
              }
            }.awaitAll()
            .mapNotNull { (key, details) -> details?.let { key to it } }
            .toMap()
        }

      requests.map { request ->
        request.mediaDetailsKey()?.let(detailsByKey::get)?.let { details -> request.withMediaDetails(details) }
          ?: request
      }
    }

  private fun JellyseerrRequest.mediaDetailsKey(): Pair<String, Int>? {
    if (!media.title.isNullOrBlank() || !media.name.isNullOrBlank()) return null
    val tmdbId = media.tmdbId ?: return null
    return media.mediaType.lowercase() to tmdbId
  }

  private suspend fun fetchMediaDetails(key: Pair<String, Int>): MediaDetails? {
    mediaDetailsCache[key]?.let { return it }
    val (mediaType, tmdbId) = key
    return (if (mediaType == "tv") getTvDetails(tmdbId) else getMovieDetails(tmdbId)).getOrNull()
  }

  private fun JellyseerrRequest.withMediaDetails(details: MediaDetails): JellyseerrRequest =
    copy(
      media =
        media.copy(
          title = details.title,
          name = details.name,
          posterPath = details.posterPath,
          backdropPath = details.backdropPath,
          releaseDate = details.releaseDate,
          firstAirDate = details.firstAirDate,
        ),
    )

  suspend fun getRequests(
    take: Int = 50,
    skip: Int = 0,
    filter: String? = null,
    sort: String = "added",
  ): Result<List<JellyseerrRequest>> =
    withContext(Dispatchers.IO) {
      val queryParams =
        mutableMapOf(
          "take" to take.toString(),
          "skip" to skip.toString(),
          "sort" to sort,
        )
      if (!filter.isNullOrBlank()) queryParams["filter"] = filter

      val req = buildRequest(path = "api/v1/request", queryParameters = queryParams)
      try {
        val responseBody =
          httpClient.newCall(req).awaitResponse().use { response ->
            val bodyStr = response.body.string()
            if (!response.isSuccessful) {
              return@withContext Result.failure(Exception("Failed to load requests (HTTP ${response.code}): $bodyStr"))
            }
            json.decodeFromString<RequestsResponse>(bodyStr)
          }
        Result.success(enrichRequests(responseBody.results))
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  suspend fun getRadarrServers(): Result<List<SeerrRadarrServer>> =
    withContext(Dispatchers.IO) {
      val req = buildRequest(path = "api/v1/service/radarr")
      val res = executeCall<List<SeerrRadarrServer>>(req, "Failed to load Radarr servers")
      res.map { servers ->
        servers.map { server ->
          val serverId = server.id ?: return@map server
          val detailReq = buildRequest(path = "api/v1/service/radarr/$serverId")
          val detailRes =
            executeCall<SeerrRadarrServerResponse>(
              detailReq,
              "Failed to load Radarr server details",
            ).getOrNull()
          if (detailRes != null) {
            server.copy(
              profiles = detailRes.profiles,
              rootFolders = detailRes.rootFolders,
            )
          } else {
            server
          }
        }
      }
    }

  suspend fun getSonarrServers(): Result<List<SeerrSonarrServer>> =
    withContext(Dispatchers.IO) {
      val req = buildRequest(path = "api/v1/service/sonarr")
      val res = executeCall<List<SeerrSonarrServer>>(req, "Failed to load Sonarr servers")
      res.map { servers ->
        servers.map { server ->
          val serverId = server.id ?: return@map server
          val detailReq = buildRequest(path = "api/v1/service/sonarr/$serverId")
          val detailRes =
            executeCall<SeerrSonarrServerResponse>(
              detailReq,
              "Failed to load Sonarr server details",
            ).getOrNull()
          if (detailRes != null) {
            server.copy(
              profiles = detailRes.profiles,
              rootFolders = detailRes.rootFolders,
            )
          } else {
            server
          }
        }
      }
    }

  suspend fun createRequest(
    mediaId: Int,
    mediaType: MediaType,
    seasons: List<Int>? = null,
    is4k: Boolean = false,
    serverId: Int? = null,
    profileId: Int? = null,
    rootFolder: String? = null,
  ): Result<JellyseerrRequest> =
    withContext(Dispatchers.IO) {
      val body =
        CreateRequestBody(
          mediaType = mediaType.value,
          mediaId = mediaId,
          seasons = seasons,
          is4k = is4k,
          serverId = serverId,
          profileId = profileId,
          rootFolder = rootFolder,
        )
      val payload = json.encodeToString(CreateRequestBody.serializer(), body)
      val req = buildRequest(path = "api/v1/request", method = "POST", bodyJson = payload)
      val res = executeCall<JellyseerrRequest>(req, "Failed to create media request")
      res.onSuccess { clearCacheForMedia(mediaType.value, mediaId) }
      res
    }

  suspend fun approveRequest(requestId: Int): Result<JellyseerrRequest> =
    withContext(Dispatchers.IO) {
      val req =
        buildRequest(
          path = "api/v1/request/$requestId/approve",
          method = "POST",
          bodyJson = "{}",
        )
      val res = executeCall<JellyseerrRequest>(req, "Failed to approve request $requestId")
      res.onSuccess { r ->
        val tmdbId = r.media.tmdbId ?: r.media.id
        if (tmdbId > 0) clearCacheForMedia(r.media.mediaType, tmdbId)
      }
      res
    }

  suspend fun declineRequest(requestId: Int): Result<JellyseerrRequest> =
    withContext(Dispatchers.IO) {
      val req =
        buildRequest(
          path = "api/v1/request/$requestId/decline",
          method = "POST",
          bodyJson = "{}",
        )
      val res = executeCall<JellyseerrRequest>(req, "Failed to decline request $requestId")
      res.onSuccess { r ->
        val tmdbId = r.media.tmdbId ?: r.media.id
        if (tmdbId > 0) clearCacheForMedia(r.media.mediaType, tmdbId)
      }
      res
    }

  fun clearCacheForMedia(
    mediaType: String,
    tmdbId: Int,
  ) {
    mediaDetailsCache.remove(mediaType.lowercase() to tmdbId)
  }

  suspend fun deleteMedia(mediaId: Int): Result<Unit> =
    withContext(Dispatchers.IO) {
      val req =
        buildRequest(
          path = "api/v1/media/$mediaId",
          method = "DELETE",
        )
      try {
        httpClient.newCall(req).awaitResponse().use { response ->
          if (response.isSuccessful) {
            Result.success(Unit)
          } else {
            Result.failure(Exception("Failed to delete media $mediaId (HTTP ${response.code})"))
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  suspend fun deleteRequest(requestId: Int): Result<Unit> =
    withContext(Dispatchers.IO) {
      val req =
        buildRequest(
          path = "api/v1/request/$requestId",
          method = "DELETE",
        )
      try {
        httpClient.newCall(req).awaitResponse().use { response ->
          if (response.isSuccessful) {
            Result.success(Unit)
          } else {
            Result.failure(Exception("Failed to delete request $requestId (HTTP ${response.code})"))
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
}
