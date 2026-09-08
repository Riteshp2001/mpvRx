/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionEditorSheet(
  title: String,
  initialConnection: NetworkConnection,
  isEditing: Boolean,
  onDismiss: () -> Unit,
  onSave: (NetworkConnection, clearPassword: Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  var name by remember(initialConnection) { mutableStateOf(initialConnection.name) }
  var protocol by remember(initialConnection) { mutableStateOf(initialConnection.protocol) }
  var host by remember(initialConnection) { mutableStateOf(initialConnection.host) }
  var port by remember(initialConnection) { mutableStateOf(initialConnection.port.toString()) }
  var path by remember(initialConnection) { mutableStateOf(initialConnection.path) }
  var isAnonymous by remember(initialConnection) { mutableStateOf(initialConnection.isAnonymous) }
  var useHttps by remember(initialConnection) { mutableStateOf(initialConnection.useHttps) }
  var username by remember(initialConnection) { mutableStateOf(initialConnection.username) }
  var password by remember(initialConnection) { mutableStateOf("") }
  var passwordVisible by remember(initialConnection) { mutableStateOf(false) }
  var clearPassword by remember(initialConnection) { mutableStateOf(false) }
  var protocolMenuExpanded by remember { mutableStateOf(false) }

  val focusManager = LocalFocusManager.current
  val hostFocusRequester = remember { FocusRequester() }
  val portFocusRequester = remember { FocusRequester() }
  val pathFocusRequester = remember { FocusRequester() }
  val usernameFocusRequester = remember { FocusRequester() }
  val passwordFocusRequester = remember { FocusRequester() }
  val sheetState =
    rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
  val parsedPort = port.toIntOrNull()
  val isPortValid = parsedPort != null && parsedPort in MIN_PORT..MAX_PORT
  val canSave = host.isNotBlank() && isPortValid && (isAnonymous || username.isNotBlank())

  val dismiss = {
    focusManager.clearFocus()
    onDismiss()
  }
  val save = {
    if (canSave) {
      focusManager.clearFocus()
      onSave(
        initialConnection.copy(
          name = name.trim().ifBlank { "${protocol.displayName} - ${host.trim()}" },
          protocol = protocol,
          host = host.trim(),
          port = requireNotNull(parsedPort),
          username = if (isAnonymous) "" else username.trim(),
          password = if (isAnonymous) "" else password,
          path = path.trim().ifBlank { "/" },
          isAnonymous = isAnonymous,
          useHttps = protocol == NetworkProtocol.WEBDAV && useHttps,
        ),
        isAnonymous || clearPassword,
      )
    }
  }

  ModalBottomSheet(
    onDismissRequest = dismiss,
    modifier = modifier,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
      )

      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { FieldLabel(R.string.ui_name) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { hostFocusRequester.requestFocus() }),
      )

      ExposedDropdownMenuBox(
        expanded = protocolMenuExpanded,
        onExpandedChange = { protocolMenuExpanded = it },
        modifier = Modifier.fillMaxWidth(),
      ) {
        OutlinedTextField(
          value = protocol.displayName,
          onValueChange = {},
          readOnly = true,
          label = { FieldLabel(R.string.ui_protocol) },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolMenuExpanded) },
          modifier =
            Modifier
              .fillMaxWidth()
              .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
          expanded = protocolMenuExpanded,
          onDismissRequest = { protocolMenuExpanded = false },
        ) {
          NetworkProtocol.entries.forEach { selectedProtocol ->
            DropdownMenuItem(
              text = { Text(selectedProtocol.displayName) },
              onClick = {
                protocol = selectedProtocol
                port = selectedProtocol.defaultPort.toString()
                if (selectedProtocol != NetworkProtocol.WEBDAV) useHttps = false
                protocolMenuExpanded = false
              },
            )
          }
        }
      }

      OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { FieldLabel(R.string.ui_host_ip_address) },
        modifier = Modifier.fillMaxWidth().focusRequester(hostFocusRequester),
        singleLine = true,
        placeholder = { Text("192.168.1.100", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { portFocusRequester.requestFocus() }),
      )

      OutlinedTextField(
        value = port,
        onValueChange = { port = it.filter(Char::isDigit).take(MAX_PORT_DIGITS) },
        label = { FieldLabel(R.string.ui_port) },
        modifier = Modifier.fillMaxWidth().focusRequester(portFocusRequester),
        singleLine = true,
        isError = !isPortValid,
        supportingText =
          if (!isPortValid) {
            { Text(stringResource(R.string.network_connection_invalid_port)) }
          } else {
            null
          },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { pathFocusRequester.requestFocus() }),
      )

      OutlinedTextField(
        value = path,
        onValueChange = { path = it },
        label = { FieldLabel(R.string.ui_path) },
        modifier = Modifier.fillMaxWidth().focusRequester(pathFocusRequester),
        singleLine = true,
        placeholder = { Text("/", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = KeyboardOptions(imeAction = if (isAnonymous) ImeAction.Done else ImeAction.Next),
        keyboardActions =
          KeyboardActions(
            onNext = { usernameFocusRequester.requestFocus() },
            onDone = { focusManager.clearFocus() },
          ),
      )

      ConnectionToggle(
        checked = isAnonymous,
        onCheckedChange = { isAnonymous = it },
        label = stringResource(R.string.ui_anonymous_guest_access),
      )

      if (protocol == NetworkProtocol.WEBDAV) {
        ConnectionToggle(
          checked = useHttps,
          onCheckedChange = { enabled ->
            useHttps = enabled
            if (enabled && port == "80") {
              port = "443"
            } else if (!enabled && port == "443") {
              port = "80"
            }
          },
          label = stringResource(R.string.ui_use_https_secure_connection),
        )
      }

      OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { FieldLabel(R.string.ui_username) },
        modifier = Modifier.fillMaxWidth().focusRequester(usernameFocusRequester),
        singleLine = true,
        enabled = !isAnonymous,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
      )

      OutlinedTextField(
        value = password,
        onValueChange = {
          password = it
          if (it.isNotEmpty()) clearPassword = false
        },
        label = {
          FieldLabel(if (isEditing) R.string.ui_new_password_keep_existing else R.string.ui_password)
        },
        modifier = Modifier.fillMaxWidth().focusRequester(passwordFocusRequester),
        singleLine = true,
        enabled = !isAnonymous && !clearPassword,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
          IconButton(
            onClick = { passwordVisible = !passwordVisible },
            enabled = !isAnonymous && !clearPassword,
          ) {
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
              modifier = Modifier.size(20.dp),
            )
          }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
      )

      if (isEditing) {
        ConnectionToggle(
          checked = clearPassword,
          onCheckedChange = { clear ->
            clearPassword = clear
            if (clear) password = ""
          },
          label = stringResource(R.string.ui_clear_saved_password),
          enabled = !isAnonymous,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = dismiss) {
          Text(stringResource(R.string.generic_cancel), fontWeight = FontWeight.Medium)
        }
        Button(onClick = save, enabled = canSave) {
          Text(stringResource(R.string.ui_save), fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

@Composable
private fun FieldLabel(stringId: Int) {
  Text(
    text = stringResource(stringId),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun ConnectionToggle(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  label: String,
  enabled: Boolean = true,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .toggleable(
          value = checked,
          enabled = enabled,
          role = Role.Checkbox,
          onValueChange = onCheckedChange,
        ).padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(
      checked = checked,
      onCheckedChange = null,
      enabled = enabled,
    )
    Spacer(Modifier.width(8.dp))
    Text(
      text = label,
      color =
        if (enabled) {
          MaterialTheme.colorScheme.onSurface
        } else {
          MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
    )
  }
}

private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private const val MAX_PORT_DIGITS = 5