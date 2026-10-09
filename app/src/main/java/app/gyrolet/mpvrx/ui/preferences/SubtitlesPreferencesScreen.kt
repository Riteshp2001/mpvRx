/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.fonts.GoogleFontsRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.DEFAULT_SUBTITLE_FONT_FAMILY
import app.gyrolet.mpvrx.preferences.LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchMode
import app.gyrolet.mpvrx.repository.subtitlehub.MpvRxSubtitleHubSources
import app.gyrolet.mpvrx.repository.wyzie.WyzieEncodings
import app.gyrolet.mpvrx.repository.wyzie.WyzieFormats
import app.gyrolet.mpvrx.repository.wyzie.WyzieLanguages
import app.gyrolet.mpvrx.repository.wyzie.WyzieSearchRepository
import app.gyrolet.mpvrx.repository.wyzie.WyzieSourceItem
import app.gyrolet.mpvrx.repository.wyzie.WyzieSources
import app.gyrolet.mpvrx.repository.wyzie.WyzieSourcesResponse
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.player.defaultSubtitleFontDisplayName
import app.gyrolet.mpvrx.ui.player.resolveSubtitleFontFamily
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.copyFontsFromDirectory
import app.gyrolet.mpvrx.utils.media.loadCustomFontEntries
import app.gyrolet.mpvrx.utils.media.resolveSubtitleStorageDirectory
import com.github.k1rakishou.fsaf.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.TextFieldPreference
import org.koin.compose.koinInject

@Serializable
object SubtitlesPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val preferences = koinInject<SubtitlesPreferences>()
    val appearancePreferences = koinInject<AppearancePreferences>()
    val googleFontsRepository = koinInject<GoogleFontsRepository>()
    val configOwnedOptions = currentMpvConfigOverrideOptions()
    val fileManager = koinInject<FileManager>()
    val wyzieSearchRepository = koinInject<WyzieSearchRepository>()
    val scope = rememberCoroutineScope()
    var fontRefreshKey by remember { mutableStateOf(0) }

    fun reloadFontsFrom(uriString: String) {
      if (uriString.isBlank()) return
      scope.launch(Dispatchers.IO) {
        val copiedFonts = copyFontsFromDirectory(context, fileManager, uriString)
        withContext(Dispatchers.Main) {
          fontRefreshKey++
          Toast
            .makeText(
              context,
              if (copiedFonts > 0) {
                context.getString(R.string.fonts_loaded, copiedFonts)
              } else {
                context.getString(R.string.pref_subtitles_font_no_custom)
              },
              Toast.LENGTH_SHORT,
            ).show()
        }
      }
    }

    val fontFolderPicker =
      rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
      ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }.onFailure { error ->
          Toast
            .makeText(
              context,
              context.getString(R.string.pref_subtitles_font_copy_failed, error.message ?: "Unknown error"),
              Toast.LENGTH_SHORT,
            ).show()
        }

        val uriString = uri.toString()
        preferences.fontsFolder.set(uriString)
        reloadFontsFrom(uriString)
      }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_subtitles),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(
                onClick = { backstack.popSafely() },
              ) {
                Icon(
                  Icons.RoundedFilled.ArrowBack,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        val subtitleSaveFolder by preferences.subtitleSaveFolder.collectAsState()
        val fontsFolder by preferences.fontsFolder.collectAsState()
        val selectedFont by preferences.font.collectAsState()
        val appFontFamily by appearancePreferences.googleFontFamily.collectAsState()
        val wyzieHearingImpaired by preferences.wyzieHearingImpaired.collectAsState()
        val wyzieSources by preferences.wyzieSources.collectAsState()
        val wyzieFormats by preferences.wyzieFormats.collectAsState()
        val wyzieEncodings by preferences.wyzieEncodings.collectAsState()
        val wyzieApiKey by preferences.wyzieApiKey.collectAsState()
        val betaSeriesApiKey by preferences.betaSeriesApiKey.collectAsState()
        val jimakuApiKey by preferences.jimakuApiKey.collectAsState()
        val subDlApiKey by preferences.subDlApiKey.collectAsState()
        val subSourceApiKey by preferences.subSourceApiKey.collectAsState()
        val subsRoApiKey by preferences.subsRoApiKey.collectAsState()
        val subXApiKey by preferences.subXApiKey.collectAsState()
        val wyzieAiSubtitles by preferences.wyzieAiSubtitles.collectAsState()
        val onlineSubtitleSearchMode by preferences.onlineSubtitleSearchMode.collectAsState()
        val subtitleHubSources by preferences.subtitleHubSources.collectAsState()
        val allKeylessSourcesLabel = stringResource(R.string.pref_subtitles_all_keyless_sources)
        val apiKeyRequiredLabel = stringResource(R.string.pref_subtitles_source_api_key_required)
        val subtitleHubSourceValues =
          remember(allKeylessSourcesLabel) {
            MpvRxSubtitleHubSources.ALL.toMutableMap().apply {
              this["all"] = allKeylessSourcesLabel
            }
          }
        val subtitleHubSourceDescriptions =
          remember(apiKeyRequiredLabel) {
            MpvRxSubtitleHubSources.AUTHENTICATED_SOURCES.associateWith { apiKeyRequiredLabel }
          }
        val subtitleApiKeyPreferences =
          listOf(
            SubtitleApiKeyPreferenceSpec(
              sourceKey = MpvRxSubtitleHubSources.BETASERIES_KEY,
              providerName = "BetaSeries",
              titleRes = R.string.pref_betaseries_api_key_title,
              value = betaSeriesApiKey,
              onValueChange = preferences.betaSeriesApiKey::set,
            ),
            SubtitleApiKeyPreferenceSpec(
              sourceKey = MpvRxSubtitleHubSources.JIMAKU_KEY,
              providerName = "Jimaku",
              titleRes = R.string.pref_jimaku_api_key_title,
              value = jimakuApiKey,
              onValueChange = preferences.jimakuApiKey::set,
            ),
            SubtitleApiKeyPreferenceSpec(
              sourceKey = MpvRxSubtitleHubSources.SUBDL_KEY,
              providerName = "SubDL",
              titleRes = R.string.pref_subdl_api_key_title,
              value = subDlApiKey,
              onValueChange = preferences.subDlApiKey::set,
            ),
            SubtitleApiKeyPreferenceSpec(
              sourceKey = MpvRxSubtitleHubSources.SUBSOURCE_KEY,
              providerName = "SubSource",
              titleRes = R.string.pref_subsource_api_key_title,
              value = subSourceApiKey,
              onValueChange = preferences.subSourceApiKey::set,
            ),
            SubtitleApiKeyPreferenceSpec(
              sourceKey = MpvRxSubtitleHubSources.SUBS_RO_KEY,
              providerName = "Subs.ro",
              titleRes = R.string.pref_subs_ro_api_key_title,
              value = subsRoApiKey,
              onValueChange = preferences.subsRoApiKey::set,
            ),
            SubtitleApiKeyPreferenceSpec(
              sourceKey = MpvRxSubtitleHubSources.SUBX_KEY,
              providerName = "SubX",
              titleRes = R.string.pref_subx_api_key_title,
              value = subXApiKey,
              onValueChange = preferences.subXApiKey::set,
            ),
          )
        val selectedSubtitleApiKeyPreferences =
          subtitleApiKeyPreferences.filter { it.sourceKey in subtitleHubSources }
        val requestedSearchTarget by SettingsSearchNavigation.target.collectFlowAsState()
        var customFonts by remember { mutableStateOf<List<String>>(emptyList()) }
        var downloadedGoogleFonts by remember { mutableStateOf<List<String>>(emptyList()) }

        LaunchedEffect(fontsFolder, fontRefreshKey, appFontFamily) {
          downloadedGoogleFonts =
            withContext(Dispatchers.IO) {
              googleFontsRepository.installedFonts(appFontFamily).map { it.family }
            }
          customFonts =
            loadCustomFontEntries(context)
              .filterNot { googleFontsRepository.isManagedMpvFont(it.file) }
              .map { it.familyName }
              .distinct()
              .filter { it != DEFAULT_SUBTITLE_FONT_FAMILY && it != LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY }
        }

        val availableFonts =
          remember(downloadedGoogleFonts, customFonts) {
            (downloadedGoogleFonts + customFonts)
              .filter { it != DEFAULT_SUBTITLE_FONT_FAMILY && it != LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY }
              .distinct()
              .sorted()
          }
        val effectiveDefaultFont = DEFAULT_SUBTITLE_FONT_FAMILY

        var sourcesResponse by remember { mutableStateOf<WyzieSourcesResponse?>(null) }
        var isLoadingSources by remember { mutableStateOf(false) }
        var sourcesError by remember { mutableStateOf<String?>(null) }
        var refreshTrigger by remember { mutableStateOf(0) }

        LaunchedEffect(wyzieApiKey, refreshTrigger) {
          isLoadingSources = true
          sourcesError = null
          val result = wyzieSearchRepository.getSources(forceRefresh = refreshTrigger > 0)
          result
            .onSuccess {
              sourcesResponse = it
              isLoadingSources = false
            }.onFailure { err ->
              sourcesError = err.message ?: "Failed to fetch sources"
              isLoadingSources = false
            }
        }

        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(SubtitlesPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          // === GENERAL SECTION ===
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.general),
              modifier = Modifier.settingsSearchTarget(R.string.pref_subtitles),
            )
          }

          item {
            PreferenceCard {
              val preferredLanguages by preferences.preferredLanguages.collectAsState()
              TextFieldPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_preferred_languages),
                value = preferredLanguages,
                enabled = "slang" !in configOwnedOptions,
                onValueChange = preferences.preferredLanguages::set,
                textToValue = { input ->
                  input
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .joinToString(",")
                },
                title = { Text(stringResource(R.string.pref_preferred_languages)) },
                summary = {
                  if (preferredLanguages.isNotBlank()) {
                    Text(
                      preferredLanguages,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  } else {
                    Text(
                      stringResource(R.string.not_set_video_default),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                },
                textField = { value, onValueChange, _ ->
                  Column {
                    Text(stringResource(R.string.enter_subtitle_title_preferences))
                    TextField(
                      value,
                      onValueChange,
                      modifier = Modifier.fillMaxWidth(),
                      placeholder = { Text(stringResource(R.string.subtitle_title_preferences_placeholder)) },
                    )
                  }
                },
              )

              PreferenceDivider()

              val autoload by preferences.autoloadMatchingSubtitles.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_subtitles_autoload_title),
                value = autoload,
                enabled =
                  setOf("sub-auto", "sub-file-paths", "subs-fallback").none(configOwnedOptions::contains),
                onValueChange = { preferences.autoloadMatchingSubtitles.set(it) },
                title = { Text(stringResource(R.string.pref_subtitles_autoload_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_subtitles_autoload_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val overrideAss by preferences.overrideAssSubs.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.player_sheets_sub_override_ass),
                value = overrideAss,
                enabled =
                  setOf("sub-ass-override", "secondary-sub-ass-override", "sub-pos", "secondary-sub-pos")
                    .none(configOwnedOptions::contains),
                onValueChange = { preferences.overrideAssSubs.set(it) },
                title = { Text(stringResource(R.string.player_sheets_sub_override_ass)) },
                summary = {
                  Text(
                    stringResource(R.string.player_sheets_sub_override_ass_subtitle),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val scaleByWindow by preferences.scaleByWindow.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.player_sheets_sub_scale_by_window),
                value = scaleByWindow,
                enabled =
                  setOf(
                    "sub-scale-by-window",
                    "sub-use-margins",
                  ).none(configOwnedOptions::contains),
                onValueChange = { preferences.scaleByWindow.set(it) },
                title = { Text(stringResource(R.string.player_sheets_sub_scale_by_window)) },
                summary = {
                  Text(
                    stringResource(R.string.player_sheets_sub_scale_by_window_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val secondaryPinchZoom by preferences.secondarySubPinchZoom.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_subtitles_secondary_pinch_zoom),
                value = secondaryPinchZoom,
                enabled = setOf("sub-scale", "secondary-sub-scale").none(configOwnedOptions::contains),
                onValueChange = { preferences.secondarySubPinchZoom.set(it) },
                title = { Text(stringResource(R.string.pref_subtitles_secondary_pinch_zoom)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_subtitles_secondary_pinch_zoom_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // === FONT SECTION ===
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_subtitle_fonts))
          }

          item {
            PreferenceCard {
              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_subtitles_fonts_dir),
                enabled = "sub-fonts-dir" !in configOwnedOptions,
                title = { Text(stringResource(R.string.pref_subtitles_fonts_dir)) },
                summary = {
                  val folderSummary =
                    if (fontsFolder.isBlank()) {
                      stringResource(R.string.pref_subtitles_font_directory_summary)
                    } else {
                      val displayPath =
                        runCatching { getSimplifiedPathFromUri(fontsFolder) }
                          .getOrElse { Uri.decode(fontsFolder) ?: fontsFolder }
                      stringResource(R.string.pref_subtitles_font_directory_selected, displayPath)
                    }
                  Text(
                    text = folderSummary,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                icon = {
                  Icon(
                    Icons.RoundedFilled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { fontFolderPicker.launch(null) },
              )

              PreferenceDivider()

              val fontValues =
                remember(availableFonts, selectedFont) {
                  val effectiveSelected =
                    selectedFont.takeUnless {
                      it.isBlank() ||
                        it == DEFAULT_SUBTITLE_FONT_FAMILY ||
                        it == LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY
                    }.orEmpty()
                  (listOf("") + availableFonts + listOf(effectiveSelected).filter { it.isNotBlank() }).distinct()
                }
              val fontValue = if (selectedFont in fontValues) selectedFont else ""
              val fontLabel =
                selectedFont.takeUnless {
                  it.isBlank() ||
                    it == DEFAULT_SUBTITLE_FONT_FAMILY ||
                    it == LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY
                } ?: defaultSubtitleFontDisplayName(effectiveDefaultFont)

              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_subtitles_font_title),
                value = fontValue,
                enabled = "sub-font" !in configOwnedOptions,
                onValueChange = preferences.font::set,
                values = fontValues,
                valueToText = {
                  AnnotatedString(it.ifBlank { context.getString(R.string.pref_subtitles_font_default) })
                },
                title = { Text(stringResource(R.string.pref_subtitles_font_title)) },
                summary = {
                  Column {
                    Text(
                      stringResource(R.string.pref_subtitles_font_summary, fontLabel),
                      color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                      if (availableFonts.isEmpty()) {
                        stringResource(R.string.pref_subtitles_font_no_custom)
                      } else {
                        stringResource(R.string.fonts_loaded, availableFonts.size)
                      },
                      color = MaterialTheme.colorScheme.outline,
                      style = MaterialTheme.typography.bodySmall,
                    )
                  }
                },
              )

              PreferenceDivider()

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.reload_fonts),
                title = { Text(stringResource(R.string.reload_fonts)) },
                summary = {
                  Text(
                    stringResource(R.string.reload_fonts_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.RoundedFilled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                enabled = fontsFolder.isNotBlank(),
                onClick = { reloadFontsFrom(fontsFolder) },
              )

              if (fontsFolder.isNotBlank() || customFonts.isNotEmpty()) {
                PreferenceDivider()

                Preference(
                  title = {
                    Text(
                      stringResource(R.string.clear_font_directory),
                      color = MaterialTheme.colorScheme.error,
                    )
                  },
                  icon = {
                    Icon(
                      Icons.RoundedFilled.Clear,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.error,
                    )
                  },
                  onClick = {
                    preferences.fontsFolder.set("")
                    preferences.font.set("")
                    scope.launch(Dispatchers.IO) {
                      googleFontsRepository.clearImportedMpvFonts()
                      googleFontsRepository.syncMpvFonts(appFontFamily)
                      withContext(Dispatchers.Main) { fontRefreshKey++ }
                    }
                  },
                )
              }
            }
          }

          // === ONLINE SUBTITLE SECTION ===
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_section_subtitle_search),
              modifier = Modifier.settingsSearchTarget(R.string.pref_subtitle_search_title),
            )
          }

          item {
            PreferenceCard {
              var showClearDialog by remember { mutableStateOf(false) }

              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_subtitles_search_mode_title),
                value = onlineSubtitleSearchMode,
                onValueChange = preferences.onlineSubtitleSearchMode::set,
                values = OnlineSubtitleSearchMode.values().toList(),
                valueToText = { AnnotatedString(it.displayName) },
                title = { Text(stringResource(R.string.pref_subtitles_search_mode_title)) },
                summary = {
                  Text(
                    onlineSubtitleSearchMode.displayName,
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              MultiChoicePreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_subtitles_subhub_sources_title),
                title = { Text(stringResource(R.string.pref_subtitles_subhub_sources_title)) },
                summary = {
                  val summaryText =
                    if (subtitleHubSources.isEmpty() || subtitleHubSources.contains("all")) {
                      (listOf(allKeylessSourcesLabel) + selectedSubtitleApiKeyPreferences.map { it.providerName })
                        .joinToString(" + ")
                    } else {
                      subtitleHubSources.mapNotNull { subtitleHubSourceValues[it] }.joinToString(", ")
                    }
                  Text(
                    text = summaryText,
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                values = subtitleHubSourceValues,
                valueDescriptions = subtitleHubSourceDescriptions,
                selectedValues = subtitleHubSources,
                onValuesChange = { preferences.subtitleHubSources.set(it) },
                hasAllOption = true,
                additiveWithAll = MpvRxSubtitleHubSources.AUTHENTICATED_SOURCES,
              )

              selectedSubtitleApiKeyPreferences.forEach { apiKeyPreference ->
                PreferenceDivider()
                SubtitleApiKeyPreference(
                  modifier = Modifier.settingsSearchTarget(apiKeyPreference.titleRes),
                  spec = apiKeyPreference,
                )
              }

              PreferenceDivider()

              SubtitleApiKeyPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_wyzie_api_key_title),
                spec =
                  SubtitleApiKeyPreferenceSpec(
                    sourceKey = "wyzie",
                    providerName = "Wyzie",
                    titleRes = R.string.pref_wyzie_api_key_title,
                    value = wyzieApiKey,
                    onValueChange = preferences.wyzieApiKey::set,
                    dialogTextRes = R.string.pref_wyzie_api_key_dialog_text,
                    placeholderRes = R.string.pref_wyzie_api_key_placeholder,
                  ),
              )

              PreferenceDivider()

              // Wyzie Sources
              var showSourcesDialog by remember { mutableStateOf(false) }
              var sourceSearch by rememberSaveable { mutableStateOf("") }
              val displayNamesMap =
                remember(sourcesResponse) {
                  val map = mutableMapOf<String, String>()
                  map.putAll(WyzieSources.ALL)
                  sourcesResponse?.tiered?.forEach { item ->
                    map[item.key] = item.name.replaceFirstChar { it.uppercase() }
                  }
                  map
                }

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_subtitle_sources_title),
                title = { Text(stringResource(R.string.pref_subtitle_sources_title)) },
                summary = {
                  val summaryText =
                    if (wyzieSources.isEmpty() || wyzieSources.contains("all")) {
                      stringResource(R.string.pref_all_sources)
                    } else {
                      wyzieSources
                        .mapNotNull {
                          displayNamesMap[it] ?: it.replaceFirstChar { c ->
                            c.uppercase()
                          }
                        }.joinToString(", ")
                    }
                  Text(summaryText, color = MaterialTheme.colorScheme.outline)
                },
                onClick = { showSourcesDialog = true },
              )

              if (showSourcesDialog) {
                val allSourcesLabel = stringResource(R.string.pref_all_sources)
                AppPickerSheet(
                  onDismissRequest = {
                    showSourcesDialog = false
                    sourceSearch = ""
                  },
                  title = stringResource(R.string.pref_subtitle_sources_title),
                  scrollContent = false,
                  actions = {
                    if (isLoadingSources) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                      )
                    } else {
                      IconButton(onClick = { refreshTrigger++ }) {
                        Icon(
                          imageVector = Icons.RoundedFilled.Refresh,
                          contentDescription = stringResource(R.string.ui_refresh),
                        )
                      }
                    }
                    TextButton(onClick = {
                      showSourcesDialog = false
                      sourceSearch = ""
                    }) {
                      Text(stringResource(android.R.string.ok))
                    }
                  },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                      PlayerSheetSearchField(
                        query = sourceSearch,
                        onQueryChange = { sourceSearch = it },
                        placeholder = stringResource(R.string.subtitle_source_search_hint),
                        modifier = Modifier.padding(bottom = 8.dp),
                      )
                      sourcesResponse?.key?.let { keyInfo ->
                        val keyType = keyInfo.type?.replaceFirstChar { it.uppercase() }
                          ?: stringResource(R.string.generic_unknown)
                        val badgeColor = if (keyInfo.valid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                        val badgeTextColor = if (keyInfo.valid) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer

                        Row(
                          modifier = Modifier.padding(bottom = 8.dp),
                          verticalAlignment = Alignment.CenterVertically,
                        ) {
                          SuggestionChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(stringResource(R.string.pref_api_key_type, keyType)) },
                            colors =
                              SuggestionChipDefaults.suggestionChipColors(
                                containerColor = badgeColor,
                                labelColor = badgeTextColor,
                              ),
                          )
                        }
                      }

                      if (sourcesError != null && sourcesResponse == null) {
                        Text(
                          text = sourcesError ?: stringResource(R.string.subtitle_sources_load_error),
                          color = MaterialTheme.colorScheme.error,
                          style = MaterialTheme.typography.bodyMedium,
                          modifier = Modifier.padding(vertical = 16.dp),
                        )
                      } else {
                        val allItems =
                          sourcesResponse?.tiered ?: WyzieSources.ALL.filterKeys { it != "all" }.map { (key, value) ->
                            WyzieSourceItem(
                              key = key,
                              name = value,
                              tier = if (key == "charlie" || key == "lima") "free" else "paid",
                              tags = emptyList(),
                              available = true,
                            )
                          }
                        val items = if (sourceSearch.isBlank()) allItems else allItems.filter { item ->
                          item.name.contains(sourceSearch, ignoreCase = true) ||
                            item.key.contains(sourceSearch, ignoreCase = true) ||
                            item.tags.any { it.contains(sourceSearch, ignoreCase = true) }
                        }

                        val freeItems = items.filter { it.tier.lowercase() == "free" }
                        val paidItems = items.filter { it.tier.lowercase() == "paid" }
                        val showAllSources = sourceSearch.isBlank() ||
                          allSourcesLabel.contains(sourceSearch, ignoreCase = true)

                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                          if (showAllSources) item {
                            val isAllChecked = wyzieSources.isEmpty() || wyzieSources.contains("all")
                            Row(
                              modifier =
                                Modifier
                                  .fillMaxWidth()
                                  .clickable {
                                    preferences.wyzieSources.set(setOf("all"))
                                  }.padding(vertical = 8.dp),
                              verticalAlignment = Alignment.CenterVertically,
                            ) {
                              Checkbox(
                                checked = isAllChecked,
                                onCheckedChange = null,
                              )
                              Spacer(modifier = Modifier.width(8.dp))
                              Text(
                                text = allSourcesLabel,
                                fontWeight = FontWeight.Bold,
                              )
                            }
                            PreferenceDivider()
                          }

                          // Free Sources Group
                          if (freeItems.isNotEmpty()) {
                            item {
                              Text(
                                text =
                                  androidx.compose.ui.res.stringResource(
                                    app.gyrolet.mpvrx.R.string.ui_free_sources,
                                  ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp),
                              )
                            }
                            items(freeItems.size, key = { freeItems[it].key }) { index ->
                              val item = freeItems[index]
                              val checked = !wyzieSources.contains("all") && wyzieSources.contains(item.key)
                              Row(
                                modifier =
                                  Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                      val newSet = wyzieSources.toMutableSet()
                                      newSet.remove("all")
                                      if (checked) {
                                        newSet.remove(item.key)
                                      } else {
                                        newSet.add(item.key)
                                      }
                                      if (newSet.isEmpty()) newSet.add("all")
                                      preferences.wyzieSources.set(newSet)
                                    }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                              ) {
                                Checkbox(
                                  checked = checked,
                                  onCheckedChange = null,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                  Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = item.name.replaceFirstChar { it.uppercase() })

                                    item.tags.forEach { tag ->
                                      Spacer(modifier = Modifier.width(6.dp))
                                      Text(
                                        text = tag,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier =
                                          Modifier
                                            .background(
                                              color = MaterialTheme.colorScheme.secondaryContainer,
                                              shape = MaterialTheme.shapes.extraSmall,
                                            ).padding(horizontal = 4.dp, vertical = 2.dp),
                                      )
                                    }
                                  }
                                }
                              }
                            }
                          }

                          // Paid Sources Group
                          if (paidItems.isNotEmpty()) {
                            item {
                              Text(
                                text =
                                  androidx.compose.ui.res.stringResource(
                                    app.gyrolet.mpvrx.R.string.ui_paid_sources,
                                  ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp),
                              )
                            }
                            items(paidItems.size, key = { paidItems[it].key }) { index ->
                              val item = paidItems[index]
                              val checked = !wyzieSources.contains("all") && wyzieSources.contains(item.key)
                              val isAvailable = item.available

                              Row(
                                modifier =
                                  Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = isAvailable) {
                                      val newSet = wyzieSources.toMutableSet()
                                      newSet.remove("all")
                                      if (checked) {
                                        newSet.remove(item.key)
                                      } else {
                                        newSet.add(item.key)
                                      }
                                      if (newSet.isEmpty()) newSet.add("all")
                                      preferences.wyzieSources.set(newSet)
                                    }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                              ) {
                                Checkbox(
                                  checked = checked,
                                  onCheckedChange = null,
                                  enabled = isAvailable,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                  Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                      text = item.name.replaceFirstChar { it.uppercase() },
                                      color =
                                        if (isAvailable) {
                                          MaterialTheme.colorScheme.onSurface
                                        } else {
                                          MaterialTheme.colorScheme.onSurface
                                            .copy(
                                              alpha = 0.6f,
                                            )
                                        },
                                    )

                                    item.tags.forEach { tag ->
                                      Spacer(modifier = Modifier.width(6.dp))
                                      Text(
                                        text = tag,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier =
                                          Modifier
                                            .background(
                                              color = MaterialTheme.colorScheme.secondaryContainer,
                                              shape = MaterialTheme.shapes.extraSmall,
                                            ).padding(horizontal = 4.dp, vertical = 2.dp),
                                      )
                                    }
                                  }
                                  if (!isAvailable) {
                                    Text(
                                      text =
                                        androidx.compose.ui.res.stringResource(
                                          app.gyrolet.mpvrx.R.string.ui_restricted_requires_paid_api_key,
                                        ),
                                      style = MaterialTheme.typography.bodySmall,
                                      color = MaterialTheme.colorScheme.error,
                                    )
                                  }
                                }

                                if (!isAvailable) {
                                  Icon(
                                    imageVector = Icons.RoundedFilled.Lock,
                                    contentDescription =
                                      androidx.compose.ui.res.stringResource(
                                        app.gyrolet.mpvrx.R.string.ui_restricted,
                                      ),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                  )
                                }
                              }
                            }
                          }
                          if (!showAllSources && freeItems.isEmpty() && paidItems.isEmpty()) {
                            item {
                              Text(
                                text = stringResource(R.string.generic_no_matching_options),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                              )
                            }
                          }
                        }
                      }
                    }
                }
              }

              PreferenceDivider()

              // Languages
              val subtitleSearchLanguages by preferences.subtitleSearchLanguages.collectAsState()
              MultiChoicePreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_subtitles_search_languages),
                title = { Text(stringResource(R.string.pref_subtitles_search_languages)) },
                summary = {
                  val summaryText =
                    if (subtitleSearchLanguages.isEmpty() || subtitleSearchLanguages.contains("all")) {
                      stringResource(R.string.all_languages)
                    } else {
                      subtitleSearchLanguages.mapNotNull { WyzieLanguages.ALL[it] }.joinToString(", ")
                    }
                  Text(summaryText, color = MaterialTheme.colorScheme.outline)
                },
                values = WyzieLanguages.SORTED,
                selectedValues = subtitleSearchLanguages,
                onValuesChange = { preferences.subtitleSearchLanguages.set(it) },
                hasAllOption = true,
              )

              PreferenceDivider()

              // Advanced Filters (Toggleable)
              var showAdvanced by remember { mutableStateOf(false) }
              LaunchedEffect(requestedSearchTarget) {
                val advancedTargetKeys =
                  setOf(
                    "res:${R.string.pref_hearing_impaired_title}",
                    "res:${R.string.pref_preferred_formats_title}",
                    "res:${R.string.pref_preferred_encodings_title}",
                  )
                if (requestedSearchTarget?.screen == SubtitlesPreferencesScreen &&
                  requestedSearchTarget?.key in advancedTargetKeys
                ) {
                  showAdvanced = true
                }
              }
              Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .clickable { showAdvanced = !showAdvanced }
                      .padding(16.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = stringResource(R.string.pref_section_advanced_search_filters),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                  )
                  Icon(
                    imageVector = if (showAdvanced) Icons.RoundedFilled.KeyboardArrowUp else Icons.RoundedFilled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }

                if (showAdvanced) {
                  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SwitchPreference(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_hearing_impaired_title),
                      value = wyzieHearingImpaired,
                      onValueChange = { preferences.wyzieHearingImpaired.set(it) },
                      title = { Text(stringResource(R.string.pref_hearing_impaired_title)) },
                      summary = { Text(stringResource(R.string.pref_hearing_impaired_summary)) },
                    )

                    PreferenceDivider()

                    val aiOptions =
                      mapOf(
                        "all" to stringResource(R.string.pref_ai_subtitles_all),
                        "human" to stringResource(R.string.pref_ai_subtitles_human_only),
                        "ai" to stringResource(R.string.pref_ai_subtitles_ai_only),
                      )
                    MultiChoicePreference(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_ai_subtitles_title),
                      title = { Text(stringResource(R.string.pref_ai_subtitles_title)) },
                      summary = {
                        val current = aiOptions[wyzieAiSubtitles] ?: stringResource(R.string.pref_ai_subtitles_all)
                        Text(current, color = MaterialTheme.colorScheme.outline)
                      },
                      values = aiOptions,
                      selectedValues = setOf(wyzieAiSubtitles),
                      onValuesChange = {
                        if (it.isNotEmpty()) preferences.wyzieAiSubtitles.set(it.first())
                      },
                      hasAllOption = false,
                    )

                    PreferenceDivider()

                    MultiChoicePreference(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_preferred_formats_title),
                      title = { Text(stringResource(R.string.pref_preferred_formats_title)) },
                      summary = {
                        val summaryText =
                          if (wyzieFormats.isEmpty() || wyzieFormats.contains("all")) {
                            stringResource(R.string.pref_all_sources)
                          } else {
                            wyzieFormats.mapNotNull { WyzieFormats.ALL[it] }.joinToString(", ")
                          }
                        Text(summaryText, color = MaterialTheme.colorScheme.outline)
                      },
                      values = WyzieFormats.ALL,
                      selectedValues = wyzieFormats,
                      onValuesChange = { preferences.wyzieFormats.set(it) },
                      hasAllOption = true,
                    )

                    PreferenceDivider()

                    MultiChoicePreference(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_preferred_encodings_title),
                      title = { Text(stringResource(R.string.pref_preferred_encodings_title)) },
                      summary = {
                        val summaryText =
                          if (wyzieEncodings.isEmpty() || wyzieEncodings.contains("all")) {
                            stringResource(R.string.pref_all_sources)
                          } else {
                            wyzieEncodings.mapNotNull { WyzieEncodings.ALL[it] }.joinToString(", ")
                          }
                        Text(summaryText, color = MaterialTheme.colorScheme.outline)
                      },
                      values = WyzieEncodings.ALL,
                      selectedValues = wyzieEncodings,
                      onValuesChange = { preferences.wyzieEncodings.set(it) },
                      hasAllOption = true,
                    )

                    Spacer(modifier = Modifier.size(16.dp))
                  }
                }
              }

              PreferenceDivider()

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_subtitles_clear_downloads),
                title = {
                  Text(
                    stringResource(R.string.pref_subtitles_clear_downloads),
                    color = MaterialTheme.colorScheme.error,
                  )
                },
                summary = { Text(stringResource(R.string.pref_subtitles_clear_downloads_summary)) },
                onClick = { showClearDialog = true },
                enabled = subtitleSaveFolder.isNotBlank(),
              )

              if (showClearDialog) {
                AlertDialog(
                  onDismissRequest = { showClearDialog = false },
                  title = { Text(stringResource(R.string.pref_subtitles_clear_downloads)) },
                  text = { Text(stringResource(R.string.pref_subtitles_clear_downloads_confirmation)) },
                  confirmButton = {
                    TextButton(
                      onClick = {
                        showClearDialog = false
                        scope.launch(Dispatchers.IO) {
                          runCatching {
                            val uri = Uri.parse(subtitleSaveFolder)
                            val folder = resolveSubtitleStorageDirectory(context, uri.toString())
                            folder?.listFiles()?.forEach { it.delete() }
                            withContext(Dispatchers.Main) {
                              android.widget.Toast
                                .makeText(
                                  context,
                                  R.string.toast_subtitles_cleared,
                                  android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                          }.onFailure { e ->
                            withContext(Dispatchers.Main) {
                              android.widget.Toast
                                .makeText(
                                  context,
                                  context.getString(
                                    R.string.pref_subtitle_search_error,
                                    e.message ?: context.getString(R.string.generic_unknown_error),
                                  ),
                                  android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                          }
                        }
                      },
                    ) {
                      Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                  },
                  dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                      Text(stringResource(android.R.string.cancel))
                    }
                  },
                )
              }

              PreferenceDivider()

              // Wyzie Tag
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = stringResource(R.string.pref_subtitle_search_attribution),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = stringResource(R.string.pref_subtitle_search_link),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Bold,
                  modifier =
                    Modifier.clickable {
                      val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://sub.wyzie.io"))
                      context.startActivity(intent)
                    },
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
fun MultiChoicePreference(
  title: @Composable () -> Unit,
  summary: @Composable () -> Unit,
  values: Map<String, String>,
  selectedValues: Set<String>,
  onValuesChange: (Set<String>) -> Unit,
  hasAllOption: Boolean = false,
  additiveWithAll: Set<String> = emptySet(),
  valueDescriptions: Map<String, String> = emptyMap(),
  modifier: Modifier = Modifier,
) {
  var showDialog by remember { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }

  Preference(
    modifier = modifier,
    title = title,
    summary = summary,
    onClick = { showDialog = true },
  )

  if (showDialog) {
    val valuesList = values.toList()
    val filteredValues = remember(values, valueDescriptions, query) {
      val normalized = query.trim()
      if (normalized.isEmpty()) valuesList else valuesList.filter { (key, label) ->
        key.contains(normalized, ignoreCase = true) ||
          label.contains(normalized, ignoreCase = true) ||
          valueDescriptions[key]?.contains(normalized, ignoreCase = true) == true
      }
    }
    AppPickerSheet(
      onDismissRequest = {
        showDialog = false
        query = ""
      },
      titleContent = title,
      scrollContent = false,
      actions = {
        TextButton(onClick = {
          showDialog = false
          query = ""
        }) {
          Text(stringResource(android.R.string.ok))
        }
      },
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (values.size >= 8) {
          PlayerSheetSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.generic_search),
          )
        }
        if (filteredValues.isEmpty()) {
          Text(
            text = stringResource(R.string.generic_no_matching_options),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
          )
        } else {
          LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(count = filteredValues.size, key = { index -> filteredValues[index].first }) { index ->
            val entry = filteredValues[index]
            val key = entry.first
            val checked =
              if (hasAllOption && (selectedValues.isEmpty() || selectedValues.contains("all"))) {
                key == "all" || (key in additiveWithAll && key in selectedValues)
              } else {
                selectedValues.contains(key)
              }

            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clickable {
                    val newSet = selectedValues.toMutableSet()
                    if (hasAllOption) {
                      if (key == "all") {
                        val retainedAdditiveValues = newSet.intersect(additiveWithAll)
                        newSet.clear()
                        newSet.add("all")
                        newSet.addAll(retainedAdditiveValues)
                      } else if (key in additiveWithAll) {
                        if (checked) newSet.remove(key) else newSet.add(key)
                      } else {
                        newSet.remove("all")
                        if (checked) newSet.remove(key) else newSet.add(key)
                        if (newSet.isEmpty()) newSet.add("all")
                      }
                    } else {
                      if (checked) newSet.remove(key) else newSet.add(key)
                    }
                    onValuesChange(newSet)
                  }.padding(vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(
                checked = checked,
                onCheckedChange = null,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.second)
                valueDescriptions[key]?.let { description ->
                  Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

private data class SubtitleApiKeyPreferenceSpec(
  val sourceKey: String,
  val providerName: String,
  @StringRes val titleRes: Int,
  val value: String,
  val onValueChange: (String) -> Unit,
  @StringRes val dialogTextRes: Int? = null,
  @StringRes val placeholderRes: Int? = null,
)

@Composable
private fun SubtitleApiKeyPreference(
  spec: SubtitleApiKeyPreferenceSpec,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var showApiKey by rememberSaveable(spec.sourceKey) { mutableStateOf(false) }

  TextFieldPreference(
    modifier = modifier,
    value = spec.value,
    onValueChange = spec.onValueChange,
    textToValue = { it.trim() },
    title = { Text(stringResource(spec.titleRes)) },
    summary = {
      Text(
        text =
          stringResource(
            if (spec.value.isBlank()) {
              R.string.pref_subtitle_api_key_summary_required
            } else {
              R.string.pref_subtitle_api_key_summary_saved
            },
            spec.providerName,
          ),
        color = if (spec.value.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
      )
    },
    textField = { value, onValueChange, _ ->
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text =
            spec.dialogTextRes?.let { stringResource(it) }
              ?: stringResource(R.string.pref_subtitle_api_key_dialog_text, spec.providerName),
        )
        TextField(
          value = value,
          onValueChange = onValueChange,
          modifier = Modifier.fillMaxWidth(),
          placeholder = {
            Text(
              spec.placeholderRes?.let { stringResource(it) }
                ?: stringResource(R.string.pref_subtitle_api_key_placeholder),
            )
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(
                onClick = {
                  val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                  val clip = clipboard?.primaryClip
                  val pastedValue =
                    clip
                      ?.takeIf { it.itemCount > 0 }
                      ?.getItemAt(0)
                      ?.coerceToText(context)
                      ?.toString()
                      ?.trim()
                  if (!pastedValue.isNullOrBlank()) {
                    onValueChange(androidx.compose.ui.text.input.TextFieldValue(pastedValue))
                  }
                },
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.ContentPaste,
                  contentDescription = stringResource(R.string.pref_paste_api_key, spec.providerName),
                )
              }
              IconButton(onClick = { showApiKey = !showApiKey }) {
                Icon(
                  imageVector = if (showApiKey) Icons.RoundedFilled.VisibilityOff else Icons.RoundedFilled.Visibility,
                  contentDescription =
                    stringResource(if (showApiKey) R.string.generic_hide_key else R.string.generic_show_key),
                )
              }
            }
          },
        )
      }
    },
  )
}
