/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinAuthMode
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

@Composable
fun AddJellyfinServerDialog(
  isOpen: Boolean,
  isLoading: Boolean,
  errorMessage: String?,
  onDismiss: () -> Unit,
  onConnect: (serverUrl: String, serverName: String, authMode: JellyfinAuthMode, username: String, password: String, token: String) -> Unit,
) {
  if (!isOpen) return

  var serverUrl by remember { mutableStateOf("") }
  var serverName by remember { mutableStateOf("") }
  var authMode by remember { mutableStateOf(JellyfinAuthMode.CREDENTIALS) }
  var username by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var token by remember { mutableStateOf("") }

  val canConnect =
    serverUrl.isNotBlank() &&
      when (authMode) {
        JellyfinAuthMode.CREDENTIALS -> username.isNotBlank()
        JellyfinAuthMode.TOKEN -> token.isNotBlank()
      }

  AlertDialog(
    onDismissRequest = { if (!isLoading) onDismiss() },
    title = {
      Text(
        text = "Add Jellyfin Server",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )
    },
    text = {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedTextField(
          value = serverUrl,
          onValueChange = { serverUrl = it },
          label = { Text("Server URL") },
          placeholder = { Text("http://192.168.1.100:8096") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        OutlinedTextField(
          value = serverName,
          onValueChange = { serverName = it },
          label = { Text("Display Name (Optional)") },
          placeholder = { Text("Home Jellyfin") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Text(
          text = "Authentication Method",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = authMode == JellyfinAuthMode.CREDENTIALS,
            onClick = { authMode = JellyfinAuthMode.CREDENTIALS },
            label = { Text("Username & Password") },
          )
          FilterChip(
            selected = authMode == JellyfinAuthMode.TOKEN,
            onClick = { authMode = JellyfinAuthMode.TOKEN },
            label = { Text("API Token") },
          )
        }

        if (authMode == JellyfinAuthMode.CREDENTIALS) {
          OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Access Token / API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }

        if (!errorMessage.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = errorMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onConnect(serverUrl, serverName, authMode, username, password, token)
        },
        enabled = canConnect && !isLoading,
      ) {
        if (isLoading) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
          Spacer(modifier = Modifier.width(8.dp))
        }
        Text("Connect")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        enabled = !isLoading,
      ) {
        Text("Cancel")
      }
    },
  )
}

@Composable
fun ManageJellyfinServersDialog(
  isOpen: Boolean,
  servers: List<JellyfinServer>,
  activeServer: JellyfinServer?,
  onDismiss: () -> Unit,
  onSelectServer: (JellyfinServer) -> Unit,
  onDeleteServer: (JellyfinServer) -> Unit,
  onAddServerClick: () -> Unit,
) {
  if (!isOpen) return

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "Jellyfin Servers",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )
    },
    text = {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (servers.isEmpty()) {
          Text(
            text = "No servers connected yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          servers.forEach { server ->
            val isSelected = server.id == activeServer?.id
            Surface(
              shape = RoundedCornerShape(12.dp),
              color =
                if (isSelected) {
                  MaterialTheme.colorScheme.primaryContainer
                } else {
                  MaterialTheme.colorScheme.surfaceContainer
                },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .clickable {
                    onSelectServer(server)
                    onDismiss()
                  },
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.BringYourOwnIp,
                  contentDescription = null,
                  tint =
                    if (isSelected) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                  modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color =
                      if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                      } else {
                        MaterialTheme.colorScheme.onSurface
                      },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = server.serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                      if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                IconButton(
                  onClick = { onDeleteServer(server) },
                  modifier = Modifier.size(32.dp),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Delete,
                    contentDescription = "Remove Server",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
          onClick = {
            onDismiss()
            onAddServerClick()
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Add New Server")
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Done")
      }
    },
  )
}

