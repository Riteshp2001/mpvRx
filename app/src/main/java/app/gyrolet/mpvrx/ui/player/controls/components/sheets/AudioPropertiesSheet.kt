/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.fontFamilyForText

data class AudioPropertyItem(
  val label: String,
  val value: String,
)

@Composable
fun AudioPropertiesSheet(
  properties: List<AudioPropertyItem>,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  isLoading: Boolean = false,
  artwork: Bitmap? = null,
  mediaTitle: String? = null,
  artistName: String? = null,
) {
  val image = remember(artwork) { artwork?.asImageBitmap() }
  val showHeader = image != null || !mediaTitle.isNullOrBlank() || !artistName.isNullOrBlank()
  val headerRows = if (showHeader) 1 else 0
  val rowCount = headerRows + if (isLoading || properties.isEmpty()) 1 else properties.size
  PlayerSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    title = stringResource(R.string.audio_information_title),
  ) {
    SelectionContainer {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
        ) {
          if (showHeader) {
            Surface(
              modifier = Modifier.fillMaxWidth(),
              shape = rememberConnectedShape(0, rowCount, ConnectedLayout.Vertical),
              color = playerSheetSelectionColor(false),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Box(
                  modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                  contentAlignment = Alignment.Center,
                ) {
                  if (image != null) {
                    Image(
                      bitmap = image,
                      contentDescription = null,
                      contentScale = ContentScale.Crop,
                      modifier = Modifier.size(72.dp),
                    )
                  } else {
                    Icon(
                      Icons.RoundedFilled.Audiotrack,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(32.dp),
                    )
                  }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  mediaTitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                      it,
                      style = MaterialTheme.typography.titleLarge,
                      fontFamily = fontFamilyForText(it),
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                  artistName?.takeIf(String::isNotBlank)?.let {
                    Text(
                      it,
                      style = MaterialTheme.typography.bodyMedium,
                      fontFamily = fontFamilyForText(it),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
            }
          }
          if (isLoading || properties.isEmpty()) {
            Surface(
              modifier = Modifier.fillMaxWidth(),
              shape = rememberConnectedShape(headerRows, rowCount, ConnectedLayout.Vertical),
              color = playerSheetSelectionColor(false),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                  stringResource(if (isLoading) R.string.audio_info_loading else R.string.audio_info_unavailable),
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
            }
          } else {
            properties.forEachIndexed { index, prop ->
              Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = rememberConnectedShape(index + headerRows, rowCount, ConnectedLayout.Vertical),
                color = playerSheetSelectionColor(false),
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                  Text(
                    text = prop.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = prop.value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    fontFamily = fontFamilyForText(prop.value),
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                }
              }
            }
          }
        }
      }
  }
}
