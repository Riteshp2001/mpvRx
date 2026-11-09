/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.preferences.IntroSegmentProvider
import app.gyrolet.mpvrx.utils.media.MediaInfoParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

enum class IntroDbResolutionSource {
  DIRECT_IMDB,
  DIRECT_MAL,
  EXPLICIT_TMDB,
  MAL_SEARCH,
  ANIME_SKIP_SEARCH,
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
  val durationSeconds: Double? = null,
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
    get() = startSec ?: start

  val endSecondsOrNull: Double?
    get() = endSec ?: end

  val normalizedStart: Double
    get() = startSecondsOrNull ?: 0.0

  val normalizedEnd: Double
    get() = endSecondsOrNull ?: 0.0

  val hasTimingBounds: Boolean
    get() = startSecondsOrNull != null || endSecondsOrNull != null

  internal fun validatedForDuration(durationSeconds: Double?): IntroDbSegment? {
    val type =
      when (segmentType?.trim()?.lowercase()) {
        null, "intro", "opening", "op" -> "intro"
        "recap", "summary" -> "recap"
        "outro", "ending", "ed" -> "outro"
        "credit", "credits" -> "credits"
        "preview", "next", "next episode preview" -> "preview"
        else -> return null
      }
    val startsAtBeginning = type == "intro" || type == "recap"
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
    if (!startsAtBeginning && startSecondsOrNull == null) return null
    if (startsAtBeginning && endSecondsOrNull == null) return null
    val start = startSecondsOrNull ?: 0.0
    val end = endSecondsOrNull ?: duration ?: return null
    if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start) return null
    if (duration != null && (start >= duration || end > duration + 2.0)) return null
    return IntroDbSegment(
      segmentType = type,
      start = start,
      end = if (duration != null) end.coerceAtMost(duration) else end,
    )
  }
}

@Serializable
private data class IntroDbTmdbSearchResult(
  val id: Int,
  val mediaType: String,
  val title: String,
  val originalTitle: String? = null,
  val releaseYear: String? = null,
)

@Serializable
private data class IntroDbTmdbSearchResponse(
  val results: List<IntroDbTmdbSearchResult> = emptyList(),
)

@Serializable
private data class AniSkipLookupResponse(
  val found: Boolean = false,
  val results: List<AniSkipLookupResult> = emptyList(),
)

@Serializable
private data class AniSkipLookupResult(
  val interval: AniSkipInterval? = null,
  val skipType: String? = null,
)

@Serializable
private data class AniSkipInterval(
  val startTime: Double? = null,
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

@Serializable
private data class AnimeSkipGraphqlResponse(
  val data: AnimeSkipData? = null,
  val errors: List<AnimeSkipGraphqlError> = emptyList(),
)

@Serializable
private data class AnimeSkipGraphqlError(
  val message: String,
)

private data class AnimeSkipSegmentMatch(
  val episodeId: String,
  val segments: List<IntroDbSegment>,
)

@Serializable
private data class AnimeSkipData(
  @SerialName("searchShows")
  val searchShows: List<AnimeSkipShow>? = null,
)

@Serializable
private data class AnimeSkipShow(
  val id: String,
  val name: String,
  val episodes: List<AnimeSkipEpisode> = emptyList(),
)

@Serializable
private data class AnimeSkipEpisode(
  val id: String,
  val season: String? = null,
  val number: String? = null,
  val baseDuration: Double? = null,
  val timestamps: List<AnimeSkipTimestamp> = emptyList(),
)

@Serializable
private data class AnimeSkipTimestamp(
  val at: Double? = null,
  val type: AnimeSkipTimestampType? = null,
)

@Serializable
private data class AnimeSkipTimestampType(
  val name: String? = null,
)

class IntroDbRepository(
  client: OkHttpClient,
  private val json: Json,
  private val context: Context,
) {
  private val client = client.newBuilder().callTimeout(PROVIDER_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()

  suspend fun lookupSegments(request: IntroDbLookupRequest): IntroDbLookupOutcome =
    withContext(Dispatchers.IO) {
      runCatching {
        val titleForLookup = request.canonicalTitle?.takeIf { it.isNotBlank() } ?: request.mediaTitle
        val parsed = MediaInfoParser.parseForLookup(context, request.mediaTitle)
        val normalizedTitle = (request.canonicalTitle?.takeIf { it.isNotBlank() } ?: parsed.title).trim()
        val effectiveMediaType =
          when ((request.mediaType ?: parsed.type).lowercase()) {
            "series", "tv" -> "tv"
            else -> "movie"
          }
        val effectiveSeason = request.season ?: parsed.season
        val effectiveEpisode = request.episode ?: parsed.episode
        if (normalizedTitle.isBlank() || parsed.episodeEnd != null || parsed.isEpisodeAmbiguous ||
          (effectiveMediaType == "tv" && (effectiveSeason == null || effectiveEpisode == null || effectiveEpisode <= 0))
        ) {
          return@runCatching IntroDbLookupOutcome.Unresolved(
            title = titleForLookup,
            provider = request.provider,
          )
        }

        val outcome =
          when (request.provider) {
            IntroSegmentProvider.INTRO_DB, IntroSegmentProvider.SKIP_DB ->
              lookupViaImdbProvider(
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

            IntroSegmentProvider.ANIME_SKIP ->
              lookupViaAnimeSkip(
                request = request,
                normalizedTitle = normalizedTitle,
                season = effectiveSeason,
                episode = effectiveEpisode,
              )

            IntroSegmentProvider.HYBRID ->
              lookupHybridSegments(request)
          }
        if (outcome is IntroDbLookupOutcome.Loaded) {
          val segments = outcome.segments.mapNotNull { it.validatedForDuration(request.durationSeconds) }.distinct()
          if (segments.isEmpty()) {
            IntroDbLookupOutcome.NoSegments(outcome.imdbId, outcome.source, outcome.provider)
          } else {
            outcome.copy(segments = segments)
          }
        } else {
          outcome
        }
      }.getOrElse { error ->
        if (error is CancellationException) throw error
        Log.w(TAG, "Online marker lookup failed for ${request.mediaTitle}", error)
        IntroDbLookupOutcome.Error(
          reason = error.message ?: "unknown error",
          provider = request.provider,
        )
      }
    }

  private suspend fun lookupHybridSegments(request: IntroDbLookupRequest): IntroDbLookupOutcome =
    coroutineScope {
      val providers = IntroSegmentProvider.entries.filter { it != IntroSegmentProvider.HYBRID }
      val results = Channel<IntroDbLookupOutcome>(providers.size)
      val jobs =
        providers.map { provider ->
          launch {
            val result =
              withTimeoutOrNull(PROVIDER_LOOKUP_TIMEOUT_MS) {
                lookupSegments(request.copy(provider = provider))
              } ?: IntroDbLookupOutcome.Error("${provider.displayName} timed out", provider)
            results.send(result)
          }
        }
      var noSegments: IntroDbLookupOutcome.NoSegments? = null
      var failure: IntroDbLookupOutcome.Error? = null
      val loaded = mutableListOf<IntroDbLookupOutcome.Loaded>()
      try {
        repeat(providers.size) {
          when (val outcome = results.receive()) {
            is IntroDbLookupOutcome.Loaded -> loaded += outcome
            is IntroDbLookupOutcome.NoSegments -> if (noSegments == null) noSegments = outcome
            is IntroDbLookupOutcome.Error -> if (failure == null) failure = outcome
            is IntroDbLookupOutcome.Unresolved -> Unit
          }
        }
        combineHybridSegments(loaded)
          ?: noSegments?.copy(provider = IntroSegmentProvider.HYBRID)
          ?: failure?.copy(provider = IntroSegmentProvider.HYBRID)
          ?: IntroDbLookupOutcome.Unresolved(request.mediaTitle, IntroSegmentProvider.HYBRID)
      } finally {
        jobs.forEach { it.cancel() }
        results.cancel()
      }
    }

  suspend fun getIntroDbAppSegments(
    imdbId: String,
    season: Int? = null,
    episode: Int? = null,
  ): Result<List<IntroDbSegment>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val urlBuilder =
          "https://api.introdb.app/segments"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("imdb_id", imdbId)

        if (season != null) urlBuilder.addQueryParameter("season", season.toString())
        if (episode != null) urlBuilder.addQueryParameter("episode", episode.toString())
        if (season == null && episode == null) urlBuilder.addQueryParameter("is_movie", "true")

        val request =
          Request
            .Builder()
            .url(urlBuilder.build())
            .header("User-Agent", MARKER_PROVIDER_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        client
          .newCall(request)
          .awaitResponse()
          .use { response ->
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

  private suspend fun getSkipDbSegments(
    imdbId: String,
    season: Int?,
    episode: Int?,
    durationSeconds: Double?,
  ): Result<List<IntroDbSegment>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val url =
          SKIPDB_SEGMENTS_URL.toHttpUrl().newBuilder()
            .addQueryParameter("imdb_id", imdbId)
            .addQueryParameter("adjust", "conservative")
            .apply {
              if (season != null) addQueryParameter("season", season.toString())
              if (episode != null) addQueryParameter("episode", episode.toString())
              durationSeconds?.takeIf { it.isFinite() && it > 0.0 }?.let { duration ->
                addQueryParameter("duration", duration.toString())
              }
            }.build()
        val request =
          Request.Builder()
            .url(url)
            .header("User-Agent", MARKER_PROVIDER_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).awaitResponse().use { response ->
          if (response.code == 404) return@use emptyList()
          check(response.isSuccessful) { "SkipDB request failed with HTTP ${response.code}" }
          val payload = json.parseToJsonElement(response.body.string()).jsonObject
          val segments = payload["segments"] as? JsonObject ?: error("SkipDB response is missing segments")
          listOf("intro", "recap", "outro", "preview").mapNotNull { type ->
            val rawSegment = segments[type] as? JsonObject ?: return@mapNotNull null
            if ((rawSegment["match"] as? JsonPrimitive)?.content == "out-of-range") return@mapNotNull null
            val segment = rawSegment.toIntroDbSegment(type) ?: return@mapNotNull null
            val start = segment.startSecondsOrNull ?: return@mapNotNull null
            val end = segment.endSecondsOrNull ?: return@mapNotNull null
            segment.takeIf { start.isFinite() && end.isFinite() && end > start }
          }
        }
      }
    }

  suspend fun getTheIntroDbSegments(
    tmdbId: Int? = null,
    imdbId: String? = null,
    mediaType: String,
    season: Int? = null,
    episode: Int? = null,
    durationSeconds: Double? = null,
  ): Result<List<IntroDbSegment>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val urlBuilder = THEINTRODB_MEDIA_URL.toHttpUrl().newBuilder()
        when {
          tmdbId != null && tmdbId > 0 -> urlBuilder.addQueryParameter("tmdb_id", tmdbId.toString())
          !imdbId.isNullOrBlank() -> urlBuilder.addQueryParameter("imdb_id", imdbId)
          else -> error("TheIntroDB lookup requires a TMDB or IMDb id")
        }
        if (mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)) {
          require(season != null && season > 0 && episode != null && episode > 0) {
            "TheIntroDB episode lookup requires a season and episode"
          }
          urlBuilder.addQueryParameter("season", season.toString())
          urlBuilder.addQueryParameter("episode", episode.toString())
        }
        durationSeconds?.takeIf { it.isFinite() && it > 0.0 }?.let { duration ->
          urlBuilder.addQueryParameter("duration_ms", (duration * 1000.0).roundToLong().toString())
        }
        val request =
          Request
            .Builder()
            .url(urlBuilder.build())
            .header("User-Agent", MARKER_PROVIDER_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).awaitResponse().use { response ->
          if (response.code == 404) return@use emptyList()
          check(response.isSuccessful) { "TheIntroDB request failed with HTTP ${response.code}" }
          parseTheIntroDbMediaBody(response.body.string())
        }
      }.onFailure { error ->
        Log.w(TAG, "Failed to fetch TheIntroDB data for tmdbId=$tmdbId imdbId=$imdbId", error)
      }
    }

  suspend fun getAniSkipSegments(
    malId: Int,
    episode: Int,
    durationSeconds: Double? = null,
  ): Result<List<IntroDbSegment>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val url =
          "$ANISKIP_SKIP_TIMES_URL/$malId/$episode"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("types", "op")
            .addQueryParameter("types", "ed")
            .addQueryParameter("types", "mixed-op")
            .addQueryParameter("types", "mixed-ed")
            .addQueryParameter("types", "recap")
            .addQueryParameter(
              "episodeLength",
              durationSeconds?.takeIf { it.isFinite() && it > 0.0 }?.toString() ?: "0",
            ).build()
        val request =
          Request
            .Builder()
            .url(url)
            .header("User-Agent", MARKER_PROVIDER_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        client
          .newCall(request)
          .awaitResponse()
          .use { response ->
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
              val segmentType =
                when (result.skipType) {
                  "recap" -> "recap"
                  "ed", "mixed-ed" -> "ending"
                  "op", "mixed-op" -> "opening"
                  else -> return@mapNotNull null
                }
              IntroDbSegment(
                segmentType = segmentType,
                start = start,
                end = end,
              )
            }
          }.filter { it.hasTimingBounds }
      }.onFailure { error ->
        Log.w(TAG, "Failed to fetch AniSkip data for malId=$malId episode=$episode", error)
      }
    }

  private suspend fun getAnimeSkipSegments(
    showName: String,
    season: Int?,
    episode: Int?,
    durationSeconds: Double?,
  ): Result<AnimeSkipSegmentMatch?> =
    withContext(Dispatchers.IO) {
      runCatching {
        val searchArg = json.encodeToString(JsonPrimitive(showName))
        val gqlQuery =
          "{searchShows(search: $searchArg, limit: 3) {id name episodes " +
            "{id season number baseDuration timestamps {at type {name}}}}}"
        val requestBody =
          json
            .encodeToString(
              JsonObject(mapOf("query" to JsonPrimitive(gqlQuery))),
            ).toRequestBody(JSON_MEDIA_TYPE)

        val request =
          Request
            .Builder()
            .url(ANIME_SKIP_GRAPHQL_URL)
            .header("Content-Type", "application/json")
            .header("User-Agent", MARKER_PROVIDER_USER_AGENT)
            .header("X-Client-ID", ANIME_SKIP_CLIENT_ID)
            .post(requestBody)
            .build()

        val responseBody =
          client.newCall(request).awaitResponse().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
              val details = body.take(300)
              Log.w(TAG, "Anime Skip API returned HTTP ${response.code}: $details")
              error("Anime Skip request failed with HTTP ${response.code}: $details")
            }
            body
          }

        val payload = json.decodeFromString<AnimeSkipGraphqlResponse>(responseBody)
        if (payload.errors.isNotEmpty()) {
          error(payload.errors.joinToString("; ") { it.message })
        }
        val data = checkNotNull(payload.data) { "Anime Skip response is missing data" }
        val shows = data.searchShows.orEmpty()
        if (shows.isEmpty()) return@runCatching null

        val normalizedSearch = normalizeTitle(showName)
        val rankedShows =
          shows.map { show -> show to scoreNormalizedTitleMatch(normalizedSearch, normalizeTitle(show.name)) }
        val bestScore = rankedShows.maxOfOrNull { it.second } ?: return@runCatching null
        val bestShow =
          rankedShows.singleOrNull { it.second == bestScore && bestScore >= ANISKIP_MATCH_THRESHOLD }?.first
            ?: return@runCatching null

        val expectedEpisode = episode?.toDouble() ?: return@runCatching null
        val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
        val episodeMatches =
          bestShow.episodes.filter { candidate ->
            val baseDuration = candidate.baseDuration?.takeIf { it.isFinite() && it > 0.0 }
            candidate.number?.toDoubleOrNull() == expectedEpisode &&
              (duration == null || baseDuration == null ||
                abs(duration - baseDuration) <= ANIME_SKIP_DURATION_TOLERANCE_SECONDS)
          }
        val seasonMatches =
          if (season == null) {
            episodeMatches
          } else {
            episodeMatches.filter { it.season?.toDoubleOrNull() == season.toDouble() }.ifEmpty {
              if (season == 1 && bestShow.episodes.all { it.season.isNullOrBlank() }) episodeMatches else emptyList()
            }
          }
        val matchingEpisode =
          seasonMatches.singleOrNull() ?: return@runCatching null

        val timestamps =
          matchingEpisode
            .timestamps
            .filter { it.at?.isFinite() == true && it.type?.name != null }
            .sortedBy { it.at }

        AnimeSkipSegmentMatch(matchingEpisode.id, buildAnimeSkipSegments(timestamps))
      }.onFailure { error ->
        Log.w(TAG, "Failed to fetch Anime Skip data for $showName S${season}E$episode", error)
      }
    }

  private fun buildAnimeSkipSegments(timestamps: List<AnimeSkipTimestamp>): List<IntroDbSegment> {
    val segments = mutableListOf<IntroDbSegment>()
    for (index in timestamps.indices) {
      val current = timestamps[index]
      val typeName = current.type?.name?.trim()?.lowercase().orEmpty()
      val segmentType =
        when (typeName) {
          "recap", "summary" -> "recap"
          "opening", "intro", "op", "new intro" -> "opening"
          "ending", "outro", "ed" -> "ending"
          "credit", "credits", "new credits" -> "credits"
          "preview", "next", "next episode preview" -> "preview"
          else -> continue
        }
      val start = current.at ?: continue
      val end = timestamps.getOrNull(index + 1)?.at
      if (end == null && segmentType !in setOf("ending", "credits", "preview")) continue
      segments.add(IntroDbSegment(segmentType = segmentType, start = start, end = end))
    }
    return segments
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

  private fun parseObjectSegments(body: String): List<IntroDbSegment> {
    val payload = json.parseToJsonElement(body).jsonObject
    return payload.entries.mapNotNull { (segmentType, value) ->
      val segmentPayload = value as? JsonObject ?: return@mapNotNull null
      segmentPayload.toIntroDbSegment(segmentType)
    }.ifEmpty { payload.toLegacyIntroDbSegments() }
  }

  private fun parseTheIntroDbMediaBody(body: String): List<IntroDbSegment> {
    val payload = json.parseToJsonElement(body).jsonObject
    check(payload["tvdb_id"] is JsonPrimitive && payload["type"] is JsonPrimitive) {
      "Invalid TheIntroDB media response"
    }
    return listOf("intro", "recap", "credits", "preview").flatMap { type ->
      val value = payload[type] ?: return@flatMap emptyList()
      check(value is JsonArray) { "Invalid TheIntroDB $type collection" }
      value.mapNotNull { element ->
        check(element is JsonObject) { "Invalid TheIntroDB $type marker" }
        element.toIntroDbSegment(type)
      }
    }
  }

  private fun JsonObject.toLegacyIntroDbSegments(): List<IntroDbSegment> {
    val start = this["start"]?.jsonPrimitive?.doubleOrNull
    val end = this["end"]?.jsonPrimitive?.doubleOrNull
    return if (start != null || end != null) {
      listOf(IntroDbSegment(segmentType = "intro", start = start, end = end))
    } else {
      emptyList()
    }
  }

  private fun JsonObject.toIntroDbSegment(segmentType: String): IntroDbSegment? {
    val type =
      (this["segment_type"] as? JsonPrimitive)?.content
        ?: (this["type"] as? JsonPrimitive)?.content
        ?: (this["category"] as? JsonPrimitive)?.content
        ?: (this["skip_type"] as? JsonPrimitive)?.content
        ?: segmentType

    val start =
      this["start_sec"]?.jsonPrimitive?.doubleOrNull
        ?: this["start"]?.jsonPrimitive?.doubleOrNull
        ?: this["start_time"]?.jsonPrimitive?.doubleOrNull
        ?: this["startTime"]?.jsonPrimitive?.doubleOrNull
        ?: this["start_ms"]?.jsonPrimitive?.doubleOrNull?.div(1000.0)
    val end =
      this["end_sec"]?.jsonPrimitive?.doubleOrNull
        ?: this["end"]?.jsonPrimitive?.doubleOrNull
        ?: this["end_time"]?.jsonPrimitive?.doubleOrNull
        ?: this["endTime"]?.jsonPrimitive?.doubleOrNull
        ?: this["end_ms"]?.jsonPrimitive?.doubleOrNull?.div(1000.0)
    return if (start != null || end != null) {
      IntroDbSegment(segmentType = type, start = start, end = end)
    } else {
      null
    }
  }

  private suspend fun lookupViaImdbProvider(
    request: IntroDbLookupRequest,
    normalizedTitle: String,
    parsedYear: String?,
    mediaType: String,
    season: Int?,
    episode: Int?,
  ): IntroDbLookupOutcome {
    request.imdbId?.takeIf { it.isNotBlank() }?.let { imdbId ->
      return fetchSegmentsForResolvedId(
        request = request,
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
        request = request,
        lookupId = imdbId,
        imdbId = imdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.DIRECT_IMDB,
      )
    }

    if (request.provider == IntroSegmentProvider.SKIP_DB) {
      return IntroDbLookupOutcome.Unresolved(title = normalizedTitle, provider = request.provider)
    }

    request.tmdbId?.let { tmdbId ->
      return fetchSegmentsForResolvedId(
        request = request,
        lookupId = "tmdb:$tmdbId",
        tmdbId = tmdbId,
        imdbId = null,
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.EXPLICIT_TMDB,
      )
    }

    val match = searchTmdb(normalizedTitle, parsedYear, mediaType)
    if (match != null) {
      return fetchSegmentsForResolvedId(
        request = request,
        lookupId = "tmdb:${match.id}",
        tmdbId = match.id,
        imdbId = null,
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.TMDB_SEARCH,
      )
    }

    return IntroDbLookupOutcome.Unresolved(
      title = normalizedTitle,
      provider = request.provider,
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
        request = request,
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
        request = request,
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
        request = request,
        lookupId = imdbId,
        imdbId = imdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        source = IntroDbResolutionSource.DIRECT_IMDB,
      )
    }

    val match =
      searchTmdb(normalizedTitle, parsedYear, mediaType)
        ?: return IntroDbLookupOutcome.Unresolved(
          title = normalizedTitle,
          provider = request.provider,
        )

    return fetchSegmentsForResolvedId(
      request = request,
      lookupId = "tmdb:${match.id}",
      tmdbId = match.id,
      imdbId = null,
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
        request = request,
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
      request = request,
      lookupId = "MAL ${match.malId}",
      malId = match.malId,
      mediaType = "tv",
      season = season,
      episode = episode,
      source = IntroDbResolutionSource.MAL_SEARCH,
    )
  }

  private suspend fun lookupViaAnimeSkip(
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

    val searchTitle = request.canonicalTitle?.takeIf { it.isNotBlank() } ?: normalizedTitle
    val match =
      getAnimeSkipSegments(searchTitle, season, episode, request.durationSeconds).getOrElse { error ->
        if (error is CancellationException) throw error
        return IntroDbLookupOutcome.Error(
          reason = error.message ?: "Anime Skip request failed",
          provider = request.provider,
        )
      } ?: return IntroDbLookupOutcome.Unresolved(title = searchTitle, provider = request.provider)

    val lookupId = "anime-skip:${match.episodeId}"
    return if (match.segments.isEmpty()) {
      IntroDbLookupOutcome.NoSegments(
        imdbId = lookupId,
        source = IntroDbResolutionSource.ANIME_SKIP_SEARCH,
        provider = request.provider,
      )
    } else {
      IntroDbLookupOutcome.Loaded(
        imdbId = lookupId,
        segments = match.segments,
        source = IntroDbResolutionSource.ANIME_SKIP_SEARCH,
        provider = request.provider,
      )
    }
  }

  private suspend fun fetchSegmentsForResolvedId(
    request: IntroDbLookupRequest,
    lookupId: String,
    imdbId: String? = null,
    tmdbId: Int? = null,
    malId: Int? = null,
    mediaType: String,
    season: Int?,
    episode: Int?,
    source: IntroDbResolutionSource,
  ): IntroDbLookupOutcome {
    val provider = request.provider
    val durationSeconds = request.durationSeconds
    val useEpisodeHints = mediaType.equals("tv", ignoreCase = true)
    val segments =
      when (provider) {
        IntroSegmentProvider.INTRO_DB ->
          if (!imdbId.isNullOrBlank()) {
            getIntroDbAppSegments(
              imdbId = imdbId,
              season = season.takeIf { useEpisodeHints },
              episode = episode.takeIf { useEpisodeHints },
            )
          } else if (tmdbId != null && tmdbId > 0) {
            getTheIntroDbSegments(
              tmdbId = tmdbId,
              imdbId = null,
              mediaType = mediaType,
              season = season.takeIf { useEpisodeHints },
              episode = episode.takeIf { useEpisodeHints },
              durationSeconds = durationSeconds,
            )
          } else {
            Result.failure(IllegalArgumentException("IntroDB lookup requires an IMDb or TMDB ID"))
          }

        IntroSegmentProvider.THE_INTRO_DB ->
          getTheIntroDbSegments(
            tmdbId = tmdbId,
            imdbId = imdbId,
            mediaType = mediaType,
            season = season.takeIf { useEpisodeHints },
            episode = episode.takeIf { useEpisodeHints },
            durationSeconds = durationSeconds,
          )

        IntroSegmentProvider.SKIP_DB ->
          if (!imdbId.isNullOrBlank()) {
            getSkipDbSegments(
              imdbId = imdbId,
              season = season.takeIf { useEpisodeHints },
              episode = episode.takeIf { useEpisodeHints },
              durationSeconds = durationSeconds,
            )
          } else {
            return IntroDbLookupOutcome.Unresolved(title = request.mediaTitle, provider = provider)
          }

        IntroSegmentProvider.ANI_SKIP ->
          getAniSkipSegments(
            malId = malId ?: error("AniSkip lookup requires a MAL id"),
            episode = episode.takeIf { useEpisodeHints } ?: error("AniSkip lookup requires an episode number"),
            durationSeconds = durationSeconds,
          )

        IntroSegmentProvider.ANIME_SKIP ->
          Result.failure(IllegalArgumentException("Anime Skip provider does not use fetchSegmentsForResolvedId"))

        IntroSegmentProvider.HYBRID ->
          Result.failure(IllegalArgumentException("Hybrid provider cannot be resolved sequentially"))
      }.getOrElse { error ->
        throw error
      }

    val resolvedProvider =
      if (provider == IntroSegmentProvider.INTRO_DB && imdbId.isNullOrBlank()) {
        IntroSegmentProvider.THE_INTRO_DB
      } else {
        provider
      }
    return if (segments.isEmpty()) {
      IntroDbLookupOutcome.NoSegments(imdbId = lookupId, source = source, provider = resolvedProvider)
    } else {
      IntroDbLookupOutcome.Loaded(
        imdbId = lookupId,
        segments = segments,
        source = source,
        provider = resolvedProvider,
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
        myAnimeListUrlRegex
          .find(source)
          ?.groupValues
          ?.getOrNull(1)
          ?.toIntOrNull()
          ?: source.trim().takeIf { it.matches(malIdRegex) }?.toIntOrNull()
      }

  private suspend fun buildAniSkipQueryCandidates(
    request: IntroDbLookupRequest,
    normalizedTitle: String,
    season: Int?,
  ): List<String> {
    val baseTitles =
      buildList {
        add(request.canonicalTitle)
        add(normalizedTitle)
        add(MediaInfoParser.parseForLookup(context, request.mediaTitle).title)
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
    var searchFailure: Exception? = null
    val searchStartedAt = System.nanoTime()
    for (query in queryCandidates.take(MAX_ANISKIP_QUERY_CANDIDATES)) {
      try {
        val remainingMillis =
          PROVIDER_LOOKUP_TIMEOUT_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - searchStartedAt)
        val results = withTimeoutOrNull(remainingMillis.coerceAtLeast(0L)) { searchJikanAnime(query) }
        if (results == null) {
          Log.d(TAG, "AniSkip title search timed out; using any partial matches")
          break
        }
        results.forEach { result ->
          aggregatedResults.putIfAbsent(result.malId, result)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        searchFailure = error
        Log.w(TAG, "AniSkip title search failed; checking remaining candidates", error)
      }
    }

    val match = pickBestAniSkipMatch(
      results = aggregatedResults.values.toList(),
      queryCandidates = queryCandidates,
      expectedSeason = expectedSeason,
    )?.first
    if (match == null) searchFailure?.let { throw it }
    return match
  }

  private suspend fun searchJikanAnime(query: String): List<JikanAnimeSearchResult> {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val request =
      Request
        .Builder()
        .url("$JIKAN_SEARCH_URL?q=$encodedQuery&limit=$MAX_ANISKIP_SEARCH_RESULTS")
        .header("User-Agent", MARKER_PROVIDER_USER_AGENT)
        .get()
        .build()

    repeat(2) { attempt ->
      val results = client.newCall(request).awaitResponse().use { response ->
        if (attempt == 0 && response.code in setOf(502, 503, 504)) return@use null
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
      if (results != null) return results
      delay(500L)
    }
    error("AniSkip MAL search did not complete")
  }

  private fun pickBestAniSkipMatch(
    results: List<JikanAnimeSearchResult>,
    queryCandidates: List<String>,
    expectedSeason: Int?,
  ): Pair<JikanAnimeSearchResult, Int>? {
    if (results.isEmpty()) return null

    val ranked =
      results
        .map { result ->
          result to buildAniSkipScore(result, queryCandidates, expectedSeason)
        }.sortedByDescending { it.second }
    val bestScore = ranked.firstOrNull()?.second ?: return null
    if (bestScore < ANISKIP_MATCH_THRESHOLD) return null
    return ranked.filter { it.second == bestScore }.distinctBy { it.first.malId }.singleOrNull()
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
    if (score < ANISKIP_MATCH_THRESHOLD) return Int.MIN_VALUE

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
    if (candidate.startsWith("$query ") || query.startsWith("$candidate ")) return 92
    if (" $candidate ".contains(" $query ") || " $query ".contains(" $candidate ")) return 70

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

  private fun containsAniSkipPenaltyMarker(normalizedTitle: String): Boolean =
    ANISKIP_PENALTY_MARKERS.containsMatchIn(normalizedTitle)

  private fun extractSeasonNumber(normalizedTitle: String): Int? {
    val directSeason =
      seasonNumberRegex
        .find(normalizedTitle)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: ordinalSeasonRegex
          .find(normalizedTitle)
          ?.groupValues
          ?.getOrNull(1)
          ?.toIntOrNull()
    if (directSeason != null) return directSeason

    return normalizedTitle
      .takeIf { " second season" in it }
      ?.let { 2 }
      ?: normalizedTitle.takeIf { " third season" in it }?.let { 3 }
      ?: normalizedTitle.takeIf { " fourth season" in it }?.let { 4 }
  }

  private fun ordinalSeason(season: Int): String =
    when {
      season % 100 in 11..13 -> "${season}th"
      season % 10 == 1 -> "${season}st"
      season % 10 == 2 -> "${season}nd"
      season % 10 == 3 -> "${season}rd"
      else -> "${season}th"
    }

  private suspend fun searchTmdb(
    title: String,
    year: String?,
    mediaType: String,
  ): IntroDbTmdbSearchResult? {
    val startedAt = System.nanoTime()
    for (language in titleSearchLanguages(title)) {
      val remainingMillis = PROVIDER_LOOKUP_TIMEOUT_MS -
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
      if (remainingMillis <= 0L) break
      val url = TMDB_SEARCH_URL.toHttpUrl().newBuilder()
        .addQueryParameter("q", title)
        .addQueryParameter("language", language)
        .build()
      val request = Request.Builder().url(url)
        .header("User-Agent", MARKER_PROVIDER_USER_AGENT)
        .header("Accept", "application/json")
        .get().build()
      val results = withTimeoutOrNull(remainingMillis) {
        client.newCall(request).awaitResponse().use { response ->
          check(response.isSuccessful) { "TMDB search failed with HTTP ${response.code}" }
          val body = response.body.string()
          if (body.isBlank()) emptyList() else json.decodeFromString<IntroDbTmdbSearchResponse>(body).results
        }
      } ?: break
      pickBestTmdbMatch(results, title, year, mediaType)?.let { return it }
    }
    return null
  }

  private fun titleSearchLanguages(title: String): List<String> {
    val scripts = title.map { Character.UnicodeScript.of(it.code) }.toSet()
    val configuredLanguage = context.resources.configuration.locales[0].toLanguageTag()
    val detectedLanguages = when {
      Character.UnicodeScript.HIRAGANA in scripts || Character.UnicodeScript.KATAKANA in scripts -> listOf("ja-JP", "zh-CN")
      Character.UnicodeScript.HANGUL in scripts -> listOf("ko-KR")
      Character.UnicodeScript.HAN in scripts -> listOf("zh-CN", "ja-JP")
      Character.UnicodeScript.ARABIC in scripts -> listOf("ar-SA")
      Character.UnicodeScript.DEVANAGARI in scripts -> listOf("hi-IN")
      Character.UnicodeScript.CYRILLIC in scripts -> listOf("ru-RU", "uk-UA")
      else -> emptyList()
    }
    val fallbackLanguages = if (detectedLanguages.isEmpty()) {
      listOf("en-US", "es-ES", "fr-FR", "de-DE", "pt-BR")
    } else listOf("en-US")
    return (detectedLanguages + configuredLanguage + fallbackLanguages).distinct().take(6)
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
          result to
            buildTmdbScore(
              result = result,
              normalizedTitle = normalizedTitle,
              expectedYear = year,
              expectedMediaType = mediaType,
            )
        }.sortedByDescending { it.second }
        .firstOrNull()
        ?: return null

    val bestScore = preferred.second
    if (bestScore < 90) return null
    val tiedMatches = results.filter {
      buildTmdbScore(it, normalizedTitle, year, mediaType) == bestScore
    }.distinctBy(IntroDbTmdbSearchResult::id)
    return tiedMatches.singleOrNull()
  }

  private fun buildTmdbScore(
    result: IntroDbTmdbSearchResult,
    normalizedTitle: String,
    expectedYear: String?,
    expectedMediaType: String,
  ): Int {
    val titleScore = listOfNotNull(result.title, result.originalTitle)
      .maxOf { candidate -> scoreNormalizedTitleMatch(normalizedTitle, normalizeTitle(candidate)) }
    if (titleScore < 60 || !result.mediaType.equals(expectedMediaType, ignoreCase = true)) return Int.MIN_VALUE
    var score = titleScore + 30
    if (expectedYear != null && result.releaseYear == expectedYear) {
      score += 25
    }

    return score
  }

  private fun normalizeTitle(title: String): String =
    Normalizer.normalize(
      Normalizer.normalize(title, Normalizer.Form.NFKD).replace(LATIN_TITLE_DIACRITICS, ""),
      Normalizer.Form.NFKC,
    )
      .lowercase(Locale.ROOT)
      .replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), " ")
      .trim()

  companion object {
    private const val TAG = "IntroDbRepository"
    private const val PROVIDER_LOOKUP_TIMEOUT_MS = 8_000L
    private const val THEINTRODB_MEDIA_URL = "https://api.theintrodb.org/v4/media"
    private const val SKIPDB_SEGMENTS_URL = "https://api.skipdb.tv/api/segments"
    private const val ANISKIP_SKIP_TIMES_URL = "https://api.aniskip.com/v2/skip-times"
    private const val JIKAN_SEARCH_URL = "https://api.jikan.moe/v4/anime"
    private const val MARKER_PROVIDER_USER_AGENT =
      "Mozilla/5.0 (Windows NT 6.1; Win64; rv:109.0) Gecko/20100101 Firefox/109.0"
    private const val MAX_ANISKIP_QUERY_CANDIDATES = 4
    private const val MAX_ANISKIP_SEARCH_RESULTS = 10
    private const val ANISKIP_MATCH_THRESHOLD = 60
    private const val TMDB_SEARCH_URL = "https://sub.wyzie.io/api/tmdb/search"
    private const val ANIME_SKIP_GRAPHQL_URL = "https://api.anime-skip.com/graphql"
    private const val ANIME_SKIP_DURATION_TOLERANCE_SECONDS = 2.0
    private const val ANIME_SKIP_CLIENT_ID = "ZGfO0sMF3eCwLYf8yMSCJjlynwNGRXWE"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val imdbIdRegex = Regex("""tt\d{7,9}""", RegexOption.IGNORE_CASE)
    private val myAnimeListUrlRegex = Regex("""myanimelist\.net/anime/(\d+)""", RegexOption.IGNORE_CASE)
    private val malIdRegex = Regex("""\d{3,8}""")
    private val seasonNumberRegex = Regex("""\bseason\s*(\d+)\b""")
    private val ordinalSeasonRegex = Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b""")
    private val LATIN_TITLE_DIACRITICS = Regex("""(?<=\p{IsLatin})\p{M}+""")
    private val ANISKIP_PENALTY_MARKERS = Regex("""(?:^|\s)(?:recap|summary|digest|compilation)(?:\s|$)""")

    internal fun combineHybridSegments(outcomes: List<IntroDbLookupOutcome.Loaded>): IntroDbLookupOutcome.Loaded? {
      val priority = listOf(
        IntroSegmentProvider.THE_INTRO_DB,
        IntroSegmentProvider.SKIP_DB,
        IntroSegmentProvider.INTRO_DB,
        IntroSegmentProvider.ANI_SKIP,
        IntroSegmentProvider.ANIME_SKIP,
      )
      val ordered = outcomes.sortedWith(
        compareByDescending<IntroDbLookupOutcome.Loaded> { it.source == IntroDbResolutionSource.DIRECT_MAL }
          .thenBy { priority.indexOf(it.provider).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
      )
      val segmentsByCategory = linkedMapOf<String, List<IntroDbSegment>>()
      ordered.forEach { outcome ->
        outcome.segments.groupBy { segment ->
          when (segment.segmentType) {
            "intro", "recap", "preview" -> segment.segmentType
            "outro", "credits" -> "ending"
            else -> null
          }
        }.forEach { (category, segments) ->
          if (category != null) segmentsByCategory.putIfAbsent(category, segments)
        }
      }
      val first = ordered.firstOrNull() ?: return null
      val segments = segmentsByCategory.values.flatten().distinct().sortedBy(IntroDbSegment::normalizedStart)
      return if (segments.isEmpty()) null else first.copy(segments = segments, provider = IntroSegmentProvider.HYBRID)
    }
  }
}
