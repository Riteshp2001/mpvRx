package app.gyrolet.mpvrx.repository

import android.util.Log
import app.gyrolet.mpvrx.preferences.IntroSegmentProvider
import app.gyrolet.mpvrx.utils.media.MediaInfoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

enum class IntroDbResolutionSource {
  DIRECT_IMDB,
  DIRECT_MAL,
  EXPLICIT_TMDB,
  MAL_SEARCH,
  TMDB_SEARCH,
}

data class IntroDbLookupRequest(
  val mediaTitle: String,
  val canonicalTitle: String? = null,
  val lookupHint: String? = null,
  val imdbId: String? = null,
  val tmdbId: Int? = null,
  val mediaType: String? = null,
  val season: Int? = null,
  val episode: Int? = null,
  val provider: IntroSegmentProvider = IntroSegmentProvider.INTRO_DB,
)

sealed interface IntroDbLookupOutcome {
  val provider: IntroSegmentProvider
  val message: String

  data class Loaded(
    val imdbId: String,
    val segments: List<IntroDbSegment>,
    val source: IntroDbResolutionSource,
    override val provider: IntroSegmentProvider,
  ) : IntroDbLookupOutcome {
    override val message: String =
      "${provider.displayName}: loaded ${segments.size} marker${if (segments.size == 1) "" else "s"}"
  }

  data class NoSegments(
    val imdbId: String,
    val source: IntroDbResolutionSource,
    override val provider: IntroSegmentProvider,
  ) : IntroDbLookupOutcome {
    override val message: String = "${provider.displayName}: no markers for $imdbId"
  }

  data class Unresolved(
    val title: String,
    override val provider: IntroSegmentProvider,
  ) : IntroDbLookupOutcome {
    override val message: String = "${provider.displayName}: couldn't match \"$title\""
  }

  data class Error(
    val reason: String,
    override val provider: IntroSegmentProvider,
  ) : IntroDbLookupOutcome {
    override val message: String = "${provider.displayName} failed: $reason"
  }
}

@Serializable
data class IntroDbSegment(
  @SerialName("segment_type")
  val segmentType: String? = null,
  @SerialName("start_sec")
  val startSec: Double? = null,
  @SerialName("end_sec")
  val endSec: Double? = null,
  val start: Double? = null,
  val end: Double? = null,
) {
  val startSecondsOrNull: Double?
    get() = (startSec ?: start)?.coerceAtLeast(0.0)

  val endSecondsOrNull: Double?
    get() = (endSec ?: end)?.coerceAtLeast(0.0)

  val normalizedStart: Double
    get() = startSecondsOrNull ?: 0.0

  val normalizedEnd: Double
    get() = endSecondsOrNull ?: 0.0

  val hasTimingBounds: Boolean
    get() = startSecondsOrNull != null || endSecondsOrNull != null
}

@Serializable
private data class IntroDbTmdbSearchResult(
  val id: Int,
  val mediaType: String,
  val title: String,
  val releaseYear: String? = null,
)

@Serializable
private data class IntroDbTmdbSearchResponse(
  val results: List<IntroDbTmdbSearchResult> = emptyList(),
)

@Serializable
private data class IntroDbExternalIdsResponse(
  @SerialName("imdb_id")
  val imdbId: String? = null,
)

@Serializable
private data class AniSkipLookupResponse(
  val found: Boolean = false,
  val results: List<AniSkipLookupResult> = emptyList(),
)

@Serializable
private data class AniSkipLookupResult(
  val interval: AniSkipInterval? = null,
  @SerialName("skip_type")
  val skipType: String? = null,
)

@Serializable
private data class AniSkipInterval(
  @SerialName("start_time")
  val startTime: Double? = null,
  @SerialName("end_time")
  val endTime: Double? = null,
)

@Serializable
private data class JikanAnimeSearchResponse(
  val data: List<JikanAnimeSearchResult> = emptyList(),
)

@Serializable
private data class JikanAnimeSearchResult(
  @SerialName("mal_id")
  val malId: Int,
  val title: String? = null,
  @SerialName("title_english")
  val titleEnglish: String? = null,
  @SerialName("title_japanese")
  val titleJapanese: String? = null,
  @SerialName("title_synonyms")
  val titleSynonyms: List<String> = emptyList(),
  val titles: List<JikanAnimeTitle> = emptyList(),
  val type: String? = null,
)

@Serializable
private data class JikanAnimeTitle(
  val title: String? = null,
)

class IntroDbRepository(
  private val client: OkHttpClient,
  private val json: Json,
) {
  suspend fun lookupSegments(
    request: IntroDbLookupRequest,
  ): IntroDbLookupOutcome = withContext(Dispatchers.IO) {
    runCatching {
      val titleForLookup = request.canonicalTitle?.takeIf { it.isNotBlank() } ?: request.mediaTitle
      val parsed = MediaInfoParser.parse(titleForLookup)
      val normalizedTitle = parsed.title.ifBlank { titleForLookup.substringBeforeLast('.') }.trim()
      val effectiveMediaType = request.mediaType ?: parsed.type
      val effectiveSeason = request.season ?: parsed.season
      val effectiveEpisode = request.episode ?: parsed.episode
      if (normalizedTitle.isBlank()) {
        return@runCatching IntroDbLookupOutcome.Unresolved(
          title = titleForLookup,
          provider = request.provider,
        )
      }

      when (request.provider) {
        IntroSegmentProvider.INTRO_DB ->
          lookupViaIntroDbApp(
            request = request,
            normalizedTitle = normalizedTitle,
            parsedYear = parsed.year,
            mediaType = effectiveMediaType,
            season = effectiveSeason,
            episode = effectiveEpisode,
          )

        IntroSegmentProvider.THE_INTRO_DB ->
          lookupViaTheIntroDb(
            request = request,
            normalizedTitle = normalizedTitle,
            parsedYear = parsed.year,
            mediaType = effectiveMediaType,
            season = effectiveSeason,
            episode = effectiveEpisode,
          )

        IntroSegmentProvider.ANI_SKIP ->
          lookupViaAniSkip(
            request = request,
            normalizedTitle = normalizedTitle,
            season = effectiveSeason,
            episode = effectiveEpisode,
          )
      }
    }.getOrElse { error ->
      Log.w(TAG, "Online marker lookup failed for ${request.mediaTitle}", error)
      IntroDbLookupOutcome.Error(
        reason = error.message ?: "unknown error",
        provider = request.provider,
      )
    }
  }

  suspend fun getIntroDbAppSegments(
    imdbId: String,
    season: Int? = null,
    episode: Int? = null,
  ): Result<List<IntroDbSegment>> = withContext(Dispatchers.IO) {
    runCatching {
      val urlBuilder =
        "https://api.introdb.app/segments"
          .toHttpUrl()
          .newBuilder()
          .addQueryParameter("imdb_id", imdbId)

      if (season != null) urlBuilder.addQueryParameter("season", season.toString())
      if (episode != null) urlBuilder.addQueryParameter("episode", episode.toString())

      val request =
        Request
          .Builder()
          .url(urlBuilder.build())
          .get()
          .build()

      client.newCall(request).execute().use { response ->
        if (response.code == 404) {
          return@use emptyList()
        }
        if (!response.isSuccessful) {
          error("IntroDB request failed with HTTP ${response.code}")
        }

        val body = response.body.string()
        if (body.isBlank()) return@use emptyList()

        parseSegmentsBody(body)
      }.filter { it.hasTimingBounds }
    }.onFailure { error ->
      Log.w(TAG, "Failed to fetch IntroDB data for $imdbId", error)
    }
  }

  suspend fun getTheIntroDbSegments(
    tmdbId: Int? = null,
    imdbId: String? = null,
    mediaType: String,
    season: Int? = null,
    episode: Int? = null,
  ): Result<List<IntroDbSegment>> = withContext(Dispatchers.IO) {
    runCatching {
      val urlBuilder =
        THEINTRODB_MEDIA_URL
          .toHttpUrl()
          .newBuilder()

      when {
        tmdbId != null -> urlBuilder.addQueryParameter("tmdb_id", tmdbId.toString())
        !imdbId.isNullOrBlank() -> urlBuilder.addQueryParameter("imdb_id", imdbId)
        else -> error("TheIntroDB lookup requires a TMDB or IMDb id")
      }

      if (mediaType.equals("tv", ignoreCase = true)) {
        if (season == null || episode == null) {
          error("TheIntroDB TV lookup requires season and episode")
        }
        urlBuilder.addQueryParameter("season", season.toString())
        urlBuilder.addQueryParameter("episode", episode.toString())
      }

      val request =
        Request
          .Builder()
          .url(urlBuilder.build())
          .get()
          .build()

      client.newCall(request).execute().use { response ->
        if (response.code == 404) {
          return@use emptyList()
        }
        if (!response.isSuccessful) {
          error("TheIntroDB request failed with HTTP ${response.code}")
        }

        val body = response.body.string()
        if (body.isBlank()) return@use emptyList()

        parseTheIntroDbMediaBody(body)
      }.filter { it.hasTimingBounds }
    }.onFailure { error ->
      Log.w(TAG, "Failed to fetch TheIntroDB data for tmdbId=$tmdbId imdbId=$imdbId", error)
    }
  }

  suspend fun getAniSkipSegments(
    malId: Int,
    episode: Int,
  ): Result<List<IntroDbSegment>> = withContext(Dispatchers.IO) {
    runCatching {
      val request =
        Request
          .Builder()
          .url("$ANISKIP_SKIP_TIMES_URL/$malId/$episode?types=op&types=ed")
          .header("User-Agent", MARKER_PROVIDER_USER_AGENT)
          .get()
          .build()

      client.newCall(request).execute().use { response ->
        if (response.code == 404) {
          return@use emptyList()
        }
        if (!response.isSuccessful) {
          error("AniSkip request failed with HTTP ${response.code}")
        }

        val body = response.body.string()
        if (body.isBlank()) return@use emptyList()

        val payload = json.decodeFromString<AniSkipLookupResponse>(body)
        if (!payload.found) {
          return@use emptyList()
        }

        payload.results.mapNotNull { result ->
          val interval = result.interval ?: return@mapNotNull null
          val start = interval.startTime
          val end = interval.endTime
          if (start == null && end == null) return@mapNotNull null
          IntroDbSegment(
            segmentType =
              when (result.skipType?.lowercase()) {
                "ed" -> "ending"
                "op" -> "opening"
                else -> result.skipType ?: "intro"
              },
            start = start,
            end = end,
          )
        }
      }.filter { it.hasTimingBounds }
    }.onFailure { error ->
      Log.w(TAG, "Failed to fetch AniSkip data for malId=$malId episode=$episode", error)
    }
  }

  private fun parseSegmentsBody(body: String): List<IntroDbSegment> =
    runCatching {
      json.decodeFromString<List<IntroDbSegment>>(body)
    }.getOrElse {
      runCatching {
        json.decodeFromString<IntroDbSegment>(body)
      }.getOrNull()
        ?.takeIf { it.hasTimingBounds }
        ?.let(::listOf)
        ?: parseObjectSegments(body)
    }

  private fun parseObjectSegments(body: String): List<IntroDbSegment> =
    runCatching {
      val payload = json.parseToJsonElement(body).jsonObject
      payload
        .entries
        .mapNotNull { (segmentType, value) ->
          val segmentPayload = value as? JsonObject ?: return@mapNotNull null
          segmentPayload.toIntroDbSegment(segmentType)
        }.ifEmpty {
          payload.toLegacyIntroDbSegments()
        }
    }.getOrDefault(emptyList())

  private fun parseTheIntroDbMediaBody(body: String): List<IntroDbSegment> =
    runCatching {
      val payload = json.parseToJsonElement(body).jsonObject
      payload.entries.flatMap { (segmentType, value) ->
        val segmentList = value as? JsonArray ?: return@flatMap emptyList()
        segmentList.mapNotNull { element ->
          val segmentPayload = element as? JsonObject ?: return@mapNotNull null
          segmentPayload.toIntroDbSegment(segmentType)
        }
      }
    }.getOrDefault(emptyList())

  private fun JsonObject.toLegacyIntroDbSegments(): List<IntroDbSegment> {
    val start = this["start"]?.jsonPrimitive?.doubleOrNull
    val end = this["end"]?.jsonPrimitive?.doubleOrNull
    return if (start != null && end != null) {
      listOf(IntroDbSegment(segmentType = "intro", start = start, end = end))
    } else {
      emptyList()
    }
  }

  private fun JsonObject.toIntroDbSegment(segmentType: String): IntroDbSegment? {
    val start =
      this["start_sec"]?.jsonPrimitive?.doubleOrNull
        ?: this["start"]?.jsonPrimitive?.doubleOrNull
        ?: this["start_ms"]?.jsonPrimitive?.doubleOrNull?.div(1000.0)
    val end =
      this["end_sec"]?.jsonPrimitive?.doubleOrNull
        ?: this["end"]?.jsonPrimitive?.doubleOrNull
        ?: this["end_ms"]?.jsonPrimitive?.doubleOrNull?.div(1000.0)
    return if (start != null || end != null) {
      IntroDbSegment(segmentType = segmentType, start = start, end = end)
    } else {
      null
    }
  }

  private suspend fun lookupViaIntroDbApp(
    request: IntroDbLookupRequest,
    normalizedTitle: String,
    parsedYear: String?,
    mediaType: String,
    season: Int?,
    episode: Int?,
  ): IntroDbLookupOutcome {
    request.imdbId?.takeIf { it.isNotBlank() }?.let { imdbId ->
      return fetchSegmentsForResolvedId(
        provider = request.provider,
        lookupId = imdbId,
        imdbId = imdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.DIRECT_IMDB,
      )
    }

    extractImdbId(request.mediaTitle, request.lookupHint, request.canonicalTitle)?.let { imdbId ->
      return fetchSegmentsForResolvedId(
        provider = request.provider,
        lookupId = imdbId,
        imdbId = imdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.DIRECT_IMDB,
      )
    }

    request.tmdbId?.let { tmdbId ->
      val imdbId =
        resolveImdbIdFromTmdb(
          tmdbId = tmdbId,
          mediaType = mediaType,
        ) ?: return IntroDbLookupOutcome.Unresolved(
          title = normalizedTitle,
          provider = request.provider,
        )
      return fetchSegmentsForResolvedId(
        provider = request.provider,
        lookupId = imdbId,
        imdbId = imdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.EXPLICIT_TMDB,
      )
    }

    val match = searchTmdb(normalizedTitle, parsedYear, mediaType)
      ?: return IntroDbLookupOutcome.Unresolved(
        title = normalizedTitle,
        provider = request.provider,
      )
    val imdbId =
      resolveImdbIdFromTmdb(
        tmdbId = match.id,
        mediaType = match.mediaType,
      ) ?: return IntroDbLookupOutcome.Unresolved(
        title = normalizedTitle,
        provider = request.provider,
      )

    return fetchSegmentsForResolvedId(
      provider = request.provider,
      lookupId = imdbId,
      imdbId = imdbId,
      mediaType = mediaType,
      season = season,
      episode = episode,
      source = IntroDbResolutionSource.TMDB_SEARCH,
    )
  }

  private suspend fun lookupViaTheIntroDb(
    request: IntroDbLookupRequest,
    normalizedTitle: String,
    parsedYear: String?,
    mediaType: String,
    season: Int?,
    episode: Int?,
  ): IntroDbLookupOutcome {
    request.tmdbId?.let { tmdbId ->
      return fetchSegmentsForResolvedId(
        provider = request.provider,
        lookupId = "tmdb:$tmdbId",
        tmdbId = tmdbId,
        imdbId = request.imdbId?.takeIf { it.isNotBlank() },
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.EXPLICIT_TMDB,
      )
    }

    request.imdbId?.takeIf { it.isNotBlank() }?.let { imdbId ->
      return fetchSegmentsForResolvedId(
        provider = request.provider,
        lookupId = imdbId,
        imdbId = imdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.DIRECT_IMDB,
      )
    }

    extractImdbId(request.mediaTitle, request.lookupHint, request.canonicalTitle)?.let { imdbId ->
      return fetchSegmentsForResolvedId(
        provider = request.provider,
        lookupId = imdbId,
        imdbId = imdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.DIRECT_IMDB,
      )
    }

    val match = searchTmdb(normalizedTitle, parsedYear, mediaType)
      ?: return IntroDbLookupOutcome.Unresolved(
        title = normalizedTitle,
        provider = request.provider,
      )

    return fetchSegmentsForResolvedId(
      provider = request.provider,
      lookupId = "tmdb:${match.id}",
      tmdbId = match.id,
      imdbId = resolveImdbIdFromTmdb(match.id, match.mediaType),
      mediaType = mediaType,
      season = season,
      episode = episode,
      source = IntroDbResolutionSource.TMDB_SEARCH,
    )
  }

  private suspend fun lookupViaAniSkip(
    request: IntroDbLookupRequest,
    normalizedTitle: String,
    season: Int?,
    episode: Int?,
  ): IntroDbLookupOutcome {
    if (episode == null || episode <= 0) {
      return IntroDbLookupOutcome.Unresolved(
        title = normalizedTitle,
        provider = request.provider,
      )
    }

    extractMalId(request.mediaTitle, request.lookupHint, request.canonicalTitle)?.let { malId ->
      return fetchSegmentsForResolvedId(
        provider = request.provider,
        lookupId = "MAL $malId",
        malId = malId,
        mediaType = "tv",
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.DIRECT_MAL,
      )
    }

    val searchQueries = buildAniSkipQueryCandidates(request, normalizedTitle, season)
    val match =
      searchAniSkipAnime(searchQueries, season)
        ?: return IntroDbLookupOutcome.Unresolved(
          title = normalizedTitle,
          provider = request.provider,
        )

    return fetchSegmentsForResolvedId(
      provider = request.provider,
      lookupId = "MAL ${match.malId}",
      malId = match.malId,
      mediaType = "tv",
      season = season,
      episode = episode,
      source = IntroDbResolutionSource.MAL_SEARCH,
    )
  }

  private suspend fun fetchSegmentsForResolvedId(
    provider: IntroSegmentProvider,
    lookupId: String,
    imdbId: String? = null,
    tmdbId: Int? = null,
    malId: Int? = null,
    mediaType: String,
    season: Int?,
    episode: Int?,
    source: IntroDbResolutionSource,
  ): IntroDbLookupOutcome {
    val useEpisodeHints = mediaType.equals("tv", ignoreCase = true)
    val segments =
      when (provider) {
        IntroSegmentProvider.INTRO_DB ->
          getIntroDbAppSegments(
            imdbId = imdbId ?: error("IntroDB lookup requires an IMDb id"),
            season = season.takeIf { useEpisodeHints },
            episode = episode.takeIf { useEpisodeHints },
          )

        IntroSegmentProvider.THE_INTRO_DB ->
          getTheIntroDbSegments(
            tmdbId = tmdbId,
            imdbId = imdbId,
            mediaType = mediaType,
            season = season.takeIf { useEpisodeHints },
            episode = episode.takeIf { useEpisodeHints },
          )

        IntroSegmentProvider.ANI_SKIP ->
          getAniSkipSegments(
            malId = malId ?: error("AniSkip lookup requires a MAL id"),
            episode = episode.takeIf { useEpisodeHints } ?: error("AniSkip lookup requires an episode number"),
          )
      }.getOrElse { error ->
        throw error
      }

    return if (segments.isEmpty()) {
      IntroDbLookupOutcome.NoSegments(imdbId = lookupId, source = source, provider = provider)
    } else {
      IntroDbLookupOutcome.Loaded(
        imdbId = lookupId,
        segments = segments,
        source = source,
        provider = provider,
      )
    }
  }

  private fun extractImdbId(
    mediaTitle: String,
    lookupHint: String?,
    canonicalTitle: String?,
  ): String? =
    listOf(mediaTitle, lookupHint.orEmpty(), canonicalTitle.orEmpty())
      .firstNotNullOfOrNull { source ->
        imdbIdRegex.find(source)?.value?.lowercase()
      }

  private fun extractMalId(
    mediaTitle: String,
    lookupHint: String?,
    canonicalTitle: String?,
  ): Int? =
    listOf(mediaTitle, lookupHint.orEmpty(), canonicalTitle.orEmpty())
      .firstNotNullOfOrNull { source ->
        myAnimeListUrlRegex.find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
          ?: source.trim().takeIf { it.matches(malIdRegex) }?.toIntOrNull()
      }

  private fun buildAniSkipQueryCandidates(
    request: IntroDbLookupRequest,
    normalizedTitle: String,
    season: Int?,
  ): List<String> {
    val baseTitles =
      buildList {
        add(request.canonicalTitle)
        add(MediaInfoParser.parse(request.mediaTitle).title)
        add(normalizedTitle)
      }.mapNotNull { candidate ->
        candidate?.trim()?.takeIf { it.isNotBlank() }
      }.distinct()

    return buildList {
      baseTitles.forEach { title ->
        add(title)
        if (season != null && season > 1) {
          add("$title Season $season")
          add("$title ${ordinalSeason(season)} Season")
        }
      }
    }.distinct()
  }

  private suspend fun searchAniSkipAnime(
    queryCandidates: List<String>,
    expectedSeason: Int?,
  ): JikanAnimeSearchResult? {
    if (queryCandidates.isEmpty()) return null

    val aggregatedResults = LinkedHashMap<Int, JikanAnimeSearchResult>()
    queryCandidates
      .take(MAX_ANISKIP_QUERY_CANDIDATES)
      .forEach { query ->
        searchJikanAnime(query).forEach { result ->
          aggregatedResults.putIfAbsent(result.malId, result)
        }
      }

    return pickBestAniSkipMatch(
      results = aggregatedResults.values.toList(),
      queryCandidates = queryCandidates,
      expectedSeason = expectedSeason,
    )?.first
  }

  private suspend fun searchJikanAnime(
    query: String,
  ): List<JikanAnimeSearchResult> {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val request =
      Request
        .Builder()
        .url("$JIKAN_SEARCH_URL?q=$encodedQuery&limit=$MAX_ANISKIP_SEARCH_RESULTS")
        .header("User-Agent", MARKER_PROVIDER_USER_AGENT)
        .get()
        .build()

    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        error("AniSkip MAL search failed with HTTP ${response.code}")
      }

      val body = response.body.string()
      if (body.isBlank()) {
        emptyList()
      } else {
        json.decodeFromString<JikanAnimeSearchResponse>(body).data
      }
    }
  }

  private fun pickBestAniSkipMatch(
    results: List<JikanAnimeSearchResult>,
    queryCandidates: List<String>,
    expectedSeason: Int?,
  ): Pair<JikanAnimeSearchResult, Int>? {
    if (results.isEmpty()) return null

    val preferred =
      results
        .map { result ->
          result to buildAniSkipScore(result, queryCandidates, expectedSeason)
        }.sortedByDescending { it.second }
        .firstOrNull()
        ?: return null

    return preferred.takeIf { it.second >= ANISKIP_MATCH_THRESHOLD }
  }

  private fun buildAniSkipScore(
    result: JikanAnimeSearchResult,
    queryCandidates: List<String>,
    expectedSeason: Int?,
  ): Int {
    val normalizedTitles = result.normalizedTitles()
    var score =
      queryCandidates.maxOfOrNull { query ->
        val normalizedQuery = normalizeTitle(query)
        normalizedTitles.maxOfOrNull { candidate -> scoreNormalizedTitleMatch(normalizedQuery, candidate) } ?: 0
      } ?: 0

    val combinedTitles = normalizedTitles.joinToString(" ")

    if (result.type.equals("TV", ignoreCase = true)) {
      score += 10
    }

    if (containsAniSkipPenaltyMarker(combinedTitles)) {
      score -= 45
    }

    expectedSeason?.let { season ->
      score += scoreAniSkipSeasonMatch(normalizedTitles, season)
    }

    return score
  }

  private fun JikanAnimeSearchResult.normalizedTitles(): List<String> =
    buildList {
      add(title)
      add(titleEnglish)
      add(titleJapanese)
      addAll(titleSynonyms)
      addAll(titles.mapNotNull { it.title })
    }.mapNotNull { candidate ->
      candidate?.takeIf { it.isNotBlank() }?.let(::normalizeTitle)
    }.distinct()

  private fun scoreNormalizedTitleMatch(
    query: String,
    candidate: String,
  ): Int {
    if (query.isBlank() || candidate.isBlank()) return 0
    if (query == candidate) return 120
    if (candidate.startsWith(query) || query.startsWith(candidate)) return 92
    if (candidate.contains(query) || query.contains(candidate)) return 70

    val queryTokens = query.split(' ').filter { it.isNotBlank() }
    val candidateTokens = candidate.split(' ').filter { it.isNotBlank() }.toSet()
    val overlap = queryTokens.count(candidateTokens::contains)
    return when {
      overlap == 0 -> 0
      overlap == queryTokens.size -> 60
      else -> overlap * 12
    }
  }

  private fun scoreAniSkipSeasonMatch(
    normalizedTitles: List<String>,
    expectedSeason: Int,
  ): Int {
    val detectedSeasons = normalizedTitles.mapNotNull(::extractSeasonNumber).distinct()
    if (detectedSeasons.isEmpty()) {
      return if (expectedSeason > 1) -15 else 5
    }

    return when {
      detectedSeasons.contains(expectedSeason) -> 55
      else -> -70
    }
  }

  private fun containsAniSkipPenaltyMarker(
    normalizedTitle: String,
  ): Boolean =
    normalizedTitle.contains(" recap ") ||
      normalizedTitle.contains(" summary ") ||
      normalizedTitle.contains(" digest ") ||
      normalizedTitle.contains(" compilation ")

  private fun extractSeasonNumber(
    normalizedTitle: String,
  ): Int? {
    val directSeason =
      seasonNumberRegex.find(normalizedTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: ordinalSeasonRegex.find(normalizedTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (directSeason != null) return directSeason

    return normalizedTitle
      .takeIf { " second season" in it }
      ?.let { 2 }
      ?: normalizedTitle.takeIf { " third season" in it }?.let { 3 }
      ?: normalizedTitle.takeIf { " fourth season" in it }?.let { 4 }
  }

  private fun ordinalSeason(
    season: Int,
  ): String =
    when {
      season % 100 in 11..13 -> "${season}th"
      season % 10 == 1 -> "${season}st"
      season % 10 == 2 -> "${season}nd"
      season % 10 == 3 -> "${season}rd"
      else -> "${season}th"
    }

  private fun searchTmdb(
    title: String,
    year: String?,
    mediaType: String,
  ): IntroDbTmdbSearchResult? {
    val url = "$TMDB_SEARCH_URL?q=${URLEncoder.encode(title, "UTF-8")}"
    val request = Request.Builder().url(url).get().build()

    val results =
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          error("TMDB search failed with HTTP ${response.code}")
        }

        val body = response.body.string()
        if (body.isBlank()) {
          emptyList()
        } else {
          json.decodeFromString<IntroDbTmdbSearchResponse>(body).results
        }
      }

    return pickBestTmdbMatch(results, title, year, mediaType)
  }

  private fun pickBestTmdbMatch(
    results: List<IntroDbTmdbSearchResult>,
    title: String,
    year: String?,
    mediaType: String,
  ): IntroDbTmdbSearchResult? {
    if (results.isEmpty()) return null

    val normalizedTitle = normalizeTitle(title)
    val preferred =
      results
        .map { result ->
          result to buildTmdbScore(
            result = result,
            normalizedTitle = normalizedTitle,
            expectedYear = year,
            expectedMediaType = mediaType,
          )
        }.sortedByDescending { it.second }
        .firstOrNull()
        ?: return null

    return preferred.first.takeIf { preferred.second >= 20 }
  }

  private fun buildTmdbScore(
    result: IntroDbTmdbSearchResult,
    normalizedTitle: String,
    expectedYear: String?,
    expectedMediaType: String,
  ): Int {
    val candidateTitle = normalizeTitle(result.title)
    var score = 0

    if (result.mediaType.equals(expectedMediaType, ignoreCase = true)) {
      score += 30
    }
    if (expectedYear != null && result.releaseYear == expectedYear) {
      score += 25
    }
    if (candidateTitle == normalizedTitle) {
      score += 80
    } else if (
      candidateTitle.startsWith(normalizedTitle) ||
      normalizedTitle.startsWith(candidateTitle)
    ) {
      score += 45
    } else if (
      candidateTitle.contains(normalizedTitle) ||
      normalizedTitle.contains(candidateTitle)
    ) {
      score += 20
    }

    return score
  }

  private fun resolveImdbIdFromTmdb(
    tmdbId: Int,
    mediaType: String?,
  ): String? {
    val pathType =
      when {
        mediaType.equals("tv", ignoreCase = true) -> "tv"
        else -> "movie"
      }
    val request =
      Request
        .Builder()
        .url("$TMDB_EXTERNAL_IDS_BASE/$pathType/$tmdbId/external_ids")
        .get()
        .build()

    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        error("TMDB external_ids failed with HTTP ${response.code}")
      }

      val body = response.body.string()
      if (body.isBlank()) {
        null
      } else {
        json.decodeFromString<IntroDbExternalIdsResponse>(body).imdbId?.takeIf { it.isNotBlank() }
      }
    }
  }

  private fun normalizeTitle(title: String): String =
    title
      .lowercase()
      .replace(Regex("""[^a-z0-9]+"""), " ")
      .trim()

  companion object {
    private const val TAG = "IntroDbRepository"
    private const val THEINTRODB_MEDIA_URL = "https://api.theintrodb.org/v2/media"
    private const val ANISKIP_SKIP_TIMES_URL = "https://api.aniskip.com/v1/skip-times"
    private const val JIKAN_SEARCH_URL = "https://api.jikan.moe/v4/anime"
    private const val MARKER_PROVIDER_USER_AGENT =
      "Mozilla/5.0 (Windows NT 6.1; Win64; rv:109.0) Gecko/20100101 Firefox/109.0"
    private const val MAX_ANISKIP_QUERY_CANDIDATES = 4
    private const val MAX_ANISKIP_SEARCH_RESULTS = 10
    private const val ANISKIP_MATCH_THRESHOLD = 60
    private const val TMDB_SEARCH_URL = "https://sub.wyzie.io/api/tmdb/search"
    private const val TMDB_EXTERNAL_IDS_BASE = "https://db.videasy.net/3"
    private val imdbIdRegex = Regex("""tt\d{7,9}""", RegexOption.IGNORE_CASE)
    private val myAnimeListUrlRegex = Regex("""myanimelist\.net/anime/(\d+)""", RegexOption.IGNORE_CASE)
    private val malIdRegex = Regex("""\d{3,8}""")
    private val seasonNumberRegex = Regex("""\bseason\s*(\d+)\b""")
    private val ordinalSeasonRegex = Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b""")
  }
}
