/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.database.dao.JellyfinServerDao
import app.gyrolet.mpvrx.database.entities.JellyfinServerEntity
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinAuthResult
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinUser
import app.gyrolet.mpvrx.utils.media.PlaybackSubtitleTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class JellyfinRepository(
  private val dao: JellyfinServerDao,
  private val client: JellyfinClient,
) {
  val allServers: Flow<List<JellyfinServer>> =
    dao.getAllServers().map { list -> list.map { it.toDomain() } }

  suspend fun getServerById(id: Long): JellyfinServer? =
    dao.getServerById(id)?.toDomain()

  suspend fun saveServer(server: JellyfinServer): Long =
    dao.insert(JellyfinServerEntity.fromDomain(server))

  suspend fun updateServer(server: JellyfinServer) =
    dao.update(JellyfinServerEntity.fromDomain(server))

  suspend fun deleteServer(server: JellyfinServer) =
    dao.delete(JellyfinServerEntity.fromDomain(server))

  suspend fun authenticate(
    serverUrl: String,
    username: String,
    password: String,
  ): Result<JellyfinAuthResult> =
    client.authenticate(serverUrl, username, password)

  suspend fun validateToken(
    serverUrl: String,
    token: String,
  ): Result<JellyfinUser> =
    client.validateToken(serverUrl, token)

  suspend fun getLibraries(server: JellyfinServer): Result<List<JellyfinItem>> =
    client.getUserLibraries(server.serverUrl, server.userId, server.accessToken)

  suspend fun getResumeItems(
    server: JellyfinServer,
    limit: Int = 12,
  ): Result<List<JellyfinItem>> =
    client.getResumeItems(server.serverUrl, server.userId, server.accessToken, limit)

  suspend fun getItem(
    server: JellyfinServer,
    itemId: String,
  ): Result<JellyfinItem> =
    client.getItem(server.serverUrl, server.userId, itemId, server.accessToken)

  suspend fun getItems(
    server: JellyfinServer,
    parentId: String? = null,
    searchTerm: String? = null,
    sortBy: app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.NAME,
    sortOrder: app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder.ASCENDING,
    isPlayed: Boolean? = null,
    startIndex: Int = 0,
    limit: Int = 100,
  ): Result<app.gyrolet.mpvrx.domain.jellyfin.JellyfinQueryResult> =
    client.getItems(
      serverUrl = server.serverUrl,
      userId = server.userId,
      parentId = parentId,
      searchTerm = searchTerm,
      sortBy = sortBy,
      sortOrder = sortOrder,
      isPlayed = isPlayed,
      startIndex = startIndex,
      limit = limit,
      token = server.accessToken,
    )

  suspend fun getSeasons(
    server: JellyfinServer,
    seriesId: String,
  ): Result<List<JellyfinItem>> =
    client.getSeasons(
      serverUrl = server.serverUrl,
      userId = server.userId,
      seriesId = seriesId,
      token = server.accessToken,
    )

  suspend fun getEpisodes(
    server: JellyfinServer,
    seriesId: String,
    seasonId: String,
  ): Result<List<JellyfinItem>> =
    client.getEpisodes(
      serverUrl = server.serverUrl,
      userId = server.userId,
      seriesId = seriesId,
      seasonId = seasonId,
      token = server.accessToken,
    )

  suspend fun getSubtitleTracks(
    server: JellyfinServer,
    itemId: String,
  ): Result<List<PlaybackSubtitleTrack>> =
    client.getSubtitleTracks(
      serverUrl = server.serverUrl,
      token = server.accessToken,
      userId = server.userId,
      itemId = itemId,
    )

  fun getStreamUrl(
    server: JellyfinServer,
    item: JellyfinItem,
  ): String =
    client.getStreamUrl(
      serverUrl = server.serverUrl,
      itemId = item.id,
      token = server.accessToken,
      isAudio = item.isAudio,
    )

  fun getImageUrl(
    server: JellyfinServer,
    item: JellyfinItem,
    maxWidth: Int = 400,
  ): String =
    client.getImageUrl(
      serverUrl = server.serverUrl,
      itemId = item.id,
      imageTag = item.primaryImageTag,
      maxWidth = maxWidth,
      token = server.accessToken,
    )

  fun getBackdropUrl(
    server: JellyfinServer,
    item: JellyfinItem,
    maxWidth: Int = 1280,
  ): String =
    client.getBackdropUrl(
      serverUrl = server.serverUrl,
      itemId = item.id,
      imageTag = item.backdropImageTag,
      maxWidth = maxWidth,
      token = server.accessToken,
    )

  suspend fun reportPlaybackStart(
    serverUrl: String,
    token: String,
    itemId: String,
    positionTicks: Long,
  ) = client.reportPlaybackStart(serverUrl, token, itemId, positionTicks)

  suspend fun reportPlaybackProgress(
    serverUrl: String,
    token: String,
    itemId: String,
    positionTicks: Long,
    isPaused: Boolean = false,
  ) = client.reportPlaybackProgress(serverUrl, token, itemId, positionTicks, isPaused)

  suspend fun reportPlaybackStopped(
    serverUrl: String,
    token: String,
    itemId: String,
    positionTicks: Long,
  ) = client.reportPlaybackStopped(serverUrl, token, itemId, positionTicks)

  suspend fun markPlayed(
    server: JellyfinServer,
    item: JellyfinItem,
  ): Result<Unit> =
    client.markPlayed(
      serverUrl = server.serverUrl,
      userId = server.userId,
      itemId = item.id,
      token = server.accessToken,
    )

  suspend fun markUnplayed(
    server: JellyfinServer,
    item: JellyfinItem,
  ): Result<Unit> =
    client.markUnplayed(
      serverUrl = server.serverUrl,
      userId = server.userId,
      itemId = item.id,
      token = server.accessToken,
    )
}
