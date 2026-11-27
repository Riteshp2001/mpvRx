/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.network

import app.gyrolet.mpvrx.utils.storage.FileTypeUtils

internal val NETWORK_IMAGE_MIME_TYPES =
  setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
    "image/bmp",
    "image/heic",
    "image/heif",
    "image/avif",
  )

internal fun NetworkFile.isNetworkImageFile(): Boolean {
  if (isDirectory) return false
  val cleanName = name.substringBefore('?').substringBefore('#')
  val extension = cleanName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
  return mimeType in NETWORK_IMAGE_MIME_TYPES ||
    (mimeType.isNullOrEmpty() && extension in FileTypeUtils.IMAGE_EXTENSIONS)
}
