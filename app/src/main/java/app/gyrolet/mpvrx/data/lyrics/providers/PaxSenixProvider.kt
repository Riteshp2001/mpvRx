/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's PaxSenix provider
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * PaxSenix: Apple Music lyrics through a public proxy, plus two authenticated
 * routes that need an API key of the user's own.
 *
 * The Apple route needs no key. It searches Apple's own catalogue for a track
 * id — with a token scraped from music.apple.com and cached for as long as it
 * keeps working — then asks the proxy for that track's TTML.
 */
internal object PaxSenixApi {
  private const val API = "https://api.paxsenix.org"
  private const val PUBLIC_PROXY = "https://lyrics.paxsenix.org"
  private const val APPLE_SEARCH = "https://amp-api.music.apple.com/v1/catalog/us/search"
  private const val MINIMUM_MATCH_SCORE = 10

  private val tokenMutex = Mutex()
  private val cachedAppleToken = AtomicReference<String?>(null)

  @Volatile
  var apiKey: String = ""
    private set

  fun setApiKey(value: String) {
    apiKey = normalizeApiKey(value)
  }

  /** Apple Music TTML through the keyless public proxy. */
  suspend fun appleLyrics(query: LyricsFetchQuery): Lyrics? {
    val id = searchPublicAppleTrackId(query) ?: return null
    val url = buildUrl("$PUBLIC_PROXY/apple-music/lyrics", "id" to id, "ttml" to "true") ?: return null
    val body = lyricsGet(url) ?: return null
    return parseResponse(body)
  }

  /** Spotify timings where a key is configured, otherwise the generic route. */
  suspend fun spotifyLyrics(query: LyricsFetchQuery): Lyrics? {
    if (apiKey.isBlank()) return null
    for (ref in query.candidates()) {
      searchTrackId("spotify/search", ref, query)?.let { return it }
    }
    return genericLyrics(query)
  }

  /** Musixmatch timings where a key is configured, otherwise the generic route. */
  suspend fun musixmatchLyrics(query: LyricsFetchQuery): Lyrics? {
    if (apiKey.isBlank()) return null
    for (ref in query.candidates()) {
      val url =
        apiUrl("lyrics/musixmatch")
          .addQueryParameter("t", ref.title)
          .addQueryParameter("a", ref.artist)
          .addQueryParameter("d", (query.durationMs / 1000).toString())
          .build()
      apiBody(url)?.let(::parseResponse)?.let { return it }
    }
    return genericLyrics(query)
  }

  /** The general route answers with candidates rather than a single document. */
  private suspend fun genericLyrics(query: LyricsFetchQuery): Lyrics? {
    if (apiKey.isBlank()) return null
    for (ref in query.candidates()) {
      val url = apiUrl("lyrics/lrcget").addQueryParameter("q", "${ref.title} ${ref.artist}").build()
      val body = apiBody(url) ?: continue
      parseLrcGet(body, query)?.let { return it }
    }
    return null
  }

  /**
   * The general endpoint returns search candidates, not lyric lines. Parsing
   * its array as one document concatenates every candidate and makes each
   * song's timestamps restart at zero, which reads as repeated lines that can
   * never stay in sync — so one recording is selected before its lyrics are
   * parsed.
   */
  private fun parseLrcGet(
    raw: String,
    query: LyricsFetchQuery,
  ): Lyrics? {
    val root = runCatching { lyricsJson.parseToJsonElement(raw) }.getOrNull() ?: return null
    val payload = (root as? JsonObject)?.get("lyrics")
    val documents = (payload as? JsonArray).orEmpty()
    if (documents.isEmpty()) return parseResponse(raw)

    return documents
      .mapNotNull { document ->
        val lyrics = parseResponse(document.toString()) ?: return@mapNotNull null
        val metadataScore = (document as? JsonObject)?.candidateScore(query) ?: 0
        Scored(lyrics, metadataScore, durationDistance(lyrics, query.durationMs))
      }.maxWithOrNull(
        compareBy<Scored> { it.metadataScore }
          .thenBy { -it.durationDistanceMs }
          .thenBy { candidate -> candidate.lyrics.synced?.count { it.line.isNotBlank() } ?: 0 },
      )?.lyrics
  }

  private fun durationDistance(
    lyrics: Lyrics,
    durationMs: Int,
  ): Long {
    if (durationMs <= 0) return 0
    val lastTimestamp =
      lyrics.synced?.maxOfOrNull { line ->
        maxOf(line.time, line.words?.maxOfOrNull { it.time } ?: 0)
      } ?: return Long.MAX_VALUE
    return abs(lastTimestamp.toLong() - durationMs)
  }

  private fun JsonObject.candidateScore(query: LyricsFetchQuery): Int {
    val details = this["attributes"] as? JsonObject ?: this
    val ref = query.primary
    val candidate =
      Candidate(
        id = firstString(ID_KEYS) ?: "",
        title = details.firstString(TITLE_KEYS).orEmpty(),
        artist = details.firstString(ARTIST_KEYS) ?: details.artistNames().orEmpty(),
        durationMs = details.firstLong(DURATION_KEYS).toDurationMs(),
      )
    return candidate.score(ref.title, ref.artist, query.durationMs.toLong())
  }

  private suspend fun searchPublicAppleTrackId(query: LyricsFetchQuery): String? {
    val token = appleToken() ?: return null
    for (ref in query.candidates()) {
      val url =
        buildUrl(
          APPLE_SEARCH,
          "term" to "${ref.title} ${ref.artist}",
          "types" to "songs",
          "limit" to "10",
          "l" to "en-US",
        ) ?: return null
      val root =
        lyricsGetAuthorized(url, token)
          ?.let { runCatching { lyricsJson.parseToJsonElement(it) }.getOrNull() }
          ?: continue
      bestCandidate(root, query)?.id?.let { return it }
    }
    return null
  }

  private suspend fun appleToken(): String? =
    cachedAppleToken.get() ?: tokenMutex.withLock {
      cachedAppleToken.get() ?: scrapeAppleToken()?.also(cachedAppleToken::set)
    }

  private suspend fun scrapeAppleToken(): String? {
    val page = lyricsGet("https://music.apple.com/us/new") ?: return null
    val scriptPath = APPLE_INDEX_SCRIPT.find(page)?.value ?: return null
    val script = lyricsGet("https://music.apple.com$scriptPath") ?: return null
    return APPLE_TOKEN.find(script)?.value
  }

  private suspend fun searchTrackId(
    path: String,
    ref: TrackRef,
    query: LyricsFetchQuery,
  ): Lyrics? {
    val url = apiUrl(path).addQueryParameter("q", "${ref.title} ${ref.artist}").build()
    val body = apiBody(url) ?: return null
    val root = runCatching { lyricsJson.parseToJsonElement(body) }.getOrNull() ?: return null
    val id = bestCandidate(root, query)?.id ?: return null
    val lyricsUrl = apiUrl("lyrics/spotify").addQueryParameter("id", id).build()
    return apiBody(lyricsUrl)?.let(::parseResponse)
  }

  private fun bestCandidate(
    root: JsonElement,
    query: LyricsFetchQuery,
  ): Candidate? {
    val candidates = buildList { root.collectCandidates(this) }
    val ref = query.primary
    return candidates
      .map { it to it.score(ref.title, ref.artist, query.durationMs.toLong()) }
      .maxByOrNull { it.second }
      ?.takeIf { it.second >= MINIMUM_MATCH_SCORE }
      ?.first
  }

  private fun parseResponse(raw: String): Lyrics? = parseTimedApple(raw) ?: parseProviderText(raw)

  /** Keeps word timestamps when the proxy answers with its structured payload. */
  private fun parseTimedApple(raw: String): Lyrics? {
    val root = runCatching { lyricsJson.parseToJsonElement(raw) }.getOrNull() ?: return null
    val content = root.findTimedContent() ?: return null
    val rows = content.mapNotNull { it as? JsonObject }
    val timed =
      rows.mapIndexedNotNull { index, row ->
        val start = row.long("timestamp")?.inMs() ?: return@mapIndexedNotNull null
        val wordRows = row["text"] as? JsonArray ?: return@mapIndexedNotNull null
        val texts = wordRows.mapNotNull { (it as? JsonObject)?.string("text") }
        if (texts.isEmpty()) return@mapIndexedNotNull null
        val nextLine = rows.getOrNull(index + 1)?.long("timestamp")?.inMs()
        val words =
          wordRows.mapNotNull { element ->
            val word = element as? JsonObject ?: return@mapNotNull null
            val text = word.string("text")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val wordStart = word.long("timestamp")?.inMs() ?: return@mapNotNull null
            SyncedWord(time = wordStart, word = text, startsNewWord = true)
          }
        val lineStart = minOf(start, words.firstOrNull()?.time ?: start)
        TimedLine(
          line =
            SyncedLine(
              time = lineStart,
              line = texts.joinToString(" ") { it.trim() },
              words = words.takeIf { it.size == texts.size },
            ),
          endMs = maxOf(nextLine ?: lineStart + 800, lineStart),
        )
      }
    return lyricsFromSynced(timed.withInstrumentalGaps())
  }

  private fun Long.inMs(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

  private fun JsonElement.findTimedContent(): JsonArray? =
    when (this) {
      is JsonObject -> {
        (this["content"] as? JsonArray)?.takeIf { array ->
          array.any { (it as? JsonObject)?.get("timestamp") != null }
        } ?: values.firstNotNullOfOrNull { it.findTimedContent() }
      }
      is JsonArray -> firstNotNullOfOrNull { it.findTimedContent() }
      else -> null
    }

  private fun JsonElement.collectCandidates(into: MutableList<Candidate>) {
    when (this) {
      is JsonArray -> forEach { it.collectCandidates(into) }
      is JsonObject -> {
        toCandidate()?.let(into::add)
        values.forEach { it.collectCandidates(into) }
      }
      else -> Unit
    }
  }

  private fun JsonObject.toCandidate(): Candidate? {
    val details = this["attributes"] as? JsonObject ?: this
    val id = firstString(ID_KEYS) ?: details.firstString(ID_KEYS) ?: return null
    val title = details.firstString(TITLE_KEYS) ?: return null
    val artist = details.firstString(ARTIST_KEYS) ?: details.artistNames().orEmpty()
    return Candidate(id, title, artist, details.firstLong(DURATION_KEYS).toDurationMs())
  }

  private fun JsonObject.artistNames(): String? =
    when (val artists = this["artists"] ?: this["artist"]) {
      is JsonPrimitive -> artists.contentOrNull
      is JsonObject -> artists.firstString(listOf("name", "artistName", "title"))
      is JsonArray ->
        artists
          .mapNotNull {
            when (it) {
              is JsonPrimitive -> it.contentOrNull
              is JsonObject -> it.firstString(listOf("name", "artistName", "title"))
              else -> null
            }
          }.joinToString(", ")
          .takeIf(String::isNotEmpty)
      else -> null
    }

  private fun JsonObject.firstString(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { string(it)?.trim()?.takeIf(String::isNotEmpty) }

  private fun JsonObject.firstLong(keys: List<String>): Long? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.longOrNull }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

  private fun Long?.toDurationMs(): Long =
    when {
      this == null || this <= 0 -> 0
      this < 10_000 -> this * 1000
      else -> this
    }

  private fun Candidate.score(
    wantedTitle: String,
    wantedArtist: String,
    wantedDuration: Long,
  ): Int {
    var score = textScore(title, wantedTitle, 20, 10) + textScore(artist, wantedArtist, 15, 5)
    if (wantedDuration > 0 && durationMs > 0) {
      score +=
        when {
          abs(durationMs - wantedDuration) < 3_000 -> 10
          abs(durationMs - wantedDuration) < 10_000 -> 5
          else -> 0
        }
    }
    return score
  }

  private fun textScore(
    candidate: String,
    wanted: String,
    exact: Int,
    partial: Int,
  ): Int =
    when {
      candidate.isBlank() || wanted.isBlank() -> 0
      candidate.equals(wanted, ignoreCase = true) -> exact
      candidate.contains(wanted, ignoreCase = true) || wanted.contains(candidate, ignoreCase = true) -> partial
      else -> 0
    }

  private fun apiUrl(path: String): HttpUrl.Builder = "$API/$path".toHttpUrl().newBuilder()

  private suspend fun apiBody(url: HttpUrl): String? =
    apiKey.takeIf(String::isNotBlank)?.let { lyricsGetBearer(url.toString(), it) }

  private data class Candidate(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
  )

  private data class Scored(
    val lyrics: Lyrics,
    val metadataScore: Int,
    val durationDistanceMs: Long,
  )

  private val ID_KEYS = listOf("id", "trackId", "track_id", "realId")
  private val TITLE_KEYS = listOf("name", "title", "trackName", "track_name")
  private val ARTIST_KEYS = listOf("artistName", "artist_name")
  private val DURATION_KEYS = listOf("durationInMillis", "durationMs", "duration_ms", "duration")

  private val APPLE_INDEX_SCRIPT = Regex("""/assets/index~[^"]+\.js""")
  private val APPLE_TOKEN = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
}

internal fun normalizeApiKey(value: String): String {
  val trimmed = value.trim()
  return if (trimmed.startsWith("Bearer ", ignoreCase = true)) trimmed.substringAfter(' ').trim() else trimmed
}

/** The single PaxSenix route a provider instance stands for. */
internal enum class PaxSenixRoute { APPLE, SPOTIFY, MUSIXMATCH }

internal class PaxSenixProvider(
  override val id: LyricsProvider,
  private val route: PaxSenixRoute,
) : LyricsProviderClient {
  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? =
    when (route) {
      PaxSenixRoute.APPLE -> PaxSenixApi.appleLyrics(query)
      PaxSenixRoute.SPOTIFY -> PaxSenixApi.spotifyLyrics(query)
      PaxSenixRoute.MUSIXMATCH -> PaxSenixApi.musixmatchLyrics(query)
    }
}
