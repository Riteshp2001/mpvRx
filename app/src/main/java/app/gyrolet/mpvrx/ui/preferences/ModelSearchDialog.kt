/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import app.gyrolet.mpvrx.ui.theme.animatedConnectedContainerColor
import app.gyrolet.mpvrx.ui.theme.appPressFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.repository.ai.AiModelInfo
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape

@Composable
fun ModelSearchDialog(
  models: List<AiModelInfo>,
  selectedModelId: String,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var searchQuery by rememberSaveable { mutableStateOf("") }

  val sortedFiltered =
    remember(models, searchQuery) {
      models
        .filter {
          searchQuery.isBlank() ||
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.id.contains(searchQuery, ignoreCase = true)
        }.sortedWith(compareByDescending<AiModelInfo> { it.isFree }.thenBy { it.displayName })
    }

  AppPickerSheet(
    onDismissRequest = onDismiss,
    title = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_select_model),
    scrollContent = false,
    actions = {
      TextButton(onClick = onDismiss) {
        Text(
          androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
        )
      }
    },
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      PlayerSheetSearchField(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        placeholder = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_search_models),
      )
      if (sortedFiltered.isNotEmpty()) {
        Text(
          text = pluralStringResource(
            app.gyrolet.mpvrx.R.plurals.ai_model_result_count,
            sortedFiltered.size,
            sortedFiltered.size,
          ),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
          modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        )
      }
      if (sortedFiltered.isEmpty()) {
        Text(
          text = androidx.compose.ui.res.stringResource(
            if (searchQuery.isNotBlank()) {
              app.gyrolet.mpvrx.R.string.ai_model_search_empty
            } else {
              app.gyrolet.mpvrx.R.string.ai_model_none_available
            },
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.outline,
          modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
          verticalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
        ) {
          itemsIndexed(sortedFiltered, key = { _, model -> model.id }) { index, model ->
            ModelSearchItem(
              model = model,
              isSelected = model.id == selectedModelId,
              index = index,
              itemCount = sortedFiltered.size,
              onClick = {
                onSelect(model.id)
                onDismiss()
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ModelSearchItem(
  model: AiModelInfo,
  isSelected: Boolean,
  index: Int,
  itemCount: Int,
  onClick: () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  Surface(
    onClick = onClick,
    shape = rememberConnectedShape(
      index, itemCount, ConnectedLayout.Vertical, tokens = AppConnectedControlShapeTokens,
      pressed = pressed, selected = isSelected,
    ),
    color = animatedConnectedContainerColor(
      selected = isSelected, pressed = pressed, containerColor = MaterialTheme.colorScheme.surface,
      selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
    ),
    interactionSource = interactionSource,
    modifier = Modifier.fillMaxWidth().appPressFeedback(interactionSource, pressedScale = 0.985f),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
      RadioButton(
        selected = isSelected,
        onClick = null,
      )
      Spacer(modifier = Modifier.width(4.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = model.displayName,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = model.id,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (model.isFree) {
        Spacer(modifier = Modifier.width(6.dp))
        FreeTag()
      }
    }
  }
}

@Composable
fun FreeTag() {
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = MaterialTheme.colorScheme.primary,
  ) {
    Text(
      text =
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.pref_player_orientation_free),
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      color = MaterialTheme.colorScheme.onPrimary,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
    )
  }
}
