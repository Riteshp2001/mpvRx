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
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Finds file servers that do not advertise themselves. Windows PCs, most NAS boxes and routers
 * announce SMB through NetBIOS or WS-Discovery rather than DNS-SD, and FTP, SFTP and WebDAV
 * servers rarely announce anything. Like file managers, this tries each protocol's usual ports on
 * every address of the local IPv4 subnet and keeps only what proves itself: SMB's port, an FTP or
 * SSH greeting, a WebDAV reply to OPTIONS. It runs only while the user scans, sends no
 * credentials, and is bounded in subnet size, time and parallelism.
 */
internal class LanServiceScanner(
  context: Context,
) {
  private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

  private enum class Probe(
    val port: Int,
    val protocol: NetworkProtocol,
    val greeting: String? = null,
    val https: Boolean = false,
  ) {
    SMB(445, NetworkProtocol.SMB),
    FTP(21, NetworkProtocol.FTP, greeting = "220"),

    // Common ports of FTP server apps on phones and of unprivileged desktop servers.
    FTP_ALTERNATE(2121, NetworkProtocol.FTP, greeting = "220"),
    FTP_ANDROID(2221, NetworkProtocol.FTP, greeting = "220"),
    SFTP(22, NetworkProtocol.SFTP, greeting = "SSH-"),
    WEBDAV(80, NetworkProtocol.WEBDAV),
    WEBDAV_ALTERNATE(8080, NetworkProtocol.WEBDAV),
    WEBDAV_SYNOLOGY(5005, NetworkProtocol.WEBDAV),
    WEBDAVS(443, NetworkProtocol.WEBDAV, https = true),
    WEBDAVS_SYNOLOGY(5006, NetworkProtocol.WEBDAV, https = true),
  }

  suspend fun scan(onServer: (ServerSuggestion) -> Unit) =
    coroutineScope {
      // Blocking connects, one thread each, so an address with nothing on it costs one timeout.
      val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLEL)
      localSubnets().forEach { subnet ->
        val http = webDavClient(subnet.network)
        subnet.hosts().forEach { host ->
          launch(dispatcher) { scanHost(subnet.network, http, host, onServer) }
        }
      }
    }

  private suspend fun scanHost(
    network: Network,
    http: OkHttpClient,
    host: Inet4Address,
    onServer: (ServerSuggestion) -> Unit,
  ) {
    val address = host.hostAddress ?: return
    // A host's ports are tried together: an empty address costs one timeout, not one per port.
    val found =
      coroutineScope {
        Probe.entries.map { probe -> async { identify(network, http, host, address, probe) } }.awaitAll()
      }.filterNotNull()
    if (found.isEmpty() || !currentCoroutineContext().isActive) return
    val name = netbiosName(network, host).orEmpty()
    if (!currentCoroutineContext().isActive) return
    found.forEach { onServer(it.copy(name = name)) }
  }

  private fun identify(
    network: Network,
    http: OkHttpClient,
    host: Inet4Address,
    address: String,
    probe: Probe,
  ): ServerSuggestion? {
    if (!answers(network, host, probe)) return null
    if (probe.protocol != NetworkProtocol.WEBDAV) {
      return ServerSuggestion("", address, probe.protocol, probe.port, source = ServerSuggestionSource.DISCOVERED)
    }
    val path = webDavPath(http, address, probe) ?: return null
    return ServerSuggestion(
      name = "",
      host = address,
      protocol = NetworkProtocol.WEBDAV,
      port = probe.port,
      path = path.takeUnless { it == "/" }?.let { runCatching { NetworkPath.from(it).value }.getOrNull() },
      useHttps = probe.https,
      source = ServerSuggestionSource.DISCOVERED,
    )
  }

  /** Whether [probe]'s port accepts a connection and, where the protocol greets first, greets as it. */
  private fun answers(
    network: Network,
    host: Inet4Address,
    probe: Probe,
  ): Boolean =
    try {
      network.socketFactory.createSocket().use { socket ->
        socket.connect(InetSocketAddress(host, probe.port), CONNECT_TIMEOUT_MS)
        val greeting = probe.greeting ?: return@use true
        socket.soTimeout = GREETING_TIMEOUT_MS
        val input = socket.getInputStream()
        val buffer = ByteArray(MAX_GREETING_BYTES)
        var count = 0
        while (count < greeting.length) {
          val read = input.read(buffer, count, buffer.size - count)
          if (read <= 0) break
          count += read
        }
        String(buffer, 0, count, Charsets.ISO_8859_1).startsWith(greeting)
      }
    } catch (_: IOException) {
      false
    } catch (_: SecurityException) {
      false
    }

  /** The first usual WebDAV root that answers OPTIONS as WebDAV, or null for another web server. */
  private fun webDavPath(
    http: OkHttpClient,
    address: String,
    probe: Probe,
  ): String? {
    for (path in WEBDAV_PATHS) {
      val url =
        HttpUrl
          .Builder()
          .scheme(if (probe.https) "https" else "http")
          .host(address)
          .port(probe.port)
          .encodedPath(path)
          .build()
      val request =
        Request
          .Builder()
          .url(url)
          .method("OPTIONS", null)
          .build()
      val isWebDav =
        try {
          http.newCall(request).execute().use { response ->
            response.header("DAV") != null ||
              listOfNotNull(
                response.header("Allow"),
                response.header("Public"),
              ).any { it.contains("PROPFIND", true) } ||
              // Synology's WebDAV ports ask for credentials before saying anything else.
              (response.code == 401 && probe.port in SYNOLOGY_WEBDAV_PORTS)
          }
        } catch (_: IOException) {
          // Includes TLS failures: a self-signed HTTPS server is not trusted here, so it is typed in.
          return null
        } catch (_: SecurityException) {
          return null
        }
      if (isWebDav) return path
    }
    return null
  }

  private fun webDavClient(network: Network): OkHttpClient =
    SharedHttpClient.derive {
      connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
      readTimeout(GREETING_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
      callTimeout(3, TimeUnit.SECONDS)
      followRedirects(false)
      followSslRedirects(false)
      retryOnConnectionFailure(false)
      socketFactory(network.socketFactory)
      dns { name -> network.getAllByName(name).toList() }
    }

  private class Subnet(
    val network: Network,
    private val self: Inet4Address,
    private val prefix: Int,
  ) {
    /** Every other address of the subnet; a larger one is swept only in this device's /24. */
    fun hosts(): List<Inet4Address> {
      val bits = prefix.coerceIn(MIN_PREFIX, MAX_PREFIX)
      val selfValue = ByteBuffer.wrap(self.address).int
      val base = selfValue and (-1 shl (32 - bits))
      val size = 1 shl (32 - bits)
      return (1 until size - 1).map { base + it }.filter { it != selfValue }.mapNotNull { value ->
        InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(value).array()) as? Inet4Address
      }
    }
  }

  // allNetworks has no synchronous replacement; NetworkCallback cannot back this one-shot scan.
  @Suppress("DEPRECATION")
  private fun localSubnets(): List<Subnet> {
    val connectivity = manager ?: return emptyList()
    return runCatching {
      connectivity.allNetworks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
          !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
          return@mapNotNull null
        }
        val link =
          connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().firstOrNull { link ->
            val address = link.address
            address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
          } ?: return@mapNotNull null
        Subnet(network, link.address as Inet4Address, link.prefixLength)
      }
    }.getOrDefault(emptyList())
  }

  /** The host's NetBIOS computer name (node status query, UDP 137), or null without one. */
  private fun netbiosName(
    network: Network,
    host: Inet4Address,
  ): String? =
    try {
      DatagramSocket().use { socket ->
        network.bindSocket(socket)
        socket.soTimeout = NETBIOS_TIMEOUT_MS
        socket.send(DatagramPacket(NODE_STATUS_REQUEST, NODE_STATUS_REQUEST.size, host, NETBIOS_PORT))
        val buffer = ByteArray(1024)
        val reply = DatagramPacket(buffer, buffer.size)
        socket.receive(reply)
        parseNodeStatus(buffer, reply.length)
      }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }

  companion object {
    private const val NETBIOS_PORT = 137
    private const val CONNECT_TIMEOUT_MS = 350
    private const val GREETING_TIMEOUT_MS = 1_500
    private const val NETBIOS_TIMEOUT_MS = 700
    private const val MAX_GREETING_BYTES = 256
    private const val MAX_PARALLEL = 128
    private const val MIN_PREFIX = 24
    private const val MAX_PREFIX = 30
    private val SYNOLOGY_WEBDAV_PORTS = setOf(5005, 5006)
    private val WEBDAV_PATHS = listOf("/", "/webdav/", "/dav/")

    /** NBSTAT query for the wildcard name "*", as `nmblookup -A` sends it. */
    private val NODE_STATUS_REQUEST: ByteArray =
      byteArrayOf(
        0x4D,
        0x52, // transaction id
        0x00,
        0x00, // flags
        0x00,
        0x01, // one question
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00, // no answer, authority or additional records
        0x20, // the encoded name is 32 bytes
      ) + "CK".toByteArray(Charsets.US_ASCII) + "A".repeat(30).toByteArray(Charsets.US_ASCII) +
        byteArrayOf(
          0x00, // end of name
          0x00,
          0x21, // NBSTAT
          0x00,
          0x01, // IN
        )

    /** The first unique workstation or server name of a node status reply. */
    internal fun parseNodeStatus(
      reply: ByteArray,
      length: Int,
    ): String? {
      if (length < 12) return null
      val answers = ((reply[6].toInt() and 0xFF) shl 8) or (reply[7].toInt() and 0xFF)
      if (answers < 1) return null
      var offset = 12
      // The answer repeats the queried name, either in full or as a compression pointer.
      if (offset < length && (reply[offset].toInt() and 0xC0) == 0xC0) {
        offset += 2
      } else {
        while (offset < length && reply[offset].toInt() != 0) offset += (reply[offset].toInt() and 0xFF) + 1
        offset += 1
      }
      offset += 10 // type, class, ttl, data length
      if (offset >= length) return null
      val count = reply[offset].toInt() and 0xFF
      offset += 1
      repeat(count) {
        if (offset + 18 > length) return null
        val suffix = reply[offset + 15].toInt() and 0xFF
        val group = (reply[offset + 16].toInt() and 0x80) != 0
        if (!group && (suffix == 0x00 || suffix == 0x20)) {
          val name = String(reply, offset, 15, Charsets.ISO_8859_1).trim { it <= ' ' }
          if (name.isNotEmpty() && name.all { it.code in 0x20..0x7E }) return name
        }
        offset += 18
      }
      return null
    }
  }
}
