/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.animatedConnectedContainerColor
import app.gyrolet.mpvrx.ui.theme.appPressFeedback

/**
 * Connected settings row that opens another screen or runs an action, in the style of the main
 * Settings list: optional tonal icon tile, title, summary and a chevron. [shape] is the row's
 * position in its group (rememberConnectedShape). While [busy], a spinner replaces the chevron and
 * the row ignores clicks. [modifier] applies inside the tile's shape, above its color, so a
 * settings-search highlight on the row stays visible.
 */
@Composable
internal fun SettingsNavigationRow(
  title: String,
  shape: Shape,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  icon: AppIcon? = null,
  enabled: Boolean = true,
  busy: Boolean = false,
  showChevron: Boolean = true,
) {
  val colors = MaterialTheme.colorScheme
  val interactive = enabled && !busy
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val containerColor = animatedConnectedContainerColor(
    selected = false, pressed = pressed, enabled = interactive, containerColor = colors.surfaceContainerLow,
  )
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .tvFocusHighlight(shape, enabled = interactive, focusedScale = 1.01f)
        .appPressFeedback(interactionSource, enabled = interactive, pressedScale = 0.985f)
        .clip(shape)
        .background(containerColor)
        .then(modifier)
        .clickable(
          enabled = interactive,
          role = Role.Button,
          interactionSource = interactionSource,
          indication = androidx.compose.material3.ripple(),
          onClick = onClick,
        )
        .heightIn(min = 64.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
      ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
          Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
      }
      Spacer(modifier = Modifier.width(16.dp))
    }
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (summary != null) {
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          color = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.38f),
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    when {
      busy -> {
        Spacer(modifier = Modifier.width(12.dp))
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
      }
      showChevron -> {
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
          Icons.RoundedFilled.ChevronRight,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = colors.onSurfaceVariant,
        )
      }
    }
  }
}
