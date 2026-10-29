/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitlehub

import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitle
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchRequest
import app.gyrolet.mpvrx.repository.subtitle.SUBDL_GROUP_EPISODE_END_KEY
import app.gyrolet.mpvrx.repository.subtitle.SUBDL_GROUP_EPISODE_START_KEY
import app.gyrolet.mpvrx.repository.subtitle.SubtitleProvider
import app.gyrolet.mpvrx.repository.wyzie.WyzieLanguages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal const val AUTHENTICATED_SOURCE_METADATA_KEY = "subtitleHubAuthenticatedSource"

internal class MpvRxSubtitleHubApiSources(
  private val client: OkHttpClient,
  private val json: Json,
  private val preferences: SubtitlesPreferences,
) {
  private val noRedirectClient =
    client
      .newBuilder()
      .followRedirects(false)
      .followSslRedirects(false)
      .build()

  fun search(
    source: String,
    request: OnlineSubtitleSearchRequest,
    selectedLanguages: Set<String>?,
  ): List<OnlineSubtitle> =
    when (source) {
      MpvRxSubtitleHubSources.BETASERIES_KEY -> searchBetaSeries(request, selectedLanguages)
      MpvRxSubtitleHubSources.JIMAKU_KEY -> searchJimaku(request, selectedLanguages)
      MpvRxSubtitleHubSources.SUBDL_KEY -> searchSubDl(request, selectedLanguages)
      MpvRxSubtitleHubSources.SUBSOURCE_KEY -> searchSubSource(request, selectedLanguages)
      MpvRxSubtitleHubSources.SUBS_RO_KEY -> searchSubsRo(request, selectedLanguages)
      MpvRxSubtitleHubSources.SUBX_KEY -> searchSubX(request, selectedLanguages)
      else -> emptyList()
    }

  fun requireApiKey(source: String) {
    if (source in MpvRxSubtitleHubSources.AUTHENTICATED_SOURCES) {
      apiKey(source)
    }
  }

  fun authenticateDownload(
    subtitle: OnlineSubtitle,
    builder: Request.Builder,
  ) {
    when (val source = subtitle.metadata[AUTHENTICATED_SOURCE_METADATA_KEY]) {
      MpvRxSubtitleHubSources.JIMAKU_KEY -> {
        requireProviderHost(subtitle.url, "Jimaku", "jimaku.cc")
        builder.header("Authorization", apiKey(source))
      }
      MpvRxSubtitleHubSources.SUBSOURCE_KEY -> {
        requireProviderHost(subtitle.url, "SubSource", "api.subsource.net")
        builder.header("X-API-Key", apiKey(source))
      }
      MpvRxSubtitleHubSources.SUBS_RO_KEY -> {
        requireProviderHost(subtitle.url, "Subs.ro", "subs.ro")
        builder.header("X-Subs-Api-Key", apiKey(source))
      }
      MpvRxSubtitleHubSources.SUBX_KEY -> {
        requireProviderHost(subtitle.url, "SubX", "subx-api.duckdns.org")
        builder.header("Authorization", "Bearer ${apiKey(source)}")
      }
    }
  }

  fun clientForDownload(subtitle: OnlineSubtitle): OkHttpClient =
    when (subtitle.metadata[AUTHENTICATED_SOURCE_METADATA_KEY]) {
      MpvRxSubtitleHubSources.SUBSOURCE_KEY,
      MpvRxSubtitleHubSources.SUBS_RO_KEY,
      -> noRedirectClient
      else -> client
    }

  private fun searchBetaSeries(
    request: OnlineSubtitleSearchRequest,
    selectedLanguages: Set<String>?,
  ): List<OnlineSubtitle> {
    val season = request.season ?: return emptyList()
    val episode = request.episode ?: return emptyList()
    val languages =
      listOf("en", "fr").filter { languageMatches(it, selectedLanguages) }
    if (languages.isEmpty()) return emptyList()

    val apiKey = apiKey(MpvRxSubtitleHubSources.BETASERIES_KEY)
    val showSearchUrl =
      apiUrl(BETASERIES_API_BASE_URL, "shows/search") {
        addQueryParameter("key", apiKey)
        addQueryParameter("v", "3.0")
        addQueryParameter("title", request.query)
      }
    val showSearchRoot = fetchJsonObject(showSearchUrl, "BetaSeries", acceptedErrorStatuses = setOf(400))
    checkBetaSeriesErrors(showSearchRoot)
    val show =
      showSearchRoot.array("shows")
        ?.mapNotNull { it.obj() }
        ?.maxByOrNull { candidate ->
          SubtitleHubSearchMatcher.titleMatchScore(
            request.query,
            candidate.string("title").orEmpty(),
          )
        } ?: return emptyList()

    val showId = show.int("id")
    val tvdbId = show.int("thetvdb_id")
    if (showId == null && tvdbId == null) return emptyList()
    val episodesUrl =
      apiUrl(BETASERIES_API_BASE_URL, "shows/episodes") {
        addQueryParameter("key", apiKey)
        addQueryParameter("v", "3.0")
        addQueryParameter("subtitles", "1")
        addQueryParameter("season", season.toString())
        addQueryParameter("episode", episode.toString())
        if (tvdbId != null) {
          addQueryParameter("thetvdb_id", tvdbId.toString())
        } else {
          addQueryParameter("id", showId.toString())
        }
      }
    val root = fetchJsonObject(episodesUrl, "BetaSeries", acceptedErrorStatuses = setOf(400))
    checkBetaSeriesErrors(root)
    val rows =
      root.array("episodes")
        ?.firstOrNull()
        ?.obj()
        ?.array("subtitles")
        ?: root.obj("episode")?.array("subtitles")
        ?: return emptyList()

    return rows.mapNotNull { element ->
      val row = element.obj() ?: return@mapNotNull null
      if (row.string("source").equals("seriessub", ignoreCase = true)) return@mapNotNull null
      val languageCode =
        when (row.string("language")?.lowercase()) {
          "vo" -> "en"
          "vf" -> "fr"
          else -> return@mapNotNull null
        }
      if (languageCode !in languages) return@mapNotNull null
      val subtitleId = row.string("id") ?: row.int("id")?.toString() ?: return@mapNotNull null
      val downloadUrl = row.string("url")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      val release = row.string("file").orEmpty()
      val fileName = downloadUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf(String::isNotBlank)
      OnlineSubtitle(
        provider = SubtitleProvider.MPVRX_SUBTITLE_HUB,
        id = "${MpvRxSubtitleHubSources.BETASERIES_KEY}:$subtitleId:$languageCode",
        url = downloadUrl,
        fileName = fileName,
        release = release.takeIf(String::isNotBlank),
        media = show.string("title") ?: request.query,
        displayName = release.ifBlank { fileName ?: "BetaSeries subtitle" },
        displayLanguage = displayLanguage(languageCode),
        language = languageCode,
        source = "BetaSeries",
        format = displayFormat(fileName ?: downloadUrl),
        metadata = authenticatedMetadata(MpvRxSubtitleHubSources.BETASERIES_KEY),
      )
    }.distinctBy { it.url.lowercase() }
  }

  private fun searchJimaku(
    request: OnlineSubtitleSearchRequest,
    selectedLanguages: Set<String>?,
  ): List<OnlineSubtitle> {
    if (!languageMatches("ja", selectedLanguages)) return emptyList()
    val apiKey = apiKey(MpvRxSubtitleHubSources.JIMAKU_KEY)
    val searchParams =
      if (request.tmdbId != null) {
        mapOf("tmdb_id" to "${if (request.isEpisode) "tv" else "movie"}:${request.tmdbId}")
      } else {
        mapOf("query" to request.query.lowercase())
      }
    val entries =
      sequenceOf(true, false)
        .map { anime ->
          val url =
            apiUrl(JIMAKU_API_BASE_URL, "entries/search") {
              searchParams.forEach(::addQueryParameter)
              addQueryParameter("anime", anime.toString())
            }
          fetchJsonArray(url, "Jimaku") { header("Authorization", apiKey) }
        }.firstOrNull { it.isNotEmpty() }
        ?: return emptyList()
    val entry =
      entries.mapNotNull { it.obj() }.maxByOrNull { candidate ->
        listOf("name", "english_name", "japanese_name")
          .maxOf { key -> SubtitleHubSearchMatcher.titleMatchScore(request.query, candidate.string(key).orEmpty()) }
      } ?: return emptyList()
    val entryId = entry.int("id") ?: return emptyList()

    val filesUrl =
      apiUrl(JIMAKU_API_BASE_URL, "entries/$entryId/files") {
        request.episode?.let { addQueryParameter("episode", it.toString()) }
      }
    var files = fetchJsonArray(filesUrl, "Jimaku") { header("Authorization", apiKey) }
    if (files.isEmpty() && request.isEpisode) {
      files = fetchJsonArray(apiUrl(JIMAKU_API_BASE_URL, "entries/$entryId/files"), "Jimaku") {
        header("Authorization", apiKey)
      }
    }

    return files.mapNotNull { element ->
      val file = element.obj() ?: return@mapNotNull null
      val fileName = file.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      val extension = extensionFromName(fileName)
      if (extension !in SUBTITLE_EXTENSIONS && extension !in ARCHIVE_EXTENSIONS) return@mapNotNull null
      if ((file.int("size") ?: JIMAKU_MIN_FILE_SIZE) < JIMAKU_MIN_FILE_SIZE) return@mapNotNull null
      val downloadUrl =
        file.string("url")
          ?.takeIf(String::isNotBlank)
          ?.let { absoluteUrl(JIMAKU_API_BASE_URL, it) }
          ?: return@mapNotNull null
      OnlineSubtitle(
        provider = SubtitleProvider.MPVRX_SUBTITLE_HUB,
        id = "${MpvRxSubtitleHubSources.JIMAKU_KEY}:$entryId:${downloadUrl.hashCode()}",
        url = downloadUrl,
        fileName = fileName,
        release = fileName,
        media = entry.string("name") ?: entry.string("english_name") ?: request.query,
        displayName = fileName,
        displayLanguage = displayLanguage("ja"),
        language = "ja",
        source = "Jimaku",
        format = displayFormat(fileName),
        metadata =
          authenticatedMetadata(
            MpvRxSubtitleHubSources.JIMAKU_KEY,
            buildMap {
              request.season?.let { put("season", it.toString()) }
              request.episode?.let { put("episode", it.toString()) }
            },
          ),
      )
    }.distinctBy { it.url.lowercase() }
  }

  private fun searchSubDl(
    request: OnlineSubtitleSearchRequest,
    selectedLanguages: Set<String>?,
  ): List<OnlineSubtitle> {
    val apiKey = apiKey(MpvRxSubtitleHubSources.SUBDL_KEY)
    val subDlLanguages = selectedLanguages?.mapNotNull(::toSubDlLanguage)?.distinct().orEmpty()
    if (selectedLanguages != null && subDlLanguages.isEmpty()) return emptyList()
    val url =
      apiUrl(SUBDL_API_URL, null) {
        addQueryParameter("api_key", apiKey)
        addQueryParameter("type", if (request.isEpisode) "tv" else "movie")
        when {
          !request.imdbId.isNullOrBlank() -> addQueryParameter("imdb_id", request.imdbId)
          request.tmdbId != null -> addQueryParameter("tmdb_id", request.tmdbId.toString())
          else -> addQueryParameter("film_name", request.query)
        }
        if (subDlLanguages.isNotEmpty()) addQueryParameter("languages", subDlLanguages.joinToString(","))
        request.season?.let { addQueryParameter("season_number", it.toString()) }
        request.episode?.let { addQueryParameter("episode_number", it.toString()) }
        addQueryParameter("subs_per_page", SUBDL_RESULT_LIMIT.toString())
        addQueryParameter("comment", "1")
        addQueryParameter("releases", "1")
        addQueryParameter("bazarr", "1")
        addQueryParameter("unpack", "1")
      }
    val root = fetchJsonObject(url, "SubDL")
    if (root.bool("status") == false || root.bool("success") == false) {
      val error = root.string("error").orEmpty()
      if (error.isBlank() || error.contains("find", ignoreCase = true)) return emptyList()
      throw IllegalStateException("SubDL API returned an error")
    }

    return root.array("subtitles").orEmpty().flatMap { element ->
      val item = element.obj() ?: return@flatMap emptyList()
      val matchingChildren =
        item.array("unpack_files")
          ?.mapNotNull { it.obj() }
          ?.filter { child -> request.episode == null || child.int("episode") == request.episode }
          .orEmpty()
      if (matchingChildren.isNotEmpty()) {
        matchingChildren.mapNotNull { child -> subDlResult(request, selectedLanguages, item, child) }
      } else {
        listOfNotNull(subDlResult(request, selectedLanguages, item, null))
      }
    }.distinctBy { it.url.lowercase() }
  }

  private fun subDlResult(
    request: OnlineSubtitleSearchRequest,
    selectedLanguages: Set<String>?,
    item: JsonObject,
    child: JsonObject?,
  ): OnlineSubtitle? {
    val languageRaw = child?.string("language") ?: item.string("language") ?: return null
    val languageCode = fromSubDlLanguage(languageRaw)
    if (!languageMatches(languageCode, selectedLanguages)) return null
    val downloadPath = child?.string("url") ?: item.string("url") ?: return null
    val downloadUrl = absoluteUrl(SUBDL_DOWNLOAD_BASE_URL, downloadPath)
    val fileName = child?.string("name") ?: item.string("name") ?: "subdl-subtitle.zip"
    val subtitleId =
      child?.string("file_n_id")
        ?: child?.string("id")
        ?: item.string("id")
        ?: item.string("name")
        ?: downloadPath
    val release =
      child?.string("release_name")
        ?: item.string("release_name")
        ?: item.array("releases")?.firstOrNull()?.stringValue()
    val episodeFrom = item.int("episode_from")
    val episodeTo = item.int("episode_end")
    if (request.episode != null && episodeFrom != null && episodeTo != null && request.episode !in episodeFrom..episodeTo) {
      return null
    }
    val itemEpisode = child?.int("episode") ?: item.int("episode")
    if (request.episode != null && itemEpisode != null && itemEpisode != request.episode) return null
    return OnlineSubtitle(
      provider = SubtitleProvider.MPVRX_SUBTITLE_HUB,
      id = "${MpvRxSubtitleHubSources.SUBDL_KEY}:$subtitleId",
      url = downloadUrl,
      fileName = fileName,
      release = release,
      displayName = fileName,
      displayLanguage = displayLanguage(languageCode),
      language = languageCode,
      source = "SubDL.com",
      format = displayFormat(fileName.ifBlank { downloadUrl }),
      downloadCount = item.int("downloads"),
      isHearingImpaired = child?.bool("hi") ?: item.bool("hi") ?: false,
      metadata =
        authenticatedMetadata(
          MpvRxSubtitleHubSources.SUBDL_KEY,
          buildMap {
            request.season?.let { put("season", it.toString()) }
            itemEpisode?.let { put("episode", it.toString()) }
            if (episodeFrom != null && episodeTo != null && episodeTo >= episodeFrom) {
              put(SUBDL_GROUP_EPISODE_START_KEY, episodeFrom.toString())
              put(SUBDL_GROUP_EPISODE_END_KEY, episodeTo.toString())
            }
          },
        ),
    )
  }

  private fun searchSubSource(
    request: OnlineSubtitleSearchRequest,
    selectedLanguages: Set<String>?,
  ): List<OnlineSubtitle> {
    val apiKey = apiKey(MpvRxSubtitleHubSources.SUBSOURCE_KEY)
    val movieUrl =
      apiUrl(SUBSOURCE_API_BASE_URL, "api/v1/movies/search") {
        addQueryParameter("searchType", "text")
        addQueryParameter("q", request.query)
        addQueryParameter("type", if (request.isEpisode) "series" else "all")
        request.yearInt?.let { addQueryParameter("year", it.toString()) }
        request.season?.let { addQueryParameter("season", it.toString()) }
      }
    val movie =
      fetchJsonObject(movieUrl, "SubSource") { header("X-API-Key", apiKey) }
        .array("data")
        ?.mapNotNull { it.obj() }
        ?.mapNotNull { candidate ->
          val movieId = candidate.int("movieId") ?: return@mapNotNull null
          val title = candidate.string("title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
          val titleScore =
            maxOf(
              SubtitleHubSearchMatcher.titleMatchScore(request.query, title),
              SubtitleHubSearchMatcher.titleMatchScore(request.query, candidate.string("alternateTitle").orEmpty()),
            )
          val tmdbMatch = request.tmdbId != null && request.tmdbId == candidate.int("tmdbId")
          if (!tmdbMatch && titleScore <= 0) return@mapNotNull null
          val score =
            titleScore +
              (if (tmdbMatch) 10_000 else 0) +
              (if (request.yearInt != null && request.yearInt == candidate.int("releaseYear")) 1_000 else 0) +
              (if (request.season != null && request.season == candidate.int("season")) 500 else 0) +
              (candidate.int("subtitleCount") ?: 0).coerceIn(0, 100)
          SubSourceMovie(movieId, title) to score
        }?.maxByOrNull { it.second }
        ?.first
        ?: return emptyList()

    val languageQueries =
      selectedLanguages?.map { code -> displayLanguage(code).lowercase() }?.distinct() ?: listOf(null)
    return languageQueries.flatMap { language ->
      val subtitlesUrl =
        apiUrl(SUBSOURCE_API_BASE_URL, "api/v1/subtitles") {
          addQueryParameter("movieId", movie.id.toString())
          addQueryParameter("limit", SUBSOURCE_RESULT_LIMIT.toString())
          addQueryParameter("sort", "popular")
          language?.let { addQueryParameter("language", it) }
        }
      fetchJsonObject(subtitlesUrl, "SubSource") { header("X-API-Key", apiKey) }
        .array("data")
        .orEmpty()
        .mapNotNull { element ->
          val subtitle = element.obj() ?: return@mapNotNull null
          val subtitleId = subtitle.int("subtitleId") ?: return@mapNotNull null
          val languageRaw = subtitle.string("language")
          val languageCode = languageRaw?.toLanguageCode()
          if (languageCode != null && !languageMatches(languageCode, selectedLanguages)) return@mapNotNull null
          val releases = subtitle.array("releaseInfo")?.mapNotNull { it.stringValue() }.orEmpty()
          val release = releases.joinToString(" • ")
          OnlineSubtitle(
            provider = SubtitleProvider.MPVRX_SUBTITLE_HUB,
            id = "${MpvRxSubtitleHubSources.SUBSOURCE_KEY}:$subtitleId",
            url = absoluteUrl(SUBSOURCE_API_BASE_URL, "api/v1/subtitles/$subtitleId/download"),
            release = release.takeIf(String::isNotBlank),
            media = movie.title,
            displayName = release.ifBlank { "${movie.title} - ${languageRaw ?: "Subtitle"}" },
            displayLanguage = languageCode?.let(::displayLanguage) ?: languageRaw ?: "Unknown",
            language = languageCode,
            source = "SubSource.net",
            format = "zip",
            downloadCount = subtitle.int("downloads"),
            isHearingImpaired = subtitle.bool("hearingImpaired") == true,
            metadata =
              authenticatedMetadata(
                MpvRxSubtitleHubSources.SUBSOURCE_KEY,
                buildMap {
                  request.season?.let { put("season", it.toString()) }
                  request.episode?.let { put("episode", it.toString()) }
                },
              ),
          )
        }
    }.distinctBy { it.url.lowercase() }
  }

  private fun searchSubsRo(
    request: OnlineSubtitleSearchRequest,
    selectedLanguages: Set<String>?,
  ): List<OnlineSubtitle> {
    val imdbId = request.imdbId?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
    val apiKey = apiKey(MpvRxSubtitleHubSources.SUBS_RO_KEY)
    val languageCodes =
      mapOf("ro" to "ro", "en" to "en").filterKeys { languageMatches(it, selectedLanguages) }
    return languageCodes.flatMap { (apiLanguage, languageCode) ->
      val url =
        apiUrl(SUBS_RO_API_BASE_URL, "search/imdbid/${normalizeImdbId(imdbId)}") {
          addQueryParameter("language", apiLanguage)
        }
      fetchJsonObject(url, "Subs.ro") { header("X-Subs-Api-Key", apiKey) }
        .array("items")
        .orEmpty()
        .mapNotNull { element ->
          val item = element.obj() ?: return@mapNotNull null
          val subtitleId = item.string("id") ?: item.int("id")?.toString() ?: return@mapNotNull null
          val title = item.string("title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
          val downloadUrl =
            item.string("downloadLink")?.takeIf(String::isNotBlank)
              ?: "subtitle/$subtitleId/download"
          val release = item.string("description")?.takeIf(String::isNotBlank) ?: title
          OnlineSubtitle(
            provider = SubtitleProvider.MPVRX_SUBTITLE_HUB,
            id = "${MpvRxSubtitleHubSources.SUBS_RO_KEY}:$subtitleId:$languageCode",
            url = absoluteUrl(SUBS_RO_API_BASE_URL, downloadUrl),
            release = release,
            media = request.query,
            displayName = title,
            displayLanguage = displayLanguage(languageCode),
            language = languageCode,
            source = "Subs.ro",
            format = displayFormat(downloadUrl, "zip"),
            metadata =
              authenticatedMetadata(
                MpvRxSubtitleHubSources.SUBS_RO_KEY,
                buildMap {
                  request.season?.let { put("season", it.toString()) }
                  request.episode?.let { put("episode", it.toString()) }
                },
              ),
          )
        }
    }.distinctBy { it.url.lowercase() }
  }

  private fun searchSubX(
    request: OnlineSubtitleSearchRequest,
    selectedLanguages: Set<String>?,
  ): List<OnlineSubtitle> {
    if (!languageMatches("es", selectedLanguages)) return emptyList()
    val apiKey = apiKey(MpvRxSubtitleHubSources.SUBX_KEY)
    val queries =
      if (request.isEpisode && request.season != null && request.episode != null) {
        listOf(
          "${request.query} S${request.season.toString().padStart(2, '0')}E${request.episode.toString().padStart(2, '0')}",
          "${request.query} S${request.season.toString().padStart(2, '0')}",
          request.query,
        )
      } else {
        listOf(request.query)
      }
    return queries.asSequence().map { query ->
      val url =
        apiUrl(SUBX_API_BASE_URL, "api/subtitles/search") {
          addQueryParameter("limit", SUBX_RESULT_LIMIT.toString())
          addQueryParameter("video_type", if (request.isEpisode) "episode" else "movie")
          if (!request.imdbId.isNullOrBlank()) {
            addQueryParameter("imdb_id", request.imdbId)
          } else {
            addQueryParameter("title", query)
          }
          request.yearInt?.let { addQueryParameter("year", it.toString()) }
        }
      fetchJsonObject(url, "SubX") { header("Authorization", "Bearer $apiKey") }
        .array("items")
        .orEmpty()
        .mapNotNull { element ->
          val item = element.obj() ?: return@mapNotNull null
          val itemSeason = item.int("season")
          val itemEpisode = item.int("episode")
          if (request.season != null && itemSeason != request.season) return@mapNotNull null
          val seasonPack = request.isEpisode && itemEpisode == null && itemSeason == request.season
          if (request.episode != null && itemEpisode != request.episode && !seasonPack) return@mapNotNull null
          val subtitleId = item.string("id") ?: item.int("id")?.toString() ?: return@mapNotNull null
          val description = item.string("description").orEmpty()
          val variant = if (SUBX_SPAIN_MARKERS.any { description.contains(it, ignoreCase = true) }) "Spain" else "Latin America"
          val downloadUrl =
            item.string("download_url")?.takeIf(String::isNotBlank)
              ?: "api/subtitles/$subtitleId/download"
          val title = item.string("title").orEmpty()
          val release = listOf(title, description).filter(String::isNotBlank).joinToString(" | ")
          OnlineSubtitle(
            provider = SubtitleProvider.MPVRX_SUBTITLE_HUB,
            id = "${MpvRxSubtitleHubSources.SUBX_KEY}:$subtitleId:$variant",
            url = absoluteUrl(SUBX_API_BASE_URL, downloadUrl),
            fileName = "subx.$subtitleId.es.zip",
            release = release.takeIf(String::isNotBlank),
            media = request.query,
            displayName = release.ifBlank { "SubX subtitle" },
            displayLanguage = "${displayLanguage("es")} ($variant)",
            language = "es",
            source = "SubX",
            format = "zip",
            downloadCount = item.int("downloads"),
            metadata =
              authenticatedMetadata(
                MpvRxSubtitleHubSources.SUBX_KEY,
                buildMap {
                  itemSeason?.let { put("season", it.toString()) }
                  request.episode?.let { put("episode", it.toString()) }
                  if (seasonPack) put("seasonPack", "true")
                },
              ),
          )
        }
    }.firstOrNull { it.isNotEmpty() }.orEmpty()
  }

  private fun fetchJsonObject(
    url: HttpUrl,
    sourceName: String,
    acceptedErrorStatuses: Set<Int> = emptySet(),
    customize: Request.Builder.() -> Unit = {},
  ): JsonObject = fetchJson(url, sourceName, acceptedErrorStatuses, customize).obj() ?: throw invalidJson(sourceName)

  private fun fetchJsonArray(
    url: HttpUrl,
    sourceName: String,
    customize: Request.Builder.() -> Unit = {},
  ): JsonArray = fetchJson(url, sourceName, emptySet(), customize) as? JsonArray ?: throw invalidJson(sourceName)

  private fun fetchJson(
    url: HttpUrl,
    sourceName: String,
    acceptedErrorStatuses: Set<Int>,
    customize: Request.Builder.() -> Unit,
  ): JsonElement {
    val request =
      Request
        .Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT)
        .apply(customize)
        .build()
    val response =
      try {
        noRedirectClient.newCall(request).execute()
      } catch (_: IOException) {
        throw IllegalStateException("$sourceName request failed")
      }
    response.use {
      if (!it.isSuccessful && it.code !in acceptedErrorStatuses) {
        throw when (it.code) {
          401, 403 -> IllegalArgumentException("$sourceName API key was rejected")
          429 -> IllegalStateException("$sourceName rate limit exceeded")
          else -> IllegalStateException("$sourceName request failed with HTTP ${it.code}")
        }
      }
      val body = it.body.string()
      return runCatching { json.parseToJsonElement(body) }.getOrElse { throw invalidJson(sourceName) }
    }
  }

  private fun apiKey(source: String): String {
    val key =
      when (source) {
        MpvRxSubtitleHubSources.BETASERIES_KEY -> preferences.betaSeriesApiKey.get()
        MpvRxSubtitleHubSources.JIMAKU_KEY -> preferences.jimakuApiKey.get()
        MpvRxSubtitleHubSources.SUBDL_KEY -> preferences.subDlApiKey.get()
        MpvRxSubtitleHubSources.SUBSOURCE_KEY -> preferences.subSourceApiKey.get()
        MpvRxSubtitleHubSources.SUBS_RO_KEY -> preferences.subsRoApiKey.get()
        MpvRxSubtitleHubSources.SUBX_KEY -> preferences.subXApiKey.get()
        else -> ""
      }.trim()
    return key.takeIf(String::isNotBlank)
      ?: throw IllegalStateException("${MpvRxSubtitleHubSources.ALL[source] ?: source} API key is required")
  }

  private fun requireProviderHost(
    url: String,
    sourceName: String,
    providerHost: String,
  ) {
    val parsed = url.toHttpUrlOrNull() ?: throw IllegalStateException("Invalid $sourceName download URL")
    if (!parsed.isHttps) throw IllegalStateException("$sourceName authenticated downloads require HTTPS")
    val host = parsed.host
    if (host != providerHost && !host.endsWith(".$providerHost")) {
      throw IllegalStateException("Refusing to send the $sourceName API key to another host")
    }
  }

  private fun apiUrl(
    baseUrl: String,
    path: String?,
    build: HttpUrl.Builder.() -> Unit = {},
  ): HttpUrl {
    val base = baseUrl.toHttpUrl()
    val builder = base.newBuilder()
    if (!path.isNullOrBlank()) builder.addPathSegments(path.trimStart('/'))
    return builder.apply(build).build()
  }

  private fun authenticatedMetadata(
    source: String,
    values: Map<String, String> = emptyMap(),
  ): Map<String, String> = values + (AUTHENTICATED_SOURCE_METADATA_KEY to source)

  private fun checkBetaSeriesErrors(root: JsonObject) {
    val code = root.array("errors")?.firstOrNull()?.obj()?.int("code") ?: return
    if (code == 4001) return
    if (code == 1001) throw IllegalArgumentException("BetaSeries API key was rejected")
    throw IllegalStateException("BetaSeries API returned an error")
  }

  private fun languageMatches(
    language: String,
    selectedLanguages: Set<String>?,
  ): Boolean {
    if (selectedLanguages == null) return true
    val normalized = language.normalizeLanguageCode()
    return selectedLanguages.any { selected ->
      val normalizedSelected = selected.normalizeLanguageCode()
      normalized == normalizedSelected || normalized.substringBefore('-') == normalizedSelected.substringBefore('-')
    }
  }

  private fun displayLanguage(code: String): String = WyzieLanguages.ALL[code] ?: code

  private fun String.toLanguageCode(): String {
    val normalized = normalizeLanguageCode()
    return WyzieLanguages.ALL.entries
      .firstOrNull { it.value.equals(this, ignoreCase = true) }
      ?.key
      ?: LANGUAGE_ALIASES[normalized]
      ?: normalized
  }

  private fun String.normalizeLanguageCode(): String = replace('_', '-').lowercase()

  private fun toSubDlLanguage(language: String): String? =
    when (language.normalizeLanguageCode()) {
      "pt-br" -> "BR_PT"
      "zh-tw" -> "ZH_BG"
      "zh-cn" -> "ZH"
      else -> ISO_ALPHA2_PATTERN.matchEntire(language.normalizeLanguageCode())?.value?.uppercase()
    }

  private fun fromSubDlLanguage(language: String): String =
    when (language.uppercase()) {
      "BR_PT" -> "pt-br"
      "ZH_BG" -> "zh-tw"
      "ZH" -> "zh"
      else -> language.lowercase().replace('_', '-')
    }

  private fun displayFormat(
    value: String,
    fallback: String? = null,
  ): String? = SubtitleHubSearchMatcher.displayFormat(extensionFromName(value) ?: fallback)

  private fun extensionFromName(value: String): String? =
    value
      .substringBefore('?')
      .substringAfterLast('/')
      .substringAfterLast('.', "")
      .lowercase()
      .takeIf { it.isNotBlank() && it.length <= 5 }

  private fun absoluteUrl(
    baseUrl: String,
    path: String,
  ): String = path.toHttpUrlOrNull()?.toString() ?: baseUrl.toHttpUrl().resolve(path)?.toString().orEmpty()

  private fun normalizeImdbId(value: String): String {
    val normalized = value.trim()
    return if (normalized.startsWith("tt", ignoreCase = true)) normalized else "tt$normalized"
  }

  private val OnlineSubtitleSearchRequest.isEpisode: Boolean
    get() = season != null || episode != null

  private val OnlineSubtitleSearchRequest.yearInt: Int?
    get() = year?.let(YEAR_PATTERN::find)?.value?.toIntOrNull()

  private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

  private fun JsonObject.obj(key: String): JsonObject? = get(key).obj()

  private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

  private fun JsonObject.string(key: String): String? = get(key).stringValue()

  private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.int(key: String): Int? {
    val primitive = get(key) as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
  }

  private fun JsonObject.bool(key: String): Boolean? {
    val primitive = get(key) as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: primitive.contentOrNull?.toBooleanStrictOrNull()
  }

  private fun invalidJson(sourceName: String) = IllegalStateException("$sourceName returned invalid JSON")

  private data class SubSourceMovie(
    val id: Int,
    val title: String,
  )

  private companion object {
    const val USER_AGENT = "mpvRx/1.0 (Android; SubtitleHub)"
    const val BETASERIES_API_BASE_URL = "https://api.betaseries.com/"
    const val JIMAKU_API_BASE_URL = "https://jimaku.cc/api/"
    const val SUBDL_API_URL = "https://api.subdl.com/api/v1/subtitles"
    const val SUBDL_DOWNLOAD_BASE_URL = "https://dl.subdl.com/"
    const val SUBSOURCE_API_BASE_URL = "https://api.subsource.net/"
    const val SUBS_RO_API_BASE_URL = "https://api.subs.ro/v1.0/"
    const val SUBX_API_BASE_URL = "https://subx-api.duckdns.org/"
    const val JIMAKU_MIN_FILE_SIZE = 500
    const val SUBDL_RESULT_LIMIT = 30
    const val SUBSOURCE_RESULT_LIMIT = 100
    const val SUBX_RESULT_LIMIT = 200
    val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")
    val ARCHIVE_EXTENSIONS = setOf("zip", "rar")
    val ISO_ALPHA2_PATTERN = Regex("[a-z]{2}")
    val YEAR_PATTERN = Regex("""\b(?:19|20)\d{2}\b""")
    val SUBX_SPAIN_MARKERS = listOf("espana", "españa", "iberico", "ibérico", "castellano", "gallego", "castilla")
    val LANGUAGE_ALIASES =
      mapOf(
        "english" to "en",
        "french" to "fr",
        "japanese" to "ja",
        "romanian" to "ro",
        "spanish" to "es",
      )
  }
}
