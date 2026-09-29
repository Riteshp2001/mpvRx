/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.gyrolet.mpvrx.database.entities.FrameCaptureEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FrameCaptureDao {
  /** Newest first — this is the only ordering the snapshot grid ever shows. */
  @Query("SELECT * FROM frame_captures ORDER BY capturedAt DESC, id DESC")
  fun observeAll(): Flow<List<FrameCaptureEntity>>

  @Query("SELECT * FROM frame_captures WHERE id = :id")
  suspend fun findById(id: Long): FrameCaptureEntity?

  @Insert
  suspend fun insert(capture: FrameCaptureEntity): Long

  @Query("DELETE FROM frame_captures WHERE id = :id")
  suspend fun delete(id: Long)

  @Query("DELETE FROM frame_captures WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<Long>)

  /** A null [folderId] moves the rows back to the root of the snapshot library. */
  @Query("UPDATE frame_captures SET folderId = :folderId WHERE id IN (:ids)")
  suspend fun updateFolder(
    ids: List<Long>,
    folderId: Long?,
  )

  /**
   * Fills in the image size of a row written before the mosaic layout existed. Only ever called for
   * a row that has none, so it cannot overwrite a size recorded at capture time. A size of `0 × 0`
   * means the image could not be read at all — see `ImageDimensions.UNREADABLE`.
   */
  @Query("UPDATE frame_captures SET imageWidth = :width, imageHeight = :height WHERE id = :id")
  suspend fun updateImageSize(
    id: Long,
    width: Int,
    height: Int,
  )

  @Query("DELETE FROM frame_captures WHERE folderId = :folderId")
  suspend fun deleteByFolder(folderId: Long)
}
