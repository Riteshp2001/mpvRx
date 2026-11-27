/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.components.expressive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion

/**
 * Section header with optional leading icon, count badge, and expand toggle.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  leadingIcon: AppIcon? = null,
  count: Int? = null,
  isExpanded: Boolean = false,
  onClick: (() -> Unit)? = null,
) {
  val actionLabel = stringResource(if (isExpanded) R.string.generic_collapse else R.string.generic_expand)
  val iconRotation by animateFloatAsState(
    targetValue = if (isExpanded) 180f else 0f,
    animationSpec = if (AppMotion.playerReducedMotion()) snap() else AppMotion.Spatial.Expressive,
    label = "SectionHeaderIconRotation",
  )

  Row(
    modifier =
      modifier
        .then(
          if (onClick !=
            null
          ) {
            Modifier.clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
          } else {
            Modifier
          },
        ).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    leadingIcon?.let { icon ->
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 8.dp),
      )
    }

    Text(
      text = title,
      style = MaterialTheme.typography.labelLargeEmphasized,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f).semantics { heading() },
    )

    count?.let {
      Text(
        text = "$it",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }

    if (onClick != null) {
      Icon(
        imageVector = Icons.RoundedFilled.KeyboardArrowDown,
        contentDescription = null,
        modifier = Modifier.rotate(iconRotation),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
