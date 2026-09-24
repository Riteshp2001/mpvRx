/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import app.gyrolet.mpvrx.utils.media.ProgressiveResultsPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

object MusicLibraryScanner {

  private const val TAG = "MusicLibraryScanner"
  private val ALBUM_ART_BASE_URI = Uri.parse("content://media/external/audio/albumart")
  private val albumTagCache = LruCache<String, String>(4096)

  suspend fun scanSongs(
    context: Context,
    onSnapshot: (suspend (List<MusicSong>) -> Unit)? = null,
  ): List<MusicSong> = withContext(Dispatchers.IO) {
    val songs = mutableListOf<MusicSong>()
    val pendingAlbums = mutableListOf<Pair<Int, String?>>()
    val publisher = ProgressiveResultsPublisher(onSnapshot) { songs }
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.ALBUM,
      MediaStore.Audio.Media.ALBUM_ID,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.DATA,
      MediaStore.Audio.Media.DATE_ADDED,
      MediaStore.Audio.Media.DATE_MODIFIED,
      MediaStore.Audio.Media.TRACK,
      MediaStore.Audio.Media.YEAR,
      MediaStore.Audio.Media.SIZE
    ).let { columns ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) columns + MediaStore.MediaColumns.RELATIVE_PATH else columns
    }

    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 1000"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

    try {
      context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        null,
        sortOrder
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        } else -1
        val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

        while (cursor.moveToNext()) {
          currentCoroutineContext().ensureActive()
          val id = cursor.getLong(idCol)
          val path = cursor.getString(dataCol)
          val size = cursor.getLong(sizeCol)
          val duration = cursor.getLong(durationCol)

          // On Android 10+ the DATA column may be null or stale; use content URI as fallback.
          val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
          val effectivePath = path ?: contentUri.toString()
          val file = path?.let { File(it) }
          val fileExists = try { file?.exists() == true } catch (_: Exception) { false }
          if (!fileExists && size <= 0L && duration <= 0L) continue
          if (app.gyrolet.mpvrx.domain.audiobook.AudiobookMarkerUtils.isAudiobookPath(path ?: effectivePath)) continue

          val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: (file?.nameWithoutExtension ?: id.toString())
          val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
          val indexedAlbum = cursor.getString(albumCol)?.trim()?.takeIf { it.isNotBlank() && it != "<unknown>" }
          val folderName = file?.parentFile?.name ?: relativePathCol.takeIf { it >= 0 }?.let { column ->
            cursor.getString(column)?.let { File(it).name }
          }
          val album = indexedAlbum
          if (indexedAlbum == null || folderName == null || indexedAlbum.equals(folderName, ignoreCase = true)) {
            pendingAlbums += songs.size to folderName
          }
          val albumId = if (album?.equals(indexedAlbum, ignoreCase = true) == true) cursor.getLong(albumIdCol) else 0L
          val dateAdded = cursor.getLong(dateAddedCol)
          val track = cursor.getInt(trackCol)
          val year = cursor.getInt(yearCol)

          val albumArtUri = if (albumId > 0) ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId) else null

          songs.add(
            MusicSong(
              id = id,
              title = title,
              artist = artist,
              album = album ?: "Unknown Album",
              albumId = albumId,
              durationMs = duration,
              path = effectivePath,
              uri = contentUri,
              dateAdded = dateAdded,
              trackNumber = track,
              year = year,
              albumArtUri = albumArtUri,
              size = size,
              hasAlbumTag = album != null,
              dateModified = cursor.getLong(dateModifiedCol),
            )
          )
          publisher.publishIfNeeded()
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error scanning songs from MediaStore", e)
      if (onSnapshot != null) throw e
    }

    publisher.publishIfNeeded(force = true)
    for ((index, folderName) in pendingAlbums) {
      currentCoroutineContext().ensureActive()
      val song = songs[index]
      val indexedAlbum = song.album.takeIf { song.hasAlbumTag }
      val album = resolveAlbumTag(context, song.uri, indexedAlbum, folderName, song.dateModified, song.size)
      val albumId = song.albumId.takeIf { album?.equals(indexedAlbum, ignoreCase = true) == true } ?: 0L
      songs[index] = song.copy(
        album = album ?: "Unknown Album",
        albumId = albumId,
        albumArtUri = song.albumArtUri.takeIf { albumId > 0 },
        hasAlbumTag = album != null,
      )
      publisher.publishIfNeeded()
    }
    publisher.publishIfNeeded(force = true)
    songs.toList()
  }

  private fun resolveAlbumTag(
    context: Context,
    uri: Uri,
    indexedAlbum: String?,
    folderName: String?,
    dateModified: Long,
    size: Long,
  ): String? {
    if (indexedAlbum != null && folderName != null && !indexedAlbum.equals(folderName, ignoreCase = true)) {
      return indexedAlbum
    }
    val cacheKey = "$uri:$dateModified:$size:${indexedAlbum.orEmpty()}"
    albumTagCache.get(cacheKey)?.let { return it.takeIf(String::isNotBlank) }
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        ?.trim()?.takeIf { it.isNotBlank() && it != "<unknown>" }
      albumTagCache.put(cacheKey, album.orEmpty())
      album
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      indexedAlbum
    } finally {
      retriever.release()
    }
  }

  suspend fun scanAlbums(context: Context, songs: List<MusicSong>): List<MusicAlbum> = withContext(Dispatchers.IO) {
    if (songs.isNotEmpty()) {
      // Group songs by albumId/album title for exact matching
      songs.filter { it.hasAlbumTag }.groupBy { requireNotNull(it.albumKey) }
        .map { (albumId, albumSongs) ->
          val firstSong = albumSongs.first()
          MusicAlbum(
            id = albumId,
            title = firstSong.album,
            artist = firstSong.artist,
            songCount = albumSongs.size,
            year = albumSongs.maxOfOrNull { it.year } ?: 0,
            albumArtUri = firstSong.albumArtUri,
            artworkSong = firstSong,
          )
        }
        .sortedBy { it.title.lowercase() }
    } else {
      emptyList()
    }
  }

  suspend fun scanArtists(context: Context, songs: List<MusicSong>): List<MusicArtist> = withContext(Dispatchers.IO) {
    if (songs.isNotEmpty()) {
      songs.groupBy { it.artist.lowercase().trim() }
        .map { (_, artistSongs) ->
          val firstSong = artistSongs.first()
          val albumCount = artistSongs.mapNotNull { it.albumKey }.distinct().size
          MusicArtist(
            id = firstSong.artist.hashCode().toLong(),
            name = firstSong.artist,
            songCount = artistSongs.size,
            albumCount = albumCount
          )
        }
        .sortedBy { it.name.lowercase() }
    } else {
      emptyList()
    }
  }
}
