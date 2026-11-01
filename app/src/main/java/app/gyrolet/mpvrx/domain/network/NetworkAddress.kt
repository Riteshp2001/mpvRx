/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.network

import java.net.URI

/** An address supplied by the user, not a credential store. */
data class NetworkAddress(
  val host: String,
  val protocol: NetworkProtocol,
  val port: Int?,
  val path: String?,
  val useHttps: Boolean,
) {
  companion object {
    fun parse(raw: String, protocol: NetworkProtocol, useHttps: Boolean = false): NetworkAddress {
      val input = raw.trim()
      require(input.isNotEmpty() && input.none { it.isISOControl() }) { "Invalid server address" }
      val scheme = when (protocol) {
        NetworkProtocol.SMB -> "smb"
        NetworkProtocol.FTP -> "ftp"
        NetworkProtocol.SFTP -> "sftp"
        NetworkProtocol.WEBDAV, NetworkProtocol.DLNA -> if (useHttps) "https" else "http"
      }
      val url = when {
        input.startsWith("\\\\") -> "smb://${input.drop(2).replace('\\', '/')}"
        "://" in input -> input
        input.count { it == ':' } > 1 && !input.startsWith('[') && '/' !in input -> "$scheme://[$input]"
        else -> "$scheme://$input"
      }
      val uri = URI(url.replace(" ", "%20"))
      require(uri.rawUserInfo == null) { "Enter credentials in their own fields" }
      require(uri.rawQuery == null && uri.rawFragment == null) { "Invalid server address" }
      val detectedProtocol = when (uri.scheme.lowercase()) {
        "smb" -> NetworkProtocol.SMB
        "ftp" -> NetworkProtocol.FTP
        "sftp" -> NetworkProtocol.SFTP
        "http", "https" -> if (protocol == NetworkProtocol.DLNA) NetworkProtocol.DLNA else NetworkProtocol.WEBDAV
        "webdav", "webdavs", "dav", "davs" -> NetworkProtocol.WEBDAV
        else -> throw IllegalArgumentException("Unsupported network protocol")
      }
      // Some NAS hostnames contain underscores, which java.net.URI does not consider DNS hosts.
      val nasAuthority = if (uri.host == null) {
        Regex("([A-Za-z0-9_.-]+)(?::([0-9]+))?").matchEntire(uri.rawAuthority.orEmpty())
      } else null
      val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: nasAuthority?.groupValues?.get(1)
      require(!host.isNullOrBlank()) { "Invalid server address" }
      val fallbackPort = nasAuthority?.groups?.get(2)?.value
      val port = if (fallbackPort != null) {
        fallbackPort.toIntOrNull() ?: throw IllegalArgumentException("Invalid server port")
      } else uri.port
      require(port == -1 || port in 1..65535) { "Invalid server port" }
      val path = uri.path?.takeIf { it.isNotEmpty() }?.let { NetworkPath.from(it).value }
      return NetworkAddress(
        host, detectedProtocol, port.takeIf { it != -1 }, path,
        uri.scheme.lowercase() in setOf("https", "webdavs", "davs"),
      )
    }
  }
}

/** Also repairs old saved entries that contain a URL/UNC path in the host field. */
fun NetworkConnection.normalizedAddress(): NetworkConnection {
  val address = NetworkAddress.parse(host, protocol, useHttps)
  return copy(
    host = address.host,
    protocol = address.protocol,
    port = address.port ?: when {
      address.protocol != protocol ||
        (address.useHttps != useHttps && port == (if (useHttps) 443 else protocol.defaultPort)) ->
        if (address.useHttps) 443 else address.protocol.defaultPort
      else -> port
    },
    path = address.path ?: path,
    useHttps = address.useHttps,
  )
}

/** An optional SMB root: blank or / browses the server, /Share[/Folder] pins a folder. */
class SmbSharePath(rawPath: String) {
  private val configured = NetworkPath.from(rawPath.trim().replace('\\', '/'))
  val shareName: String? = configured.segments.firstOrNull()
  val isServerRoot: Boolean = configured.isRoot
  val directory: NetworkPath = NetworkPath.from(configured.segments.drop(1).joinToString("/"))

  data class Target(val shareName: String, val path: NetworkPath)

  /** Resolve each request independently so concurrent streams can use different shares. */
  fun resolve(path: NetworkPath): Target {
    val fullPath = NetworkPath.from("${configured.value}/${path.relative}")
    val share = fullPath.segments.firstOrNull()
      ?: throw IllegalArgumentException("Select an SMB shared folder first")
    return Target(share, NetworkPath.from(fullPath.segments.drop(1).joinToString("/")))
  }

  fun fromServerPath(path: NetworkPath): NetworkPath {
    require(configured.segments.indices.all { index ->
      path.segments.getOrNull(index)?.equals(configured.segments[index], ignoreCase = true) == true
    }) {
      "SMB path is outside the configured folder"
    }
    return NetworkPath.from(path.segments.drop(configured.segments.size).joinToString("/"))
  }
}

data class SmbUsername(val username: String, val domain: String?) {
  companion object {
    fun parse(raw: String): SmbUsername {
      val separator = raw.indexOf('\\')
      return if (separator > 0) {
        require(separator < raw.lastIndex && '\\' !in raw.substring(separator + 1)) { "Invalid SMB username" }
        SmbUsername(raw.substring(separator + 1), raw.substring(0, separator))
      } else {
        SmbUsername(raw, null) // Preserve UPNs such as user@example.com.
      }
    }
  }
}
