// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.data.network.client

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** The standard SMBJ transport needs an explicit TCP connect deadline on Android. */
internal class SmbSocketFactory(
  private val connectTimeoutMillis: Int,
) : SocketFactory() {
  override fun createSocket(): Socket = Socket()

  override fun createSocket(
    host: String,
    port: Int,
  ): Socket = connect(InetSocketAddress(host, port))

  override fun createSocket(
    address: InetAddress,
    port: Int,
  ): Socket = connect(InetSocketAddress(address, port))

  override fun createSocket(
    host: String,
    port: Int,
    localAddress: InetAddress,
    localPort: Int,
  ): Socket = connect(InetSocketAddress(host, port), InetSocketAddress(localAddress, localPort))

  override fun createSocket(
    address: InetAddress,
    port: Int,
    localAddress: InetAddress,
    localPort: Int,
  ): Socket = connect(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

  private fun connect(
    remote: InetSocketAddress,
    local: InetSocketAddress? = null,
  ): Socket {
    val socket = Socket()
    try {
      // SMB is strictly request/response; do not let Nagle hold back a request queued behind
      // another in-flight one (concurrent range reads, listings, CLOSE without waiting).
      socket.tcpNoDelay = true
      if (local != null) socket.bind(local)
      socket.connect(remote, connectTimeoutMillis)
      return socket
    } catch (error: Throwable) {
      runCatching { socket.close() }
      throw error
    }
  }
}
