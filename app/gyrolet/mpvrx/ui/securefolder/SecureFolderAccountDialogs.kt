/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.securefolder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.SecureFolderPreferences
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

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
  onChanged: () -> Unit,
) {
  if (!isOpen) return

  var step by rememberSaveable(isOpen) { mutableStateOf(AccountDialogStep.VERIFY_CURRENT_PIN) }
  var currentPin by rememberSaveable(isOpen) { mutableStateOf("") }
  var newPin by rememberSaveable(isOpen) { mutableStateOf("") }
  var confirmPin by rememberSaveable(isOpen) { mutableStateOf("") }
  var showPin by rememberSaveable(isOpen) { mutableStateOf(false) }
  var error by rememberSaveable(isOpen) { mutableStateOf<String?>(null) }

  fun submit() {
    when (step) {
      AccountDialogStep.VERIFY_CURRENT_PIN -> {
        if (preferences.verifyPin(currentPin)) {
          error = null
          step = AccountDialogStep.ENTER_NEW
        } else {
          error = "Incorrect PIN"
        }
      }
      AccountDialogStep.ENTER_NEW -> {
        when {
          newPin.length < 4 -> error = "PIN must be at least 4 digits"
          newPin != confirmPin -> error = "PINs don't match"
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
      Text(if (step == AccountDialogStep.VERIFY_CURRENT_PIN) "Confirm current PIN" else "Choose a new PIN")
    },
    text = {
      Column {
        when (step) {
          AccountDialogStep.VERIFY_CURRENT_PIN ->
            PinTextField(
              value = currentPin,
              onValueChange = {
                currentPin = it.filter(Char::isDigit).take(8)
                error = null
              },
              showPin = showPin,
              onToggleShowPin = { showPin = !showPin },
              label = "Current PIN",
              onDone = ::submit,
            )
          AccountDialogStep.ENTER_NEW -> {
            PinTextField(
              value = newPin,
              onValueChange = {
                newPin = it.filter(Char::isDigit).take(8)
                error = null
              },
              showPin = showPin,
              onToggleShowPin = { showPin = !showPin },
              label = "New PIN",
            )
            PinTextField(
              value = confirmPin,
              onValueChange = {
                confirmPin = it.filter(Char::isDigit).take(8)
                error = null
              },
              showPin = showPin,
              onToggleShowPin = { showPin = !showPin },
              label = "Confirm new PIN",
              onDone = ::submit,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
        }
        if (error != null) {
          Text(
            error!!,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    },
    confirmButton = {
      Button(onClick = ::submit) {
        Text(if (step == AccountDialogStep.VERIFY_CURRENT_PIN) "Next" else "Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeSecurityQuestionDialog(
  isOpen: Boolean,
  preferences: SecureFolderPreferences,
  onDismiss: () -> Unit,
  onChanged: () -> Unit,
) {
  if (!isOpen) return

  var step by rememberSaveable(isOpen) { mutableStateOf(AccountDialogStep.VERIFY_CURRENT_PIN) }
  var currentPin by rememberSaveable(isOpen) { mutableStateOf("") }
  var showPin by rememberSaveable(isOpen) { mutableStateOf(false) }
  var question by rememberSaveable(isOpen) { mutableStateOf("") }
  var answer by rememberSaveable(isOpen) { mutableStateOf("") }
  var error by rememberSaveable(isOpen) { mutableStateOf<String?>(null) }

  fun submit() {
    when (step) {
      AccountDialogStep.VERIFY_CURRENT_PIN -> {
        if (preferences.verifyPin(currentPin)) {
          error = null
          step = AccountDialogStep.ENTER_NEW
        } else {
          error = "Incorrect PIN"
        }
      }
      AccountDialogStep.ENTER_NEW -> {
        if (question.isBlank() || answer.isBlank()) {
          error = "Please fill in both fields"
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
      Text(if (step == AccountDialogStep.VERIFY_CURRENT_PIN) "Confirm current PIN" else "New security question")
    },
    text = {
      Column {
        when (step) {
          AccountDialogStep.VERIFY_CURRENT_PIN ->
            PinTextField(
              value = currentPin,
              onValueChange = {
                currentPin = it.filter(Char::isDigit).take(8)
                error = null
              },
              showPin = showPin,
              onToggleShowPin = { showPin = !showPin },
              label = "Current PIN",
              onDone = ::submit,
            )
          AccountDialogStep.ENTER_NEW -> {
            OutlinedTextField(
              value = question,
              onValueChange = { question = it },
              label = { Text("Question") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
              value = answer,
              onValueChange = { answer = it },
              label = { Text("Answer") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
          }
        }
        if (error != null) {
          Text(
            error!!,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    },
    confirmButton = {
      Button(onClick = ::submit) {
        Text(if (step == AccountDialogStep.VERIFY_CURRENT_PIN) "Next" else "Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

/** Small local PIN field — [SecureFolderGateScreen]'s `PinField` is `private`, so this mirrors it rather than reaching across files. */
@Composable
private fun PinTextField(
  value: String,
  onValueChange: (String) -> Unit,
  showPin: Boolean,
  onToggleShowPin: () -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  onDone: () -> Unit = {},
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { onDone() }),
    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
    trailingIcon = {
      IconButton(onClick = onToggleShowPin) {
        Icon(
          if (showPin) Icons.RoundedFilled.VisibilityOff else Icons.RoundedFilled.Visibility,
          contentDescription = if (showPin) "Hide PIN" else "Show PIN",
        )
      }
    },
    modifier = modifier.fillMaxWidth(),
  )
}
