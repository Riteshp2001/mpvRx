/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinAuthMode
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.ui.browser.dialogs.SharedAddServerDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.SharedManageServersDialog
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddJellyfinServerDialog(
  isOpen: Boolean,
  isLoading: Boolean,
  errorMessage: String?,
  initialServer: JellyfinServer? = null,
  onDismiss: () -> Unit,
  onConnect: (serverUrl: String, serverName: String, authMode: JellyfinAuthMode, username: String, password: String, token: String) -> Unit,
) {
  if (!isOpen) return

  var serverUrl by remember(initialServer) { mutableStateOf(initialServer?.serverUrl ?: "") }
  var serverName by remember(initialServer) { mutableStateOf(initialServer?.name ?: "") }
  var authMode by remember(initialServer) { mutableStateOf(JellyfinAuthMode.CREDENTIALS) }
  var username by remember(initialServer) { mutableStateOf(initialServer?.username ?: "") }
  var password by remember(initialServer) { mutableStateOf("") }
  var token by remember(initialServer) { mutableStateOf("") }

  val canConnect =
    serverUrl.isNotBlank() &&
      when (authMode) {
        JellyfinAuthMode.CREDENTIALS -> username.isNotBlank()
        JellyfinAuthMode.TOKEN -> token.isNotBlank()
      }

  SharedAddServerDialog(
    isOpen = isOpen,
    isLoading = isLoading,
    errorMessage = errorMessage,
    title = if (initialServer == null) "Add Jellyfin Server" else "Edit Jellyfin Server",
    subtitle = "Enter your server address and account details",
    serverUrl = serverUrl,
    onServerUrlChange = { serverUrl = it },
    serverUrlPlaceholder = "jellyfin.example.com or 192.168.1.100:8096",
    serverName = serverName,
    onServerNameChange = { serverName = it },
    serverNamePlaceholder = "Home Server",
    isTokenAuth = authMode == JellyfinAuthMode.TOKEN,
    onAuthModeChange = { isToken ->
      authMode = if (isToken) JellyfinAuthMode.TOKEN else JellyfinAuthMode.CREDENTIALS
    },
    username = username,
    onUsernameChange = { username = it },
    password = password,
    onPasswordChange = { password = it },
    token = token,
    onTokenChange = { token = it },
    tokenLabel = "API Key / Access Token",
    tokenPlaceholder = "Paste token from Jellyfin dashboard",
    tokenSupportingText = "Dashboard > Advanced > API Keys",
    usernameInTokenMode = false,
    canConnect = canConnect,
    onDismiss = onDismiss,
    onSubmit = {
      val trimmedUrl = serverUrl.trim()
      onConnect(
        trimmedUrl,
        serverName.trim().ifBlank { "Jellyfin" },
        authMode,
        username.trim(),
        password,
        // Copying from the dashboard easily picks up a ':' or quotes around the key.
        token.trim().trim(':', '"', '\'', ' '),
      )
    },
    headerIcon = {
      Icon(
        imageVector = Icons.RoundedFilled.BringYourOwnIp,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
      )
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
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
  SharedManageServersDialog(
    isOpen = isOpen,
    title = "Jellyfin Servers",
    servers = servers,
    activeServerId = activeServer?.id,
    getServerId = { it.id },
    getServerName = { it.name },
    getServerUrl = { it.serverUrl },
    getServerSubtitle = { server ->
      if (server.username.isNotBlank()) server.username else null
    },
    onDismiss = onDismiss,
    onSelectServer = onSelectServer,
    onDeleteServer = onDeleteServer,
    onAddServerClick = onAddServerClick,
  )
}
