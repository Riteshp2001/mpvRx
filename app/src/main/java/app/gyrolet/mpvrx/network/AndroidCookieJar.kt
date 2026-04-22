package app.gyrolet.mpvrx.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {
  private val manager: CookieManager? = try {
    CookieManager.getInstance()
  } catch (_: Exception) {
    null
  }

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
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
