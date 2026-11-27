/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitlehub

import android.net.Uri
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitle
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleFileStore
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleProvider
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchRequest
import app.gyrolet.mpvrx.repository.subtitle.SubtitleProvider
import app.gyrolet.mpvrx.repository.wyzie.WyzieLanguages
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder

class MpvRxSubtitleHubRepository(
  private val client: OkHttpClient,
  private val json: Json,
  private val preferences: SubtitlesPreferences,
  private val fileStore: OnlineSubtitleFileStore,
) : OnlineSubtitleProvider {
  override val provider: SubtitleProvider = SubtitleProvider.MPVRX_SUBTITLE_HUB

  private val apiSources = MpvRxSubtitleHubApiSources(client, json, preferences)
  private val providerSemaphore = Semaphore(MAX_CONCURRENT_PROVIDER_REQUESTS)

  override suspend fun search(request: OnlineSubtitleSearchRequest): Result<List<OnlineSubtitle>> =
    searchIncrementally(request) {}

  override suspend fun searchIncrementally(
    request: OnlineSubtitleSearchRequest,
    onResults: suspend (List<OnlineSubtitle>) -> Unit,
  ): Result<List<OnlineSubtitle>> =
    withContext(Dispatchers.IO) {
      try {
        val selectedSources =
          SubtitleHubSearchMatcher.sourcesFor(
            request = request,
            selectedSources = MpvRxSubtitleHubSources.resolveSelected(preferences.subtitleHubSources.get()),
          )
        if (selectedSources.size == 1) {
          apiSources.requireApiKey(selectedSources.single())
        }
        val results =
          coroutineScope {
            val completedSources = Channel<List<OnlineSubtitle>>(selectedSources.size)
            selectedSources.forEach { source ->
              launch {
                val providerResults =
                  try {
                    providerSemaphore.withPermit {
                      if (source in MpvRxSubtitleHubSources.AUTHENTICATED_SOURCES) {
                        apiSources.search(source, request, selectedLanguages())
                      } else {
                        when (source) {
                          "subtitlecat_com" -> searchSubtitleCat(request.withEpisodeSearchQuery())
                          "moviesubtitles_org" -> searchMovieSubtitlesOrg(request)
                          "moviesubtitlesrt_com" -> searchMovieSubtitlesRt(request)
                          "my_subs_co" -> searchMySubs(request)
                          "tvsubtitles_net" -> searchTvSubtitles(request)
                          else -> emptyList()
                        }
                      }
                    }
                  } catch (cancellation: CancellationException) {
                    throw cancellation
                  } catch (error: Exception) {
                    Log.w(TAG, "Skipping $source after provider failure", error)
                    emptyList()
                  }
                completedSources.send(providerResults)
              }
            }

            buildList {
              repeat(selectedSources.size) {
                addAll(completedSources.receive())
                onResults(normalizeSearchResults(this))
              }
            }
          }

        Result.success(normalizeSearchResults(results))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "mpvRx SubtitleHub search failed", e)
        Result.failure(e)
      }
    }

  private fun normalizeSearchResults(results: List<OnlineSubtitle>): List<OnlineSubtitle> =
    results
      .distinctBy { it.url.lowercase() }
      .filterNot { it.format.equals("html", ignoreCase = true) }
      .sortedWith(
        compareByDescending<OnlineSubtitle> { it.downloadCount ?: 0 }
          .thenBy { it.source ?: "" }
          .thenBy { it.displayName.lowercase() },
      )

  override suspend fun download(
    subtitle: OnlineSubtitle,
    mediaTitle: String,
  ): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        val resolved = resolveDownloadIfNeeded(subtitle)
        val request =
          Request
            .Builder()
            .url(resolved.url)
            .header("User-Agent", USER_AGENT)
            .apply {
              resolved.referer?.let { header("Referer", it) }
              apiSources.authenticateDownload(subtitle, this)
            }.build()
        apiSources.clientForDownload(subtitle).newCall(request).execute().use { response ->
          if (!response.isSuccessful) return@withContext Result.failure(Exception("Download failed: ${response.code}"))
          Result.success(fileStore.save(response.body.bytes(), subtitle, mediaTitle))
        }
      } catch (e: Exception) {
        Log.e(TAG, "mpvRx SubtitleHub download failed", e)
        Result.failure(e)
      }
    }

  private fun searchSubtitleCat(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> {
    val encoded = URLEncoder.encode(request.query, "UTF-8")
    val url = "$SUBTITLECAT_BASE_URL/index.php?search=$encoded&show=10000"
    val html = fetchText(url, "text/html")
    val listings = SubtitleCatHtmlParser.parseSearchResults(html).take(SUBTITLECAT_SEARCH_RESULT_LIMIT)
    val languages = selectedLanguages()

    return listings
      .filter { listing -> SubtitleHubSearchMatcher.matchesQueryTitle(request.query, listing.title) }
      .flatMap { listing ->
        val detailsUrl = subtitleCatAbsoluteUrl(listing.path)
        runCatching {
          val detailHtml = fetchText(detailsUrl, "text/html")
          SubtitleCatHtmlParser
            .parseDownloadLinks(detailHtml)
            .asSequence()
            .filter { link -> languages == null || languages.any { it.codeEquals(link.languageCode) } }
            .map { link ->
              val downloadUrl = subtitleCatAbsoluteUrl(link.path)
              val languageCode = link.languageCode.toLanguageCode()
              OnlineSubtitle(
                provider = provider,
                id = "subtitlecat_com:${listing.path}:${link.languageCode}",
                url = downloadUrl,
                fileName = link.fileName,
                release = listing.title,
                displayName = link.fileName ?: listing.title,
                displayLanguage = link.languageLabel ?: WyzieLanguages.ALL[languageCode] ?: link.languageCode,
                language = languageCode,
                source = "SubtitleCat",
                format = SubtitleHubSearchMatcher.displayFormat(extensionFromName(downloadUrl)),
                downloadCount = listing.downloads.takeIf { it > 0 },
                metadata = mapOf("detailsUrl" to detailsUrl),
              )
            }.toList()
        }.getOrElse { error ->
          Log.w(TAG, "Skipping SubtitleCat listing ${listing.path}", error)
          emptyList()
        }
      }
  }

  private fun searchMovieSubtitlesRt(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> {
    val encoded = URLEncoder.encode(request.query, "UTF-8")
    val html = fetchText("$MOVIESUBTITLESRT_BASE_URL/?s=$encoded", "text/html")
    val doc = Jsoup.parse(html, MOVIESUBTITLESRT_BASE_URL)
    val selectedLanguages = selectedLanguages()
    val pages =
      doc
        .select("div.inside-article header h2 a[href], article h2 a[href]")
        .asSequence()
        .map { it.text().trim() to it.absUrl("href") }
        .filter { (title, url) -> title.isNotBlank() && url.isNotBlank() }
        .filter { (title, _) -> SubtitleHubSearchMatcher.matchesQueryTitle(request.query, title) }
        .distinctBy { it.second.lowercase() }
        .take(GENERIC_SEARCH_RESULT_LIMIT)
        .toList()

    return pages.mapNotNull { (listingTitle, pageUrl) ->
      runCatching {
        val page = Jsoup.parse(fetchText(pageUrl, "text/html"), pageUrl)
        val title =
          page
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: listingTitle
        val metadata = parseLabelValueRows(page)
        val languageRaw = metadata["language"] ?: metadata["subtitle language"]
        if (!matchesSelectedLanguage(languageRaw, selectedLanguages)) return@runCatching null

        val downloadUrl =
          page
            .selectFirst("a[href$=.zip], a[href*=.zip], a[href*=wp-content][href*=zip]")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        hubSubtitle(
          sourceKey = "moviesubtitlesrt_com",
          sourceName = "MovieSubtitlesRT",
          url = downloadUrl,
          title = title,
          fileName = title,
          languageRaw = languageRaw,
          metadata = metadata + ("detailsUrl" to pageUrl),
        )
      }.getOrElse { error ->
        Log.w(TAG, "Skipping MovieSubtitlesRT result $pageUrl", error)
        null
      }
    }
  }

  private fun searchMovieSubtitlesOrg(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> {
    val html =
      fetchText("$MOVIESUBTITLES_ORG_BASE_URL/search.php", "text/html", allowNonOk = true) {
        post(FormBody.Builder().add("q", request.query).build())
      }
    val doc = Jsoup.parse(html, MOVIESUBTITLES_ORG_BASE_URL)
    val selectedLanguages = selectedLanguages()
    val moviePages =
      doc
        .select("div[style*=width:500px] a[href], a[href^=/movie-][href$=.html]")
        .asSequence()
        .map { it.text().trim() to it.absUrl("href") }
        .filter { (title, url) -> title.isNotBlank() && url.contains("/movie-") }
        .filter { (title, _) -> SubtitleHubSearchMatcher.matchesQueryTitle(request.query, title) }
        .distinctBy { it.second.lowercase() }
        .take(GENERIC_SEARCH_RESULT_LIMIT)
        .toList()

    return moviePages.flatMap { (movieTitle, movieUrl) ->
      runCatching {
        val page = Jsoup.parse(fetchText(movieUrl, "text/html", allowNonOk = true), movieUrl)
        val pageTitle =
          page
            .selectFirst("h1, title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: movieTitle
        page
          .select("a[href*=subtitle-]")
          .asSequence()
          .mapNotNull { anchor ->
            val detailsUrl = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val block =
              anchor.closestParent { it.tagName() == "div" && it.attr("style").contains("margin-bottom") }
                ?: anchor.parent()
            val languageRaw =
              block
                ?.selectFirst(
                  "img[src*=flags]",
                )?.attr("src")
                ?.substringBeforeLast(".")
                ?.substringAfterLast("/")
            if (!matchesSelectedLanguage(languageRaw, selectedLanguages)) return@mapNotNull null
            val fileName =
              block
                ?.selectFirst("b")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: block?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: pageTitle
            hubSubtitle(
              sourceKey = "moviesubtitles_org",
              sourceName = "MovieSubtitles.org",
              url = movieSubtitlesOrgDetailToDownloadUrl(detailsUrl),
              title = fileName,
              fileName = fileName,
              languageRaw = languageRaw,
              metadata =
                mapOf(
                  "detailsUrl" to detailsUrl,
                  "movieUrl" to movieUrl,
                  "requiresResolution" to "moviesubtitles_org",
                  "fallbackFormat" to "zip",
                ),
            )
          }.distinctBy { it.url.lowercase() }
          .take(GENERIC_SUBTITLE_RESULT_LIMIT)
          .toList()
      }.getOrElse { error ->
        Log.w(TAG, "Skipping MovieSubtitles.org result $movieUrl", error)
        emptyList()
      }
    }
  }

  private fun searchMySubs(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> {
    val encoded = URLEncoder.encode(request.query, "UTF-8")
    val doc =
      Jsoup.parse(
        fetchText("$MY_SUBS_BASE_URL/search.php?key=$encoded", "text/html", allowNonOk = true),
        MY_SUBS_BASE_URL,
      )
    val selectedLanguages = selectedLanguages()
    val pages =
      doc
        .select("a[href*=/showlistsubtitles-], a[href*=/film-versions-]")
        .asSequence()
        .map { it.text().trim().ifBlank { it.attr("title").trim() } to it.absUrl("href") }
        .filter { (title, url) -> title.isNotBlank() && url.isNotBlank() }
        .filter { (title, _) -> SubtitleHubSearchMatcher.matchesQueryTitle(request.query, title) }
        .distinctBy { it.second.lowercase() }
        .sortedByDescending { (title, _) -> SubtitleHubSearchMatcher.titleMatchScore(request.query, title) }
        .take(GENERIC_SEARCH_RESULT_LIMIT)
        .toList()

    return pages.flatMap { (title, detailsUrl) ->
      runCatching {
        val pageUrls = linkedSetOf(detailsUrl)
        val root = Jsoup.parse(fetchText(detailsUrl, "text/html", allowNonOk = true), detailsUrl)
        val episodePageUrl = mySubsEpisodePageUrl(detailsUrl, request.season, request.episode)
        if (episodePageUrl != null) {
          pageUrls.clear()
          pageUrls += episodePageUrl
        } else {
          root
            .select("#saison a[href*=versions-][href*=subtitles], a[href*=versions-][href*=subtitles]")
            .forEach { anchor ->
              anchor.absUrl("href").takeIf { it.isNotBlank() }?.let { pageUrls += it }
            }
        }

        pageUrls
          .flatMap { pageUrl ->
            val page = Jsoup.parse(fetchText(pageUrl, "text/html", allowNonOk = true), pageUrl)
            val pageTitle =
              page
                .selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: title
            page
              .select("a[href*=/downloads/]")
              .asSequence()
              .mapNotNull { anchor ->
                val downloadPageUrl = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val languageRaw = mySubsLanguage(anchor)
                if (!matchesSelectedLanguage(languageRaw, selectedLanguages)) return@mapNotNull null
                val release =
                  mySubsRelease(anchor)
                    ?: pageTitle
                hubSubtitle(
                  sourceKey = "my_subs_co",
                  sourceName = "My Subs",
                  url = downloadPageUrl,
                  title = release,
                  fileName = release,
                  languageRaw = languageRaw,
                  metadata =
                    buildMap {
                      put("detailsUrl", pageUrl)
                      put("requiresResolution", "my_subs_co")
                      request.season?.let { put("season", it.toString()) }
                      request.episode?.let { put("episode", it.toString()) }
                    },
                )
              }.toList()
          }.distinctBy { it.url.lowercase() }
          .take(GENERIC_SUBTITLE_RESULT_LIMIT)
      }.getOrElse { error ->
        Log.w(TAG, "Skipping My Subs result $detailsUrl", error)
        emptyList()
      }
    }
  }

  private fun searchTvSubtitles(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> {
    val html =
      fetchText("$TVSUBTITLES_BASE_URL/search1.php", "text/html", allowNonOk = true) {
        post(FormBody.Builder().add("qs", request.query).build())
      }
    val doc = Jsoup.parse(html, TVSUBTITLES_BASE_URL)
    val selectedLanguages = selectedLanguages()
    val shows =
      doc
        .select(".left_articles a[href*=tvshow-], a[href*=tvshow-]")
        .asSequence()
        .map { it.text().trim() to it.absUrl("href") }
        .filter { (title, url) -> title.isNotBlank() && url.contains("tvshow-") }
        .filter { (title, _) -> SubtitleHubSearchMatcher.matchesQueryTitle(request.query, title) }
        .distinctBy { it.second.lowercase() }
        .sortedByDescending { (title, _) -> SubtitleHubSearchMatcher.titleMatchScore(request.query, title) }
        .take(GENERIC_SEARCH_RESULT_LIMIT)
        .toList()

    return shows.flatMap { (title, showUrl) ->
      runCatching {
        val seasonUrls =
          request.season
            ?.let { linkedSetOf(tvSubtitlesSeasonUrl(showUrl, it)) }
            ?: linkedSetOf(showUrl)

        seasonUrls
          .flatMap { seasonUrl ->
            val page = Jsoup.parse(fetchText(seasonUrl, "text/html", allowNonOk = true), seasonUrl)
            page
              .select("table#table5 tr[align=middle]")
              .asSequence()
              .flatMap { row ->
                val episodeCode =
                  row
                    .selectFirst("td:nth-child(1)")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                val rowSeason = episodeCode.substringBefore("x").toIntOrNull()
                val rowEpisode = episodeCode.substringAfter("x", "").toIntOrNull()
                if (rowSeason == null || rowEpisode == null) return@flatMap emptySequence<OnlineSubtitle>()
                if (request.season != null && rowSeason != request.season) return@flatMap emptySequence()
                if (request.episode != null && rowEpisode != request.episode) return@flatMap emptySequence()

                val episodeTitle =
                  row
                    .selectFirst("td:nth-child(2) a b, td:nth-child(2) a")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                row
                  .select("a[href*=subtitle-], a[href*=episode-]")
                  .asSequence()
                  .flatMap { anchor ->
                    val subtitlePageUrl =
                      anchor.absUrl("href").takeIf { it.isNotBlank() }
                        ?: return@flatMap emptySequence<OnlineSubtitle>()
                    val languageRaw = tvSubtitlesLanguage(anchor, subtitlePageUrl)
                    if (!matchesSelectedLanguage(
                        languageRaw,
                        selectedLanguages,
                      )
                    ) {
                      return@flatMap emptySequence<OnlineSubtitle>()
                    }

                    tvSubtitlesSubtitlePages(subtitlePageUrl).asSequence().mapNotNull { detailsUrl ->
                      val subtitleId = parseTvSubtitlesId(detailsUrl) ?: return@mapNotNull null
                      val fileName =
                        "${title.sanitizeDisplayName()}.S${rowSeason.toString().padStart(
                          2,
                          '0',
                        )}E${rowEpisode.toString().padStart(2, '0')}.${languageRaw ?: "unknown"}.zip"
                      hubSubtitle(
                        sourceKey = "tvsubtitles_net",
                        sourceName = "TVSubtitles",
                        url = absoluteUrl(TVSUBTITLES_BASE_URL, "download-$subtitleId.html"),
                        title = episodeTitle ?: title,
                        fileName = fileName,
                        languageRaw = languageRaw,
                        metadata =
                          mapOf(
                            "detailsUrl" to detailsUrl,
                            "seasonUrl" to seasonUrl,
                            "requiresResolution" to "tvsubtitles_net",
                            "season" to rowSeason.toString(),
                            "episode" to rowEpisode.toString(),
                          ),
                      )
                    }
                  }
              }.toList()
          }.distinctBy { it.url.lowercase() }
          .take(GENERIC_SUBTITLE_RESULT_LIMIT)
      }.getOrElse { error ->
        Log.w(TAG, "Skipping TVSubtitles result $showUrl", error)
        emptyList()
      }
    }
  }

  private fun fetchText(
    url: String,
    accept: String,
    allowNonOk: Boolean = false,
    customize: Request.Builder.() -> Unit = {},
  ): String {
    val builder =
      Request
        .Builder()
        .url(url)
        .header("Accept", accept)
        .header("User-Agent", USER_AGENT)
    builder.customize()

    client.newCall(builder.build()).execute().use { response ->
      if (!response.isSuccessful && !allowNonOk) {
        throw IllegalStateException("HTTP ${response.code} for $url")
      }
      return response.body.string()
    }
  }

  private fun resolveDownloadIfNeeded(subtitle: OnlineSubtitle): ResolvedDownload {
    val url = subtitle.url
    return when {
      subtitle.metadata["requiresResolution"] == "moviesubtitles_org" ->
        ResolvedDownload(
          resolveMovieSubtitlesOrgDownloadUrl(url),
        )
      url.contains("my-subs.co/downloads/") -> ResolvedDownload(resolveMySubsDownloadUrl(url), referer = url)
      url.contains("tvsubtitles.net/download-") -> ResolvedDownload(resolveTvSubtitlesDownloadUrl(url))
      else -> ResolvedDownload(url)
    }
  }

  private fun resolveMySubsDownloadUrl(downloadPageUrl: String): String {
    val html = fetchText(downloadPageUrl, "text/html", allowNonOk = true)
    val raw =
      Regex("""REAL_URL\s*=\s*['"]((?:\\.|[^'"])*)['"]""")
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.decodeJsString()
        ?: throw IllegalStateException("My Subs download URL not found")
    return absoluteUrl(MY_SUBS_BASE_URL, raw)
  }

  private fun resolveTvSubtitlesDownloadUrl(downloadPageUrl: String): String {
    val html = fetchText(downloadPageUrl, "text/html", allowNonOk = true)
    val scriptPath =
      parseDocumentLocationConcat(html) ?: parseTvSubtitlesZipPath(html)
        ?: throw IllegalStateException("TVSubtitles download URL not found")
    return absoluteUrl(TVSUBTITLES_BASE_URL, scriptPath.replace(" ", "%20"))
  }

  private fun resolveMovieSubtitlesOrgDownloadUrl(downloadPageUrl: String): String {
    val html = fetchText(downloadPageUrl, "text/html", allowNonOk = true)
    val page = Jsoup.parse(html, downloadPageUrl)
    return page
      .selectFirst("a[href$=.zip], a[href*=files][href$=.zip], a[href*=download][href$=.zip]")
      ?.absUrl("href")
      ?.takeIf { it.isNotBlank() }
      ?: parseDocumentLocationConcat(html)?.let { absoluteUrl(MOVIESUBTITLES_ORG_BASE_URL, it) }
      ?: downloadPageUrl
  }

  private fun parseDocumentLocationConcat(html: String): String? {
    val vars =
      Regex("""var\s+([A-Za-z_$][\w$]*)\s*=\s*(['"])((?:\\.|(?!\2).)*)\2""")
        .findAll(html)
        .associate { it.groupValues[1] to it.groupValues[3].decodeJsString() }
    val expr =
      Regex("""document\.location\s*=\s*([^;]+);""")
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?: return null

    val out = StringBuilder()
    expr.split("+").forEach { partRaw ->
      val part = partRaw.trim()
      if (part.isBlank()) return@forEach
      val quoted = Regex("""^(['"])((?:\\.|.)*)\1$""").find(part)
      when {
        quoted != null -> out.append(quoted.groupValues[2].decodeJsString())
        vars.containsKey(part) -> out.append(vars.getValue(part))
        else -> return null
      }
    }
    return out.toString().takeIf { it.contains(".zip", ignoreCase = true) }
  }

  private fun parseTvSubtitlesZipPath(html: String): String? =
    Regex("""['"]([^'"]*files/[^'"]+\.zip)['"]""", RegexOption.IGNORE_CASE)
      .find(html)
      ?.groupValues
      ?.getOrNull(1)

  private fun hubSubtitle(
    sourceKey: String,
    sourceName: String,
    url: String,
    title: String,
    fileName: String?,
    languageRaw: String?,
    metadata: Map<String, String> = emptyMap(),
  ): OnlineSubtitle {
    val languageCode = languageRaw?.toLanguageCode()
    return OnlineSubtitle(
      provider = provider,
      id = "$sourceKey:${url.hashCode()}",
      url = url,
      fileName = fileName,
      release = title,
      displayName = fileName?.takeIf { it.isNotBlank() } ?: title,
      displayLanguage =
        languageCode?.let { WyzieLanguages.ALL[it] } ?: languageRaw?.takeIf { it.isNotBlank() } ?: "Unknown",
      language = languageCode,
      source = sourceName,
      format =
        SubtitleHubSearchMatcher.displayFormat(
          value = extensionFromName(fileName.orEmpty()) ?: extensionFromName(url),
          fallbackForResolvedPage = metadata["fallbackFormat"],
        ),
      metadata = metadata.filterValues { it.isNotBlank() },
    )
  }

  private fun parseLabelValueRows(doc: org.jsoup.nodes.Document): Map<String, String> {
    val rows = linkedMapOf<String, String>()
    doc.select("tbody tr, table tr").forEach { row ->
      val cells = row.select("td")
      if (cells.size < 2) return@forEach
      val label =
        cells
          .first()
          ?.text()
          ?.trim()
          ?.trimEnd(':')
          ?.lowercase()
          .orEmpty()
      val value =
        cells
          .last()
          ?.text()
          ?.trim()
          .orEmpty()
      if (label.isNotBlank() && value.isNotBlank()) rows[label] = value
    }
    return rows
  }

  private fun Element.closestParent(predicate: (Element) -> Boolean): Element? {
    var current = parent()
    while (current != null) {
      if (predicate(current)) return current
      current = current.parent()
    }
    return null
  }

  private fun matchesSelectedLanguage(
    languageRaw: String?,
    selectedLanguages: Set<String>?,
  ): Boolean {
    if (selectedLanguages == null) return true
    val code = languageRaw?.toLanguageCode() ?: return false
    return selectedLanguages.any { it.codeEquals(code) || it.codeEquals(languageRaw) }
  }

  private fun movieSubtitlesOrgDetailToDownloadUrl(detailsUrl: String): String =
    detailsUrl.replace("/subtitle-", "/download-")

  private fun mySubsEpisodePageUrl(
    detailsUrl: String,
    season: Int?,
    episode: Int?,
  ): String? {
    if (season == null || episode == null) return null
    val match = Regex("""showlistsubtitles-(\d+)-([^/?#]+)""").find(detailsUrl) ?: return null
    val showId = match.groupValues[1]
    val slug = match.groupValues[2]
    return absoluteUrl(MY_SUBS_BASE_URL, "versions-$showId-$episode-$season-$slug-subtitles")
  }

  private fun mySubsRelease(anchor: Element): String? {
    var current: Element? = anchor
    while (current != null) {
      current.previousElementSibling()?.let { previous ->
        previous
          .selectFirst("h4, h3, strong, b")
          ?.text()
          ?.trim()
          ?.removePrefix("Version:")
          ?.trim()
          ?.takeIf { it.isNotBlank() }
          ?.let { return it }
      }
      current = current.parent()
    }

    return anchor
      .closestParent { it.hasClass("row") && it.text().contains("Downloads", ignoreCase = true) }
      ?.parent()
      ?.selectFirst("h4, h3, strong, b")
      ?.text()
      ?.trim()
      ?.removePrefix("Version:")
      ?.trim()
      ?.takeIf { it.isNotBlank() }
  }

  private fun mySubsLanguage(anchor: Element): String? {
    val container =
      anchor.closestParent { element ->
        element.hasClass("row") && element.text().contains("Language", ignoreCase = true)
      } ?: anchor
    val flag = container.selectFirst("span[class*=flag-icon-]")
    val className = flag?.classNames()?.firstOrNull { it.startsWith("flag-icon-") }
    val code =
      className?.removePrefix("flag-icon-")
        ?: container
          .selectFirst("i")
          ?.text()
          ?.trim()
          ?.takeIf { it.isNotBlank() }
        ?: flag?.attr("title")?.takeIf { it.isNotBlank() }
        ?: return null
    return when (code.lowercase()) {
      "br" -> "pt-br"
      "gb" -> "en"
      "gr" -> "el"
      "sa" -> "ar"
      "ua" -> "uk"
      "jp" -> "ja"
      "kr" -> "ko"
      "cn" -> "zh"
      "cz" -> "cs"
      "dk" -> "da"
      else -> code
    }
  }

  private fun parseTvSubtitlesId(subtitlePageUrl: String): String? =
    Regex("""subtitle-(\d+)""").find(subtitlePageUrl)?.groupValues?.getOrNull(1)

  private fun tvSubtitlesSeasonUrl(
    showUrl: String,
    season: Int,
  ): String {
    val match = Regex("""^(.*?/tvshow-\d+)(?:-\d+)?\.html$""").find(showUrl)
    return match?.let { "${it.groupValues[1]}-$season.html" } ?: showUrl
  }

  private fun tvSubtitlesSubtitlePages(url: String): List<String> {
    parseTvSubtitlesId(url)?.let { return listOf(url) }
    if (!url.contains("episode-", ignoreCase = true)) return emptyList()

    val page = Jsoup.parse(fetchText(url, "text/html", allowNonOk = true), url)
    return page
      .select("a[href*=subtitle-]")
      .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
      .distinctBy { it.lowercase() }
      .take(GENERIC_SUBTITLE_RESULT_LIMIT)
  }

  private fun tvSubtitlesLanguage(
    anchor: Element,
    href: String,
  ): String? {
    val fromAlt = anchor.selectFirst("img[alt]")?.attr("alt")?.takeIf { it.length == 2 }
    val raw = fromAlt ?: href.substringAfterLast("-").take(2).takeIf { it.length == 2 }
    return when (raw?.lowercase()) {
      "br" -> "pt-br"
      "gr" -> "el"
      "ua" -> "uk"
      "jp" -> "ja"
      "cz" -> "cs"
      "cn" -> "zh"
      else -> raw
    }
  }

  private fun String.sanitizeDisplayName(): String = replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "subtitle" }

  private fun absoluteUrl(
    base: String,
    path: String,
  ): String = URL(URL(base), path).toString()

  private fun String.decodeJsString(): String {
    val out = StringBuilder()
    var i = 0
    while (i < length) {
      val c = this[i]
      if (c == '\\' && i + 1 < length) {
        val next = this[i + 1]
        out.append(
          when (next) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            '/', '\\', '\'', '"' -> next
            else -> next
          },
        )
        i += 2
      } else {
        out.append(c)
        i += 1
      }
    }
    return out.toString()
  }

  private fun subtitleCatAbsoluteUrl(path: String): String = URL(URL(SUBTITLECAT_BASE_URL), path).toString()

  private fun selectedLanguages(): Set<String>? {
    val selected = preferences.subtitleSearchLanguages.get()
    if (selected.isEmpty() || selected.contains("all")) return null
    return selected.map { it.normalizeCode() }.toSet()
  }

  private fun String.toLanguageCode(): String {
    val normalized = normalizeCode()
    return WyzieLanguages.ALL.entries
      .firstOrNull { it.value.equals(this, ignoreCase = true) }
      ?.key
      ?: LANGUAGE_ALIASES[normalized]
      ?: normalized
  }

  private fun String.normalizeCode(): String = replace('_', '-').lowercase()

  private fun String.codeEquals(other: String): Boolean = normalizeCode() == other.normalizeCode()

  private fun extensionFromName(value: String): String? =
    app.gyrolet.mpvrx.repository.subtitle.SubtitleArchiveExtractor
      .extensionFromName(value)

  private data class ResolvedDownload(
    val url: String,
    val referer: String? = null,
  )

  private fun OnlineSubtitleSearchRequest.withEpisodeSearchQuery(): OnlineSubtitleSearchRequest =
    if (season != null && episode != null) {
      copy(query = "$query S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}")
    } else {
      this
    }

  private companion object {
    const val TAG = "mpvRxSubtitleHub"
    const val MAX_CONCURRENT_PROVIDER_REQUESTS = 4
    const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14; mpvRx) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    const val SUBTITLECAT_BASE_URL = "https://www.subtitlecat.com/"
    const val MOVIESUBTITLES_ORG_BASE_URL = "https://www.moviesubtitles.org/"
    const val MOVIESUBTITLESRT_BASE_URL = "https://moviesubtitlesrt.com/"
    const val MY_SUBS_BASE_URL = "https://my-subs.co/"
    const val TVSUBTITLES_BASE_URL = "https://www.tvsubtitles.net/"
    const val SUBTITLECAT_SEARCH_RESULT_LIMIT = 5
    const val GENERIC_SEARCH_RESULT_LIMIT = 4
    const val GENERIC_SUBTITLE_RESULT_LIMIT = 12

    val LANGUAGE_ALIASES =
      mapOf(
        "english" to "en",
        "spanish" to "es",
        "french" to "fr",
        "german" to "de",
        "italian" to "it",
        "portuguese" to "pt",
        "brazillian portuguese" to "pt",
        "russian" to "ru",
        "chinese bg code" to "zh-cn",
        "big 5 code" to "zh-tw",
      )
  }
}
