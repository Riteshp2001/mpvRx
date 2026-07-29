/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
    "DELETE FROM directory_scan_index WHERE scanKey = :scanKey AND rootPath = :rootPath AND path NOT IN (:currentPaths)",
  )
  suspend fun deleteMissingPaths(
    scanKey: String,
    rootPath: String,
    currentPaths: List<String>,
  )

  @Transaction
  suspend fun reconcileRoot(
    scanKey: String,
    rootPath: String,
    currentPaths: List<String>,
    changedEntries: List<DirectoryScanEntity>,
  ) {
    if (changedEntries.isNotEmpty()) upsert(changedEntries)
    deleteMissingPaths(scanKey, rootPath, currentPaths)
  }
}
