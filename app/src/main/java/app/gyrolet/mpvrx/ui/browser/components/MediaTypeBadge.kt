/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppShapeScale

/**
 * Which kind of media a thumbnail holds, for the corner label.
 *
 * The labels are deliberately neither localised nor theme-aware: the badge sits on a photo rather
 * than on app chrome, so it has to stay legible whatever the artwork or active theme underneath.
 */
enum class NetworkMediaType(
  val label: String,
) {
  VIDEO("Video"),
  AUDIO("Audio"),
  IMAGE("Img"),
}

@Composable
fun MediaTypeBadge(
  type: NetworkMediaType,
  modifier: Modifier = Modifier,
) {
  Text(
    text = type.label,
    style = MaterialTheme.typography.labelSmall,
    color = BADGE_TEXT,
    modifier =
      modifier
        .background(BADGE_BACKGROUND, AppShapeScale.extraSmall)
        .padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

private val BADGE_BACKGROUND = Color.White.copy(alpha = 0.75f)
private val BADGE_TEXT = Color(0xFF303030)
