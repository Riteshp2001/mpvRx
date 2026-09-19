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
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing network connections in the database
 */
@Dao
interface NetworkConnectionDao {
  @Query("SELECT * FROM network_connections WHERE isDeleted = 0 ORDER BY id ASC")
  fun getAllConnections(): Flow<List<NetworkConnection>>

  @Query("SELECT * FROM network_connections WHERE id = :id AND isDeleted = 0")
  suspend fun getConnectionById(id: Long): NetworkConnection?

  /** Tombstones included, for callers that need a connection's identity rather than its usability. */
  @Query("SELECT * FROM network_connections WHERE id = :id")
  suspend fun getConnectionByIdIncludingDeleted(id: Long): NetworkConnection?

  @Query("SELECT * FROM network_connections WHERE autoConnect = 1 AND isDeleted = 0 ORDER BY id ASC")
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

  @Query("UPDATE network_connections SET password = :encryptedPassword WHERE id = :id")
  suspend fun updateEncryptedPassword(
    id: Long,
    encryptedPassword: String,
  )

  /** Tombstones the row so its id survives for re-creation to revive. */
  @Query("UPDATE network_connections SET isDeleted = 1 WHERE id = :id")
  suspend fun markDeleted(id: Long)

  /** Rows available for revival; matched against new connections by their settings. */
  @Query("SELECT * FROM network_connections WHERE isDeleted = 1 ORDER BY id ASC")
  suspend fun getDeletedConnections(): List<NetworkConnection>

  /**
   * Includes tombstones. Callers use these for display and for deciding availability themselves —
   * a playlist entry must still be able to name the share it came from after that share is deleted.
   */
  @Query("SELECT * FROM network_connections ORDER BY id ASC")
  fun observeAllConnectionsIncludingDeleted(): Flow<List<NetworkConnection>>

  @Query("SELECT * FROM network_connections ORDER BY id ASC")
  suspend fun getAllConnectionsIncludingDeleted(): List<NetworkConnection>

  @Query("SELECT * FROM network_connections WHERE isDeleted = 0 ORDER BY id ASC")
  suspend fun getAllConnectionsList(): List<NetworkConnection>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(connections: List<NetworkConnection>)
}
