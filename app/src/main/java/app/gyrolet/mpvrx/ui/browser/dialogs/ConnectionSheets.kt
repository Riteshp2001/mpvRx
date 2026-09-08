/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkProtocol

@Composable
fun AddConnectionSheet(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onSave: (NetworkConnection) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isOpen) return

  val initialConnection =
    remember {
      NetworkConnection(
        name = "",
        protocol = NetworkProtocol.SMB,
        host = "",
        port = NetworkProtocol.SMB.defaultPort,
      )
    }
  ConnectionEditorSheet(
    title = stringResource(R.string.ui_add_network_connection),
    initialConnection = initialConnection,
    isEditing = false,
    onDismiss = onDismiss,
    onSave = { connection, _ -> onSave(connection) },
    modifier = modifier,
  )
}

@Composable
fun EditConnectionSheet(
  connection: NetworkConnection,
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onSave: (NetworkConnection, Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isOpen) return

  ConnectionEditorSheet(
    title = stringResource(R.string.ui_edit_connection),
    initialConnection = connection,
    isEditing = true,
    onDismiss = onDismiss,
    onSave = onSave,
    modifier = modifier,
  )
}