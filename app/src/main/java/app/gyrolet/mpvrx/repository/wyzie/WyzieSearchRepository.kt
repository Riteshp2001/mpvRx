/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.wyzie

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitle
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleFileStore
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleProvider
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchRequest
import app.gyrolet.mpvrx.repository.subtitle.SubtitleProvider
import app.gyrolet.mpvrx.utils.media.resolveSubtitleStorageDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder

@Serializable
data class WyzieSubtitle(
  val id: String? = null,
  val url: String,
  val flagUrl: String? = null,
  val format: String? = null,
  val encoding: String? = null,
  val display: String? = null,
  val language: String? = null,
  val media: String? = null,
  val isHearingImpaired: Boolean = false,
  val source: String? = null,
  val release: String? = null,
  val releases: List<String> = emptyList(),
  val origin: String? = null,
  val fileName: String? = null,
  val matchedRelease: String? = null,
  val matchedFilter: String? = null,
  val downloadCount: Int? = null,
  val isHashMatch: Boolean = false,
  val ai: Boolean = false,
) {
  val displayName: String get() = fileName ?: release ?: media ?: "Unknown Subtitle"
  val displayLanguage: String get() = display ?: language ?: "Unknown"
}

@Serializable
data class WyzieTmdbResult(
  val id: Int,
  val mediaType: String,
  val title: String,
  val releaseYear: String? = null,
  val poster: String? = null,
  val backdrop: String? = null,
  val overview: String? = null,
)

@Serializable
data class WyzieTmdbResponse(
  val results: List<WyzieTmdbResult>,
)

@Serializable
data class WyzieSeason(
  val id: Int? = null,
  val name: String? = null,
  val season_number: Int,
  val episode_count: Int? = null,
  val poster_path: String? = null,
  val overview: String? = null,
)

@Serializable
data class WyzieEpisode(
  val id: Int? = null,
  val name: String? = null,
  val episode_number: Int,
  val season_number: Int,
  val still_path: String? = null,
  val overview: String? = null,
)

@Serializable
data class WyzieTvShowDetails(
  val id: Int,
  val name: String,
  val seasons: List<WyzieSeason> = emptyList(),
)

@Serializable
data class WyzieSeasonDetails(
  val id: String? = null,
  val season_number: Int,
  val episodes: List<WyzieEpisode> = emptyList(),
)

@Serializable
data class WyzieSourceItem(
  val key: String,
  val name: String,
  val tier: String,
  val tags: List<String> = emptyList(),
  val available: Boolean = true,
)

@Serializable
data class WyzieKeyInfo(
  val valid: Boolean = false,
  val type: String? = null,
)

@Serializable
data class WyzieSourcesResponse(
  val sources: List<String> = emptyList(),
  val free: List<String> = emptyList(),
  val paid: List<String> = emptyList(),
  val tiered: List<WyzieSourceItem> = emptyList(),
  val allFree: Boolean = false,
  val key: WyzieKeyInfo? = null,
  val available: List<String> = emptyList(),
  val restricted: List<String> = emptyList(),
)

object WyzieSources {
  val ALL =
    mapOf(
      "all" to "All",
      "bravo" to "Bravo",
      "charlie" to "Charlie",
      "foxtrot" to "Foxtrot",
      "india" to "India",
      "juliet" to "Juliet",
      "lima" to "Lima",
      "mike" to "Mike",
      "november" to "November",
    )
}

object WyzieFormats {
  val ALL =
    mapOf(
      "srt" to "SRT",
      "ass" to "ASS",
      "ssa" to "SSA",
      "vtt" to "VTT",
      "sub" to "SUB",
    )
}

object WyzieEncodings {
  val ALL =
    mapOf(
      "iso-8859-6" to "Arabic (ISO-8859-6)",
      "cp1256" to "Arabic (Cp1256)",
      "cp1257" to "Baltic (Cp1257)",
      "iso-8859-13" to "Baltic (ISO-8859-13)",
      "iso-8859-4" to "Baltic, Scandinavia (ISO-8859-4)",
      "iso-8859-14" to "Celtic (ISO-8859-14)",
      "iso-8859-2" to "Central European, Slavic (ISO-8859-2)",
      "ms936" to "Chinese, Simplified (MS936)",
      "gb18030" to "Chinese, Simplified (GB18030)",
      "euc_cn" to "Chinese, Simplified (EUC_CN)",
      "gbk" to "Chinese, Simplified (GBK)",
      "iso-2022-cn" to "Chinese, Simplified (ISO-2022-CN)",
      "ms950" to "Chinese, Traditional (MS950)",
      "ms950_hkscs" to "Chinese, Traditional (Hong Kong) (MS950_HKSCS)",
      "big5" to "Chinese, Traditional (Big5)",
      "big5-hkscs" to "Chinese, Traditional (Hong Kong) (Big5-HKSCS)",
      "cp1251" to "Cyrillic (Cp1251)",
      "iso-8859-5" to "Cyrillic (ISO-8859-5)",
      "cp1250" to "Eastern European (Cp1250)",
      "cp1253" to "Greek (Cp1253)",
      "iso-8859-7" to "Greek (ISO-8859-7)",
      "iso-8859-8" to "Hebrew (ISO-8859-8)",
      "cp1255" to "Hebrew (Cp1255)",
      "iscii91" to "Indic scripts (ISCII91)",
      "ms932" to "Japanese (MS932)",
      "euc_jp" to "Japanese (EUC_JP)",
      "shift_jis" to "Japanese (Shift_JIS)",
      "iso-2022-jp" to "Japanese (ISO-2022-JP)",
      "ms949" to "Korean (MS949)",
      "euc_kr" to "Korean (EUC_KR)",
      "iso-2022-kr" to "Korean (ISO-2022-KR)",
      "iso-8859-10" to "Nordic (ISO-8859-10)",
      "iso-8859-16" to "Romanian (ISO-8859-16)",
      "koi8_r" to "Russian (KOI8_R)",
      "iso-8859-3" to "South European (ISO-8859-3)",
      "tis-620" to "Thai (TIS-620)",
      "iso-8859-11" to "Thai (ISO-8859-11)",
      "cp1254" to "Turkish (Cp1254)",
      "iso-8859-9" to "Turkish (ISO-8859-9)",
      "utf-8" to "Unicode (UTF-8)",
      "utf-16" to "Unicode (UTF-16)",
      "utf-16be" to "Unicode (UTF-16BE)",
      "utf-16le" to "Unicode (UTF-16LE)",
      "utf-32" to "Unicode (UTF-32)",
      "utf-32be" to "Unicode (UTF-32BE)",
      "utf-32le" to "Unicode (UTF-32LE)",
      "us-ascii" to "(US-ASCII)",
      "cp1258" to "Vietnamese (Cp1258)",
      "iso-8859-1" to "Western European (ISO-8859-1)",
      "iso-8859-15" to "Western European (ISO-8859-15)",
      "cp1252" to "Western European (ANSI) (Cp1252)",
    )
}

object WyzieLanguages {
  val ALL =
    mapOf(
      "en" to "English",
      "es" to "Spanish",
      "fr" to "French",
      "de" to "German",
      "it" to "Italian",
      "pt" to "Portuguese",
      "ru" to "Russian",
      "zh" to "Chinese",
      "ja" to "Japanese",
      "ko" to "Korean",
      "ar" to "Arabic",
      "hi" to "Hindi",
      "bn" to "Bengali",
      "pa" to "Punjabi",
      "jv" to "Javanese",
      "vi" to "Vietnamese",
      "te" to "Telugu",
      "mr" to "Marathi",
      "ta" to "Tamil",
      "ur" to "Urdu",
      "tr" to "Turkish",
      "pl" to "Polish",
      "uk" to "Ukrainian",
      "nl" to "Dutch",
      "el" to "Greek",
      "hu" to "Hungarian",
      "sv" to "Swedish",
      "cs" to "Czech",
      "ro" to "Romanian",
      "da" to "Danish",
      "fi" to "Finnish",
      "no" to "Norwegian",
      "he" to "Hebrew",
      "id" to "Indonesian",
      "ms" to "Malay",
      "th" to "Thai",
      "fa" to "Persian",
      "sk" to "Slovak",
      "bg" to "Bulgarian",
      "hr" to "Croatian",
      "sr" to "Serbian",
      "sl" to "Slovenian",
      "et" to "Estonian",
      "lv" to "Latvian",
      "lt" to "Lithuanian",
      "af" to "Afrikaans",
      "sq" to "Albanian",
      "am" to "Amharic",
      "hy" to "Armenian",
      "az" to "Azerbaijani",
      "eu" to "Basque",
      "be" to "Belarusian",
      "bs" to "Bosnian",
      "ca" to "Catalan",
      "cy" to "Welsh",
      "eo" to "Esperanto",
      "ga" to "Irish",
      "gl" to "Galician",
      "ka" to "Georgian",
      "gu" to "Gujarati",
      "ht" to "Haitian Creole",
      "is" to "Icelandic",
      "kn" to "Kannada",
      "kk" to "Kazakh",
      "km" to "Khmer",
      "ky" to "Kyrgyz",
      "lo" to "Lao",
      "mk" to "Macedonian",
      "mg" to "Malagasy",
      "mt" to "Maltese",
      "mi" to "Maori",
      "mn" to "Mongolian",
      "ne" to "Nepali",
      "ps" to "Pashto",
      "si" to "Sinhala",
      "sw" to "Swahili",
      "tg" to "Tajik",
      "tt" to "Tatar",
      "uz" to "Uzbek",
      "yi" to "Yiddish",
      "yo" to "Yoruba",
      "zu" to "Zulu",
    )
  val SORTED = ALL.toList().sortedBy { it.second }.toMap()
}

class WyzieSearchRepository(
  private val context: Context,
  private val client: OkHttpClient,
  private val json: Json,
  private val preferences: SubtitlesPreferences,
  private val fileStore: OnlineSubtitleFileStore,
) : OnlineSubtitleProvider {
  override val provider: SubtitleProvider = SubtitleProvider.WYZIE

  private val baseUrl = "https://sub.wyzie.io"

  private var cachedSourcesResponse: WyzieSourcesResponse? = null

  suspend fun getSources(forceRefresh: Boolean = false): Result<WyzieSourcesResponse> =
    withContext(Dispatchers.IO) {
      if (!forceRefresh && cachedSourcesResponse != null) {
        return@withContext Result.success(cachedSourcesResponse!!)
      }

      // Try to load from preference cache first if memory cache is empty
      if (cachedSourcesResponse == null) {
        val cachedJson = preferences.wyzieCachedSourcesJson.get()
        if (cachedJson.isNotBlank()) {
          try {
            cachedSourcesResponse = json.decodeFromString<WyzieSourcesResponse>(cachedJson)
          } catch (e: Exception) {
            Log.w("WyzieSearchRepository", "Failed to parse cached sources json", e)
          }
        }
      }

      // If not forcing refresh and we just loaded from preference cache, return it
      if (!forceRefresh && cachedSourcesResponse != null) {
        return@withContext Result.success(cachedSourcesResponse!!)
      }

      try {
        val apiKey = preferences.wyzieApiKey.get().trim()
        val url = "$baseUrl/sources${if (apiKey.isNotBlank()) "?key=${URLEncoder.encode(apiKey, "UTF-8")}" else ""}"
        val request =
          Request
            .Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Failed to fetch sources: ${response.code}")
          val body = response.body.string()
          val parsed = json.decodeFromString<WyzieSourcesResponse>(body)
          cachedSourcesResponse = parsed
          preferences.wyzieCachedSourcesJson.set(body)
          Result.success(parsed)
        }
      } catch (e: Exception) {
        Log.e("WyzieSearchRepository", "Failed to fetch sources from network", e)
        // If we have cached sources, return them as a fallback instead of failing completely
        cachedSourcesResponse?.let {
          return@withContext Result.success(it)
        }
        Result.failure(e)
      }
    }

  override suspend fun search(request: OnlineSubtitleSearchRequest): Result<List<OnlineSubtitle>> =
    search(
      query = request.query,
      tmdbId = request.tmdbId,
      season = request.season,
      episode = request.episode,
      year = request.year,
      movieHash = request.movieHash,
    ).map { subtitles -> subtitles.map { it.toOnlineSubtitle() } }

  override suspend fun download(
    subtitle: OnlineSubtitle,
    mediaTitle: String,
  ): Result<Uri> = download(subtitle.toWyzieSubtitle(), mediaTitle)

  suspend fun search(
    query: String,
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    year: String? = null,
    movieHash: String? = null,
  ): Result<List<WyzieSubtitle>> =
    withContext(Dispatchers.IO) {
      try {
        var searchId = tmdbId?.toString() ?: query
        if (tmdbId == null && !query.startsWith("tt", ignoreCase = true) && !query.all { it.isDigit() }) {
          val tmdbResults = tmdbSearch(query)
          if (tmdbResults.isNotEmpty()) {
            // If year is provided, prefer match with matching release year
            val result =
              if (year != null) {
                tmdbResults.firstOrNull { it.releaseYear == year }
                  ?: tmdbResults.firstOrNull { it.releaseYear?.startsWith(year.take(3)) == true }
                  ?: tmdbResults[0]
              } else {
                tmdbResults[0]
              }
            searchId = result.id.toString()
          } else {
            return@withContext Result.failure(Exception("Could not find media ID for '$query'"))
          }
        }

        val selectedLangsRaw = preferences.subtitleSearchLanguages.get()
        val languages =
          if (selectedLangsRaw.isNotEmpty() && !selectedLangsRaw.contains("all")) {
            selectedLangsRaw.joinToString(",").lowercase()
          } else {
            null
          }

        val sources = preferences.wyzieSources.get()
        val sourceParam =
          if (sources.isEmpty() ||
            sources.contains("all")
          ) {
            "all"
          } else {
            sources.joinToString(",").lowercase()
          }

        val formats = preferences.wyzieFormats.get()
        val formatParam =
          if (formats.isNotEmpty() &&
            !formats.contains("all")
          ) {
            formats.joinToString(",").lowercase()
          } else {
            null
          }

        val encodings = preferences.wyzieEncodings.get()
        val encodingParam =
          if (encodings.isNotEmpty() &&
            !encodings.contains("all")
          ) {
            encodings.joinToString(",").lowercase()
          } else {
            null
          }

        val hearingImpaired = preferences.wyzieHearingImpaired.get()
        val releaseFilter = preferences.wyzieRelease.get()
        val fileFilter = preferences.wyzieFile.get()
        val originFilter = preferences.wyzieOrigin.get()
        val refreshFlag = preferences.wyzieRefresh.get()

        val results =
          fetchSubtitles(
            id = searchId,
            season = season,
            episode = episode,
            language = languages,
            format = formatParam,
            encoding = encodingParam,
            source = sourceParam,
            hi = if (hearingImpaired) true else null,
            movieHash = movieHash,
            release = releaseFilter.takeIf { it.isNotBlank() },
            file = fileFilter.takeIf { it.isNotBlank() },
            origin = originFilter.takeIf { it.isNotBlank() },
            refresh = if (refreshFlag) true else null,
          )

        // The Wyzie API often returns all languages regardless of query parameters.
        // We must strictly filter the results locally based on selected languages.
        var filteredResults =
          if (languages != null && languages != "all") {
            val allowedLangs = languages.split(",").map { it.trim() }
            results.filter { sub ->
              val subLangCode =
                WyzieLanguages.ALL.entries
                  .find {
                    it.value.equals(sub.language, ignoreCase = true)
                  }?.key ?: sub.language?.lowercase()

              allowedLangs.contains(subLangCode)
            }
          } else {
            results
          }

        // Filter by AI subtitle type preference
        val aiPreference = preferences.wyzieAiSubtitles.get()
        filteredResults =
          when (aiPreference) {
            "human" -> filteredResults.filter { !it.ai }
            "ai" -> filteredResults.filter { it.ai }
            else -> filteredResults
          }

        val sortedResults =
          filteredResults.sortedWith(
            compareByDescending<WyzieSubtitle> { it.isHashMatch }
              .thenByDescending { sub ->
                val name = sub.displayName.lowercase()
                val q = query.lowercase()
                var score = 0
                if (name.contains(q)) score += 100
                if (name.contains("720p") || name.contains("1080p") || name.contains("2160p")) score += 50
                if (name.contains("web-dl") || name.contains("webrip") || name.contains("bluray")) score += 40
                if (name.contains("yify") || name.contains("sparks") || name.contains("rarbg")) score += 30
                score
              }.thenByDescending { it.displayName.length },
          )

        Result.success(sortedResults)
      } catch (e: Exception) {
        Log.e("WyzieSearchRepository", "Search failed: ${e.message}", e)
        Result.failure(e)
      }
    }

  private fun fetchSubtitles(
    id: String,
    season: Int? = null,
    episode: Int? = null,
    language: String? = null,
    format: String? = null,
    encoding: String? = null,
    source: String = "all",
    hi: Boolean? = null,
    movieHash: String? = null,
    release: String? = null,
    file: String? = null,
    origin: String? = null,
    refresh: Boolean? = null,
  ): List<WyzieSubtitle> {
    fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    val apiKey = preferences.wyzieApiKey.get().trim()

    val url =
      StringBuilder("$baseUrl/search?id=${encode(id)}")
        .apply {
          if (season != null && episode != null) {
            append("&season=$season")
            append("&episode=$episode")
          }

          // Wyzie API language format: single or multiple language codes are comma separated: `language=en,es`
          language
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(",") { encode(it) }
            ?.let { append("&language=$it") }

          // Format and Encoding parameters
          format
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(",") { encode(it) }
            ?.let { append("&format=$it") }

          encoding
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(",") { encode(it) }
            ?.let { append("&encoding=$it") }

          // Source parameter: 'all' means all sources; otherwise comma-separated list (e.g., source=opensubtitles,subf2m)
          if (source != "all") {
            source
              .split(",")
              .map { it.trim() }
              .filter { it.isNotBlank() }
              .joinToString(",") { encode(it) }
              .let { append("&source=$it") }
          }

          release
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(",") { encode(it) }
            ?.let { append("&release=$it") }

          file
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(",") { encode(it) }
            ?.let { append("&file=$it") }

          origin
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(",") { encode(it) }
            ?.let { append("&origin=$it") }

          refresh?.let { if (it) append("&refresh=true") }
          append("&unzip=true")
          hi?.let { append("&hi=$it") }
          movieHash?.takeIf { it.isNotBlank() }?.let { append("&moviehash=${encode(it)}") }
          if (apiKey.isNotBlank()) {
            append("&key=${encode(apiKey)}")
          }
        }.toString()

    val request = Request.Builder().url(url).build()
    client.newCall(request).execute().use { response ->
      val responseBodyString = response.body.string()
      if (!response.isSuccessful) {
        // Wyzie API returns 400 when no subtitles are found for valid parameters
        if (response.code == 400 && responseBodyString.contains("No subtitles found", ignoreCase = true)) {
          return emptyList()
        }

        if (response.code == 400 && responseBodyString.contains("season and episode", ignoreCase = true)) {
          throw IOException("Please select both a Season and an Episode.")
        }
        val errorMsg = "Search failed: HTTP ${response.code} for URL: $url | Body: $responseBodyString"
        Log.e("WyzieSearchRepository", errorMsg)
        throw IOException(errorMsg)
      }
      return try {
        val parsedResults = json.decodeFromString<List<WyzieSubtitle>>(responseBodyString)
        if (movieHash.isNullOrBlank()) {
          parsedResults
        } else {
          parsedResults.map { subtitle ->
            subtitle.copy(
              isHashMatch =
                subtitle.isHashMatch ||
                  subtitle.matchedFilter?.equals("moviehash", ignoreCase = true) == true,
            )
          }
        }
      } catch (e: Exception) {
        Log.e("WyzieSearchRepository", "Failed to parse response: $responseBodyString", e)
        emptyList()
      }
    }
  }

  suspend fun download(
    subtitle: WyzieSubtitle,
    mediaTitle: String,
  ): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        client.newCall(Request.Builder().url(subtitle.url).build()).execute().use { response ->
          if (!response.isSuccessful) {
            return@withContext Result.failure(
              Exception("Download failed: ${response.code}"),
            )
          }
          Result.success(fileStore.save(response.body.bytes(), subtitle.toOnlineSubtitle(), mediaTitle))
        }
      } catch (e: Exception) {
        Log.e("WyzieSearchRepository", "Download failed", e)
        Result.failure(e)
      }
    }

  suspend fun searchMedia(query: String): Result<List<WyzieTmdbResult>> =
    withContext(Dispatchers.IO) {
      try {
        Result.success(tmdbSearch(query))
      } catch (e: Exception) {
        Log.e("WyzieSearchRepository", "Media search failed", e)
        Result.failure(e)
      }
    }

  suspend fun trendingMedia(limit: Int = 20): Result<List<WyzieTmdbResult>> =
    withContext(Dispatchers.IO) {
      Result.failure(IOException("Trending endpoint no longer available"))
    }

  suspend fun getTvShowDetails(id: Int): Result<WyzieTvShowDetails> =
    withContext(Dispatchers.IO) {
      try {
        val apiKey = preferences.wyzieApiKey.get().trim()
        val url = "$baseUrl/api/tmdb/tv/$id${if (apiKey.isNotBlank()) {
          "?key=${URLEncoder.encode(
            apiKey,
            "UTF-8",
          )}"
        } else {
          ""
        }}"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Failed to get TV show details: ${response.code}")
          val body = response.body.string()
          Result.success(json.decodeFromString<WyzieTvShowDetails>(body))
        }
      } catch (e: Exception) {
        Log.e("WyzieSearchRepository", "Failed to get TV show details", e)
        Result.failure(e)
      }
    }

  suspend fun getSeasonEpisodes(
    id: Int,
    season: Int,
  ): Result<List<WyzieEpisode>> =
    withContext(Dispatchers.IO) {
      try {
        val apiKey = preferences.wyzieApiKey.get().trim()
        val url = "$baseUrl/api/tmdb/tv/$id/$season${if (apiKey.isNotBlank()) {
          "?key=${URLEncoder.encode(
            apiKey,
            "UTF-8",
          )}"
        } else {
          ""
        }}"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Failed to get season episodes: ${response.code}")
          val body = response.body.string()
          Result.success(json.decodeFromString<WyzieSeasonDetails>(body).episodes)
        }
      } catch (e: Exception) {
        Log.e("WyzieSearchRepository", "Failed to get season episodes", e)
        Result.failure(e)
      }
    }

  private fun tmdbSearch(query: String): List<WyzieTmdbResult> {
    val url = "$baseUrl/api/tmdb/search?q=${URLEncoder.encode(query, "UTF-8")}"
    try {
      val request =
        Request
          .Builder()
          .url(url)
          .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
          .build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("TMDB search failed: ${response.code}")
        val body = response.body.string()
        val results = json.decodeFromString<WyzieTmdbResponse>(body).results
        if (results.isEmpty()) throw IOException("TMDB search returned no results")
        return results
      }
    } catch (e: Exception) {
      Log.w("WyzieSearchRepository", "TMDB search failed: ${e.message}")
      throw IOException("TMDB search failed", e)
    }
  }

  suspend fun deleteSubtitleFile(uri: Uri): Boolean =
    withContext(Dispatchers.IO) {
      try {
        val file =
          if (uri.scheme ==
            "content"
          ) {
            DocumentFile.fromSingleUri(context, uri)
          } else {
            DocumentFile.fromFile(
              File(
                uri.path ?: uri.toString(),
              ),
            )
          }
        if (file == null || !file.exists()) return@withContext false
        val deleted = file.delete()
        if (deleted) {
          preferences.subtitleSaveFolder.get().takeIf { it.isNotBlank() }?.let {
            cleanupEmptyFolders(
              Uri.parse(it),
            )
          }
        }
        deleted
      } catch (e: Exception) {
        Log.e("WyzieSearchRepository", "Delete failed", e)
        false
      }
    }

  private fun cleanupEmptyFolders(saveFolderUri: Uri) {
    try {
      val root = resolveSubtitleStorageDirectory(context, saveFolderUri.toString()) ?: return
      root.listFiles().forEach { if (it.isDirectory && it.listFiles().isEmpty()) it.delete() }
    } catch (e: Exception) {
      Log.e("WyzieSearchRepository", "Cleanup failed", e)
    }
  }
}

private fun WyzieSubtitle.toOnlineSubtitle(): OnlineSubtitle =
  OnlineSubtitle(
    provider = SubtitleProvider.WYZIE,
    id = id,
    url = url,
    fileName = fileName,
    release = release,
    media = media,
    displayName = displayName,
    displayLanguage = displayLanguage,
    language = language,
    source = source ?: SubtitleProvider.WYZIE.displayName,
    format = format,
    encoding = encoding,
    downloadCount = downloadCount,
    isHashMatch = isHashMatch,
    isHearingImpaired = isHearingImpaired,
    metadata =
      buildMap {
        matchedFilter?.let { put("matchedFilter", it) }
        matchedRelease?.let { put("matchedRelease", it) }
        origin?.let { put("origin", it) }
        put("ai", ai.toString())
      },
  )

private fun OnlineSubtitle.toWyzieSubtitle(): WyzieSubtitle =
  WyzieSubtitle(
    id = id,
    url = url,
    format = format,
    encoding = encoding,
    display = displayLanguage,
    language = language,
    media = media,
    isHearingImpaired = isHearingImpaired,
    source = source,
    release = release,
    fileName = fileName ?: displayName,
    matchedRelease = metadata["matchedRelease"],
    matchedFilter = metadata["matchedFilter"],
    downloadCount = downloadCount,
    isHashMatch = isHashMatch,
  )
