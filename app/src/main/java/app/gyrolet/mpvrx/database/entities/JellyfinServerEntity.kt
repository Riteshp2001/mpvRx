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
import androidx.room.PrimaryKey
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer

@Entity(tableName = "jellyfin_servers")
data class JellyfinServerEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val name: String,
  val serverUrl: String,
  val userId: String,
  val username: String,
  val accessToken: String,
  val lastConnected: Long = 0,
) {
  fun toDomain(): JellyfinServer =
    JellyfinServer(
      id = id,
      name = name,
      serverUrl = serverUrl,
      userId = userId,
      username = username,
      accessToken = accessToken,
      lastConnected = lastConnected,
    )

  companion object {
    fun fromDomain(server: JellyfinServer): JellyfinServerEntity =
      JellyfinServerEntity(
        id = server.id,
        name = server.name,
        serverUrl = server.serverUrl,
        userId = server.userId,
        username = server.username,
        accessToken = server.accessToken,
        lastConnected = server.lastConnected,
      )
  }
}
