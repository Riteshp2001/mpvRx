/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.AppTheme
import app.gyrolet.mpvrx.ui.theme.CustomThemeDefinition

/**
 * A horizontal scrollable theme picker with preview cards.
 * Displays all available themes with visual previews.
 */
@Composable
fun ThemePicker(
  currentTheme: AppTheme,
  customThemes: List<CustomThemeDefinition>,
  selectedCustomThemeName: String,
  isDarkMode: Boolean,
  amoledMode: Boolean,
  onThemeSelected: (AppTheme, Offset) -> Unit,
  onAddCustomTheme: () -> Unit,
  onCustomThemeSelected: (CustomThemeDefinition, Offset) -> Unit,
  onEditCustomTheme: (String) -> Unit,
  onDeleteCustomTheme: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val initialFocusRequester =
    rememberTvInitialFocusRequester(requestKey = selectedCustomThemeName.ifBlank { currentTheme.name })

  LaunchedEffect(currentTheme, selectedCustomThemeName, customThemes) {
    val customIndex = customThemes.indexOfFirst { it.name == selectedCustomThemeName }
    val index =
      if (customIndex >= 0) {
        AppTheme.entries.size + 1 + customIndex
      } else {
        AppTheme.entries.indexOf(currentTheme)
      }
    if (index >= 0) {
      listState.animateScrollToItem(maxOf(0, index - 1))
    }
  }

  Column(
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = stringResource(R.string.pref_appearance_theme_picker_label),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )

    LazyRow(
      modifier = Modifier.fillMaxWidth().tvFocusGroup(),
      state = listState,
      contentPadding = PaddingValues(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      items(AppTheme.entries, key = { it.name }) { theme ->
        val isSelected = selectedCustomThemeName.isBlank() && theme == currentTheme
        ThemePreviewCard(
          label = stringResource(theme.titleRes),
          colorScheme = when {
            isDarkMode && amoledMode -> theme.getAmoledColorScheme()
            isDarkMode -> theme.getDarkColorScheme()
            else -> theme.getLightColorScheme()
          },
          isSelected = isSelected,
          onClick = { position -> onThemeSelected(theme, position) },
          modifier =
            if (isSelected) {
              Modifier.tvInitialFocus(initialFocusRequester)
            } else {
              Modifier
            },
        )
      }
      item(key = "add_custom_theme") {
        AddCustomThemeCard(onClick = onAddCustomTheme)
      }
      items(customThemes, key = { "custom_${it.name}" }) { theme ->
        val isSelected = theme.name == selectedCustomThemeName
        ThemePreviewCard(
          label = theme.name,
          colorScheme = if (isDarkMode) theme.darkColorScheme(amoledMode) else theme.lightColorScheme(),
          isSelected = isSelected,
          onClick = { position -> onCustomThemeSelected(theme, position) },
          modifier = if (isSelected) Modifier.tvInitialFocus(initialFocusRequester) else Modifier,
          actionOverlay = {
            Column(
              modifier = Modifier.tvFocusGroup(),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              ThemeCardAction(
                icon = Icons.RoundedFilled.Edit,
                contentDescription = stringResource(R.string.pref_appearance_custom_theme_edit),
                onClick = { onEditCustomTheme(theme.name) },
              )
              ThemeCardAction(
                icon = Icons.RoundedFilled.Delete,
                contentDescription = stringResource(R.string.pref_appearance_custom_theme_clear),
                onClick = { onDeleteCustomTheme(theme.name) },
              )
            }
          },
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
  }
}

@Composable
private fun AddCustomThemeCard(onClick: () -> Unit) {
  Column(
    modifier =
      Modifier
        .width(100.dp)
        .tvFocusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.05f)
        .clickable(onClick = onClick),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier =
        Modifier
          .size(width = 90.dp, height = 140.dp)
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Add,
        contentDescription = null,
        modifier = Modifier.size(34.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = stringResource(R.string.pref_appearance_custom_theme_title),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
      maxLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun ThemeCardAction(
  icon: AppIcon,
  contentDescription: String,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .size(30.dp)
        .tvFocusHighlight(CircleShape, focusedScale = 1.12f)
        .background(Color.Black.copy(alpha = 0.68f), CircleShape)
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      modifier = Modifier.size(18.dp),
      tint = Color.White,
    )
  }
}
