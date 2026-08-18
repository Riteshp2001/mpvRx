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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.gyrolet.mpvrx.database.entities.JellyfinServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JellyfinServerDao {
  @Query("SELECT * FROM jellyfin_servers ORDER BY lastConnected DESC")
  fun getAllServers(): Flow<List<JellyfinServerEntity>>

  @Query("SELECT * FROM jellyfin_servers WHERE id = :id LIMIT 1")
  suspend fun getServerById(id: Long): JellyfinServerEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(server: JellyfinServerEntity): Long

  @Update
  suspend fun update(server: JellyfinServerEntity)

  @Delete
  suspend fun delete(server: JellyfinServerEntity)

  @Query("DELETE FROM jellyfin_servers WHERE id = :id")
  suspend fun deleteById(id: Long)
}
