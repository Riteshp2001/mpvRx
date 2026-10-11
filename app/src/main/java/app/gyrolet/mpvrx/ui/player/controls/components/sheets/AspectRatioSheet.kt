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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSectionHeader
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.player.AutoCropState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.spacing

data class AspectRatio(
  val label: String,
  val ratio: Double,
  val isCustom: Boolean = false,
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AspectRatioSheet(
  currentRatio: Double?,
  customRatios: List<AspectRatio>,
  autoCropEnabled: Boolean,
  autoCropState: AutoCropState,
  autoCropControlEnabled: Boolean,
  onAutoCropChanged: (Boolean) -> Unit,
  onSelectRatio: (Double) -> Unit,
  onAddCustomRatio: (String, Double) -> Unit,
  onDeleteCustomRatio: (AspectRatio) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val presetRatios =
    listOf(
      AspectRatio("Default", -1.0),
      AspectRatio("4:3", 4.0 / 3.0),
      AspectRatio("16:9", 16.0 / 9.0),
      AspectRatio("16:10", 16.0 / 10.0),
      AspectRatio("21:9", 21.0 / 9.0),
      AspectRatio("32:9", 32.0 / 9.0),
      AspectRatio("1:1", 1.0),
      AspectRatio("2.35:1", 2.35),
      AspectRatio("2.39:1", 2.39),
    )

  PlayerSheet(onDismissRequest, title = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_aspect_ratio)) {
    Column(
      modifier =
        modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(bottom = 8.dp),
    ) {
      val autoCropSummary =
        when {
          !autoCropControlEnabled -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_managed
          !autoCropEnabled -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_summary
          autoCropState == AutoCropState.ANALYZING -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_analyzing
          autoCropState == AutoCropState.APPLIED -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_applied
          autoCropState == AutoCropState.NO_BARS -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_none
          autoCropState == AutoCropState.UNSUPPORTED -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_unsupported
          autoCropState == AutoCropState.ERROR -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_failed
          else -> app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars_summary
        }
      Surface(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color =
          if (autoCropEnabled) {
            MaterialTheme.colorScheme.secondaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceContainerLow
          },
      ) {
        ListItem(
          content = {
            Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_auto_crop_black_bars))
          },
          supportingContent = { Text(androidx.compose.ui.res.stringResource(autoCropSummary)) },
          trailingContent = {
            IconSwitch(
              checked = autoCropEnabled,
              onCheckedChange = null,
              enabled = autoCropControlEnabled,
            )
          },
          modifier =
            Modifier.clickable(enabled = autoCropControlEnabled) {
              onAutoCropChanged(!autoCropEnabled)
            },
          colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
      }

      // Preset ratios
      PlayerSheetSectionHeader(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_presets))

      androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        presetRatios.forEach { ratio ->
          InputChip(
            selected = currentRatio?.let { abs(it - ratio.ratio) < 0.01 } ?: (ratio.ratio == -1.0),
            onClick = { onSelectRatio(ratio.ratio) },
            label = { Text(ratio.label) },
            leadingIcon = null,
          )
        }
      }

      // Custom ratios
      if (customRatios.isNotEmpty()) {
        PlayerSheetSectionHeader(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.pref_gesture_double_tap_custom))

        androidx.compose.foundation.layout.FlowRow(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          customRatios.forEach { ratio ->
            InputChip(
              selected = currentRatio?.let { abs(it - ratio.ratio) < 0.01 } ?: false,
              onClick = { onSelectRatio(ratio.ratio) },
              label = { Text(ratio.label) },
              leadingIcon = null,
              trailingIcon = {
                Icon(
                  Icons.RoundedFilled.Close,
                  null,
                  modifier = Modifier.clickable { onDeleteCustomRatio(ratio) },
                )
              },
            )
          }
        }
      }

      // Add custom ratio
      AddCustomRatioRow(
        onAdd = onAddCustomRatio,
        modifier = Modifier.padding(top = MaterialTheme.spacing.medium),
      )
    }
  }
}

@Composable
private fun AddCustomRatioRow(
  onAdd: (String, Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  var widthText by remember { mutableStateOf("") }
  var heightText by remember { mutableStateOf("") }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  val keyboardController = LocalSoftwareKeyboardController.current

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium),
  ) {
    Text(
      text =
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.ui_add_custom_ratio_e_g_16_9),
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
      modifier = Modifier.fillMaxWidth(),
    ) {
      // Width input
      OutlinedTextField(
        value = widthText,
        onValueChange = {
          widthText = it.filter { char -> char.isDigit() || char == '.' }
          errorMessage = null
        },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_width),
          )
        },
        isError = errorMessage != null,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
          ),
        modifier = Modifier.weight(1f),
        singleLine = true,
      )

      // Colon separator
      Text(
        text = ":",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.extraSmall),
      )

      // Height input
      OutlinedTextField(
        value = heightText,
        onValueChange = {
          heightText = it.filter { char -> char.isDigit() || char == '.' }
          errorMessage = null
        },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_height),
          )
        },
        isError = errorMessage != null,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
          ),
        keyboardActions =
          KeyboardActions(
            onDone = {
              val result = calculateRatio(widthText, heightText)
              if (result != null) {
                onAdd("$widthText:$heightText", result)
                widthText = ""
                heightText = ""
                keyboardController?.hide()
              } else {
                errorMessage = "Invalid"
              }
            },
          ),
        modifier = Modifier.weight(1f),
        singleLine = true,
      )

      // Add button
      FilledTonalIconButton(
        onClick = {
          val result = calculateRatio(widthText, heightText)
          if (result != null) {
            onAdd("$widthText:$heightText", result)
            widthText = ""
            heightText = ""
            keyboardController?.hide()
          } else {
            errorMessage = "Invalid"
          }
        },
      ) {
        Icon(
          Icons.RoundedFilled.Add,
          contentDescription =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_add),
        )
      }
    }

    errorMessage?.let { msg ->
      Text(
        text = msg,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = MaterialTheme.spacing.small, top = 4.dp),
      )
    }
  }
}

private fun calculateRatio(
  widthStr: String,
  heightStr: String,
): Double? {
  if (widthStr.isEmpty() || heightStr.isEmpty()) return null

  return try {
    val width = widthStr.toDouble()
    val height = heightStr.toDouble()
    if (width > 0 && height > 0) width / height else null
  } catch (_: NumberFormatException) {
    null
  }
}

private fun abs(value: Double): Double = if (value < 0) -value else value
