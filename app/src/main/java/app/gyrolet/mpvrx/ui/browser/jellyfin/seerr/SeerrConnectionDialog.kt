/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin.seerr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.domain.seerr.JellyseerrUser
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

enum class SeerrAuthType {
  JELLYFIN,
  LOCAL,
  API_KEY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeerrConnectionDialog(
  isOpen: Boolean,
  isConnected: Boolean,
  currentUser: JellyseerrUser?,
  currentServerUrl: String,
  currentApiKey: String,
  activeJellyfinServer: JellyfinServer?,
  isConnecting: Boolean,
  errorMessage: String?,
  onDismiss: () -> Unit,
  onConnectWithCredentials: (serverUrl: String, user: String, pass: String, useJellyfin: Boolean) -> Unit,
  onConnectWithApiKey: (serverUrl: String, apiKey: String) -> Unit,
  onDisconnect: () -> Unit,
  sheetState: SheetState =
    rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    ),
) {
  if (!isOpen) return

  var authType by remember { mutableStateOf(SeerrAuthType.JELLYFIN) }
  var isEditingServer by remember { mutableStateOf(false) }
  var serverUrl by remember(currentServerUrl, activeJellyfinServer) {
    val initial = if (currentServerUrl.isNotBlank()) {
      currentServerUrl
    } else if (activeJellyfinServer != null) {
      val raw = activeJellyfinServer.serverUrl.removeSuffix("/")
      // If Jellyfin has standard port 8096, guess Overseerr/Jellyseerr on 5055
      if (raw.contains(":8096")) raw.replace(":8096", ":5055") else "$raw:5055"
    } else {
      ""
    }
    mutableStateOf(initial)
  }

  var username by remember(activeJellyfinServer, currentUser) {
    val initial = currentUser?.username
      ?: activeJellyfinServer?.username
      ?: currentUser?.displayName
      ?: ""
    mutableStateOf(initial)
  }
  var password by remember { mutableStateOf("") }
  var isPasswordVisible by remember { mutableStateOf(false) }
  var apiKey by remember(currentApiKey) { mutableStateOf(currentApiKey) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 8.dp)
        .verticalScroll(rememberScrollState()),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp),
          ) {
            val rawAvatar = currentUser?.avatar
            val avatarUrl = when {
              rawAvatar.isNullOrBlank() -> null
              rawAvatar.startsWith("http") -> rawAvatar
              currentServerUrl.isNotBlank() -> "${currentServerUrl.trimEnd('/')}/${rawAvatar.trimStart('/')}"
              else -> null
            }

            if (isConnected && avatarUrl != null) {
              RemoteImage(
                url = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
              )
            } else {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  Icons.RoundedFilled.Person,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.size(22.dp),
                )
              }
            }
          }
          Column {
            Text(
              text = if (isConnected) (currentUser?.displayName ?: currentUser?.username ?: stringResource(R.string.seerr_connect_server)) else stringResource(R.string.seerr_connect_server),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = if (isConnected) "Active Connection" else "Seerr",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        IconButton(onClick = onDismiss) {
          Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.generic_cancel))
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // If connected, show active account profile card
      if (isConnected && currentUser != null) {
        Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            val rawAvatar = currentUser.avatar
            val avatarUrl = when {
              rawAvatar.isNullOrBlank() -> null
              rawAvatar.startsWith("http") -> rawAvatar
              currentServerUrl.isNotBlank() -> "${currentServerUrl.trimEnd('/')}/${rawAvatar.trimStart('/')}"
              else -> null
            }

            if (avatarUrl != null) {
              RemoteImage(
                url = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                  .size(48.dp)
                  .clip(CircleShape),
              )
            } else {
              Box(
                modifier = Modifier
                  .size(48.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = (currentUser.displayName ?: currentUser.username ?: "U").take(1).uppercase(),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onPrimary,
                )
              }
            }

            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = currentUser.displayName ?: currentUser.username ?: "Connected User",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = currentServerUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (currentUser.isAdmin()) {
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                  modifier = Modifier.padding(top = 4.dp),
                ) {
                  Text(
                    text = "Admin",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
            }

            IconButton(
              onClick = { isEditingServer = !isEditingServer },
              modifier = Modifier.size(36.dp),
            ) {
              Icon(
                Icons.RoundedFilled.Edit,
                contentDescription = "Reconfigure Server",
                tint = if (isEditingServer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
          onClick = {
            onDisconnect()
          },
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(
            Icons.RoundedFilled.ExitToApp,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = stringResource(R.string.seerr_disconnect),
            fontWeight = FontWeight.Bold,
          )
        }
      }

      if (!isConnected || isEditingServer) {
        if (isConnected) {
          Spacer(modifier = Modifier.height(16.dp))
          HorizontalDivider()
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Switch or Reconfigure Server",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          Spacer(modifier = Modifier.height(8.dp))
        }

        // Server URL Input
      OutlinedTextField(
        value = serverUrl,
        onValueChange = { serverUrl = it },
        label = { Text(stringResource(R.string.seerr_server_url)) },
        placeholder = { Text(stringResource(R.string.seerr_server_url_hint)) },
        leadingIcon = {
          Icon(Icons.RoundedFilled.Language, contentDescription = null)
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(modifier = Modifier.height(12.dp))

      // Auth Mode Selector
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(
          selected = authType == SeerrAuthType.JELLYFIN,
          onClick = { authType = SeerrAuthType.JELLYFIN },
          label = { Text("Jellyfin Auth") },
          shape = RoundedCornerShape(10.dp),
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        )
        FilterChip(
          selected = authType == SeerrAuthType.LOCAL,
          onClick = { authType = SeerrAuthType.LOCAL },
          label = { Text("Local Account") },
          shape = RoundedCornerShape(10.dp),
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        )
        FilterChip(
          selected = authType == SeerrAuthType.API_KEY,
          onClick = { authType = SeerrAuthType.API_KEY },
          label = { Text("API Key") },
          shape = RoundedCornerShape(10.dp),
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      if (authType == SeerrAuthType.API_KEY) {
        OutlinedTextField(
          value = apiKey,
          onValueChange = { apiKey = it },
          label = { Text(stringResource(R.string.seerr_api_key)) },
          placeholder = { Text(stringResource(R.string.seerr_api_key_hint)) },
          leadingIcon = {
            Icon(Icons.RoundedFilled.Key, contentDescription = null)
          },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = {
            if (serverUrl.isNotBlank() && apiKey.isNotBlank()) {
              onConnectWithApiKey(serverUrl, apiKey)
            }
          }),
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = {
            Text(if (authType == SeerrAuthType.JELLYFIN) "Jellyfin Username" else "Email / Username")
          },
          leadingIcon = {
            Icon(Icons.RoundedFilled.Person, contentDescription = null)
          },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text(stringResource(R.string.seerr_password)) },
          leadingIcon = {
            Icon(Icons.RoundedFilled.Lock, contentDescription = null)
          },
          trailingIcon = {
            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
              Icon(
                if (isPasswordVisible) Icons.RoundedFilled.VisibilityOff else Icons.RoundedFilled.Visibility,
                contentDescription = null,
              )
            }
          },
          visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = {
            if (serverUrl.isNotBlank() && username.isNotBlank()) {
              onConnectWithCredentials(serverUrl, username, password, authType == SeerrAuthType.JELLYFIN)
            }
          }),
          modifier = Modifier.fillMaxWidth(),
        )
      }

      // Error Message if any
      AnimatedVisibility(visible = !errorMessage.isNullOrBlank()) {
        Card(
          shape = RoundedCornerShape(10.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              Icons.RoundedFilled.ErrorOutline,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = errorMessage ?: "",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(20.dp))

      Button(
        onClick = {
          if (authType == SeerrAuthType.API_KEY) {
            onConnectWithApiKey(serverUrl, apiKey)
          } else {
            onConnectWithCredentials(serverUrl, username, password, authType == SeerrAuthType.JELLYFIN)
          }
        },
        enabled = !isConnecting && serverUrl.isNotBlank() && (
          (authType == SeerrAuthType.API_KEY && apiKey.isNotBlank()) ||
            (authType != SeerrAuthType.API_KEY && username.isNotBlank())
          ),
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp),
      ) {
        if (isConnecting) {
          CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text("Connecting…", fontWeight = FontWeight.Bold)
        } else {
          Icon(Icons.RoundedFilled.Link, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.seerr_login), fontWeight = FontWeight.Bold)
        }
      }
    }

    Spacer(modifier = Modifier.height(32.dp))
  }
}
}
