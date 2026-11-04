/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.securefolder

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Returns a function that asks for the phone's own lock-screen credential (PIN / pattern /
 * password) and calls [onSuccess] only if it is entered correctly.
 *
 * Uses [BiometricPrompt] with DEVICE_CREDENTIAL on Android 11+, where that combination is
 * supported on its own, and the system confirm-credential screen on older versions.
 * [onUnavailable] fires when the device has no screen lock to verify against.
 */
@Composable
fun rememberDeviceCredentialPrompt(
  title: String,
  subtitle: String,
  onUnavailable: () -> Unit,
  onSuccess: () -> Unit,
): () -> Unit {
  val context = LocalContext.current
  val currentOnSuccess by rememberUpdatedState(onSuccess)
  val currentOnUnavailable by rememberUpdatedState(onUnavailable)

  val legacyLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) currentOnSuccess()
    }

  return remember(context, title, subtitle, legacyLauncher) {
    {
      val activity = context as? FragmentActivity
      when {
        !isDeviceSecure(context) -> currentOnUnavailable()
        activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
          val callback =
            object : BiometricPrompt.AuthenticationCallback() {
              override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                currentOnSuccess()
              }
            }
          val info =
            BiometricPrompt.PromptInfo
              .Builder()
              .setTitle(title)
              .setSubtitle(subtitle)
              .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
              .build()
          BiometricPrompt(activity, ContextCompat.getMainExecutor(context), callback).authenticate(info)
        }
        else -> {
          val intent = legacyCredentialIntent(context, title, subtitle)
          if (intent != null) legacyLauncher.launch(intent) else currentOnUnavailable()
        }
      }
    }
  }
}

private fun isDeviceSecure(context: Context): Boolean =
  context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

// DEVICE_CREDENTIAL alone is unsupported by BiometricPrompt before Android 11.
// Keep the system credential fallback for those devices and non-FragmentActivity hosts.
@Suppress("DEPRECATION")
private fun legacyCredentialIntent(context: Context, title: String, subtitle: String): Intent? =
  context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, subtitle)
