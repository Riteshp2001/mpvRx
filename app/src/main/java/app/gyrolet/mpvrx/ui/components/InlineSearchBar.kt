/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp

/**
 * Material 3 Expressive search field used inline at the top of list screens.
 *
 * Built from [SearchBarDefaults.InputField] on a search-bar surface so every screen gets
 * search semantics, the IME Search action and Expressive styling while typing in place.
 */
@Composable
fun InlineSearchBar(
  query: String,
  onQueryChange: (String) -> Unit,
  onSearch: (String) -> Unit,
  modifier: Modifier = Modifier,
  inputFieldModifier: Modifier = Modifier,
  placeholder: (@Composable () -> Unit)? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  shape: Shape = SearchBarDefaults.inputFieldShape,
  /**
   * Overrides the solid default container color. The default keeps every existing call site on
   * the plain SearchBar styling; glass surfaces pass a translucent tint instead.
   */
  containerColor: Color? = null,
  tonalElevation: Dp = SearchBarDefaults.TonalElevation,
  shadowElevation: Dp = SearchBarDefaults.ShadowElevation,
  windowInsets: WindowInsets = SearchBarDefaults.windowInsets,
) {
  val keyboardController = LocalSoftwareKeyboardController.current
  val textFieldState = rememberTextFieldState(query)
  val searchBarState = rememberSearchBarState()
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  val currentOnQueryChange by rememberUpdatedState(onQueryChange)
  // Last text this field reported, so the caller echoing it back is not re-applied mid-typing.
  val lastReported = remember { arrayOf(query) }

  LaunchedEffect(textFieldState) {
    snapshotFlow { textFieldState.text.toString() }.collect { text ->
      if (text != lastReported[0]) {
        lastReported[0] = text
        currentOnQueryChange(text)
      }
    }
  }
  LaunchedEffect(query) {
    if (query != lastReported[0]) {
      lastReported[0] = query
      textFieldState.setTextAndPlaceCursorAtEnd(query)
    }
  }
  // There is no expanded results view; collapse after focus leaves so semantics stay accurate.
  LaunchedEffect(focused) {
    if (!focused && searchBarState.currentValue != SearchBarValue.Collapsed) searchBarState.snapTo(0f)
  }

  Surface(
    modifier = Modifier.windowInsetsPadding(windowInsets).then(modifier),
    shape = shape,
    color = containerColor ?: SearchBarDefaults.colors().containerColor,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation,
  ) {
    SearchBarDefaults.InputField(
      textFieldState = textFieldState,
      searchBarState = searchBarState,
      onSearch = { submitted ->
        keyboardController?.hide()
        onSearch(submitted)
      },
      modifier = inputFieldModifier.fillMaxWidth(),
      placeholder = placeholder,
      leadingIcon = leadingIcon,
      trailingIcon = trailingIcon,
      shape = shape,
      interactionSource = interactionSource,
    )
  }
}
