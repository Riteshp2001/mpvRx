/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.lyrics.LyricsLanguageOptions
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.LyricsTranslationDisplayMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive
import app.gyrolet.mpvrx.ui.liquidglass.LocalPlayerGlassSurfaceActive
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.theme.fontFamilyForText
import org.koin.compose.koinInject

@Composable
fun LyricsTranslateDialog(
  viewModel: PlayerViewModel,
  onDismiss: () -> Unit,
) {
  val audioPreferences = koinInject<AudioPreferences>()
  val displayMode by audioPreferences.lyricsTranslationDisplayMode.collectAsState()
  val state by viewModel.lyricsUiState.collectAsState()
  var searchQuery by rememberSaveable { mutableStateOf("") }

  val filteredLanguages = remember(searchQuery) {
    if (searchQuery.isBlank()) {
      LyricsLanguageOptions.ALL_LANGUAGES
    } else {
      LyricsLanguageOptions.ALL_LANGUAGES.filter {
        it.displayName.contains(searchQuery, ignoreCase = true) || it.code.contains(searchQuery, ignoreCase = true)
      }
    }
  }

  // Opt into the shared PlayerSheet so the translate picker gets the same liquid glass
  // material as the other player sheets when liquid glass is turned on. Liquid glass
  // already blurs and dims the backdrop itself, so the sheet keeps the background fully
  // visible instead of stacking the standard black scrim on top of it.
  val scrimAlpha = if (liquidGlassActive()) 0f else 0.5f

  PlayerSheet(
    onDismissRequest = onDismiss,
    title = stringResource(R.string.lyrics_translate_title),
    scrimAlpha = scrimAlpha,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 8.dp),
    ) {
      if (state.isTranslating) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = stringResource(R.string.lyrics_translating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.height(10.dp))
      }

      // Option to toggle off (original lyrics)
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .clickable {
            viewModel.showOriginalLyrics()
            onDismiss()
          },
        color =
          if (LocalPlayerGlassSurfaceActive.current) {
            playerSheetSelectionColor(!state.isTranslationActive)
          } else {
            if (!state.isTranslationActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
          },
        shape = RoundedCornerShape(12.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = !state.isTranslationActive,
            onClick = null,
          )
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = stringResource(R.string.lyrics_translation_off),
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.Bold,
              color = if (!state.isTranslationActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = stringResource(R.string.lyrics_translation_off_summary),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.outline,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Display Mode Selector (Dual-Line vs Replace)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilterChip(
          selected = displayMode == LyricsTranslationDisplayMode.DualLine,
          onClick = { audioPreferences.lyricsTranslationDisplayMode.set(LyricsTranslationDisplayMode.DualLine) },
          label = {
            Text(
              stringResource(R.string.lyrics_translation_dual_line),
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
            )
          },
          modifier = Modifier.weight(1f),
          colors = displayModeChipColors(),
        )
        FilterChip(
          selected = displayMode == LyricsTranslationDisplayMode.Replace,
          onClick = { audioPreferences.lyricsTranslationDisplayMode.set(LyricsTranslationDisplayMode.Replace) },
          label = {
            Text(
              stringResource(R.string.lyrics_translation_replace),
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
            )
          },
          modifier = Modifier.weight(1f),
          colors = displayModeChipColors(),
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      PlayerSheetSearchField(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        placeholder = stringResource(R.string.ui_search_language),
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Language List
      if (filteredLanguages.isEmpty()) {
        Text(
          text = stringResource(R.string.lyrics_translation_no_languages),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        )
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
          contentPadding = PaddingValues(vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(filteredLanguages, key = { it.code }) { lang ->
            val isSelected = state.isTranslationActive && state.targetLanguage.equals(lang.code, ignoreCase = true)
            val frosted = LocalPlayerGlassSurfaceActive.current
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  audioPreferences.lyricsTargetLanguage.set(lang.code)
                  viewModel.translateLyrics(lang.code)
                  onDismiss()
                }
                .background(
                  when {
                    frosted -> playerSheetSelectionColor(isSelected)
                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else -> Color.Transparent
                  },
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = isSelected,
                onClick = null,
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = lang.displayName,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                  fontFamily = fontFamilyForText(lang.displayName),
                  color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                if (lang.isRomanization) {
                  Text(
                    text = lang.subtitle ?: stringResource(R.string.lyrics_translation_romanized),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/** Chips read from the same frosted selection palette as the other player sheet rows. */
@Composable
private fun displayModeChipColors() =
  if (LocalPlayerGlassSurfaceActive.current) {
    FilterChipDefaults.filterChipColors(
      containerColor = playerSheetSelectionColor(false),
      selectedContainerColor = playerSheetSelectionColor(true),
      selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
  } else {
    FilterChipDefaults.filterChipColors(
      selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
      selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
