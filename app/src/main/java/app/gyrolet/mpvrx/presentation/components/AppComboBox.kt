/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.components

import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox as MaterialExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion

/*
 * The app's one combobox: a control that shows the current choice and opens a list to pick another.
 * Every dropdown selector is built from these pieces so they look and behave the same everywhere:
 * - [AppComboBox]: the standard read-only field with a label and a turning arrow.
 * - [AppComboBoxChip]: the compact pill for toolbars, headers and dense rows.
 * - [AppComboBoxMenu] and [AppComboBoxOption]: the shared popup and rows, also used directly by
 *   editable fields that offer suggestions.
 * The chosen option is always highlighted and marked with a check.
 */

object AppComboBoxDefaults {
  val MenuShape: Shape = RoundedCornerShape(24.dp)
  val ConnectedMenuShape: Shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomEnd = 24.dp, bottomStart = 24.dp)
  val ConnectedMenuAboveShape: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 4.dp, bottomStart = 4.dp)
  val MenuMaxHeight: Dp = 300.dp
  val ChipMinHeight: Dp = 40.dp

  @Composable
  fun fieldShape(expanded: Boolean, menuAbove: Boolean = false): Shape {
    val joiningCorner by animateDpAsState(
      targetValue = if (expanded) 4.dp else 24.dp,
      animationSpec = if (AppMotion.shouldReduceMotion()) snap() else tween(200),
      label = "combobox_joining_corner",
    )
    return RoundedCornerShape(
      topStart = if (menuAbove) joiningCorner else 24.dp,
      topEnd = if (menuAbove) joiningCorner else 24.dp,
      bottomEnd = if (menuAbove) 24.dp else joiningCorner,
      bottomStart = if (menuAbove) 24.dp else joiningCorner,
    )
  }
}

@Stable
private class AppComboBoxConnection {
  var anchorBounds by mutableStateOf(Rect.Zero)
  var menuTop by mutableStateOf<Float?>(null)
  val menuAbove: Boolean
    get() = !anchorBounds.isEmpty && menuTop?.let { it < anchorBounds.top } == true
}

private val LocalAppComboBoxConnection = staticCompositionLocalOf<AppComboBoxConnection?> { null }

private fun LayoutCoordinates.inputBoundsOnScreen(view: View): Rect {
  val location = IntArray(2)
  view.getLocationOnScreen(location)
  return Rect(positionInRoot() + Offset(location[0].toFloat(), location[1].toFloat()), size.toSize())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppComboBoxAnchor(
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ExposedDropdownMenuBoxScope.() -> Unit,
) {
  val connection = remember { AppComboBoxConnection() }
  val view = LocalView.current
  val shape = AppComboBoxDefaults.fieldShape(expanded, connection.menuAbove)
  CompositionLocalProvider(
    LocalAppInputShape provides shape,
    LocalAppComboBoxConnection provides connection,
  ) {
    MaterialExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = onExpandedChange,
      modifier = modifier.onGloballyPositioned { connection.anchorBounds = it.inputBoundsOnScreen(view) },
      content = content,
    )
  }
}

/** Standard combobox: a read-only outlined field showing the selection, opening the shared list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> AppComboBox(
  options: List<T>,
  selected: T?,
  onSelect: (T) -> Unit,
  optionLabel: @Composable (T) -> String,
  modifier: Modifier = Modifier,
  label: String? = null,
  // Shown instead of the selected option's label, e.g. "Automatic" when nothing is selected.
  valueText: String? = null,
  placeholder: String? = null,
  enabled: Boolean = true,
  isError: Boolean = false,
  leadingIcon: (@Composable () -> Unit)? = null,
  supportingText: (@Composable () -> Unit)? = null,
  optionSupportingText: (@Composable (T) -> String?)? = null,
  optionLeadingIcon: (@Composable (T) -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  val open = expanded && enabled
  val displayText = valueText ?: if (selected != null) optionLabel(selected) else ""

  AppComboBoxAnchor(
    expanded = open,
    onExpandedChange = { if (enabled) expanded = it },
    modifier = modifier,
  ) {
    OutlinedTextField(
      value = displayText,
      onValueChange = {},
      readOnly = true,
      enabled = enabled,
      singleLine = true,
      isError = isError,
      label = if (label != null) { { Text(label) } } else null,
      placeholder = if (placeholder != null) { { Text(placeholder) } } else null,
      leadingIcon = leadingIcon,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
      supportingText = supportingText,
      modifier =
        Modifier
          .fillMaxWidth()
          .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
    )
    AppComboBoxMenu(expanded = open, onDismissRequest = { expanded = false }) {
      AppComboBoxOptions(
        options = options,
        selected = selected,
        optionLabel = optionLabel,
        optionSupportingText = optionSupportingText,
        optionLeadingIcon = optionLeadingIcon,
        onSelect = { option ->
          expanded = false
          onSelect(option)
        },
      )
    }
  }
}

/**
 * Compact combobox for toolbars, section headers and dense rows: a pill with the selection and the
 * same turning arrow, opening the same list as [AppComboBox]. [footer] adds rows after the options,
 * such as a link to related settings; it receives a callback that closes the list.
 */
@Composable
fun <T> AppComboBoxChip(
  options: List<T>,
  selected: T?,
  onSelect: (T) -> Unit,
  optionLabel: @Composable (T) -> String,
  modifier: Modifier = Modifier,
  valueText: String? = null,
  enabled: Boolean = true,
  contentDescription: String? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  optionSupportingText: (@Composable (T) -> String?)? = null,
  optionLeadingIcon: (@Composable (T) -> Unit)? = null,
  footer: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  val open = expanded && enabled

  Box(modifier = modifier) {
    AppComboBoxChipAnchor(
      text = valueText ?: if (selected != null) optionLabel(selected) else "",
      expanded = open,
      enabled = enabled,
      contentDescription = contentDescription,
      leadingIcon = leadingIcon,
      onClick = { expanded = true },
    )
    AppDropdownMenu(
      expanded = open,
      onDismissRequest = { expanded = false },
      modifier = Modifier.heightIn(max = AppComboBoxDefaults.MenuMaxHeight),
      shape = AppComboBoxDefaults.MenuShape,
    ) {
      AppComboBoxOptions(
        options = options,
        selected = selected,
        optionLabel = optionLabel,
        optionSupportingText = optionSupportingText,
        optionLeadingIcon = optionLeadingIcon,
        onSelect = { option ->
          expanded = false
          onSelect(option)
        },
      )
      footer?.invoke(this) { expanded = false }
    }
  }
}

/** The pill used by [AppComboBoxChip], for selectors that build their own list. */
@Composable
fun AppComboBoxChipAnchor(
  text: String,
  expanded: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  contentDescription: String? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    onClick = onClick,
    enabled = enabled,
    modifier =
      modifier
        .heightIn(min = AppComboBoxDefaults.ChipMinHeight)
        .tvFocusHighlight(CircleShape, enabled = enabled, focusedScale = 1.04f, clipToShape = false)
        .then(
          if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
          } else {
            Modifier
          },
        ),
    shape = CircleShape,
    color = if (expanded) colors.secondaryContainer else colors.surfaceContainerHigh,
    contentColor =
      when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        expanded -> colors.onSecondaryContainer
        else -> colors.onSurface
      },
    border = BorderStroke(1.dp, if (expanded) colors.primary else colors.outlineVariant),
  ) {
    Row(
      modifier = if (text.isEmpty()) {
        Modifier.padding(horizontal = 10.dp)
      } else {
        Modifier.padding(start = if (leadingIcon != null) 10.dp else 16.dp, end = 6.dp)
      },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (leadingIcon != null) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) { leadingIcon() }
      }
      // Empty text gives an icon-only pill.
      if (text.isNotEmpty()) {
        Text(
          text = text,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
      }
      ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
    }
  }
}

/** The shared popup for an [ExposedDropdownMenuBox]; editable fields with suggestions use it too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdownMenuBoxScope.AppComboBoxMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val connection = LocalAppComboBoxConnection.current
  // The exposed menu honors PrimaryEditable/SecondaryEditable anchors, keeping the IME and
  // text field focused while typing. A regular focusable DropdownMenu steals that focus.
  ExposedDropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    modifier = modifier.heightIn(max = AppComboBoxDefaults.MenuMaxHeight),
    matchAnchorWidth = true,
    shape = if (connection?.menuAbove == true) AppComboBoxDefaults.ConnectedMenuAboveShape else AppComboBoxDefaults.ConnectedMenuShape,
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    content = {
      val popupView = LocalView.current
      Column(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned {
          val location = IntArray(2)
          popupView.rootView.getLocationOnScreen(location)
          connection?.menuTop = location[1].toFloat()
        },
        content = content,
      )
    },
  )
}

/** One choice in a combobox list: connected tonal row, the selected one highlighted and checked. */
@Composable
fun AppComboBoxOption(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  index: Int? = null,
  itemCount: Int? = null,
  supportingText: String? = null,
  enabled: Boolean = true,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingContent: (@Composable () -> Unit)? = null,
) {
  val checkMark: (@Composable () -> Unit)? =
    if (selected) {
      { Icon(Icons.RoundedFilled.Check, contentDescription = null, modifier = Modifier.size(20.dp)) }
    } else {
      null
    }
  AppDropdownMenuItem(
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = text,
          fontWeight = if (selected) FontWeight.SemiBold else null,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (supportingText != null) {
          Text(
            text = supportingText,
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    },
    onClick = onClick,
    modifier = modifier.fillMaxWidth().semantics { this.selected = selected },
    enabled = enabled,
    selected = selected,
    index = index,
    itemCount = itemCount,
    leadingIcon = leadingIcon,
    trailingIcon = trailingContent ?: checkMark,
  )
}

@Composable
private fun <T> AppComboBoxOptions(
  options: List<T>,
  selected: T?,
  optionLabel: @Composable (T) -> String,
  optionSupportingText: (@Composable (T) -> String?)?,
  optionLeadingIcon: (@Composable (T) -> Unit)?,
  onSelect: (T) -> Unit,
) {
  options.forEachIndexed { index, option ->
    AppComboBoxOption(
      text = optionLabel(option),
      selected = option == selected,
      index = index,
      itemCount = options.size,
      supportingText = optionSupportingText?.invoke(option),
      leadingIcon = if (optionLeadingIcon != null) { { optionLeadingIcon(option) } } else null,
      onClick = { onSelect(option) },
    )
  }
}
