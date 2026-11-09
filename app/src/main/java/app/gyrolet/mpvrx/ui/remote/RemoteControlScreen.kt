/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.remote.REMOTE_MAX_TEXT_LENGTH
import app.gyrolet.mpvrx.remote.RemoteControlStore
import app.gyrolet.mpvrx.remote.RemoteKey
import app.gyrolet.mpvrx.remote.RemoteReason
import app.gyrolet.mpvrx.remote.TvRemoteServer
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.preferences.SettingsTopAppBar
import app.gyrolet.mpvrx.ui.tv.isTelevision
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import kotlinx.serialization.Serializable

/** On a TV: lets phones pair with it. On a phone: finds MPVRX TVs and controls them. */
@Serializable
object RemoteControlScreen : Screen {
  @Composable
  override fun Content() {
    if (isTelevision()) TvRemoteSettings() else PhoneRemoteController()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvRemoteSettings() {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val store = remember(context) { RemoteControlStore(context) }
  var enabled by remember { mutableStateOf(store.serverEnabled) }
  val address by TvRemoteServer.address.collectAsState()
  val revision by TvRemoteServer.revision.collectAsState()
  val phones = remember(revision) { store.pairedPhones() }

  Scaffold(
    topBar = {
      SettingsTopAppBar(title = stringResource(R.string.remote_settings_title_tv), onBack = { backStack.popSafely() })
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Card(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
              Text(stringResource(R.string.remote_tv_enable), style = MaterialTheme.typography.titleMedium)
              Text(
                stringResource(R.string.remote_tv_enable_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = enabled,
              onCheckedChange = { checked ->
                enabled = checked
                TvRemoteServer.setEnabled(context, checked)
              },
              modifier = Modifier.tvFocusHighlight(CircleShape, focusedScale = 1.05f),
            )
          }
        }
      }
      if (enabled) {
        item {
          Text(
            text = address?.let { stringResource(R.string.remote_tv_address, it) }
              ?: stringResource(R.string.remote_tv_address_unavailable),
            style = MaterialTheme.typography.titleMedium,
          )
        }
        item {
          Text(
            stringResource(R.string.remote_tv_how_to),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      item {
        Text(
          stringResource(R.string.remote_tv_paired_phones),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(top = 12.dp),
        )
      }
      if (phones.isEmpty()) {
        item {
          Text(
            stringResource(R.string.remote_tv_no_paired_phones),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(phones, key = { it.id }) { phone ->
        Card(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(phone.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(
              onClick = { TvRemoteServer.removePairedPhone(context, phone.id) },
              modifier = Modifier.tvFocusHighlight(RoundedCornerShape(50), focusedScale = 1.05f),
            ) {
              Text(stringResource(R.string.remote_tv_remove))
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneRemoteController() {
  val backStack = LocalBackStack.current
  val viewModel: RemoteControlViewModel = viewModel()
  val connection by viewModel.connection.collectAsState()

  Scaffold(
    topBar = {
      SettingsTopAppBar(title = stringResource(R.string.remote_settings_title_phone), onBack = { backStack.popSafely() })
    },
  ) { padding ->
    val contentModifier = Modifier.fillMaxSize().padding(padding)
    when (val state = connection) {
      is RemoteConnection.Connected -> RemotePad(state.name, viewModel, contentModifier)
      is RemoteConnection.AwaitingApproval ->
        WaitingPanel(
          message = stringResource(R.string.remote_phone_confirm_code, state.name),
          code = state.code,
          onCancel = viewModel::disconnect,
          modifier = contentModifier,
        )
      is RemoteConnection.Connecting ->
        WaitingPanel(
          message = stringResource(R.string.remote_phone_connecting, state.name),
          code = null,
          onCancel = viewModel::disconnect,
          modifier = contentModifier,
        )
      is RemoteConnection.Failed -> TvPicker(viewModel, state.reason, contentModifier)
      RemoteConnection.Idle -> TvPicker(viewModel, null, contentModifier)
    }
  }
}

@Composable
private fun TvPicker(
  viewModel: RemoteControlViewModel,
  failure: RemoteFailure?,
  modifier: Modifier,
) {
  val discovered by viewModel.discovered.collectAsState()
  val saved by viewModel.savedTvs.collectAsState()
  val pairedIds by viewModel.pairedTvIds.collectAsState()
  val tvs =
    remember(discovered, saved) {
      (
        discovered +
          saved.filter { savedTv ->
            discovered.none { it.tvId == savedTv.tvId || (it.host == savedTv.host && it.port == savedTv.port) }
          }
      ).distinctBy { it.host to it.port }
    }
  var address by rememberSaveable { mutableStateOf("") }
  var addressError by remember { mutableStateOf(false) }
  val connectToAddress = { addressError = !viewModel.connectToAddress(address) }

  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (failure != null) {
      item {
        Text(
          stringResource(failure.messageRes),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
    item {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(stringResource(R.string.remote_phone_searching), style = MaterialTheme.typography.bodyMedium)
      }
    }
    if (tvs.isEmpty()) {
      item {
        Text(
          stringResource(R.string.remote_phone_no_tvs),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    items(tvs, key = { "${it.host}:${it.port}" }) { tv ->
      Card(onClick = { viewModel.connect(tv) }, modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.RoundedFilled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(tv.name, style = MaterialTheme.typography.titleMedium)
            Text(
              "${tv.host}:${tv.port}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (tv.tvId != null && tv.tvId in pairedIds) {
            TextButton(onClick = { viewModel.forget(tv) }) {
              Text(stringResource(R.string.remote_phone_forget))
            }
          }
        }
      }
    }
    item {
      Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = address,
          onValueChange = {
            address = it
            addressError = false
          },
          label = { Text(stringResource(R.string.remote_phone_address_label)) },
          placeholder = { Text("192.168.1.20:47821") },
          singleLine = true,
          isError = addressError,
          supportingText = if (addressError) {
            { Text(stringResource(R.string.remote_error_invalid_address)) }
          } else null,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
          keyboardActions = KeyboardActions(onGo = { connectToAddress() }),
          modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = connectToAddress, enabled = address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.remote_phone_connect))
        }
      }
    }
  }
}

@Composable
private fun WaitingPanel(
  message: String,
  code: String?,
  onCancel: () -> Unit,
  modifier: Modifier,
) {
  Column(
    modifier = modifier.padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
  ) {
    Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    if (code != null) {
      Text(
        code.chunked(3).joinToString(" "),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    CircularProgressIndicator()
    OutlinedButton(onClick = onCancel) {
      Text(stringResource(R.string.generic_cancel))
    }
  }
}

@Composable
private fun RemotePad(
  name: String,
  viewModel: RemoteControlViewModel,
  modifier: Modifier,
) {
  val failure by viewModel.commandFailure.collectAsState()
  val haptics = rememberAppHaptics()
  var text by rememberSaveable { mutableStateOf("") }
  val press: (String) -> Unit = { key ->
    haptics.tick()
    viewModel.sendKey(key)
  }
  val sendText = {
    if (text.isNotEmpty()) {
      haptics.confirm()
      viewModel.sendText(text)
      text = ""
    }
  }

  Column(
    modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Text(
      stringResource(R.string.remote_phone_connected, name),
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
    )
    if (failure == RemoteReason.BACKGROUND) {
      Text(
        stringResource(R.string.remote_error_background),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
      )
    }
    DirectionPad(press)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      RemoteButton(Icons.RoundedFilled.ArrowBack, R.string.remote_phone_back) { press(RemoteKey.BACK) }
      RemoteButton(Icons.RoundedFilled.MoreVert, R.string.remote_phone_menu) { press(RemoteKey.MENU) }
      RemoteButton(Icons.RoundedFilled.PlayArrow, R.string.remote_phone_play_pause) { press(RemoteKey.PLAY_PAUSE) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      RemoteButton(Icons.RoundedFilled.FastRewind, R.string.remote_phone_rewind) { press(RemoteKey.REWIND) }
      RemoteButton(Icons.RoundedFilled.FastForward, R.string.remote_phone_fast_forward) { press(RemoteKey.FAST_FORWARD) }
      RemoteButton(Icons.RoundedFilled.VolumeDown, R.string.remote_phone_volume_down) { press(RemoteKey.VOLUME_DOWN) }
      RemoteButton(Icons.RoundedFilled.VolumeUp, R.string.remote_phone_volume_up) { press(RemoteKey.VOLUME_UP) }
    }
    OutlinedTextField(
      value = text,
      onValueChange = { text = it.take(REMOTE_MAX_TEXT_LENGTH) },
      label = { Text(stringResource(R.string.remote_phone_text_label)) },
      maxLines = 4,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { sendText() }),
      modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = sendText, enabled = text.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.remote_phone_send))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { press(RemoteKey.BACKSPACE) }, modifier = Modifier.weight(1f)) {
        Text(stringResource(R.string.remote_phone_backspace))
      }
      OutlinedButton(onClick = { press(RemoteKey.CLEAR_FIELD) }, modifier = Modifier.weight(1f)) {
        Text(stringResource(R.string.remote_phone_clear_field))
      }
      OutlinedButton(onClick = { press(RemoteKey.ENTER) }, modifier = Modifier.weight(1f)) {
        Text(stringResource(R.string.remote_phone_enter))
      }
    }
    TextButton(onClick = viewModel::disconnect) {
      Text(stringResource(R.string.remote_phone_disconnect))
    }
  }
}

@Composable
private fun DirectionPad(press: (String) -> Unit) {
  Box(
    modifier =
      Modifier
        .size(248.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    RemoteButton(
      Icons.RoundedFilled.KeyboardArrowUp,
      R.string.remote_phone_up,
      Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
    ) { press(RemoteKey.UP) }
    RemoteButton(
      Icons.RoundedFilled.KeyboardArrowDown,
      R.string.remote_phone_down,
      Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
    ) { press(RemoteKey.DOWN) }
    RemoteButton(
      Icons.RoundedFilled.KeyboardArrowLeft,
      R.string.remote_phone_left,
      Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
    ) { press(RemoteKey.LEFT) }
    RemoteButton(
      Icons.RoundedFilled.KeyboardArrowRight,
      R.string.remote_phone_right,
      Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
    ) { press(RemoteKey.RIGHT) }
    Button(
      onClick = { press(RemoteKey.CENTER) },
      shape = CircleShape,
      modifier = Modifier.align(Alignment.Center).size(96.dp),
    ) {
      Text(stringResource(R.string.remote_phone_ok), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun RemoteButton(
  icon: AppIcon,
  @StringRes label: Int,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  FilledTonalIconButton(onClick = onClick, modifier = modifier.size(56.dp)) {
    Icon(icon, contentDescription = stringResource(label))
  }
}
