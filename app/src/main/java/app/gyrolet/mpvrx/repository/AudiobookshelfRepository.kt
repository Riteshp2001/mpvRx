/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.data.audiobookshelf.AudiobookshelfClient
import app.gyrolet.mpvrx.database.dao.AudiobookshelfServerDao
import app.gyrolet.mpvrx.database.entities.AudiobookshelfServerEntity
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfBook
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfLibrary
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfServer
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AudiobookshelfRepository(
  private val dao: AudiobookshelfServerDao,
  private val client: AudiobookshelfClient,
) {
  val allServers: Flow<List<AudiobookshelfServer>> =
    dao.getAllServers().map { list -> list.map { it.toDomain() } }

  suspend fun getServerById(id: Long): AudiobookshelfServer? = dao.getServerById(id)?.toDomain()

  suspend fun saveServer(server: AudiobookshelfServer): Long = dao.insert(AudiobookshelfServerEntity.fromDomain(server))

  suspend fun updateServer(server: AudiobookshelfServer) = dao.update(AudiobookshelfServerEntity.fromDomain(server))

  suspend fun deleteServer(server: AudiobookshelfServer) = dao.delete(AudiobookshelfServerEntity.fromDomain(server))

  suspend fun deleteServerById(id: Long) = dao.deleteById(id)

  suspend fun login(
    serverUrl: String,
    username: String,
    password: String,
  ): Result<AudiobookshelfServer> = client.login(serverUrl, username, password)

  suspend fun verifyToken(
    serverUrl: String,
    token: String,
    name: String = "",
  ): Result<AudiobookshelfServer> = client.verifyToken(serverUrl, token, name)

  suspend fun getLibraries(server: AudiobookshelfServer): Result<List<AudiobookshelfLibrary>> =
    client.getLibraries(server)

  suspend fun getItems(
    server: AudiobookshelfServer,
    libraryId: String,
    page: Int = 0,
    limit: Int = 200,
    sort: String? = null,
    desc: Boolean = false,
    filter: String? = null,
  ): Result<List<AudiobookshelfBook>> = client.getItems(server, libraryId, page, limit, sort, desc, filter)

  suspend fun getItemDetails(
    server: AudiobookshelfServer,
    itemId: String,
  ): Result<AudiobookshelfBook> = client.getItemDetails(server, itemId)

  suspend fun syncProgress(
    server: AudiobookshelfServer,
    itemId: String,
    currentTimeSeconds: Double,
    durationSeconds: Double,
    isFinished: Boolean = false,
  ): Result<Unit> = client.syncProgress(server, itemId, currentTimeSeconds, durationSeconds, isFinished)

  suspend fun syncProgress(
    serverId: Long,
    itemId: String,
    currentTimeSeconds: Double,
    durationSeconds: Double,
    isFinished: Boolean = false,
  ): Result<Unit> {
    val server = getServerById(serverId) ?: return Result.failure(Exception("Server not found"))
    return client.syncProgress(server, itemId, currentTimeSeconds, durationSeconds, isFinished)
  }

  fun getCoverUrl(
    server: AudiobookshelfServer,
    itemId: String,
  ): String = client.getCoverUrl(server, itemId)

  suspend fun updateCoverUrl(
    server: AudiobookshelfServer,
    itemId: String,
    coverUrl: String,
  ): Result<Unit> = client.updateCoverUrl(server, itemId, coverUrl)

  suspend fun quickMatch(
    server: AudiobookshelfServer,
    itemId: String,
    provider: String = "audible",
  ): Result<Unit> = client.quickMatch(server, itemId, provider)

  fun getTrackStreamUrl(
    server: AudiobookshelfServer,
    track: AudiobookshelfTrack,
    bookId: String,
  ): String = client.getTrackStreamUrl(server, track, bookId)
}
