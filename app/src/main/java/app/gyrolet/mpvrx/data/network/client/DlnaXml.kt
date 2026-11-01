/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class DlnaDevice(
  val name: String,
  val serviceType: String,
  val controlUrl: HttpUrl,
  val baseUrl: HttpUrl,
)

internal data class DlnaEntry(
  val id: String,
  val title: String,
  val kind: Char,
  val mimeType: String?,
  val size: Long,
  val resource: HttpUrl?,
)

internal data class DlnaBrowsePage(val entries: List<DlnaEntry>, val returned: Int, val total: Int?)

/** XML supplied by a LAN device is untrusted; do not resolve entities or read unbounded bodies. */
internal object DlnaXml {
  private const val MAX_XML_BYTES = 8 * 1024 * 1024

  fun readBounded(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      if (output.size() + count > MAX_XML_BYTES) throw IOException("DLNA response is too large")
      output.write(buffer, 0, count)
    }
    return output.toString("UTF-8")
  }

  private fun parse(xml: String): Element {
    if (xml.contains("<!DOCTYPE", ignoreCase = true)) throw IOException("Invalid DLNA XML")
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeature("http://xml.org/sax/features/external-general-entities", false)
      setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    return factory.newDocumentBuilder().parse(xml.byteInputStream()).documentElement
  }

  private fun Element.descendants(name: String): List<Element> {
    val nodes = getElementsByTagNameNS("*", name)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
  }

  private fun Element.text(name: String): String? = descendants(name).firstOrNull()?.textContent?.trim()

  private fun Element.children(): List<Element> = buildList {
    var node: Node? = firstChild
    while (node != null) {
      if (node is Element) add(node)
      node = node.nextSibling
    }
  }

  fun description(xml: String, location: HttpUrl): DlnaDevice {
    val root = parse(xml)
    val base = root.text("URLBase")?.let(location::resolve) ?: location
    val service = root.descendants("service").firstOrNull {
      it.text("serviceType")?.matches(Regex("urn:schemas-upnp-org:service:ContentDirectory:[0-9]+", RegexOption.IGNORE_CASE)) == true
    } ?: throw IOException("Server has no DLNA ContentDirectory service")
    val type = service.text("serviceType") ?: throw IOException("DLNA service type is missing")
    val control = service.text("controlURL")?.let(base::resolve)
      ?: throw IOException("DLNA control URL is invalid")
    if (control.username.isNotEmpty() || control.password.isNotEmpty()) throw IOException("Invalid DLNA control URL")
    return DlnaDevice(root.text("friendlyName").orEmpty(), type, control, base)
  }

  fun browseRequest(serviceType: String, objectId: String, metadata: Boolean, start: Int, count: Int): String {
    val id = objectId.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;").replace("'", "&apos;")
    val flag = if (metadata) "BrowseMetadata" else "BrowseDirectChildren"
    return """<?xml version="1.0" encoding="utf-8"?>
      <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>
      <u:Browse xmlns:u="$serviceType"><ObjectID>$id</ObjectID><BrowseFlag>$flag</BrowseFlag><Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria></u:Browse>
      </s:Body></s:Envelope>""".trimIndent()
  }

  fun browseResponse(xml: String, base: HttpUrl): DlnaBrowsePage {
    val root = parse(xml)
    val result = root.descendants("Result").firstOrNull() ?: throw IOException("Invalid DLNA browse response")
    val returnedValue = root.text("NumberReturned")?.toIntOrNull()?.takeIf { it >= 0 }
    val total = root.text("TotalMatches")?.toIntOrNull()?.takeIf { it >= 0 }
    val content = result.textContent.trim()
    val didl = result.children().firstOrNull() ?: content.takeIf { it.isNotEmpty() }?.let(::parse)
    if (didl == null) return DlnaBrowsePage(emptyList(), returnedValue ?: 0, total)
    val entries = didl.children().mapNotNull { element ->
      val elementName = element.localName ?: element.tagName
      if (elementName != "container" && elementName != "item") return@mapNotNull null
      val id = element.getAttribute("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
      val title = element.text("title").orEmpty().ifBlank { id }
      val type = element.text("class").orEmpty().lowercase()
      if (elementName == "container") {
        val ordered = type.contains("playlistcontainer") || type.contains("musicalbum")
        return@mapNotNull DlnaEntry(id, title, if (ordered) 'o' else 'd', null, 0, null)
      }
      val classKind = when {
        type.contains("audioitem") -> 'a'
        type.contains("videoitem") -> 'v'
        type.contains("imageitem") -> 'i'
        else -> null
      }
      val resources = element.children().filter { (it.localName ?: it.tagName) == "res" }
        .mapNotNull resource@ { res ->
          val protocol = res.getAttribute("protocolInfo")
          if (protocol.isNotBlank() && !protocol.startsWith("http-get:", true)) return@resource null
          val url = base.resolve(res.textContent.trim()) ?: return@resource null
          if (url.username.isNotEmpty() || url.password.isNotEmpty()) return@resource null
          val mime = protocol.split(':').getOrNull(2)?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "*" }
          Triple(res, url, mime)
        }
      val expectedPrefix = when (classKind) { 'a' -> "audio/"; 'v' -> "video/"; 'i' -> "image/"; else -> null }
      val matching = resources.filter { expectedPrefix != null && it.third?.startsWith(expectedPrefix) == true }
      val generic = resources.filter { it.third == null || it.third == "application/octet-stream" }
      val candidates = matching.ifEmpty { generic.ifEmpty { resources } }
      // Prefer an original over a server-side transcoded resource when both are available.
      val chosen = candidates.firstOrNull { it.first.getAttribute("protocolInfo").contains("DLNA.ORG_CI=0", true) }
        ?: candidates.firstOrNull() ?: return@mapNotNull null
      val advertisedMime = chosen.third
      val kind = classKind ?: when {
        advertisedMime?.startsWith("audio/") == true -> 'a'
        advertisedMime?.startsWith("video/") == true -> 'v'
        advertisedMime?.startsWith("image/") == true -> 'i'
        else -> return@mapNotNull null
      }
      val mimePrefix = when (kind) { 'a' -> "audio/"; 'v' -> "video/"; else -> "image/" }
      val mime = advertisedMime?.takeIf { it.startsWith(mimePrefix) }
        ?: when (kind) { 'a' -> "audio/*"; 'v' -> "video/*"; else -> null }
      DlnaEntry(id, title, kind, mime, chosen.first.getAttribute("size").toLongOrNull()?.takeIf { it >= 0 } ?: -1L, chosen.second)
    }
    return DlnaBrowsePage(entries, returnedValue ?: entries.size, total)
  }
}
