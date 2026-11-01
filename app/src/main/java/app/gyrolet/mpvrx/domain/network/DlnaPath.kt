/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.network

import java.util.Base64

/** Persistent, credential-free references to opaque ContentDirectory object IDs. */
object DlnaPath {
  private const val ROOT_ID = "0"

  fun child(parent: NetworkPath, id: String, kind: Char, duplicate: Int = 0): NetworkPath {
    require(kind in "doavi" && id.isNotEmpty()) { "Invalid DLNA object" }
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))
    return parent.child("$kind-$encoded" + if (duplicate > 0) "~$duplicate" else "")
  }

  fun objectId(path: NetworkPath): String {
    if (path.isRoot) return ROOT_ID
    val segment = path.segments.last()
    require(segment.length > 2 && segment[0] in "doavi" && segment[1] == '-') { "Invalid DLNA path" }
    return String(Base64.getUrlDecoder().decode(segment.substring(2).substringBefore('~')), Charsets.UTF_8)
      .also { require(it.isNotEmpty()) { "Invalid DLNA object ID" } }
  }

  fun isOrderedContainer(path: NetworkPath): Boolean = path.segments.lastOrNull()?.startsWith("o-") == true
  fun isAudio(path: NetworkPath): Boolean = path.segments.lastOrNull()?.startsWith("a-") == true
}
