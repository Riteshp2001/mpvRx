/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.preview

import app.gyrolet.mpvrx.network.SharedHttpClient
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.FilterInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Short-lived loopback relay: FastThumbnails has no HTTP-header API. Also rewrites HLS children. */
internal class SeekPreviewHttpProxy(private val headers: Map<String, String>) : NanoHTTPD("127.0.0.1", 0) {
  private val routes = ConcurrentHashMap<String, HttpUrl>()
  private val exactPaths = ConcurrentHashMap<String, HttpUrl>()
  private val calls = ConcurrentHashMap.newKeySet<Call>()
  @Volatile private var closed = false
  private val client = SharedHttpClient.derive {
    connectTimeout(5, TimeUnit.SECONDS)
    readTimeout(5, TimeUnit.SECONDS)
    callTimeout(8, TimeUnit.SECONDS)
  }

  init { start(5_000, true) }

  @Synchronized
  fun url(source: String): String {
    check(!closed)
    val upstream = source.toHttpUrl()
    val origin = upstream.newBuilder().encodedPath("/").query(null).fragment(null).build()
    val token = routes.entries.firstOrNull { it.value == origin }?.key ?: run {
      require(routes.size < 64) { "Preview manifest has too many hosts" }
      UUID.randomUUID().toString().also { routes[it] = origin }
    }
    val route = "/$token${upstream.encodedPath}"
    if (exactPaths.size < 4096) exactPaths[java.net.URLDecoder.decode(route, "UTF-8")] = upstream
    return "http://127.0.0.1:$listeningPort$route" +
      (upstream.encodedQuery?.let { "?$it" } ?: "")
  }

  override fun serve(session: IHTTPSession): Response {
    if (closed) return failure()
    val token = session.uri.removePrefix("/").substringBefore('/')
    val origin = routes[token] ?: return failure()
    val relative = session.uri.removePrefix("/$token").ifBlank { "/" }
    val source = (exactPaths[session.uri]?.newBuilder() ?: origin.newBuilder().encodedPath(relative))
      .encodedQuery(session.queryParameterString).build()
    if (session.method != Method.GET && session.method != Method.HEAD) return failure()
    return try {
      val builder = Request.Builder().url(source)
      headers.forEach { (key, value) -> builder.header(key, value) }
      builder.header("Accept-Encoding", "identity")
      session.headers["range"]?.let { builder.header("Range", it) }
      if (session.method == Method.HEAD) builder.head()
      val call = client.newCall(builder.build())
      calls.add(call)
      if (closed) call.cancel()
      val upstream = try { call.execute() } catch (error: Exception) { calls.remove(call); throw error }
      if (!upstream.isSuccessful) {
        upstream.close()
        calls.remove(call)
        return failure()
      }
      val type = upstream.header("Content-Type").orEmpty().substringBefore(';')
      val hls = type.contains("mpegurl", true) || upstream.request.url.encodedPath.endsWith(".m3u8", true)
      val dash = type.contains("dash+xml", true) || upstream.request.url.encodedPath.endsWith(".mpd", true)
      if ((hls || dash) && session.method != Method.HEAD) {
        try {
          val bytes = upstream.body.byteStream().readBytesLimited(2 * 1024 * 1024)
          val base = upstream.request.url
          val manifest = if (dash) rewriteDash(bytes, base) else bytes.toString(Charsets.UTF_8).lineSequence().joinToString("\n") { line ->
            if (line.isNotBlank() && !line.startsWith('#')) {
              url(requireNotNull(base.resolve(line.trim())).toString())
            } else {
              Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                "URI=\"${url(requireNotNull(base.resolve(match.groupValues[1])).toString())}\""
              }
            }
          }
          newFixedLengthResponse(Response.Status.OK, if (dash) "application/dash+xml" else "application/vnd.apple.mpegurl", manifest)
        } finally {
          upstream.close()
          calls.remove(call)
        }
      } else {
        val stream = object : FilterInputStream(upstream.body.byteStream()) {
          override fun close() {
            try { super.close() } finally { upstream.close(); calls.remove(call) }
          }
        }
        val status = if (upstream.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val length = upstream.body.contentLength()
        val response = if (length >= 0) {
          newFixedLengthResponse(status, type.ifBlank { "application/octet-stream" }, stream, length)
        } else newChunkedResponse(status, type.ifBlank { "application/octet-stream" }, stream)
        listOf("Content-Range", "Accept-Ranges").forEach { key -> upstream.header(key)?.let { response.addHeader(key, it) } }
        response.addHeader("Connection", "close")
        response
      }
    } catch (_: Exception) { failure() }
  }

  private fun rewriteDash(bytes: ByteArray, manifestUrl: HttpUrl): String {
    val xml = bytes.toString(Charsets.UTF_8)
    require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      // Android parser implementations differ; the lexical guard above is mandatory too.
      runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
      runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
      runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
      isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    fun rewrite(element: org.w3c.dom.Element, parentBase: HttpUrl) {
      val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? org.w3c.dom.Element }
      val bases = children.filter { it.localName == "BaseURL" }
      val base = bases.firstOrNull()?.textContent?.trim()?.let { parentBase.resolve(it) } ?: parentBase
      // One deterministic origin per representation; alternatives are redundant for a preview.
      bases.drop(1).forEach(element::removeChild)
      bases.firstOrNull()?.textContent = url(base.toString())
      listOf("media", "initialization", "sourceURL", "index").forEach { attribute ->
        element.getAttribute(attribute).takeIf { isPreviewHttpUrl(it) || it.startsWith('/') }?.let { value ->
          base.resolve(value)?.let { element.setAttribute(attribute, url(it.toString())) }
        }
      }
      children.filter { it.localName != "BaseURL" }.forEach { rewrite(it, base) }
    }
    rewrite(document.documentElement, manifestUrl)
    // Preserve relative inherited SegmentTemplate URLs: a Representation may override BaseURL.
    val root = document.documentElement
    if ((0 until root.childNodes.length).none { (root.childNodes.item(it) as? org.w3c.dom.Element)?.localName == "BaseURL" }) {
      val base = document.createElementNS(root.namespaceURI, "BaseURL")
      base.textContent = url(requireNotNull(manifestUrl.resolve(".")).toString())
      root.insertBefore(base, root.firstChild)
    }
    val writer = java.io.StringWriter()
    javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
      javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(writer),
    )
    return writer.toString()
  }

  @Synchronized
  fun close() {
    closed = true
    calls.forEach(Call::cancel)
    calls.clear()
    routes.clear()
    exactPaths.clear()
    stop()
  }

  private fun failure(): Response = newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Preview unavailable")
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
  val out = java.io.ByteArrayOutputStream()
  val buffer = ByteArray(8192)
  while (true) {
    val count = read(buffer)
    if (count < 0) break
    require(out.size() + count <= limit)
    out.write(buffer, 0, count)
  }
  return out.toByteArray()
}
