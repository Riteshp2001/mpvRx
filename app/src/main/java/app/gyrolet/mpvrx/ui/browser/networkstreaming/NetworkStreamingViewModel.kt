/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryEntity
import app.gyrolet.mpvrx.database.repository.NetworkStreamEntryRepository
import app.gyrolet.mpvrx.domain.network.ConnectionStatus
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.torrent.isTorrentSource
import app.gyrolet.mpvrx.domain.torrent.parseMagnet
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.repository.wyzie.WyzieSearchRepository
import app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult
import app.gyrolet.mpvrx.repository.wyzie.bestTmdbResult
import app.gyrolet.mpvrx.utils.media.HttpUtils
import app.gyrolet.mpvrx.utils.media.MediaInfoParser
import app.gyrolet.mpvrx.utils.media.MediaUtils
import android.net.Uri
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URI

enum class MediaGroupType {
  TORRENT,
  STREAM,
  YOUTUBE,
}

data class MediaStreamGroup(
  val id: String,
  val groupType: MediaGroupType = MediaGroupType.TORRENT,
  val infoHash: String? = null,
  val title: String,
  val canonicalSourceUri: String,
  val files: List<NetworkStreamEntryEntity>,
  val totalSize: Long,
  val updatedAt: Long,
  val posterUrl: String? = null,
  val backdropUrl: String? = null,
  val overview: String? = null,
  val releaseYear: String? = null,
  val mediaType: String? = null,
)

typealias TorrentStreamGroup = MediaStreamGroup

/**
 * ViewModel for managing network connections and streaming media references.
 * Follows MVVM pattern with proper separation of concerns.
 */
class NetworkStreamingViewModel(
  application: Application,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: NetworkRepository by inject()
  private val streamEntryRepository: NetworkStreamEntryRepository by inject()
  private val wyzieSearchRepository: WyzieSearchRepository by inject()

  private val enrichmentAttempts = mutableMapOf<String, Long>()

  /**
   * Observable list of all saved network connections
   */
  val connections: StateFlow<List<NetworkConnection>> =
    repository
      .getAllConnections()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  /**
   * Observable connection statuses
   */
  val connectionStatuses: StateFlow<Map<Long, ConnectionStatus>> = repository.connectionStatuses

  val recentLinks: StateFlow<List<NetworkStreamEntryEntity>> =
    streamEntryRepository
      .observeRecentNormalEntries()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val torrentFiles: StateFlow<List<NetworkStreamEntryEntity>> =
    streamEntryRepository
      .observeTorrentFileEntries()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val torrentGroups: StateFlow<List<TorrentStreamGroup>> =
    torrentFiles
      .map(::groupTorrentFiles)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val allMediaGroups: StateFlow<List<MediaStreamGroup>> =
    combine(torrentGroups, recentLinks) { torrents, recents ->
      val savedStreams =
        recents.map { entry ->
          val isYt = HttpUtils.isYouTubeUrl(entry.canonicalSourceUri)
          MediaStreamGroup(
            id = "stream:${entry.stableKey}",
            groupType = if (isYt) MediaGroupType.YOUTUBE else MediaGroupType.STREAM,
            infoHash = null,
            title = entry.fileName,
            canonicalSourceUri = entry.canonicalSourceUri,
            files = listOf(entry),
            totalSize = entry.fileSize,
            updatedAt = entry.updatedAt,
            posterUrl = entry.posterUrl,
            backdropUrl = entry.backdropUrl ?: entry.posterUrl,
            overview = entry.overview,
            releaseYear = entry.releaseYear,
            mediaType = if (isYt) "YouTube" else "Stream",
          )
        }
      (torrents + savedStreams).sortedByDescending { it.updatedAt }
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  init {
    viewModelScope.launch {
      torrentGroups.collect { groups ->
        groups.forEach { group ->
          val hash = group.infoHash ?: return@forEach
          if ((group.posterUrl.isNullOrBlank() || group.backdropUrl.isNullOrBlank()) &&
            shouldAttemptEnrichment(hash)
          ) {
            launchArtworkEnrichment(group)
          }
        }
      }
    }
  }

  private fun shouldAttemptEnrichment(hash: String): Boolean {
    val now = System.currentTimeMillis()
    val lastAttempt = enrichmentAttempts[hash] ?: 0L
    if (now - lastAttempt < ENRICHMENT_RETRY_MS) return false
    enrichmentAttempts[hash] = now
    return true
  }

  private fun launchArtworkEnrichment(group: TorrentStreamGroup) {
    val infoHash = group.infoHash ?: return
    viewModelScope.launch {
      val rawTitle = group.title
      val parsed = MediaInfoParser.parse(rawTitle)
      val queryCandidates = mutableListOf<String>()

      if (parsed.title.isNotBlank()) queryCandidates.add(parsed.title)

      val cleaned = cleanSearchTitle(rawTitle)
      if (cleaned.isNotBlank() && !queryCandidates.contains(cleaned)) queryCandidates.add(cleaned)

      val beforeDash = rawTitle.substringBefore('-').trim()
      val cleanedBeforeDash = cleanSearchTitle(beforeDash)
      if (cleanedBeforeDash.length >= MIN_SEARCH_LENGTH && !queryCandidates.contains(cleanedBeforeDash)) {
        queryCandidates.add(cleanedBeforeDash)
      }

      val beforeColon = rawTitle.substringBefore(':').trim()
      val cleanedBeforeColon = cleanSearchTitle(beforeColon)
      if (cleanedBeforeColon.length >= MIN_SEARCH_LENGTH && !queryCandidates.contains(cleanedBeforeColon)) {
        queryCandidates.add(cleanedBeforeColon)
      }

      var results: List<WyzieTmdbResult>? = null
      var successfulQuery = ""

      for (query in queryCandidates) {
        if (query.length < MIN_SEARCH_LENGTH) continue
        val search = wyzieSearchRepository.searchMedia(query)
        val candidateResults = search.getOrNull()
        if (!candidateResults.isNullOrEmpty()) {
          results = candidateResults
          successfulQuery = query
          break
        }
      }

      if (results == null) {
        logTorrentEnrichment(
          rawTitle = rawTitle,
          query = queryCandidates.firstOrNull().orEmpty(),
          year = parsed.year,
          results = null,
          match = null,
          error = "search-failed",
        )
        return@launch
      }

      val match = bestTmdbResult(results, parsed.year)
      logTorrentEnrichment(rawTitle, successfulQuery, parsed.year, results, match)
      if (match != null) {
        val poster = tmdbImageUrl(match.poster, "w500")
        val backdrop = tmdbImageUrl(match.backdrop, "w1280")
        streamEntryRepository.updateTorrentArtwork(
          infoHash = infoHash,
          title = match.title.takeIf(String::isNotBlank) ?: group.title,
          posterUrl = poster,
          backdropUrl = backdrop,
          overview = match.overview?.take(MAX_DESCRIPTION_LENGTH),
          releaseYear = match.releaseYear,
          mediaType = match.mediaType,
        )
      }
    }
  }

  private fun logTorrentEnrichment(
    rawTitle: String,
    query: String,
    year: String?,
    results: List<WyzieTmdbResult>?,
    match: WyzieTmdbResult?,
    error: String? = null,
  ) {
    val outcome =
      when {
        error != null -> "error=$error"
        results == null -> "search=failed"
        match == null -> "no-match(results=${results.size})"
        else ->
          "match=\"${match.title}\" (${match.releaseYear ?: "?"}) results=${results.size} " +
            "poster=${match.poster != null} backdrop=${match.backdrop != null}"
      }
    Log.d(ENRICHMENT_TAG, "artwork raw=\"$rawTitle\" query=\"$query\" year=$year $outcome")
  }

  fun recordSubmittedLink(url: String) {
    val source = url.trim()
    if (source.isBlank() || isTorrentSource(source) || !MediaUtils.isURLValid(source)) return
    viewModelScope.launch {
      val uri = runCatching { Uri.parse(source) }.getOrNull()
      val initialTitle = MediaInfoParser.parseStreamTitle(source)
      streamEntryRepository.saveNormalEntry(
        canonicalSourceUri = source,
        fileName = initialTitle,
      )

      // Asynchronously enrich title and thumbnail for YouTube and network streams
      if (HttpUtils.isYouTubeUrl(uri)) {
        val ytMeta = HttpUtils.fetchYouTubeMetadata(source)
        if (ytMeta != null && ytMeta.title.isNotBlank()) {
          streamEntryRepository.saveNormalEntry(
            canonicalSourceUri = source,
            fileName = ytMeta.title,
            posterUrl = ytMeta.thumbnailUrl,
            backdropUrl = ytMeta.thumbnailUrl,
          )
        }
      } else {
        val betterTitle = HttpUtils.extractFilenameFromUrl(source)
        if (betterTitle != null && !HttpUtils.isLikelyJunkTitle(betterTitle) && betterTitle != initialTitle && betterTitle != uri?.host) {
          streamEntryRepository.saveNormalEntry(
            canonicalSourceUri = source,
            fileName = betterTitle,
          )
        }
      }
    }
  }

  fun deleteStreamEntry(stableKey: String) {
    viewModelScope.launch { streamEntryRepository.delete(stableKey) }
  }

  fun deleteTorrentGroup(group: TorrentStreamGroup) {
    deleteMediaGroup(group)
  }

  fun deleteMediaGroup(group: MediaStreamGroup) {
    viewModelScope.launch {
      val infoHash = group.infoHash
      if (infoHash != null) {
        streamEntryRepository.deleteTorrentGroup(infoHash)
      } else {
        group.files.forEach { streamEntryRepository.delete(it.stableKey) }
      }
    }
  }

  fun saveLinkToMedia(url: String, customTitle: String? = null) {
    val source = url.trim()
    if (source.isBlank()) return
    viewModelScope.launch {
      val uri = runCatching { Uri.parse(source) }.getOrNull()
      val initialTitle = customTitle?.takeIf(String::isNotBlank) ?: MediaInfoParser.parseStreamTitle(source)
      streamEntryRepository.saveNormalEntry(
        canonicalSourceUri = source,
        fileName = initialTitle,
      )
      if (HttpUtils.isYouTubeUrl(uri)) {
        val ytMeta = HttpUtils.fetchYouTubeMetadata(source)
        if (ytMeta != null && ytMeta.title.isNotBlank()) {
          streamEntryRepository.saveNormalEntry(
            canonicalSourceUri = source,
            fileName = ytMeta.title,
            posterUrl = ytMeta.thumbnailUrl,
            backdropUrl = ytMeta.thumbnailUrl,
          )
        }
      } else {
        val betterTitle = HttpUtils.extractFilenameFromUrl(source)
        if (betterTitle != null && !HttpUtils.isLikelyJunkTitle(betterTitle) && betterTitle != initialTitle && betterTitle != uri?.host) {
          streamEntryRepository.saveNormalEntry(
            canonicalSourceUri = source,
            fileName = betterTitle,
          )
        }
      }
    }
  }

  /**
   * Add a new network connection
   */
  fun addConnection(connection: NetworkConnection) {
    viewModelScope.launch {
      repository.addConnection(connection)
    }
  }

  /**
   * Update an existing connection
   */
  fun updateConnection(
    connection: NetworkConnection,
    clearPassword: Boolean = false,
  ) {
    viewModelScope.launch {
      repository.updateConnection(connection, clearPassword)
    }
  }

  /**
   * Delete a connection
   */
  fun deleteConnection(connection: NetworkConnection) {
    viewModelScope.launch {
      repository.deleteConnection(connection)
    }
  }

  /**
   * Connect to a network share
   */
  fun connect(connection: NetworkConnection) {
    viewModelScope.launch {
      repository.connect(connection)
    }
  }

  /**
   * Disconnect from a network share
   */
  fun disconnect(connection: NetworkConnection) {
    viewModelScope.launch {
      repository.disconnect(connection)
    }
  }

  override fun onCleared() {
    super.onCleared()
    // Clean up all connections when ViewModel is destroyed
    viewModelScope.launch {
      repository.disconnectAll()
    }
  }

  companion object {
    private const val MAX_DESCRIPTION_LENGTH = 2000
    private const val MIN_SEARCH_LENGTH = 3
    private const val ENRICHMENT_RETRY_MS = 60_000L
    private const val ENRICHMENT_TAG = "MpvRxTorrentArtwork"

    private fun groupTorrentFiles(entries: List<NetworkStreamEntryEntity>): List<TorrentStreamGroup> =
      entries
        .groupBy { entry -> entry.infoHash?.trim()?.lowercase() ?: "source:${entry.canonicalSourceUri}" }
        .map { (groupKey, groupEntries) ->
          val files =
            groupEntries.sortedWith { e1, e2 ->
              MediaInfoParser.compareMediaFiles(e1.fileName, e1.fileIndex, e2.fileName, e2.fileIndex)
            }
          val newestEntry = groupEntries.maxBy { it.updatedAt }
          val infoHash = newestEntry.infoHash?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
          val groupTitle = groupEntries.firstNotNullOfOrNull { it.groupTitle?.takeIf(String::isNotBlank) }
          val posterUrl = groupEntries.firstNotNullOfOrNull { it.posterUrl?.takeIf(String::isNotBlank) }
          val backdropUrl = groupEntries.firstNotNullOfOrNull { it.backdropUrl?.takeIf(String::isNotBlank) }
          val overview = groupEntries.firstNotNullOfOrNull { it.overview?.takeIf(String::isNotBlank) }
          val releaseYear = groupEntries.firstNotNullOfOrNull { it.releaseYear?.takeIf(String::isNotBlank) }
          val mediaType = groupEntries.firstNotNullOfOrNull { it.mediaType?.takeIf(String::isNotBlank) }
          val computedTitle = groupTitle ?: torrentGroupTitle(newestEntry.canonicalSourceUri, infoHash, files)

          TorrentStreamGroup(
            id = infoHash ?: groupKey,
            infoHash = infoHash,
            title = computedTitle,
            canonicalSourceUri = newestEntry.canonicalSourceUri,
            files = files,
            totalSize = files.fold(0L) { total, file -> safeAdd(total, file.fileSize.coerceAtLeast(0L)) },
            updatedAt = groupEntries.maxOf { it.updatedAt },
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            overview = overview,
            releaseYear = releaseYear,
            mediaType = mediaType,
          )
        }
        .sortedWith(
          compareByDescending<TorrentStreamGroup> { it.updatedAt }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

    private fun torrentGroupTitle(
      source: String,
      infoHash: String?,
      files: List<NetworkStreamEntryEntity>,
    ): String {
      runCatching { parseMagnet(source)?.displayName?.trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() && !looksLikeHash(it) && !looksLikeGarbage(it) }
        ?.let { return it }

      runCatching {
        android.net.Uri.parse(source).lastPathSegment
          ?.substringAfterLast('/')
          ?.removeSuffix(".torrent")
          ?.trim()
      }.getOrNull()?.takeIf {
        it.isNotEmpty() &&
          !it.startsWith("magnet:", ignoreCase = true) &&
          !looksLikeHash(it) &&
          !looksLikeGarbage(it)
      }?.let { return it }

      commonRoot(files)
        ?.takeIf { !looksLikeHash(it) && !looksLikeGarbage(it) }
        ?.let { return it }

      files.firstOrNull()?.fileName
        ?.let { extractNameFromFileName(it) }
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { return it }

      files.singleOrNull()?.fileName
        ?.substringBeforeLast('.', missingDelimiterValue = files.single().fileName)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !looksLikeHash(it) && !looksLikeGarbage(it) }
        ?.let { return it }

      return infoHash?.take(8)?.uppercase()?.let { "Torrent $it" } ?: "Torrent"
    }

    private fun looksLikeHash(value: String): Boolean {
      val trimmed = value.trim()
      if (trimmed.length < 16) return false
      if (trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return true
      if (trimmed.length >= 32 && trimmed.all { it in 'A'..'Z' || it in '2'..'7' }) return true
      return Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(trimmed)
    }

    private fun looksLikeGarbage(value: String): Boolean {
      val trimmed = value.trim()
      if (trimmed.isEmpty()) return true
      if (trimmed.count { it.isLetter() } == 0) return true
      val alphanumeric = trimmed.count { it.isLetterOrDigit() }
      return alphanumeric * 100 < trimmed.length * 50
    }

    private fun extractNameFromFileName(fileName: String): String? {
      val cleaned = fileName
        .substringBeforeLast('.')
        .replace(Regex("[\\[\\]_-]"), " ")
        .trim()

      val seasonEpisodeMatch = Regex("(?i)\\b[Ss](\\d{1,2})[\\s.:_-]*[Ee](\\d{1,4})\\b").find(cleaned)
      if (seasonEpisodeMatch != null) {
        val beforeMatch = cleaned.substring(0, seasonEpisodeMatch.range.first).trim(' ', '-', ':', '.')
        if (beforeMatch.isNotEmpty()) return beforeMatch
      }

      val crossMatch = Regex("(?i)\\b(\\d{1,2})[xX](\\d{1,4})\\b").find(cleaned)
      if (crossMatch != null) {
        val beforeMatch = cleaned.substring(0, crossMatch.range.first).trim(' ', '-', ':', '.')
        if (beforeMatch.isNotEmpty()) return beforeMatch
      }

      val episodeMatch = Regex("(?i)\\b(?:episode|ep)[\\s.:_-]*(\\d{1,4})\\b").find(cleaned)
      if (episodeMatch != null) {
        val beforeMatch = cleaned.substring(0, episodeMatch.range.first).trim(' ', '-', ':', '.')
        if (beforeMatch.isNotEmpty()) return beforeMatch
      }

      val qualityMatch = Regex("(?i)\\b(4K|2160p|1080p|720p|480p|HDR|HDRip|WEBRip|BluRay|BRRip|DVDRip)\\b").find(cleaned)
      if (qualityMatch != null) {
        val beforeMatch = cleaned.substring(0, qualityMatch.range.first).trim(' ', '-', ':', '.')
        if (beforeMatch.isNotEmpty()) return beforeMatch
      }

      return cleaned.takeIf { it.length >= 3 }
    }

    private fun commonRoot(files: List<NetworkStreamEntryEntity>): String? {
      val paths =
        files.map { entry ->
          entry.filePath
            .orEmpty()
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter(String::isNotBlank)
        }
      if (paths.isEmpty() || paths.any { it.isEmpty() }) return null
      val candidate = paths.first().firstOrNull() ?: return null
      return candidate.takeIf { root ->
        paths.all { path -> path.size > 1 && path.first().equals(root, ignoreCase = true) }
      }
    }

    private fun safeAdd(
      first: Long,
      second: Long,
    ): Long = if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    private fun displayNameFor(source: String): String =
      runCatching {
        val uri = android.net.Uri.parse(source)
        uri.lastPathSegment
          ?.substringAfterLast('/')
          ?.takeIf { it.isNotBlank() }
          ?: uri.host?.takeIf { it.isNotBlank() }
          ?: source
      }.getOrDefault(source)

    fun factory(application: Application): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          NetworkStreamingViewModel(application)
        }
      }
  }
}

private val seasonEpisodeRegex = Regex("(?i)\\bS\\d{1,2}[\\s.:_-]*E\\d{1,4}\\b")
private val crossFormatRegex = Regex("(?i)\\b\\d{1,2}x\\d{1,4}\\b")
private val episodeWordRegex = Regex("(?i)\\bep(?:isode)?[\\s.:_-]*\\d{1,4}\\b")
private val seasonRegex = Regex("(?i)\\bS(?:eason)?[\\s.:_-]*\\d{1,2}\\b")
private val knownExtensionRegex = Regex("(?i)\\.(?:torrent|mkv|mp4|m4v|webm|avi|mov|ts|m2ts|mp3|m4a|flac|ogg)$")
private val releaseNoiseRegex =
  Regex(
    "(?i)\\b(?:2160p|1080p|720p|480p|uhd|hdr10?|dv|dolby[ ._-]*vision|bluray|brrip|" +
      "web[ ._-]*dl|webrip|hdtv|x26[45]|hevc|av1|aac|dts|atmos|proper|repack)\\b.*$",
  )

private fun prettyTorrentTitle(value: String): String =
  value
    .substringAfterLast('/')
    .replace(knownExtensionRegex, "")
    .replace(seasonEpisodeRegex, " ")
    .replace(crossFormatRegex, " ")
    .replace(episodeWordRegex, " ")
    .replace(seasonRegex, " ")
    .replace(releaseNoiseRegex, " ")
    .replace(Regex("[\\[\\]【】()（）]"), " ")
    .replace(Regex("[._]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim(' ', '-', '_', ':', '.')
    .ifBlank { "Torrent" }

private fun cleanSearchTitle(value: String): String =
  prettyTorrentTitle(value)
    .replace(Regex("\\s+"), " ")
    .trim()

private fun tmdbImageUrl(
  path: String?,
  size: String,
): String? {
  val value = path?.trim()?.takeIf(String::isNotBlank) ?: return null
  return when {
    safeRemoteImageUrl(value) != null -> safeRemoteImageUrl(value)
    value.startsWith('/') -> "https://image.tmdb.org/t/p/$size$value"
    else -> "https://image.tmdb.org/t/p/$size/$value"
  }
}

private fun safeRemoteImageUrl(value: String?): String? {
  val candidate = value?.trim()?.takeIf(String::isNotBlank) ?: return null
  return runCatching {
    val uri = URI(candidate)
    candidate.takeIf {
      uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        !uri.host.equals("localhost", ignoreCase = true) &&
        uri.host != "127.0.0.1" &&
        uri.host != "::1"
    }
  }.getOrNull()
}
