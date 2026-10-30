package app.gyrolet.mpvrx.domain.seekpreview

import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as HttpResponse
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class SeekPreviewHttpBridge(
  source: String,
  private val headers: Map<String, String>,
) : NanoHTTPD("127.0.0.1", 0), AutoCloseable {
  private val origin = source.toHttpUrl()
  private val token = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
  private val closed = AtomicBoolean(false)
  private val calls = ConcurrentHashMap.newKeySet<Call>()
  private val client = OkHttpClient.Builder()
    .connectTimeout(8L, TimeUnit.SECONDS)
    .readTimeout(8L, TimeUnit.SECONDS)
    .callTimeout(15L, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

  val url: String get() = "http://127.0.0.1:$listeningPort/$token"

  init {
    start(SOCKET_READ_TIMEOUT, true)
  }

  override fun serve(session: IHTTPSession): Response {
    if (closed.get() || session.uri != "/$token") return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
    if (session.method != Method.GET && session.method != Method.HEAD) {
      return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
    }
    return try {
      var destination = origin
      var upstream: HttpResponse? = null
      var activeCall: Call? = null
      for (redirect in 0..5) {
        check(!closed.get())
        val request = Request.Builder().url(destination)
        if (sameOrigin(destination, origin)) headers.forEach { (name, value) -> request.header(name, value) }
        request.header("Accept-Encoding", "identity")
        session.headers["range"]?.let { request.header("Range", it) }
        if (session.method == Method.HEAD) request.head() else request.get()
        val call = client.newCall(request.build())
        calls.add(call)
        if (closed.get()) call.cancel()
        val response = try {
          call.execute()
        } catch (error: Exception) {
          calls.remove(call)
          throw error
        }
        if (response.code in REDIRECT_CODES) {
          val next = response.header("Location")?.let(destination::resolve)
          response.close()
          calls.remove(call)
          require(next != null && redirect < 5)
          destination = next
        } else {
          upstream = response
          activeCall = call
          break
        }
      }
      val response = requireNotNull(upstream)
      val call = requireNotNull(activeCall)
      val status = Response.Status.lookup(response.code) ?: BAD_GATEWAY
      val type = response.header("Content-Type") ?: "application/octet-stream"
      val length = response.header("Content-Length")?.toLongOrNull() ?: response.body.contentLength()
      val result = if (session.method == Method.HEAD) {
        response.close()
        calls.remove(call)
        object : Response(status, type, ByteArrayInputStream(ByteArray(0)), length.coerceAtLeast(0L)) {}
      } else {
        val stream = object : FilterInputStream(response.body.byteStream()) {
          override fun close() {
            try {
              super.close()
            } finally {
              response.close()
              calls.remove(call)
            }
          }
        }
        if (length >= 0L) newFixedLengthResponse(status, type, stream, length)
        else newChunkedResponse(status, type, stream)
      }
      response.header("Content-Range")?.let { result.addHeader("Content-Range", it) }
      response.header("Accept-Ranges")?.let { result.addHeader("Accept-Ranges", it) }
      result.addHeader("Connection", "close")
      result
    } catch (_: Exception) {
      newFixedLengthResponse(BAD_GATEWAY, MIME_PLAINTEXT, "")
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    cancelReads()
    calls.clear()
    stop()
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdown()
  }

  fun cancelReads() {
    calls.forEach(Call::cancel)
  }

  private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
    first.scheme == second.scheme && first.host == second.host && first.port == second.port

  private companion object {
    // NanoHTTPD 2.3.1 does not define a BAD_GATEWAY enum value.
    val BAD_GATEWAY: Response.IStatus = object : Response.IStatus {
      override fun getRequestStatus(): Int = 502
      override fun getDescription(): String = "502 Bad Gateway"
    }
    val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
  }
}