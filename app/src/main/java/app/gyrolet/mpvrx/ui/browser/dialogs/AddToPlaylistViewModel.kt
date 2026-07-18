package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class PlaylistOption(
  val playlist: PlaylistEntity,
  val itemCount: Int,
)

class AddToPlaylistViewModel : ViewModel(), KoinComponent {
  private val repository: PlaylistRepository by inject()

  private val _playlistOptions = MutableStateFlow<List<PlaylistOption>>(emptyList())
  val playlistOptions: StateFlow<List<PlaylistOption>> = _playlistOptions.asStateFlow()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      repository.observeAllPlaylists().collectLatest { playlists ->
        _playlistOptions.value = playlists
          .sortedBy { it.name.lowercase() }
          .map { playlist ->
            PlaylistOption(
              playlist = playlist,
              itemCount = repository.getPlaylistItems(playlist.id).size,
            )
          }
      }
    }
  }

  suspend fun createAndAdd(name: String, videos: List<Video>) = withContext(Dispatchers.IO) {
    val playlistId = repository.createPlaylist(name).toInt()
    repository.addItemsToPlaylist(playlistId, videos.asPlaylistItems())
  }

  suspend fun addToPlaylist(playlistId: Int, videos: List<Video>) = withContext(Dispatchers.IO) {
    repository.addItemsToPlaylist(playlistId, videos.asPlaylistItems())
  }

  private fun List<Video>.asPlaylistItems(): List<Pair<String, String>> =
    map { video -> video.path to video.displayName }
}
