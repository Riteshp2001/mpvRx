package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.download.AppDownloadManager
import app.gyrolet.mpvrx.domain.recentlyplayed.repository.RecentlyPlayedRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.ui.browser.music.MusicLibraryScanner
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class AudioMediaLibrary(private val context: Context) : KoinComponent {
  private val playlists: PlaylistRepository by inject()
  private val history: RecentlyPlayedRepository by inject()
  private val downloads: AppDownloadManager by inject()
  private val folders: FoldersPreferences by inject()
  private val browser: BrowserPreferences by inject()
  private val advanced: AdvancedPreferences by inject()

  suspend fun children(parentId: String): List<MediaBrowserCompat.MediaItem> = withContext(Dispatchers.IO) {
    when (parentId) {
      ROOT -> buildList {
        if (AudioQueuePersistence.read(context) != null) add(entry(RESUME, context.getString(R.string.audio_queue_resume), false))
        add(entry(QUEUE, context.getString(R.string.audio_queue_label), true))
        add(entry(SONGS, context.getString(R.string.ui_songs), true))
        add(entry(PLAYLISTS, context.getString(R.string.ui_playlists), true))
        if (advanced.enableRecentlyPlayed.get()) add(entry(RECENTS, context.getString(R.string.pref_advanced_enable_recently_played_title), true))
        add(entry(DOWNLOADS, context.getString(R.string.downloads_title), true))
      }
      PLAYLISTS -> playlists.getAllPlaylists(isAudio = true).map { playlist ->
        entry("$PLAYLIST_PREFIX${playlist.id}", playlist.name, true)
      }
      else -> {
        val parent = parentId.substringBefore(PAGE_MARKER)
        val items = items(parent)
        val requestedPage = parentId.substringAfter(PAGE_MARKER, "").toIntOrNull()
        if (requestedPage == null && items.size > PAGE_SIZE) {
          items.indices.step(PAGE_SIZE).map { start ->
            entry("$parent$PAGE_MARKER${start / PAGE_SIZE}", "${start + 1} - ${minOf(items.size, start + PAGE_SIZE)}", true)
          }
        } else {
          val start = (requestedPage?.coerceAtLeast(0)?.toLong()?.times(PAGE_SIZE) ?: 0L).coerceAtMost(items.size.toLong()).toInt()
          items.drop(start).take(PAGE_SIZE).map { item -> trackEntry(parent, item) }
        }
      }
    }
  }

  suspend fun selection(mediaId: String): SavedAudioQueue? = withContext(Dispatchers.IO) {
    if (mediaId == RESUME) return@withContext AudioQueuePersistence.read(context)
    val uri = Uri.parse(mediaId)
    if (uri.scheme != "mpvrx-audio" || uri.authority != "item") return@withContext null
    val parent = uri.getQueryParameter("parent") ?: return@withContext null
    val stableId = uri.getQueryParameter("id") ?: return@withContext null
    val items = items(parent)
    val index = items.indexOfFirst { it.stableId == stableId }
    if (index < 0) return@withContext null
    SavedAudioQueue(queue = PlaybackQueueState(items = items, currentIndex = index, isExplicitQueue = true))
  }

  suspend fun search(query: String): List<MediaBrowserCompat.MediaItem> = withContext(Dispatchers.IO) {
    if (query.isBlank()) emptyList() else items(SONGS).filter {
      it.title.orEmpty().contains(query.take(256), ignoreCase = true) || it.artist.orEmpty().contains(query.take(256), ignoreCase = true)
    }.take(PAGE_SIZE).map { trackEntry(SONGS, it) }
  }

  private suspend fun items(parentId: String): List<PlaybackItem> = when {
    parentId == QUEUE -> PlaybackSession.queue.value.takeIf { it.hasItems }?.items
      ?: AudioQueuePersistence.read(context)?.queue?.items.orEmpty()
    parentId == SONGS -> {
      val blacklist = folders.blacklistedAudioFolders.get()
      val minimumMs = browser.minimumAudioDurationSeconds.get().coerceAtLeast(0).toLong() * 1000
      MusicLibraryScanner.scanSongs(context).filter { song ->
        song.durationMs >= minimumMs && blacklist.none { path -> song.path == path || song.path.startsWith("${path.trimEnd('/')}/", true) }
      }.map { song ->
        PlaybackItem.fromUri(
          uri = song.uri.toString(), title = song.title, artist = song.artist, mimeType = "audio/*",
          artworkUri = song.albumArtUri?.toString(), durationSeconds = (song.durationMs / 1000).toInt(),
        )
      }
    }
    parentId == RECENTS && advanced.enableRecentlyPlayed.get() -> history.getRecentlyPlayed(500).map {
      PlaybackItem.fromUri(it.filePath, title = it.videoTitle ?: it.fileName, artworkUri = it.artworkUrl)
    }.filter { it.isDefinitelyAudioOnly() }
    parentId == DOWNLOADS -> {
      val coroutineContext = currentCoroutineContext()
      downloads.locations.root().walkTopDown().filter { file ->
        coroutineContext.ensureActive()
        file.isFile && FileTypeUtils.isAudioFile(file)
      }.map { file -> PlaybackItem.fromUri(Uri.fromFile(file).toString(), title = file.nameWithoutExtension, mimeType = "audio/*") }
        .toList().sortedBy { it.title.orEmpty().lowercase() }
    }
    parentId.startsWith(PLAYLIST_PREFIX) -> {
      val id = parentId.removePrefix(PLAYLIST_PREFIX).toIntOrNull()
      if (id == null || playlists.getPlaylistById(id)?.isAudio != true) emptyList() else playlists.getPlaylistItems(id).map { item ->
        PlaybackItem.fromUri(
          uri = item.filePath, title = item.fileName, playlistItemId = item.id,
          artworkUri = item.tvgLogo, mimeType = "audio/*",
          headers = item.userAgent?.let { mapOf("User-Agent" to it) }.orEmpty(),
        )
      }
    }
    else -> emptyList()
  }

  private fun trackEntry(parent: String, item: PlaybackItem): MediaBrowserCompat.MediaItem {
    val mediaId = Uri.Builder().scheme("mpvrx-audio").authority("item")
      .appendQueryParameter("parent", parent).appendQueryParameter("id", item.stableId).build().toString()
    return MediaBrowserCompat.MediaItem(MediaDescriptionCompat.Builder().setMediaId(mediaId)
      .setTitle(item.title ?: item.originalUri.substringAfterLast('/')).setSubtitle(item.artist)
      .setIconUri(item.artworkUri?.let(Uri::parse)).build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
  }

  private fun entry(id: String, title: String, browsable: Boolean) = MediaBrowserCompat.MediaItem(
    MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
    if (browsable) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
  )

  companion object {
    const val ROOT = "root_id"
    const val RESUME = "audio:resume"
    const val QUEUE = "audio:queue"
    private const val SONGS = "audio:songs"
    private const val PLAYLISTS = "audio:playlists"
    private const val PLAYLIST_PREFIX = "audio:playlist:"
    private const val RECENTS = "audio:recents"
    private const val DOWNLOADS = "audio:downloads"
    private const val PAGE_MARKER = "|page="
    private const val PAGE_SIZE = 200
  }
}