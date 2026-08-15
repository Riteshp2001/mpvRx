/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Identifies whether a persisted Network-tab entry is a regular link or one file in a torrent. */
enum class NetworkStreamEntryType {
  NORMAL,
  TORRENT_FILE,
}

/**
 * Durable playback reference displayed in the Network tab.
 *
 * Torrent rows deliberately store the canonical torrent source rather than the temporary
 * loopback proxy URL used during playback. [stableKey] makes regular links idempotent and makes
 * every `(infoHash, fileIndex)` pair a distinct torrent entry.
 */
@Entity(
  tableName = "network_stream_entries",
  indices = [
    Index(value = ["entryType", "updatedAt"]),
    Index(value = ["infoHash", "fileIndex"], unique = true),
  ],
)
data class NetworkStreamEntryEntity(
  @PrimaryKey
  val stableKey: String,
  val entryType: NetworkStreamEntryType,
  val canonicalSourceUri: String,
  val infoHash: String? = null,
  val fileIndex: Int? = null,
  val filePath: String? = null,
  val fileName: String,
  val fileSize: Long = 0L,
  val updatedAt: Long,
  val posterUrl: String? = null,
  val backdropUrl: String? = null,
  val groupTitle: String? = null,
  val overview: String? = null,
  val releaseYear: String? = null,
  val mediaType: String? = null,
)

