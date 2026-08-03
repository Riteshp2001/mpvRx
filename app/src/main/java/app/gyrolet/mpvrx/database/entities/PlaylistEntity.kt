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
import androidx.room.PrimaryKey

@Entity
data class PlaylistEntity(
  @PrimaryKey(autoGenerate = true) val id: Int = 0,
  val name: String,
  val createdAt: Long,
  val updatedAt: Long,
  val m3uSourceUrl: String? = null, // URL of the M3U/M3U8 source, null for manual playlists
  val isM3uPlaylist: Boolean = false, // True if this playlist was created from an M3U source
  val userAgent: String? = null, // Custom User-Agent for fetching this M3U
)
