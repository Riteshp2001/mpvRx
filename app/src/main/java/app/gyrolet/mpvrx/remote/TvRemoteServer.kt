/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Listens only on Android TV while MPVRX is in the foreground; every new phone is approved on the TV. */
object TvRemoteServer {
  data class PairingRequest(
    val id: Long,
    val deviceName: String,
    val code: String,
  )

  private const val TAG = "TvRemoteServer"
  private const val HANDSHAKE_TIMEOUT_MS = 10_000
  private const val APPROVAL_TIMEOUT_SECONDS = 60L
  private const val MAX_CONNECTIONS = 4
  private const val MAX_NAME_LENGTH = 40
  private const val DENIAL_COOLDOWN_MS = 20_000L

  private val _pairingRequest = MutableStateFlow<PairingRequest?>(null)
  val pairingRequest: StateFlow<PairingRequest?> = _pairingRequest.asStateFlow()

  private val _address = MutableStateFlow<String?>(null)

  /** "ip:port" phones can type when discovery is blocked on the network; null while stopped. */
  val address: StateFlow<String?> = _address.asStateFlow()

  private val _revision = MutableStateFlow(0)

  /** Changes whenever the paired-phone list changes. */
  val revision: StateFlow<Int> = _revision.asStateFlow()

  private val lock = Any()
  private val requestIds = AtomicLong()

  // Socket to pairing id; empty while the phone is still pairing.
  private val sessions = ConcurrentHashMap<Socket, String>()

  // A denied address cannot reopen the prompt at once, so a LAN device cannot spam the TV.
  private val deniedAt = ConcurrentHashMap<String, Long>()
  private var serverSocket: ServerSocket? = null
  private var registration: NsdManager.RegistrationListener? = null
  private var pendingDecision: CompletableFuture<Boolean>? = null
  private var wantRunning = false

  fun onAppForeground(context: Context) {
    if (!DeviceFormFactor.isTelevision(context)) return
    val appContext = context.applicationContext
    if (!RemoteControlStore(appContext).serverEnabled) return
    synchronized(lock) { wantRunning = true }
    thread(name = "mpvrx-remote-start", isDaemon = true) { start(appContext) }
  }

  fun onAppBackground(context: Context) {
    synchronized(lock) { wantRunning = false }
    stop(context.applicationContext)
  }

  fun setEnabled(context: Context, enabled: Boolean) {
    val appContext = context.applicationContext
    RemoteControlStore(appContext).serverEnabled = enabled
    if (enabled) onAppForeground(appContext) else onAppBackground(appContext)
  }

  fun respondToPairing(id: Long, allow: Boolean) {
    synchronized(lock) {
      if (_pairingRequest.value?.id == id) pendingDecision?.complete(allow)
    }
  }

  fun removePairedPhone(context: Context, id: String) {
    RemoteControlStore(context).removePairedPhone(id)
    sessions.entries.filter { it.value == id }.forEach { runCatching { it.key.close() } }
    _revision.update { it + 1 }
  }

  private fun start(context: Context) {
    synchronized(lock) {
      if (!wantRunning || serverSocket != null) return
      val server = bind() ?: return
      serverSocket = server
      registration = register(context, server.localPort)
      _address.value = localAddress(context)?.let { "$it:${server.localPort}" }
      thread(name = "mpvrx-remote-accept", isDaemon = true) { acceptLoop(context, server) }
    }
  }

  private fun stop(context: Context) {
    val server: ServerSocket?
    val listener: NsdManager.RegistrationListener?
    synchronized(lock) {
      server = serverSocket
      listener = registration
      serverSocket = null
      registration = null
      _address.value = null
      pendingDecision?.complete(false)
    }
    runCatching { server?.close() }
    sessions.keys.forEach { runCatching { it.close() } }
    if (listener != null) {
      context.getSystemService(NsdManager::class.java)?.let { manager -> runCatching { manager.unregisterService(listener) } }
    }
  }

  private fun bind(): ServerSocket? {
    for (port in listOf(REMOTE_DEFAULT_PORT, 0)) {
      val server = ServerSocket()
      try {
        server.reuseAddress = true
        server.bind(InetSocketAddress(port), MAX_CONNECTIONS)
        return server
      } catch (error: IOException) {
        runCatching { server.close() }
        Log.w(TAG, "Remote control port $port unavailable", error)
      }
    }
    return null
  }

  private fun acceptLoop(context: Context, server: ServerSocket) {
    while (!server.isClosed) {
      val client =
        try {
          server.accept()
        } catch (_: IOException) {
          break
        }
      if (sessions.size >= MAX_CONNECTIONS) {
        runCatching { client.close() }
        continue
      }
      sessions[client] = ""
      thread(name = "mpvrx-remote-session", isDaemon = true) {
        try {
          serve(context, client)
        } catch (error: Exception) {
          Log.d(TAG, "Remote session ended: ${error.javaClass.simpleName}")
        } finally {
          sessions.remove(client)
          runCatching { client.close() }
        }
      }
    }
  }

  private fun serve(context: Context, socket: Socket) {
    socket.soTimeout = HANDSHAKE_TIMEOUT_MS
    socket.tcpNoDelay = true
    val channel = RemoteChannel(socket)
    val store = RemoteControlStore(context)

    val hello = channel.receive()
    require(hello.type == RemoteMessageType.HELLO && hello.version == REMOTE_PROTOCOL_VERSION) {
      "Unsupported remote protocol"
    }
    val clientKey = hello.publicKey.fromBase64()
    val keyPair = RemoteCrypto.generateKeyPair()
    val serverKey = keyPair.public.encoded
    val shared = RemoteCrypto.sharedSecret(keyPair, RemoteCrypto.decodePublicKey(clientKey))
    val serverNonce = RemoteCrypto.randomBytes(REMOTE_NONCE_BYTES)
    val tvId = store.tvId
    val tvName = deviceName(context)
    channel.send(
      RemoteMessage(
        type = RemoteMessageType.HELLO,
        version = REMOTE_PROTOCOL_VERSION,
        publicKey = serverKey.toBase64(),
        commitment = RemoteCrypto.commitment(serverKey, clientKey, serverNonce).toBase64(),
        tvId = tvId,
        name = tvName,
      ),
    )

    val nonceMessage = channel.receive()
    require(nonceMessage.type == RemoteMessageType.NONCE) { "Expected remote nonce" }
    val clientNonce = nonceMessage.nonce.fromBase64(REMOTE_NONCE_BYTES)
    val paired = nonceMessage.pairId?.let(store::pairedPhone)
    channel.send(
      RemoteMessage(
        type = RemoteMessageType.NONCE,
        nonce = serverNonce.toBase64(),
        mode = if (paired != null) RemoteMode.RESUME else RemoteMode.PAIR,
      ),
    )
    val transcript = RemoteCrypto.transcript(clientKey, serverKey, clientNonce, serverNonce, tvId)

    if (paired != null) {
      val secret = paired.secret.fromBase64(REMOTE_SECRET_BYTES)
      val auth = channel.receive()
      val expected = RemoteCrypto.proof(secret, "client", transcript)
      if (auth.type != RemoteMessageType.AUTH || !RemoteCrypto.matches(expected, auth.proof.fromBase64())) {
        channel.send(RemoteMessage(type = RemoteMessageType.ERROR, reason = RemoteReason.UNPAIRED))
        return
      }
      channel.send(
        RemoteMessage(type = RemoteMessageType.AUTH, proof = RemoteCrypto.proof(secret, "server", transcript).toBase64()),
      )
      channel.cipher = RemoteCrypto.sessionCipher(shared, secret, transcript, server = true)
      sessions[socket] = paired.id
    } else {
      channel.cipher = RemoteCrypto.sessionCipher(shared, null, transcript, server = true)
      val device = hello.device.orEmpty().trim().take(MAX_NAME_LENGTH).ifBlank { "Phone" }
      val remoteAddress = socket.inetAddress?.hostAddress.orEmpty()
      val lastDenied = deniedAt[remoteAddress]
      val approved =
        if (lastDenied != null && SystemClock.elapsedRealtime() - lastDenied < DENIAL_COOLDOWN_MS) {
          false
        } else {
          requestApproval(device, RemoteCrypto.pairingCode(transcript))
        }
      if (approved == false) deniedAt[remoteAddress] = SystemClock.elapsedRealtime()
      if (approved != true) {
        val reason = if (approved == null) RemoteReason.BUSY else RemoteReason.DENIED
        channel.send(RemoteMessage(type = RemoteMessageType.ERROR, reason = reason))
        return
      }
      val phone = PairedPhone(UUID.randomUUID().toString(), device, RemoteCrypto.randomBytes(REMOTE_SECRET_BYTES).toBase64())
      channel.send(
        RemoteMessage(type = RemoteMessageType.PAIRED, pairId = phone.id, secret = phone.secret, tvId = tvId, name = tvName),
      )
      store.addPairedPhone(phone)
      sessions[socket] = phone.id
      _revision.update { it + 1 }
    }

    socket.soTimeout = 0
    socket.keepAlive = true
    while (true) {
      val failure = TvInputDispatcher.perform(context, channel.receive())
      channel.send(RemoteMessage(type = RemoteMessageType.ACK, ok = failure == null, reason = failure))
    }
  }

  /** Blocks until the TV user answers; null when another request is already on screen. */
  private fun requestApproval(device: String, code: String): Boolean? {
    val decision = CompletableFuture<Boolean>()
    synchronized(lock) {
      if (pendingDecision != null || serverSocket == null) return null
      pendingDecision = decision
      _pairingRequest.value = PairingRequest(requestIds.incrementAndGet(), device, code)
    }
    return try {
      decision.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: Exception) {
      false
    } finally {
      synchronized(lock) {
        if (pendingDecision === decision) {
          pendingDecision = null
          _pairingRequest.value = null
        }
      }
    }
  }

  private fun register(context: Context, servicePort: Int): NsdManager.RegistrationListener? {
    val manager = context.getSystemService(NsdManager::class.java) ?: return null
    val info =
      NsdServiceInfo().apply {
        serviceName = deviceName(context)
        serviceType = REMOTE_SERVICE_TYPE
        port = servicePort
        setAttribute("id", RemoteControlStore(context).tvId)
      }
    val listener =
      object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
          Log.w(TAG, "Remote control discovery unavailable: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
      }
    return runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
      .map { listener }
      .getOrNull()
  }

  private fun localAddress(context: Context): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = manager.activeNetwork ?: return null
    return manager.getLinkProperties(network)?.linkAddresses.orEmpty()
      .map { it.address }
      .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
      ?.hostAddress
  }

  private fun deviceName(context: Context): String =
    (
      Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL.orEmpty().ifBlank { "Android TV" }
    ).take(MAX_NAME_LENGTH)
}
