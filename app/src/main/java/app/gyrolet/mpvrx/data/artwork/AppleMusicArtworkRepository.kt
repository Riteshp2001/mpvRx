/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Apple Music editorial-video discovery inspired by BitChord:
 * https://github.com/kushagrasinghx/BitChord (GPL-3.0).
 * This implementation does not download or persist the artwork videos.
 */

package app.gyrolet.mpvrx.data.artwork

import android.util.Log
import app.gyrolet.mpvrx.network.awaitResponse
import java.io.IOException
import java.util.Base64
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

internal class AppleMusicArtworkRepository(client: OkHttpClient) : MotionArtworkProvider {
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
  private var token: String? = null
  private var tokenExpiry = 0L
  private var nextTokenAttempt = 0L
  private val rejectedTokens = mutableSetOf<String>()

  override suspend fun find(query: MotionArtworkQuery): RemoteMotionArtwork? = withContext(Dispatchers.IO) {
    mutex.withLock {
      val now = System.currentTimeMillis()
      cache[query]?.takeIf { it.expiresAt > now }?.let { return@withLock it.artwork }
      try {
        val result = withTimeoutOrNull(30_000L) {
          val storefront = MotionArtworkMatching.storefront()
          Lookup(search(query, storefront) ?: if (storefront != "us") search(query, "us") else null)
        }
        val artwork = result?.artwork
        val lifetime = when {
          result == null -> MINUTE
          artwork == null -> 30 * MINUTE
          else -> 6 * HOUR
        }
        cache[query] = Cached(artwork, System.currentTimeMillis() + lifetime)
        if (artwork == null) Log.d(TAG, "No matching animated album artwork available")
        artwork
      } catch (e: IOException) {
        Log.w(TAG, "Animated artwork lookup failed; retaining the existing cover", e)
        val retryAt = System.currentTimeMillis()
        if (token == null && nextTokenAttempt <= retryAt) nextTokenAttempt = retryAt + MINUTE
        cache[query] = Cached(null, System.currentTimeMillis() + MINUTE)
        null
      } catch (e: SerializationException) {
        Log.w(TAG, "Unexpected animated artwork response; retaining the existing cover", e)
        cache[query] = Cached(null, System.currentTimeMillis() + MINUTE)
        null
      }
    }
  }

  private suspend fun search(query: MotionArtworkQuery, storefront: String): RemoteMotionArtwork? {
    val term = "${query.artist} ${MotionArtworkMatching.cleanTitle(query.title)}"
    val url = "$CATALOG/$storefront/search".toHttpUrl().newBuilder()
      .addQueryParameter("term", term)
      .addQueryParameter("types", "songs")
      .addQueryParameter("limit", "10")
      .addQueryParameter("include", "albums")
      .addQueryParameter("extend", "editorialVideo")
      .build().toString()
    val root = try {
      catalog(url)
    } catch (e: HttpFailure) {
      // Device regions and catalog storefronts are not interchangeable (for example ZZ).
      if (storefront != "us" && (e.status == 400 || e.status == 404)) return null
      throw e
    }
    val songs = root.obj("results")?.obj("songs")?.get("data") as? JsonArray ?: return null
    val visited = mutableSetOf<String>()
    val ranked = songs.mapNotNull { element ->
      val song = element as? JsonObject ?: return@mapNotNull null
      val attributes = song.obj("attributes") ?: return@mapNotNull null
      matchScore(query, attributes)?.let { it to song }
    }.sortedByDescending { it.first }
    for ((_, song) in ranked) {
      val attributes = song.obj("attributes") ?: continue
      motionArtwork(attributes)?.let { return it }
      for (id in albumIds(song)) {
        if (!visited.add(id)) continue
        if (visited.size > 4) return null
        val albumUrl = "$CATALOG/$storefront/albums/$id?extend=editorialVideo"
        val data = try {
          catalog(albumUrl)["data"] as? JsonArray
        } catch (e: HttpFailure) {
          if (e.status != 404) throw e
          null
        } ?: continue
        val albumAttributes = (data.firstOrNull() as? JsonObject)?.obj("attributes") ?: continue
        if ((albumAttributes["isCompilation"] as? JsonPrimitive)?.booleanOrNull == true) continue
        motionArtwork(albumAttributes)?.let { return it }
      }
    }
    return null
  }

  private suspend fun catalog(url: String): JsonObject {
    repeat(2) {
      val bearer = anonymousToken()
      val request = Request.Builder().url(url)
        .header("Authorization", "Bearer $bearer")
        .header("Origin", "https://music.apple.com")
        .header("Referer", "https://music.apple.com/")
        .header("User-Agent", USER_AGENT)
        .build()
      try {
        return json.parseToJsonElement(get(request, 2 * 1024 * 1024)) as? JsonObject
          ?: throw IOException("Unexpected artwork catalog response")
      } catch (e: HttpFailure) {
        if (e.status != 401) throw e
        rejectedTokens.add(bearer)
        if (rejectedTokens.size > 8) rejectedTokens.remove(rejectedTokens.first())
        token = null
        nextTokenAttempt = 0L
        Log.w(TAG, "Anonymous artwork catalog authorization expired; refreshing")
      }
    }
    throw IOException("Anonymous artwork catalog authorization unavailable")
  }

  private suspend fun anonymousToken(): String {
    val now = System.currentTimeMillis()
    token?.takeIf { tokenExpiry > now + MINUTE }?.let { return it }
    if (now < nextTokenAttempt) throw IOException("Artwork catalog authorization is backing off")
    token = null
    tokenExpiry = 0L
    // Apple's current HTML alone exceeds 2 MiB, before the entry bundle is fetched.
    val page = "https://music.apple.com/us/new"
    val html = get(Request.Builder().url(page).header("User-Agent", USER_AGENT).build(), WEB_RESPONSE_LIMIT)
    findToken(html, now)?.let { return it }
    val scripts = Jsoup.parse(html, page).select("script[src], link[rel=modulepreload][href]")
      .mapNotNull { element ->
        element.absUrl(if (element.hasAttr("src")) "src" else "href").toHttpUrlOrNull()
      }
      .filter { it.isHttps && it.host == "music.apple.com" && it.encodedPath.startsWith("/assets/") && it.encodedPath.endsWith(".js") }
      .distinct()
      .sortedBy { if (it.encodedPath.substringAfterLast('/').startsWith("index")) 0 else 1 }
      .take(4)
    for (url in scripts) {
      val script = try {
        get(Request.Builder().url(url).header("User-Agent", USER_AGENT).build(), WEB_RESPONSE_LIMIT)
      } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        continue
      }
      findToken(script, now)?.let { return it }
    }
    nextTokenAttempt = System.currentTimeMillis() + MINUTE
    throw IOException("No usable anonymous artwork catalog authorization")
  }

  private fun findToken(source: String, now: Long): String? {
    for (match in JWT.findAll(source)) {
      val candidate = match.value
      if (candidate in rejectedTokens) continue
      val payload = tokenPart(candidate, 1) ?: continue
      if (payload.text("iss") != "AMPWebPlay" && tokenPart(candidate, 0)?.text("kid") != "WebPlayKid") continue
      val expires = (payload["exp"] as? JsonPrimitive)?.longOrNull?.times(1000L) ?: continue
      if (expires <= now + MINUTE) continue
      token = candidate
      tokenExpiry = expires
      return candidate
    }
    return null
  }

  private fun tokenPart(value: String, index: Int): JsonObject? =
    try {
      val payload = Base64.getUrlDecoder().decode(value.split('.')[index]).toString(Charsets.UTF_8)
      json.parseToJsonElement(payload) as? JsonObject
    } catch (e: SerializationException) {
      Log.d(TAG, "Ignoring malformed anonymous catalog authorization payload")
      null
    } catch (e: IllegalArgumentException) {
      Log.d(TAG, "Ignoring malformed anonymous catalog authorization")
      null
    }

  private suspend fun get(request: Request, maxBytes: Int): String =
    http.newCall(request).awaitResponse().use { response ->
      currentCoroutineContext().ensureActive()
      if (!response.isSuccessful) throw HttpFailure(response.code)
      val body = response.body
      val source = body.source()
      source.request(maxBytes.toLong() + 1)
      if (source.buffer.size > maxBytes) throw IOException("Artwork response exceeds size limit")
      currentCoroutineContext().ensureActive()
      source.readUtf8()
    }

  private class HttpFailure(val status: Int) : IOException("Artwork provider HTTP $status")

  companion object {
    private const val TAG = "AppleMusicArtwork"
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val WEB_RESPONSE_LIMIT = 8 * 1024 * 1024
    private const val CATALOG = "https://amp-api.music.apple.com/v1/catalog"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
    private val JWT = Regex("""ey[A-Za-z0-9_-]+\.ey[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

    private fun matchScore(query: MotionArtworkQuery, attributes: JsonObject): Int? {
      val title = attributes.text("name") ?: return null
      val artist = attributes.text("artistName") ?: return null
      if (!MotionArtworkMatching.titleMatches(query, title)) return null
      if (!MotionArtworkMatching.artistsMatch(query, artist, title)) return null
      return MotionArtworkMatching.score(query, attributes.text("albumName"))
    }

    private fun albumIds(song: JsonObject): List<String> {
      val albums = song.obj("relationships")?.obj("albums")?.get("data") as? JsonArray
      val ids = albums.orEmpty().mapNotNull { (it as? JsonObject)?.text("id") }.toMutableList()
      // Search currently omits relationships even with include=albums. The web URL still
      // identifies the actual album; the ?i= value identifies the song and must not be used.
      val url = song.obj("attributes")?.text("url")?.toHttpUrlOrNull()
      if (url?.host == "music.apple.com" && "album" in url.pathSegments) {
        url.pathSegments.lastOrNull()?.let(ids::add)
      }
      return ids.filter { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }.distinct()
    }

    private fun motionArtwork(attributes: JsonObject): RemoteMotionArtwork? {
      val video = attributes.obj("editorialVideo") ?: return null
      val renditions = listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailRaw", "motionDetailTall", "motionTallVideo3x4")
        .mapNotNull { key ->
          val asset = video.obj(key) ?: return@mapNotNull null
          sequenceOf("video", "videoUrl", "hlsUrl", "url").mapNotNull { asset.text(it) }
            .firstOrNull { value ->
              val url = value.toHttpUrlOrNull() ?: return@firstOrNull false
              url.isHttps && (url.host.endsWith(".apple.com") || url.host.endsWith(".mzstatic.com")) &&
                (url.encodedPath.endsWith(".m3u8", ignoreCase = true) || url.encodedPath.endsWith(".mp4", ignoreCase = true))
            }
        }.distinct()
        .map { MotionArtworkRendition(it, hls = !it.substringBefore('?').endsWith(".mp4", ignoreCase = true)) }
      return renditions.takeIf { it.isNotEmpty() }?.let { RemoteMotionArtwork(MotionArtworkSource.APPLE_MUSIC, it) }
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
  }
}
