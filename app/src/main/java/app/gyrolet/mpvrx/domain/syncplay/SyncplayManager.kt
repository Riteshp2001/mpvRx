/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.syncplay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SyncplayState(
  val isConnected: Boolean = false,
  val isConnecting: Boolean = false,
  val room: String? = null,
  val username: String? = null,
  val users: List<String> = emptyList(),
  val error: String? = null,
  val connectionFailed: Boolean = false,
  val connectionFailure: SyncplayConnectionFailure? = null,
)

class SyncplayManager(
  context: Context,
) {
  private val client = SyncplayClient()
  private val credentialsStore = SyncplayCredentialsStore(context.applicationContext)

  // Session changes and UI actions share one dispatcher. Socket operations switch to IO in the client.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private val _state = MutableStateFlow(SyncplayState())
  val state: StateFlow<SyncplayState> = _state.asStateFlow()

  var onRemotePause: ((Boolean) -> Unit)? = null
  var onRemoteSeek: ((Double) -> Unit)? = null
  var playbackStateProvider: (() -> SyncplayPlaybackState?)? = null
  var fileInfoProvider: (() -> SyncplayFile?)? = null

  private var pingJob: Job? = null
  private var connectJob: Job? = null
  private var handshakeJob: Job? = null
  private var listenJob: Job? = null
  private var messageCollectorJob: Job? = null
  private var backgroundDisconnectJob: Job? = null

  @Volatile private var sessionGeneration = 0L
  private var loggedIn = false
  private var lastLocalPlaybackState = SyncplayPlaybackState(position = 0.0, paused = true)
  private var lastLocalFile: SyncplayFile? = null
  private var lastServerLatencyCalculation: Double? = null
  private var lastClientRtt = 0.0
  private var averagedClientRtt = 0.0
  private var lastForwardDelay = 0.0
  private var clientIgnoringOnTheFly = 0
  private var serverIgnoringOnTheFly = 0

  val savedCredentials: SyncplayCredentials
    get() = credentialsStore.load()

  fun connect(
    host: String,
    port: Int,
    username: String,
    room: String,
    password: String?,
  ) {
    if (_state.value.isConnecting) return
    val endpoint = runCatching { SyncplayEndpoint.parse(host, port) }.getOrNull() ?: return
    val credentials =
      SyncplayCredentials(
        host = endpoint.host,
        port = endpoint.port,
        username = username.trim(),
        room = room.trim(),
        password = password.orEmpty(),
      )
    if (!credentials.isValid) return
    credentialsStore.save(credentials)
    credentialsStore.reconnectRequested = true
    connect(credentials)
  }

  private fun connect(credentials: SyncplayCredentials) {
    val (host, port, username, room, password) = credentials
    stopSession()
    val connectionGeneration = sessionGeneration
    _state.value = SyncplayState(isConnecting = true, room = room, username = username)
    connectJob =
      scope.launch {
        val result = client.connect(host, port)
        if (sessionGeneration != connectionGeneration) return@launch
        if (result.isFailure) {
          failConnection(SyncplayConnectionFailure.from(result.exceptionOrNull()!!))
          return@launch
        }

        messageCollectorJob =
          scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.messages.collect { if (sessionGeneration == connectionGeneration) handleMessage(it) }
          }
        listenJob =
          scope.launch {
            val listening = client.listen()
            if (sessionGeneration == connectionGeneration) {
              failConnection(
                listening.exceptionOrNull()?.let(SyncplayConnectionFailure::from) ?: SyncplayConnectionFailure.CLOSED,
              )
            }
          }
        handshakeJob =
          scope.launch {
            delay(10_000)
            if (sessionGeneration == connectionGeneration && !loggedIn) {
              failConnection(SyncplayConnectionFailure.HANDSHAKE_TIMEOUT)
            }
          }

        val sent =
          client.sendMessage(
            SyncplayMessage(
              hello =
                HelloMessage(
                  username = username,
                  room = Room(room),
                  version = SYNCPLAY_LEGACY_VERSION,
                  realversion = SYNCPLAY_PROTOCOL_VERSION,
                  password = password.md5OrNull(),
                  features = buildJsonObject {},
                ),
            ),
          )
        if (!sent && sessionGeneration == connectionGeneration) failConnection(SyncplayConnectionFailure.NETWORK)
      }
  }

  fun updatePlayerState(
    position: Double,
    paused: Boolean,
    doSeek: Boolean? = null,
  ) {
    lastLocalPlaybackState =
      SyncplayPlaybackState(
        position = position.coerceAtLeast(0.0),
        paused = paused,
      )
    if (!client.isConnected() || !loggedIn) return

    scope.launch {
      sendState(lastLocalPlaybackState, forced = doSeek != null, doSeek = doSeek)
    }
  }

  fun updateFileInfo(file: SyncplayFile?) {
    lastLocalFile = file
    if (!client.isConnected() || !loggedIn) return

    scope.launch {
      sendFile(file)
      requestUserList()
    }
  }

  fun clearPlayerBindings() {
    onRemotePause = null
    onRemoteSeek = null
    playbackStateProvider = null
    fileInfoProvider = null
  }

  fun disconnect() {
    credentialsStore.reconnectRequested = false
    backgroundDisconnectJob?.cancel()
    backgroundDisconnectJob = null
    disconnect(error = null)
  }

  fun onAppBackgrounded() {
    backgroundDisconnectJob?.cancel()
    if (!credentialsStore.reconnectRequested ||
      (!_state.value.isConnected && !_state.value.isConnecting)
    ) {
      return
    }

    backgroundDisconnectJob =
      scope.launch {
        delay(BACKGROUND_GRACE_PERIOD_MS)
        stopSession()
        _state.value =
          _state.value.copy(
            isConnected = false,
            isConnecting = false,
            users = emptyList(),
            error = null,
            connectionFailed = false,
            connectionFailure = null,
          )
      }
  }

  fun onAppForegrounded() {
    backgroundDisconnectJob?.cancel()
    backgroundDisconnectJob = null
    val credentials = credentialsStore.load()
    if (credentialsStore.reconnectRequested &&
      !_state.value.isConnected &&
      !_state.value.isConnecting &&
      credentials.isValid
    ) {
      connect(credentials)
    }
  }

  private fun handleMessage(message: SyncplayMessage) {
    message.error?.let {
      _state.value = _state.value.copy(error = it.message)
      disconnect(it.message)
      return
    }

    message.hello?.let { hello ->
      if (hello.username.isNullOrBlank() ||
        hello.room?.name.isNullOrBlank() ||
        (hello.realversion.isNullOrBlank() && hello.version.isNullOrBlank())
      ) {
        failConnection(SyncplayConnectionFailure.PROTOCOL)
        return
      }
      handshakeJob?.cancel()
      handshakeJob = null
      loggedIn = true
      val acceptedUsername = hello.username
      val acceptedRoom = hello.room.name
      _state.value =
        _state.value.copy(
          isConnected = true,
          isConnecting = false,
          username = acceptedUsername,
          room = acceptedRoom,
          users = (_state.value.users + acceptedUsername).distinct(),
        )

      startPingLoop()
      scope.launch {
        sendCurrentFile()
        requestUserList()
        sendState(currentPlaybackState(), forced = false, doSeek = null)
      }
    }

    message.set?.user?.forEach { (user, userEvent) ->
      userEvent.event?.joined?.let { joined ->
        if (joined && !_state.value.users.contains(user)) {
          _state.value = _state.value.copy(users = _state.value.users + user)
        }
      }
      userEvent.event?.left?.let { left ->
        if (left) {
          _state.value = _state.value.copy(users = _state.value.users - user)
        }
      }
      if (userEvent.file != null && !_state.value.users.contains(user)) {
        _state.value = _state.value.copy(users = _state.value.users + user)
      }
    }

    message.list?.let { rooms ->
      val roomName = _state.value.room
      val roomUsers = roomName?.let { rooms[it] } ?: rooms.values.firstOrNull()
      if (roomUsers != null) {
        _state.value = _state.value.copy(users = roomUsers.keys.toList())
      }
    }

    message.state?.let { stateMessage ->
      handleIgnoringOnTheFly(stateMessage.ignoringOnTheFly)
      updateLatency(stateMessage.ping)
      if (clientIgnoringOnTheFly == 0) {
        handleRemotePlaystate(stateMessage.playstate)
      }
    }
  }

  private fun startPingLoop() {
    pingJob?.cancel()
    pingJob =
      scope.launch {
        while (client.isConnected() && loggedIn) {
          sendState(currentPlaybackState(), forced = false, doSeek = null)
          delay(1000)
        }
      }
  }

  private fun disconnect(error: String?) {
    stopSession()
    _state.value = SyncplayState(error = error)
  }

  private fun failConnection(failure: SyncplayConnectionFailure) {
    stopSession()
    _state.value =
      _state.value.copy(
        isConnected = false,
        isConnecting = false,
        users = emptyList(),
        connectionFailed = true,
        connectionFailure = failure,
      )
  }

  private fun stopSession() {
    sessionGeneration += 1
    loggedIn = false
    connectJob?.cancel()
    handshakeJob?.cancel()
    pingJob?.cancel()
    listenJob?.cancel()
    messageCollectorJob?.cancel()
    pingJob = null
    connectJob = null
    handshakeJob = null
    listenJob = null
    messageCollectorJob = null
    clientIgnoringOnTheFly = 0
    serverIgnoringOnTheFly = 0
    lastServerLatencyCalculation = null
    lastClientRtt = 0.0
    averagedClientRtt = 0.0
    lastForwardDelay = 0.0
    client.disconnect()
  }

  private fun currentPlaybackState(): SyncplayPlaybackState =
    playbackStateProvider?.invoke()?.also { lastLocalPlaybackState = it }
      ?: lastLocalPlaybackState

  private suspend fun sendCurrentFile() {
    val file = fileInfoProvider?.invoke() ?: lastLocalFile
    sendFile(file)
  }

  private suspend fun sendFile(file: SyncplayFile?) {
    if (file == null || !client.isConnected() || !loggedIn) return
    client.sendMessage(SyncplayMessage(set = SetMessage(file = file)))
  }

  private suspend fun requestUserList() {
    if (!client.isConnected() || !loggedIn) return
    client.sendRawMessage("""{"List": null}""")
  }

  private suspend fun sendState(
    playbackState: SyncplayPlaybackState,
    forced: Boolean,
    doSeek: Boolean?,
  ) {
    if (!client.isConnected() || !loggedIn) return

    val canSendPlaystate = clientIgnoringOnTheFly == 0 || serverIgnoringOnTheFly != 0
    client.sendMessage(
      SyncplayMessage(
        state =
          StateMessage(
            ping =
              Ping(
                clientLatencyCalculation = nowSeconds(),
                clientRtt = lastClientRtt,
                latencyCalculation = lastServerLatencyCalculation,
              ),
            playstate =
              if (canSendPlaystate) {
                Playstate(
                  paused = playbackState.paused,
                  position = playbackState.position.coerceAtLeast(0.0),
                  doSeek = doSeek,
                )
              } else {
                null
              },
            ignoringOnTheFly = buildIgnoringOnTheFly(forced),
          ),
      ),
    )
  }

  private fun handleRemotePlaystate(playstate: Playstate?) {
    if (playstate == null) return
    if (playstate.setBy != null && playstate.setBy == _state.value.username) return
    if (playstate.setBy == "Nobody" && _state.value.users.size <= 1) return

    playstate.paused?.let { paused ->
      if (paused != lastLocalPlaybackState.paused) {
        lastLocalPlaybackState = lastLocalPlaybackState.copy(paused = paused)
        onRemotePause?.invoke(paused)
      }
    }

    val remotePosition = playstate.position ?: return
    val adjustedPosition =
      if (playstate.paused == false) {
        remotePosition + lastForwardDelay
      } else {
        remotePosition
      }.coerceAtLeast(0.0)

    if (playstate.doSeek == true || kotlin.math.abs(lastLocalPlaybackState.position - adjustedPosition) > 2.0) {
      lastLocalPlaybackState = lastLocalPlaybackState.copy(position = adjustedPosition)
      onRemoteSeek?.invoke(adjustedPosition)
    }
  }

  private fun handleIgnoringOnTheFly(ignoring: Map<String, Int>?) {
    if (ignoring == null) return
    ignoring["server"]?.let { serverCounter ->
      serverIgnoringOnTheFly = serverCounter
      clientIgnoringOnTheFly = 0
      return
    }
    ignoring["client"]?.let { clientCounter ->
      if (clientIgnoringOnTheFly == clientCounter) clientIgnoringOnTheFly = 0
    }
  }

  private fun buildIgnoringOnTheFly(forced: Boolean): Map<String, Int>? {
    if (forced) clientIgnoringOnTheFly += 1

    val ignoring = mutableMapOf<String, Int>()
    if (clientIgnoringOnTheFly > 0) ignoring["client"] = clientIgnoringOnTheFly
    if (serverIgnoringOnTheFly > 0) {
      ignoring["server"] = serverIgnoringOnTheFly
      serverIgnoringOnTheFly = 0
    }
    return ignoring.ifEmpty { null }
  }

  private fun updateLatency(ping: Ping?) {
    if (ping == null) return
    lastServerLatencyCalculation = ping.latencyCalculation ?: lastServerLatencyCalculation

    ping.clientLatencyCalculation?.let { clientTimestamp ->
      val rtt = (nowSeconds() - clientTimestamp).coerceAtLeast(0.0)
      lastClientRtt = rtt
      averagedClientRtt =
        if (averagedClientRtt <= 0.0) {
          rtt
        } else {
          averagedClientRtt * 0.85 + rtt * 0.15
        }
    }

    val senderRtt = ping.serverRtt ?: ping.senderLatency ?: return
    lastForwardDelay =
      if (averagedClientRtt > 0.0 && senderRtt < lastClientRtt) {
        averagedClientRtt / 2.0 + (lastClientRtt - senderRtt)
      } else {
        averagedClientRtt / 2.0
      }.coerceIn(0.0, 2.0)
  }

  private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

  private fun String?.md5OrNull(): String? {
    val value = this?.takeIf { it.isNotBlank() } ?: return null
    val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
  }

  private companion object {
    const val SYNCPLAY_LEGACY_VERSION = "1.2.255"
    const val SYNCPLAY_PROTOCOL_VERSION = "1.2.7"
    const val BACKGROUND_GRACE_PERIOD_MS = 15_000L
  }
}
