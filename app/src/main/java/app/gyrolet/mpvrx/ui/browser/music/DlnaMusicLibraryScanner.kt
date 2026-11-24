/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.network.DlnaPath
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.repository.NetworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** Index ContentDirectory tags, not media bytes; duplicate folders and songs share UPnP object IDs. */
internal object DlnaMusicLibraryScanner {
  private const val MAX_CONTAINERS = 5_000
  private const val MAX_SONGS = 50_000

  suspend fun scan(
    context: Context,
    repository: NetworkRepository,
    connection: NetworkConnection,
  ): List<MusicSong> = withContext(Dispatchers.IO) {
    val client = repository.createClient(connection.id).getOrThrow()
    try {
      client.connect().getOrThrow()
      val pending = ArrayDeque<NetworkPath>()
      val containers = mutableSetOf(DlnaPath.objectId(NetworkPath.from("/")))
      val songs = linkedMapOf<String, MusicSong>()
      pending.add(NetworkPath.from("/"))
      while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val path = pending.removeFirst()
        for (file in client.listFiles(path.value).getOrThrow()) {
          currentCoroutineContext().ensureActive()
          val child = NetworkPath.from(file.path)
          if (file.isDirectory) {
            if (containers.add(DlnaPath.objectId(child))) {
              if (containers.size > MAX_CONTAINERS) throw IOException(context.getString(R.string.music_dlna_library_too_large))
              pending.add(child)
            }
          } else if (file.mimeType?.startsWith("audio/") == true || DlnaPath.isAudio(child)) {
            val id = DlnaPath.objectId(child)
            val song = file.toSong(context, connection.id)
            val previous = songs[id]
            songs[id] = if (previous == null) song else previous.copy(
              artist = if (previous.artist == context.getString(R.string.music_unknown_artist)) song.artist else previous.artist,
              album = previous.album.ifBlank { song.album },
              albumId = if (!previous.hasAlbumTag && song.hasAlbumTag) song.albumId else previous.albumId,
              hasAlbumTag = previous.hasAlbumTag || song.hasAlbumTag,
              albumArtUri = previous.albumArtUri ?: song.albumArtUri,
              durationMs = previous.durationMs.takeIf { it > 0L } ?: song.durationMs,
              trackNumber = previous.trackNumber.takeIf { it > 0 } ?: song.trackNumber,
            )
            if (songs.size > MAX_SONGS) throw IOException(context.getString(R.string.music_dlna_library_too_large))
          }
        }
      }
      songs.values.toList()
    } finally {
      withContext(NonCancellable) { client.disconnect() }
    }
  }

  private fun NetworkFile.toSong(context: Context, connectionId: Long): MusicSong {
    val uri = NetworkPlaybackUri.create(connectionId, path)
    val albumName = album.orEmpty()
    val artistName = artist?.takeIf(String::isNotBlank) ?: context.getString(R.string.music_unknown_artist)
    return MusicSong(
      id = stableId("$connectionId|${DlnaPath.objectId(NetworkPath.from(path))}"),
      title = name,
      artist = artistName,
      album = albumName,
      albumId = stableId("$connectionId|${artistName.lowercase(Locale.ROOT)}|${albumName.lowercase(Locale.ROOT)}"),
      durationMs = durationMs,
      path = uri,
      uri = Uri.parse(uri),
      dateAdded = lastModified,
      trackNumber = trackNumber,
      year = year,
      albumArtUri = artworkUrl?.let(Uri::parse),
      size = size.coerceAtLeast(0L),
      hasAlbumTag = albumName.isNotBlank(),
      dateModified = lastModified,
      mimeType = mimeType,
    )
  }

  private fun stableId(value: String): Long =
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))).long and Long.MAX_VALUE
}
