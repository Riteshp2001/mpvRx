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
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  foreignKeys = [
    ForeignKey(
      entity = PlaylistEntity::class,
      parentColumns = ["id"],
      childColumns = ["playlistId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["playlistId"]),
    Index(value = ["isFavorite"]),
  ],
)
data class PlaylistItemEntity(
  @PrimaryKey(autoGenerate = true) val id: Int = 0,
  val playlistId: Int,
  val filePath: String,
  val fileName: String,
  val position: Int, // Order in playlist
  val addedAt: Long,
  val lastPlayedAt: Long = 0, // When this video was last played from this playlist
  val playCount: Int = 0, // How many times played from this playlist
  val lastPosition: Long = 0, // Last playback position in milliseconds
  // M3U-specific metadata
  val tvgId: String? = null, // EPG channel ID
  val tvgLogo: String? = null, // Channel logo URL
  val groupTitle: String? = null, // Channel category / group
  val licenseType: String? = null, // DRM license type (e.g. com.widevine.alpha)
  val licenseKey: String? = null, // DRM license key URL
  val userAgent: String? = null, // Per-stream user-agent from the playlist
  val isFavorite: Boolean = false, // User-starred item
)
