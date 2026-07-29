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

