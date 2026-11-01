/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.repository.subtitle.translation.MlKitSubtitleTranslator
import app.gyrolet.mpvrx.repository.subtitle.translation.TranslationLanguages
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import app.gyrolet.mpvrx.ui.preferences.components.AppPreference as Preference

/** Lists ML Kit's offline languages; one tap downloads a model (or deletes a downloaded one). */
@Composable
fun OfflineTranslationModelsPreference() {
  val scope = rememberCoroutineScope()
  var downloaded by remember { mutableStateOf<Set<String>?>(null) }
  var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
  var error by remember { mutableStateOf<String?>(null) }
  var showDialog by remember { mutableStateOf(false) }

  suspend fun refresh() {
    downloaded = runCatching { MlKitSubtitleTranslator.downloadedLanguages() }.getOrDefault(emptySet())
  }
  LaunchedEffect(Unit) { refresh() }

  val languages =
    remember {
      TranslationLanguages.all
        .filter { MlKitSubtitleTranslator.mlKitLanguage(it.code) != null && it.code != "zh-TW" }
    }

  fun toggle(code: String) {
    if (code in busy) return
    val isDownloaded = downloaded?.contains(code) == true
    busy = busy + code
    error = null
    scope.launch {
      runCatching {
        if (isDownloaded) MlKitSubtitleTranslator.deleteLanguage(code) else MlKitSubtitleTranslator.downloadLanguage(code)
      }.onFailure { error = "${TranslationLanguages.displayName(code)}: ${it.message ?: "download failed"}" }
      refresh()
      busy = busy - code
    }
  }

  Preference(
    title = { Text(stringResource(R.string.pref_subtitle_translation_offline_models)) },
    summary = {
      val names = downloaded?.mapNotNull { code -> TranslationLanguages.find(code)?.name }?.sorted().orEmpty()
      Text(
        if (names.isEmpty()) {
          stringResource(R.string.pref_subtitle_translation_offline_models_none)
        } else {
          stringResource(R.string.pref_subtitle_translation_offline_models_ready, names.joinToString(", "))
        },
        color = MaterialTheme.colorScheme.outline,
      )
    },
    onClick = { showDialog = true },
  )

  if (showDialog) {
    AlertDialog(
      onDismissRequest = { showDialog = false },
      title = { Text(stringResource(R.string.pref_subtitle_translation_offline_models)) },
      text = {
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
          item {
            Text(
              error ?: stringResource(R.string.pref_subtitle_translation_offline_models_hint),
              style = MaterialTheme.typography.bodySmall,
              color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
              modifier = Modifier.padding(bottom = 8.dp),
            )
          }
          items(languages, key = { it.code }) { language ->
            val isDownloaded = downloaded?.contains(language.code) == true
            val isBusy = language.code in busy
            val isBuiltIn = language.code == "en"
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clickable(enabled = !isBusy && !isBuiltIn) { toggle(language.code) }
                  .padding(vertical = 12.dp, horizontal = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(language.name, modifier = Modifier.weight(1f))
              when {
                isBusy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                isBuiltIn -> Text(stringResource(R.string.pref_subtitle_translation_offline_builtin), color = MaterialTheme.colorScheme.outline)
                isDownloaded -> Text(stringResource(R.string.pref_subtitle_translation_offline_delete), color = MaterialTheme.colorScheme.error)
                else -> Text(stringResource(R.string.pref_subtitle_translation_offline_download), color = MaterialTheme.colorScheme.primary)
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.generic_ok)) }
      },
    )
  }
}
