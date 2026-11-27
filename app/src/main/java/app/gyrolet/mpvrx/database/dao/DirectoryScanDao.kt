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
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gyrolet.mpvrx.database.entities.DirectoryScanEntity

@Dao
interface DirectoryScanDao {
  @Query("SELECT * FROM directory_scan_index WHERE scanKey = :scanKey")
  suspend fun getEntries(scanKey: String): List<DirectoryScanEntity>

  @Query("SELECT DISTINCT rootPath FROM directory_scan_index WHERE scanKey = :scanKey AND isNoMediaRoot = 1")
  suspend fun getNoMediaRoots(scanKey: String): List<String>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entries: List<DirectoryScanEntity>)

  @Query("DELETE FROM directory_scan_index WHERE scanKey = :scanKey AND rootPath = :rootPath")
  suspend fun deleteRoot(
    scanKey: String,
    rootPath: String,
  )

  @Query("DELETE FROM directory_scan_index WHERE scanKey = :scanKey")
  suspend fun deleteScan(scanKey: String)

  @Query(
    "DELETE FROM directory_scan_index WHERE scanKey = :scanKey " +
      "AND (path = :path OR substr(path, 1, length(:pathPrefix)) = :pathPrefix)",
  )
  suspend fun deleteSubtree(
    scanKey: String,
    path: String,
    pathPrefix: String,
  )
}
