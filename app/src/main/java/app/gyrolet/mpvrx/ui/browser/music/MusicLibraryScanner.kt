/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MusicLibraryScanner {

  private const val TAG = "MusicLibraryScanner"
  private val ALBUM_ART_BASE_URI = Uri.parse("content://media/external/audio/albumart")

  suspend fun scanSongs(context: Context): List<MusicSong> = withContext(Dispatchers.IO) {
    val songs = mutableListOf<MusicSong>()
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.ALBUM,
      MediaStore.Audio.Media.ALBUM_ID,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.DATA,
      MediaStore.Audio.Media.DATE_ADDED,
      MediaStore.Audio.Media.TRACK,
      MediaStore.Audio.Media.YEAR,
      MediaStore.Audio.Media.SIZE
    )

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
        val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

        while (cursor.moveToNext()) {
          val id = cursor.getLong(idCol)
          val path = cursor.getString(dataCol) ?: continue
          val file = File(path)
          if (!file.exists()) continue

          val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
          val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
          val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album"
          val albumId = cursor.getLong(albumIdCol)
          val duration = cursor.getLong(durationCol)
          val dateAdded = cursor.getLong(dateAddedCol)
          val track = cursor.getInt(trackCol)
          val year = cursor.getInt(yearCol)
          val size = cursor.getLong(sizeCol)

          val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
          val albumArtUri = if (albumId > 0) ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId) else null

          songs.add(
            MusicSong(
              id = id,
              title = title,
              artist = artist,
              album = album,
              albumId = albumId,
              durationMs = duration,
              path = path,
              uri = contentUri,
              dateAdded = dateAdded,
              trackNumber = track,
              year = year,
              albumArtUri = albumArtUri,
              size = size
            )
          )
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error scanning songs from MediaStore", e)
    }

    songs
  }

  suspend fun scanAlbums(context: Context, songs: List<MusicSong>): List<MusicAlbum> = withContext(Dispatchers.IO) {
    if (songs.isNotEmpty()) {
      // Group songs by albumId/album title for exact matching
      songs.groupBy { if (it.albumId > 0) it.albumId else it.album.hashCode().toLong() }
        .map { (albumId, albumSongs) ->
          val firstSong = albumSongs.first()
          MusicAlbum(
            id = albumId,
            title = firstSong.album,
            artist = firstSong.artist,
            songCount = albumSongs.size,
            year = albumSongs.maxOfOrNull { it.year } ?: 0,
            albumArtUri = firstSong.albumArtUri
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
          val albumCount = artistSongs.map { it.albumId }.distinct().size
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
