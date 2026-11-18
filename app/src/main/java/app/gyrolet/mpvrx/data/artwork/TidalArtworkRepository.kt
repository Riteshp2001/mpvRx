/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * TIDAL video album covers through the official TIDAL API
 * (https://developer.tidal.com), authenticated with the client credentials
 * the user registers there. The videos are streamed, not stored.
 */

package app.gyrolet.mpvrx.data.artwork

import android.util.Log
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.preferences.AudioPreferences
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal class TidalArtworkRepository(
  client: OkHttpClient,
  private val preferences: AudioPreferences,
) : MotionArtworkProvider {
  private val http = client.newBuilder()
    .cookieJar(CookieJar.NO_COOKIES)
    .callTimeout(20, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()
  private val json = Json { ignoreUnknownKeys = true }
  private val mutex = Mutex()
  private data class Cached(val artwork: RemoteMotionArtwork?, val expiresAt: Long)
  private data class Lookup(val artwork: RemoteMotionArtwork?)
  private val cache = object : LinkedHashMap<MotionArtworkQuery, Cached>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MotionArtworkQuery, Cached>?): Boolean = size > 64
  }
  private var credentialsHash = 0
  private var token: String? = null
  private var tokenExpiry = 0L
  private var nextTokenAttempt = 0L

  val isConfigured: Boolean
    get() = preferences.tidalClientId.get().isNotBlank() && preferences.tidalClientSecret.get().isNotBlank()

  override suspend fun find(query: MotionArtworkQuery): RemoteMotionArtwork? = withContext(Dispatchers.IO) {
    val clientId = preferences.tidalClientId.get().trim()
    val clientSecret = preferences.tidalClientSecret.get().trim()
    if (clientId.isEmpty() || clientSecret.isEmpty()) return@withContext null
    mutex.withLock {
      val hash = (clientId to clientSecret).hashCode()
      if (hash != credentialsHash) {
        credentialsHash = hash
        cache.clear()
        token = null
        tokenExpiry = 0L
        nextTokenAttempt = 0L
      }
      val now = System.currentTimeMillis()
      cache[query]?.takeIf { it.expiresAt > now }?.let { return@withLock it.artwork }
      try {
        val result = withTimeoutOrNull(30_000L) {
          val country = MotionArtworkMatching.storefront().uppercase(Locale.ROOT)
          Lookup(search(query, country, clientId, clientSecret) ?: if (country != "US") search(query, "US", clientId, clientSecret) else null)
        }
        val artwork = result?.artwork
        val lifetime = when {
          result == null -> MINUTE
          artwork == null -> 30 * MINUTE
          else -> 6 * HOUR
        }
        cache[query] = Cached(artwork, System.currentTimeMillis() + lifetime)
        if (artwork == null) Log.d(TAG, "No TIDAL video cover available for this track")
        artwork
      } catch (e: IOException) {
        Log.w(TAG, "TIDAL video cover lookup failed; retaining the existing cover: ${e.message}")
        cache[query] = Cached(null, System.currentTimeMillis() + MINUTE)
        null
      } catch (e: SerializationException) {
        Log.w(TAG, "Unexpected TIDAL response; retaining the existing cover", e)
        cache[query] = Cached(null, System.currentTimeMillis() + MINUTE)
        null
      }
    }
  }

  private suspend fun search(query: MotionArtworkQuery, country: String, clientId: String, clientSecret: String): RemoteMotionArtwork? {
    val term = "${query.artist} ${MotionArtworkMatching.cleanTitle(query.title)}"
    val searchUrl = api().addPathSegment("searchResults").addPathSegment(term)
      .addPathSegment("relationships").addPathSegment("tracks")
      .addQueryParameter("countryCode", country)
      .addQueryParameter("explicitFilter", "INCLUDE")
      .addQueryParameter("include", "tracks")
      .build()
    val root = try {
      request(searchUrl, clientId, clientSecret)
    } catch (e: HttpFailure) {
      // The device region may not be a TIDAL market; the caller retries with US.
      if (country != "US" && (e.status == 400 || e.status == 404)) return null
      throw e
    }
    val candidates = root.included("tracks").filter { track ->
      val title = track.obj("attributes")?.text("title") ?: return@filter false
      MotionArtworkMatching.titleMatches(query, title)
    }.take(4)
    val visitedAlbums = mutableSetOf<String>()
    val ranked = candidates.mapNotNull { track ->
      val id = track.text("id") ?: return@mapNotNull null
      val trackUrl = api().addPathSegment("tracks").addPathSegment(id)
        .addQueryParameter("countryCode", country)
        .addQueryParameter("include", "albums")
        .addQueryParameter("include", "artists")
        .build()
      val details = try {
        request(trackUrl, clientId, clientSecret)
      } catch (e: HttpFailure) {
        if (e.status == 404) return@mapNotNull null
        throw e
      }
      val title = details.obj("data")?.obj("attributes")?.text("title") ?: return@mapNotNull null
      val artist = details.included("artists").mapNotNull { it.obj("attributes")?.text("name") }.joinToString(", ")
      if (!MotionArtworkMatching.artistsMatch(query, artist, title)) return@mapNotNull null
      val albums = details.included("albums").filter {
        !it.obj("attributes")?.text("albumType").equals("COMPILATION", ignoreCase = true)
      }
      albums.mapNotNull { album ->
        val albumId = album.text("id") ?: return@mapNotNull null
        MotionArtworkMatching.score(query, album.obj("attributes")?.text("title")) to albumId
      }.maxByOrNull { it.first }
    }.sortedByDescending { it.first }
    for ((_, albumId) in ranked) {
      if (!visitedAlbums.add(albumId)) continue
      val coverUrl = api().addPathSegment("albums").addPathSegment(albumId)
        .addPathSegment("relationships").addPathSegment("coverArt")
        .addQueryParameter("countryCode", country)
        .addQueryParameter("include", "coverArt")
        .build()
      val cover = try {
        request(coverUrl, clientId, clientSecret)
      } catch (e: HttpFailure) {
        if (e.status == 404) continue
        throw e
      }
      videoCover(cover)?.let { return it }
    }
    return null
  }

  private suspend fun request(url: HttpUrl, clientId: String, clientSecret: String): JsonObject {
    repeat(2) { attempt ->
      val bearer = accessToken(clientId, clientSecret)
      val request = Request.Builder().url(url)
        .header("Authorization", "Bearer $bearer")
        .header("Accept", "application/vnd.api+json")
        .build()
      try {
        return json.parseToJsonElement(get(request)) as? JsonObject
          ?: throw IOException("Unexpected TIDAL response")
      } catch (e: HttpFailure) {
        if (e.status != 401 || attempt > 0) throw e
        token = null
        tokenExpiry = 0L
      }
    }
    throw IOException("TIDAL authorization unavailable")
  }

  private suspend fun accessToken(clientId: String, clientSecret: String): String {
    val now = System.currentTimeMillis()
    token?.takeIf { tokenExpiry > now + MINUTE }?.let { return it }
    if (now < nextTokenAttempt) throw IOException("TIDAL authorization is backing off")
    val request = Request.Builder().url(TOKEN_URL)
      .header("Authorization", Credentials.basic(clientId, clientSecret))
      .post(FormBody.Builder().add("grant_type", "client_credentials").build())
      .build()
    val body = try {
      get(request)
    } catch (e: HttpFailure) {
      // Rejected credentials will not fix themselves; avoid hammering the auth server.
      nextTokenAttempt = now + if (e.status == 400 || e.status == 401) 10 * MINUTE else MINUTE
      throw IOException("TIDAL rejected the client credentials (HTTP ${e.status})")
    }
    val root = json.parseToJsonElement(body) as? JsonObject ?: throw IOException("Unexpected TIDAL token response")
    val access = root.text("access_token")?.takeIf(String::isNotBlank) ?: throw IOException("TIDAL token response had no token")
    val expiresIn = (root["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L
    token = access
    tokenExpiry = now + expiresIn * 1000L
    return access
  }

  private suspend fun get(request: Request): String =
    http.newCall(request).awaitResponse().use { response ->
      currentCoroutineContext().ensureActive()
      if (!response.isSuccessful) throw HttpFailure(response.code)
      val source = response.body.source()
      source.request(RESPONSE_LIMIT + 1L)
      if (source.buffer.size > RESPONSE_LIMIT) throw IOException("TIDAL response exceeds size limit")
      source.readUtf8()
    }

  private class HttpFailure(val status: Int) : IOException("TIDAL HTTP $status")

  companion object {
    private const val TAG = "TidalArtwork"
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val RESPONSE_LIMIT = 2 * 1024 * 1024
    private const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
    private const val API = "https://openapi.tidal.com/v2"
    private const val PREFERRED_SIZE = 720

    private fun api(): HttpUrl.Builder = API.toHttpUrl().newBuilder()

    /** Video renditions of an album's cover art, closest to the decode size first. */
    fun videoCover(root: JsonObject): RemoteMotionArtwork? {
      val renditions = root.included("artworks")
        .filter { it.obj("attributes")?.text("mediaType").equals("VIDEO", ignoreCase = true) }
        .flatMap { (it.obj("attributes")?.get("files") as? JsonArray).orEmpty() }
        .mapNotNull { element ->
          val file = element as? JsonObject ?: return@mapNotNull null
          val href = file.text("href") ?: return@mapNotNull null
          val url = href.toHttpUrlOrNull() ?: return@mapNotNull null
          if (!url.isHttps || !(url.host == "tidal.com" || url.host.endsWith(".tidal.com"))) return@mapNotNull null
          val width = (file.obj("meta")?.get("width") as? JsonPrimitive)?.intOrNull ?: PREFERRED_SIZE
          val hls = url.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
            file.text("type")?.contains("mpegurl", ignoreCase = true) == true
          width to MotionArtworkRendition(href, hls)
        }
        .sortedBy { abs(it.first - PREFERRED_SIZE) }
        .map { it.second }
        .distinctBy { it.url }
      return renditions.takeIf { it.isNotEmpty() }?.let { RemoteMotionArtwork(MotionArtworkSource.TIDAL, it) }
    }

    private fun JsonObject.included(type: String): List<JsonObject> =
      (get("included") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.text("type") == type }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
  }
}
