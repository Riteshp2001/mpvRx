package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.syncplay.SyncplayManager
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncplaySheet(
    onDismiss: () -> Unit,
    syncplayManager: SyncplayManager = koinInject()
) {
    val state by syncplayManager.state.collectAsState()

    var host by remember { mutableStateOf("syncplay.pl") }
    var port by remember { mutableStateOf("8999") }
    var username by remember { mutableStateOf("MpvRxUser") }
    var room by remember { mutableStateOf("TestRoom") }
    var password by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.syncplay_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (state.isConnected) {
                Text(stringResource(R.string.syncplay_connected_as, state.username.orEmpty(), state.room.orEmpty()))
                Spacer(modifier = Modifier.height(8.dp))

                Text(stringResource(R.string.syncplay_users_in_room), style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(state.users) { user ->
                        Text("- $user", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { syncplayManager.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.syncplay_disconnect))
                }
            } else {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.syncplay_server_host)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.syncplay_port)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.syncplay_username)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text(stringResource(R.string.syncplay_room_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.syncplay_password_optional)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (state.connectionFailed || state.error != null) {
                    Text(
                        text = if (state.connectionFailed) {
                            stringResource(R.string.syncplay_connection_failed)
                        } else {
                            stringResource(R.string.syncplay_error, state.error.orEmpty())
                        },
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Button(
                    onClick = {
                        syncplayManager.connect(host.trim(), port.toInt(), username.trim(), room.trim(), password)
                    },
                    enabled = !state.isConnecting &&
                        host.isNotBlank() &&
                        username.isNotBlank() &&
                        room.isNotBlank() &&
                        (port.toIntOrNull() ?: 0) in 1..65535,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (state.isConnecting) R.string.syncplay_connecting else R.string.syncplay_connect,
                        ),
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
