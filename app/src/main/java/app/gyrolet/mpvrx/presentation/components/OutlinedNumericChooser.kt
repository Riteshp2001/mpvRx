/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.components

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.spacing
import java.text.DecimalFormatSymbols

@Composable
fun OutlinedNumericChooser(
  value: Int,
  onChange: (Int) -> Unit,
  max: Int,
  step: Int,
  modifier: Modifier = Modifier,
  min: Int = 0,
  suffix: (@Composable () -> Unit)? = null,
  label: (@Composable () -> Unit)? = null,
  decreaseIcon: AppIcon = Icons.RoundedFilled.Remove,
  increaseIcon: AppIcon = Icons.RoundedFilled.Add,
  valueFormatter: ((Int) -> String)? = null,
  enabled: Boolean = true,
) {
  assert(max > min) { "min can't be larger than max ($min > $max)" }
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    RepeatingIconButton(
      onClick = { onChange(value - step) },
      enabled = enabled,
      modifier = Modifier.size(48.dp),
    ) {
      Icon(decreaseIcon, stringResource(R.string.generic_decrease))
    }
    var valueString by remember { mutableStateOf("$value") }
    LaunchedEffect(value) {
      if (valueString.isBlank() && value == 0) return@LaunchedEffect
      valueString = valueFormatter?.invoke(value) ?: value.toString()
    }
    OutlinedTextField(
      label = label,
      enabled = enabled,
      value = valueString,
      onValueChange = { newValue ->
        if (newValue.isBlank()) {
          valueString = newValue
          onChange(0)
        }
        runCatching {
          val intValue = if (newValue.trimStart() == "-") -0 else newValue.toInt()
          onChange(intValue)
          valueString = newValue
        }
      },
      isError = value > max || value < min,
      supportingText = {
        if (value > max) Text(stringResource(R.string.numeric_chooser_value_too_big))
        if (value < min) Text(stringResource(R.string.numeric_chooser_value_too_small))
      },
      suffix = suffix,
      modifier = Modifier.weight(1f),
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    RepeatingIconButton(
      onClick = { onChange(value + step) },
      enabled = enabled,
      modifier = Modifier.size(48.dp),
    ) {
      Icon(increaseIcon, stringResource(R.string.generic_increase))
    }
  }
}

@Composable
fun OutlinedNumericChooser(
  value: Float,
  onChange: (Float) -> Unit,
  max: Float,
  step: Float,
  modifier: Modifier = Modifier,
  min: Float = 0f,
  suffix: (@Composable () -> Unit)? = null,
  label: (@Composable () -> Unit)? = null,
  decreaseIcon: AppIcon = Icons.RoundedFilled.Remove,
  increaseIcon: AppIcon = Icons.RoundedFilled.Add,
  valueFormatter: ((Float) -> String)? = null,
  enabled: Boolean = true,
) {
  assert(max > min) { "min can't be larger than max ($min > $max)" }
  fun formatValue(number: Float): String =
    valueFormatter?.invoke(number) ?: number.toString().dropLastWhile { it == '0' }.dropLastWhile { it == '.' }

  var valueString by remember { mutableStateOf(formatValue(value)) }
  var focused by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  val locales = LocalConfiguration.current.locales
  val symbols = remember(locales) { DecimalFormatSymbols.getInstance(locales[0]) }
  val numericText = normalizeNumericInput(valueString, symbols)
  val enteredValue = numericText.toFloatOrNull()?.takeIf { it.isFinite() }
  val invalidInput = when (numericText) {
    "", "-", "+", ".", "-.", "+." -> false
    else -> enteredValue == null
  }
  val displayedValue = enteredValue ?: value

  LaunchedEffect(value, focused) {
    // Playback property echoes must not reformat an in-progress edit.
    if (!focused) valueString = formatValue(value)
  }

  fun changeFromButton(number: Float) {
    valueString = formatValue(number)
    onChange(number)
  }

  val allowNegative = min < 0f
  val inputInterceptor = remember(allowNegative) {
    PlatformTextInputInterceptor { request, nextHandler ->
      nextHandler.startInputMethod(
        PlatformTextInputMethodRequest { attributes ->
          val connection = request.createInputConnection(attributes)
          if (allowNegative) attributes.inputType = attributes.inputType or InputType.TYPE_NUMBER_FLAG_SIGNED
          attributes.imeOptions = attributes.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
          connection
        },
      )
    }
  }

  Row(
    modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    RepeatingIconButton(
      onClick = { changeFromButton(value - step) },
      enabled = enabled,
      modifier = Modifier.size(48.dp),
    ) {
      Icon(decreaseIcon, stringResource(R.string.generic_decrease))
    }
    InterceptPlatformTextInput(inputInterceptor) {
      OutlinedTextField(
        value = valueString,
        enabled = enabled,
        label = label,
        onValueChange = { newValue ->
          valueString = newValue
          val parsed = normalizeNumericInput(newValue, symbols).toFloatOrNull()?.takeIf { it.isFinite() }
          if (newValue.isBlank()) onChange(0f) else parsed?.let(onChange)
        },
        isError = invalidInput || displayedValue > max || displayedValue < min,
        supportingText = {
          when {
            invalidInput -> Text(stringResource(R.string.numeric_chooser_invalid_value))
            displayedValue > max -> Text(stringResource(R.string.numeric_chooser_value_too_big))
            displayedValue < min -> Text(stringResource(R.string.numeric_chooser_value_too_small))
          }
        },
        modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
        singleLine = true,
        suffix = suffix,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
      )
    }
    RepeatingIconButton(
      onClick = { changeFromButton(value + step) },
      enabled = enabled,
      modifier = Modifier.size(48.dp),
    ) {
      Icon(increaseIcon, stringResource(R.string.generic_increase))
    }
  }

}

private fun normalizeNumericInput(text: String, symbols: DecimalFormatSymbols): String =
  text.trim().map { character ->
    when (character) {
      symbols.decimalSeparator, ',' -> '.'
      symbols.minusSign, '\u2212' -> '-'
      else -> character.digitToIntOrNull()?.digitToChar() ?: character
    }
  }.joinToString("")
