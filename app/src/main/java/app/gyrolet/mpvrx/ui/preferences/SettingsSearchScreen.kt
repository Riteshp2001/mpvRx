/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.SecureFolderPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.LocalEmphasizedTypography
import app.gyrolet.mpvrx.ui.tv.isTelevision
import app.gyrolet.mpvrx.ui.tv.tvContentFocus
import app.gyrolet.mpvrx.ui.tv.tvFocusMemory
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable

@Serializable
object SettingsSearchScreen : ScrollableSettingsScreen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val resources = LocalResources.current
    val television = isTelevision()
    val backstack = LocalBackStack.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val emphasizedTypography = LocalEmphasizedTypography.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    val secureFolderPreferences = org.koin.compose.koinInject<SecureFolderPreferences>()
    val secureFolderHidden by secureFolderPreferences.isEntryPointHidden.collectAsState()

    val searchResults by remember(searchQuery, resources, secureFolderHidden, television) {
      derivedStateOf {
        SearchablePreferences.search(
          searchQuery,
          includeSecureFolder = !secureFolderHidden,
          television = television,
        ) { resId ->
          resources.getString(resId)
        }
      }
    }

    // Auto-focus the search field
    LaunchedEffect(Unit) {
      if (!television) focusRequester.requestFocus()
    }

    val preferenceStore = org.koin.compose.koinInject<app.gyrolet.mpvrx.preferences.preference.PreferenceStore>()
    val searchHistoryPref = remember { preferenceStore.getString("settings_search_history", "") }
    val searchHistoryRaw by searchHistoryPref.collectAsState()
    val searchHistory =
      remember(searchHistoryRaw) {
        if (searchHistoryRaw.isEmpty()) emptyList() else searchHistoryRaw.split("|")
      }

    fun clearSearchHistory() {
      searchHistoryPref.set("")
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.settings_search_title),
              style = emphasizedTypography.headlineSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              Icon(
                Icons.RoundedFilled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .tvContentFocus()
            .padding(padding),
      ) {
        InlineSearchBar(
          query = searchQuery,
          onQueryChange = { searchQuery = it },
          onSearch = { keyboardController?.hide() },
          modifier = Modifier.padding(horizontal = 16.dp),
          inputFieldModifier = Modifier.tvFocusMemory("settings-query").focusRequester(focusRequester),
          windowInsets = WindowInsets(0.dp),
          placeholder = {
            Text(
              text = stringResource(R.string.settings_search_hint),
              color = MaterialTheme.colorScheme.outline,
            )
          },
          leadingIcon = {
            Icon(
              imageVector = Icons.RoundedFilled.Search,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.outline,
            )
          },
          trailingIcon = {
            AnimatedVisibility(
              visible = searchQuery.isNotEmpty(),
              enter = fadeIn(),
              exit = fadeOut(),
            ) {
              IconButton(onClick = { searchQuery = "" }) {
                Icon(
                  imageVector = Icons.RoundedFilled.Clear,
                  contentDescription =
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.pref_clear_content_desc,
                    ),
                  tint = MaterialTheme.colorScheme.outline,
                )
              }
            }
          },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Results
        if (searchQuery.isBlank()) {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = settingsContentPadding(PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)),
          ) {
            if (searchHistory.isNotEmpty()) {
              item {
                Row(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .padding(bottom = 8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text =
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.ui_search_history,
                      ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                  IconButton(onClick = { clearSearchHistory() }) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Delete,
                      contentDescription =
                        androidx.compose.ui.res.stringResource(
                          app.gyrolet.mpvrx.R.string.ui_clear_history,
                        ),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
              item {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .padding(bottom = 24.dp),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  searchHistory.forEach { historyQuery ->
                    Surface(
                      onClick = { searchQuery = historyQuery },
                      shape = androidx.compose.foundation.shape.CircleShape,
                      color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                      Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Text(
                          text = historyQuery,
                          style = MaterialTheme.typography.labelLarge,
                          color = MaterialTheme.colorScheme.onSurface,
                        )
                      }
                    }
                  }
                }
              }
            }

            item {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp, top = if (searchHistory.isEmpty()) 0.dp else 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text =
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.ui_search_suggestions,
                    ),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                Icon(
                  imageVector = Icons.RoundedFilled.Search,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            item {
              val suggestions =
                listOf(
                  stringResource(R.string.pref_appearance_title),
                  stringResource(R.string.pref_gesture),
                  stringResource(R.string.pref_decoder_try_hw_dec_title),
                  stringResource(R.string.pref_subtitles),
                  stringResource(R.string.pref_folders_title),
                  stringResource(R.string.pref_audio),
                  stringResource(R.string.pref_video_background_playback_title),
                  stringResource(R.string.ui_yt_dlp_streaming).takeIf { app.gyrolet.mpvrx.BuildConfig.ENABLE_YTDLP },
                  stringResource(R.string.pref_advanced),
                ).filterNotNull()
              @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
              androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                suggestions.forEach { suggestion ->
                  Surface(
                    onClick = { searchQuery = suggestion },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                  ) {
                    Text(
                      text = suggestion,
                      modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                  }
                }
              }
            }
          }
        } else if (searchResults.isEmpty()) {
          // No results
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Settings,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.52f),
              )
              Text(
                text = stringResource(R.string.settings_search_no_results),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = settingsContentPadding(PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            itemsIndexed(
              items = searchResults,
              key = {
                index,
                result,
                ->
                "${result.preference.searchTargetKey}_${result.preference.screen}_$index"
              },
            ) { _, result ->
              SearchResultItem(
                result = result,
                onClick = {
                  keyboardController?.hide()
                  val currentQuery = searchQuery.trim()
                  if (currentQuery.isNotEmpty()) {
                    val currentHistory =
                      searchHistoryPref
                        .get()
                        .split(
                          "|",
                        ).filter { it.isNotEmpty() }
                        .toMutableList()
                    currentHistory.remove(currentQuery)
                    currentHistory.add(0, currentQuery)
                    if (currentHistory.size > 10) {
                      currentHistory.removeAt(currentHistory.lastIndex)
                    }
                    searchHistoryPref.set(currentHistory.joinToString("|"))
                  }
                  SettingsSearchNavigation.open(result.preference)
                  backstack.navigateTo(result.preference.screen)
                },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SearchResultItem(
  result: SettingsSearchResult,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val preference = result.preference
  val titleText =
    if (preference.titleRes != null) {
      stringResource(preference.titleRes)
    } else {
      preference.title ?: ""
    }
  val highlightedTitle =
    buildAnnotatedString {
      append(titleText)
      result.titleMatchIndices.sorted().forEach { index ->
        if (index in titleText.indices) {
          addStyle(
            SpanStyle(
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold,
            ),
            start = index,
            end = index + 1,
          )
        }
      }
    }

  val summaryText =
    if (preference.summaryRes != null) {
      stringResource(preference.summaryRes)
    } else {
      preference.summary
    }

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusMemory(preference.screen to preference.searchTargetKey)
        .tvFocusHighlight(MaterialTheme.shapes.largeIncreased)
        .clip(MaterialTheme.shapes.largeIncreased)
        .clickable(onClick = onClick),
    shape = MaterialTheme.shapes.largeIncreased,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 1.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
      ) {
        Box(
          modifier = Modifier.size(44.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp),
          )
        }
      }

      Spacer(modifier = Modifier.width(14.dp))

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = highlightedTitle,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        summaryText?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Surface(
          shape = MaterialTheme.shapes.extraSmall,
          color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
          Text(
            text = localizedSearchCategory(preference.category),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        }
      }
    }
  }
}

@Composable
private fun localizedSearchCategory(category: String): String = stringResource(searchCategoryTitleRes(category))

@androidx.annotation.StringRes
internal fun searchCategoryTitleRes(category: String): Int =
  when (category) {
    "About" -> R.string.pref_section_about
    "Advanced" -> R.string.search_category_advanced
    "AI" -> R.string.search_category_ai
    "Appearance" -> R.string.search_category_appearance
    "Audio" -> R.string.search_category_audio
    "Decoder" -> R.string.search_category_decoder
    "Codecs" -> R.string.pref_codecs_title
    "Folders" -> R.string.search_category_folders
    "Media Servers" -> R.string.pref_media_servers_title
    "Gestures" -> R.string.search_category_gestures
    "Network" -> R.string.ui_network
    "Player" -> R.string.search_category_player
    "Subtitles" -> R.string.search_category_subtitles
    else -> R.string.search_category_advanced
  }
