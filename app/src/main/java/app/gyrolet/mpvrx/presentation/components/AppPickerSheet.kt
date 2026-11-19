/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.LocalMotionPolicy
import app.gyrolet.mpvrx.ui.theme.MotionPolicy

/**
 * The sheet the app's pickers sit in: a heading, an optional path and warning,
 * a scrolling body, and the actions pinned under it.
 *
 * Sized rather than filled. A picker is a list of a few rows most of the time,
 * and a sheet that always claims the whole screen to show four folders reads
 * as a page the user has been taken to rather than a choice laid over what
 * they were doing. The body is capped instead, at a fraction of the screen
 * that is much smaller in landscape — where the height is the scarce axis and
 * a half-screen body would leave nothing for the actions.
 *
 * Storage browsers use [fixedHeight] to keep navigation and footer actions stationary as paths change.
 *
 * Player callers can opt into [PlayerSheet] to share its glass material and backdrop window.
 *
 * Only ever fully open or gone: a half-expanded detent is a second thing to
 * get past on a phone and meaningless on a short landscape screen.
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
  content: @Composable () -> Unit,
) {
  val configuration = LocalConfiguration.current
  val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
  val reducedMotion = AppMotion.playerReducedMotion()
  val bodyMaxHeight =
    (configuration.screenHeightDp.dp - if (isLandscape) 132.dp else 180.dp)
      .coerceAtLeast(160.dp)
      .coerceAtMost(if (isLandscape) 460.dp else 600.dp)

  val sheetContent: @Composable () -> Unit = {
    CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = reducedMotion)) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .then(if (fixedHeight) Modifier.fillMaxHeight(0.85f) else Modifier)
            .then(if (usePlayerSheet) Modifier else Modifier.navigationBarsPadding().imePadding())
            .padding(horizontal = 20.dp)
            .padding(bottom = if (usePlayerSheet) 0.dp else 12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
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
                style = MaterialTheme.typography.headlineSmallEmphasized,
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

        Spacer(Modifier.height(12.dp))

        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f, fill = fixedHeight)
              .then(if (fixedHeight) Modifier else Modifier.heightIn(max = bodyMaxHeight))
              .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        ) {
          content()
        }

        Spacer(Modifier.height(12.dp))

        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          content = actions,
        )
      }
    }
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

  val sheetState =
    rememberBottomSheetState(
      initialValue = if (reducedMotion) SheetValue.Expanded else SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    scrimColor = BottomSheetDefaults.ScrimColor,
    // Keeps the sheet a centred panel on a tablet instead of a band across a 1600dp screen.
    sheetMaxWidth = 640.dp,
    dragHandle = { PlayerSheetDragHandle() },
    modifier = modifier,
  ) {
    sheetContent()
  }
}
