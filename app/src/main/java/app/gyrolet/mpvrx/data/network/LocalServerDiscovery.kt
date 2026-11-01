/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.domain.network.normalizedAddress
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class ServerSuggestionSource { SAVED, DISCOVERED, DEVICE, LOOPBACK }

data class ServerSuggestion(
  val name: String,
  val host: String,
  val protocol: NetworkProtocol? = null,
  val port: Int? = null,
  val path: String? = null,
  val useHttps: Boolean = false,
  val source: ServerSuggestionSource,
) {
  val authority: String
    get() = (if (':' in host) "[$host]" else host) + (port?.let { ":$it" } ?: "")

  companion object {
    fun saved(connection: NetworkConnection): ServerSuggestion? = runCatching {
      val address = connection.normalizedAddress()
      ServerSuggestion(
        name = address.name, host = address.host, protocol = address.protocol,
        port = address.port, path = address.path, useHttps = address.useHttps,
        source = ServerSuggestionSource.SAVED,
      )
    }.getOrNull()
  }
}

data class ServerDiscoveryState(
  val servers: List<ServerSuggestion> = emptyList(),
  val isScanning: Boolean = false,
  val unavailable: Boolean = false,
)

/** A bounded, foreground-only DNS-SD search. No credentials, subnet sweep or application startup work. */
class LocalServerDiscovery(context: Context) {
  private val context = context.applicationContext

  // allNetworks has no synchronous replacement; NetworkCallback cannot back this one-shot query.
  @Suppress("DEPRECATION")
  fun localAddresses(): List<ServerSuggestion> {
    val addresses = mutableListOf(
      ServerSuggestion("", "127.0.0.1", source = ServerSuggestionSource.LOOPBACK),
    )
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return addresses
    runCatching {
      manager.allNetworks.forEach { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@forEach
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
          !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return@forEach
        manager.getLinkProperties(network)?.linkAddresses.orEmpty().forEach { link ->
          val address = link.address
          if (!address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isLinkLocalAddress) {
            address.hostAddress?.let {
              addresses += ServerSuggestion("", it, source = ServerSuggestionSource.DEVICE)
            }
          }
        }
      }
    }
    return addresses.distinctBy { it.host }
  }

  @Suppress("DEPRECATION") // Serialized legacy resolution also supports the app's API 26 minimum.
  fun discover(): Flow<ServerDiscoveryState> = callbackFlow {
    val manager = context.getSystemService(NsdManager::class.java)
    if (manager == null) {
      trySend(ServerDiscoveryState(unavailable = true))
      close()
      return@callbackFlow
    }
    val handler = Handler(Looper.getMainLooper())
    val closed = AtomicBoolean(false)
    val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    val results = linkedMapOf<String, ServerSuggestion>()
    val found = mutableSetOf<String>()
    val pending = ArrayDeque<Pair<String, NsdServiceInfo>>()
    var resolving: NsdManager.ResolveListener? = null
    var multicastLock: WifiManager.MulticastLock? = null
    var unavailable = false

    fun emit() {
      if (!closed.get()) trySend(ServerDiscoveryState(results.values.toList(), true, unavailable))
    }
    fun serviceKey(info: NsdServiceInfo) = "${info.serviceType}:${info.serviceName}"
    fun next() {
      if (closed.get() || resolving != null || pending.isEmpty()) return
      val (key, info) = pending.removeFirst()
      if (key !in found) { next(); return }
      val listener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
          handler.post {
            if (!closed.get()) { resolving = null; next() }
          }
        }
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
          handler.post {
            if (closed.get()) return@post
            resolving = null
            if (key in found) {
              val type = serviceInfo.serviceType.trimEnd('.').lowercase()
              val protocol = SERVICE_TYPES[type]
              val address = if (Build.VERSION.SDK_INT >= 34) {
                serviceInfo.hostAddresses.firstOrNull { it is Inet4Address }
                  ?: serviceInfo.hostAddresses.firstOrNull()
              } else serviceInfo.host
              val host = address?.hostAddress
              if (protocol != null && !host.isNullOrBlank() && serviceInfo.port in 1..65535) {
                val path = if (protocol == NetworkProtocol.WEBDAV) {
                  serviceInfo.attributes["path"]?.toString(Charsets.UTF_8)?.let {
                    runCatching { NetworkPath.from(it).value }.getOrNull()
                  }
                } else null
                results[key] = ServerSuggestion(
                  serviceInfo.serviceName, host, protocol, serviceInfo.port, path,
                  useHttps = type == "_webdavs._tcp", source = ServerSuggestionSource.DISCOVERED,
                )
                emit()
              }
            }
            next()
          }
        }
      }
      resolving = listener
      try {
        manager.resolveService(info, listener)
      } catch (_: RuntimeException) {
        resolving = null
        unavailable = true
        emit()
        next()
      }
    }

    var dlnaScan: Job? = null

    val finish = Runnable {
      trySend(ServerDiscoveryState(results.values.toList(), unavailable = unavailable))
      close()
    }
    handler.post {
      if (closed.get()) return@post
      try {
        // SSDP needs multicast on all supported Android versions.
        multicastLock = context.getSystemService(WifiManager::class.java)
          ?.createMulticastLock("mpvrx-server-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
          }
      } catch (_: RuntimeException) {
        unavailable = true
      }
      dlnaScan = launch(Dispatchers.IO) {
        DlnaServerDiscovery(context).discover { server ->
          handler.post {
            if (!closed.get()) {
              results["dlna:${server.host}:${server.port}:${server.path}"] = server
              emit()
            }
          }
        }
      }
      try {
        SERVICE_TYPES.keys.forEach { type ->
          val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
              handler.post {
                unavailable = true
                emit()
                runCatching { manager.stopServiceDiscovery(this) }
              }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
              handler.post {
                if (closed.get() || found.size >= MAX_SERVICES) return@post
                val key = serviceKey(serviceInfo)
                if (found.add(key)) { pending.addLast(key to serviceInfo); next() }
              }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
              handler.post {
                val key = serviceKey(serviceInfo)
                found.remove(key)
                results.remove(key)
                emit()
              }
            }
          }
          listeners += listener
          manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
      } catch (_: RuntimeException) {
        unavailable = true
      }
      emit()
      handler.postDelayed(finish, SCAN_DURATION_MS)
    }
    awaitClose {
      closed.set(true)
      dlnaScan?.cancel()
      handler.post {
        handler.removeCallbacks(finish)
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        if (Build.VERSION.SDK_INT >= 34) {
          resolving?.let { runCatching { manager.stopServiceResolution(it) } }
        }
        pending.clear()
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
      }
    }
  }

  companion object {
    private const val SCAN_DURATION_MS = 12_000L
    private const val MAX_SERVICES = 100
    private val SERVICE_TYPES = linkedMapOf(
      "_smb._tcp" to NetworkProtocol.SMB,
      "_ftp._tcp" to NetworkProtocol.FTP,
      "_sftp-ssh._tcp" to NetworkProtocol.SFTP,
      "_webdav._tcp" to NetworkProtocol.WEBDAV,
      "_webdavs._tcp" to NetworkProtocol.WEBDAV,
    )
  }
}
