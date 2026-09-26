/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import app.gyrolet.mpvrx.data.lyrics.LrcLibApiService
import app.gyrolet.mpvrx.data.lyrics.providers.LyricsFetchQuery
import app.gyrolet.mpvrx.data.lyrics.providers.LyricsProviderRegistry
import app.gyrolet.mpvrx.data.lyrics.providers.PaxSenixApi
import app.gyrolet.mpvrx.data.lyrics.providers.TrackRef
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.utils.media.EmbeddedLyricsExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Lyrics for one track, plus which of its sources they came from. */
data class LyricsResult(
  val embeddedLyrics: Lyrics? = null,
  val onlineLyrics: Lyrics? = null,
  val activeLyrics: Lyrics? = null,
  val selectedSource: LyricsSourceType = LyricsSourceType.EMBEDDED,
  val availableSources: List<LyricsSourceType> = emptyList(),
  /** Which provider stands behind [onlineLyrics]. */
  val onlineProvider: LyricsProvider? = null,
  /**
   * What the picker should show: the chosen provider when it came through, or
   * null when the answer came from the automatic race and the choice is moot.
   */
  val preferredOnlineProvider: LyricsProvider? = null,
  /** What the automatic race produced, kept so switching back to it is free. */
  val autoOnlineLyrics: Lyrics? = null,
  val autoOnlineProvider: LyricsProvider? = null,
  /** Every online answer seen for this track, so changing provider costs no network. */
  val onlineByProvider: Map<LyricsProvider, Lyrics> = emptyMap(),
)

/** What one online lookup produced, and what else it saw on the way. */
data class OnlineLyricsResult(
  val provider: LyricsProvider? = null,
  val lyrics: Lyrics? = null,
  /** Whether this came from the automatic race rather than one chosen source. */
  val raced: Boolean = false,
  val byProvider: Map<LyricsProvider, Lyrics> = emptyMap(),
)

/**
 * Where the player gets its lyrics.
 *
 * Embedded tags and a sidecar `.lrc` are read locally; everything else is
 * fetched from one of the [LyricsProvider]s. On an automatic lookup every
 * provider is asked at the same time and their answers are taken in priority
 * order, so a miss on a slow host costs only the wait for its budget rather
 * than a round trip before the next is tried — and the first word-timed answer
 * in that order wins outright.
 */
class LyricsRepository(
  private val context: Context,
  lrcLibApiService: LrcLibApiService,
  private val audioPreferences: AudioPreferences,
) {
  companion object {
    private const val TAG = "LyricsRepository"
    private val BRACKETED_REGEX =
      Regex(
        """[\(\[\{\uFF08\uFF3B\uFF5B\u3010\u300E\u300C\u3014\u3008\u300A]([^)\]\}\uFF09\uFF3D\uFF5D\u3011\u300F\u300D\u3015\u3009\u300B\u300C]*)[\)\]\}\uFF09\uFF3D\uFF5D\u3011\u300F\u300D\u3015\u3009\u300B\u300C]""",
      )
    private val TRACK_NO_REGEX = Regex("""^\s*\d{1,3}\s*[\._-]\s+""")
    private val YOUTUBE_ID_REGEX = Regex("""\s*\[[a-zA-Z0-9_-]{6,16}\]\s*$""")
    private val MEDIA_EXT_REGEX =
      Regex(
        """\.(mp3|flac|m4a|aac|wav|ogg|opus|wma|alac|ape|mp4|mkv|webm|avi|mov|flv|wmv|m4v|3gp|ts)$""",
        RegexOption.IGNORE_CASE,
      )
    private val YOUTUBE_NOISE_REGEX =
      Regex(
        """(?i)\b(official\s+(music\s+)?video|official\s+audio|official\s+lyric\s+video|lyric\s+video|lyrics\s+video|lyrical\s+video|full\s+song\s+lyrics|full\s+song|full\s+video|video\s+song|music\s+video|lyrical|lyrics|audio|visualizer|remastered|remaster|4k|hd|8k|mv)\b""",
      )
    private val SEGMENT_DELIMITER_REGEX = Regex("""\s*[|｜¦/／]\s*""")
    private val HYPHEN_DELIMITER_REGEX = Regex("""\s*[-–—－]\s*""")
    private val UNKNOWN_ARTISTS = setOf("", "unknown", "unknown artist", "<unknown>", "various artists", "various")
    private val YOUTUBE_VIDEO_REGEX =
      Regex(
        """(?:youtube\.com/watch\?(?:[^#]*&)?v=|music\.youtube\.com/watch\?(?:[^#]*&)?v=|youtu\.be/|youtube\.com/shorts/)([A-Za-z0-9_-]{11})""",
      )

    /** How many title/artist guesses a lookup hands the providers. */
    private const val MAX_REFS = 8

    /** The video behind a playing path, when the path is a YouTube URL at all. */
    fun videoIdFrom(path: String?): String? =
      path
        ?.takeIf { it.isNotBlank() }
        ?.let { YOUTUBE_VIDEO_REGEX.find(it) }
        ?.groupValues
        ?.get(1)
  }

  private val registry = LyricsProviderRegistry(lrcLibApiService)

  /**
   * The provider the user asked for on this session's tracks, or null for the
   * automatic race. Memory rather than a stored preference: what serves one
   * album well is not a decision that should outlive the listening.
   */
  @Volatile
  var preferredProvider: LyricsProvider? = null
    private set

  private data class CacheKey(
    val mediaPath: String,
    val allowOnline: Boolean,
  )

  private val cache = LruCache<CacheKey, LyricsResult>(64)

  private fun cleanTitle(title: String): String =
    title
      .replace(MEDIA_EXT_REGEX, "")
      .replace(YOUTUBE_ID_REGEX, "")
      .replace(TRACK_NO_REGEX, "")
      .trim()

  private fun superCleanTitle(title: String): String {
    val step1 = cleanTitle(title)
    val firstSeg =
      step1
        .split(SEGMENT_DELIMITER_REGEX)
        .firstOrNull()
        ?.trim()
        .orEmpty()
        .ifBlank { step1 }
    val step2 = BRACKETED_REGEX.replace(firstSeg, "").trim()
    val step3 = step2.split(" feat.", " ft.", " featuring", " Feat.", " Ft.", " feat ", " ft ").first().trim()
    val step4 = YOUTUBE_NOISE_REGEX.replace(step3, "").trim()
    return step4.ifBlank { step3.ifBlank { step1 } }
  }

  private fun cleanArtist(artist: String?): String {
    val raw = artist?.trim().orEmpty()
    if (raw.lowercase() in UNKNOWN_ARTISTS) return ""
    return raw.split(" feat.", " ft.", " featuring", " Feat.", " Ft.", " feat ", " ft ").first().trim()
  }

  private fun isLikelyChannelOrLabel(artist: String?): Boolean {
    val lower = artist?.trim().orEmpty().lowercase()
    if (lower.isBlank() || lower in UNKNOWN_ARTISTS) return true
    return lower.endsWith(" music") || lower.endsWith(" records") ||
      lower.endsWith(" official") || lower.endsWith(" entertainment") ||
      lower.endsWith(" channel") || lower.endsWith(" series") ||
      lower.endsWith(" films") || lower.endsWith(" company") ||
      lower.endsWith(" vevo") || lower.contains("t-series") ||
      lower.contains("tseries") || lower.contains("saregama") ||
      lower.contains("zee music") || lower.contains("sony music") ||
      lower.contains("tips official") || lower.contains("yrf") ||
      lower.contains("speed records") || lower.contains("geet mp3") ||
      lower.contains("desire music") || lower.contains("lofi girl") ||
      lower.contains("aditya music") || lower.contains("lahari music")
  }

  /**
   * The guesses about what this file actually is, in the order they are worth
   * trying: the metadata as given, then the halves of an "Artist - Title" line
   * read both ways round, then any other segment of a multi-part title as an
   * artist, then the title on its own.
   */
  private fun buildRefs(
    rawTitle: String?,
    rawArtist: String?,
  ): List<TrackRef> {
    if (rawTitle.isNullOrBlank()) return emptyList()

    val baseTitle = cleanTitle(rawTitle)
    val segments = baseTitle.split(SEGMENT_DELIMITER_REGEX).map { it.trim() }.filter { it.isNotBlank() }
    val firstSeg = segments.firstOrNull() ?: baseTitle
    val primaryTitle = superCleanTitle(firstSeg)

    val cleanRawArtist = cleanArtist(rawArtist)
    val metadataArtist = if (isLikelyChannelOrLabel(cleanRawArtist)) "" else cleanRawArtist

    val refs = LinkedHashMap<String, TrackRef>()

    fun add(
      title: String,
      artist: String,
    ) {
      val cleanT = title.trim()
      if (cleanT.isBlank()) return
      val ref = TrackRef(cleanT, artist.trim())
      refs.putIfAbsent(ref.key(), ref)
    }

    if (metadataArtist.isNotBlank()) add(primaryTitle, metadataArtist)

    if (firstSeg.contains(HYPHEN_DELIMITER_REGEX)) {
      val parts = firstSeg.split(HYPHEN_DELIMITER_REGEX, limit = 2)
      if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
        val p0 = superCleanTitle(parts[0])
        val p1 = superCleanTitle(parts[1])
        // "A.R. Rahman - Raanjhanaa" and "Vaaroon - Mirzapur" are both common,
        // and nothing here knows which half is the artist.
        add(p1, p0)
        add(p0, p1)
      }
    }

    for (otherSeg in segments.drop(1)) {
      val cleanSeg = YOUTUBE_NOISE_REGEX.replace(BRACKETED_REGEX.replace(otherSeg, "").trim(), "").trim()
      if (cleanSeg.isBlank() || isLikelyChannelOrLabel(cleanSeg)) continue
      val firstArtist = cleanArtist(cleanSeg)
      if (firstArtist.isNotBlank()) add(primaryTitle, firstArtist)
      add(primaryTitle, cleanSeg)
    }

    add(primaryTitle, "")
    val rawFirstClean = cleanTitle(firstSeg)
    if (rawFirstClean != primaryTitle) add(rawFirstClean, "")

    return refs.values.toList().take(MAX_REFS)
  }

  suspend fun loadLyricsForTrack(
    mediaPath: String,
    title: String?,
    artist: String?,
    durationSeconds: Int = 0,
    forceRefresh: Boolean = false,
    allowOnline: Boolean = true,
    album: String? = null,
    isrc: String? = null,
  ): LyricsResult =
    withContext(Dispatchers.IO) {
      val cacheKey = CacheKey(mediaPath, allowOnline)
      if (!forceRefresh) {
        cache.get(cacheKey)?.let { return@withContext it }
      }

      Log.d(TAG, "Loading lyrics for: $title by $artist ($mediaPath)")

      val embedded = EmbeddedLyricsExtractor.extractEmbeddedLyrics(context, mediaPath)

      val online =
        if (allowOnline) {
          fetchOnlineLyrics(
            rawTitle = title,
            rawArtist = artist,
            durationSeconds = durationSeconds,
            provider = preferredProvider,
            album = album,
            isrc = isrc,
            mediaPath = mediaPath,
          )
        } else {
          OnlineLyricsResult()
        }

      val sources = mutableListOf<LyricsSourceType>()
      if (embedded != null && embedded.isValid()) {
        sources.add(
          if (embedded.sourceType ==
            LyricsSourceType.LOCAL
          ) {
            LyricsSourceType.LOCAL
          } else {
            LyricsSourceType.EMBEDDED
          },
        )
      }
      if (online.lyrics != null && online.lyrics.isValid()) {
        sources.add(LyricsSourceType.ONLINE)
      }

      val defaultSelected =
        when {
          embedded != null && embedded.isValid() -> {
            if (embedded.synced.isNullOrEmpty() && online.lyrics != null && !online.lyrics.synced.isNullOrEmpty()) {
              LyricsSourceType.ONLINE
            } else {
              embedded.sourceType
            }
          }
          online.lyrics != null && online.lyrics.isValid() -> LyricsSourceType.ONLINE
          else -> LyricsSourceType.EMBEDDED
        }

      val active =
        when (defaultSelected) {
          LyricsSourceType.EMBEDDED, LyricsSourceType.LOCAL -> embedded ?: online.lyrics
          LyricsSourceType.ONLINE -> online.lyrics ?: embedded
        }

      val autoResult = online.lyrics.takeIf { preferredProvider == null }
      val result =
        LyricsResult(
          embeddedLyrics = embedded,
          onlineLyrics = online.lyrics,
          activeLyrics = active,
          selectedSource = defaultSelected,
          availableSources = sources.distinct(),
          onlineProvider = online.provider,
          preferredOnlineProvider =
            if (preferredProvider != null && online.provider == preferredProvider) {
              online.provider
            } else {
              null
            },
          autoOnlineLyrics = autoResult,
          autoOnlineProvider = if (autoResult != null) online.provider else null,
          onlineByProvider = online.byProvider,
        )

      cache.put(cacheKey, result)
      result
    }

  /**
   * Asks for online lyrics.
   *
   * With [provider] set, that one provider is asked — and if it has nothing,
   * the automatic race runs anyway rather than leaving the panel empty. Without
   * it every provider is raced and the first answer in priority order is taken.
   */
  suspend fun fetchOnlineLyrics(
    rawTitle: String?,
    rawArtist: String?,
    durationSeconds: Int = 0,
    provider: LyricsProvider? = null,
    album: String? = null,
    isrc: String? = null,
    mediaPath: String? = null,
  ): OnlineLyricsResult =
    withContext(Dispatchers.IO) {
      if (rawTitle.isNullOrBlank()) return@withContext OnlineLyricsResult()

      val query =
        LyricsFetchQuery(
          refs = buildRefs(rawTitle, rawArtist),
          durationMs = (durationSeconds * 1000).coerceAtLeast(0),
          album = album,
          videoId = videoIdFrom(mediaPath),
          isrc = isrc,
        )
      if (query.refs.isEmpty()) return@withContext OnlineLyricsResult()

      PaxSenixApi.setApiKey(audioPreferences.paxsenixApiKey.get())

      if (provider != null) {
        val found = registry.fetch(provider, query)
        if (found != null && found.isValid()) {
          return@withContext OnlineLyricsResult(provider, found, false, mapOf(provider to found))
        }
        Log.d(TAG, "${provider.label} had nothing; falling back to the automatic lookup")
      }

      raceProviders(query)
    }

  /**
   * Every provider asked at once, answers taken in priority order.
   *
   * A word-timed hit from a provider near the top wins the moment it arrives;
   * a plain-text answer is held back as the fallback and only used if nothing
   * timed comes in. The scraping provider at the bottom of the order is not
   * started at all unless everything above it has already missed.
   */
  private suspend fun raceProviders(query: LyricsFetchQuery): OnlineLyricsResult =
    coroutineScope {
      val jobs =
        LyricsProvider.entries.map { provider ->
          val start = if (provider in LyricsProvider.LAZY_PROVIDERS) CoroutineStart.LAZY else CoroutineStart.DEFAULT
          provider to async(Dispatchers.IO, start = start) { registry.fetch(provider, query) }
        }

      try {
        var timed: Pair<LyricsProvider, Lyrics>? = null
        var plainFallback: Pair<LyricsProvider, Lyrics>? = null
        val seen = mutableMapOf<LyricsProvider, Lyrics>()

        for ((provider, job) in jobs) {
          if (timed != null && provider in LyricsProvider.LAZY_PROVIDERS) continue
          val found =
            runCatching { job.await() }
              .getOrElse { error -> if (error is CancellationException) throw error else null } ?: continue
          if (!found.isValid()) continue
          seen[provider] = found

          if (!found.synced.isNullOrEmpty()) {
            timed = provider to found
            break
          }
          if (plainFallback == null) plainFallback = provider to found
        }

        val winner = timed ?: plainFallback
        if (winner != null) {
          seen[winner.first] = winner.second
          OnlineLyricsResult(
            provider = winner.first,
            lyrics = winner.second,
            raced = true,
            byProvider = seen.toMap(),
          )
        } else {
          OnlineLyricsResult(byProvider = seen.toMap())
        }
      } finally {
        jobs.forEach { it.second.cancel() }
      }
    }

  fun switchSource(
    mediaPath: String,
    sourceType: LyricsSourceType,
    allowOnline: Boolean = true,
  ): LyricsResult? {
    if (!allowOnline && sourceType == LyricsSourceType.ONLINE) return null
    val cacheKey = CacheKey(mediaPath, allowOnline)
    val existing = cache.get(cacheKey) ?: return null
    val newActive =
      when (sourceType) {
        LyricsSourceType.EMBEDDED, LyricsSourceType.LOCAL -> existing.embeddedLyrics ?: existing.onlineLyrics
        LyricsSourceType.ONLINE -> existing.onlineLyrics ?: existing.embeddedLyrics
      }
    val updated =
      existing.copy(
        selectedSource = sourceType,
        activeLyrics = newActive,
      )
    cache.put(cacheKey, updated)
    return updated
  }

  /**
   * Moves the online half of the result onto [provider], when this track has
   * already been fetched from it. Null for "not fetched yet", which is the
   * caller's signal to go and get it.
   */
  fun switchProvider(
    mediaPath: String,
    provider: LyricsProvider?,
    allowOnline: Boolean = true,
  ): LyricsResult? {
    preferredProvider = provider
    val cacheKey = CacheKey(mediaPath, allowOnline)
    val existing = cache.get(cacheKey) ?: return null

    val picked =
      if (provider == null) {
        existing.autoOnlineLyrics?.let { it to existing.autoOnlineProvider }
      } else {
        existing.onlineByProvider[provider]?.let { it to provider }
      }

    val (lyrics, from) = picked ?: return null
    val updated =
      existing.copy(
        selectedSource = LyricsSourceType.ONLINE,
        onlineLyrics = lyrics,
        onlineProvider = from,
        preferredOnlineProvider = provider,
        activeLyrics = lyrics,
        availableSources = (existing.availableSources + LyricsSourceType.ONLINE).distinct(),
      )
    cache.put(cacheKey, updated)
    return updated
  }

  /**
   * Folds a fresh lookup into the cached result: every provider it heard from
   * joins the map, and its winner becomes the online lyrics if there is one.
   */
  fun mergeOnline(
    mediaPath: String,
    result: OnlineLyricsResult,
    allowOnline: Boolean = true,
  ): LyricsResult? {
    val cacheKey = CacheKey(mediaPath, allowOnline)
    val existing = cache.get(cacheKey) ?: return null
    val byProvider =
      if (result.byProvider.isEmpty()) {
        existing.onlineByProvider
      } else {
        existing.onlineByProvider +
          result.byProvider
      }
    val lyrics = result.lyrics?.takeIf { it.isValid() }
    val updated =
      existing.copy(
        onlineByProvider = byProvider,
        onlineLyrics = lyrics ?: existing.onlineLyrics,
        onlineProvider = result.provider ?: existing.onlineProvider,
        preferredOnlineProvider =
          if (result.raced) {
            null
          } else {
            result.provider ?: existing.preferredOnlineProvider
          },
        autoOnlineLyrics = if (result.raced) lyrics else existing.autoOnlineLyrics,
        autoOnlineProvider = if (result.raced) result.provider else existing.autoOnlineProvider,
        selectedSource = if (lyrics != null) LyricsSourceType.ONLINE else existing.selectedSource,
        activeLyrics = if (lyrics != null) lyrics else existing.activeLyrics,
        availableSources =
          if (lyrics != null) {
            (existing.availableSources + LyricsSourceType.ONLINE).distinct()
          } else {
            existing.availableSources
          },
      )
    cache.put(cacheKey, updated)
    return updated
  }

  fun invalidate(
    mediaPath: String,
    allowOnline: Boolean = true,
  ) {
    cache.remove(CacheKey(mediaPath, allowOnline))
  }
}
