/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.lyrics

import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.data.lyrics.LrcLibApiService
import app.gyrolet.mpvrx.data.lyrics.LrcLibResponse
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.utils.media.EmbeddedLyricsExtractor
import app.gyrolet.mpvrx.utils.media.LyricsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class LyricsResult(
  val embeddedLyrics: Lyrics? = null,
  val onlineLyrics: Lyrics? = null,
  val activeLyrics: Lyrics? = null,
  val selectedSource: LyricsSourceType = LyricsSourceType.EMBEDDED,
  val availableSources: List<LyricsSourceType> = emptyList(),
)

class LyricsRepository(
  private val context: Context,
  private val lrcLibApiService: LrcLibApiService,
) {
  companion object {
    private const val TAG = "LyricsRepository"
    private val BRACKETED_REGEX = Regex("""[\(\[\{\uFF08\uFF3B\uFF5B\u3010\u300E\u300C\u3014\u3008\u300A]([^)\]\}\uFF09\uFF3D\uFF5D\u3011\u300F\u300D\u3015\u3009\u300B]*)[\)\]\}\uFF09\uFF3D\uFF5D\u3011\u300F\u300D\u3015\u3009\u300B]""")
    private val TRACK_NO_REGEX = Regex("""^\s*\d{1,3}\s*[\._-]\s+""")
    private val AUDIO_EXT_REGEX = Regex("""\.(mp3|flac|m4a|aac|wav|ogg|opus|wma|alac|ape)$""", RegexOption.IGNORE_CASE)
    private val UNKNOWN_ARTISTS = setOf("", "unknown", "unknown artist", "<unknown>", "various artists", "various")
  }

  // Cache by media path -> LyricsResult
  private val cache = ConcurrentHashMap<String, LyricsResult>()

  private fun cleanTitle(title: String): String {
    return title
      .replace(AUDIO_EXT_REGEX, "")
      .replace(TRACK_NO_REGEX, "")
      .trim()
  }

  private fun superCleanTitle(title: String): String {
    val step1 = cleanTitle(title)
    val step2 = BRACKETED_REGEX.replace(step1, "").trim()
    val step3 = step2.split(" feat.", " ft.", " featuring", " Feat.", " Ft.").first().trim()
    return step3.ifBlank { step1 }
  }

  private fun cleanArtist(artist: String?): String {
    val raw = artist?.trim().orEmpty()
    if (raw.lowercase() in UNKNOWN_ARTISTS) return ""
    return raw.split(" feat.", " ft.", " featuring", " & ", " , ").first().trim()
  }

  suspend fun loadLyricsForTrack(
    mediaPath: String,
    title: String?,
    artist: String?,
    durationSeconds: Int = 0,
    forceRefresh: Boolean = false,
  ): LyricsResult = withContext(Dispatchers.IO) {
    if (!forceRefresh && cache.containsKey(mediaPath)) {
      cache[mediaPath]?.let { return@withContext it }
    }

    Log.d(TAG, "Loading lyrics for: $title by $artist ($mediaPath)")

    // 1. Check embedded lyrics first
    val embedded = EmbeddedLyricsExtractor.extractEmbeddedLyrics(context, mediaPath)

    // 2. Fetch online lyrics from LRCLIB
    val online = fetchOnlineLyrics(title, artist, durationSeconds)

    // 3. Determine available sources and default preference (Embedded first if available)
    val sources = mutableListOf<LyricsSourceType>()
    if (embedded != null && embedded.isValid()) {
      sources.add(if (embedded.sourceType == LyricsSourceType.LOCAL) LyricsSourceType.LOCAL else LyricsSourceType.EMBEDDED)
    }
    if (online != null && online.isValid()) {
      sources.add(LyricsSourceType.ONLINE)
    }

    val defaultSelected = when {
      embedded != null && embedded.isValid() -> embedded.sourceType
      online != null && online.isValid() -> LyricsSourceType.ONLINE
      else -> LyricsSourceType.EMBEDDED
    }

    val active = when (defaultSelected) {
      LyricsSourceType.EMBEDDED, LyricsSourceType.LOCAL -> embedded ?: online
      LyricsSourceType.ONLINE -> online ?: embedded
    }

    val result = LyricsResult(
      embeddedLyrics = embedded,
      onlineLyrics = online,
      activeLyrics = active,
      selectedSource = defaultSelected,
      availableSources = sources.distinct(),
    )

    cache[mediaPath] = result
    result
  }

  suspend fun fetchOnlineLyrics(
    rawTitle: String?,
    rawArtist: String?,
    durationSeconds: Int = 0,
  ): Lyrics? = withContext(Dispatchers.IO) {
    if (rawTitle.isNullOrBlank()) return@withContext null

    var title = cleanTitle(rawTitle)
    var artist = cleanArtist(rawArtist)

    // Split "Artist - Title" if artist is unknown and title contains "-"
    if (artist.isBlank() && title.contains("-")) {
      val parts = title.split("-", limit = 2)
      if (parts.size == 2 && parts[0].trim().isNotBlank() && parts[1].trim().isNotBlank()) {
        artist = cleanArtist(parts[0])
        title = cleanTitle(parts[1])
      }
    }

    val sCleanTitle = superCleanTitle(title)

    try {
      // Strategy 1: Exact search with get endpoint (if artist known)
      if (artist.isNotBlank()) {
        val response = lrcLibApiService.getLyrics(
          trackName = title,
          artistName = artist,
          duration = if (durationSeconds > 0) durationSeconds else null,
        )
        if (response != null) {
          val raw = response.syncedLyrics ?: response.plainLyrics
          if (!raw.isNullOrBlank()) {
            val parsed = LyricsUtils.parseLyrics(raw, sourceType = LyricsSourceType.ONLINE)
            if (parsed.isValid()) return@withContext parsed
          }
        }
      }

      // Strategy 2: Flexible search with clean title and artist
      val results2 = lrcLibApiService.searchLyrics(
        trackName = title,
        artistName = artist.takeIf { it.isNotBlank() },
      )
      extractBestMatch(results2)?.let { return@withContext it }

      // Strategy 3: Super clean title and artist (stripped brackets/feat)
      if (sCleanTitle != title) {
        val results3 = lrcLibApiService.searchLyrics(
          trackName = sCleanTitle,
          artistName = artist.takeIf { it.isNotBlank() },
        )
        extractBestMatch(results3)?.let { return@withContext it }
      }

      // Strategy 4: Combined query search ("Artist Track")
      if (artist.isNotBlank()) {
        val results4 = lrcLibApiService.searchLyrics(query = "$artist $sCleanTitle")
        extractBestMatch(results4)?.let { return@withContext it }
      }

      // Strategy 5: Track-only search with superCleanTitle (aggressive fallback)
      val results5 = lrcLibApiService.searchLyrics(trackName = sCleanTitle)
      extractBestMatch(results5)?.let { return@withContext it }

    } catch (e: Exception) {
      Log.w(TAG, "Failed to fetch online lyrics: ${e.message}")
    }

    null
  }

  private fun extractBestMatch(responses: List<LrcLibResponse>): Lyrics? {
    if (responses.isEmpty()) return null
    val bestMatch = responses.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
      ?: responses.firstOrNull { !it.plainLyrics.isNullOrBlank() }
      ?: return null

    val raw = bestMatch.syncedLyrics ?: bestMatch.plainLyrics ?: return null
    val parsed = LyricsUtils.parseLyrics(raw, sourceType = LyricsSourceType.ONLINE)
    return if (parsed.isValid()) parsed else null
  }

  fun switchSource(mediaPath: String, sourceType: LyricsSourceType): LyricsResult? {
    val existing = cache[mediaPath] ?: return null
    val newActive = when (sourceType) {
      LyricsSourceType.EMBEDDED, LyricsSourceType.LOCAL -> existing.embeddedLyrics ?: existing.onlineLyrics
      LyricsSourceType.ONLINE -> existing.onlineLyrics ?: existing.embeddedLyrics
    }
    val updated = existing.copy(
      selectedSource = sourceType,
      activeLyrics = newActive,
    )
    cache[mediaPath] = updated
    return updated
  }
}
