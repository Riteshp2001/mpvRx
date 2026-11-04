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
import android.net.Network
import android.net.NetworkCapabilities
import app.gyrolet.mpvrx.data.network.client.DlnaXml
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.network.SharedHttpClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** Foreground SSDP discovery; sockets and HTTP requests are bounded to the active scan. */
internal class DlnaServerDiscovery(context: Context) {
  private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
  private val http = SharedHttpClient.derive { callTimeout(3, TimeUnit.SECONDS) }

  @Suppress("DEPRECATION")
  suspend fun discover(onServer: (ServerSuggestion) -> Unit) = coroutineScope {
    val networks = runCatching {
      manager?.allNetworks.orEmpty().filter { network ->
        val caps = manager?.getNetworkCapabilities(network)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
          caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
      }
    }.getOrDefault(emptyList())
    val seen = ConcurrentHashMap.newKeySet<String>()
    // Use each local network even if mobile data is the default route.
    val scanNetworks: List<Network?> = networks.ifEmpty { listOf(null) }
    scanNetworks.forEach { network ->
      launch(Dispatchers.IO) { scanNetwork(network, seen, onServer) }
    }
  }

  private suspend fun scanNetwork(
    network: Network?,
    seen: MutableSet<String>,
    onServer: (ServerSuggestion) -> Unit,
  ) {
    try {
      val networkHttp = if (network == null) http else http.newBuilder()
        .socketFactory(network.socketFactory)
        .dns { host -> network.getAllByName(host).toList() }
        .build()
      DatagramSocket().use { socket ->
        network?.bindSocket(socket)
        socket.soTimeout = 500
        val message = ("M-SEARCH * HTTP/1.1\r\n" +
          "HOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
          "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray(Charsets.US_ASCII)
        val target = InetAddress.getByName("239.255.255.250")
        repeat(2) { socket.send(DatagramPacket(message, message.size, target, 1900)) }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (currentCoroutineContext().isActive && System.nanoTime() < end && seen.size < 100) {
          val packet = DatagramPacket(ByteArray(16 * 1024), 16 * 1024)
          try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
          val response = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
          val location = response.lineSequence().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("location", true)) {
              line.substring(colon + 1).trim()
            } else null
          }.firstOrNull()?.toHttpUrlOrNull() ?: continue
          if (location.username.isNotEmpty() || location.password.isNotEmpty() ||
            location.query != null || location.fragment != null || !seen.add(location.toString())) continue
          val path = runCatching { NetworkPath.from(location.pathSegments.joinToString("/")).value }.getOrNull() ?: continue
          val suggestion = runCatching {
            val request = Request.Builder().url(location).get().build()
            networkHttp.newCall(request).execute().use { result ->
              if (!result.isSuccessful) return@use null
              val device = DlnaXml.description(DlnaXml.readBounded(result.body.byteStream()), result.request.url)
              ServerSuggestion(
                name = device.name.ifBlank { location.host }, host = location.host,
                protocol = NetworkProtocol.DLNA, port = location.port, path = path,
                useHttps = location.isHttps, source = ServerSuggestionSource.DISCOVERED,
              )
            }
          }.getOrNull()
          if (suggestion != null && currentCoroutineContext().isActive) onServer(suggestion)
        }
      }
    } catch (_: java.io.IOException) {
      // Other interfaces may still discover devices; manual entry remains available.
    } catch (_: SecurityException) {
      // Discovery can be unavailable on restricted devices or networks.
    }
  }
}
