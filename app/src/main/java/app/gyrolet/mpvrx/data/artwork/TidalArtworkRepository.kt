/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * TIDAL album covers through the public search endpoint TIDAL's embeddable player uses, the
 * way BitChord reads them (https://github.com/kushagrasinghx/BitChord, GPL-3.0). No account
 * or developer credentials are needed: a track search returns its album, whose `cover` and
 * `videoCover` ids expand into fixed 1280px URLs on TIDAL's CDN.
 */

package app.gyrolet.mpvrx.data.artwork

import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.utils.logging.Log
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
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
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class TidalArtworkRepository(client: OkHttpClient) : MotionArtworkProvider {
  private val http = client.newBuilder()
    .cookieJar(CookieJar.NO_COOKIES)
    .callTimeout(20, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()
  private val json = Json { ignoreUnknownKeys = true }
  private val mutex = Mutex()

  /** One matched album; either id may be absent. [albumMatched] means it is the tagged release. */
  private data class Album(val cover: String?, val videoCover: String?, val albumMatched: Boolean)
  private data class Cached(val albums: List<Album>, val expiresAt: Long)
  private val cache = object : LinkedHashMap<MotionArtworkQuery, Cached>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MotionArtworkQuery, Cached>?): Boolean = size > 64
  }
  private var retryAfter = 0L

  override suspend fun find(query: MotionArtworkQuery): RemoteMotionArtwork? =
    albums(query).firstNotNullOfOrNull { album -> album.videoCover?.let { videoUrl(it) } }
      ?.let { RemoteMotionArtwork(MotionArtworkSource.TIDAL, listOf(MotionArtworkRendition(it, hls = false))) }

  /**
   * The largest still cover TIDAL serves for [query], or null. It replaces the file's own
   * cover, so with an album tag only that release's cover qualifies.
   */
  suspend fun findStillCover(query: MotionArtworkQuery): String? {
    val albumTagged = !query.album.isNullOrBlank()
    return albums(query).filter { !albumTagged || it.albumMatched }
      .firstNotNullOfOrNull { album -> album.cover?.let { imageUrl(it) } }
  }

  /** Matching albums, best first; the search is shared by the still and video lookups. */
  private suspend fun albums(query: MotionArtworkQuery): List<Album> = withContext(Dispatchers.IO) {
    mutex.withLock {
      val now = System.currentTimeMillis()
      cache[query]?.takeIf { it.expiresAt > now }?.let { return@withLock it.albums }
      if (now < retryAfter) return@withLock emptyList()
      try {
        val albums = withTimeoutOrNull(30_000L) { search(query) }
        val lifetime = when {
          albums == null -> MINUTE
          albums.isEmpty() -> 30 * MINUTE
          else -> 6 * HOUR
        }
        cache[query] = Cached(albums.orEmpty(), System.currentTimeMillis() + lifetime)
        if (albums.isNullOrEmpty()) Log.d(TAG, "No matching TIDAL album for this track")
        albums.orEmpty()
      } catch (e: IOException) {
        Log.w(TAG, "TIDAL artwork lookup failed; retaining the existing cover: ${e.message}")
        cache[query] = Cached(emptyList(), System.currentTimeMillis() + MINUTE)
        emptyList()
      } catch (e: SerializationException) {
        Log.w(TAG, "Unexpected TIDAL response; retaining the existing cover", e)
        cache[query] = Cached(emptyList(), System.currentTimeMillis() + MINUTE)
        emptyList()
      }
    }
  }

  private suspend fun search(query: MotionArtworkQuery): List<Album> {
    val title = MotionArtworkMatching.cleanTitle(query.title)
    // The album is what makes the catalogue search land; a mistagged one must not hide the song.
    val terms = listOfNotNull(
      query.album?.takeIf { it.isNotBlank() }?.let { "$it ${query.artist} $title" },
      "${query.artist} $title",
    ).distinct()
    val countries = listOf(MotionArtworkMatching.storefront().uppercase(Locale.ROOT), "US").distinct()
    for (country in countries) {
      for (term in terms) {
        val albums = searchOnce(query, term, country) ?: break
        if (albums.isNotEmpty()) return albums
      }
    }
    return emptyList()
  }

  /** Null when [country] is not a TIDAL market, so the caller moves on to the next one. */
  private suspend fun searchOnce(query: MotionArtworkQuery, term: String, country: String): List<Album>? {
    val url = SEARCH.toHttpUrl().newBuilder()
      .addQueryParameter("query", term)
      .addQueryParameter("limit", "10")
      .addQueryParameter("types", "TRACKS")
      .addQueryParameter("countryCode", country)
      .build()
    val request = Request.Builder().url(url)
      .header("X-Tidal-Token", EMBED_TOKEN)
      .header("User-Agent", USER_AGENT)
      .build()
    val body = try {
      get(request)
    } catch (e: HttpFailure) {
      when (e.status) {
        400, 404 -> return if (country != "US") null else emptyList()
        // A rejected embed token will not recover by itself; stop asking for a while.
        401, 403 -> retryAfter = System.currentTimeMillis() + 30 * MINUTE
      }
      throw e
    }
    val root = json.parseToJsonElement(body) as? JsonObject ?: throw IOException("Unexpected TIDAL response")
    // The response has a section per result type even when one was asked for; name it.
    val items = (root.obj("tracks")?.get("items") as? JsonArray).orEmpty()
    return items.mapNotNull { element ->
      val track = element as? JsonObject ?: return@mapNotNull null
      val trackTitle = track.text("title") ?: return@mapNotNull null
      if (!MotionArtworkMatching.titleMatches(query, trackTitle)) return@mapNotNull null
      val artists = (track["artists"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.text("name") }
        .joinToString(", ")
      if (!MotionArtworkMatching.artistsMatch(query, artists, trackTitle)) return@mapNotNull null
      val album = track.obj("album") ?: return@mapNotNull null
      val albumTitle = album.text("title")
      val match = Album(
        cover = album.text("cover"),
        videoCover = album.text("videoCover"),
        albumMatched = MotionArtworkMatching.albumMatches(query, albumTitle),
      )
      MotionArtworkMatching.score(query, albumTitle) to match
    }.sortedByDescending { it.first }.map { it.second }.distinct()
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
    private const val SEARCH = "https://api.tidal.com/v1/search"

    /** The read-only token TIDAL's public embed player ships; no account involved. */
    private const val EMBED_TOKEN = "vNVdglQOjFJJGG2U"
    private const val USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val RESOURCE_ID = Regex("[0-9A-Za-z]+(?:-[0-9A-Za-z]+){4}")

    /** A resource id's five dash-separated segments spell out its path on the CDN. */
    private fun resourcePath(id: String): String? =
      id.takeIf { RESOURCE_ID.matches(it) }?.replace('-', '/')

    fun videoUrl(id: String): String? =
      resourcePath(id)?.let { "https://resources.tidal.com/videos/$it/1280x1280.mp4" }

    fun imageUrl(id: String): String? =
      resourcePath(id)?.let { "https://resources.tidal.com/images/$it/1280x1280.jpg" }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
  }
}
