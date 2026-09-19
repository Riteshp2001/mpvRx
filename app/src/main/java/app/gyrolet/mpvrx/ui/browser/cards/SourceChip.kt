/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.ui.theme.AppShapeScale

/**
 * Colored label identifying where a video comes from, followed by its path on the card.
 *
 * Colors are fixed rather than theme-derived so the same source keeps the same color across the
 * app's many themes; white label text stays legible on all of them.
 */
@Composable
fun SourceChip(
  label: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelSmall,
    modifier =
      modifier
        .background(color, AppShapeScale.small)
        .padding(horizontal = 8.dp, vertical = 4.dp),
    // Chosen from the background rather than fixed to white: callers fall back to a light surface
    // colour when they have no source colour, where white text would be unreadable.
    color = if (color.luminance() > 0.5f) Color.Black else Color.White,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * A null [protocol] is a local file, unless [isNetwork] says the entry came from a share we can no
 * longer name — an entry whose connection row is gone must not be painted as local.
 */
fun sourceChipColor(
  protocol: NetworkProtocol?,
  isNetwork: Boolean = false,
): Color =
  when (protocol) {
    NetworkProtocol.WEBDAV -> Color(0xFF1E88E5)
    NetworkProtocol.SMB -> Color(0xFF8E24AA)
    NetworkProtocol.FTP -> Color(0xFFF4511E)
    NetworkProtocol.SFTP -> Color(0xFF00897B)
    null -> if (isNetwork) Color(0xFF607D8B) else Color(0xFF2E7D32)
  }
