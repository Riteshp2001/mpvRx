/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a media file that has been moved into app-private "secure" storage
 * (outside MediaStore) as part of the Secure Folder feature.
 *
 * [originalPath] is retained so the file can be restored back to its original
 * location (or as close to it as possible) via [app.gyrolet.mpvrx.repository.SecureFolderRepository].
 */
@Entity(
  tableName = "secure_media",
  indices = [
    Index(value = ["secureFilePath"], unique = true),
  ],
)
data class SecureMediaEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val originalPath: String,
  val secureFilePath: String,
  val fileName: String,
  val fileSize: Long,
  val mimeType: String,
  val dateHidden: Long,
)
