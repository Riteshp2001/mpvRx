/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import app.gyrolet.mpvrx.data.lyrics.LrcLibApiService
import app.gyrolet.mpvrx.data.lyrics.providers.BiniLyricsProvider
import app.gyrolet.mpvrx.data.lyrics.providers.LyricsFetchQuery
import app.gyrolet.mpvrx.data.lyrics.providers.LyricsProviderRegistry
import app.gyrolet.mpvrx.data.lyrics.providers.PaxSenixApi
import app.gyrolet.mpvrx.data.lyrics.providers.ProviderOutcome
import app.gyrolet.mpvrx.data.lyrics.providers.TrackRef
import app.gyrolet.mpvrx.data.lyrics.providers.withBackgroundVocals
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.utils.media.EmbeddedLyricsExtractor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
  /** Providers that answered with nothing for this track. */
  val missingProviders: Set<LyricsProvider> = emptySet(),
  /** Providers that errored out or timed out for this track. */
  val failedProviders: Set<LyricsProvider> = emptySet(),
) {
  /** Folds what one lookup learned about each provider into this track's statuses. */
  fun withOutcomes(result: OnlineLyricsResult): LyricsResult {
    val answered = result.byProvider.keys
    return copy(
      missingProviders = (missingProviders - answered - result.failed) + result.missing,
      failedProviders = (failedProviders - answered - result.missing) + result.failed,
    )
  }
}

/** What one online lookup produced, and what else it saw on the way. */
data class OnlineLyricsResult(
  val provider: LyricsProvider? = null,
  val lyrics: Lyrics? = null,
  /** Whether this came from the automatic race rather than one chosen source. */
  val raced: Boolean = false,
  val byProvider: Map<LyricsProvider, Lyrics> = emptyMap(),
  val missing: Set<LyricsProvider> = emptySet(),
  val failed: Set<LyricsProvider> = emptySet(),
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
    private val RECORDING_VERSION_REGEX = Regex(
      """\b(live|remix|remixed|mix|acoustic|unplugged|instrumental|karaoke|demo|edit|cover|sped|slowed|nightcore|lofi|extended|remaster|remastered)\b""",
      RegexOption.IGNORE_CASE,
    )
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

    /**
     * Longest the lookup will wait to find out which recording this is.
     *
     * Short on purpose. Knowing the recording makes every match better, but
     * not knowing it only leaves the lookup where it was before, and an empty
     * panel because one host is slow is the worse failure.
     */
    private const val IDENTIFY_TIMEOUT_MS = 2_500L

    /** How many recordings to keep in hand. */
    private const val REMEMBERED_RECORDINGS = 100

    /** How far past the end of the file a timed line may sit before the lyrics are another edit. */
    private const val LENGTH_OVERRUN_MS = 12_000

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
   * The online provider to try first for this listening session. Null is the
   * automatic provider race, which is the fresh-session default: it takes the
   * best-timed answer that fits the playing track, with LRCLIB as the last
   * resort when nothing else has words. The choice remains session-scoped
   * rather than becoming a persisted setting.
   */
  @Volatile
  var preferredProvider: LyricsProvider? = null
    private set

  private data class CacheKey(
    val mediaPath: String,
    val allowOnline: Boolean,
    val autoFetchOnline: Boolean,
  )

  private fun cacheKey(mediaPath: String, allowOnline: Boolean): CacheKey =
    CacheKey(mediaPath, allowOnline, audioPreferences.lyricsAutoFetchOnline.get())

  private val cache = LruCache<CacheKey, LyricsResult>(64)
  private val providerCache = LyricsProviderCache(File(context.cacheDir, "lyrics/providers-v4"))

  /**
   * The recording behind a track, once something has worked it out.
   *
   * In memory only: this is a shortcut, not a store, and losing it costs one
   * fuzzy match — which is what every lookup did before any of this.
   */
  private val isrcs = LruCache<String, String>(REMEMBERED_RECORDINGS)

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
    val step2 = BRACKETED_REGEX.replace(firstSeg) { match ->
      if (RECORDING_VERSION_REGEX.containsMatchIn(match.value)) match.value else ""
    }.trim()
    val step3 = step2.split(" feat.", " ft.", " featuring", " Feat.", " Ft.", " feat ", " ft ").first().trim()
    val step4 = YOUTUBE_NOISE_REGEX.replace(step3) { match ->
      if (RECORDING_VERSION_REGEX.matches(match.value)) match.value else ""
    }.trim()
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
    forceOnline: Boolean = false,
    album: String? = null,
    isrc: String? = null,
  ): LyricsResult =
    withContext(Dispatchers.IO) {
      val cacheKey = cacheKey(mediaPath, allowOnline)
      val requestedProvider = preferredProvider
      if (!forceRefresh && !forceOnline) {
        cache.get(cacheKey)?.let { existing ->
          if (!allowOnline || existing.embeddedLyrics?.isValid() == true ||
            !audioPreferences.lyricsAutoFetchOnline.get() ||
            existing.preferredOnlineProvider == requestedProvider
          ) return@withContext existing
          switchProvider(mediaPath, requestedProvider, allowOnline)?.let { return@withContext it }
        }
      }

      Log.d(TAG, "Loading lyrics for: $title by $artist ($mediaPath)")

      val embedded = EmbeddedLyricsExtractor.extractEmbeddedLyrics(context, mediaPath)

      // Never delay usable embedded/local lyrics for an automatic provider search.
      // An explicit Search Online action can override this preference.
      val online =
        if (allowOnline && (forceOnline ||
            (audioPreferences.lyricsAutoFetchOnline.get() && embedded?.isValid() != true))) {
          fetchOnlineLyrics(
            rawTitle = title,
            rawArtist = artist,
            durationSeconds = durationSeconds,
            provider = requestedProvider,
            album = album,
            isrc = isrc,
            mediaPath = mediaPath,
            forceRefresh = forceRefresh,
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
          forceOnline && online.lyrics?.isValid() == true -> LyricsSourceType.ONLINE
          embedded != null && embedded.isValid() -> embedded.sourceType
          online.lyrics != null && online.lyrics.isValid() -> LyricsSourceType.ONLINE
          else -> LyricsSourceType.EMBEDDED
        }

      val active =
        when (defaultSelected) {
          LyricsSourceType.EMBEDDED, LyricsSourceType.LOCAL -> embedded ?: online.lyrics
          LyricsSourceType.ONLINE -> online.lyrics ?: embedded
        }

      val autoResult = online.lyrics.takeIf { online.raced }
      val previous = if (forceRefresh) null else cache.get(cacheKey)
      val result =
        LyricsResult(
          embeddedLyrics = embedded,
          onlineLyrics = online.lyrics,
          activeLyrics = active,
          selectedSource = defaultSelected,
          availableSources = sources.distinct(),
          onlineProvider = online.provider,
          preferredOnlineProvider =
            if (requestedProvider != null && online.provider == requestedProvider) {
              online.provider
            } else {
              null
            },
          autoOnlineLyrics = autoResult,
          autoOnlineProvider = if (autoResult != null) online.provider else null,
          onlineByProvider = previous?.onlineByProvider.orEmpty() + online.byProvider,
          missingProviders = previous?.missingProviders.orEmpty(),
          failedProviders = previous?.failedProviders.orEmpty(),
        ).withOutcomes(online)

      currentCoroutineContext().ensureActive()
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
    forceRefresh: Boolean = false,
  ): OnlineLyricsResult =
    withContext(Dispatchers.IO) {
      if (forceRefresh && !mediaPath.isNullOrBlank()) providerCache.remove(mediaPath)
      val durationMs = (durationSeconds.toLong() * 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
      val saved = if (!forceRefresh && !mediaPath.isNullOrBlank()) providerCache.read(mediaPath) else emptyMap()
      cachedOnlineResult(saved, provider, durationMs)?.let { return@withContext it }
      if (rawTitle.isNullOrBlank()) return@withContext OnlineLyricsResult()

      val query =
        LyricsFetchQuery(
          refs = buildRefs(rawTitle, rawArtist),
          durationMs = durationMs,
          album = album,
          videoId = videoIdFrom(mediaPath),
          isrc = isrc,
        )
      if (query.refs.isEmpty()) return@withContext OnlineLyricsResult()

      PaxSenixApi.setApiKey(audioPreferences.paxsenixApiKey.get())

      val resolved = withRecording(query)

      if (provider != null) {
        val outcome = fetchProvider(provider, resolved, mediaPath)
        if (outcome is ProviderOutcome.Found) {
          val answers = mediaPath?.let(providerCache::read).orEmpty() + (provider to outcome.lyrics)
          return@withContext OnlineLyricsResult(provider, outcome.lyrics, false, answers)
        }
        Log.d(TAG, "${provider.label} had nothing; falling back to the automatic lookup")
        val raced = raceWithLrcLibFallback(resolved, mediaPath, saved, setOf(provider))
        val raceKnows = provider in raced.byProvider || provider in raced.missing || provider in raced.failed
        return@withContext raced.copy(
          byProvider = mediaPath?.let(providerCache::read).orEmpty() + raced.byProvider,
          missing = if (!raceKnows && outcome == ProviderOutcome.Missing) raced.missing + provider else raced.missing,
          failed = if (!raceKnows && outcome == ProviderOutcome.Failed) raced.failed + provider else raced.failed,
        )
      }

      val result = raceWithLrcLibFallback(resolved, mediaPath, saved)
      result.copy(byProvider = mediaPath?.let(providerCache::read).orEmpty() + result.byProvider)
    }

  private fun cachedOnlineResult(
    answers: Map<LyricsProvider, Lyrics>,
    provider: LyricsProvider?,
    durationMs: Int,
  ): OnlineLyricsResult? {
    if (provider != null) {
      val lyrics = answers[provider]?.takeIf { it.fitsLength(durationMs) } ?: return null
      return OnlineLyricsResult(provider, lyrics, false, answers)
    }
    val candidates = LyricsProvider.available.mapNotNull { source -> answers[source]?.let { source to it } }
    val fitting = candidates.filter { (_, lyrics) -> lyrics.fitsLength(durationMs) }
    val winner = fitting.firstOrNull { (_, lyrics) -> lyrics.hasWordTiming() }
      ?: fitting.firstOrNull { (_, lyrics) -> !lyrics.synced.isNullOrEmpty() }
      ?: fitting.firstOrNull()
      ?: return null
    return OnlineLyricsResult(winner.first, winner.second, true, answers)
  }

  private suspend fun fetchProvider(
    provider: LyricsProvider,
    query: LyricsFetchQuery,
    mediaPath: String?,
  ): ProviderOutcome =
    when (val outcome = registry.fetch(provider, query)) {
      is ProviderOutcome.Found ->
        if (outcome.lyrics.isValid() && outcome.lyrics.fitsLength(query.durationMs)) {
          val shaped = outcome.lyrics.withBackgroundVocals()
          currentCoroutineContext().ensureActive()
          if (!mediaPath.isNullOrBlank()) providerCache.write(mediaPath, provider, shaped)
          ProviderOutcome.Found(shaped)
        } else {
          ProviderOutcome.Missing
        }
      else -> outcome
    }

  /**
   * Names the recording before anybody is asked for words.
   *
   * A title is ambiguous where it matters: a single and its album cut share a
   * name, an artist and very nearly a length, and routinely differ in the
   * words. An ISRC settles it, and BiniLyrics is the one source here that
   * hands them out, so one short search up front gives every ISRC-capable
   * provider a name instead of a description. Capped and remembered, so a
   * slow host costs the match rather than the panel, and a track asked about
   * twice pays for it once.
   */
  private suspend fun withRecording(query: LyricsFetchQuery): LyricsFetchQuery {
    if (!query.isrc.isNullOrBlank()) return query
    if (LyricsProvider.BINI_LYRICS !in LyricsProvider.available) return query
    val key = recordingKey(query) ?: return query
    isrcs.get(key)?.let { return query.copy(isrc = it) }
    val found =
      withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
        runCatching { BiniLyricsProvider.identifyIsrc(query) }
          .getOrElse { error -> if (error is CancellationException) throw error else null }
      } ?: return query
    isrcs.put(key, found)
    return query.copy(isrc = found)
  }

  private fun recordingKey(query: LyricsFetchQuery): String? {
    query.videoId?.takeIf { it.isNotBlank() }?.let { return it }
    val ref = query.primary
    if (ref.title.isBlank()) return null
    return "${ref.title}|${ref.artist}|${query.durationMs / 1000}"
  }

  /**
   * The automatic race, with LRCLIB as the safety net.
   *
   * When every provider missed or failed and the artist is unknown, LRCLIB
   * gets one more go with the title alone, closest length wins. That is
   * wrong more often than the exact lookups, so its answer is never written to
   * the per-provider cache and only ever shown when nothing better exists.
   */
  private suspend fun raceWithLrcLibFallback(
    query: LyricsFetchQuery,
    mediaPath: String?,
    cached: Map<LyricsProvider, Lyrics>,
    excluded: Set<LyricsProvider> = emptySet(),
  ): OnlineLyricsResult {
    val raced = raceProviders(query, mediaPath, cached, excluded)
    if (raced.lyrics != null || query.primary.artist.isNotBlank() || LyricsProvider.LRCLIB in excluded) return raced

    val loose =
      query.copy(
        refs = query.candidates().map { TrackRef(it.title, "") }.distinctBy { it.key() },
        isrc = null,
      )
    if (loose.refs.isEmpty()) return raced
    val outcome = registry.fetch(LyricsProvider.LRCLIB, loose)
    val lyrics = (outcome as? ProviderOutcome.Found)?.lyrics?.takeIf { it.isValid() && it.fitsLength(query.durationMs) }
      ?: return raced
    Log.d(TAG, "Nothing answered the race; using the loose LRCLIB title match")
    return OnlineLyricsResult(
      provider = LyricsProvider.LRCLIB,
      lyrics = lyrics,
      raced = true,
      byProvider = raced.byProvider + (LyricsProvider.LRCLIB to lyrics),
      missing = raced.missing - LyricsProvider.LRCLIB,
      failed = raced.failed - LyricsProvider.LRCLIB,
    )
  }

  /**
   * Whether timed lyrics could belong to a file this long.
   *
   * A different edit of the same song drifts or runs on past the end; a line
   * stamped well beyond the end of the file is proof it is not this recording.
   * Untimed lyrics, and files whose length is unknown, always fit.
   */
  private fun Lyrics.fitsLength(durationMs: Int): Boolean {
    if (durationMs <= 0) return true
    val last = synced?.filter { it.line.isNotBlank() }?.maxOfOrNull { line ->
      maxOf(line.time, line.words?.maxOfOrNull { it.time } ?: 0)
    } ?: return true
    return last.toLong() <= durationMs.toLong() + LENGTH_OVERRUN_MS
  }

  /** Every provider is asked together; actual timing quality wins, then provider priority. */
  private suspend fun raceProviders(
    query: LyricsFetchQuery,
    mediaPath: String?,
    cached: Map<LyricsProvider, Lyrics>,
    excluded: Set<LyricsProvider> = emptySet(),
  ): OnlineLyricsResult =
    coroutineScope {
      val jobs =
        LyricsProvider.available.filterNot { it in excluded }.map { provider ->
          val start = if (provider in LyricsProvider.LAZY_PROVIDERS) CoroutineStart.LAZY else CoroutineStart.DEFAULT
          provider to async(Dispatchers.IO, start = start) {
            cached[provider]?.let(ProviderOutcome::Found) ?: fetchProvider(provider, query, mediaPath)
          }
        }

      try {
        var wordTimed: Pair<LyricsProvider, Lyrics>? = null
        var lineTimed: Pair<LyricsProvider, Lyrics>? = null
        var plainFallback: Pair<LyricsProvider, Lyrics>? = null
        val seen = mutableMapOf<LyricsProvider, Lyrics>()
        val missing = mutableSetOf<LyricsProvider>()
        val failed = mutableSetOf<LyricsProvider>()

        for ((provider, job) in jobs) {
          if ((wordTimed != null || lineTimed != null) && provider in LyricsProvider.LAZY_PROVIDERS) continue
          val outcome =
            runCatching { job.await() }
              .getOrElse { error -> if (error is CancellationException) throw error else ProviderOutcome.Failed }
          val found =
            when (outcome) {
              is ProviderOutcome.Found -> outcome.lyrics
              ProviderOutcome.Missing -> {
                missing += provider
                continue
              }
              ProviderOutcome.Failed -> {
                failed += provider
                continue
              }
            }
          if (!found.isValid()) {
            missing += provider
            continue
          }
          if (!found.fitsLength(query.durationMs)) {
            missing += provider
            continue
          }
          seen[provider] = found
          if (found.hasWordTiming()) {
            wordTimed = provider to found
            break
          }
          if (lineTimed == null && !found.synced.isNullOrEmpty()) lineTimed = provider to found
          if (plainFallback == null) plainFallback = provider to found
        }

        val winner = wordTimed ?: lineTimed ?: plainFallback
        if (winner != null) {
          seen[winner.first] = winner.second
          OnlineLyricsResult(
            provider = winner.first,
            lyrics = winner.second,
            raced = true,
            byProvider = seen.toMap(),
            missing = missing.toSet(),
            failed = failed.toSet(),
          )
        } else {
          OnlineLyricsResult(byProvider = seen.toMap(), missing = missing.toSet(), failed = failed.toSet())
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
    val cacheKey = cacheKey(mediaPath, allowOnline)
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
    if (!allowOnline) return null
    preferredProvider = provider
    val cacheKey = cacheKey(mediaPath, allowOnline)
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
    val cacheKey = cacheKey(mediaPath, allowOnline)
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
      ).withOutcomes(result)
    cache.put(cacheKey, updated)
    return updated
  }

  fun invalidate(
    mediaPath: String,
    allowOnline: Boolean = true,
  ) {
    cache.remove(cacheKey(mediaPath, allowOnline))
  }
}
