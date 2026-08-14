/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.player.Decoder
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.RendererBackendPolicy

@Composable
fun DecodersSheet(
  selectedDecoder: Decoder,
  onSelect: (Decoder) -> Unit,
  onDismissRequest: () -> Unit,
) {
  val gpuApi by PlaybackSession.propString["gpu-api"].collectAsState()
  val isVulkanActive = gpuApi == "vulkan"
  val directMediaCodecAllowed =
    RendererBackendPolicy.canUseDirectMediaCodec(
      usesVulkan = isVulkanActive,
      buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
    )

  PlayerSheet(onDismissRequest) {
    LazyColumn {
      items(Decoder.entries.minusElement(Decoder.Auto), key = { it.name }) { decoder ->
        AudioTrackRow(
          title = stringResource(R.string.player_sheets_decoder_formatted, decoder.title, decoder.value),
          isSelected = selectedDecoder == decoder,
          enabled = decoder != Decoder.HWPlus || directMediaCodecAllowed,
          onClick = { onSelect(decoder) },
        )
      }
    }
  }
}
