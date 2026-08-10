/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.securefolder

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ExposedTextDropDownMenu
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.replaceTop
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
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

    // Biometric authentication
    val biometricManager = BiometricManager.from(context)
    val canAuthenticateBiometric = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    val canAuthenticateDeviceCredential = biometricManager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    val isBiometricAvailable = canAuthenticateBiometric || canAuthenticateDeviceCredential
    val isBiometricEnabled = viewModel.isBiometricEnabled()

    fun showBiometricPrompt() {
      val fragmentActivity = context as? FragmentActivity ?: return
      val executor = ContextCompat.getMainExecutor(context)
      val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          super.onAuthenticationSucceeded(result)
          backstack.replaceTop(SecureFolderScreen)
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          super.onAuthenticationError(errorCode, errString)
        }
        override fun onAuthenticationFailed() {
          super.onAuthenticationFailed()
        }
      }
      val biometricPrompt = BiometricPrompt(fragmentActivity, executor, callback)
      val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(context.getString(R.string.secure_folder_title))
        .setSubtitle(context.getString(R.string.secure_folder_enter_pin))
      if (canAuthenticateBiometric) {
        promptInfoBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
      } else {
        promptInfoBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
      }
      biometricPrompt.authenticate(promptInfoBuilder.build())
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
      viewModel.refreshGateStep()
    }

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.secure_folder_title),
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onBackClick = { backstack.popSafely() },
          onCancelSelection = {},
        )
      },
    ) { padding ->
      Surface(
        modifier = Modifier.fillMaxSize().padding(padding),
        color = MaterialTheme.colorScheme.background,
      ) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            modifier =
              Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                      val ok = viewModel.verifyPin(pin)
                      if (ok) backstack.replaceTop(SecureFolderScreen)
                      ok
                    },
                    onForgotPin = { viewModel.startForgotPinFlow() },
                    isBiometricAvailable = isBiometricAvailable,
                    isBiometricEnabled = isBiometricEnabled,
                    canAuthenticateBiometric = canAuthenticateBiometric,
                    onBiometricClick = { showBiometricPrompt() },
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
  }
}

@Composable
private fun EnterPinContent(
  error: String?,
  onSubmit: (String) -> Boolean,
  onForgotPin: () -> Unit,
  isBiometricAvailable: Boolean,
  isBiometricEnabled: Boolean,
  canAuthenticateBiometric: Boolean,
  onBiometricClick: () -> Unit,
) {
  var pin by rememberSaveable { mutableStateOf("") }
  val shakeOffset = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  val focusRequester = remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current

  fun submit(): Boolean {
    if (pin.isEmpty()) return false
    val ok = onSubmit(pin)
    if (!ok) {
      pin = ""
      scope.launch {
        shakeOffset.animateTo(
          targetValue = 0f,
          animationSpec =
            keyframes {
              durationMillis = 400
              0f at 0
              -20f at 50
              20f at 100
              -16f at 150
              16f at 200
              -8f at 250
              8f at 300
              0f at 400
            },
        )
      }
    }
    return ok
  }

  // PIN is fixed at 4 digits now, so there's no ambiguity — verify the instant it's complete.
  LaunchedEffect(pin) {
    if (pin.length == 4) {
      submit()
    }
  }

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
    keyboardController?.show()
  }

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(modifier = Modifier.height(16.dp))

    // Header Icon
    Surface(
      modifier = Modifier.size(72.dp),
      shape = AppShapeScale.extraLarge,
      color = MaterialTheme.colorScheme.primaryContainer,
      tonalElevation = 2.dp,
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Lock,
          contentDescription = null,
          modifier = Modifier.size(36.dp),
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = stringResource(R.string.secure_folder_enter_pin),
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(36.dp))

    // Dots are the visible UI; the actual text field is invisible and only exists to drive the
    // system keyboard, so tapping anywhere here (re)focuses it.
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier
          .fillMaxWidth()
          .height(32.dp)
          .offset { IntOffset(shakeOffset.value.toInt(), 0) },
    ) {
      PinDots(filledCount = pin.length)

      BasicTextField(
        value = pin,
        onValueChange = { pin = it.filter(Char::isDigit).take(4) },
        modifier =
          Modifier
            .matchParentSize()
            .focusRequester(focusRequester)
            .alpha(0f),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
      )
    }

    if (error != null) {
      Text(
        text = error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 12.dp),
      )
    }

    Spacer(modifier = Modifier.height(36.dp))

    if (isBiometricAvailable && isBiometricEnabled) {
      OutlinedButton(
        onClick = onBiometricClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = AppShapeScale.large,
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Fingerprint,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text =
            if (canAuthenticateBiometric) {
              stringResource(R.string.secure_folder_use_fingerprint)
            } else {
              stringResource(R.string.secure_folder_use_face_unlock)
            },
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
      }

      Spacer(modifier = Modifier.height(8.dp))
    }

    TextButton(onClick = onForgotPin) {
      Text(
        text = stringResource(R.string.secure_folder_forgot_pin),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
      )
    }
  }
}

/** Row of 4 dot placeholders — filled solid as each digit of the (fixed 4-digit) PIN is typed. */
@Composable
private fun PinDots(
  filledCount: Int,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(20.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(4) { index ->
      val filled = index < filledCount
      Box(
        modifier =
          Modifier
            .size(16.dp)
            .background(
              color = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
              shape = CircleShape,
            )
            .border(
              width = 1.5.dp,
              color = MaterialTheme.colorScheme.outline,
              shape = CircleShape,
            ),
      )
    }
  }
}

/**
 * First-time setup, all in one step: PIN + confirm PIN + security question (preset dropdown) +
 * answer. Everything is validated and persisted together in a single call.
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

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(modifier = Modifier.height(16.dp))

    Surface(
      modifier = Modifier.size(72.dp),
      shape = AppShapeScale.extraLarge,
      color = MaterialTheme.colorScheme.primaryContainer,
      tonalElevation = 2.dp,
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Security,
          contentDescription = null,
          modifier = Modifier.size(36.dp),
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = stringResource(R.string.secure_folder_setup_title),
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = stringResource(R.string.secure_folder_setup_subtitle),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(28.dp))

    PinField(
      value = pin,
      onValueChange = {
        pin = it.filter(Char::isDigit).take(4)
        validationErrorRes = null
      },
      showPin = showPin,
      onToggleShowPin = { showPin = !showPin },
      label = stringResource(R.string.secure_folder_new_pin),
    )

    PinField(
      value = confirmPin,
      onValueChange = {
        confirmPin = it.filter(Char::isDigit).take(4)
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
      modifier = Modifier.padding(top = 16.dp),
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
        text = shownError,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Spacer(modifier = Modifier.height(28.dp))

    Button(
      onClick = {
        when {
          pin.length < 4 -> validationErrorRes = R.string.secure_folder_error_pin_min_digits
          pin != confirmPin -> validationErrorRes = R.string.secure_folder_error_pins_dont_match
          answer.isBlank() -> validationErrorRes = R.string.secure_folder_error_answer_question
          else -> onSubmit(pin, question, answer)
        }
      },
      modifier = Modifier.fillMaxWidth().height(54.dp),
      shape = AppShapeScale.large,
    ) {
      Text(
        text = stringResource(R.string.secure_folder_finish_setup),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
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
        .padding(vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(modifier = Modifier.height(16.dp))

    Surface(
      modifier = Modifier.size(72.dp),
      shape = AppShapeScale.extraLarge,
      color = MaterialTheme.colorScheme.primaryContainer,
      tonalElevation = 2.dp,
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Security,
          contentDescription = null,
          modifier = Modifier.size(36.dp),
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = title,
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = subtitle,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(28.dp))

    PinField(
      value = pin,
      onValueChange = {
        pin = it.filter(Char::isDigit).take(4)
        mismatchErrorRes = null
      },
      showPin = showPin,
      onToggleShowPin = { showPin = !showPin },
      label = stringResource(R.string.secure_folder_new_pin),
    )

    PinField(
      value = confirmPin,
      onValueChange = {
        confirmPin = it.filter(Char::isDigit).take(4)
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
        text = shownError,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Spacer(modifier = Modifier.height(28.dp))

    Button(
      onClick = {
        when {
          pin.length < 4 -> mismatchErrorRes = R.string.secure_folder_error_pin_min_digits
          pin != confirmPin -> mismatchErrorRes = R.string.secure_folder_error_pins_dont_match
          else -> onSubmit(pin)
        }
      },
      modifier = Modifier.fillMaxWidth().height(54.dp),
      shape = AppShapeScale.large,
    ) {
      Text(
        text = stringResource(R.string.secure_folder_next),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
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
        .padding(vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(modifier = Modifier.height(16.dp))

    Surface(
      modifier = Modifier.size(72.dp),
      shape = AppShapeScale.extraLarge,
      color = MaterialTheme.colorScheme.primaryContainer,
      tonalElevation = 2.dp,
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.HelpOutline,
          contentDescription = null,
          modifier = Modifier.size(36.dp),
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = stringResource(R.string.secure_folder_answer_security_question),
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = question.ifBlank { stringResource(R.string.secure_folder_no_question_set) },
      style = MaterialTheme.typography.bodyLarge,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(28.dp))

    OutlinedTextField(
      value = answer,
      onValueChange = { answer = it },
      label = { Text(stringResource(R.string.secure_folder_answer)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    if (error != null) {
      Text(
        text = error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Spacer(modifier = Modifier.height(28.dp))

    Button(
      onClick = { onSubmit(answer) },
      enabled = answer.isNotBlank(),
      modifier = Modifier.fillMaxWidth().height(54.dp),
      shape = AppShapeScale.large,
    ) {
      Text(
        text = stringResource(R.string.secure_folder_verify),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedButton(
      onClick = onCancel,
      modifier = Modifier.fillMaxWidth().height(54.dp),
      shape = AppShapeScale.large,
    ) {
      Text(
        text = stringResource(R.string.generic_cancel),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

/**
 * Single PIN entry field shared by the entry/setup screens, with a trailing eye toggle to
 * reveal/hide the digits (masked by default via [PasswordVisualTransformation]).
 */
@Composable
internal fun PinField(
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


