/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's SpotifyToken
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.artwork

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.preferences.AudioPreferences
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The bearer token behind Spotify's own web player, minted from the listener's `sp_dc` session
 * cookie. There is no public API for a track's Canvas, so this walks the door the web player
 * uses: open.spotify.com is loaded in an offscreen WebView with the cookie applied, and the
 * token the page mints for itself is read by hooking fetch/XHR before its bundle runs. Signing
 * the token request directly yields a token the canvas endpoints refuse.
 */
internal class SpotifyToken(
  context: Context,
  client: OkHttpClient,
  private val preferences: AudioPreferences,
) {
  private val appContext = context.applicationContext
  private val http = client.newBuilder()
    .cookieJar(CookieJar.NO_COOKIES)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val harvestMutex = Mutex()
  private val clientTokenMutex = Mutex()

  private data class SessionInfo(val clientVersion: String, val deviceId: String)
  private data class HarvestedToken(val token: String, val expiresAt: Long, val clientId: String?)

  @Volatile private var cookieHash = 0
  @Volatile private var accessToken: String? = null
  @Volatile private var accessTokenExpiresAt = 0L
  @Volatile private var clientId: String? = null
  @Volatile private var retryHarvestAfter = 0L
  @Volatile private var session: SessionInfo? = null
  @Volatile private var clientToken: String? = null
  @Volatile private var clientTokenExpiresAt = 0L

  val hasCookie: Boolean
    get() = preferences.spotifySpDcCookie.get().isNotBlank()

  /** The current bearer token, or null with no cookie or after a failed harvest. */
  suspend fun accessToken(): String? {
    val cookie = preferences.spotifySpDcCookie.get().trim()
    if (cookie.isEmpty()) return null
    resetIfCookieChanged(cookie)
    fresh()?.let { return it }

    return harvestMutex.withLock {
      fresh()?.let { return@withLock it }
      // Each harvest loads the whole web player; a failure must not repeat on every track.
      if (System.currentTimeMillis() < retryHarvestAfter) return@withLock null
      val harvested = withContext(Dispatchers.Main) { harvestViaWebView(cookie) }
      if (harvested == null) {
        Log.w(TAG, "Spotify token harvest failed or timed out")
        retryHarvestAfter = System.currentTimeMillis() + HARVEST_RETRY_MS
        return@withLock null
      }
      accessToken = harvested.token
      accessTokenExpiresAt = harvested.expiresAt
      harvested.clientId?.let { clientId = it }
      harvested.token
    }
  }

  /** Forgets the token after the canvas endpoints reject it, so the next lookup mints anew. */
  fun invalidate() {
    accessToken = null
    accessTokenExpiresAt = 0L
    clientToken = null
    clientTokenExpiresAt = 0L
  }

  private fun fresh(): String? =
    accessToken?.takeIf { System.currentTimeMillis() < accessTokenExpiresAt - 30_000L }

  private fun resetIfCookieChanged(cookie: String) {
    val hash = cookie.hashCode()
    if (hash == cookieHash) return
    cookieHash = hash
    invalidate()
    clientId = null
    retryHarvestAfter = 0L
  }

  @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
  private suspend fun harvestViaWebView(cookie: String): HarvestedToken? {
    val deferred = CompletableDeferred<HarvestedToken?>()
    var webView: WebView? = null
    return try {
      val cookieManager = CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setCookie("https://open.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
        setCookie("https://accounts.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
        flush()
      }
      // The player reuses a token cached in web storage and then never requests one the hook
      // could see. mpvRx has no other WebView, so clearing storage affects only this page.
      runCatching { WebStorage.getInstance().deleteAllData() }
      webView = WebView(appContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = WEB_USER_AGENT
        cookieManager.setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(TokenBridge(deferred), BRIDGE_NAME)
        webChromeClient = object : WebChromeClient() {
          override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
        }
        webViewClient = object : WebViewClient() {
          override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view.evaluateJavascript(HOOK_SCRIPT, null)
          }

          override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            // Re-assert in case the player navigated client-side after the first injection.
            view.evaluateJavascript(HOOK_SCRIPT, null)
          }
        }
        loadUrl("https://open.spotify.com/")
      }
      withTimeoutOrNull(HARVEST_TIMEOUT_MS) { deferred.await() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Devices without a usable WebView provider land here.
      Log.w(TAG, "Spotify token harvest unavailable: ${e.message}")
      null
    } finally {
      runCatching {
        webView?.removeJavascriptInterface(BRIDGE_NAME)
        webView?.stopLoading()
        webView?.destroy()
      }
    }
  }

  /** Receives raw `/api/token` response bodies from the hooked page. */
  private inner class TokenBridge(private val deferred: CompletableDeferred<HarvestedToken?>) {
    @JavascriptInterface
    fun onTokenPayload(payload: String?) {
      if (payload.isNullOrBlank() || deferred.isCompleted) return
      runCatching {
        val root = json.parseToJsonElement(payload) as? JsonObject ?: return
        val token = root.text("accessToken")
        // An anonymous token is minted before the cookie takes effect; it cannot read canvases.
        val anonymous = root.text("isAnonymous")?.toBooleanStrictOrNull() ?: false
        if (token.isNullOrBlank() || anonymous) return
        val expiresAt = root.text("accessTokenExpirationTimestampMs")?.toLongOrNull()
          ?.takeIf { it > System.currentTimeMillis() }
          ?: (System.currentTimeMillis() + DEFAULT_TOKEN_LIFETIME_MS)
        deferred.complete(HarvestedToken(token, expiresAt, root.text("clientId")))
      }
    }
  }

  /**
   * The second header api.spotify.com and spclient now demand beside the bearer; without it
   * they answer 429. Best effort: callers send the bearer alone when this is unavailable.
   */
  suspend fun clientToken(): String? = clientTokenMutex.withLock {
    val now = System.currentTimeMillis()
    clientToken?.takeIf { now < clientTokenExpiresAt - 30_000L }?.let { return@withLock it }
    val id = clientId ?: return@withLock null
    val session = session() ?: return@withLock null
    val payload = buildJsonObject {
      putJsonObject("client_data") {
        put("client_version", session.clientVersion)
        put("client_id", id)
        putJsonObject("js_sdk_data") {
          put("device_brand", "unknown")
          put("device_model", "unknown")
          put("os", "android")
          put("os_version", Build.VERSION.RELEASE.orEmpty())
          put("device_id", session.deviceId)
          put("device_type", "smartphone")
        }
      }
    }
    // The ByteArray overload keeps the bare "application/json" type; clienttoken rejects a charset.
    val request = Request.Builder()
      .url("https://clienttoken.spotify.com/v1/clienttoken")
      .post(payload.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
      .header("Accept", "application/json")
      .header("User-Agent", WEB_USER_AGENT)
      .build()
    val body = runCatching {
      http.newCall(request).awaitResponse().use { response ->
        if (response.isSuccessful) response.body.string() else null
      }
    }.getOrNull() ?: return@withLock null
    val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return@withLock null
    if (root.text("response_type") != "RESPONSE_GRANTED_TOKEN_RESPONSE") return@withLock null
    val granted = root["granted_token"] as? JsonObject ?: return@withLock null
    val token = granted.text("token") ?: return@withLock null
    val ttlSeconds = granted.text("expires_after_seconds")?.toLongOrNull() ?: 3600L
    clientToken = token
    clientTokenExpiresAt = now + ttlSeconds * 1000L
    token
  }

  /** Client version and device id from the plain web player page; stable for the process. */
  private suspend fun session(): SessionInfo? {
    session?.let { return it }
    val request = Request.Builder().url("https://open.spotify.com").header("User-Agent", WEB_USER_AGENT).build()
    val (html, deviceId) = runCatching {
      http.newCall(request).awaitResponse().use { response ->
        val spT = response.headers("Set-Cookie").firstNotNullOfOrNull { header ->
          SP_T_COOKIE.find(header)?.groupValues?.get(1)
        }
        response.body.string() to spT
      }
    }.getOrNull() ?: return null
    val config = APP_SERVER_CONFIG.find(html)?.groupValues?.get(1) ?: return null
    val clientVersion = runCatching {
      val configJson = String(Base64.getDecoder().decode(config), Charsets.UTF_8)
      (json.parseToJsonElement(configJson) as? JsonObject)?.text("clientVersion")
    }.getOrNull() ?: return null
    return SessionInfo(clientVersion, deviceId ?: UUID.randomUUID().toString()).also { session = it }
  }

  private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

  companion object {
    private const val TAG = "SpotifyToken"
    private const val BRIDGE_NAME = "MpvRxSpotifyTokenBridge"
    private const val HARVEST_TIMEOUT_MS = 20_000L
    private const val HARVEST_RETRY_MS = 10 * 60_000L
    private const val DEFAULT_TOKEN_LIFETIME_MS = 3_600_000L
    const val WEB_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val SP_T_COOKIE = Regex("""sp_t=([^;]+)""")
    private val APP_SERVER_CONFIG = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")

    private val HOOK_SCRIPT = """
      (function () {
        if (window.__mpvrxTokenHook) return;
        window.__mpvrxTokenHook = true;
        var report = function (body) {
          try { $BRIDGE_NAME.onTokenPayload(body); } catch (e) {}
        };
        var isToken = function (u) {
          try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; }
        };
        var origFetch = window.fetch;
        if (origFetch) {
          window.fetch = function (input, init) {
            var url = (input && input.url) ? input.url : input;
            var result = origFetch.apply(this, arguments);
            if (isToken(url)) {
              try {
                result.then(function (res) {
                  res.clone().text().then(report).catch(function () {});
                }).catch(function () {});
              } catch (e) {}
            }
            return result;
          };
        }
        var origOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function (method, url) {
          this.__mpvrxUrl = url;
          return origOpen.apply(this, arguments);
        };
        var origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send = function () {
          var xhr = this;
          try {
            xhr.addEventListener('load', function () {
              if (isToken(xhr.__mpvrxUrl)) {
                try { report(xhr.responseText); } catch (e) {}
              }
            });
          } catch (e) {}
          return origSend.apply(this, arguments);
        };
      })();
    """.trimIndent()
  }
}
