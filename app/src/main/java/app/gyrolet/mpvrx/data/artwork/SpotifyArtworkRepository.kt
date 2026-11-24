/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's SpotifyCanvas
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.artwork

import android.util.Log
import app.gyrolet.mpvrx.network.awaitResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Spotify's own Canvas: the portrait loop Spotify shows behind a track. There is no public API,
 * so the track is found with the web player's search and its canvas read with the `canvas`
 * GraphQL query ([SpotifyCanvasQuery]), falling back to the older `canvaz-cache` protobuf
 * endpoint. Both need the listener's own session ([SpotifyToken]); without the cookie this is a
 * free no-op.
 */
internal class SpotifyArtworkRepository(
  client: OkHttpClient,
  private val token: SpotifyToken,
) : MotionArtworkProvider {
  private val http = client.newBuilder()
    .cookieJar(CookieJar.NO_COOKIES)
    .callTimeout(20, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val mutex = Mutex()
  private data class Cached(val artwork: RemoteMotionArtwork?, val expiresAt: Long)
  private val cache = object : LinkedHashMap<MotionArtworkQuery, Cached>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MotionArtworkQuery, Cached>?): Boolean = size > 64
  }
  private val queryHashes = SpotifyCanvasQuery.QueryHashes(
    fetch = { url -> runCatching { get(url, mapOf("User-Agent" to SpotifyToken.WEB_USER_AGENT)) }.getOrNull() },
  )

  override suspend fun find(query: MotionArtworkQuery): RemoteMotionArtwork? = withContext(Dispatchers.IO) {
    if (!token.hasCookie) return@withContext null
    mutex.withLock {
      val now = System.currentTimeMillis()
      cache[query]?.takeIf { it.expiresAt > now }?.let { return@withLock it.artwork }
      val result = try {
        withTimeoutOrNull(45_000L) { lookUp(query) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        Log.w(TAG, "Spotify Canvas lookup failed; retaining the existing cover: ${e.message}")
        null
      }
      val artwork = result?.artwork
      val lifetime = when {
        result == null -> MINUTE
        artwork == null -> 30 * MINUTE
        else -> 6 * HOUR
      }
      cache[query] = Cached(artwork, System.currentTimeMillis() + lifetime)
      artwork
    }
  }

  /** A settled answer; null from [lookUp] means "try again soon" (no token yet, transport failure). */
  private data class Lookup(val artwork: RemoteMotionArtwork?)

  private suspend fun lookUp(query: MotionArtworkQuery): Lookup? {
    val bearer = token.accessToken() ?: return null
    val uri = searchViaPathfinder(query, bearer) ?: searchViaRest(query, bearer) ?: return Lookup(null)
    val url = canvasUrl(uri, bearer) ?: return Lookup(null)
    val rendition = MotionArtworkRendition(url, hls = false)
    return Lookup(RemoteMotionArtwork(MotionArtworkSource.SPOTIFY, listOf(rendition), portrait = listOf(rendition)))
  }

  private fun searchTerm(query: MotionArtworkQuery): String =
    listOfNotNull(MotionArtworkMatching.cleanTitle(query.title), query.artist, query.album?.takeIf { it.isNotBlank() })
      .joinToString(" ")

  private suspend fun searchViaPathfinder(query: MotionArtworkQuery, bearer: String): String? {
    val clientToken = token.clientToken() ?: return null
    val variables = buildJsonObject {
      put("searchTerm", searchTerm(query))
      put("offset", 0)
      put("limit", 10)
      put("numberOfTopResults", 5)
      put("includeAudiobooks", false)
      put("includePreReleases", false)
    }.toString()
    val extensions = buildJsonObject {
      putJsonObject("persistedQuery") {
        put("version", 1)
        put("sha256Hash", PATHFINDER_SEARCH_HASH)
      }
    }.toString()
    val url = PATHFINDER_URL.toHttpUrl().newBuilder()
      .addQueryParameter("operationName", "searchTracks")
      .addQueryParameter("variables", variables)
      .addQueryParameter("extensions", extensions)
      .build().toString()
    val body = getOrNull(
      url,
      mapOf(
        "Authorization" to "Bearer $bearer",
        "Client-Token" to clientToken,
        "App-platform" to "WebPlayer",
        "Accept" to "application/json",
        "User-Agent" to SpotifyToken.WEB_USER_AGENT,
      ),
    ) ?: return null
    val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val items = root.obj("data")?.obj("searchV2")?.obj("tracksV2")?.get("items") as? JsonArray ?: return null
    for (element in items) {
      val data = (element as? JsonObject)?.obj("item")?.obj("data") ?: continue
      val uri = data.text("uri") ?: data.text("id")?.let { "spotify:track:$it" } ?: continue
      // The response shape is undocumented: check the title and artists when present.
      val name = data.text("name")
      if (name != null && !MotionArtworkMatching.titleMatches(query, name)) continue
      val artists = (data.obj("artists")?.get("items") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.obj("profile")?.text("name") }
      if (name != null && artists.isNotEmpty() &&
        !MotionArtworkMatching.artistsMatch(query, artists.joinToString(", "), name)
      ) continue
      return uri
    }
    return null
  }

  private suspend fun searchViaRest(query: MotionArtworkQuery, bearer: String): String? {
    val url = SEARCH_URL.toHttpUrl().newBuilder()
      .addQueryParameter("q", searchTerm(query))
      .addQueryParameter("type", "track")
      .addQueryParameter("limit", "10")
      .build().toString()
    val body = getOrNull(url, authHeaders(bearer)) ?: return null
    val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val items = root.obj("tracks")?.get("items") as? JsonArray ?: return null
    return items.firstNotNullOfOrNull { element ->
      val track = element as? JsonObject ?: return@firstNotNullOfOrNull null
      val name = track.text("name") ?: return@firstNotNullOfOrNull null
      val artists = (track["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") }
      if (!MotionArtworkMatching.titleMatches(query, name)) return@firstNotNullOfOrNull null
      if (!MotionArtworkMatching.artistsMatch(query, artists.joinToString(", "), name)) return@firstNotNullOfOrNull null
      track.text("uri")
    }
  }

  /** Pathfinder first; canvaz-cache when it fails or answers without a playable video. */
  private suspend fun canvasUrl(trackUri: String, bearer: String): String? =
    when (val answer = canvasViaPathfinder(trackUri, bearer)) {
      is SpotifyCanvasQuery.Answer.Found -> answer.url
      is SpotifyCanvasQuery.Answer.NoCanvas, is SpotifyCanvasQuery.Answer.Failed -> canvasViaCanvaz(trackUri, bearer)
    }

  private suspend fun canvasViaPathfinder(trackUri: String, bearer: String, isRetry: Boolean = false): SpotifyCanvasQuery.Answer {
    val hash = queryHashes.canvasHash(forceRefresh = isRetry)
    val request = Request.Builder()
      .url(SpotifyCanvasQuery.ENDPOINT)
      .post(SpotifyCanvasQuery.requestBody(trackUri, hash).toByteArray(Charsets.UTF_8).toRequestBody(JSON_TYPE))
      .apply { authHeaders(bearer).forEach { (name, value) -> header(name, value) } }
      .header("App-platform", "WebPlayer")
      .header("Accept", "application/json")
      .header("Accept-Language", "en")
      .build()
    val body = try {
      http.newCall(request).awaitResponse().use { response ->
        if (response.code == 401) token.invalidate()
        if (response.isSuccessful) response.body.string() else null
      }
    } catch (e: IOException) {
      return SpotifyCanvasQuery.Answer.Failed("request failed: ${e.message}")
    } ?: return SpotifyCanvasQuery.Answer.Failed("http error")
    val answer = SpotifyCanvasQuery.parse(body)
    // A rebuilt web player retires the old hash: look it up again once.
    if (answer is SpotifyCanvasQuery.Answer.Failed && answer.staleHash && !isRetry) {
      return canvasViaPathfinder(trackUri, bearer, isRetry = true)
    }
    return answer
  }

  private suspend fun canvasViaCanvaz(trackUri: String, bearer: String): String? {
    val request = Request.Builder()
      .url(CANVAS_URL)
      .post(Protobuf.canvasRequest(trackUri).toRequestBody(PROTOBUF_TYPE))
      .apply { authHeaders(bearer).forEach { (name, value) -> header(name, value) } }
      .header("Accept", "application/protobuf")
      .header("Accept-Language", "en")
      // spclient serves this path to Spotify's own apps; the web player's UA is refused.
      .header("User-Agent", SPOTIFY_APP_USER_AGENT)
      .build()
    val bytes = try {
      http.newCall(request).awaitResponse().use { response ->
        if (response.isSuccessful) response.body.bytes() else null
      }
    } catch (e: IOException) {
      null
    } ?: return null
    val hits = Protobuf.canvasResponse(bytes)
    hits.firstOrNull { it.second == trackUri }?.first?.let { return it }
    hits.firstOrNull()?.first?.let { return it }
    // A stale field number must not hide a clip whose URL is plainly in the bytes.
    return CANVAS_URL_REGEX.find(String(bytes, Charsets.ISO_8859_1))?.value
  }

  private suspend fun authHeaders(bearer: String): Map<String, String> = buildMap {
    put("Authorization", "Bearer $bearer")
    put("User-Agent", SpotifyToken.WEB_USER_AGENT)
    token.clientToken()?.let { put("Client-Token", it) }
  }

  private suspend fun getOrNull(url: String, headers: Map<String, String>): String? =
    try {
      get(url, headers)
    } catch (e: IOException) {
      Log.d(TAG, "Spotify request failed: ${e.message}")
      null
    }

  private suspend fun get(url: String, headers: Map<String, String>): String? {
    val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
    return http.newCall(request).awaitResponse().use { response ->
      if (response.code == 401) token.invalidate()
      if (!response.isSuccessful) return@use null
      val source = response.body.source()
      source.request(RESPONSE_LIMIT + 1L)
      if (source.buffer.size > RESPONSE_LIMIT) throw IOException("Spotify response exceeds size limit")
      source.readUtf8()
    }
  }

  /**
   * The two tiny canvaz-cache messages, hand-rolled rather than generated:
   * `CanvasRequest { repeated Track tracks = 1; }`, `Track { string track_uri = 1; }`,
   * `CanvasResponse { repeated Canvas canvases = 1; }`,
   * `Canvas { string id = 1; string canvas_url = 2; string track_uri = 5; }`.
   */
  private object Protobuf {
    fun canvasRequest(trackUri: String): ByteArray {
      val track = ByteArrayOutputStream().apply { writeBytesField(1, trackUri.toByteArray(Charsets.UTF_8)) }.toByteArray()
      return ByteArrayOutputStream().apply { writeBytesField(1, track) }.toByteArray()
    }

    /** (canvas url, track uri) pairs. */
    fun canvasResponse(bytes: ByteArray): List<Pair<String, String?>> = runCatching {
      buildList {
        Reader(bytes).forEachField { field, value ->
          if (field == 1 && value is ByteArray) canvas(value)?.let { add(it) }
        }
      }
    }.getOrDefault(emptyList())

    private fun canvas(bytes: ByteArray): Pair<String, String?>? {
      var url: String? = null
      var trackUri: String? = null
      Reader(bytes).forEachField { field, value ->
        if (value !is ByteArray) return@forEachField
        when (field) {
          2 -> url = String(value, Charsets.UTF_8)
          5 -> trackUri = String(value, Charsets.UTF_8)
        }
      }
      return url?.let { it to trackUri }
    }

    private fun ByteArrayOutputStream.writeBytesField(field: Int, value: ByteArray) {
      writeVarint((field shl 3 or 2).toLong())
      writeVarint(value.size.toLong())
      write(value)
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
      var remaining = value
      while (remaining and 0x7FL.inv() != 0L) {
        write(((remaining and 0x7F) or 0x80).toInt())
        remaining = remaining ushr 7
      }
      write(remaining.toInt())
    }

    private class Reader(private val bytes: ByteArray) {
      private var position = 0

      /** Varint fields arrive as Long, length-delimited ones as ByteArray; others are skipped. */
      fun forEachField(block: (field: Int, value: Any) -> Unit) {
        while (position < bytes.size) {
          val tag = varint()
          val field = (tag ushr 3).toInt()
          when ((tag and 7).toInt()) {
            0 -> block(field, varint())
            1 -> skip(8)
            2 -> {
              val length = varint().toInt()
              require(length >= 0 && position + length <= bytes.size) { "Truncated protobuf field" }
              block(field, bytes.copyOfRange(position, position + length))
              position += length
            }
            5 -> skip(4)
            else -> throw IllegalArgumentException("Unsupported protobuf wire type")
          }
        }
      }

      private fun skip(count: Int) {
        require(position + count <= bytes.size) { "Truncated protobuf field" }
        position += count
      }

      private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
          require(position < bytes.size) { "Truncated protobuf varint" }
          val byte = bytes[position++].toInt()
          result = result or ((byte and 0x7F).toLong() shl shift)
          if (byte and 0x80 == 0) return result
          shift += 7
        }
        throw IllegalArgumentException("Malformed protobuf varint")
      }
    }
  }

  companion object {
    private const val TAG = "SpotifyCanvas"
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val RESPONSE_LIMIT = 2 * 1024 * 1024
    private const val SEARCH_URL = "https://api.spotify.com/v1/search"
    private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
    private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"

    /** The persisted query id for the web player's own track search. */
    private const val PATHFINDER_SEARCH_HASH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
    private const val SPOTIFY_APP_USER_AGENT = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
    private val JSON_TYPE = "application/json".toMediaType()
    private val PROTOBUF_TYPE = "application/protobuf".toMediaType()
    private val CANVAS_URL_REGEX = Regex("""https://[^"'\s\x00-\x1F]+\.cnvs\.mp4""")

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
  }
}
