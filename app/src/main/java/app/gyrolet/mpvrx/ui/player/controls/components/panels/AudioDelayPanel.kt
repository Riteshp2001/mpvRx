/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:Suppress("ktlint:standard:no-wildcard-imports")

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun AudioDelayPanel(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val preferences = koinInject<AudioPreferences>()

  DraggablePanel(
    modifier = modifier,
    header = {
      DraggablePanelHeader(
        title = stringResource(R.string.player_sheets_audio_delay_card_title),
        onDismissRequest = onDismissRequest,
      )
    },
  ) {
    val delay by PlaybackSession.propDouble["audio-delay"].collectAsState()
    val delayFloat by remember { derivedStateOf { (delay ?: 0.0).toFloat() } }

    DelayCard(
      delay = delayFloat,
      onDelayChange = {
        val delayInSeconds = it.toDouble()
        PlaybackSession.setPropertyDouble("audio-delay", delayInSeconds)
      },
      onApply = { preferences.defaultAudioDelay.set((delayFloat * 1000).roundToInt()) },
      onReset = { PlaybackSession.setPropertyDouble("audio-delay", 0.0) },
      delayType = DelayType.Audio,
    )
  }
}

// Ensure the AudioDelayPanel also uses the content version as DraggablePanel wraps it
@Composable
fun DelayCard(
  delay: Float,
  onDelayChange: (Float) -> Unit,
  onApply: () -> Unit,
  onReset: () -> Unit,
  delayType: DelayType,
) {
  DelayCardContent(
    delay = delay,
    onDelayChange = onDelayChange,
    onApply = onApply,
    onReset = onReset,
    delayType = delayType,
  )
}
