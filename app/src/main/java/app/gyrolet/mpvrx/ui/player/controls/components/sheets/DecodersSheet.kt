/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.player.Decoder
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.theme.spacing

@Composable
fun DecodersSheet(
  selectedDecoder: Decoder,
  onSelect: (Decoder) -> Unit,
  onDismissRequest: () -> Unit,
) {
  // mediacodec is a zero-copy Android hwdec path that requires mpv's Android/OpenGL
  // interop. It cannot interop with a Vulkan renderer. mediacodec-copy remains valid
  // because decoded frames are copied back to system memory before GPU upload.
  val gpuApi by PlaybackSession.propString["gpu-api"].collectAsState()
  val isVulkanActive = gpuApi == "vulkan"

  PlayerSheet(onDismissRequest) {
    LazyColumn {
      items(Decoder.entries.minusElement(Decoder.Auto), key = { it.name }) { decoder ->
        val isSupported = !(isVulkanActive && decoder == Decoder.HWPlus)
        DecoderRow(
          title = stringResource(R.string.player_sheets_decoder_formatted, decoder.title, decoder.value),
          isSelected = selectedDecoder == decoder,
          enabled = isSupported,
          unsupportedReason = if (isSupported) null else "Unavailable with Vulkan",
          onClick = { onSelect(decoder) },
        )
      }
    }
  }
}

@Composable
private fun DecoderRow(
  title: String,
  isSelected: Boolean,
  enabled: Boolean,
  unsupportedReason: String?,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .alpha(if (enabled) 1f else 0.45f)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    RadioButton(
      selected = isSelected,
      onClick = if (enabled) onClick else null,
      enabled = enabled,
    )
    Column {
      Text(
        text = title,
        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal,
        fontStyle = if (isSelected) FontStyle.Italic else FontStyle.Normal,
      )
      if (unsupportedReason != null) {
        Text(
          text = unsupportedReason,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
