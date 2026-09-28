/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Derived from BitChord's SearchField, GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

/**
 * The pill a sheet puts above a long list: a magnifier at the head, a hint
 * that gives way to typed text, and a clear button once there is something to
 * clear.
 *
 * Filtering happens as the field is typed into, so the magnifier is the
 * affordance rather than the trigger — there is nothing for pressing it to do
 * that has not happened already.
 */
@Composable
fun PlayerSheetSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = stringResource(R.string.generic_search),
) {
  val focusManager = LocalFocusManager.current
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        // Fixed, so the row does not grow the moment it is typed into.
        .height(46.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
        .padding(start = 8.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.Search,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(32.dp).padding(6.dp),
    )
    Spacer(Modifier.width(4.dp))
    Box(Modifier.weight(1f)) {
      if (query.isEmpty()) {
        Text(
          text = placeholder,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle =
          MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
          ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (query.isNotEmpty()) {
      Icon(
        imageVector = Icons.RoundedFilled.Close,
        contentDescription = stringResource(R.string.generic_clear),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
          Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable {
              onQueryChange("")
              focusManager.clearFocus()
            }
            .padding(5.dp),
      )
    }
  }
}
