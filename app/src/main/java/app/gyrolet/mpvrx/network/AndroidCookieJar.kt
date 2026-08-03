/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {
  private val manager: CookieManager? =
    try {
      CookieManager.getInstance()
    } catch (_: Exception) {
      null
    }

  override fun saveFromResponse(
    url: HttpUrl,
    cookies: List<Cookie>,
  ) {
    val urlString = url.toString()
    cookies.forEach { cookie ->
      manager?.setCookie(urlString, cookie.toString())
    }
    manager?.flush()
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val cookies = manager?.getCookie(url.toString()).orEmpty()
    if (cookies.isBlank()) return emptyList()
    return cookies
      .split(";")
      .mapNotNull { Cookie.parse(url, it.trim()) }
  }
}
