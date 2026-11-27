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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvContextMenu
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.tv.tvFocusMemory
import org.koin.compose.koinInject

@Composable
fun NetworkFolderCard(
  file: NetworkFile,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  isGridMode: Boolean = false,
  selectionNeighbors: MediaSelectionNeighbors = MediaSelectionNeighbors(),
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2
  val cardShape = rememberMediaSelectionShape(isSelected, isGridMode, selectionNeighbors)
  val selectionColor = animatedSelectionColor(isSelected)

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusMemory("network:${file.path}")
        .tvFocusHighlight(cardShape, focusedScale = 1.03f)
        .clip(cardShape)
        .semantics { selected = isSelected }
        .tvContextMenu(onLongClick)
        .mediaCardClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = cardShape,
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    // Size the tile from its thumbnail and title rather than a fixed footer.
    Box(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier = Modifier.matchParentSize().clip(cardShape).background(selectionColor),
      )

      if (isGridMode) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 4.dp, vertical = 6.dp),
          horizontalAlignment = if (centerGridTitles) Alignment.CenterHorizontally else Alignment.Start,
        ) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .aspectRatio(20f / 17f),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              Icons.RoundedFilled.Folder,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_folder),
              modifier = Modifier.matchParentSize(),
              tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
            )
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            file.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centerGridTitles) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      } else {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier =
              Modifier
                .size(72.dp),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              Icons.RoundedFilled.Folder,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_folder),
              modifier = Modifier.matchParentSize(),
              tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(
            modifier = Modifier.weight(1f),
          ) {
            Text(
              file.name,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = maxLines,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
      SelectionIndicator(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
  }
}
