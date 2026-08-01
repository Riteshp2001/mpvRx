/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.securefolder

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ExposedTextDropDownMenu
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.replaceTop
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable

/** Preset security question resource IDs — no free-text question, only the answer is typed. */
val SECURITY_QUESTION_PRESET_RES_IDS =
  persistentListOf(
    R.string.secure_folder_sq_pet,
    R.string.secure_folder_sq_mother,
    R.string.secure_folder_sq_school,
    R.string.secure_folder_sq_city,
    R.string.secure_folder_sq_nickname,
    R.string.secure_folder_sq_movie,
  )

/**
 * PIN gate in front of the Secure Folder. Handles first-time setup (PIN + security question
 * chosen together, in one step), normal entry (PIN + eye toggle to reveal it), and the
 * forgot-PIN recovery flow (security question -> new PIN).
 *
 * On a successful unlock, pushes [SecureFolderScreen] onto the back stack.
 */
@Serializable
data object SecureFolderGateScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val viewModel: SecureFolderViewModel =
      viewModel(factory = SecureFolderViewModel.factory(context.applicationContext as android.app.Application))

    val gateStep by viewModel.gateStep.collectAsState()
    val gateError by viewModel.gateError.collectAsState()

    // Re-check the persisted PIN state every time this screen is entered, so a previously
    // completed setup is never re-shown (see SecureFolderViewModel.refreshGateStep).
    androidx.compose.runtime.LaunchedEffect(Unit) {
      viewModel.refreshGateStep()
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.secure_folder_title)) },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null)
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(),
        )
      },
    ) { padding ->
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding)
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.Center,
      ) {
        AnimatedContent(
          targetState = gateStep,
          transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
          label = "secure_folder_gate_step",
        ) { step ->
          when (step) {
            SecureFolderViewModel.GateStep.ENTER_PIN ->
              EnterPinContent(
                error = gateError,
                onSubmit = { pin ->
                  if (viewModel.verifyPin(pin)) {
                    backstack.replaceTop(SecureFolderScreen)
                  }
                },
                onForgotPin = { viewModel.startForgotPinFlow() },
              )

            SecureFolderViewModel.GateStep.SETUP ->
              SetupContent(
                error = gateError,
                onSubmit = { pin, question, answer ->
                  if (viewModel.submitSetup(pin, question, answer)) {
                    backstack.replaceTop(SecureFolderScreen)
                  }
                },
              )

            SecureFolderViewModel.GateStep.FORGOT_PIN_QUESTION ->
              SecurityQuestionAnswerContent(
                question = viewModel.preferences.securityQuestion.get(),
                error = gateError,
                onSubmit = { answer -> viewModel.verifySecurityAnswerForRecovery(answer) },
                onCancel = { viewModel.cancelForgotPinFlow() },
              )

            SecureFolderViewModel.GateStep.FORGOT_PIN_NEW_PIN ->
              ChoosePinContent(
                title = stringResource(R.string.secure_folder_choose_new_pin),
                subtitle = stringResource(R.string.secure_folder_old_pin_invalid),
                error = gateError,
                onSubmit = { pin -> viewModel.finishForgotPinFlow(pin) },
              )
          }
        }
      }
    }
  }
}

@Composable
private fun EnterPinContent(
  error: String?,
  onSubmit: (String) -> Unit,
  onForgotPin: () -> Unit,
) {
  var pin by rememberSaveable { mutableStateOf("") }
  var showPin by rememberSaveable { mutableStateOf(false) }

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      Icons.RoundedFilled.Lock,
      contentDescription = null,
      modifier = Modifier.size(48.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      stringResource(R.string.secure_folder_enter_pin),
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
    )

    PinField(
      value = pin,
      onValueChange = { pin = it.filter(Char::isDigit).take(8) },
      showPin = showPin,
      onToggleShowPin = { showPin = !showPin },
      onDone = { if (pin.isNotEmpty()) onSubmit(pin) },
    )

    if (error != null) {
      Text(
        error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Button(
      onClick = { onSubmit(pin) },
      enabled = pin.isNotEmpty(),
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
      Text(stringResource(R.string.secure_folder_unlock))
    }

    TextButton(onClick = onForgotPin, modifier = Modifier.padding(top = 4.dp)) {
      Text(stringResource(R.string.secure_folder_forgot_pin))
    }
  }
}

/**
 * First-time setup, all in one step: PIN + confirm PIN + security question (preset dropdown) +
 * answer. Everything is validated and persisted together in a single call, so there's no
 * intermediate "pending PIN" state that can be lost between steps.
 */
@Composable
private fun SetupContent(
  error: String?,
  onSubmit: (pin: String, question: String, answer: String) -> Unit,
) {
  val presets = SECURITY_QUESTION_PRESET_RES_IDS.map { stringResource(it) }.toImmutableList()
  var pin by rememberSaveable { mutableStateOf("") }
  var confirmPin by rememberSaveable { mutableStateOf("") }
  var showPin by rememberSaveable { mutableStateOf(false) }
  var question by rememberSaveable(presets) { mutableStateOf(presets.first()) }
  var answer by rememberSaveable { mutableStateOf("") }
  var validationErrorRes by remember { mutableStateOf<Int?>(null) }

  val errPinMin = stringResource(R.string.secure_folder_error_pin_min_digits)
  val errPinsMatch = stringResource(R.string.secure_folder_error_pins_dont_match)
  val errAnswerSq = stringResource(R.string.secure_folder_error_answer_question)

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      Icons.RoundedFilled.Security,
      contentDescription = null,
      modifier = Modifier.size(48.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      stringResource(R.string.secure_folder_setup_title),
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.padding(top = 16.dp),
    )
    Text(
      stringResource(R.string.secure_folder_setup_subtitle),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
    )

    PinField(
      value = pin,
      onValueChange = {
        pin = it.filter(Char::isDigit).take(8)
        validationErrorRes = null
      },
      showPin = showPin,
      onToggleShowPin = { showPin = !showPin },
      label = stringResource(R.string.secure_folder_new_pin),
    )

    PinField(
      value = confirmPin,
      onValueChange = {
        confirmPin = it.filter(Char::isDigit).take(8)
        validationErrorRes = null
      },
      showPin = showPin,
      onToggleShowPin = { showPin = !showPin },
      label = stringResource(R.string.secure_folder_confirm_pin),
      modifier = Modifier.padding(top = 12.dp),
    )

    ExposedTextDropDownMenu(
      selectedValue = question,
      options = presets,
      label = stringResource(R.string.secure_folder_security_question),
      onValueChangedEvent = { question = it },
      modifier = Modifier.padding(top = 20.dp),
    )

    OutlinedTextField(
      value = answer,
      onValueChange = {
        answer = it
        validationErrorRes = null
      },
      label = { Text(stringResource(R.string.secure_folder_answer)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )

    val shownError = if (validationErrorRes != null) stringResource(validationErrorRes!!) else error
    if (shownError != null) {
      Text(
        shownError,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Button(
      onClick = {
        when {
          pin.length < 4 -> validationErrorRes = R.string.secure_folder_error_pin_min_digits
          pin != confirmPin -> validationErrorRes = R.string.secure_folder_error_pins_dont_match
          answer.isBlank() -> validationErrorRes = R.string.secure_folder_error_answer_question
          else -> onSubmit(pin, question, answer)
        }
      },
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
      Text(stringResource(R.string.secure_folder_finish_setup))
    }
  }
}

@Composable
private fun ChoosePinContent(
  title: String,
  subtitle: String,
  error: String?,
  onSubmit: (String) -> Unit,
) {
  var pin by rememberSaveable { mutableStateOf("") }
  var confirmPin by rememberSaveable { mutableStateOf("") }
  var showPin by rememberSaveable { mutableStateOf(false) }
  var mismatchErrorRes by remember { mutableStateOf<Int?>(null) }

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      Icons.RoundedFilled.Security,
      contentDescription = null,
      modifier = Modifier.size(48.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
    Text(
      subtitle,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
    )

    PinField(
      value = pin,
      onValueChange = {
        pin = it.filter(Char::isDigit).take(8)
        mismatchErrorRes = null
      },
      showPin = showPin,
      onToggleShowPin = { showPin = !showPin },
      label = stringResource(R.string.secure_folder_new_pin),
    )

    PinField(
      value = confirmPin,
      onValueChange = {
        confirmPin = it.filter(Char::isDigit).take(8)
        mismatchErrorRes = null
      },
      showPin = showPin,
      onToggleShowPin = { showPin = !showPin },
      label = stringResource(R.string.secure_folder_confirm_pin),
      modifier = Modifier.padding(top = 12.dp),
    )

    val shownError = if (mismatchErrorRes != null) stringResource(mismatchErrorRes!!) else error
    if (shownError != null) {
      Text(
        shownError,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Button(
      onClick = {
        when {
          pin.length < 4 -> mismatchErrorRes = R.string.secure_folder_error_pin_min_digits
          pin != confirmPin -> mismatchErrorRes = R.string.secure_folder_error_pins_dont_match
          else -> onSubmit(pin)
        }
      },
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
      Text(stringResource(R.string.secure_folder_next))
    }
  }
}

@Composable
private fun SecurityQuestionAnswerContent(
  question: String,
  error: String?,
  onSubmit: (String) -> Boolean,
  onCancel: () -> Unit,
) {
  var answer by rememberSaveable { mutableStateOf("") }

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      Icons.RoundedFilled.HelpOutline,
      contentDescription = null,
      modifier = Modifier.size(48.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      stringResource(R.string.secure_folder_answer_security_question),
      style = MaterialTheme.typography.headlineSmall,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    Text(
      question.ifBlank { stringResource(R.string.secure_folder_no_question_set) },
      style = MaterialTheme.typography.bodyLarge,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(bottom = 24.dp),
    )

    OutlinedTextField(
      value = answer,
      onValueChange = { answer = it },
      label = { Text(stringResource(R.string.secure_folder_answer)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    if (error != null) {
      Text(
        error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Button(
      onClick = { onSubmit(answer) },
      enabled = answer.isNotBlank(),
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
      Text(stringResource(R.string.secure_folder_verify))
    }

    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
      Text(stringResource(R.string.generic_cancel))
    }
  }
}

/**
 * Single PIN entry field shared by the entry/setup screens, with a trailing eye toggle to
 * reveal/hide the digits (masked by default via [PasswordVisualTransformation]).
 */
@Composable
private fun PinField(
  value: String,
  onValueChange: (String) -> Unit,
  showPin: Boolean,
  onToggleShowPin: () -> Unit,
  modifier: Modifier = Modifier,
  label: String = stringResource(R.string.secure_folder_pin),
  onDone: () -> Unit = {},
) {
  OutlinedTextField(
    value = value,
    onValueChange = { onValueChange(it) },
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { onDone() }),
    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
    trailingIcon = {
      IconButton(onClick = onToggleShowPin) {
        Icon(
          if (showPin) Icons.RoundedFilled.VisibilityOff else Icons.RoundedFilled.Visibility,
          contentDescription = if (showPin) {
            stringResource(R.string.secure_folder_hide_pin)
          } else {
            stringResource(R.string.secure_folder_show_pin)
          },
        )
      }
    },
    modifier = modifier.fillMaxWidth(),
  )
}
