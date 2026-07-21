package app.gyrolet.mpvrx.domain.syncplay

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject

data class SyncplayState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val room: String? = null,
    val username: String? = null,
    val users: List<String> = emptyList(),
    val error: String? = null,
    val connectionFailed: Boolean = false,
)

class SyncplayManager {
    private val client = SyncplayClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _state = MutableStateFlow(SyncplayState())
    val state: StateFlow<SyncplayState> = _state.asStateFlow()

    var onRemotePause: ((Boolean) -> Unit)? = null
    var onRemoteSeek: ((Double) -> Unit)? = null
    var playbackStateProvider: (() -> SyncplayPlaybackState?)? = null
    var fileInfoProvider: (() -> SyncplayFile?)? = null

    private var pingJob: Job? = null
    private var listenJob: Job? = null
    private var messageCollectorJob: Job? = null
    private var loggedIn = false
    private var lastLocalPlaybackState = SyncplayPlaybackState(position = 0.0, paused = true)
    private var lastLocalFile: SyncplayFile? = null
    private var lastServerLatencyCalculation: Double? = null
    private var lastClientRtt = 0.0
    private var averagedClientRtt = 0.0
    private var lastForwardDelay = 0.0
    private var clientIgnoringOnTheFly = 0
    private var serverIgnoringOnTheFly = 0

    fun connect(host: String, port: Int, username: String, room: String, password: String?) {
        if (_state.value.isConnecting) return
        _state.value = SyncplayState(isConnecting = true, room = room, username = username)
        scope.launch {
            stopSession()

            val success = client.connect(host, port)
            if (!success) {
                _state.value = SyncplayState(connectionFailed = true)
                return@launch
            }

            _state.value = SyncplayState(isConnected = true, room = room, username = username)

            messageCollectorJob = scope.launch {
                client.messages.collect { handleMessage(it) }
            }
            listenJob = scope.launch {
                client.listen()
                loggedIn = false
                pingJob?.cancel()
                _state.value = _state.value.copy(isConnected = false)
            }

            client.sendMessage(
                SyncplayMessage(
                    hello = HelloMessage(
                        username = username,
                        room = Room(room),
                        version = SYNCPLAY_LEGACY_VERSION,
                        realversion = SYNCPLAY_PROTOCOL_VERSION,
                        password = password.md5OrNull(),
                        features = buildJsonObject {},
                    ),
                ),
            )
        }
    }

    fun updatePlayerState(position: Double, paused: Boolean, doSeek: Boolean? = null) {
        lastLocalPlaybackState = SyncplayPlaybackState(
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
        disconnect(error = null)
    }

    private fun handleMessage(message: SyncplayMessage) {
        message.error?.let {
            _state.value = _state.value.copy(error = it.message)
            disconnect(it.message)
            return
        }

        message.hello?.let { hello ->
            loggedIn = true
            val acceptedUsername = hello.username ?: _state.value.username
            val acceptedRoom = hello.room?.name ?: _state.value.room
            _state.value = _state.value.copy(
                isConnected = true,
                username = acceptedUsername,
                room = acceptedRoom,
                users = (_state.value.users + listOfNotNull(acceptedUsername)).distinct(),
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
        pingJob = scope.launch {
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

    private fun stopSession() {
        loggedIn = false
        pingJob?.cancel()
        listenJob?.cancel()
        messageCollectorJob?.cancel()
        pingJob = null
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

    private fun currentPlaybackState(): SyncplayPlaybackState {
        return playbackStateProvider?.invoke()?.also { lastLocalPlaybackState = it }
            ?: lastLocalPlaybackState
    }

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
                state = StateMessage(
                    ping = Ping(
                        clientLatencyCalculation = nowSeconds(),
                        clientRtt = lastClientRtt,
                        latencyCalculation = lastServerLatencyCalculation,
                    ),
                    playstate = if (canSendPlaystate) {
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
        val adjustedPosition = if (playstate.paused == false) {
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
            averagedClientRtt = if (averagedClientRtt <= 0.0) {
                rtt
            } else {
                averagedClientRtt * 0.85 + rtt * 0.15
            }
        }

        val senderRtt = ping.serverRtt ?: ping.senderLatency ?: return
        lastForwardDelay = if (averagedClientRtt > 0.0 && senderRtt < lastClientRtt) {
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
    }
}
