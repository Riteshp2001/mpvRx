// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.remote

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.remote.PairedTv
import app.gyrolet.mpvrx.remote.REMOTE_DEFAULT_PORT
import app.gyrolet.mpvrx.remote.REMOTE_MAX_TEXT_LENGTH
import app.gyrolet.mpvrx.remote.REMOTE_NONCE_BYTES
import app.gyrolet.mpvrx.remote.REMOTE_PROTOCOL_VERSION
import app.gyrolet.mpvrx.remote.REMOTE_SECRET_BYTES
import app.gyrolet.mpvrx.remote.REMOTE_SERVICE_TYPE
import app.gyrolet.mpvrx.remote.RemoteChannel
import app.gyrolet.mpvrx.remote.RemoteControlStore
import app.gyrolet.mpvrx.remote.RemoteCrypto
import app.gyrolet.mpvrx.remote.RemoteMessage
import app.gyrolet.mpvrx.remote.RemoteMessageType
import app.gyrolet.mpvrx.remote.RemoteMode
import app.gyrolet.mpvrx.remote.RemoteReason
import app.gyrolet.mpvrx.remote.fromBase64
import app.gyrolet.mpvrx.remote.toBase64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

data class DiscoveredTv(
  val name: String,
  val host: String,
  val port: Int,
  val tvId: String?,
)

enum class RemoteFailure(
  @StringRes val messageRes: Int,
) {
  Unreachable(R.string.remote_error_unreachable),
  Denied(R.string.remote_error_denied),
  Busy(R.string.remote_error_busy),
  Verification(R.string.remote_error_verification),
  Disconnected(R.string.remote_error_disconnected),
}

sealed interface RemoteConnection {
  data object Idle : RemoteConnection

  data class Connecting(
    val name: String,
  ) : RemoteConnection

  data class AwaitingApproval(
    val name: String,
    val code: String,
  ) : RemoteConnection

  data class Connected(
    val name: String,
  ) : RemoteConnection

  data class Failed(
    val reason: RemoteFailure,
  ) : RemoteConnection
}

class RemoteControlViewModel(
  application: Application,
) : AndroidViewModel(application) {
  private val store = RemoteControlStore(application)
  private val sender = Executors.newSingleThreadExecutor()

  private val _connection = MutableStateFlow<RemoteConnection>(RemoteConnection.Idle)
  val connection: StateFlow<RemoteConnection> = _connection.asStateFlow()

  private val _discovered = MutableStateFlow<List<DiscoveredTv>>(emptyList())
  val discovered: StateFlow<List<DiscoveredTv>> = _discovered.asStateFlow()

  private val _pairedTvIds = MutableStateFlow(store.pairedTvs().map { it.tvId }.toSet())
  val pairedTvIds: StateFlow<Set<String>> = _pairedTvIds.asStateFlow()

  /** Paired TVs at their last address, so they stay reachable where discovery is blocked. */
  private val _savedTvs = MutableStateFlow(savedTvList())
  val savedTvs: StateFlow<List<DiscoveredTv>> = _savedTvs.asStateFlow()

  /** The TV's reason for refusing the last command, e.g. MPVRX is not in the foreground there. */
  private val _commandFailure = MutableStateFlow<String?>(null)
  val commandFailure: StateFlow<String?> = _commandFailure.asStateFlow()

  private var discoveryJob: Job? = null
  private var sessionJob: Job? = null

  @Volatile private var activeSocket: Socket? = null

  @Volatile private var activeChannel: RemoteChannel? = null

  @Volatile private var sessionToken: Any? = null

  init {
    startDiscovery()
  }

  fun connect(tv: DiscoveredTv) = startSession(tv.host, tv.port, tv.name)

  /** Accepts "host" or "host:port"; returns false when the text is not an address. */
  fun connectToAddress(address: String): Boolean {
    val trimmed =
      address
        .trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == '/' }) return false
    val hasPort = trimmed.count { it == ':' } == 1
    val host = if (hasPort) trimmed.substringBefore(':') else trimmed
    val port =
      if (hasPort) {
        trimmed.substringAfter(':').toIntOrNull() ?: return false
      } else {
        REMOTE_DEFAULT_PORT
      }
    if (host.isEmpty() || port !in 1..65535) return false
    startSession(host, port, host)
    return true
  }

  fun sendKey(key: String) = send(RemoteMessage(type = RemoteMessageType.KEY, key = key))

  fun forget(tv: DiscoveredTv) {
    tv.tvId?.let(store::removePairedTv)
    refreshSaved()
  }

  fun sendText(text: String) {
    if (text.isNotEmpty()) send(RemoteMessage(type = RemoteMessageType.TEXT, text = text.take(REMOTE_MAX_TEXT_LENGTH)))
  }

  fun disconnect() {
    sessionToken = null
    sessionJob?.cancel()
    sessionJob = null
    closeSocket()
    _commandFailure.value = null
    _connection.value = RemoteConnection.Idle
    startDiscovery()
  }

  override fun onCleared() {
    sessionToken = null
    sessionJob?.cancel()
    closeSocket()
    sender.shutdown()
  }

  private fun send(message: RemoteMessage) {
    val channel = activeChannel ?: return
    val token = sessionToken
    sender.execute {
      runCatching { channel.send(message) }.onFailure { if (sessionToken === token) closeSocket() }
    }
  }

  private fun startDiscovery() {
    if (discoveryJob?.isActive == true) return
    discoveryJob =
      viewModelScope.launch {
        discoverRemoteTvs(getApplication()).collect { _discovered.value = it }
      }
  }

  private fun startSession(
    host: String,
    port: Int,
    label: String,
  ) {
    sessionJob?.cancel()
    closeSocket()
    discoveryJob?.cancel()
    _commandFailure.value = null
    _connection.value = RemoteConnection.Connecting(label)
    val token = Any()
    sessionToken = token
    sessionJob =
      viewModelScope.launch(Dispatchers.IO) {
        val failure =
          try {
            runSession(token, host, port, label)
          } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (_connection.value is RemoteConnection.Connected) {
              RemoteFailure.Disconnected
            } else {
              RemoteFailure.Unreachable
            }
          }
        if (isActive && sessionToken === token) {
          activeChannel = null
          _connection.value = RemoteConnection.Failed(failure)
          launch(Dispatchers.Main) { startDiscovery() }
        }
      }
  }

  /** Returns why the session ended. A TV that forgot this phone answers in pairing mode. */
  private fun runSession(
    token: Any,
    host: String,
    port: Int,
    label: String,
  ): RemoteFailure {
    return Socket().use { socket ->
      if (sessionToken !== token) return RemoteFailure.Disconnected
      activeSocket = socket
      socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
      socket.soTimeout = HANDSHAKE_TIMEOUT_MS
      socket.tcpNoDelay = true
      val channel = RemoteChannel(socket)

      val keyPair = RemoteCrypto.generateKeyPair()
      val clientKey = keyPair.public.encoded
      channel.send(
        RemoteMessage(
          type = RemoteMessageType.HELLO,
          version = REMOTE_PROTOCOL_VERSION,
          publicKey = clientKey.toBase64(),
          device = deviceName(getApplication()),
        ),
      )
      val hello = channel.receive()
      if (hello.type != RemoteMessageType.HELLO ||
        hello.version != REMOTE_PROTOCOL_VERSION
      ) {
        return RemoteFailure.Unreachable
      }
      val serverKey = hello.publicKey.fromBase64()
      val commitment = hello.commitment.fromBase64()
      val tvId = hello.tvId?.takeIf { it.isNotBlank() } ?: return RemoteFailure.Unreachable
      val tvName =
        hello.name
          ?.trim()
          ?.take(MAX_NAME_LENGTH)
          ?.takeIf { it.isNotBlank() } ?: label
      val saved = store.pairedTv(tvId)

      val clientNonce = RemoteCrypto.randomBytes(REMOTE_NONCE_BYTES)
      channel.send(
        RemoteMessage(type = RemoteMessageType.NONCE, nonce = clientNonce.toBase64(), pairId = saved?.pairId),
      )
      val nonceReply = channel.receive()
      if (nonceReply.type != RemoteMessageType.NONCE) return RemoteFailure.Unreachable
      val serverNonce = nonceReply.nonce.fromBase64(REMOTE_NONCE_BYTES)
      // The TV committed to its nonce before seeing ours, so the pairing code cannot be steered.
      if (!RemoteCrypto.matches(RemoteCrypto.commitment(serverKey, clientKey, serverNonce), commitment)) {
        return RemoteFailure.Verification
      }
      val transcript = RemoteCrypto.transcript(clientKey, serverKey, clientNonce, serverNonce, tvId)
      val shared = RemoteCrypto.sharedSecret(keyPair, RemoteCrypto.decodePublicKey(serverKey))

      if (nonceReply.mode == RemoteMode.RESUME && saved != null) {
        val secret = saved.secret.fromBase64(REMOTE_SECRET_BYTES)
        channel.send(
          RemoteMessage(
            type = RemoteMessageType.AUTH,
            proof = RemoteCrypto.proof(secret, "client", transcript).toBase64(),
          ),
        )
        val auth = channel.receive()
        val expected = RemoteCrypto.proof(secret, "server", transcript)
        if (auth.type != RemoteMessageType.AUTH || !RemoteCrypto.matches(expected, auth.proof.fromBase64())) {
          return RemoteFailure.Verification
        }
        channel.cipher = RemoteCrypto.sessionCipher(shared, secret, transcript, server = false)
        store.savePairedTv(saved.copy(name = tvName, host = host, port = port))
        refreshSaved()
      } else {
        // A pairing is replaced only after the TV approves a new one, so a spoofed TV cannot erase it.
        channel.cipher = RemoteCrypto.sessionCipher(shared, null, transcript, server = false)
        if (sessionToken !== token) return RemoteFailure.Disconnected
        _connection.value = RemoteConnection.AwaitingApproval(tvName, RemoteCrypto.pairingCode(transcript))
        socket.soTimeout = APPROVAL_TIMEOUT_MS
        val reply = channel.receive()
        if (reply.type == RemoteMessageType.ERROR) {
          return if (reply.reason == RemoteReason.BUSY) RemoteFailure.Busy else RemoteFailure.Denied
        }
        val pairId = reply.pairId?.takeIf { it.isNotBlank() }
        if (reply.type != RemoteMessageType.PAIRED || pairId == null) return RemoteFailure.Verification
        val secret = reply.secret.fromBase64(REMOTE_SECRET_BYTES)
        store.savePairedTv(PairedTv(tvId, tvName, pairId, secret.toBase64(), host, port))
        refreshSaved()
      }

      socket.soTimeout = 0
      socket.keepAlive = true
      if (sessionToken !== token) return RemoteFailure.Disconnected
      activeChannel = channel
      _connection.value = RemoteConnection.Connected(tvName)
      readAcks(token, channel)
    }
  }

  private fun readAcks(
    token: Any,
    channel: RemoteChannel,
  ): Nothing {
    while (true) {
      val message = channel.receive()
      if (message.type == RemoteMessageType.ACK && sessionToken === token) {
        _commandFailure.value = if (message.ok == true) null else message.reason
      }
    }
  }

  private fun savedTvList(): List<DiscoveredTv> =
    store.pairedTvs().map {
      DiscoveredTv(it.name, it.host, it.port, it.tvId)
    }

  private fun refreshSaved() {
    val saved = savedTvList()
    _savedTvs.value = saved
    _pairedTvIds.value = saved.mapNotNull { it.tvId }.toSet()
  }

  private fun closeSocket() {
    activeChannel = null
    activeSocket?.let { runCatching { it.close() } }
    activeSocket = null
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val HANDSHAKE_TIMEOUT_MS = 10_000

    // The TV waits 60 s for its user; allow for the network on top.
    const val APPROVAL_TIMEOUT_MS = 75_000
    const val MAX_NAME_LENGTH = 40
  }
}

private fun deviceName(context: Context): String =
  Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
    ?: Build.MODEL.orEmpty().ifBlank { "Android phone" }

/** MPVRX TVs advertised on this network; resolution is serialized for the API 26 resolver. */
@Suppress("DEPRECATION")
private fun discoverRemoteTvs(context: Context): Flow<List<DiscoveredTv>> =
  callbackFlow {
    val manager = context.getSystemService(NsdManager::class.java)
    if (manager == null) {
      trySend(emptyList())
      awaitClose()
      return@callbackFlow
    }
    val handler = Handler(Looper.getMainLooper())
    val results = linkedMapOf<String, DiscoveredTv>()
    val pending = ArrayDeque<NsdServiceInfo>()
    var resolving = false
    var closed = false
    val multicastLock =
      if (Build.VERSION.SDK_INT < 34) {
        context.getSystemService(WifiManager::class.java)?.createMulticastLock("mpvrx-remote-discovery")?.apply {
          setReferenceCounted(false)
          acquire()
        }
      } else {
        null
      }

    fun resolveNext() {
      if (closed || resolving) return
      val info = pending.removeFirstOrNull() ?: return
      resolving = true
      val listener =
        object : NsdManager.ResolveListener {
          override fun onResolveFailed(
            serviceInfo: NsdServiceInfo,
            errorCode: Int,
          ) {
            handler.post {
              resolving = false
              resolveNext()
            }
          }

          override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            handler.post {
              resolving = false
              val address =
                if (Build.VERSION.SDK_INT >= 34) {
                  serviceInfo.hostAddresses.firstOrNull { it is Inet4Address }
                    ?: serviceInfo.hostAddresses.firstOrNull()
                } else {
                  serviceInfo.host
                }
              val host = address?.hostAddress
              if (!closed && host != null && serviceInfo.port in 1..65535) {
                val tvId = serviceInfo.attributes["id"]?.toString(Charsets.UTF_8)
                results[serviceInfo.serviceName] = DiscoveredTv(serviceInfo.serviceName, host, serviceInfo.port, tvId)
                trySend(results.values.distinctBy { it.host to it.port })
              }
              resolveNext()
            }
          }
        }
      try {
        manager.resolveService(info, listener)
      } catch (_: RuntimeException) {
        resolving = false
        handler.post { resolveNext() }
      }
    }

    val discoveryListener =
      object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(
          serviceType: String,
          errorCode: Int,
        ) = Unit

        override fun onStopDiscoveryFailed(
          serviceType: String,
          errorCode: Int,
        ) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
          handler.post {
            if (!closed && pending.size < MAX_PENDING_RESOLVES) {
              pending.addLast(serviceInfo)
              resolveNext()
            }
          }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
          handler.post {
            if (results.remove(serviceInfo.serviceName) !=
              null
            ) {
              trySend(results.values.distinctBy { it.host to it.port })
            }
          }
        }
      }

    trySend(emptyList())
    handler.post {
      runCatching { manager.discoverServices(REMOTE_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
    }
    awaitClose {
      handler.post {
        closed = true
        pending.clear()
        runCatching { manager.stopServiceDiscovery(discoveryListener) }
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
      }
    }
  }

private const val MAX_PENDING_RESOLVES = 16
