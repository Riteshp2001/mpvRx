/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkStreamEntryDao {
  @Query(
    """
    SELECT * FROM network_stream_entries
    WHERE entryType = 'NORMAL'
    ORDER BY updatedAt DESC, stableKey ASC
    LIMIT :limit
    """,
  )
  fun observeRecentNormalEntries(limit: Int): Flow<List<NetworkStreamEntryEntity>>

  @Query(
    """
    SELECT * FROM network_stream_entries
    WHERE entryType = 'TORRENT_FILE'
    ORDER BY updatedAt DESC, infoHash ASC, fileIndex ASC
    """,
  )
  fun observeTorrentFileEntries(): Flow<List<NetworkStreamEntryEntity>>

  @Upsert
  suspend fun upsert(entry: NetworkStreamEntryEntity)

  @Upsert
  suspend fun upsertAll(entries: List<NetworkStreamEntryEntity>)

  @Query(
    """
    DELETE FROM network_stream_entries
    WHERE entryType = 'NORMAL'
      AND stableKey NOT IN (
        SELECT stableKey FROM network_stream_entries
        WHERE entryType = 'NORMAL'
        ORDER BY updatedAt DESC, stableKey ASC
        LIMIT :keepCount
      )
    """,
  )
  suspend fun trimNormalEntries(keepCount: Int)

  @Query("DELETE FROM network_stream_entries WHERE entryType = 'TORRENT_FILE' AND infoHash = :infoHash")
  suspend fun deleteTorrentFiles(infoHash: String)

  @Query("DELETE FROM network_stream_entries WHERE stableKey = :stableKey")
  suspend fun deleteByStableKey(stableKey: String)

  @Transaction
  suspend fun upsertNormalAndTrim(
    entry: NetworkStreamEntryEntity,
    keepCount: Int,
  ) {
    upsert(entry)
    trimNormalEntries(keepCount)
  }

  @Query(
    """
    UPDATE network_stream_entries
    SET groupTitle = COALESCE(:title, groupTitle),
        posterUrl = COALESCE(:posterUrl, posterUrl),
        backdropUrl = COALESCE(:backdropUrl, backdropUrl),
        overview = COALESCE(:overview, overview),
        releaseYear = COALESCE(:releaseYear, releaseYear),
        mediaType = COALESCE(:mediaType, mediaType)
    WHERE infoHash = :infoHash
    """,
  )
  suspend fun updateTorrentArtwork(
    infoHash: String,
    title: String?,
    posterUrl: String?,
    backdropUrl: String?,
    overview: String?,
    releaseYear: String?,
    mediaType: String?,
  )

  @Transaction
  suspend fun replaceTorrentFiles(
    infoHash: String,
    entries: List<NetworkStreamEntryEntity>,
  ) {
    deleteTorrentFiles(infoHash)
    if (entries.isNotEmpty()) upsertAll(entries)
  }
}

