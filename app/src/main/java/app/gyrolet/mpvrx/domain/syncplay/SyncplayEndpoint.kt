/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.syncplay

import java.io.EOFException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

data class SyncplayEndpoint(
  val host: String,
  val port: Int,
) {
  companion object {
    fun parse(
      raw: String,
      port: Int,
    ): SyncplayEndpoint {
      val input = raw.trim()
      require(input.isNotEmpty() && input.none { it.isWhitespace() || it.isISOControl() })
      val address =
        when {
          "://" in input -> input
          input.count { it == ':' } > 1 && !input.startsWith('[') -> "syncplay://[$input]"
          else -> "syncplay://$input"
        }
      val uri = URI(address)
      require(uri.scheme.equals("syncplay", true) && uri.rawUserInfo == null)
      require(uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/"))
      val host = requireNotNull(uri.host).removePrefix("[").removeSuffix("]")
      val resolvedPort = if (uri.port == -1) port else uri.port
      require(resolvedPort in 1..65535)
      return SyncplayEndpoint(host, resolvedPort)
    }
  }
}

/**
 * Keep DNS first so IPv6-only networks can synthesize a route to the official server.
 * The DNS-failure-only fallback follows syncplay-mobile's ServerEndpoint implementation.
 * Never redirect a custom server, or retry another public room/port behind the user's back.
 */
internal fun resolveSyncplayAddresses(
  host: String,
  resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
): List<InetAddress> =
  try {
    resolver(host).ifEmpty { throw UnknownHostException(host) }.distinct()
  } catch (error: UnknownHostException) {
    if (!host.equals("syncplay.pl", ignoreCase = true)) throw error
    listOf(InetAddress.getByAddress(byteArrayOf(151.toByte(), 80, 32, 178.toByte())))
  }

enum class SyncplayConnectionFailure {
  NAME_LOOKUP,
  REFUSED,
  TIMEOUT,
  UNREACHABLE,
  HANDSHAKE_TIMEOUT,
  CLOSED,
  PROTOCOL,
  NETWORK,
  ;

  companion object {
    fun from(error: Throwable): SyncplayConnectionFailure =
      when (error) {
        is UnknownHostException -> NAME_LOOKUP
        is SocketTimeoutException -> TIMEOUT
        is NoRouteToHostException -> UNREACHABLE
        is ConnectException -> REFUSED
        is EOFException -> CLOSED
        is ProtocolException -> PROTOCOL
        else -> NETWORK
      }
  }
}
