/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.syncplay.SyncplayManager
import org.koin.compose.koinInject

/**
 * Syncplay controls rendered directly inside the Network > Syncplay tab.
 *
 * This intentionally does not use a modal or bottom sheet. Connection fields, status, room users,
 * and disconnect controls stay in the tab so Syncplay behaves like a first-class Network section.
 */
@Composable
fun SyncplayPanel(syncplayManager: SyncplayManager = koinInject()) {
  val state by syncplayManager.state.collectAsState()
  val savedCredentials = remember(syncplayManager) { syncplayManager.savedCredentials }

  var host by remember(syncplayManager) { mutableStateOf(savedCredentials.host) }
  var port by remember(syncplayManager) { mutableStateOf(savedCredentials.port.toString()) }
  var username by remember(syncplayManager) { mutableStateOf(savedCredentials.username) }
  var room by remember(syncplayManager) { mutableStateOf(savedCredentials.room) }
  var password by remember(syncplayManager) { mutableStateOf(savedCredentials.password) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text = stringResource(R.string.syncplay_title),
            style = MaterialTheme.typography.titleLarge,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.syncplay_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (state.isConnected) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
              text = stringResource(R.string.syncplay_connected_as, state.username.orEmpty(), state.room.orEmpty()),
              style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = stringResource(R.string.syncplay_users_in_room),
              style = MaterialTheme.typography.titleSmall,
            )
          }
        }
      }

      items(state.users, key = { it }) { user ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
          Text(
            text = user,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
          )
        }
      }

      item {
        Button(
          onClick = { syncplayManager.disconnect() },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.syncplay_disconnect))
        }
      }
    } else {
      item {
        OutlinedTextField(
          value = host,
          onValueChange = { host = it },
          label = { Text(stringResource(R.string.syncplay_server_host)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item {
        OutlinedTextField(
          value = port,
          onValueChange = { port = it },
          label = { Text(stringResource(R.string.syncplay_port)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item {
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text(stringResource(R.string.syncplay_username)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item {
        OutlinedTextField(
          value = room,
          onValueChange = { room = it },
          label = { Text(stringResource(R.string.syncplay_room_name)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item {
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text(stringResource(R.string.syncplay_password_optional)) },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      if (state.connectionFailed || state.error != null) {
        item {
          Text(
            text =
              if (state.connectionFailed) {
                stringResource(R.string.syncplay_connection_failed)
              } else {
                stringResource(R.string.syncplay_error, state.error.orEmpty())
              },
            color = MaterialTheme.colorScheme.error,
          )
        }
      }

      item {
        val parsedPort = port.toIntOrNull()
        Button(
          onClick = {
            parsedPort?.let {
              syncplayManager.connect(host.trim(), it, username.trim(), room.trim(), password)
            }
          },
          enabled =
            !state.isConnecting &&
              host.isNotBlank() &&
              username.isNotBlank() &&
              room.isNotBlank() &&
              parsedPort != null &&
              parsedPort in 1..65535,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            stringResource(
              if (state.isConnecting) R.string.syncplay_connecting else R.string.syncplay_connect,
            ),
          )
        }
      }
    }

    item { Spacer(modifier = Modifier.height(24.dp)) }
  }
}
