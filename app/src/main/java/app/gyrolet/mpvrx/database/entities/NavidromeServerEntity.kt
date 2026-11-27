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
import app.gyrolet.mpvrx.domain.navidrome.NavidromeAuthMode
import app.gyrolet.mpvrx.domain.navidrome.NavidromeServer

@Entity(tableName = "navidrome_servers")
data class NavidromeServerEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val name: String,
  val serverUrl: String,
  val username: String,
  val password: String = "",
  val token: String = "",
  val authMode: String = "CREDENTIALS",
  val lastConnected: Long = 0,
) {
  fun toDomain(): NavidromeServer =
    NavidromeServer(
      id = id,
      name = name,
      serverUrl = serverUrl,
      username = username,
      password = password,
      token = token,
      authMode =
        try {
          NavidromeAuthMode.valueOf(authMode)
        } catch (_: Exception) {
          NavidromeAuthMode.CREDENTIALS
        },
      lastConnected = lastConnected,
    )

  companion object {
    fun fromDomain(server: NavidromeServer): NavidromeServerEntity =
      NavidromeServerEntity(
        id = server.id,
        name = server.name,
        serverUrl = server.serverUrl,
        username = server.username,
        password = server.password,
        token = server.token,
        authMode = server.authMode.name,
        lastConnected = server.lastConnected,
      )
  }
}
