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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.AppComboBox
import app.gyrolet.mpvrx.data.network.ServerSuggestion
import app.gyrolet.mpvrx.domain.network.NetworkAddress
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.domain.network.SmbSharePath
import app.gyrolet.mpvrx.domain.network.normalizedAddress
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConnectionEditorSheet(
  title: String,
  initialConnection: NetworkConnection,
  isEditing: Boolean,
  onDismiss: () -> Unit,
  onSave: (NetworkConnection, clearPassword: Boolean) -> Unit,
  modifier: Modifier = Modifier,
  savedConnections: List<NetworkConnection> = emptyList(),
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
  val address = remember(host, protocol, useHttps) {
    runCatching { NetworkAddress.parse(host, protocol, useHttps) }.getOrNull()
  }
  val effectiveProtocol = address?.protocol ?: protocol
  val normalizedPath = remember(path, effectiveProtocol, address?.path) {
    runCatching { NetworkPath.from((address?.path ?: path).trim().let { if (effectiveProtocol == NetworkProtocol.SMB) it.replace('\\', '/') else it }).value }.getOrNull()
  }
  val isPathValid = normalizedPath != null &&
    (effectiveProtocol != NetworkProtocol.SMB || runCatching { SmbSharePath(normalizedPath) }.isSuccess)
  val dlna = effectiveProtocol == NetworkProtocol.DLNA
  val anonymous = isAnonymous || dlna
  val canSave = address != null && isPortValid && isPathValid && (anonymous || username.isNotBlank())
  val resolvedConnection = remember(host, port, protocol, path, useHttps, initialConnection) {
    runCatching {
      initialConnection.copy(host = host, port = parsedPort ?: 0, protocol = protocol, path = path, useHttps = useHttps).normalizedAddress()
    }.getOrNull()
  }
  val originalAddress = remember(initialConnection) { runCatching { initialConnection.normalizedAddress() }.getOrNull() }
  val destinationChanged = resolvedConnection?.host?.lowercase() != originalAddress?.host?.lowercase() ||
    resolvedConnection?.protocol != originalAddress?.protocol || resolvedConnection?.port != originalAddress?.port ||
    resolvedConnection?.useHttps != originalAddress?.useHttps

  val dismiss = {
    focusManager.clearFocus()
    onDismiss()
  }
  val save = {
    if (canSave) {
      val resolvedAddress = requireNotNull(resolvedConnection)
      focusManager.clearFocus()
      onSave(
        initialConnection.copy(
          name = name.trim().ifBlank { "${effectiveProtocol.displayName} - ${resolvedAddress.host}" },
          protocol = effectiveProtocol,
          host = resolvedAddress.host,
          port = resolvedAddress.port,
          username = if (anonymous) "" else username.trim(),
          password = if (anonymous) "" else password,
          path = requireNotNull(normalizedPath),
          isAnonymous = anonymous,
          useHttps = resolvedAddress.useHttps,
        ),
        anonymous || clearPassword || (destinationChanged && password.isEmpty()),
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
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmallEmphasized,
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

      AppComboBox(
        options = NetworkProtocol.entries,
        selected = protocol,
        onSelect = { selectedProtocol ->
          protocol = selectedProtocol
          port = selectedProtocol.defaultPort.toString()
          if (selectedProtocol != NetworkProtocol.WEBDAV && selectedProtocol != NetworkProtocol.DLNA) useHttps = false
        },
        optionLabel = { it.displayName },
        label = stringResource(R.string.ui_protocol),
        modifier = Modifier.fillMaxWidth(),
      )

      NetworkServerField(
        value = host,
        onValueChange = { input ->
          val pasted = input.length > host.length + 1
          host = input
          // Normalize pasted URLs/UNC paths once, leaving normal hostname typing uninterrupted.
          val parsed = runCatching { NetworkAddress.parse(input, protocol, useHttps) }.getOrNull()
          if (pasted && parsed != null && ("://" in input || input.startsWith("\\\\") || parsed.port != null || parsed.path != null)) {
            host = parsed.host
            if (parsed.protocol != protocol || parsed.useHttps != useHttps) {
              port = (if (parsed.useHttps) 443 else parsed.protocol.defaultPort).toString()
            }
            protocol = parsed.protocol
            useHttps = parsed.useHttps
            parsed.port?.let { port = it.toString() }
            parsed.path?.let { path = it }
          }
        },
        onSelected = { selected ->
          host = selected.host
          selected.protocol?.let {
            protocol = it
            useHttps = selected.useHttps
            port = (selected.port ?: if (selected.useHttps) 443 else it.defaultPort).toString()
            path = selected.path ?: "/"
          }
          if (name.isBlank()) name = selected.name
        },
        savedServers = savedConnections.filter { it.id != initialConnection.id }.mapNotNull(ServerSuggestion::saved),
        label = stringResource(R.string.ui_host_ip_address),
        modifier = Modifier.fillMaxWidth().focusRequester(hostFocusRequester),
        isError = host.isNotBlank() && address == null,
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
        label = { FieldLabel(when (effectiveProtocol) {
          NetworkProtocol.SMB -> R.string.network_smb_share_folder
          NetworkProtocol.DLNA -> R.string.network_dlna_description_path
          else -> R.string.ui_path
        }) },
        modifier = Modifier.fillMaxWidth().focusRequester(pathFocusRequester),
        singleLine = true,
        placeholder = { Text(if (effectiveProtocol == NetworkProtocol.SMB) "/Media/Movies" else "/", maxLines = 1) },
        isError = !isPathValid,
        supportingText = if (effectiveProtocol == NetworkProtocol.SMB) {
          { Text(stringResource(R.string.network_smb_share_hint)) }
        } else if (dlna) {
          { Text(stringResource(R.string.network_dlna_description_hint)) }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = if (anonymous) ImeAction.Done else ImeAction.Next),
        keyboardActions =
          KeyboardActions(
            onNext = { if (anonymous) focusManager.clearFocus() else usernameFocusRequester.requestFocus() },
            onDone = { focusManager.clearFocus() },
          ),
      )

      if (!dlna) ConnectionToggle(
        checked = isAnonymous,
        onCheckedChange = { isAnonymous = it },
        label = stringResource(R.string.ui_anonymous_guest_access),
      )

      if (protocol == NetworkProtocol.WEBDAV || protocol == NetworkProtocol.DLNA) {
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

      if (!dlna) {
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { FieldLabel(R.string.ui_username) },
          modifier = Modifier.fillMaxWidth().focusRequester(usernameFocusRequester),
          singleLine = true,
          enabled = !isAnonymous,
          supportingText = if (protocol == NetworkProtocol.SMB && !isAnonymous) {
            { Text(stringResource(R.string.network_smb_username_hint)) }
          } else null,
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
            FieldLabel(if (isEditing && !destinationChanged) R.string.ui_new_password_keep_existing else R.string.ui_password)
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
      }

      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TextButton(onClick = dismiss, shapes = ButtonDefaults.shapes()) {
          Text(stringResource(R.string.generic_cancel), fontWeight = FontWeight.Medium)
        }
        Button(onClick = save, enabled = canSave, shapes = ButtonDefaults.shapes()) {
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
        .clip(MaterialTheme.shapes.small)
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
