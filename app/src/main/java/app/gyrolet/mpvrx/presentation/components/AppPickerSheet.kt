/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.LocalMotionPolicy
import app.gyrolet.mpvrx.ui.theme.MotionPolicy
import app.gyrolet.mpvrx.ui.tv.TvSafeArea

/**
 * The sheet the app's pickers sit in: a heading, an optional path and warning,
 * a scrolling body, and the actions pinned under it.
 *
 * Storage browsers use [fixedHeight] to fill the responsive viewport and keep navigation and
 * footer actions stationary as paths change. Short pickers wrap their content.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppPickerSheet(
  onDismissRequest: () -> Unit,
  title: String = "",
  titleContent: (@Composable () -> Unit)? = null,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  warning: String? = null,
  scrollContent: Boolean = true,
  headerActions: @Composable RowScope.() -> Unit = {},
  actions: @Composable RowScope.() -> Unit = {},
  usePlayerSheet: Boolean = false,
  fixedHeight: Boolean = false,
  embeddedTvPicker: Boolean = false,
  content: @Composable () -> Unit,
) {
  val reducedMotion = AppMotion.playerReducedMotion()

  val sheetContent: @Composable () -> Unit = {
    CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = reducedMotion)) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .then(if (embeddedTvPicker || fixedHeight) Modifier.fillMaxHeight() else Modifier)
            .padding(horizontal = 20.dp)
            .padding(bottom = 4.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            if (titleContent != null) {
              titleContent()
            } else if (title.isNotBlank()) {
              Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }

            if (subtitle != null) {
              Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
              )
            }

            if (warning != null) {
              Text(
                text = warning,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
              )
            }
          }
          Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = headerActions,
          )
        }

        Spacer(Modifier.height(8.dp))

        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f, fill = fixedHeight)
              .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        ) {
          content()
        }

        Spacer(Modifier.height(4.dp))

        FlowRow(
          modifier = Modifier.align(Alignment.End),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
          content = actions,
        )
      }
    }
  }

  if (embeddedTvPicker) {
    Surface(modifier = modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize().padding(TvSafeArea.Padding)) {
        sheetContent()
      }
    }
    return
  }

  if (usePlayerSheet) {
    // Keep player pickers in the same window and use the shared glass, drag and inset handling.
    PlayerSheet(
      onDismissRequest = onDismissRequest,
      modifier = modifier,
      content = sheetContent,
    )
    return
  }

  ResponsiveBottomSheet(
    onDismissRequest = onDismissRequest,
    animateEntrance = !reducedMotion,
    containerColor = MaterialTheme.colorScheme.surface,
    modifier = modifier,
  ) {
    sheetContent()
  }
}
