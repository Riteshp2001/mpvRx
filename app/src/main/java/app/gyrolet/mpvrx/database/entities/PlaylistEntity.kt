/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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

