/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.cards

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Size and date formatting shared by the two network cards.
 *
 * They render as adjacent sections of one screen, so their numbers have to read the same way — as
 * separate copies the logic had already drifted ("2.4 MB" next to "2 MB").
 *
 * Deliberately not `MediaUtils.formatFileSize`: that one groups thousands and keeps one decimal,
 * which would change how every video card in the app reads.
 *
 * Named apart from the file-private `formatDate` helpers in `VideoCard`/`FolderCard` on purpose —
 * those take epoch **seconds** while the network cards carry **milliseconds**.
 */
internal fun formatCardFileSize(bytes: Long): String =
  when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
  }

/** Hoisted: a card formats a date on every recomposition, and building a formatter per row is waste. */
private val CARD_DATE_FORMATTER: DateTimeFormatter =
  DateTimeFormatter.ofPattern("MMM dd").withZone(ZoneId.systemDefault())

/** [timestamp] is milliseconds since the epoch, as carried by `NetworkFile.lastModified`. */
internal fun formatCardDate(timestamp: Long): String = CARD_DATE_FORMATTER.format(Instant.ofEpochMilli(timestamp))
