package app.gyrolet.mpvrx.ui.browser.cards

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppShapeScale

@Composable
fun NetworkConnectionCard(
  connection: NetworkConnection,
  onConnect: (NetworkConnection) -> Unit,
  onDisconnect: (NetworkConnection) -> Unit,
  onEdit: (NetworkConnection) -> Unit,
  onDelete: (NetworkConnection) -> Unit,
  onBrowse: (NetworkConnection) -> Unit,
  onAutoConnectChange: (NetworkConnection, Boolean) -> Unit,
  modifier: Modifier = Modifier,
  isConnected: Boolean = false,
  isConnecting: Boolean = false,
  error: String? = null,
) {
  var isPressed by remember { mutableStateOf(false) }
  val targetScale = if (isPressed) 0.98f else 1.0f
  val scale by animateFloatAsState(
    targetValue = targetScale,
    animationSpec = AppMotion.Spatial.Expressive,
    label = "NetworkConnectionCardScale",
  )

  Card(
    modifier = modifier
      .fillMaxWidth()
      .graphicsLayer(scaleX = scale, scaleY = scale)
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.pressed }) {
              isPressed = true
            } else {
              isPressed = false
            }
          }
        }
      },
    shape = AppShapeScale.large,
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
    ) {
      // Header with name and actions
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = connection.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "${connection.protocol.displayName} • ${connection.host}:${connection.port}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Row {
          IconButton(onClick = { onEdit(connection) }) {
            Icon(
              Icons.RoundedFilled.Edit,
              contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_edit),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = { onDelete(connection) }) {
            Icon(
              Icons.RoundedFilled.Delete,
              contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.delete),
              tint = MaterialTheme.colorScheme.error,
            )
          }
        }
      }

      // Connection details
      if (connection.path != "/") {
        Text(
          text = "Path: ${connection.path}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      if (connection.username.isNotEmpty() && !connection.isAnonymous) {
        Text(
          text = "User: ${connection.username}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      // Error message
      if (error != null) {
        Text(
          text = "Error: $error",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 8.dp),
        )
      }

      // Auto-connect checkbox
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(
          checked = connection.autoConnect,
          onCheckedChange = { checked ->
            onAutoConnectChange(connection, checked)
          },
        )
        Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_connect_automatically_on_app_launch),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Connection button
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
      ) {
        when {
          isConnecting -> {
            FilledTonalButton(
              onClick = { },
              enabled = false,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_connecting),
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
              }
            }
          }

          isConnected -> {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              FilledTonalButton(
                onClick = { onBrowse(connection) },
                colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
              ) {
                Icon(
                  Icons.RoundedFilled.FolderOpen,
                  contentDescription = null,
                  modifier = Modifier.padding(end = 8.dp),
                )
                Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_browse))
              }

              FilledTonalButton(
                onClick = { onDisconnect(connection) },
                colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = MaterialTheme.colorScheme.secondaryContainer,
                  contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
              ) {
                Icon(
                  Icons.RoundedFilled.LinkOff,
                  contentDescription = null,
                  modifier = Modifier.padding(end = 8.dp),
                )
                Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_disconnect))
              }
            }
          }

          else -> {
            FilledTonalButton(
              onClick = { onConnect(connection) },
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
            ) {
              Icon(
                Icons.RoundedFilled.Link,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
              )
              Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_connect))
            }
          }
        }
      }
    }
  }
}




