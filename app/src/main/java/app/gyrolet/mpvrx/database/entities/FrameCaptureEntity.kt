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
 * One captured video frame. The image itself lives in the system gallery — this row only records
 * where to find it and which moment of which video it came from.
 *
 * [imageUri] is the MediaStore URI on API 29+; [imagePath] is an absolute file path used below that,
 * where screenshots go to the public Pictures directory instead. Both are written when available and
 * readers prefer the URI.
 *
 * [folderId] is null for a snapshot that sits at the root of the snapshot library. It deliberately
 * carries no foreign key: adding one to an existing table would force a full table rebuild in the
 * migration, and the only writer is the frame capture repository, which deletes a folder and its rows
 * in one transaction. A dangling id is tolerated — readers treat an unknown folder as the root.
 *
 * [imageWidth] and [imageHeight] are the captured image's own pixel size, which is what the mosaic
 * layout tiles by. They are nullable because every row written before the mosaic existed has none;
 * those are filled in the first time the folder is browsed as a mosaic. A row whose image turned out
 * to be unreadable records `0 × 0` instead of staying null, which marks the question as answered so
 * the backfill does not reopen the dead file on every later visit.
 */
@Entity(
  tableName = "frame_captures",
  indices = [Index(value = ["capturedAt"]), Index(value = ["folderId"])],
)
data class FrameCaptureEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val imageUri: String?,
  val imagePath: String?,
  val videoUri: String,
  val videoPath: String?,
  val videoTitle: String,
  val positionMs: Long,
  val capturedAt: Long = System.currentTimeMillis(),
  val folderId: Long? = null,
  val imageWidth: Int? = null,
  val imageHeight: Int? = null,
)
