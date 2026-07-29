/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing network connections in the database
 */
@Dao
interface NetworkConnectionDao {
  @Query("SELECT * FROM network_connections ORDER BY id ASC")
  fun getAllConnections(): Flow<List<NetworkConnection>>

  @Query("SELECT * FROM network_connections WHERE id = :id")
  suspend fun getConnectionById(id: Long): NetworkConnection?

  @Query("SELECT * FROM network_connections WHERE autoConnect = 1 ORDER BY id ASC")
  suspend fun getAutoConnectConnections(): List<NetworkConnection>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(connection: NetworkConnection): Long

  @Update
  suspend fun update(connection: NetworkConnection)

  @Delete
  suspend fun delete(connection: NetworkConnection)

  @Query("UPDATE network_connections SET lastConnected = :timestamp WHERE id = :id")
  suspend fun updateLastConnected(
    id: Long,
    timestamp: Long,
  )

  @Query("DELETE FROM network_connections WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("SELECT * FROM network_connections ORDER BY id ASC")
  suspend fun getAllConnectionsList(): List<NetworkConnection>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(connections: List<NetworkConnection>)
}
