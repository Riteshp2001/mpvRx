/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.audiobooks

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.data.network.ServerUrlUtils
import app.gyrolet.mpvrx.database.dao.AudiobookDao
import app.gyrolet.mpvrx.database.entities.AudiobookChapterEntity
import app.gyrolet.mpvrx.database.entities.AudiobookEntity
import app.gyrolet.mpvrx.database.entities.AudiobookTrackEntity
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfBook
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfLibrary
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfServer
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfTrack
import app.gyrolet.mpvrx.preferences.AudiobookSourceProvider
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.repository.AudiobookshelfRepository
import app.gyrolet.mpvrx.ui.player.AudiobookPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class AudiobookshelfUiState(
  val servers: List<AudiobookshelfServer> = emptyList(),
  val activeServer: AudiobookshelfServer? = null,
  val libraries: List<AudiobookshelfLibrary> = emptyList(),
  val activeLibrary: AudiobookshelfLibrary? = null,
  val books: List<AudiobookshelfBook> = emptyList(),
  val detailBook: AudiobookshelfBook? = null,
  val isLoading: Boolean = false,
  val error: String? = null,
  val searchQuery: String = "",
  val isConnectingServer: Boolean = false,
  val connectServerError: String? = null,
)

class AudiobookshelfViewModel(
  application: Application,
) : AndroidViewModel(application), KoinComponent {
  private val repository: AudiobookshelfRepository by inject()
  private val audiobookDao: AudiobookDao by inject()
  private val mediaServerPreferences: MediaServerPreferences by inject()

  private val _uiState = MutableStateFlow(AudiobookshelfUiState())
  val uiState: StateFlow<AudiobookshelfUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      repository.allServers.collect { servers ->
        _uiState.update { current ->
          val currentActive = current.activeServer
          val newActive = if (currentActive != null && servers.any { it.id == currentActive.id }) {
            servers.first { it.id == currentActive.id }
          } else {
            servers.firstOrNull()
          }
          current.copy(servers = servers, activeServer = newActive)
        }
        if (_uiState.value.activeServer != null) {
          loadLibrariesAndBooks()
        }
      }
    }
  }

  fun selectServer(server: AudiobookshelfServer) {
    _uiState.update { it.copy(activeServer = server) }
    loadLibrariesAndBooks()
  }

  fun selectLibrary(library: AudiobookshelfLibrary) {
    val server = _uiState.value.activeServer ?: return
    _uiState.update { it.copy(activeLibrary = library) }
    viewModelScope.launch(Dispatchers.IO) {
      repository.updateServer(server.copy(activeLibraryId = library.id))
      loadBooks(server, library.id)
    }
  }

  fun deleteServer(server: AudiobookshelfServer) {
    viewModelScope.launch(Dispatchers.IO) {
      repository.deleteServer(server)
    }
  }

  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
  }

  fun refresh() {
    loadLibrariesAndBooks()
  }

  private fun loadLibrariesAndBooks() {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(isLoading = true, error = null) }
      val libResult = repository.getLibraries(server)
      if (libResult.isSuccess) {
        val libraries = libResult.getOrDefault(emptyList())
        val activeLib = libraries.firstOrNull { it.id == server.activeLibraryId } ?: libraries.firstOrNull()
        _uiState.update { it.copy(libraries = libraries, activeLibrary = activeLib) }
        if (activeLib != null) {
          loadBooks(server, activeLib.id)
        } else {
          _uiState.update { it.copy(isLoading = false, books = emptyList()) }
        }
      } else {
        _uiState.update {
          it.copy(
            isLoading = false,
            error = libResult.exceptionOrNull()?.message ?: "Failed to load libraries",
          )
        }
      }
    }
  }

  private suspend fun loadBooks(server: AudiobookshelfServer, libraryId: String) {
    val booksResult = repository.getItems(server, libraryId, limit = 500)
    if (booksResult.isSuccess) {
      val rawBooks = booksResult.getOrDefault(emptyList())
      val mergedBooks = rawBooks.map { book ->
        val localId = audiobookDao.findBySource("abs:${server.id}:${book.id}")
        if (localId != null) {
          val local = audiobookDao.getBook(localId)
          if (local != null && local.book.lastPlayedAt > 0) {
            val localProgressMs = local.book.progressMs
            val durMs = if (book.durationMs > 0) book.durationMs else local.durationMs
            val percent = if (durMs > 0) (localProgressMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
            book.copy(
              progressMs = localProgressMs,
              progressPercent = percent,
              isFinished = local.book.finished,
              durationMs = if (book.durationMs > 0) book.durationMs else durMs,
            )
          } else {
            book
          }
        } else {
          book
        }
      }
      _uiState.update {
        it.copy(
          isLoading = false,
          books = mergedBooks,
          error = null,
        )
      }
    } else {
      _uiState.update {
        it.copy(
          isLoading = false,
          books = emptyList(),
          error = booksResult.exceptionOrNull()?.message ?: "Failed to load audiobooks",
        )
      }
    }
  }

  fun openBookDetails(book: AudiobookshelfBook) {
    val server = _uiState.value.activeServer ?: return
    _uiState.update { it.copy(detailBook = book) }
    viewModelScope.launch(Dispatchers.IO) {
      val fullBook = repository.getItemDetails(server, book.id).getOrNull()
      if (fullBook != null) {
        _uiState.update { it.copy(detailBook = fullBook) }
      }
    }
  }

  fun closeBookDetails() {
    _uiState.update { it.copy(detailBook = null) }
  }

  fun connectServer(
    serverUrl: String,
    serverName: String,
    isToken: Boolean,
    username: String = "",
    password: String = "",
    token: String = "",
    existingServer: AudiobookshelfServer? = null,
    onSuccess: () -> Unit,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(isConnectingServer = true, connectServerError = null) }
      val cleanUrl = serverUrl.trim().trimEnd('/')
      val candidateUrls = ServerUrlUtils.generateCandidateUrls(cleanUrl, defaultPort = 13378)
      val urlsToTry = if (candidateUrls.isNotEmpty()) candidateUrls else listOf(cleanUrl)

      var successfulServer: AudiobookshelfServer? = null
      var lastError: String? = null

      for (candUrl in urlsToTry) {
        val result = if (isToken) {
          repository.verifyToken(candUrl, token.trim(), serverName.trim())
        } else {
          repository.login(candUrl, username.trim(), password)
        }

        if (result.isSuccess) {
          successfulServer = result.getOrNull()?.copy(
            id = existingServer?.id ?: 0,
            name = serverName.trim().ifBlank { result.getOrNull()?.name ?: "Audiobookshelf" },
          )
          break
        } else {
          lastError = result.exceptionOrNull()?.message
        }
      }

      if (successfulServer != null) {
        val savedId = if (existingServer != null) {
          repository.updateServer(successfulServer)
          existingServer.id
        } else {
          repository.saveServer(successfulServer)
        }
        val savedServer = successfulServer.copy(id = savedId)
        _uiState.update {
          it.copy(
            isConnectingServer = false,
            connectServerError = null,
            activeServer = savedServer,
          )
        }
        mediaServerPreferences.audiobookSourceProvider.set(AudiobookSourceProvider.AUDIOBOOKSHELF)
        withContext(Dispatchers.Main) {
          onSuccess()
        }
      } else {
        val err = lastError ?: "Failed to connect to Audiobookshelf server"
        _uiState.update {
          it.copy(isConnectingServer = false, connectServerError = err)
        }
      }
    }
  }

  fun playBook(
    context: Context,
    book: AudiobookshelfBook,
    startTrackIndex: Int = 0,
    startPositionMs: Long = 0,
    restart: Boolean = false,
  ) {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch(Dispatchers.IO) {
      // Fetch full details if tracks/chapters are empty
      val fullBook = if (book.tracks.isEmpty() || book.chapters.isEmpty()) {
        repository.getItemDetails(server, book.id).getOrDefault(book)
      } else {
        book
      }

      val sourceKey = "abs:${server.id}:${fullBook.id}"
      val coverUrl = fullBook.coverUrl ?: repository.getCoverUrl(server, fullBook.id)

      val rawTracks = fullBook.tracks.ifEmpty {
        listOf(
          AudiobookshelfTrack(
            id = fullBook.id,
            index = 0,
            title = fullBook.title,
            durationMs = fullBook.durationMs,
          )
        )
      }

      val trackEntities = rawTracks.mapIndexed { index, track ->
        AudiobookTrackEntity(
          id = 0L,
          bookId = 0L,
          uri = repository.getTrackStreamUrl(server, track, fullBook.id),
          fileName = track.title.ifBlank { "Track ${index + 1}" },
          title = track.title.ifBlank { fullBook.title },
          position = index,
          durationMs = track.durationMs,
          size = track.size,
        )
      }

      val existingId = audiobookDao.findBySource(sourceKey)
      val bookId = if (existingId != null) {
        audiobookDao.updateDetails(
          id = existingId,
          title = fullBook.title,
          author = fullBook.author,
          narrator = fullBook.narrator,
          series = fullBook.series,
          seriesPart = fullBook.seriesPart,
        )
        audiobookDao.fillMetadata(
          id = existingId,
          author = fullBook.author,
          narrator = fullBook.narrator,
          series = fullBook.series,
          seriesPart = fullBook.seriesPart,
          description = fullBook.description,
          language = fullBook.language,
          publisher = fullBook.publisher,
          publishedYear = fullBook.publishedYear,
          genre = fullBook.genres.joinToString(", "),
          isbn = fullBook.isbn,
          asin = fullBook.asin,
        )
        existingId
      } else {
        val bookEntity = AudiobookEntity(
          id = 0L,
          sourceKey = sourceKey,
          title = fullBook.title,
          subtitle = fullBook.subtitle,
          author = fullBook.author,
          narrator = fullBook.narrator,
          series = fullBook.series,
          seriesPart = fullBook.seriesPart,
          description = fullBook.description,
          language = fullBook.language,
          publisher = fullBook.publisher,
          publishedYear = fullBook.publishedYear,
          genre = fullBook.genres.joinToString(", "),
          isbn = fullBook.isbn,
          asin = fullBook.asin,
          coverUri = coverUrl,
          playbackSpeed = 1.0f,
          rewindSeconds = 15,
          lastPlayedAt = System.currentTimeMillis(),
          positionMs = if (restart) 0L else fullBook.progressMs,
          progressMs = if (restart) 0L else fullBook.progressMs,
          finished = if (restart) false else fullBook.isFinished,
        )
        audiobookDao.importBook(bookEntity, trackEntities)
      }

      val loadedBook = audiobookDao.getBook(bookId)
      if (loadedBook != null && fullBook.chapters.isNotEmpty()) {
        // Map chapters across all tracks in the book
        for (track in loadedBook.orderedTracks) {
          val trackStartOffset = loadedBook.positionInBook(track.id, 0L)
          val trackEndOffset = trackStartOffset + track.durationMs
          val chaptersForTrack = fullBook.chapters.mapNotNull { chap ->
            if (chap.startMs < trackEndOffset && chap.endMs > trackStartOffset) {
              val localStart = (chap.startMs - trackStartOffset).coerceAtLeast(0L)
              val localEnd = (chap.endMs - trackStartOffset).coerceAtMost(track.durationMs)
              if (localEnd > localStart) {
                AudiobookChapterEntity(
                  trackId = track.id,
                  startMs = localStart,
                  endMs = localEnd,
                  title = chap.title,
                )
              } else null
            } else null
          }
          if (chaptersForTrack.isNotEmpty()) {
            audiobookDao.replaceChapters(track.id, chaptersForTrack)
          }
        }
      }

      AudiobookPlayback.launch(
        context = context,
        bookId = bookId,
        fromBeginning = restart,
      )
    }
  }

  fun syncProgress(book: AudiobookshelfBook, currentTimeSeconds: Double, durationSeconds: Double, isFinished: Boolean = false) {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val progressMs = (currentTimeSeconds * 1000).toLong()
      val percent = if (durationSeconds > 0) (currentTimeSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) else 0f

      // Update local UI state immediately for instant feedback
      _uiState.update { state ->
        val updatedBooks = state.books.map {
          if (it.id == book.id) it.copy(progressMs = progressMs, progressPercent = percent, isFinished = isFinished)
          else it
        }
        val updatedDetail = if (state.detailBook?.id == book.id) {
          state.detailBook.copy(progressMs = progressMs, progressPercent = percent, isFinished = isFinished)
        } else state.detailBook
        state.copy(books = updatedBooks, detailBook = updatedDetail)
      }

      // Update local DB if present
      val sourceKey = "abs:${server.id}:${book.id}"
      audiobookDao.findBySource(sourceKey)?.let { localId ->
        audiobookDao.setFinished(localId, isFinished)
      }

      // Sync to server
      repository.syncProgress(server, book.id, currentTimeSeconds, durationSeconds, isFinished)
    }
  }

  fun updateCover(bookId: String, coverUrl: String) {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val res = repository.updateCoverUrl(server, bookId, coverUrl)
      if (res.isSuccess) {
        _uiState.value.activeLibrary?.let { lib ->
          loadBooks(server, lib.id)
        }
      } else {
        _uiState.update { it.copy(error = res.exceptionOrNull()?.localizedMessage ?: "Failed to update cover on server") }
      }
    }
  }

  fun quickMatch(bookId: String, provider: String = "audible") {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val res = repository.quickMatch(server, bookId, provider)
      if (res.isSuccess) {
        _uiState.value.activeLibrary?.let { lib ->
          loadBooks(server, lib.id)
        }
      } else {
        _uiState.update { it.copy(error = res.exceptionOrNull()?.localizedMessage ?: "Quick match failed on server") }
      }
    }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          AudiobookshelfViewModel(application)
        }
      }
  }
}
