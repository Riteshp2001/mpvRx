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
data class RecentlyPlayedEntity(
  @PrimaryKey(autoGenerate = true) val id: Int = 0,
  val filePath: String,
  val fileName: String,
  val videoTitle: String? = null,
  val duration: Long = 0,
  val fileSize: Long = 0,
  val width: Int = 0,
  val height: Int = 0,
  val timestamp: Long,
  val launchSource: String? = null,
  val playlistId: Int? = null,
)
