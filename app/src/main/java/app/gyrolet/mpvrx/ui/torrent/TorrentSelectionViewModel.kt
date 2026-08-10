/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.torrent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.database.repository.NetworkStreamEntryRepository
import app.gyrolet.mpvrx.domain.torrent.TorrentCatalog
import app.gyrolet.mpvrx.domain.torrent.TorrentFileItem
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingEngine
import app.gyrolet.mpvrx.repository.wyzie.WyzieSearchRepository
import app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.net.URI

data class TorrentSelectionInput(
  val source: String,
  val title: String? = null,
  val description: String? = null,
  val posterUrl: String? = null,
  val backdropUrl: String? = null,
)

data class TorrentArtwork(
  val title: String,
  val description: String? = null,
  val posterUrl: String? = null,
  val backdropUrl: String? = null,
  val releaseYear: String? = null,
  val mediaType: String? = null,
)

sealed interface TorrentSelectionUiState {
  data object Loading : TorrentSelectionUiState

  data class Ready(
    val catalog: TorrentCatalog,
    val artwork: TorrentArtwork,
    val isLookingUpArtwork: Boolean,
    val launchingFileIndex: Int? = null,
  ) : TorrentSelectionUiState

  data class Error(
    val message: String,
  ) : TorrentSelectionUiState
}

data class TorrentSelectionLaunch(
  val source: String,
  val file: TorrentFileItem,
  val preparationId: String,
)

class TorrentSelectionViewModel(
  private val torrentStreamingEngine: TorrentStreamingEngine,
  private val streamEntryRepository: NetworkStreamEntryRepository,
  private val wyzieSearchRepository: WyzieSearchRepository,
) : ViewModel() {
  private val _uiState = MutableStateFlow<TorrentSelectionUiState>(TorrentSelectionUiState.Loading)
  val uiState: StateFlow<TorrentSelectionUiState> = _uiState.asStateFlow()

  private val launchChannel = Channel<TorrentSelectionLaunch>(Channel.BUFFERED)
  val launches = launchChannel.receiveAsFlow()

  private var input: TorrentSelectionInput? = null
  private var loadJob: Job? = null
  private var activePreparationId: String? = null
  private var handedToPlayer = false

  fun initialize(value: TorrentSelectionInput) {
    if (input != null) return
    open(value)
  }

  /** Opens a new torrent in the same picker host, replacing any previous picker session. */
  fun open(value: TorrentSelectionInput) {
    input = value
    load(value)
  }

  fun retry() {
    val currentInput = input ?: return
    load(currentInput)
  }

  fun select(fileIndex: Int) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    if (ready.launchingFileIndex != null) return
    val file = ready.catalog.playableFiles.firstOrNull { it.index == fileIndex } ?: return
    launch(ready.catalog, file)
  }

  fun cancel() {
    loadJob?.cancel()
    loadJob = null
    if (!handedToPlayer) {
      activePreparationId?.let(torrentStreamingEngine::discardPreparation)
    }
    activePreparationId = null
  }

  private fun load(value: TorrentSelectionInput) {
    loadJob?.cancel()
    if (!handedToPlayer) activePreparationId?.let(torrentStreamingEngine::discardPreparation)
    activePreparationId = null
    handedToPlayer = false
    _uiState.value = TorrentSelectionUiState.Loading

    loadJob =
      viewModelScope.launch {
        try {
          val catalog = torrentStreamingEngine.prepareTorrent(value.source)
          activePreparationId = catalog.preparationId

          persistCatalog(catalog)

          val initialArtwork =
            TorrentArtwork(
              title = value.title.safeText(MAX_TITLE_LENGTH) ?: prettyTorrentTitle(catalog.torrentName),
              description = value.description.safeText(MAX_DESCRIPTION_LENGTH),
              posterUrl = safeRemoteImageUrl(value.posterUrl),
              backdropUrl = safeRemoteImageUrl(value.backdropUrl),
            )
          val needsArtworkLookup =
            initialArtwork.description == null ||
              initialArtwork.posterUrl == null ||
              initialArtwork.backdropUrl == null

          _uiState.value =
            TorrentSelectionUiState.Ready(
              catalog = catalog,
              artwork = initialArtwork,
              isLookingUpArtwork = needsArtworkLookup && catalog.playableFiles.size > 1,
            )

          if (catalog.playableFiles.size == 1) {
            launch(catalog, catalog.playableFiles.single())
          } else if (needsArtworkLookup) {
            launchArtworkLookup(catalog, initialArtwork)
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (error: Throwable) {
          activePreparationId = null
          _uiState.value =
            TorrentSelectionUiState.Error(
              error.message.safeText(MAX_ERROR_LENGTH) ?: "Couldn't open this torrent.",
            )
        }
      }
  }

  private suspend fun persistCatalog(catalog: TorrentCatalog) {
    try {
      streamEntryRepository.replaceTorrentFiles(
        canonicalSourceUri = catalog.source,
        infoHash = catalog.infoHash,
        files =
          catalog.playableFiles.map { file ->
            NetworkStreamEntryRepository.TorrentFile(
              index = file.index,
              path = file.path,
              name = file.name,
              size = file.size,
            )
          },
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      // Catalog persistence must never prevent immediate playback.
    }
  }

  private fun launchArtworkLookup(
    catalog: TorrentCatalog,
    currentArtwork: TorrentArtwork,
  ) {
    viewModelScope.launch {
      val query = cleanSearchTitle(currentArtwork.title.ifBlank { catalog.torrentName })
      val match =
        query
          .takeIf { it.length >= MIN_SEARCH_LENGTH }
          ?.let { wyzieSearchRepository.searchMedia(it).getOrNull() }
          ?.firstOrNull { result -> isStrongTitleMatch(query, result) }

      val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return@launch
      if (ready.catalog.preparationId != catalog.preparationId || ready.launchingFileIndex != null) return@launch
      _uiState.value =
        ready.copy(
          artwork =
            currentArtwork.copy(
              title = currentArtwork.title.ifBlank { match?.title.orEmpty() }.ifBlank { catalog.torrentName },
              description = currentArtwork.description ?: match?.overview.safeText(MAX_DESCRIPTION_LENGTH),
              posterUrl = currentArtwork.posterUrl ?: tmdbImageUrl(match?.poster, "w500"),
              backdropUrl = currentArtwork.backdropUrl ?: tmdbImageUrl(match?.backdrop, "w1280"),
              releaseYear = match?.releaseYear,
              mediaType = match?.mediaType,
            ),
          isLookingUpArtwork = false,
        )
    }
  }

  private fun launch(
    catalog: TorrentCatalog,
    file: TorrentFileItem,
  ) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    if (ready.catalog.preparationId != catalog.preparationId || ready.launchingFileIndex != null) return
    _uiState.value = ready.copy(launchingFileIndex = file.index, isLookingUpArtwork = false)
    handedToPlayer = true
    activePreparationId = null
    launchChannel.trySend(
      TorrentSelectionLaunch(
        source = catalog.source,
        file = file,
        preparationId = catalog.preparationId,
      ),
    )
  }

  override fun onCleared() {
    cancel()
    launchChannel.close()
    super.onCleared()
  }

  companion object {
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_DESCRIPTION_LENGTH = 2_000
    private const val MAX_ERROR_LENGTH = 240
    private const val MIN_SEARCH_LENGTH = 3

    fun factory(
      torrentStreamingEngine: TorrentStreamingEngine,
      streamEntryRepository: NetworkStreamEntryRepository,
      wyzieSearchRepository: WyzieSearchRepository,
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
          TorrentSelectionViewModel(torrentStreamingEngine, streamEntryRepository, wyzieSearchRepository) as T
      }
  }
}

private val yearRegex = Regex("\\b(?:19|20)\\d{2}\\b")
private val seasonEpisodeRegex = Regex("(?i)\\bS\\d{1,2}[ ._-]*E\\d{1,3}\\b")
private val seasonRegex = Regex("(?i)\\bS(?:eason)?[ ._-]*\\d{1,2}\\b")
private val knownExtensionRegex = Regex("(?i)\\.(?:torrent|mkv|mp4|m4v|webm|avi|mov|ts|m2ts|mp3|m4a|flac|ogg)$")
private val releaseNoiseRegex =
  Regex(
    "(?i)\\b(?:2160p|1080p|720p|480p|uhd|hdr10?|dv|dolby[ ._-]*vision|bluray|brrip|" +
      "web[ ._-]*dl|webrip|hdtv|x26[45]|hevc|av1|aac|dts|atmos|proper|repack)\\b.*$",
  )
private val titleTokenRegex = Regex("[\\p{L}\\p{N}]+")
private val ignoredTitleTokens = setOf("the", "a", "an")

private fun prettyTorrentTitle(value: String): String =
  value
    .substringAfterLast('/')
    .replace(knownExtensionRegex, "")
    .replace(seasonEpisodeRegex, " ")
    .replace(seasonRegex, " ")
    .replace(releaseNoiseRegex, " ")
    .replace(Regex("[._]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim(' ', '-', '_')
    .ifBlank { "Torrent" }

private fun cleanSearchTitle(value: String): String =
  prettyTorrentTitle(value)
    .replace(Regex("\\s+"), " ")
    .trim()

private fun isStrongTitleMatch(
  query: String,
  result: WyzieTmdbResult,
): Boolean {
  val queryTokens = normalizedTitleTokens(query)
  val resultTokens = normalizedTitleTokens(result.title)
  if (queryTokens.isEmpty() || resultTokens.isEmpty()) return false
  val queryYear = yearRegex.find(query)?.value
  if (queryYear != null && result.releaseYear != null && !result.releaseYear.startsWith(queryYear)) return false
  if (queryTokens == resultTokens) {
    return queryTokens.size >= 2 ||
      (queryYear != null && result.releaseYear?.startsWith(queryYear) == true)
  }
  val shared = queryTokens.intersect(resultTokens).size.toFloat()
  val coverage = shared / maxOf(queryTokens.size, resultTokens.size).toFloat()
  return shared >= 2f && coverage >= 0.82f
}

private fun normalizedTitleTokens(value: String): Set<String> =
  titleTokenRegex
    .findAll(value.lowercase())
    .map { it.value }
    .filterNot { it in ignoredTitleTokens || yearRegex.matches(it) }
    .toSet()

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

private fun String?.safeText(maxLength: Int): String? =
  this
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.take(maxLength)
