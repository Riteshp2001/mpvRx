/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNetworkGuardTest {
  @Test
  fun homeAddressesAreAllowed() {
    listOf(
      "192.168.1.50", "10.0.0.5", "172.16.4.1", "172.31.255.255", "127.0.0.1", "localhost", "169.254.10.1",
      "::1", "[fe80::1]", "fd12:3456::1", "nas", "home-pc.local", "jellyfin.lan", "router.home.arpa",
    ).forEach { assertTrue(it, HomeNetworkGuard.isHomeHost(it)) }
  }

  @Test
  fun everythingElseIsOutside() {
    listOf(
      "8.8.8.8", "172.32.0.1", "100.64.0.1", "1.1.1.1", "api.github.com", "translate.googleapis.com",
      "firebaselogging.googleapis.com", "192.168.1.300", "2606:4700::1", "lan.evil.com", "", null,
    ).forEach { assertFalse(it.toString(), HomeNetworkGuard.isHomeHost(it)) }
  }

  @Test
  fun nativeUrlsAreCheckedByHost() {
    assertTrue(HomeNetworkGuard.allowsUrl("/storage/emulated/0/Movie.mkv"))
    assertTrue(HomeNetworkGuard.allowsUrl("content://media/external/video/media/5"))
    assertTrue(HomeNetworkGuard.allowsUrl("http://192.168.1.50:8096/Videos/abc/stream?static=true"))
    assertTrue(HomeNetworkGuard.allowsUrl("smb://192.168.1.50/movies/a.mkv"))
    assertFalse(HomeNetworkGuard.allowsUrl("https://www.youtube.com/watch?v=x"))
    assertFalse(HomeNetworkGuard.allowsUrl("magnet:?xt=urn:btih:abc"))
    assertFalse(HomeNetworkGuard.allowsUrl("http://203.0.113.5/live.m3u8"))
  }
}
