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
import app.gyrolet.mpvrx.database.entities.SecureMediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecureMediaDao {
  @Query("SELECT * FROM secure_media ORDER BY dateHidden DESC")
  fun observeAll(): Flow<List<SecureMediaEntity>>

  @Query("SELECT * FROM secure_media ORDER BY dateHidden DESC")
  suspend fun getAll(): List<SecureMediaEntity>

  @Query("SELECT * FROM secure_media WHERE id = :id LIMIT 1")
  suspend fun getById(id: Long): SecureMediaEntity?

  @Query("SELECT * FROM secure_media WHERE id IN (:ids)")
  suspend fun getByIds(ids: List<Long>): List<SecureMediaEntity>

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entity: SecureMediaEntity): Long

  @Query("DELETE FROM secure_media WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM secure_media WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<Long>)

  @Query("SELECT COUNT(*) FROM secure_media")
  suspend fun getCount(): Int

  @Query("SELECT COUNT(*) FROM secure_media")
  fun observeCount(): Flow<Int>
}
