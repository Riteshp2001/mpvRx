// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.remote.TvRemoteServer
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus

/** Shown on Android TV when a phone asks to pair; approve only when the phone shows the same code. */
@Composable
fun RemotePairingPrompt() {
  val request by TvRemoteServer.pairingRequest.collectAsState()
  val pending = request ?: return
  val allowFocus = rememberTvInitialFocusRequester(requestKey = pending.id)

  AppAlertDialog(
    onDismissRequest = { TvRemoteServer.respondToPairing(pending.id, allow = false) },
    title = { Text(stringResource(R.string.remote_pair_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.remote_pair_message, pending.deviceName))
        Text(
          pending.code.chunked(3).joinToString(" "),
          style = MaterialTheme.typography.displaySmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    },
    confirmButton = {
      AppDialogTextButton(
        onClick = { TvRemoteServer.respondToPairing(pending.id, allow = true) },
        modifier = Modifier.tvInitialFocus(allowFocus),
      ) {
        Text(stringResource(R.string.remote_pair_allow))
      }
    },
    dismissButton = {
      AppDialogTextButton(onClick = { TvRemoteServer.respondToPairing(pending.id, allow = false) }) {
        Text(stringResource(R.string.remote_pair_deny))
      }
    },
  )
}
