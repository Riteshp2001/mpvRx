/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.repository

import app.gyrolet.mpvrx.database.dao.NetworkStreamEntryDao
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryEntity
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryType
import kotlinx.coroutines.flow.Flow

class NetworkStreamEntryRepository(
  private val dao: NetworkStreamEntryDao,
) {
  data class TorrentFile(
    val index: Int,
    val path: String,
    val name: String,
    val size: Long,
  )

  fun observeRecentNormalEntries(): Flow<List<NetworkStreamEntryEntity>> =
    dao.observeRecentNormalEntries(NORMAL_ENTRY_LIMIT)

  fun observeTorrentFileEntries(): Flow<List<NetworkStreamEntryEntity>> = dao.observeTorrentFileEntries()

  suspend fun saveNormalEntry(
    canonicalSourceUri: String,
    fileName: String,
    updatedAt: Long = System.currentTimeMillis(),
  ) {
    val source =
      canonicalSourceUri.trim().also {
        require(it.isNotEmpty()) { "Source URI must not be blank" }
      }
    val displayName = fileName.trim().ifBlank { source }
    dao.upsertNormalAndTrim(
      entry =
        NetworkStreamEntryEntity(
          stableKey = normalStableKey(source),
          entryType = NetworkStreamEntryType.NORMAL,
          canonicalSourceUri = source,
          fileName = displayName,
          updatedAt = updatedAt,
        ),
      keepCount = NORMAL_ENTRY_LIMIT,
    )
  }

  suspend fun replaceTorrentFiles(
    canonicalSourceUri: String,
    infoHash: String,
    files: List<TorrentFile>,
    updatedAt: Long = System.currentTimeMillis(),
  ) {
    val source =
      canonicalSourceUri.trim().also {
        require(it.isNotEmpty()) { "Source URI must not be blank" }
      }
    val canonicalInfoHash =
      infoHash.trim().lowercase().also {
        require(it.isNotEmpty()) { "Info hash must not be blank" }
      }
    require(files.map { it.index }.distinct().size == files.size) { "Torrent file indices must be unique" }

    val entries =
      files.map { file ->
        require(file.index >= 0) { "Torrent file index must not be negative" }
        require(file.path.isNotBlank()) { "Torrent file path must not be blank" }
        require(file.size >= 0L) { "Torrent file size must not be negative" }

        NetworkStreamEntryEntity(
          stableKey = torrentStableKey(canonicalInfoHash, file.index),
          entryType = NetworkStreamEntryType.TORRENT_FILE,
          canonicalSourceUri = source,
          infoHash = canonicalInfoHash,
          fileIndex = file.index,
          filePath = file.path,
          fileName = file.name.trim().ifBlank { file.path.substringAfterLast('/') },
          fileSize = file.size,
          updatedAt = updatedAt,
        )
      }

    dao.replaceTorrentFiles(canonicalInfoHash, entries)
  }

  suspend fun delete(stableKey: String) {
    if (stableKey.isNotBlank()) dao.deleteByStableKey(stableKey)
  }

  suspend fun deleteTorrentGroup(infoHash: String) {
    val canonicalInfoHash = infoHash.trim().lowercase()
    if (canonicalInfoHash.isNotEmpty()) dao.deleteTorrentFiles(canonicalInfoHash)
  }

  suspend fun updateTorrentArtwork(
    infoHash: String,
    title: String? = null,
    posterUrl: String? = null,
    backdropUrl: String? = null,
    overview: String? = null,
    releaseYear: String? = null,
    mediaType: String? = null,
  ) {
    val canonicalInfoHash = infoHash.trim().lowercase()
    if (canonicalInfoHash.isEmpty()) return
    dao.updateTorrentArtwork(
      infoHash = canonicalInfoHash,
      title = title?.trim()?.takeIf(String::isNotEmpty),
      posterUrl = posterUrl?.trim()?.takeIf(String::isNotEmpty),
      backdropUrl = backdropUrl?.trim()?.takeIf(String::isNotEmpty),
      overview = overview?.trim()?.takeIf(String::isNotEmpty),
      releaseYear = releaseYear?.trim()?.takeIf(String::isNotEmpty),
      mediaType = mediaType?.trim()?.takeIf(String::isNotEmpty),
    )
  }


  private fun normalStableKey(canonicalSourceUri: String): String = "normal:$canonicalSourceUri"

  private fun torrentStableKey(
    infoHash: String,
    fileIndex: Int,
  ): String = "torrent:$infoHash:$fileIndex"

  companion object {
    const val NORMAL_ENTRY_LIMIT = 3
  }
}
