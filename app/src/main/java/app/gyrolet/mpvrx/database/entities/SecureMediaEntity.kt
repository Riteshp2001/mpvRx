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
