/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import app.gyrolet.mpvrx.presentation.components.AppComboBoxAnchor as ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppComboBoxMenu
import app.gyrolet.mpvrx.presentation.components.AppComboBoxOption
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.network.XtreamServerAddress
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.launch

@Composable
fun AddXtreamPlaylistDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onImported: () -> Unit,
  onCreateXtreamPlaylist: suspend (String, String, String) -> Result<Long>,
  savedServerUrls: List<String> = emptyList(),
) {
  if (!isOpen) return

  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var serverUrl by remember { mutableStateOf("") }
  var username by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var passwordVisible by remember { mutableStateOf(false) }
  var isLoading by remember { mutableStateOf(false) }
  var submitted by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var serversExpanded by remember { mutableStateOf(false) }

  val parsedServerUrl = remember(serverUrl) { runCatching { XtreamServerAddress.parse(serverUrl) }.getOrNull() }
  val isServerUrlValid = parsedServerUrl != null
  val serverSuggestions = remember(savedServerUrls) {
    savedServerUrls.mapNotNull { runCatching { XtreamServerAddress.parse(it).serverUrl }.getOrNull() }.distinct()
  }
  val showServerUrlError = !isServerUrlValid && (submitted || serverUrl.isNotBlank())
  val canSubmit = isServerUrlValid && username.isNotBlank() && password.isNotBlank() && !isLoading

  val submit = {
    submitted = true
    errorMessage = null
    if (canSubmit) {
      isLoading = true
      coroutineScope.launch {
        onCreateXtreamPlaylist(requireNotNull(parsedServerUrl).serverUrl, username, password)
          .onSuccess {
            Toast
              .makeText(context, context.getString(R.string.playlist_xtream_import_success), Toast.LENGTH_SHORT)
              .show()
            password = ""
            onImported()
          }.onFailure { error ->
            errorMessage = error.message ?: context.getString(R.string.generic_unknown_error)
          }
        isLoading = false
      }
    }
  }

  AppAlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.playlist_xtream_add_title),
        style = MaterialTheme.typography.headlineSmall,
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
            text = stringResource(R.string.playlist_xtream_add_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          ExposedDropdownMenuBox(
            expanded = serversExpanded && serverSuggestions.isNotEmpty(),
            onExpandedChange = { if (!isLoading) serversExpanded = it },
          ) {
            OutlinedTextField(
              value = serverUrl,
              onValueChange = { input ->
                val pasted = input.length > serverUrl.length + 1
                val parsed = runCatching { XtreamServerAddress.parse(input) }.getOrNull()
                if (pasted && parsed != null) {
                  serverUrl = parsed.serverUrl
                  parsed.username?.let { username = it }
                  parsed.password?.let { password = it }
                } else serverUrl = input
                errorMessage = null
              },
              label = { Text(stringResource(R.string.playlist_xtream_server_url)) },
              placeholder = { Text(stringResource(R.string.playlist_xtream_server_placeholder)) },
              singleLine = true,
              isError = showServerUrlError,
              enabled = !isLoading,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
              trailingIcon = if (serverSuggestions.isNotEmpty()) {
                { ExposedDropdownMenuDefaults.TrailingIcon(serversExpanded, Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)) }
              } else null,
              modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )
            AppComboBoxMenu(
              expanded = serversExpanded && serverSuggestions.isNotEmpty(),
              onDismissRequest = { serversExpanded = false },
            ) {
              serverSuggestions.forEachIndexed { index, url ->
                AppComboBoxOption(
                  text = url,
                  selected = url == serverUrl,
                  index = index,
                  itemCount = serverSuggestions.size,
                  trailingContent = { Text("Xtream", style = MaterialTheme.typography.labelSmall) },
                  onClick = { serverUrl = url; serversExpanded = false; errorMessage = null },
                )
              }
            }
          }
          OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.playlist_xtream_username)) },
            singleLine = true,
            isError = submitted && username.isBlank(),
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.playlist_xtream_password)) },
            singleLine = true,
            isError = submitted && password.isBlank(),
            enabled = !isLoading,
            visualTransformation =
              if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
              IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !isLoading) {
                Icon(
                  imageVector =
                    if (passwordVisible) Icons.RoundedFilled.VisibilityOff else Icons.RoundedFilled.Visibility,
                  contentDescription =
                    stringResource(
                      if (passwordVisible) {
                        R.string.playlist_xtream_hide_password
                      } else {
                        R.string.playlist_xtream_show_password
                      },
                    ),
                )
              }
            },
            modifier = Modifier.fillMaxWidth(),
          )

          if (showServerUrlError) {
            Text(
              text = stringResource(R.string.playlist_xtream_url_error),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
          errorMessage?.let { message ->
            Text(
              text = stringResource(R.string.playlist_xtream_import_error, message),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }

          if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
          }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
    confirmButton = {
      TextButton(onClick = submit, enabled = canSubmit) {
        Text(stringResource(R.string.playlist_xtream_connect))
      }
    },
  )
}
