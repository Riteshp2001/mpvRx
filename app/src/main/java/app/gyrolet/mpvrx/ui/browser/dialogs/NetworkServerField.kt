/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import app.gyrolet.mpvrx.presentation.components.AppComboBoxAnchor as ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.network.LocalServerDiscovery
import app.gyrolet.mpvrx.data.network.ServerDiscoveryState
import app.gyrolet.mpvrx.data.network.ServerSuggestion
import app.gyrolet.mpvrx.data.network.ServerSuggestionSource
import app.gyrolet.mpvrx.presentation.components.AppComboBoxMenu
import app.gyrolet.mpvrx.presentation.components.AppComboBoxOption
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

@Composable
internal fun NetworkServerField(
  value: String,
  onValueChange: (String) -> Unit,
  onSelected: (ServerSuggestion) -> Unit,
  savedServers: List<ServerSuggestion>,
  label: String,
  modifier: Modifier = Modifier,
  isError: Boolean = false,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val discovery = remember(context) { LocalServerDiscovery(context) }
  var expanded by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var scanRequest by remember { mutableIntStateOf(0) }
  var scan by remember { mutableStateOf(ServerDiscoveryState()) }
  var addresses by remember { mutableStateOf(emptyList<ServerSuggestion>()) }

  LaunchedEffect(discovery, expanded, scanRequest) {
    if (expanded) addresses = withContext(Dispatchers.IO) { discovery.localAddresses() }
  }
  LaunchedEffect(discovery, lifecycleOwner, scanRequest) {
    if (scanRequest == 0) return@LaunchedEffect
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      scan = ServerDiscoveryState(isScanning = true)
      try {
        discovery.discover().collect { scan = it }
      } finally {
        scan = scan.copy(isScanning = false)
      }
    }
  }
  val suggestions = remember(savedServers, scan.servers, addresses, query) {
    (savedServers + scan.servers + addresses)
      .distinctBy { listOf(it.host.lowercase(), it.port, it.protocol, it.path, it.useHttps) }
      .filter {
        query.isBlank() || it.host.contains(query, true) || it.name.contains(query, true) ||
          it.protocol?.displayName?.contains(query, true) == true
      }
  }

  Column {
    ExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = { expanded = it; query = "" },
    ) {
      OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it); query = it; expanded = true },
        label = { Text(label) },
        placeholder = { Text("192.168.1.100") },
        singleLine = true,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = {
          ExposedDropdownMenuDefaults.TrailingIcon(
            expanded = expanded,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable),
          )
        },
        modifier = modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
      )
      AppComboBoxMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
      ) {
        AppComboBoxOption(
          text = stringResource(if (scan.isScanning) R.string.network_server_scanning else R.string.network_server_scan),
          selected = false,
          index = 0,
          itemCount = if (suggestions.isEmpty()) 2 else 1,
          enabled = !scan.isScanning,
          leadingIcon = { Icon(Icons.RoundedFilled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp)) },
          onClick = { query = ""; scanRequest++ },
        )
        if (suggestions.isEmpty()) {
          AppComboBoxOption(
            text = stringResource(R.string.network_server_no_matches),
            selected = false,
            index = 1,
            itemCount = 2,
            enabled = false,
            onClick = {},
          )
        }
        suggestions.forEachIndexed { index, suggestion ->
          val sourceLabel = stringResource(when (suggestion.source) {
            ServerSuggestionSource.SAVED -> R.string.network_server_saved
            ServerSuggestionSource.DISCOVERED -> R.string.network_server_nearby
            ServerSuggestionSource.DEVICE -> R.string.network_server_this_device
            ServerSuggestionSource.LOOPBACK -> R.string.network_server_loopback
          })
          AppComboBoxOption(
            text = suggestion.name.ifBlank { suggestion.authority },
            supportingText = "${suggestion.authority} · $sourceLabel",
            selected = false,
            index = index,
            itemCount = suggestions.size,
            trailingContent = {
              Text(
                suggestion.protocol?.displayName?.let {
                  if (suggestion.useHttps) "$it (HTTPS)" else it
                } ?: stringResource(R.string.network_server_address_only),
                style = MaterialTheme.typography.labelSmall,
              )
            },
            onClick = { expanded = false; query = ""; onSelected(suggestion) },
          )
        }
      }
    }
    TextButton(
      enabled = !scan.isScanning,
      onClick = { query = ""; expanded = true; scanRequest++ },
    ) {
      Text(stringResource(if (scan.isScanning) R.string.network_server_scanning else R.string.network_server_scan))
    }
    Text(
      stringResource(when {
        scan.unavailable -> R.string.network_server_scan_unavailable
        scanRequest > 0 && !scan.isScanning && scan.servers.isEmpty() -> R.string.network_server_none_found
        else -> R.string.network_server_discovery_hint
      }),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
