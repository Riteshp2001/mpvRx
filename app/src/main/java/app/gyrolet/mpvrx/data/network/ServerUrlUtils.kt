/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network

import java.net.URI

object ServerUrlUtils {
  private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

  /**
   * Determines if a host is a local network address, private IP range, Tailscale/CGNAT,
   * loopback, or single-label LAN hostname.
   */
  fun isLocalOrPrivateHost(host: String): Boolean {
    val cleanHost =
      host
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .lowercase()
    if (cleanHost.isBlank()) return false

    if (cleanHost == "localhost" || cleanHost == "127.0.0.1" || cleanHost == "::1") {
      return true
    }

    if (cleanHost.endsWith(".local") ||
      cleanHost.endsWith(".lan") ||
      cleanHost.endsWith(".home") ||
      cleanHost.endsWith(".internal")
    ) {
      return true
    }

    // Single-label hostnames without a dot (e.g. "homeserver", "truenas", "raspberrypi")
    if (!cleanHost.contains(".")) {
      return true
    }

    // Check IPv4
    val match = IPV4_REGEX.matchEntire(cleanHost)
    if (match != null) {
      val (p1Str, p2Str, p3Str, p4Str) = match.destructured
      val p1 = p1Str.toIntOrNull() ?: return false
      val p2 = p2Str.toIntOrNull() ?: return false
      val p3 = p3Str.toIntOrNull() ?: return false
      val p4 = p4Str.toIntOrNull() ?: return false

      if (p1 !in 0..255 || p2 !in 0..255 || p3 !in 0..255 || p4 !in 0..255) {
        return false
      }

      // 10.0.0.0/8
      if (p1 == 10) return true
      // 172.16.0.0/12 (172.16.x.x - 172.31.x.x)
      if (p1 == 172 && p2 in 16..31) return true
      // 192.168.0.0/16
      if (p1 == 192 && p2 == 168) return true
      // 127.0.0.0/8
      if (p1 == 127) return true
      // 169.254.0.0/16 (Link-local)
      if (p1 == 169 && p2 == 254) return true
      // 100.64.0.0/10 (CGNAT / Tailscale)
      if (p1 == 100 && p2 in 64..127) return true

      // Any other raw IPv4 address (e.g. direct public IP without domain)
      return true
    }

    // IPv6 link-local or unique-local
    if (cleanHost.startsWith("fe80:") || cleanHost.startsWith("fc00:") || cleanHost.startsWith("fd00:")) {
      return true
    }

    return false
  }

  /**
   * Generates prioritized URL candidates for connecting to a server:
   * - Explicit http:// or https:// schemes are honored directly.
   * - Local IPs / LAN hosts prioritize http:// first, then https://.
   * - Hosted domains / FQDNs prioritize https:// first, then http://.
   * - If defaultPort is provided and port is missing, candidates with defaultPort are generated.
   */
  fun generateCandidateUrls(
    rawUrl: String,
    defaultPort: Int? = null,
  ): List<String> {
    val trimmed = rawUrl.trim().removeSuffix("/")
    if (trimmed.isBlank()) return emptyList()

    val hasExplicitHttp = trimmed.startsWith("http://", ignoreCase = true)
    val hasExplicitHttps = trimmed.startsWith("https://", ignoreCase = true)

    if (hasExplicitHttp || hasExplicitHttps) {
      val parsedUri = runCatching { URI(trimmed) }.getOrNull()
      val hasPort = parsedUri?.port != null && parsedUri.port != -1
      return if (!hasPort && defaultPort != null && defaultPort > 0) {
        listOf(trimmed, "$trimmed:$defaultPort")
      } else {
        listOf(trimmed)
      }
    }

    val clean = trimmed.removePrefix("//")
    val fakeUri = runCatching { URI("http://$clean") }.getOrNull()
    val host =
      fakeUri?.host?.takeIf { it.isNotBlank() }
        ?: clean
          .substringBefore("/")
          .substringBeforeLast(":")
          .removePrefix("[")
          .removeSuffix("]")
    val port = fakeUri?.port ?: -1
    val hasPort = port != -1
    val path = fakeUri?.rawPath?.takeIf { it.isNotBlank() && it != "/" } ?: ""

    val isLocalOrIp = isLocalOrPrivateHost(host)
    val hostWithPort = if (hasPort) clean else null

    return if (isLocalOrIp) {
      // Local IP / LAN host: HTTP first, then HTTPS
      val candidates = mutableListOf<String>()
      if (hostWithPort != null) {
        candidates.add("http://$hostWithPort")
        candidates.add("https://$hostWithPort")
      } else if (defaultPort != null && defaultPort > 0) {
        val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        candidates.add("http://$hostPart:$defaultPort$path")
        candidates.add("http://$clean")
        candidates.add("https://$hostPart:$defaultPort$path")
        candidates.add("https://$clean")
      } else {
        candidates.add("http://$clean")
        candidates.add("https://$clean")
      }
      candidates.distinct()
    } else {
      // Hosted domain / FQDN: HTTPS first, then HTTP
      val candidates = mutableListOf<String>()
      if (hostWithPort != null) {
        candidates.add("https://$hostWithPort")
        candidates.add("http://$hostWithPort")
      } else {
        candidates.add("https://$clean")
        if (defaultPort != null && defaultPort > 0) {
          val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
          candidates.add("https://$hostPart:$defaultPort$path")
          candidates.add("http://$clean")
          candidates.add("http://$hostPart:$defaultPort$path")
        } else {
          candidates.add("http://$clean")
        }
      }
      candidates.distinct()
    }
  }

  fun normalizeUrl(
    rawUrl: String,
    defaultPort: Int? = null,
  ): String = generateCandidateUrls(rawUrl, defaultPort).firstOrNull() ?: rawUrl.trim()

  /**
   * Returns a dynamic hint for the UI describing how the connection will be attempted.
   */
  fun getConnectionHint(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) {
      return "HTTP will be tried first for local IPs, HTTPS for domains"
    }
    if (trimmed.startsWith("http://", ignoreCase = true)) {
      return "Using HTTP connection"
    }
    if (trimmed.startsWith("https://", ignoreCase = true)) {
      return "Using secure HTTPS connection"
    }
    val clean = trimmed.removePrefix("//")
    val fakeUri = runCatching { URI("http://$clean") }.getOrNull()
    val host =
      fakeUri?.host?.takeIf { it.isNotBlank() }
        ?: clean
          .substringBefore("/")
          .substringBeforeLast(":")
          .removePrefix("[")
          .removeSuffix("]")
    return if (isLocalOrPrivateHost(host)) {
      "Local address detected: HTTP will be tried first"
    } else {
      "Domain detected: HTTPS will be tried first"
    }
  }
}
