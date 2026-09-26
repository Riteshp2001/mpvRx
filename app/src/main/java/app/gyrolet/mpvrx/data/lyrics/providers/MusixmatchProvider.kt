/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's Musixmatch provider
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Word timing with a line-synced fallback, from Musixmatch's web API.
 *
 * The service signs every request with a key that rotates, and publishes that
 * key inside the JavaScript its own web client loads. Both the key and the
 * per-install user token are read the same way the web client reads them, and
 * an authorization failure refreshes both once before giving up.
 */
internal object MusixmatchProvider : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.MUSIXMATCH

  private const val BASE = "https://apic.musixmatch.com/ws/1.1"
  private const val APP_ID = "mobile-app-v1.0"
  private const val SEARCH_PAGE = "https://www.musixmatch.com/search"
  private const val BROWSER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

  /** Used only if the page serving the rotating key is briefly unavailable. */
  private const val FALLBACK_SIGNING_SECRET = "f09016176ba43a1cfd1031fbd6b3d26c"

  private val tokenMutex = Mutex()
  private val cachedSecret = AtomicReference<String?>(null)
  private val cachedToken = AtomicReference<String?>(null)
  private val processGuid = UUID.randomUUID().toString()

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    for (ref in query.candidates()) {
      val seconds = query.durationMs / 1000
      val track = bestTrack(ref, seconds) ?: continue

      // The subtitle endpoint is line-timed by design; the rich-sync tier is
      // what keeps tracks that have syllable data from being flattened.
      if (track.hasRichSync != 0) {
        val rich =
          fetchRichSync(track.trackId)
            ?.let(::parseRichSync)
            ?.takeIf { lyrics -> lyrics.synced?.any { !it.words.isNullOrEmpty() } == true }
        if (rich != null) return rich
      }

      val subtitle = if (track.hasSubtitles != 0) fetchSubtitle(track.trackId) else null
      val lrc = subtitle?.takeIf { it.isNotBlank() } ?: continue
      parseLrcText(lrc)?.let { return it }
    }
    return null
  }

  private suspend fun bestTrack(
    ref: TrackRef,
    seconds: Int,
  ): Track? = searchTrack(ref.title, ref.artist)?.maxByOrNull { score(it, ref.title, ref.artist, seconds) }

  private fun score(
    track: Track,
    title: String,
    artist: String,
    seconds: Int,
  ): Double {
    var score = 0.0
    val name = track.trackName.trim().lowercase(Locale.ROOT)
    val targetTitle = title.trim().lowercase(Locale.ROOT)
    score +=
      when {
        name == targetTitle -> 80.0
        name.contains(targetTitle) || targetTitle.contains(name) -> 40.0
        else -> 0.0
      }
    if (track.artistName
        .trim()
        .lowercase(Locale.ROOT)
        .contains(artist.trim().lowercase(Locale.ROOT))
    ) {
      score += 40.0
    }
    track.trackLength?.let { length ->
      val diff = abs(length - seconds)
      score +=
        when {
          diff <= 2 -> 30.0
          diff <= 5 -> 15.0
          diff <= 10 -> 5.0
          else -> -20.0
        }
    }
    return score
  }

  private suspend fun searchTrack(
    title: String,
    artist: String,
  ): List<Track>? {
    val response =
      signedGet { token ->
        "$BASE/track.search"
          .toHttpUrl()
          .newBuilder()
          .addQueryParameter("app_id", APP_ID)
          .addQueryParameter("format", "json")
          .addQueryParameter("q_track", title)
          .addQueryParameter("q_artist", artist)
          .addQueryParameter("f_has_lyrics", "1")
          .addQueryParameter("s_track_rating", "desc")
          .addQueryParameter("quorum_factor", "1")
          .addQueryParameter("page_size", "10")
          .addQueryParameter("page", "1")
          .addQueryParameter("usertoken", token)
          .build()
      } ?: return null
    val body =
      runCatching {
        lyricsJson.decodeFromString<Envelope<TrackSearchBody>>(response)
      }.getOrNull() ?: return null
    return body.message.body
      ?.trackList
      ?.map { it.track }
  }

  private suspend fun fetchSubtitle(trackId: Long): String? {
    val response =
      signedGet { token ->
        "$BASE/track.subtitle.get"
          .toHttpUrl()
          .newBuilder()
          .addQueryParameter("app_id", APP_ID)
          .addQueryParameter("format", "json")
          .addQueryParameter("track_id", trackId.toString())
          .addQueryParameter("subtitle_format", "mxm")
          .addQueryParameter("usertoken", token)
          .build()
      } ?: return null
    return runCatching {
      lyricsJson.decodeFromString<Envelope<SubtitleBody>>(response)
    }.getOrNull()?.message?.body?.subtitle?.subtitleBody
  }

  private suspend fun fetchRichSync(trackId: Long): String? {
    val response =
      signedGet { token ->
        "$BASE/track.richsync.get"
          .toHttpUrl()
          .newBuilder()
          .addQueryParameter("app_id", APP_ID)
          .addQueryParameter("format", "json")
          .addQueryParameter("track_id", trackId.toString())
          .addQueryParameter("usertoken", token)
          .build()
      } ?: return null
    return runCatching {
      lyricsJson.decodeFromString<Envelope<RichSyncBody>>(response)
    }.getOrNull()?.message?.body?.richsync?.richsyncBody
  }

  /**
   * Reads rich-sync offsets straight from their fragments. Source order is
   * retained because punctuation and syllable fragments can share a stamp.
   */
  private fun parseRichSync(body: String): Lyrics? {
    val entries =
      runCatching {
        lyricsJson.decodeFromString<List<RichSyncEntry>>(body)
      }.getOrNull() ?: return null

    val timed =
      entries.mapNotNull { entry ->
        val lineStart = secondsToMs(entry.startSeconds)
        val lineEnd = maxOf(lineStart, secondsToMs(entry.endSeconds))
        val words = ArrayList<SyncedWord>()
        val current = StringBuilder()
        var currentStart = lineStart
        var previousStart = lineStart

        fun flush() {
          val text = current.toString().trim()
          current.setLength(0)
          if (text.isNotEmpty()) words += SyncedWord(time = currentStart, word = text, startsNewWord = true)
        }

        entry.fragments.forEachIndexed { index, fragment ->
          val raw = fragment.text
          if (raw.isEmpty()) return@forEachIndexed
          val start = maxOf(lineStart, previousStart, secondsToMs(entry.startSeconds + fragment.offsetSeconds))
          val next =
            entry.fragments
              .getOrNull(index + 1)
              ?.let { secondsToMs(entry.startSeconds + it.offsetSeconds) }
              ?: lineEnd
          previousStart = start

          if (raw.first().isWhitespace()) flush()
          val content = raw.trim()
          if (content.isNotEmpty()) {
            if (current.isEmpty()) currentStart = start
            current.append(content)
          }
          if (raw.last().isWhitespace()) flush()
        }
        flush()

        val text = entry.text.trim().ifEmpty { words.joinToString(" ") { it.word } }
        if (text.isEmpty()) return@mapNotNull null
        val start = minOf(lineStart, words.firstOrNull()?.time ?: lineStart)
        TimedLine(
          line =
            SyncedLine(
              time = start,
              line = text,
              words = words.takeIf { it.isNotEmpty() },
            ),
          endMs = maxOf(lineEnd, start),
        )
      }
    return lyricsFromSynced(timed.withInstrumentalGaps())
  }

  private fun secondsToMs(seconds: Double): Int =
    (seconds * 1_000.0).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

  /** Signs and issues [buildUrl]; an auth failure refreshes both moving credentials once. */
  private suspend fun signedGet(buildUrl: (token: String) -> HttpUrl): String? {
    val secret = getSecret()
    val token = getToken(secret) ?: return null
    val first = apiGet(sign(buildUrl(token).toString(), secret))
    if (first != null && !looksUnauthorized(first)) return first

    cachedToken.set(null)
    cachedSecret.set(null)
    val freshSecret = getSecret()
    val freshToken = getToken(freshSecret) ?: return null
    return apiGet(sign(buildUrl(freshToken).toString(), freshSecret))
  }

  /** Musixmatch reports an expired token inside a successful HTTP response. */
  private fun looksUnauthorized(body: String): Boolean =
    runCatching { lyricsJson.decodeFromString<Envelope<kotlinx.serialization.json.JsonElement>>(body) }
      .getOrNull()
      ?.message
      ?.header
      ?.statusCode
      .let { it == 401 || it == 402 }

  private suspend fun getToken(secret: String): String? =
    cachedToken.get() ?: tokenMutex.withLock {
      cachedToken.get() ?: fetchToken(secret)?.also(cachedToken::set)
    }

  private suspend fun fetchToken(secret: String): String? {
    val url =
      "$BASE/token.get"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("app_id", APP_ID)
        .addQueryParameter("guid", processGuid)
        .addQueryParameter("format", "json")
        .build()
    val body = apiGet(sign(url.toString(), secret)) ?: return null
    return runCatching {
      lyricsJson.decodeFromString<Envelope<TokenBody>>(body)
    }.getOrNull()?.message?.body?.userToken
  }

  /** Reads the rotating signing key from the JavaScript linked by the web client. */
  private suspend fun getSecret(): String =
    cachedSecret.get() ?: runCatching {
      val page =
        browserGet(SEARCH_PAGE, "text/html,application/xhtml+xml")
          ?: error("search page unavailable")
      val script =
        APP_SCRIPT.find(page)?.groupValues?.get(1)
          ?: error("application script not found")
      val scriptUrl =
        SEARCH_PAGE.toHttpUrl().resolve(script)?.toString()
          ?: error("invalid application script URL")
      val javascript = browserGet(scriptUrl, "*/*") ?: error("application script unavailable")
      val encoded =
        ENCODED_SECRET.find(javascript)?.groupValues?.get(1)
          ?: error("signing key not found")
      String(Base64.getDecoder().decode(encoded.reversed()), Charsets.UTF_8)
        .takeIf { it.isNotBlank() }
        ?: error("empty signing key")
    }.getOrElse { FALLBACK_SIGNING_SECRET }.also(cachedSecret::set)

  /** Signs `<url><UTC yyyyMMdd>` with HMAC-SHA256, matching the web client. */
  private fun sign(
    url: String,
    secret: String,
  ): String {
    val normalized = url.replace("%20", "+").replace(" ", "+")
    val date =
      SimpleDateFormat("yyyyMMdd", Locale.US)
        .apply {
          timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val raw = mac.doFinal("$normalized$date".toByteArray(Charsets.UTF_8))
    val signature = Base64.getEncoder().encodeToString(raw)
    return "$normalized&signature=${java.net.URLEncoder.encode(signature, "UTF-8")}" +
      "&signature_protocol=sha256"
  }

  private suspend fun apiGet(url: String): String? = lyricsGet(url, browserHeaders("application/json, text/plain, */*"))

  private suspend fun browserGet(
    url: String,
    accept: String,
  ): String? = lyricsGet(url, browserHeaders(accept) + mapOf("Cookie" to "mxm_bab=AB"))

  private fun browserHeaders(accept: String): Map<String, String> =
    mapOf(
      "User-Agent" to BROWSER_AGENT,
      "Accept" to accept,
      "Accept-Language" to "en-US,en;q=0.9",
    )

  @Serializable
  private data class Envelope<T>(
    val message: Message<T>,
  )

  @Serializable
  private data class Message<T>(
    val header: Header,
    val body: T? = null,
  )

  @Serializable
  private data class Header(
    @SerialName("status_code") val statusCode: Int = 0,
  )

  @Serializable
  private data class TokenBody(
    @SerialName("user_token") val userToken: String,
  )

  @Serializable
  private data class TrackSearchBody(
    @SerialName("track_list") val trackList: List<TrackWrapper> = emptyList(),
  )

  @Serializable
  private data class TrackWrapper(
    val track: Track,
  )

  @Serializable
  private data class Track(
    @SerialName("track_id") val trackId: Long,
    @SerialName("track_name") val trackName: String,
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("track_length") val trackLength: Int? = null,
    @SerialName("has_subtitles") val hasSubtitles: Int? = null,
    @SerialName("has_richsync") val hasRichSync: Int? = null,
  )

  @Serializable
  private data class SubtitleBody(
    val subtitle: Subtitle? = null,
  )

  @Serializable
  private data class Subtitle(
    @SerialName("subtitle_body") val subtitleBody: String,
  )

  @Serializable
  private data class RichSyncBody(
    val richsync: RichSync? = null,
  )

  @Serializable
  private data class RichSync(
    @SerialName("richsync_body") val richsyncBody: String? = null,
  )

  @Serializable
  private data class RichSyncEntry(
    @SerialName("ts") val startSeconds: Double,
    @SerialName("te") val endSeconds: Double,
    @SerialName("l") val fragments: List<RichSyncFragment> = emptyList(),
    @SerialName("x") val text: String = "",
  )

  @Serializable
  private data class RichSyncFragment(
    @SerialName("c") val text: String,
    @SerialName("o") val offsetSeconds: Double,
  )

  private val APP_SCRIPT =
    Regex(
      """src=["']([^"']*/_next/static/chunks/pages/_app-[^"']+\.js)["']""",
      RegexOption.IGNORE_CASE,
    )
  private val ENCODED_SECRET = Regex("""from\(\s*["']([^"']+)["']\s*\.split""")
}
