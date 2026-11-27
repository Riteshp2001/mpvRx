// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.tv.TvReorderButtons
import app.gyrolet.mpvrx.ui.tv.isTelevision
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem

internal data class ReorderableTabItem(
  val id: String,
  val title: String,
  val summary: String,
  val enabled: Boolean,
  val required: Boolean = false,
  @StringRes val titleRes: Int? = null,
)

/** REX-style animated drag-and-drop with the app's normal connected preference surfaces. */
@Composable
internal fun ReorderableTabPreferences(
  tabs: List<ReorderableTabItem>,
  onEnabledChange: (String, Boolean) -> Unit,
  onMove: (String, Int) -> Unit,
) {
  val isTelevision = isTelevision()
  ReorderableColumn(
    list = tabs,
    onSettle = { from, to ->
      tabs.getOrNull(from)?.let { item ->
        if (!item.required && from != to) onMove(item.id, to)
      }
    },
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) { index, tab, isDragging ->
    ReorderableItem {
      ExpressivePreferenceTile(
        index = index,
        itemCount = tabs.size,
        modifier =
          Modifier
            .then(tab.titleRes?.let { Modifier.settingsSearchTarget(it) } ?: Modifier)
            .graphicsLayer { alpha = if (isDragging) 0.95f else 1f },
      ) {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 72.dp)
              .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (isTelevision && !tab.required) {
            // A remote cannot drag: explicit steps, and the moved row keeps focus.
            TvReorderButtons(
              canMoveUp = index > 0,
              canMoveDown = index < tabs.lastIndex,
              onMoveUp = { onMove(tab.id, index - 1) },
              onMoveDown = { onMove(tab.id, index + 1) },
            )
          } else {
            Icon(
              imageVector = Icons.RoundedFilled.DragHandle,
              contentDescription = if (tab.required) "Fixed tab" else "Drag to reorder ${tab.title}",
              modifier = Modifier.size(32.dp).then(if (tab.required) Modifier else Modifier.draggableHandle()),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tab.title, style = MaterialTheme.typography.bodyLarge)
            if (tab.summary.isNotBlank()) {
              Text(
                tab.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          IconSwitch(
            checked = tab.enabled,
            onCheckedChange = if (tab.required) null else { value -> onEnabledChange(tab.id, value) },
          )
        }
      }
    }
  }
}
