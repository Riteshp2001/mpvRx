/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape

/**
 * A card container for grouping related preferences, mimicking modern Android settings UI.
 */
@Composable
fun PreferenceCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    shape = MaterialTheme.shapes.extraLargeIncreased,
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
    elevation =
      CardDefaults.cardElevation(
        defaultElevation = 0.dp,
      ),
  ) {
    Column(
      modifier = Modifier.padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      content()
    }
  }
}

/**
 * A Material 3 Expressive preference section made from individually connected tiles.
 *
 * Unlike [PreferenceCard], this does not draw one large container behind every setting. Each
 * child owns its complete row and uses [ExpressivePreferenceTile] or the connected parameters on
 * a preference component. This keeps selected states full-width while preserving a clear visual
 * relationship between adjacent settings.
 */
@Composable
fun ExpressivePreferenceGroup(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val reduceMotion = AppMotion.shouldReduceMotion()
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .animateContentSize(if (reduceMotion) snap() else AppMotion.IntSizeSpring)
        .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    content = content,
  )
}

/** A full-width tile whose outer and inner corners follow its position in a vertical group. */
@Composable
fun ExpressivePreferenceTile(
  index: Int,
  itemCount: Int,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val shape =
    rememberConnectedShape(
      index = index,
      itemCount = itemCount,
      layout = ConnectedLayout.Vertical,
    )
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = shape,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    content()
  }
}

/**
 * A divider to separate preferences within a card.
 */
@Composable
fun PreferenceDivider(modifier: Modifier = Modifier) {
  HorizontalDivider(
    modifier = modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
  )
}

/**
 * A section header for preferences, displayed outside cards.
 */
@Composable
fun PreferenceSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  topPadding: Dp = 30.dp,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(start = 20.dp, end = 20.dp, top = topPadding, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      modifier = Modifier.width(5.dp).height(30.dp),
      color = MaterialTheme.colorScheme.primary,
      shape = MaterialTheme.shapes.extraSmall,
      content = {},
    )
    Spacer(modifier = Modifier.width(12.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
