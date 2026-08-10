/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.securefolder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.SecureFolderPreferences
import app.gyrolet.mpvrx.presentation.components.ExposedTextDropDownMenu
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.collections.immutable.toImmutableList

/**
 * "Change PIN" and "Change Security Question" dialogs for the Secure Folder overflow menu /
 * Preferences row (Step 5 wires the Preferences entry). Both require re-entering the current
 * PIN before allowing a change — same trust boundary as [SecureFolderGateScreen]'s
 * forgot-PIN flow, just without leaving the current screen.
 */
private enum class AccountDialogStep { VERIFY_CURRENT_PIN, ENTER_NEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePinDialog(
  isOpen: Boolean,
  preferences: SecureFolderPreferences,
  onDismiss: () -> Unit,
  onChanged: () -> Unit = {},
) {
  if (!isOpen) return

  var step by rememberSaveable(isOpen) { mutableStateOf(AccountDialogStep.VERIFY_CURRENT_PIN) }
  var currentPin by rememberSaveable(isOpen) { mutableStateOf("") }
  var newPin by rememberSaveable(isOpen) { mutableStateOf("") }
  var confirmPin by rememberSaveable(isOpen) { mutableStateOf("") }
  var showPin by rememberSaveable(isOpen) { mutableStateOf(false) }
  var errorRes by rememberSaveable(isOpen) { mutableStateOf<Int?>(null) }

  fun submit() {
    when (step) {
      AccountDialogStep.VERIFY_CURRENT_PIN -> {
        if (preferences.verifyPin(currentPin)) {
          errorRes = null
          step = AccountDialogStep.ENTER_NEW
        } else {
          errorRes = R.string.secure_folder_error_incorrect_pin
        }
      }
      AccountDialogStep.ENTER_NEW -> {
        when {
          newPin.length < 4 -> errorRes = R.string.secure_folder_error_pin_min_digits
          newPin != confirmPin -> errorRes = R.string.secure_folder_error_pins_dont_match
          else -> {
            preferences.setPin(newPin)
            onChanged()
            onDismiss()
          }
        }
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.RoundedFilled.Lock, contentDescription = null) },
    title = {
      Text(
        if (step == AccountDialogStep.VERIFY_CURRENT_PIN) {
          stringResource(R.string.secure_folder_confirm_current_pin)
        } else {
          stringResource(R.string.secure_folder_choose_new_pin)
        }
      )
    },
    text = {
      Column {
        when (step) {
          AccountDialogStep.VERIFY_CURRENT_PIN ->
            PinField(
              value = currentPin,
              onValueChange = {
                currentPin = it.filter(Char::isDigit).take(4)
                errorRes = null
              },
              showPin = showPin,
              onToggleShowPin = { showPin = !showPin },
              label = stringResource(R.string.secure_folder_current_pin),
              onDone = ::submit,
            )
          AccountDialogStep.ENTER_NEW -> {
            PinField(
              value = newPin,
              onValueChange = {
                newPin = it.filter(Char::isDigit).take(4)
                errorRes = null
              },
              showPin = showPin,
              onToggleShowPin = { showPin = !showPin },
              label = stringResource(R.string.secure_folder_new_pin),
            )
            PinField(
              value = confirmPin,
              onValueChange = {
                confirmPin = it.filter(Char::isDigit).take(4)
                errorRes = null
              },
              showPin = showPin,
              onToggleShowPin = { showPin = !showPin },
              label = stringResource(R.string.secure_folder_confirm_new_pin),
              onDone = ::submit,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
        }
        if (errorRes != null) {
          Text(
            stringResource(errorRes!!),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    },
    confirmButton = {
      Button(onClick = ::submit) {
        Text(
          if (step == AccountDialogStep.VERIFY_CURRENT_PIN) {
            stringResource(R.string.secure_folder_next)
          } else {
            stringResource(R.string.secure_folder_save)
          }
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeSecurityQuestionDialog(
  isOpen: Boolean,
  preferences: SecureFolderPreferences,
  onDismiss: () -> Unit,
  onChanged: () -> Unit = {},
) {
  if (!isOpen) return

  var step by rememberSaveable(isOpen) { mutableStateOf(AccountDialogStep.VERIFY_CURRENT_PIN) }
  var currentPin by rememberSaveable(isOpen) { mutableStateOf("") }
  var showPin by rememberSaveable(isOpen) { mutableStateOf(false) }
  val presets = SECURITY_QUESTION_PRESET_RES_IDS.map { stringResource(it) }.toImmutableList()
  var question by rememberSaveable(isOpen, presets) { mutableStateOf(presets.first()) }
  var answer by rememberSaveable(isOpen) { mutableStateOf("") }
  var errorRes by rememberSaveable(isOpen) { mutableStateOf<Int?>(null) }

  fun submit() {
    when (step) {
      AccountDialogStep.VERIFY_CURRENT_PIN -> {
        if (preferences.verifyPin(currentPin)) {
          errorRes = null
          step = AccountDialogStep.ENTER_NEW
        } else {
          errorRes = R.string.secure_folder_error_incorrect_pin
        }
      }
      AccountDialogStep.ENTER_NEW -> {
        if (question.isBlank() || answer.isBlank()) {
          errorRes = R.string.secure_folder_error_fill_both_fields
          return
        }
        preferences.setSecurityQuestion(question.trim(), answer)
        onChanged()
        onDismiss()
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.RoundedFilled.HelpOutline, contentDescription = null) },
    title = {
      Text(
        if (step == AccountDialogStep.VERIFY_CURRENT_PIN) {
          stringResource(R.string.secure_folder_confirm_current_pin)
        } else {
          stringResource(R.string.secure_folder_new_security_question)
        }
      )
    },
    text = {
      Column {
        when (step) {
          AccountDialogStep.VERIFY_CURRENT_PIN ->
            PinField(
              value = currentPin,
              onValueChange = {
                currentPin = it.filter(Char::isDigit).take(4)
                errorRes = null
              },
              showPin = showPin,
              onToggleShowPin = { showPin = !showPin },
              label = stringResource(R.string.secure_folder_current_pin),
              onDone = ::submit,
            )
          AccountDialogStep.ENTER_NEW -> {
            ExposedTextDropDownMenu(
              selectedValue = question,
              options = presets,
              label = stringResource(R.string.secure_folder_security_question),
              onValueChangedEvent = { question = it },
            )
            OutlinedTextField(
              value = answer,
              onValueChange = { answer = it },
              label = { Text(stringResource(R.string.secure_folder_answer)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
          }
        }
        if (errorRes != null) {
          Text(
            stringResource(errorRes!!),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    },
    confirmButton = {
      Button(onClick = ::submit) {
        Text(
          if (step == AccountDialogStep.VERIFY_CURRENT_PIN) {
            stringResource(R.string.secure_folder_next)
          } else {
            stringResource(R.string.secure_folder_save)
          }
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}


