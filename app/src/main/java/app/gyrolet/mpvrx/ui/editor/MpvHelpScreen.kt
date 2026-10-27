/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.editor

import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.util.Date

@Serializable
data class MpvHelpScreen(
  val initialFilter: HelpEntryKind? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val documentation by MpvDocumentationRepository.state.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedKind by rememberSaveable { mutableStateOf<HelpEntryKind?>(initialFilter) }

    val allEntries = documentation.entries

    val filteredEntries by remember(searchQuery, selectedKind, allEntries) {
      derivedStateOf {
        val query = searchQuery.trim().lowercase()
        allEntries.filter { entry ->
          val kindMatch = selectedKind == null || entry.kind == selectedKind
          if (!kindMatch) return@filter false
          if (query.isEmpty()) return@filter true
          entry.name.lowercase().contains(query) ||
            entry.description.lowercase().contains(query) ||
            entry.signature.lowercase().contains(query) ||
            entry.kind.label
              .lowercase()
              .contains(query) ||
            entry.category.lowercase().contains(query)
        }
      }
    }

    val groupedEntries by remember(filteredEntries, searchQuery) {
      derivedStateOf {
        if (searchQuery.isNotBlank()) {
          listOf(null to filteredEntries)
        } else {
          filteredEntries.groupBy { it.category }
            .map { (name, entries) -> HelpCategory(name, entries) to entries }
        }
      }
    }

    fun copyToClipboard(entry: HelpEntry) {
      val text =
        when (entry.kind) {
          HelpEntryKind.OPTION -> "${entry.name}="
          HelpEntryKind.COMMAND -> entry.name
          HelpEntryKind.PROPERTY -> entry.name
          HelpEntryKind.JS_API -> entry.signature.substringBefore("(") + "()"
        }
      SafeClipboard.copyPlainText(context, "mpv_help", text, showToast = false)
      Toast.makeText(context, context.getString(R.string.toast_copied_value, text), Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
      MpvDocumentationRepository.ensureLoaded(context)
      focusRequester.requestFocus()
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text =
                androidx.compose.ui.res.stringResource(
                  app.gyrolet.mpvrx.R.string.ui_mpv_documentation,
                ),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
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
          actions = {
            IconButton(
              onClick = { MpvDocumentationRepository.ensureLoaded(context, forceRefresh = true) },
              enabled = !documentation.isRefreshing,
            ) {
              Icon(
                Icons.RoundedFilled.Refresh,
                contentDescription = "Refresh official MPV documentation",
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
            .padding(padding),
      ) {
        val sourceLabel =
          if (documentation.isRefreshing) {
            "Checking official mpv documentation…"
          } else if (documentation.lastCheckedMillis > 0L) {
            val date = remember(documentation.lastCheckedMillis) {
              DateFormat.getDateInstance(DateFormat.MEDIUM)
                .format(Date(documentation.lastCheckedMillis))
            }
            "Official mpv • $date"
          } else {
            "Official mpv • offline snapshot"
          }
        Text(
          text = documentation.error ?: sourceLabel,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        )
        app.gyrolet.mpvrx.ui.components.InlineSearchBar(
          query = searchQuery,
          onQueryChange = { searchQuery = it },
          onSearch = { keyboardController?.hide() },
          modifier = Modifier.padding(horizontal = 16.dp),
          inputFieldModifier = Modifier.focusRequester(focusRequester),
          windowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
          placeholder = {
            Text(
              text =
                androidx.compose.ui.res.stringResource(
                  app.gyrolet.mpvrx.R.string.ui_search_commands_options_properties,
                ),
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

        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = selectedKind == null,
            onClick = { selectedKind = null },
            label = {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.pref_all_sources),
              )
            },
            colors = filterChipColors(),
          )
          HelpEntryKind.entries.forEach { kind ->
            FilterChip(
              selected = selectedKind == kind,
              onClick = { selectedKind = if (selectedKind == kind) null else kind },
              label = { Text(kind.label) },
              colors = filterChipColors(),
            )
          }
        }

        HorizontalDivider(
          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )

        if (filteredEntries.isEmpty()) {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.RoundedFilled.Search,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
              )
              Spacer(modifier = Modifier.height(16.dp))
              Text(
                text =
                  if (searchQuery.isNotBlank()) {
                    context.getString(app.gyrolet.mpvrx.R.string.ui_no_results_found)
                  } else {
                    documentation.error ?: "Loading official mpv documentation…"
                  },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
              )
            }
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
          ) {
            groupedEntries.forEach { (category, entries) ->
              if (category != null) {
                item(key = "header:${category.name}") {
                  CategoryHeader(category.name)
                }
              }
              itemsIndexed(
                items = entries,
                key = { index, entry ->
                  "entry:${category?.name.orEmpty()}:$index:${entry.kind}:${entry.name}"
                },
                contentType = { _, _ -> "help_entry_card" },
              ) { _, entry ->
                HelpEntryCard(
                  entry = entry,
                  onCopy = { copyToClipboard(entry) },
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
private fun CategoryHeader(name: String) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = name,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HelpEntryCard(
  entry: HelpEntry,
  onCopy: () -> Unit,
) {
  var expanded by remember(entry.name, entry.kind) { mutableStateOf(false) }
  val colors = MaterialTheme.colorScheme
  val bgColor =
    if (entry.androidCompatible) {
      colors.surface
    } else {
      colors.errorContainer.copy(alpha = 0.15f)
    }

  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .combinedClickable(
          onClick = { expanded = !expanded },
          onLongClick = onCopy,
        ),
    color = bgColor,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = entry.name,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        KindBadge(entry.kind)
        if (!entry.androidCompatible) {
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = colors.error.copy(alpha = 0.12f),
          ) {
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_no_android),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = colors.error,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(2.dp))

      Text(
        text = entry.signature,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = colors.onSurfaceVariant.copy(alpha = 0.85f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )

      Spacer(modifier = Modifier.height(2.dp))

      Text(
        text = entry.description,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
      )

      if (!entry.androidCompatible && entry.androidNote != null) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "⚠ ${entry.androidNote}",
          style = MaterialTheme.typography.labelSmall,
          color = colors.error.copy(alpha = 0.8f),
        )
      }
    }
  }
  HorizontalDivider(
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
    modifier = Modifier.padding(start = 16.dp),
  )
}

@Composable
private fun KindBadge(kind: HelpEntryKind) {
  val (bg, fg) =
    when (kind) {
      HelpEntryKind.OPTION ->
        MaterialTheme.colorScheme.tertiaryContainer to
          MaterialTheme.colorScheme.onTertiaryContainer
      HelpEntryKind.COMMAND ->
        MaterialTheme.colorScheme.secondaryContainer to
          MaterialTheme.colorScheme.onSecondaryContainer
      HelpEntryKind.PROPERTY ->
        MaterialTheme.colorScheme.primaryContainer to
          MaterialTheme.colorScheme.onPrimaryContainer
      HelpEntryKind.JS_API -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
  Surface(
    shape = RoundedCornerShape(4.dp),
    color = bg,
  ) {
    Text(
      text =
        when (kind) {
          HelpEntryKind.OPTION -> "opt"
          HelpEntryKind.COMMAND -> "cmd"
          HelpEntryKind.PROPERTY -> "prop"
          HelpEntryKind.JS_API -> "js"
        },
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = fg,
      modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun filterChipColors() =
  FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
  )
