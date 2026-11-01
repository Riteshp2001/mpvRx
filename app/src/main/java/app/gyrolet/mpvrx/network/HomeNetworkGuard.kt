/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Locale

/**
 * "Home network only": nothing in this app process may connect outside the local network.
 *
 * Installed as the process-wide [ProxySelector], which every Java HTTP stack consults per
 * connection (OkHttp, HttpURLConnection, and Google libraries running in-process such as ML Kit's
 * telemetry). Outside hosts are routed to a dead loopback port, so the connection fails on the
 * phone itself: no packet reaches the router, and the host name is judged as text, so no DNS
 * query for it is sent either. Native code (mpv, torrents) is gated where it opens a URL.
 */
object HomeNetworkGuard {
  const val PREF_KEY = "home_network_only"
  private const val TAG = "HomeNetworkGuard"
  private const val MAX_LOG = 100

  @Volatile
  var enabled: Boolean = true
    private set

  private val blocked = ArrayDeque<String>()
  private var preferences: SharedPreferences? = null

  // Kept strongly: SharedPreferences holds listeners weakly.
  private val listener =
    SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
      if (key == PREF_KEY || key == null) enabled = prefs.getBoolean(PREF_KEY, true)
    }

  /** Call first thing in Application.attachBaseContext, before any HTTP client is built. */
  fun install(context: Context) {
    val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    preferences = prefs
    enabled = prefs.getBoolean(PREF_KEY, true)
    prefs.registerOnSharedPreferenceChangeListener(listener)
    val current = ProxySelector.getDefault()
    if (current !is Selector) ProxySelector.setDefault(Selector(current))
  }

  /** True if [host] is on the home network (or the guard is off). */
  fun allows(host: String?): Boolean = !enabled || isHomeHost(host)

  /** For URLs handed to native code (mpv, torrents): local files and home hosts only. */
  fun allowsUrl(url: String): Boolean {
    if (!enabled) return true
    val trimmed = url.trim()
    val scheme = trimmed.substringBefore(':', "").lowercase(Locale.ROOT)
    return when (scheme) {
      "", "file", "content", "fd", "android.resource", "memory", "av", "lavf", "edl" -> true
      "magnet" -> false
      else -> isHomeHost(runCatching { URI(trimmed).host }.getOrNull())
    }
  }

  /** Recently blocked hosts, newest first (this session only). */
  fun blockedHosts(): List<String> = synchronized(blocked) { blocked.toList().asReversed().distinct() }

  fun blockedCount(): Int = synchronized(blocked) { blocked.size }

  internal fun record(host: String?) {
    val name = host ?: "?"
    Log.i(TAG, "Blocked connection to $name (home network only)")
    synchronized(blocked) {
      blocked.addLast(name)
      while (blocked.size > MAX_LOG) blocked.removeFirst()
    }
  }

  fun blockedMessage(host: String?): String =
    "Blocked: $host is outside your home network. (Settings → Advanced → Home network only)"

  /**
   * Home addresses: loopback, private IPv4 (10/8, 172.16/12, 192.168/16), link-local, private
   * IPv6 (fc00::/7, fe80::/10), and local names (no dot, .local, .lan, .home, .internal,
   * .home.arpa). Everything else, including public IPs and CGNAT/VPN ranges, is outside.
   */
  fun isHomeHost(host: String?): Boolean {
    val h = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
    if (h.isEmpty()) return false
    if (h == "localhost") return true
    val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(h)
    if (ipv4 != null) {
      val (a, b) = ipv4.destructured.toList().map { it.toInt() }
      if (ipv4.destructured.toList().any { it.toInt() > 255 }) return false
      return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
    if (h.contains(':')) {
      return h == "::1" || h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb") ||
        h.startsWith("fc") || h.startsWith("fd")
    }
    if (!h.contains('.')) return true
    return listOf(".local", ".lan", ".home", ".internal", ".home.arpa").any { h.endsWith(it) }
  }

  private val deadEnd = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), 9))

  private class Selector(
    private val delegate: ProxySelector?,
  ) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
      val host = uri?.host
      if (allows(host)) return delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)
      record(host)
      return listOf(deadEnd)
    }

    override fun connectFailed(
      uri: URI?,
      sa: SocketAddress?,
      ioe: IOException?,
    ) {
      delegate?.connectFailed(uri, sa, ioe)
    }
  }
}
