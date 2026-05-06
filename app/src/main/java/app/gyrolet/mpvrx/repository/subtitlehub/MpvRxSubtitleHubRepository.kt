package app.gyrolet.mpvrx.repository.subtitlehub

import android.content.Context
import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitle
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleFileStore
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleProvider
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchRequest
import app.gyrolet.mpvrx.repository.subtitle.SubtitleProvider
import app.gyrolet.mpvrx.repository.subtitlecat.SubtitleCatHtmlParser
import app.gyrolet.mpvrx.repository.wyzie.WyzieLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder

object MpvRxSubtitleHubSources {
  val ALL =
    linkedMapOf(
      "all" to "All verified SubtitleHub sources",
      "subdl_com" to "SubDL.com",
      "subtitlecat_com" to "SubtitleCat",
      "moviesubtitles_org" to "MovieSubtitles.org",
      "moviesubtitlesrt_com" to "MovieSubtitlesRT",
      "yifysubtitles_ch" to "YIFY Subtitles",
      "my_subs_co" to "My Subs",
      "tvsubtitles_net" to "TVSubtitles",
    )

  val DEFAULT = setOf("all")
  val ANDROID_SUPPORTED =
    setOf(
      "subdl_com",
      "subtitlecat_com",
      "moviesubtitles_org",
      "moviesubtitlesrt_com",
      "yifysubtitles_ch",
      "my_subs_co",
      "tvsubtitles_net",
    )

  fun resolveSelected(selected: Set<String>): Set<String> =
    if (selected.isEmpty() || selected.contains("all")) {
      ANDROID_SUPPORTED
    } else {
      selected.intersect(ANDROID_SUPPORTED)
    }
}

class MpvRxSubtitleHubRepository(
  @Suppress("unused") private val context: Context,
  private val client: OkHttpClient,
  private val json: Json,
  private val preferences: SubtitlesPreferences,
  private val fileStore: OnlineSubtitleFileStore,
) : OnlineSubtitleProvider {
  override val provider: SubtitleProvider = SubtitleProvider.MPVRX_SUBTITLE_HUB

  @Volatile private var buildId: String? = null

  override suspend fun search(request: OnlineSubtitleSearchRequest): Result<List<OnlineSubtitle>> =
    withContext(Dispatchers.IO) {
      try {
        val query = buildSearchQuery(request)
        val adjustedRequest = request.copy(query = query)
        val selectedSources =
          SubtitleHubSearchMatcher.sourcesFor(
            request = adjustedRequest,
            selectedSources = MpvRxSubtitleHubSources.resolveSelected(preferences.subtitleHubSources.get()),
          )
        val results = coroutineScope {
          selectedSources.map { source ->
            async {
              runCatching {
                when (source) {
                  "subdl_com" -> searchSubdlCom(adjustedRequest)
                  "subtitlecat_com" -> searchSubtitleCat(adjustedRequest)
                  "moviesubtitles_org" -> searchMovieSubtitlesOrg(adjustedRequest)
                  "moviesubtitlesrt_com" -> searchMovieSubtitlesRt(adjustedRequest)
                  "yifysubtitles_ch" -> searchYifySubtitles(adjustedRequest)
                  "my_subs_co" -> searchMySubs(adjustedRequest)
                  "tvsubtitles_net" -> searchTvSubtitles(adjustedRequest)
                  else -> emptyList()
                }
              }.getOrElse { error ->
                Log.w(TAG, "Skipping $source after provider failure", error)
                emptyList()
              }
            }
          }.flatMap { it.await() }
        }

        Result.success(
          results
            .distinctBy { it.url.lowercase() }
            .filterNot { it.format.equals("html", ignoreCase = true) }
            .sortedWith(
              compareByDescending<OnlineSubtitle> { it.downloadCount ?: 0 }
                .thenBy { it.source ?: "" }
                .thenBy { it.displayName.lowercase() },
            ),
        )
      } catch (e: Exception) {
        Log.e(TAG, "MpvRx SubtitleHub search failed", e)
        Result.failure(e)
      }
    }

  override suspend fun download(
    subtitle: OnlineSubtitle,
    mediaTitle: String,
  ): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        val downloadUrl = resolveDownloadUrlIfNeeded(subtitle)
        val response =
          client.newCall(
            Request.Builder()
              .url(downloadUrl)
              .header("User-Agent", USER_AGENT)
              .build(),
          ).execute()
        if (!response.isSuccessful) return@withContext Result.failure(Exception("Download failed: ${response.code}"))
        Result.success(fileStore.save(response.body.bytes(), subtitle, mediaTitle))
      } catch (e: Exception) {
        Log.e(TAG, "MpvRx SubtitleHub download failed", e)
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
        SubtitleCatHtmlParser.parseDownloadLinks(detailHtml)
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
          }
          .toList()
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
      doc.select("div.inside-article header h2 a[href], article h2 a[href]")
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
        val title = page.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: listingTitle
        val metadata = parseLabelValueRows(page)
        val languageRaw = metadata["language"] ?: metadata["subtitle language"]
        if (!matchesSelectedLanguage(languageRaw, selectedLanguages)) return@runCatching null

        val downloadUrl =
          page.selectFirst("a[href$=.zip], a[href*=download]")
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
      doc.select("div[style*=width:500px] a[href], a[href^=/movie-][href$=.html]")
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
        val pageTitle = page.selectFirst("h1, title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: movieTitle
        page.select("a[href*=subtitle-]")
          .asSequence()
          .mapNotNull { anchor ->
            val detailsUrl = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val block = anchor.closestParent { it.tagName() == "div" && it.attr("style").contains("margin-bottom") } ?: anchor.parent()
            val languageRaw = block?.selectFirst("img[src*=flags]")?.attr("src")?.substringBeforeLast(".")?.substringAfterLast("/")
            if (!matchesSelectedLanguage(languageRaw, selectedLanguages)) return@mapNotNull null
            val fileName =
              block?.selectFirst("b")?.text()?.trim()?.takeIf { it.isNotBlank() }
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
          }
          .distinctBy { it.url.lowercase() }
          .take(GENERIC_SUBTITLE_RESULT_LIMIT)
          .toList()
      }.getOrElse { error ->
        Log.w(TAG, "Skipping MovieSubtitles.org result $movieUrl", error)
        emptyList()
      }
    }
  }

  private fun searchYifySubtitles(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> {
    val encoded = URLEncoder.encode(request.query, "UTF-8")
    val root = json.parseToJsonElement(fetchText("$YIFY_BASE_URL/ajax/search/?mov=$encoded", "application/json")) as? JsonArray
      ?: return emptyList()
    val selectedLanguages = selectedLanguages()

    return root.asSequence()
      .mapNotNull { item ->
        val obj = item.obj() ?: return@mapNotNull null
        val title = obj.string("movie") ?: return@mapNotNull null
        val imdb = obj.string("imdb") ?: return@mapNotNull null
        title to "$YIFY_BASE_URL/movie-imdb/$imdb"
      }
      .filter { (title, _) -> SubtitleHubSearchMatcher.matchesQueryTitle(request.query, title) }
      .take(GENERIC_SEARCH_RESULT_LIMIT)
      .flatMap { (title, movieUrl) ->
        runCatching {
          val page = Jsoup.parse(fetchText(movieUrl, "text/html"), movieUrl)
          page.select("table tbody tr, table tr")
            .asSequence()
            .mapNotNull { row ->
              val detailsUrl =
                row.selectFirst("a[href*=/subtitles/]")
                  ?.absUrl("href")
                  ?.takeIf { it.isNotBlank() }
                  ?: return@mapNotNull null
              val languageRaw = row.selectFirst("span.sub-lang")?.text()?.trim()?.takeIf { it.isNotBlank() }
              if (!matchesSelectedLanguage(languageRaw, selectedLanguages)) return@mapNotNull null
              val release = row.selectFirst("a > span.text-muted")?.text()?.trim()?.takeIf { it.isNotBlank() }
              hubSubtitle(
                sourceKey = "yifysubtitles_ch",
                sourceName = "YIFY Subtitles",
                url = yifySubtitleToZipUrl(detailsUrl),
                title = release ?: title,
                fileName = release ?: title,
                languageRaw = languageRaw,
                metadata = mapOf("detailsUrl" to detailsUrl, "movieUrl" to movieUrl),
              )
            }
            .distinctBy { it.url.lowercase() }
            .take(GENERIC_SUBTITLE_RESULT_LIMIT)
            .toList()
        }.getOrElse { error ->
          Log.w(TAG, "Skipping YIFY result $movieUrl", error)
          emptyList()
        }
      }
      .toList()
  }

  private fun searchMySubs(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> {
    val encoded = URLEncoder.encode(request.query, "UTF-8")
    val doc = Jsoup.parse(fetchText("$MY_SUBS_BASE_URL/search.php?key=$encoded", "text/html", allowNonOk = true), MY_SUBS_BASE_URL)
    val selectedLanguages = selectedLanguages()
    val pages =
      doc.select("a[href*=/showlistsubtitles-], a[href*=/film-versions-]")
        .asSequence()
        .map { it.text().trim().ifBlank { it.attr("title").trim() } to it.absUrl("href") }
        .filter { (title, url) -> title.isNotBlank() && url.isNotBlank() }
        .filter { (title, _) -> SubtitleHubSearchMatcher.matchesQueryTitle(request.query, title) }
        .distinctBy { it.second.lowercase() }
        .take(GENERIC_SEARCH_RESULT_LIMIT)
        .toList()

    return pages.flatMap { (title, detailsUrl) ->
      runCatching {
        val pageUrls = linkedSetOf(detailsUrl)
        val root = Jsoup.parse(fetchText(detailsUrl, "text/html", allowNonOk = true), detailsUrl)
        root.select("#saison a[href*=versions-][href*=subtitles]").forEach { anchor ->
          anchor.absUrl("href").takeIf { it.isNotBlank() }?.let { pageUrls += it }
        }

        pageUrls.flatMap { pageUrl ->
          val page = Jsoup.parse(fetchText(pageUrl, "text/html", allowNonOk = true), pageUrl)
          val pageTitle = page.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: title
          page.select("a[href*=/downloads/]")
            .asSequence()
            .mapNotNull { anchor ->
              val downloadPageUrl = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
              val languageRaw = mySubsLanguage(anchor)
              if (!matchesSelectedLanguage(languageRaw, selectedLanguages)) return@mapNotNull null
              val release =
                anchor.selectFirst("strong")?.text()?.trim()?.takeIf { it.isNotBlank() }
                  ?: anchor.parent()?.parent()?.selectFirst("small")?.text()?.trim()?.takeIf { it.isNotBlank() }
                  ?: pageTitle
              hubSubtitle(
                sourceKey = "my_subs_co",
                sourceName = "My Subs",
                url = downloadPageUrl,
                title = release,
                fileName = release,
                languageRaw = languageRaw,
                metadata = mapOf("detailsUrl" to pageUrl, "requiresResolution" to "my_subs_co"),
              )
            }
            .toList()
        }.distinctBy { it.url.lowercase() }.take(GENERIC_SUBTITLE_RESULT_LIMIT)
      }.getOrElse { error ->
        Log.w(TAG, "Skipping My Subs result $detailsUrl", error)
        emptyList()
      }
    }
  }

  private fun searchTvSubtitles(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> {
    val encoded = URLEncoder.encode(request.query, "UTF-8")
    val doc = Jsoup.parse(fetchText("$TVSUBTITLES_BASE_URL/search.php?qs=$encoded", "text/html", allowNonOk = true), TVSUBTITLES_BASE_URL)
    val selectedLanguages = selectedLanguages()
    val shows =
      doc.select(".left_articles a[href*=tvshow-], a[href*=tvshow-]")
        .asSequence()
        .map { it.text().trim() to it.absUrl("href") }
        .filter { (title, url) -> title.isNotBlank() && url.contains("tvshow-") }
        .filter { (title, _) -> SubtitleHubSearchMatcher.matchesQueryTitle(request.query, title) }
        .distinctBy { it.second.lowercase() }
        .take(GENERIC_SEARCH_RESULT_LIMIT)
        .toList()

    return shows.flatMap { (title, showUrl) ->
      runCatching {
        val seasonUrls = linkedSetOf(showUrl)
        val root = Jsoup.parse(fetchText(showUrl, "text/html", allowNonOk = true), showUrl)
        root.select("p.description a[href*=tvshow-]").forEach { anchor ->
          anchor.absUrl("href").takeIf { it.isNotBlank() }?.let { seasonUrls += it }
        }

        seasonUrls.flatMap { seasonUrl ->
          val page = Jsoup.parse(fetchText(seasonUrl, "text/html", allowNonOk = true), seasonUrl)
          page.select("table#table5 tr[align=middle]")
            .asSequence()
            .flatMap { row ->
              val episodeTitle =
                row.selectFirst("td:nth-child(2) a b, td:nth-child(2) a")
                  ?.text()
                  ?.trim()
                  ?.takeIf { it.isNotBlank() }
              row.select("a[href*=subtitle-]")
                .asSequence()
                .mapNotNull { anchor ->
                  val subtitlePageUrl = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                  val subtitleId = parseTvSubtitlesId(subtitlePageUrl) ?: return@mapNotNull null
                  val languageRaw = tvSubtitlesLanguage(anchor, subtitlePageUrl)
                  if (!matchesSelectedLanguage(languageRaw, selectedLanguages)) return@mapNotNull null
                  val fileName = "${episodeTitle ?: title}-${languageRaw ?: "unknown"}.zip"
                  hubSubtitle(
                    sourceKey = "tvsubtitles_net",
                    sourceName = "TVSubtitles",
                    url = "$TVSUBTITLES_BASE_URL/download-$subtitleId.html",
                    title = episodeTitle ?: title,
                    fileName = fileName,
                    languageRaw = languageRaw,
                    metadata =
                      mapOf(
                        "detailsUrl" to subtitlePageUrl,
                        "seasonUrl" to seasonUrl,
                        "requiresResolution" to "tvsubtitles_net",
                      ),
                  )
                }
            }
            .toList()
        }.distinctBy { it.url.lowercase() }.take(GENERIC_SUBTITLE_RESULT_LIMIT)
      }.getOrElse { error ->
        Log.w(TAG, "Skipping TVSubtitles result $showUrl", error)
        emptyList()
      }
    }
  }

  private fun searchSubdlCom(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> {
    val language = selectedSearchLanguage()
    val buildId = ensureBuildId()
    val encodedQuery = URLEncoder.encode(request.query, "UTF-8").replace("+", "%20")
    val url = "https://subdl.com/_next/data/$buildId/$language/search/$encodedQuery.json"
    val root = json.parseToJsonElement(fetchText(url, "application/json")).obj() ?: return emptyList()
    val results =
      root.obj("pageProps")
        ?.array("list")
        ?.mapNotNull { it.obj()?.toSubdlSearchItem() }
        ?.filter { result -> SubtitleHubSearchMatcher.matchesQueryTitle(request.query, result.name) }
        ?.take(SUBDL_SEARCH_RESULT_LIMIT)
        ?: emptyList()

    return results.flatMap { result ->
      runCatching { fetchSubdlComSubtitles(result, request, buildId) }
        .getOrElse { error ->
          Log.w(TAG, "Skipping SubDL.com result ${result.name}", error)
          emptyList()
        }
    }
  }

  private fun fetchSubdlComSubtitles(
    result: SubdlSearchItem,
    request: OnlineSubtitleSearchRequest,
    buildId: String,
  ): List<OnlineSubtitle> {
    val baseProps = fetchSubdlPageProps(buildId, result.sdId, result.slug, seasonSlug = null)
    val mediaType = baseProps.obj("movieInfo")?.string("type") ?: result.mediaType
    val props =
      if (mediaType == "tv") {
        val selectedSeasonSlug = findSeasonSlug(baseProps, request.season)
        if (selectedSeasonSlug != null) {
          fetchSubdlPageProps(buildId, result.sdId, result.slug, selectedSeasonSlug)
        } else {
          baseProps
        }
      } else {
        baseProps
      }

    val selectedLanguages = selectedLanguages()
    val grouped = props.obj("groupedSubtitles") ?: return emptyList()
    val subtitles = mutableListOf<OnlineSubtitle>()

    grouped.forEach { (languageLabel, value) ->
      val languageCode = languageLabel.toLanguageCode()
      if (selectedLanguages != null && selectedLanguages.none { it.codeEquals(languageCode) || it.codeEquals(languageLabel) }) return@forEach

      val rows = value as? JsonArray ?: return@forEach
      rows.forEach { row ->
        val subtitle = row.obj() ?: return@forEach
        val episode = subtitle.int("episode")
        if (request.episode != null && episode != null && episode != request.episode) return@forEach
        val link = subtitle.string("link") ?: return@forEach
        val fileName = subtitle.string("title")?.takeIf { it.isNotBlank() } ?: result.name
        val downloads = subtitle.int("downloads")
        val downloadUrl = "https://dl.subdl.com/subtitle/$link"

        subtitles +=
          OnlineSubtitle(
            provider = provider,
            id = "subdl_com:${subtitle.string("id") ?: link}",
            url = downloadUrl,
            fileName = fileName,
            release = subtitle.array("releases")?.firstOrNull()?.stringValue(),
            media = result.name,
            displayName = fileName,
            displayLanguage = WyzieLanguages.ALL[languageCode] ?: languageLabel,
            language = languageCode,
            source = "SubDL.com",
            format = SubtitleHubSearchMatcher.displayFormat(extensionFromName(fileName) ?: extensionFromName(downloadUrl)),
            downloadCount = downloads,
            isHearingImpaired = subtitle.bool("hi") == true,
            metadata =
              buildMap {
                put("quality", subtitle.string("quality").orEmpty())
                put("author", subtitle.string("author").orEmpty())
                subtitle.int("season")?.let { put("season", it.toString()) }
                episode?.let { put("episode", it.toString()) }
              }.filterValues { it.isNotBlank() },
          )
      }
    }

    return subtitles
  }

  private fun fetchSubdlPageProps(
    buildId: String,
    sdId: String,
    slug: String,
    seasonSlug: String?,
  ): JsonObject {
    val url =
      if (seasonSlug != null) {
        "https://subdl.com/_next/data/$buildId/subtitle/$sdId/$slug/$seasonSlug.json"
      } else {
        "https://subdl.com/_next/data/$buildId/subtitle/$sdId/$slug.json"
      }
    val root = json.parseToJsonElement(fetchText(url, "application/json")).obj()
      ?: throw IllegalStateException("Invalid SubDL JSON")
    return root.obj("pageProps") ?: throw IllegalStateException("SubDL pageProps missing")
  }

  private fun findSeasonSlug(
    pageProps: JsonObject,
    wantedSeason: Int?,
  ): String? {
    val seasons = pageProps.obj("movieInfo")?.array("seasons") ?: return null
    if (seasons.isEmpty()) return null
    if (wantedSeason == null) return seasons.firstOrNull()?.obj()?.string("number")

    return seasons.firstNotNullOfOrNull { entry ->
      val season = entry.obj() ?: return@firstNotNullOfOrNull null
      val number = season.string("number")
      val name = season.string("name").orEmpty()
      when {
        number?.contains(wantedSeason.toString(), ignoreCase = true) == true -> number
        name.contains("season $wantedSeason", ignoreCase = true) -> number
        name.contains("s$wantedSeason", ignoreCase = true) -> number
        else -> null
      }
    } ?: seasons.getOrNull(wantedSeason - 1)?.obj()?.string("number")
  }

  private fun ensureBuildId(): String {
    buildId?.let { return it }
    val html = fetchText("https://subdl.com/api-doc", "text/html")
    val found = Regex(""""buildId"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
      ?: throw IllegalStateException("SubDL build id not found")
    buildId = found
    return found
  }

  private fun fetchText(
    url: String,
    accept: String,
    allowNonOk: Boolean = false,
    customize: Request.Builder.() -> Unit = {},
  ): String {
    val builder =
      Request.Builder()
        .url(url)
        .header("Accept", accept)
        .header("User-Agent", USER_AGENT)
    builder.customize()

    client.newCall(builder.build()).execute().use { response ->
      if (!response.isSuccessful && !allowNonOk) {
        if (url.contains("/_next/data/")) buildId = null
        throw IllegalStateException("HTTP ${response.code} for $url")
      }
      return response.body.string()
    }
  }

  private fun resolveDownloadUrlIfNeeded(subtitle: OnlineSubtitle): String {
    val url = subtitle.url
    return when {
      subtitle.metadata["requiresResolution"] == "moviesubtitles_org" -> resolveMovieSubtitlesOrgDownloadUrl(url)
      url.contains("my-subs.co/downloads/") -> resolveMySubsDownloadUrl(url)
      url.contains("tvsubtitles.net/download-") -> resolveTvSubtitlesDownloadUrl(url)
      else -> url
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
    val scriptPath = parseDocumentLocationConcat(html) ?: parseTvSubtitlesZipPath(html)
      ?: throw IllegalStateException("TVSubtitles download URL not found")
    return absoluteUrl(TVSUBTITLES_BASE_URL, scriptPath.replace(" ", "%20"))
  }

  private fun resolveMovieSubtitlesOrgDownloadUrl(downloadPageUrl: String): String {
    val html = fetchText(downloadPageUrl, "text/html", allowNonOk = true)
    val page = Jsoup.parse(html, downloadPageUrl)
    return page.selectFirst("a[href$=.zip], a[href*=files][href$=.zip], a[href*=download][href$=.zip]")
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
      displayLanguage = languageCode?.let { WyzieLanguages.ALL[it] } ?: languageRaw?.takeIf { it.isNotBlank() } ?: "Unknown",
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
      val label = cells.first()?.text()?.trim()?.trimEnd(':')?.lowercase().orEmpty()
      val value = cells.last()?.text()?.trim().orEmpty()
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

  private fun yifySubtitleToZipUrl(detailsUrl: String): String =
    detailsUrl.replace("/subtitles/", "/subtitle/") + ".zip"

  private fun mySubsLanguage(anchor: Element): String? {
    val flag = anchor.selectFirst("span[class*=flag-icon-]") ?: return null
    val className = flag.classNames().firstOrNull { it.startsWith("flag-icon-") } ?: return null
    val code = className.removePrefix("flag-icon-")
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

  private fun selectedSearchLanguage(): String {
    val selected = preferences.subdlLanguages.get()
    val first = selected.firstOrNull { it != "all" }?.lowercase() ?: "en"
    return if (first in SUPPORTED_SUBDL_SEARCH_LANGUAGES) first else "en"
  }

  private fun selectedLanguages(): Set<String>? {
    val selected = preferences.subdlLanguages.get()
    if (selected.isEmpty() || selected.contains("all")) return null
    return selected.map { it.normalizeCode() }.toSet()
  }

  private fun JsonObject.toSubdlSearchItem(): SubdlSearchItem? {
    val mediaType = string("type") ?: return null
    val sdId = string("sd_id") ?: return null
    val slug = string("slug") ?: return null
    return SubdlSearchItem(
      mediaType = mediaType,
      name = string("name") ?: slug,
      year = int("year"),
      sdId = sdId,
      slug = slug,
    )
  }

  private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

  private fun JsonObject.obj(key: String): JsonObject? = get(key).obj()

  private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

  private fun JsonObject.string(key: String): String? = get(key).stringValue()

  private fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.int(key: String): Int? =
    (get(key) as? JsonPrimitive)?.intOrNull

  private fun JsonObject.bool(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.booleanOrNull

  private fun String.toLanguageCode(): String {
    val normalized = normalizeCode()
    return WyzieLanguages.ALL.entries.firstOrNull { it.value.equals(this, ignoreCase = true) }?.key
      ?: LANGUAGE_ALIASES[normalized]
      ?: normalized
  }

  private fun String.normalizeCode(): String = replace('_', '-').lowercase()

  private fun String.codeEquals(other: String): Boolean = normalizeCode() == other.normalizeCode()

  private fun extensionFromName(value: String): String? =
    value.substringBefore("?")
      .substringAfterLast("/")
      .substringAfterLast(".", "")
      .lowercase()
      .takeIf { it.isNotBlank() && it.length <= 5 }

  private data class SubdlSearchItem(
    val mediaType: String,
    val name: String,
    val year: Int?,
    val sdId: String,
    val slug: String,
  )

  private fun buildSearchQuery(request: OnlineSubtitleSearchRequest): String {
    return if (request.season != null && request.episode != null) {
      "${request.query} S${request.season.toString().padStart(2, '0')}E${request.episode.toString().padStart(2, '0')}"
    } else {
      request.query
    }
  }

  private companion object {
    const val TAG = "MpvRxSubtitleHub"
    const val USER_AGENT = "MpvRx SubtitleHub (+https://subdl.com)"
    const val SUBTITLECAT_BASE_URL = "https://www.subtitlecat.com/"
    const val MOVIESUBTITLES_ORG_BASE_URL = "https://www.moviesubtitles.org/"
    const val MOVIESUBTITLESRT_BASE_URL = "https://moviesubtitlesrt.com/"
    const val YIFY_BASE_URL = "https://yifysubtitles.ch/"
    const val MY_SUBS_BASE_URL = "https://my-subs.co/"
    const val TVSUBTITLES_BASE_URL = "https://www.tvsubtitles.net/"
    const val SUBTITLECAT_SEARCH_RESULT_LIMIT = 5
    const val SUBDL_SEARCH_RESULT_LIMIT = 4
    const val GENERIC_SEARCH_RESULT_LIMIT = 4
    const val GENERIC_SUBTITLE_RESULT_LIMIT = 12

    val SUPPORTED_SUBDL_SEARCH_LANGUAGES =
      setOf(
        "ar", "pt", "da", "nl", "en", "fa", "ps", "fi", "fr", "id", "it", "no", "ro", "es", "sv",
        "vi", "sq", "az", "azb", "be", "bn", "zh-tw", "bs", "bg", "bg-en", "my", "ca", "zh-cn",
        "hr", "cs", "nl-en", "en-de", "eo", "et", "ka", "de", "el", "kl", "he", "hi", "hu",
        "hu-en", "is", "ja", "ko", "ku", "lv", "lt", "mk", "ms", "ml", "mni", "pl", "ru", "sr",
        "si", "sk", "sl", "tl", "ta", "te", "th", "tr", "uk", "ur",
      )

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
