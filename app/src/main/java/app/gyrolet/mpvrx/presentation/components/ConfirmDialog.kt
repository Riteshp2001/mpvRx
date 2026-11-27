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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.tv.tvScrollWhenFocusCannotMove

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConfirmDialog(
  title: String,
  subtitle: String,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  customContent: (@Composable () -> Unit)? = null,
  confirmText: String = stringResource(R.string.generic_confirm),
  confirmContent: (@Composable () -> Unit)? = null,
) {
  val initialFocusRequester = rememberTvInitialFocusRequester(requestKey = title)
  val messageScroll = rememberScrollState()
  AppAlertDialog(
    singleRowActions = confirmContent != null,
    onDismissRequest = onCancel,
    // TV: Up/Down scroll a long message once focus has nowhere else to go.
    modifier = modifier.tvScrollWhenFocusCannotMove(messageScroll),
    title = { Text(title) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(messageScroll),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
      ) {
        // The body keeps its connected dialog surface; present the confirmation message
        // as a clear visual prompt rather than a block of unstructured body text.
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.Info,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
              )
            }
          }
          Text(
            text = subtitle,
            modifier = Modifier.weight(1f).padding(top = 2.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        customContent?.invoke()
      }
    },
    dismissButton = {
      AppDialogTextButton(
        onClick = onCancel,
        modifier =
          Modifier
            .tvInitialFocus(initialFocusRequester)
            .tvFocusHighlight(MaterialTheme.shapes.extraLarge, focusedScale = 1.04f),
      ) { Text(stringResource(R.string.generic_cancel), fontWeight = FontWeight.Medium) }
    },
    confirmButton = {
      if (confirmContent != null) {
        confirmContent()
      } else {
        AppDialogTextButton(
          onClick = onConfirm,
          modifier = Modifier.tvFocusHighlight(MaterialTheme.shapes.extraLarge, focusedScale = 1.04f),
        ) { Text(confirmText, fontWeight = FontWeight.Bold) }
      }
    },
  )
}
