/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.audiobooks

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfServer
import app.gyrolet.mpvrx.ui.browser.dialogs.SharedAddServerDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAudiobookshelfServerDialog(
  isOpen: Boolean,
  isLoading: Boolean,
  errorMessage: String?,
  initialServer: AudiobookshelfServer? = null,
  onDismiss: () -> Unit,
  onConnect: (
    serverUrl: String,
    serverName: String,
    isToken: Boolean,
    username: String,
    password: String,
    token: String,
  ) -> Unit,
) {
  if (!isOpen) return

  var serverUrl by remember(initialServer) { mutableStateOf(initialServer?.serverUrl ?: "") }
  var serverName by remember(initialServer) { mutableStateOf(initialServer?.name ?: "") }
  var isTokenAuth by remember(initialServer) {
    mutableStateOf(
      initialServer?.token?.isNotBlank() == true && initialServer.username.isBlank(),
    )
  }
  var username by remember(initialServer) { mutableStateOf(initialServer?.username ?: "") }
  var password by remember(initialServer) { mutableStateOf("") }
  var token by remember(initialServer) { mutableStateOf(initialServer?.token ?: "") }

  val canConnect =
    serverUrl.isNotBlank() &&
      if (isTokenAuth) {
        token.isNotBlank()
      } else {
        username.isNotBlank() && password.isNotBlank()
      }

  SharedAddServerDialog(
    isOpen = isOpen,
    isLoading = isLoading,
    errorMessage = errorMessage,
    title = if (initialServer == null) "Connect to Audiobookshelf" else "Edit Audiobookshelf Server",
    subtitle = "Enter your Audiobookshelf server address",
    serverUrl = serverUrl,
    onServerUrlChange = { serverUrl = it },
    serverUrlPlaceholder = "audiobooks.example.com or 192.168.1.100:13378",
    serverName = serverName,
    onServerNameChange = { serverName = it },
    serverNamePlaceholder = "Audiobookshelf",
    isTokenAuth = isTokenAuth,
    onAuthModeChange = { isTokenAuth = it },
    username = username,
    onUsernameChange = { username = it },
    password = password,
    onPasswordChange = { password = it },
    token = token,
    onTokenChange = { token = it },
    tokenLabel = "API Token",
    tokenPlaceholder = "Paste your API token from ABS user settings",
    tokenSupportingText = "Audiobookshelf Settings > Users > API Token",
    usernameInTokenMode = false,
    canConnect = canConnect,
    onDismiss = onDismiss,
    onSubmit = {
      val trimmedUrl = serverUrl.trim()
      onConnect(
        trimmedUrl,
        serverName.trim().ifBlank { "Audiobookshelf" },
        isTokenAuth,
        username.trim(),
        password,
        token.trim(),
      )
    },
    headerIcon = {
      Icon(
        painter = painterResource(id = R.drawable.ic_audiobookshelf),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
      )
    },
  )
}
