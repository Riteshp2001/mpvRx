/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Spotify Canvas loops through the PaxSenix API, using the PaxSenix key the
 * user already configured for lyrics. The videos are streamed, not stored.
 */

package app.gyrolet.mpvrx.data.artwork

import android.util.Log
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.data.lyrics.providers.PaxSenixApi
import app.gyrolet.mpvrx.preferences.AudioPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class SpotifyCanvasRepository(private val preferences: AudioPreferences) : MotionArtworkProvider {
  private val mutex = Mutex()
  private data class Cached(val artwork: RemoteMotionArtwork?, val expiresAt: Long)
  private val cache = object : LinkedHashMap<MotionArtworkQuery, Cached>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MotionArtworkQuery, Cached>?): Boolean = size > 64
  }
  private var cachedForKey = 0

  val isConfigured: Boolean
    get() = !BuildConfig.IS_PLAY_STORE_BUILD && preferences.paxsenixApiKey.get().isNotBlank()

  override suspend fun find(query: MotionArtworkQuery): RemoteMotionArtwork? = withContext(Dispatchers.IO) {
    if (!isConfigured) return@withContext null
    mutex.withLock {
      // Lyrics only applies the key when it searches, so apply it here too.
      PaxSenixApi.setApiKey(preferences.paxsenixApiKey.get())
      val keyHash = PaxSenixApi.apiKey.hashCode()
      if (keyHash != cachedForKey) {
        cache.clear()
        cachedForKey = keyHash
      }
      val now = System.currentTimeMillis()
      cache[query]?.takeIf { it.expiresAt > now }?.let { return@withLock it.artwork }
      val result = withTimeoutOrNull(30_000L) { Lookup(lookup(query)) }
      val artwork = result?.artwork?.artwork
      val lifetime = when {
        result == null || result.artwork == null -> MINUTE
        artwork == null -> 30 * MINUTE
        else -> 6 * HOUR
      }
      cache[query] = Cached(artwork, System.currentTimeMillis() + lifetime)
      if (artwork == null) Log.d(TAG, "No Spotify Canvas available for this track")
      artwork
    }
  }

  private data class Lookup(val artwork: Answer?)

  /** Null means a request failed; an [Answer] with null artwork means the track has no Canvas. */
  private data class Answer(val artwork: RemoteMotionArtwork?)

  private suspend fun lookup(query: MotionArtworkQuery): Answer? {
    val term = "${MotionArtworkMatching.cleanTitle(query.title)} ${query.artist}"
    val tracks = PaxSenixApi.spotifyTracks(term) ?: return null
    val ranked = tracks.mapNotNull { track ->
      if (!MotionArtworkMatching.titleMatches(query, track.title)) return@mapNotNull null
      if (!MotionArtworkMatching.artistsMatch(query, track.artist, track.title)) return@mapNotNull null
      MotionArtworkMatching.score(query, track.album) to track
    }.sortedByDescending { it.first }.take(3)
    var failed = false
    for ((_, track) in ranked) {
      val response = PaxSenixApi.spotifyCanvas(track.id)
      if (response == null) {
        failed = true
        continue
      }
      canvasUrl(response)?.let { url ->
        return Answer(
          RemoteMotionArtwork(
            source = MotionArtworkSource.SPOTIFY_CANVAS,
            renditions = listOf(MotionArtworkRendition(url, hls = false)),
            portrait = true,
          ),
        )
      }
    }
    return if (failed) null else Answer(null)
  }

  companion object {
    private const val TAG = "SpotifyCanvas"
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Prefers a `canvasUrl` field, then any Spotify CDN MP4 in the response. */
    fun canvasUrl(root: JsonElement): String? =
      root.findStrings { key, _ -> key == "canvasUrl" }.firstOrNull(::isCanvasUrl)
        ?: root.findStrings { _, _ -> true }.firstOrNull(::isCanvasUrl)

    private fun isCanvasUrl(value: String): Boolean {
      val url = value.toHttpUrlOrNull() ?: return false
      return url.isHttps && url.encodedPath.endsWith(".mp4", ignoreCase = true) &&
        (url.host == "scdn.co" || url.host.endsWith(".scdn.co") || url.host.endsWith(".spotifycdn.com"))
    }

    private fun JsonElement.findStrings(accept: (String, String) -> Boolean): Sequence<String> = sequence {
      when (val element = this@findStrings) {
        is JsonObject -> for ((key, value) in element) {
          val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
          if (text != null && accept(key, text)) yield(text)
          if (value is JsonObject || value is JsonArray) yieldAll(value.findStrings(accept))
        }
        is JsonArray -> for (value in element) {
          val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
          if (text != null && accept("", text)) yield(text)
          if (value is JsonObject || value is JsonArray) yieldAll(value.findStrings(accept))
        }
        else -> Unit
      }
    }
  }
}
