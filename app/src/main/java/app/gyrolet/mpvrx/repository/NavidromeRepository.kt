/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.data.navidrome.NavidromeClient
import app.gyrolet.mpvrx.data.navidrome.NavidromeSearchResult
import app.gyrolet.mpvrx.database.dao.NavidromeServerDao
import app.gyrolet.mpvrx.database.entities.NavidromeServerEntity
import app.gyrolet.mpvrx.domain.navidrome.NavidromeAlbum
import app.gyrolet.mpvrx.domain.navidrome.NavidromeArtist
import app.gyrolet.mpvrx.domain.navidrome.NavidromePlaylist
import app.gyrolet.mpvrx.domain.navidrome.NavidromeServer
import app.gyrolet.mpvrx.domain.navidrome.NavidromeSong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

class NavidromeRepository(
  private val dao: NavidromeServerDao,
  private val client: NavidromeClient,
) {
  val allServers: Flow<List<NavidromeServer>> =
    dao.getAllServers().map { list -> list.map { it.toDomain() } }

  val favoriteUpdates = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 64)

  suspend fun getServerById(id: Long): NavidromeServer? = dao.getServerById(id)?.toDomain()

  suspend fun saveServer(server: NavidromeServer): Long = dao.insert(NavidromeServerEntity.fromDomain(server))

  suspend fun updateServer(server: NavidromeServer) = dao.update(NavidromeServerEntity.fromDomain(server))

  suspend fun deleteServer(server: NavidromeServer) = dao.delete(NavidromeServerEntity.fromDomain(server))

  suspend fun deleteServerById(id: Long) = dao.deleteById(id)

  suspend fun ping(server: NavidromeServer): Result<Boolean> = client.ping(server)

  suspend fun getArtists(server: NavidromeServer): Result<List<NavidromeArtist>> = client.getArtists(server)

  suspend fun getArtist(
    server: NavidromeServer,
    artistId: String,
  ): Result<NavidromeArtist> = client.getArtist(server, artistId)

  suspend fun getAlbums(
    server: NavidromeServer,
    type: String = "alphabeticalByName",
    size: Int = 500,
    offset: Int = 0,
  ): Result<List<NavidromeAlbum>> = client.getAlbums(server, type, size, offset)

  suspend fun getAlbum(
    server: NavidromeServer,
    albumId: String,
  ): Result<NavidromeAlbum> = client.getAlbum(server, albumId)

  suspend fun getRandomSongs(
    server: NavidromeServer,
    size: Int = 50,
  ): Result<List<NavidromeSong>> = client.getRandomSongs(server, size)

  suspend fun getPlaylists(server: NavidromeServer): Result<List<NavidromePlaylist>> = client.getPlaylists(server)

  suspend fun getPlaylist(
    server: NavidromeServer,
    playlistId: String,
  ): Result<NavidromePlaylist> = client.getPlaylist(server, playlistId)

  suspend fun search(
    server: NavidromeServer,
    query: String,
  ): Result<NavidromeSearchResult> = client.search(server, query)

  suspend fun getSong(
    server: NavidromeServer,
    songId: String,
  ): Result<NavidromeSong> = client.getSong(server, songId)

  suspend fun getStarred(server: NavidromeServer): Result<List<NavidromeSong>> = client.getStarred(server)

  suspend fun toggleFavorite(
    server: NavidromeServer,
    song: NavidromeSong,
    isFavorite: Boolean,
  ): Result<Unit> = toggleFavorite(server, song.id, isFavorite)

  suspend fun toggleFavorite(
    server: NavidromeServer,
    songId: String,
    isFavorite: Boolean,
  ): Result<Unit> {
    val res =
      if (isFavorite) {
        client.starItem(server, id = songId)
      } else {
        client.unstarItem(server, id = songId)
      }
    if (res.isSuccess) {
      favoriteUpdates.tryEmit(songId to isFavorite)
    }
    return res
  }

  suspend fun toggleAlbumFavorite(
    server: NavidromeServer,
    album: NavidromeAlbum,
    isFavorite: Boolean,
  ): Result<Unit> =
    if (isFavorite) {
      client.starItem(server, albumId = album.id)
    } else {
      client.unstarItem(server, albumId = album.id)
    }

  suspend fun toggleArtistFavorite(
    server: NavidromeServer,
    artist: NavidromeArtist,
    isFavorite: Boolean,
  ): Result<Unit> =
    if (isFavorite) {
      client.starItem(server, artistId = artist.id)
    } else {
      client.unstarItem(server, artistId = artist.id)
    }

  fun getStreamUrl(
    server: NavidromeServer,
    songId: String,
  ): String = client.getStreamUrl(server, songId)

  fun getCoverArtUrl(
    server: NavidromeServer,
    coverArtId: String?,
    size: Int = 500,
  ): String? = client.getCoverArtUrl(server, coverArtId, size)

  fun getArtistImageUrl(
    server: NavidromeServer,
    artist: NavidromeArtist,
    size: Int = 500,
  ): String? = client.getArtistImageUrl(server, artist, size)

  fun getSongCoverArtUrl(
    server: NavidromeServer,
    song: NavidromeSong,
    size: Int = 500,
  ): String? = client.getSongCoverArtUrl(server, song, size)
}
