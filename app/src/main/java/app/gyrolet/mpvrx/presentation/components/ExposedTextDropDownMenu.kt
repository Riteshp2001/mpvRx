/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedTextDropDownMenu(
  selectedValue: String,
  options: ImmutableList<String>,
  label: String,
  onValueChangedEvent: (String) -> Unit,
  modifier: Modifier = Modifier,
  leadingIcon: (@Composable () -> Unit)? = null,
  enabled: Boolean = true,
) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { if (enabled) expanded = !expanded },
    modifier = modifier,
  ) {
    OutlinedTextField(
      readOnly = true,
      enabled = enabled,
      value = selectedValue,
      onValueChange = {},
      label = { Text(text = label) },
      trailingIcon = {
        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
      },
      leadingIcon = leadingIcon,
      colors = OutlinedTextFieldDefaults.colors(),
      modifier =
        Modifier
          .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
          .fillMaxWidth(),
    )

    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      modifier = Modifier.heightIn(max = 300.dp),
    ) {
      options.forEach { option: String ->
        DropdownMenuItem(
          text = { Text(text = option) },
          onClick = {
            expanded = false
            onValueChangedEvent(option)
          },
        )
      }
    }
  }
}
