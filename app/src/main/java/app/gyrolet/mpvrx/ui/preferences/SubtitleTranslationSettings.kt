/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.SubtitleTranslationPreferences
import app.gyrolet.mpvrx.preferences.SubtitleTranslationProvider
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.repository.subtitle.translation.SubtitleTranslationService
import app.gyrolet.mpvrx.repository.subtitle.translation.TranslationLanguages
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.presentation.components.AppTextField as TextField
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference as ListPreference
import app.gyrolet.mpvrx.ui.preferences.components.AppPreference as Preference
import app.gyrolet.mpvrx.ui.preferences.components.AppTextFieldPreference as TextFieldPreference

/** Provider choice and provider-specific settings for subtitle translation. */
@Composable
fun SubtitleTranslationSettingsCard() {
  val preferences = koinInject<SubtitleTranslationPreferences>()
  val service = koinInject<SubtitleTranslationService>()
  val scope = rememberCoroutineScope()

  val enabled by preferences.enabled.collectAsState()
  val provider by preferences.provider.collectAsState()
  val libreUrl by preferences.libreTranslateUrl.collectAsState()
  val libreKey by preferences.libreTranslateApiKey.collectAsState()
  val myMemoryEmail by preferences.myMemoryEmail.collectAsState()
  val rtlFix by preferences.rtlFix.collectAsState()
  val targetLanguage by preferences.targetLanguage.collectAsState()
  val autoTranslate by preferences.autoTranslate.collectAsState()
  var testResult by remember { mutableStateOf<String?>(null) }
  var testing by remember { mutableStateOf(false) }

  PreferenceCard {
    SwitchPreference(
      value = enabled,
      onValueChange = preferences.enabled::set,
      title = { Text(stringResource(R.string.pref_subtitle_translation_enabled)) },
      summary = {
        Text(stringResource(R.string.pref_subtitle_translation_enabled_summary), color = MaterialTheme.colorScheme.outline)
      },
    )

    if (enabled) {
      PreferenceDivider()
      ExpressivePreferenceItem {
        ListPreference(
          value = provider,
          onValueChange = preferences.provider::set,
          values = SubtitleTranslationProvider.entries,
          valueToText = { AnnotatedString("${it.displayName}\n${it.summary}") },
          title = { Text(stringResource(R.string.pref_subtitle_translation_provider)) },
          summary = {
            Column {
              Text(provider.displayName, color = MaterialTheme.colorScheme.primary)
              Text(provider.summary, color = MaterialTheme.colorScheme.outline)
            }
          },
        )
      }

      PreferenceDivider()
      ExpressivePreferenceItem {
        ListPreference(
          value = TranslationLanguages.find(targetLanguage) ?: TranslationLanguages.all.first(),
          onValueChange = { preferences.targetLanguage.set(it.code) },
          values = TranslationLanguages.all,
          valueToText = { AnnotatedString(it.name) },
          title = { Text(stringResource(R.string.pref_subtitle_translation_target)) },
          summary = {
            Text(TranslationLanguages.displayName(targetLanguage), color = MaterialTheme.colorScheme.outline)
          },
        )
      }
      PreferenceDivider()
      SwitchPreference(
        value = autoTranslate,
        onValueChange = preferences.autoTranslate::set,
        title = { Text(stringResource(R.string.pref_subtitle_translation_auto)) },
        summary = {
          Text(
            stringResource(R.string.pref_subtitle_translation_auto_summary, TranslationLanguages.displayName(targetLanguage)),
            color = MaterialTheme.colorScheme.outline,
          )
        },
      )

      when (provider) {
        SubtitleTranslationProvider.LIBRETRANSLATE -> {
          PreferenceDivider()
          ExpressivePreferenceItem {
            TextFieldPreference(
              value = libreUrl,
              onValueChange = {
                preferences.libreTranslateUrl.set(it)
                testResult = null
              },
              textToValue = { it.trim() },
              title = { Text(stringResource(R.string.pref_subtitle_translation_libre_url)) },
              summary = {
                Text(
                  libreUrl.ifBlank { stringResource(R.string.pref_subtitle_translation_libre_url_hint) },
                  color = if (libreUrl.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
              },
              textField = { value, onValueChange, _ ->
                TextField(
                  value = value,
                  onValueChange = onValueChange,
                  modifier = Modifier.fillMaxWidth(),
                  placeholder = { Text("https://translate.example.com") },
                  singleLine = true,
                )
              },
            )
          }
          PreferenceDivider()
          ExpressivePreferenceItem {
            TextFieldPreference(
              value = libreKey,
              onValueChange = {
                preferences.libreTranslateApiKey.set(it)
                testResult = null
              },
              textToValue = { it.trim() },
              title = { Text(stringResource(R.string.pref_subtitle_translation_libre_key)) },
              summary = {
                Text(
                  if (libreKey.isBlank()) stringResource(R.string.pref_subtitle_translation_libre_key_none) else stringResource(R.string.pref_api_key_saved),
                  color = MaterialTheme.colorScheme.outline,
                )
              },
              textField = { value, onValueChange, _ ->
                TextField(
                  value = value,
                  onValueChange = onValueChange,
                  modifier = Modifier.fillMaxWidth(),
                  singleLine = true,
                  visualTransformation = PasswordVisualTransformation(),
                )
              },
            )
          }
          PreferenceDivider()
          ExpressivePreferenceItem {
            Preference(
              title = { Text(stringResource(R.string.pref_subtitle_translation_test)) },
              enabled = libreUrl.isNotBlank() && !testing,
              summary = testResult?.let { result -> { Text(result, color = MaterialTheme.colorScheme.outline) } },
              onClick = {
                testing = true
                scope.launch {
                  testResult =
                    service.testLibreTranslate(libreUrl, libreKey).fold(
                      onSuccess = { count -> "Connected · $count languages available" },
                      onFailure = { error -> "Failed: ${error.message}" },
                    )
                  testing = false
                }
              },
            )
          }
        }

        SubtitleTranslationProvider.MYMEMORY -> {
          PreferenceDivider()
          ExpressivePreferenceItem {
            TextFieldPreference(
              value = myMemoryEmail,
              onValueChange = preferences.myMemoryEmail::set,
              textToValue = { it.trim() },
              title = { Text(stringResource(R.string.pref_subtitle_translation_mymemory_email)) },
              summary = {
                Text(
                  myMemoryEmail.ifBlank { stringResource(R.string.pref_subtitle_translation_mymemory_email_summary) },
                  color = MaterialTheme.colorScheme.outline,
                )
              },
              textField = { value, onValueChange, _ ->
                TextField(
                  value = value,
                  onValueChange = onValueChange,
                  modifier = Modifier.fillMaxWidth(),
                  placeholder = { Text("you@example.com") },
                  singleLine = true,
                )
              },
            )
          }
        }

        SubtitleTranslationProvider.ON_DEVICE -> {
          PreferenceDivider()
          ExpressivePreferenceItem { OfflineTranslationModelsPreference() }
        }

        SubtitleTranslationProvider.AI -> Unit
      }

      PreferenceDivider()
      SwitchPreference(
        value = rtlFix,
        onValueChange = preferences.rtlFix::set,
        title = { Text(stringResource(R.string.pref_subtitle_translation_rtl)) },
        summary = {
          Text(stringResource(R.string.pref_subtitle_translation_rtl_summary), color = MaterialTheme.colorScheme.outline)
        },
      )

      Text(
        stringResource(
          if (provider == SubtitleTranslationProvider.ON_DEVICE) {
            R.string.pref_subtitle_translation_privacy_offline
          } else {
            R.string.pref_subtitle_translation_privacy_online
          },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )
    }
  }
}
