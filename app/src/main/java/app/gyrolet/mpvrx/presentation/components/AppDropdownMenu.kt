/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import androidx.compose.ui.window.PopupProperties

@Composable
fun AppDropdownMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  offset: DpOffset = DpOffset(0.dp, 0.dp),
  scrollState: ScrollState = rememberScrollState(),
  properties: PopupProperties = PopupProperties(focusable = true),
  shape: Shape = AppComboBoxDefaults.MenuShape,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  tonalElevation: Dp = MenuDefaults.TonalElevation,
  shadowElevation: Dp = MenuDefaults.ShadowElevation,
  border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
  content: @Composable ColumnScope.() -> Unit,
) {
  androidx.compose.material3.DropdownMenu(
    expanded = expanded, onDismissRequest = onDismissRequest,
    modifier = modifier.heightIn(max = AppComboBoxDefaults.MenuMaxHeight).widthIn(min = 200.dp, max = 320.dp),
    offset = offset, scrollState = scrollState, properties = properties, shape = shape,
    containerColor = containerColor, tonalElevation = tonalElevation, shadowElevation = shadowElevation,
    border = border, content = content,
  )
}

/**
 * Shared expressive menu option. Tonal rows are inset from the outer rounded
 * popup so actions have their own visible but unclipped interaction surfaces.
 */
@Composable
fun AppDropdownMenuItem(
  text: @Composable () -> Unit,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  selected: Boolean = false,
  index: Int? = null,
  itemCount: Int? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
) {
  // A popup has one rounded outer shell. Its inner rows use small shared corners;
  // when the item positions are known the exposed group corners stay expressive.
  val rowShape = if (index != null && itemCount != null && itemCount > 0 && index in 0 until itemCount) {
    rememberConnectedShape(index, itemCount, ConnectedLayout.Vertical, tokens = AppConnectedControlShapeTokens)
  } else {
    RoundedCornerShape(6.dp)
  }
  val rowColor =
    if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHighest
  val contentColor =
    if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurface

  DropdownMenuItem(
    text = text,
    onClick = onClick,
    modifier =
      modifier
        .padding(horizontal = 6.dp, vertical = 2.dp)
        .clip(rowShape)
        .background(rowColor),
    enabled = enabled,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    contentPadding = PaddingValues(horizontal = 14.dp),
    colors = MenuDefaults.itemColors(
      textColor = contentColor,
      leadingIconColor = contentColor,
      trailingIconColor = contentColor,
    ),
  )
}
