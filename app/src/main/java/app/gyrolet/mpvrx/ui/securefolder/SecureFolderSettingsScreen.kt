/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.securefolder

import app.gyrolet.mpvrx.ui.preferences.DevicePreference
import app.gyrolet.mpvrx.ui.tv.tvContentFocus

import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.ui.preferences.settingsSearchTarget
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.PreferenceCard
import app.gyrolet.mpvrx.ui.preferences.PreferenceDivider
import app.gyrolet.mpvrx.ui.preferences.PreferenceSectionHeader
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable

/**
 * Settings for the Secure Folder: biometric unlock, PIN / security-question management, hiding
 * this screen's own entry point from Preferences, and destroying the whole Secure Folder.
 *
 * Reachable from Preferences, or — once hidden — from the settings icon on the PIN gate.
 */
@Serializable
data object SecureFolderSettingsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val viewModel: SecureFolderViewModel =
      viewModel(factory = SecureFolderViewModel.factory(context.applicationContext as android.app.Application))
    val preferences = viewModel.preferences

    val isBiometricEnabled by preferences.isBiometricEnabled.collectAsState()
    val isEntryPointHidden by preferences.isEntryPointHidden.collectAsState()

    var changePinOpen by remember { mutableStateOf(false) }
    var changeSecurityQuestionOpen by remember { mutableStateOf(false) }
    var forgotPinOpen by remember { mutableStateOf(false) }
    var hideConfirmOpen by remember { mutableStateOf(false) }
    var biometricPinOpen by remember { mutableStateOf(false) }

    val pinChangedMessage = stringResource(R.string.secure_folder_pin_changed)
    val securityQuestionChangedMessage = stringResource(R.string.secure_folder_security_question_changed)
    val setUpFirstMessage = stringResource(R.string.secure_folder_settings_setup_first)
    val destroyedMessage = stringResource(R.string.secure_folder_destroyed)
    val destroyFailedMessage = stringResource(R.string.secure_folder_destroy_failed)
    val noScreenLockMessage = stringResource(R.string.secure_folder_destroy_no_screen_lock)

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // Every PIN-related action needs a PIN to exist first; before first-time setup there is nothing to reset.
    fun requirePin(action: () -> Unit) {
      if (preferences.isPinSet()) action() else toast(setUpFirstMessage)
    }

    val requestDestroy =
      rememberDeviceCredentialPrompt(
        title = stringResource(R.string.secure_folder_destroy_title),
        subtitle = stringResource(R.string.secure_folder_destroy_summary),
        onUnavailable = { toast(noScreenLockMessage) },
        onSuccess = {
          viewModel.destroySecureFolder { success ->
            if (success) {
              toast(destroyedMessage)
              backstack.popSafely()
            } else {
              toast(destroyFailedMessage)
            }
          }
        },
      )

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.secure_folder_settings_title),
          colors = TopAppBarDefaults.topAppBarColors(),
          forceHeadlineSmall = true,
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onBackClick = { backstack.popSafely() },
          onCancelSelection = {},
        )
      },
    ) { padding ->
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .tvContentFocus()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
      ) {
        PreferenceSectionHeader(
          modifier = Modifier.settingsSearchTarget(R.string.secure_folder_settings_title),
          title = stringResource(R.string.secure_folder_settings_security),
          topPadding = 8.dp,
        )
        PreferenceCard {
          DevicePreference(R.string.secure_folder_biometric_title) {
            SwitchPreference(
              modifier = Modifier.settingsSearchTarget(R.string.secure_folder_biometric_title),
              value = isBiometricEnabled,
              onValueChange = { enable ->
                // Turning biometric unlock on needs the Secure Folder PIN; turning it off is instant.
                if (enable) requirePin { biometricPinOpen = true } else viewModel.setBiometricEnabled(false)
              },
              title = { Text(stringResource(R.string.secure_folder_biometric_title)) },
              summary = { Text(stringResource(R.string.secure_folder_biometric_summary)) },
              icon = { SettingsIcon(Icons.RoundedFilled.Fingerprint) },
            )
            PreferenceDivider()
          }
          SecureSettingsRow(
            modifier = Modifier.settingsSearchTarget(R.string.secure_folder_reset_pin),
            icon = Icons.RoundedFilled.Lock,
            title = stringResource(R.string.secure_folder_reset_pin),
            summary = stringResource(R.string.secure_folder_reset_pin_summary),
            onClick = { requirePin { changePinOpen = true } },
          )
          PreferenceDivider()
          SecureSettingsRow(
            modifier = Modifier.settingsSearchTarget(R.string.secure_folder_reset_security_question),
            icon = Icons.RoundedFilled.HelpOutline,
            title = stringResource(R.string.secure_folder_reset_security_question),
            summary = stringResource(R.string.secure_folder_reset_security_question_summary),
            onClick = { requirePin { changeSecurityQuestionOpen = true } },
          )
          PreferenceDivider()
          SecureSettingsRow(
            modifier = Modifier.settingsSearchTarget(R.string.secure_folder_forgot_pin_title),
            icon = Icons.RoundedFilled.Lock,
            title = stringResource(R.string.secure_folder_forgot_pin_title),
            summary = stringResource(R.string.secure_folder_forgot_pin_summary),
            onClick = { requirePin { forgotPinOpen = true } },
          )
          PreferenceDivider()
          SecureSettingsRow(
            modifier = Modifier.settingsSearchTarget(R.string.secure_folder_hide_settings),
            icon = if (isEntryPointHidden) Icons.RoundedFilled.Visibility else Icons.RoundedFilled.VisibilityOff,
            title =
              stringResource(
                if (isEntryPointHidden) R.string.secure_folder_unhide_settings else R.string.secure_folder_hide_settings,
              ),
            summary =
              stringResource(
                if (isEntryPointHidden) {
                  R.string.secure_folder_unhide_settings_summary
                } else {
                  R.string.secure_folder_hide_settings_summary
                },
              ),
            onClick = {
              // Unhiding is harmless and instant; only hiding needs the confirmation that explains how to get back.
              if (isEntryPointHidden) viewModel.setEntryPointHidden(false) else hideConfirmOpen = true
            },
          )
        }

        PreferenceSectionHeader(title = stringResource(R.string.secure_folder_settings_danger_zone))
        PreferenceCard {
          SecureSettingsRow(
            modifier = Modifier.settingsSearchTarget(R.string.secure_folder_destroy_title),
            icon = Icons.RoundedFilled.Delete,
            title = stringResource(R.string.secure_folder_destroy_title),
            summary = stringResource(R.string.secure_folder_destroy_summary),
            onClick = requestDestroy,
            titleColor = MaterialTheme.colorScheme.error,
            iconTint = MaterialTheme.colorScheme.error,
          )
        }

        PreferenceSectionHeader(title = stringResource(R.string.secure_folder_settings_help))
        PreferenceCard {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
          ) {
            Box(modifier = Modifier.padding(end = 16.dp)) { SettingsIcon(Icons.RoundedFilled.Info) }
            Column {
              Text(
                text = stringResource(R.string.secure_folder_title),
                style = MaterialTheme.typography.bodyLarge,
              )
              Text(
                text = stringResource(R.string.secure_folder_settings_help_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }

    if (hideConfirmOpen) {
      ConfirmDialog(
        title = stringResource(R.string.secure_folder_hide_entry_title),
        subtitle = stringResource(R.string.secure_folder_hide_entry_subtitle),
        confirmText = stringResource(R.string.secure_folder_proceed),
        onConfirm = {
          viewModel.setEntryPointHidden(true)
          hideConfirmOpen = false
        },
        onCancel = { hideConfirmOpen = false },
      )
    }

    ChangePinDialog(
      isOpen = changePinOpen,
      preferences = preferences,
      onDismiss = { changePinOpen = false },
      onChanged = { toast(pinChangedMessage) },
    )

    ChangeSecurityQuestionDialog(
      isOpen = changeSecurityQuestionOpen,
      preferences = preferences,
      onDismiss = { changeSecurityQuestionOpen = false },
      onChanged = { toast(securityQuestionChangedMessage) },
    )

    VerifyPinDialog(
      isOpen = biometricPinOpen,
      title = stringResource(R.string.secure_folder_biometric_enable_confirm),
      preferences = preferences,
      onDismiss = { biometricPinOpen = false },
      onVerified = { viewModel.setBiometricEnabled(true) },
    )

    ForgotPinDialog(
      isOpen = forgotPinOpen,
      preferences = preferences,
      onDismiss = { forgotPinOpen = false },
      onChanged = { toast(pinChangedMessage) },
    )
  }
}

@Composable
private fun SettingsIcon(
  icon: AppIcon,
  tint: Color = MaterialTheme.colorScheme.primary,
) {
  Icon(
    imageVector = icon,
    contentDescription = null,
    modifier = Modifier.size(24.dp),
    tint = tint,
  )
}

@Composable
private fun SecureSettingsRow(
  icon: AppIcon,
  title: String,
  summary: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  titleColor: Color = MaterialTheme.colorScheme.onSurface,
  iconTint: Color = MaterialTheme.colorScheme.primary,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.padding(end = 16.dp)) { SettingsIcon(icon, iconTint) }
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
      Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
