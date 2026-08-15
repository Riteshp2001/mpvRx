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
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.fastJoinToString
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.SettingsManager
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.presentation.crash.CrashActivity
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject
import java.io.File
import java.util.Locale
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlin.io.path.readLines

private enum class AppLanguage(
  val languageTag: String,
) {
  SystemDefault(""),
  English("en"),
  Arabic("ar"),
  German("de"),
  Spanish("es"),
  French("fr"),
  Hindi("hi"),
  Japanese("ja"),
  PortugueseBrazil("pt-BR"),
  Russian("ru"),
  SimplifiedChinese("zh-CN"),
  ;

  fun displayName(context: android.content.Context): String {
    if (this == SystemDefault) return context.getString(R.string.pref_app_language_system_default)
    val locale = Locale.forLanguageTag(languageTag)
    return locale.getDisplayName(locale)
  }

  companion object {
    fun fromLanguageTag(languageTag: String): AppLanguage {
      if (languageTag.isBlank()) return SystemDefault
      return entries.firstOrNull { it.languageTag.equals(languageTag, ignoreCase = true) }
        ?: entries.firstOrNull {
          it != SystemDefault &&
            Locale.forLanguageTag(it.languageTag).language == Locale.forLanguageTag(languageTag).language
        }
        ?: SystemDefault
    }
  }
}

@Serializable
object AdvancedPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val preferences = koinInject<AdvancedPreferences>()
    val settingsManager = koinInject<SettingsManager>()
    val foldersPreferences = koinInject<FoldersPreferences>()
    val subtitlesPreferences = koinInject<SubtitlesPreferences>()
    val scope = rememberCoroutineScope()
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var importStats by remember { mutableStateOf<SettingsManager.ImportStats?>(null) }
    var exportStats by remember { mutableStateOf<SettingsManager.ExportStats?>(null) }
    var pendingAppLanguage by remember { mutableStateOf<AppLanguage?>(null) }
    val configuration = LocalConfiguration.current
    val currentAppLanguage =
      remember(configuration) {
        AppLanguage.fromLanguageTag(
          AppCompatDelegate
            .getApplicationLocales()
            .get(0)
            ?.toLanguageTag()
            .orEmpty(),
        )
      }
    val playbackHistoryClearedMessage = stringResource(R.string.pref_advanced_cleared_playback_history)
    val fontsCacheClearedMessage = stringResource(R.string.pref_advanced_cleared_fonts_cache)
    val unknownError = stringResource(R.string.generic_unknown_error)
    val exportFailedMessage = stringResource(R.string.pref_export_failed, unknownError)
    val importFailedMessage = stringResource(R.string.pref_import_failed, unknownError)

    // Export settings launcher
    val exportLauncher =
      rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/xml"),
      ) { uri ->
        uri?.let {
          scope.launch {
            settingsManager.exportSettings(it).fold(
              onSuccess = { stats ->
                exportStats = stats
                showExportDialog = true
              },
              onFailure = { error ->
                Toast
                  .makeText(
                    context,
                    context.getString(
                      R.string.pref_export_failed,
                      error.message ?: context.getString(R.string.generic_unknown_error),
                    ),
                    Toast.LENGTH_LONG,
                  ).show()
              },
            )
          }
        }
      }

    // Import settings launcher
    val importLauncher =
      rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
      ) { uri ->
        uri?.let {
          scope.launch {
            settingsManager.importSettings(it).fold(
              onSuccess = { stats ->
                importStats = stats
                showImportDialog = true
              },
              onFailure = { error ->
                Toast
                  .makeText(
                    context,
                    context.getString(
                      R.string.pref_import_failed,
                      error.message ?: context.getString(R.string.generic_unknown_error),
                    ),
                    Toast.LENGTH_LONG,
                  ).show()
              },
            )
          }
        }
      }

    val baseStorageFolder by foldersPreferences.baseStorageFolder.collectAsState()

    val storageRootPicker =
      rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
      ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val uriString = uri.toString()
        val previousBaseStorageFolder = foldersPreferences.baseStorageFolder.get()
        if (subtitlesPreferences.fontsFolder.get() == previousBaseStorageFolder) {
          subtitlesPreferences.fontsFolder.set("")
        }
        foldersPreferences.baseStorageFolder.set(uriString)
        preferences.mpvConfStorageUri.set(uriString)
        subtitlesPreferences.subtitleSaveFolder.set(uriString)
        val root = DocumentFile.fromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        listOf("fonts", "Subtitles", "scripts", "script-opts", "shaders").forEach { name ->
          if (root.findFile(name) == null) root.createDirectory(name)
        }
      }

    // Export results dialog
    if (showExportDialog && exportStats != null) {
      AlertDialog(
        onDismissRequest = { showExportDialog = false },
        title = { Text(stringResource(R.string.pref_export_complete_title)) },
        text = {
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
          ) {
            Text(
              stringResource(R.string.pref_export_complete_text, exportStats?.totalExported ?: 0),
            )
          }
        },
        confirmButton = {
          TextButton(onClick = { showExportDialog = false }) {
            Text(stringResource(R.string.generic_ok))
          }
        },
      )
    }

    // Import results dialog
    if (showImportDialog && importStats != null) {
      AlertDialog(
        onDismissRequest = { showImportDialog = false },
        title = { Text(stringResource(R.string.pref_import_complete_title)) },
        text = {
          Text(
            stringResource(
              R.string.pref_import_complete_text,
              importStats?.imported ?: 0,
              importStats?.failed ?: 0,
              importStats?.version ?: "unknown",
            ),
          )
        },
        confirmButton = {
          TextButton(onClick = { showImportDialog = false }) {
            Text(stringResource(R.string.generic_ok))
          }
        },
      )
    }

    pendingAppLanguage?.let { language ->
      AlertDialog(
        onDismissRequest = { pendingAppLanguage = null },
        title = { Text(stringResource(R.string.pref_app_language_restart_title)) },
        text = { Text(stringResource(R.string.pref_app_language_restart_message)) },
        dismissButton = {
          TextButton(onClick = { pendingAppLanguage = null }) {
            Text(stringResource(R.string.generic_cancel))
          }
        },
        confirmButton = {
          TextButton(
            onClick = {
              pendingAppLanguage = null
              val locales =
                if (language == AppLanguage.SystemDefault) {
                  LocaleListCompat.getEmptyLocaleList()
                } else {
                  LocaleListCompat.forLanguageTags(language.languageTag)
                }
              AppCompatDelegate.setApplicationLocales(locales)
            },
          ) {
            Text(stringResource(R.string.pref_app_language_restart_now))
          }
        },
      )
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_advanced),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backStack.popSafely() }) {
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
        val mpvConfStorageLocation by preferences.mpvConfStorageUri.collectAsState()
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(AdvancedPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          // App Language Section
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_section_app_language),
              modifier = Modifier.settingsSearchTarget(R.string.pref_advanced),
            )
          }

          item {
            PreferenceCard {
              ListPreference(
                value = currentAppLanguage,
                onValueChange = { language ->
                  if (language != currentAppLanguage) pendingAppLanguage = language
                },
                values = AppLanguage.entries,
                valueToText = { AnnotatedString(it.displayName(context)) },
                title = { Text(stringResource(R.string.pref_app_language_title)) },
                summary = {
                  Text(
                    text = currentAppLanguage.displayName(context),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // Backup & Restore Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_backup_restore))
          }

          item {
            PreferenceCard {
              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_export_settings_title),
                title = { Text(text = stringResource(R.string.pref_export_settings_title)) },
                summary = {
                  Text(
                    text = stringResource(R.string.pref_export_settings_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.RoundedFilled.FileUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = {
                  exportLauncher.launch(settingsManager.getDefaultExportFilename())
                },
              )

              PreferenceDivider()

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_import_settings_title),
                title = { Text(text = stringResource(R.string.pref_import_settings_title)) },
                summary = {
                  Text(
                    text = stringResource(R.string.pref_import_settings_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.RoundedFilled.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = {
                  importLauncher.launch(arrayOf("text/xml", "application/xml", "*/*"))
                },
              )
            }
          }

          // Storage Root Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_storage_root))
          }

          item {
            PreferenceCard {
              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_advanced_mpv_conf_storage_location),
                title = { Text(stringResource(R.string.pref_advanced_mpv_conf_storage_location)) },
                summary = {
                  Text(
                    text =
                      if (baseStorageFolder.isNotEmpty()) {
                        getSimplifiedStoragePath(baseStorageFolder)
                      } else {
                        stringResource(R.string.pref_base_storage_folder_summary)
                      },
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
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
                onClick = { storageRootPicker.launch(null) },
              )

              if (baseStorageFolder.isNotEmpty()) {
                PreferenceDivider()
                Preference(
                  title = { Text(stringResource(R.string.pref_clear_storage_root_title)) },
                  icon = {
                    Icon(
                      Icons.RoundedFilled.Clear,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.error,
                    )
                  },
                  onClick = {
                    if (subtitlesPreferences.fontsFolder.get() == baseStorageFolder) {
                      subtitlesPreferences.fontsFolder.set("")
                    }
                    foldersPreferences.baseStorageFolder.set("")
                    preferences.mpvConfStorageUri.set("")
                    subtitlesPreferences.subtitleSaveFolder.set("")
                  },
                )
              }
            }
          }

          // MPV Configuration Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_mpv_config))
          }

          item {
            PreferenceCard {
              var mpvConf by remember { mutableStateOf(preferences.mpvConf.get()) }
              var inputConf by remember { mutableStateOf(preferences.inputConf.get()) }

              // Load config files when storage location changes
              LaunchedEffect(mpvConfStorageLocation) {
                if (mpvConfStorageLocation.isBlank()) return@LaunchedEffect
                withContext(Dispatchers.IO) {
                  val tempFile = kotlin.io.path.createTempFile()
                  runCatching {
                    val tree = DocumentFile.fromTreeUri(context, mpvConfStorageLocation.toUri())
                    val mpvConfFile = tree?.findFile("mpv.conf")
                    if (mpvConfFile != null && mpvConfFile.exists()) {
                      context.contentResolver.openInputStream(mpvConfFile.uri)?.copyTo(tempFile.outputStream())
                      val content = tempFile.readLines().fastJoinToString("\n")
                      preferences.mpvConf.set(content)
                      File(context.filesDir, "mpv.conf").writeText(content)
                      withContext(Dispatchers.Main) { mpvConf = content }
                    }
                  }
                  tempFile.deleteIfExists()
                }
              }

              LaunchedEffect(mpvConfStorageLocation) {
                if (mpvConfStorageLocation.isBlank()) return@LaunchedEffect
                withContext(Dispatchers.IO) {
                  val tempFile = kotlin.io.path.createTempFile()
                  runCatching {
                    val tree = DocumentFile.fromTreeUri(context, mpvConfStorageLocation.toUri())
                    val inputConfFile = tree?.findFile("input.conf")
                    if (inputConfFile != null && inputConfFile.exists()) {
                      context.contentResolver.openInputStream(inputConfFile.uri)?.copyTo(tempFile.outputStream())
                      val content = tempFile.readLines().fastJoinToString("\n")
                      preferences.inputConf.set(content)
                      File(context.filesDir, "input.conf").writeText(content)
                      withContext(Dispatchers.Main) { inputConf = content }
                    }
                  }
                  tempFile.deleteIfExists()
                }
              }

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_advanced_mpv_conf),
                title = { Text(stringResource(R.string.pref_advanced_mpv_conf)) },
                summary = {
                  val firstLine = mpvConf.lines().firstOrNull()
                  if (firstLine != null && firstLine.isNotBlank()) {
                    Text(firstLine, color = MaterialTheme.colorScheme.outline)
                  } else {
                    Text(stringResource(R.string.pref_config_edit_summary), color = MaterialTheme.colorScheme.outline)
                  }
                },
                onClick = {
                  backStack.add(ConfigEditorScreen(ConfigEditorScreen.ConfigType.MPV_CONF))
                },
              )

              PreferenceDivider()

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_advanced_input_conf),
                title = { Text(stringResource(R.string.pref_advanced_input_conf)) },
                summary = {
                  val firstLine = inputConf.lines().firstOrNull()
                  if (firstLine != null && firstLine.isNotBlank()) {
                    Text(firstLine, color = MaterialTheme.colorScheme.outline)
                  } else {
                    Text(stringResource(R.string.pref_config_edit_summary), color = MaterialTheme.colorScheme.outline)
                  }
                },
                onClick = {
                  backStack.add(ConfigEditorScreen(ConfigEditorScreen.ConfigType.INPUT_CONF))
                },
              )
            }
          }

          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_p2p_streaming))
          }

          item {
            PreferenceCard {
              val enableP2pStreaming by preferences.enableP2pStreaming.collectAsState()
              val enableHlsProxy by preferences.enableHlsProxy.collectAsState()

              SwitchPreference(
                value = enableP2pStreaming,
                onValueChange = preferences.enableP2pStreaming::set,
                title = { Text(stringResource(R.string.pref_enable_p2p_streaming_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_enable_p2p_streaming_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              SwitchPreference(
                value = enableHlsProxy,
                onValueChange = preferences.enableHlsProxy::set,
                title = { Text(stringResource(R.string.pref_enable_hls_proxy_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_enable_hls_proxy_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // Scripts Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_scripts))
          }

          item {
            PreferenceCard {
              val selectedScripts by preferences.selectedLuaScripts.collectAsState()
              val enableLuaScripts by preferences.enableLuaScripts.collectAsState()

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_enable_lua_scripts_title),
                value = enableLuaScripts,
                onValueChange = preferences.enableLuaScripts::set,
                title = { Text(stringResource(R.string.pref_enable_lua_scripts_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_enable_lua_scripts_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_manage_lua_scripts_title),
                title = { Text(stringResource(R.string.pref_manage_lua_scripts_title)) },
                summary = {
                  when {
                    mpvConfStorageLocation.isBlank() || !enableLuaScripts ->
                      Text(
                        stringResource(R.string.pref_manage_scripts_summary_disabled),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    selectedScripts.isEmpty() ->
                      Text(
                        stringResource(R.string.pref_manage_scripts_summary_none),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    selectedScripts.size == 1 ->
                      Text(
                        stringResource(R.string.pref_manage_scripts_summary_singular),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    else ->
                      Text(
                        stringResource(R.string.pref_manage_scripts_summary_plural, selectedScripts.size),
                        color = MaterialTheme.colorScheme.outline,
                      )
                  }
                },
                onClick = {
                  backStack.add(LuaScriptsScreen)
                },
                enabled = mpvConfStorageLocation.isNotBlank() && enableLuaScripts,
              )

              PreferenceDivider()

              Preference(
                title = { Text(stringResource(R.string.pref_custom_buttons_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_custom_buttons_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onClick = {
                  backStack.add(app.gyrolet.mpvrx.ui.preferences.CustomButtonScreen)
                },
              )
            }
          }

          item {
            PreferenceSectionHeader(title = stringResource(R.string.ui_network))
          }

          item {
            PreferenceCard {
              Preference(
                title = {
                  Text(
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_yt_dlp_manager),
                  )
                },
                summary = {
                  Text(
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.ui_install_and_update_yt_dlp_for_streaming_support,
                    ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.RoundedFilled.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = {
                  backStack.add(YtdlpSettingsScreen)
                },
              )
            }
          }

          // Data & Cache Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_data_cache))
          }

          item {
            PreferenceCard {
              var isConfirmDialogShown by remember { mutableStateOf(false) }
              val mpvrxDatabase = koinInject<MpvRxDatabase>()
              val enableRecentlyPlayed by preferences.enableRecentlyPlayed.collectAsState()
              var recentlyPlayedCount by remember { mutableStateOf(0) }

              LaunchedEffect(enableRecentlyPlayed) {
                if (enableRecentlyPlayed) {
                  recentlyPlayedCount =
                    withContext(Dispatchers.IO) {
                      runCatching { RecentlyPlayedOps.getRecentlyPlayedCount() }.getOrDefault(0)
                    }
                } else {
                  recentlyPlayedCount = 0
                }
              }

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_advanced_enable_recently_played_title),
                value = enableRecentlyPlayed,
                onValueChange = preferences.enableRecentlyPlayed::set,
                title = { Text(stringResource(R.string.pref_advanced_enable_recently_played_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_advanced_enable_recently_played_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val playbackHistorySummary =
                if (recentlyPlayedCount > 0) {
                  stringResource(R.string.pref_clear_playback_history_recently_played, recentlyPlayedCount)
                } else {
                  stringResource(R.string.pref_clear_playback_history_recently_played_none)
                }

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_advanced_clear_playback_history),
                title = { Text(stringResource(R.string.pref_advanced_clear_playback_history)) },
                summary = {
                  Column {
                    Text(
                      stringResource(R.string.pref_advanced_clear_playback_history_confirm_subtitle),
                      color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                      playbackHistorySummary,
                      color = MaterialTheme.colorScheme.outline,
                      style = MaterialTheme.typography.bodySmall,
                    )
                  }
                },
                onClick = { isConfirmDialogShown = true },
              )

              if (isConfirmDialogShown) {
                ConfirmDialog(
                  stringResource(R.string.pref_advanced_clear_playback_history_confirm_title),
                  stringResource(R.string.pref_advanced_clear_playback_history_confirm_subtitle),
                  onConfirm = {
                    scope.launch(Dispatchers.IO) {
                      runCatching {
                        mpvrxDatabase.videoDataDao().clearAllPlaybackStates()
                        RecentlyPlayedOps.clearAll()
                      }.onSuccess {
                        withContext(Dispatchers.Main) {
                          isConfirmDialogShown = false
                          Toast
                            .makeText(
                              context,
                              playbackHistoryClearedMessage,
                              Toast.LENGTH_SHORT,
                            ).show()
                        }
                      }.onFailure { error ->
                        withContext(Dispatchers.Main) {
                          isConfirmDialogShown = false
                          Toast
                            .makeText(
                              context,
                              "Failed to clear: ${error.message}",
                              Toast.LENGTH_LONG,
                            ).show()
                        }
                      }
                    }
                  },
                  onCancel = { isConfirmDialogShown = false },
                )
              }

              PreferenceDivider()

              var mpvConf by remember { mutableStateOf(preferences.mpvConf.get()) }
              var isClearThumbsConfirmShown by remember { mutableStateOf(false) }
              val thumbnailRepository = koinInject<ThumbnailRepository>()
              var configCacheSize by remember { mutableStateOf(0L) }
              var thumbnailCacheSize by remember { mutableStateOf(0L) }
              var fontsCacheSize by remember { mutableStateOf(0L) }
              var fontsFileCount by remember { mutableStateOf(0) }

              LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                  // Config cache size
                  val mpvConfFile = File(context.filesDir, "mpv.conf")
                  val inputConfFile = File(context.filesDir, "input.conf")
                  configCacheSize =
                    run {
                      var size = 0L
                      if (mpvConfFile.exists()) size += mpvConfFile.length()
                      if (inputConfFile.exists()) size += inputConfFile.length()
                      size
                    }

                  // Thumbnail cache size
                  thumbnailCacheSize =
                    run {
                      var size = 0L
                      listOf(
                        File(context.cacheDir, "thumbnails"),
                        File(context.filesDir, "thumbnails"),
                        File(context.cacheDir, "remote_images"),
                      ).forEach { dir ->
                        if (dir.exists()) {
                          dir.walkTopDown().filter { it.isFile }.forEach { size += it.length() }
                        }
                      }
                      size
                    }

                  // Fonts cache size
                  val fontsDir = File(context.filesDir, "fonts")
                  if (fontsDir.exists()) {
                    val fontFiles =
                      fontsDir.listFiles()?.filter {
                        it.isFile && it.name.lowercase().matches(".*\\.[ot]tf$".toRegex())
                      } ?: emptyList()
                    fontsFileCount = fontFiles.size
                    fontsCacheSize = fontFiles.sumOf { it.length() }
                  } else {
                    fontsFileCount = 0
                    fontsCacheSize = 0
                  }
                }
              }

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_clear_config_cache_title),
                title = { Text(text = stringResource(R.string.pref_clear_config_cache_title)) },
                summary = {
                  val sizeStr = formatFileSize(configCacheSize)
                  Column {
                    Text(
                      text = stringResource(R.string.pref_config_cache_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                      text = stringResource(R.string.pref_config_cache_size, sizeStr),
                      color = MaterialTheme.colorScheme.outline,
                      style = MaterialTheme.typography.bodySmall,
                    )
                  }
                },
                onClick = {
                  scope.launch(Dispatchers.IO) {
                    runCatching {
                      listOf(
                        File(context.filesDir, "mpv.conf"),
                        File(context.filesDir, "input.conf"),
                      ).forEach { file ->
                        check(!file.exists() || file.delete()) {
                          "Unable to clear config cache file: ${file.absolutePath}"
                        }
                      }
                      preferences.mpvConf.delete()
                      preferences.inputConf.delete()
                    }.onSuccess {
                      withContext(Dispatchers.Main) {
                        mpvConf = ""
                        configCacheSize = 0L
                        Toast
                          .makeText(
                            context,
                            context.getString(R.string.pref_config_cache_cleared_toast),
                            Toast.LENGTH_SHORT,
                          ).show()
                      }
                    }.onFailure { error ->
                      withContext(Dispatchers.Main) {
                        Toast
                          .makeText(
                            context,
                            context.getString(
                              R.string.pref_failed_to_clear,
                              error.message ?: context.getString(R.string.generic_unknown_error),
                            ),
                            Toast.LENGTH_LONG,
                          ).show()
                      }
                    }
                  }
                },
              )

              PreferenceDivider()

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_clear_thumbnail_cache_title),
                title = { Text(text = stringResource(R.string.pref_clear_thumbnail_cache_title)) },
                summary = {
                  val sizeStr = formatFileSize(thumbnailCacheSize)
                  Column {
                    Text(
                      text = stringResource(R.string.pref_thumbnail_cache_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                      text = stringResource(R.string.pref_thumbnail_cache_size, sizeStr),
                      color = MaterialTheme.colorScheme.outline,
                      style = MaterialTheme.typography.bodySmall,
                    )
                  }
                },
                onClick = { isClearThumbsConfirmShown = true },
              )

              if (isClearThumbsConfirmShown) {
                ConfirmDialog(
                  title = stringResource(R.string.pref_thumbnail_cache_confirm_title),
                  subtitle = stringResource(R.string.pref_thumbnail_cache_confirm_subtitle),
                  onConfirm = {
                    scope.launch(Dispatchers.IO) {
                      runCatching {
                        thumbnailRepository.clearThumbnailCache()
                      }.onSuccess {
                        withContext(Dispatchers.Main) {
                          isClearThumbsConfirmShown = false
                          thumbnailCacheSize = 0L
                          Toast
                            .makeText(
                              context,
                              context.getString(R.string.pref_thumbnail_cache_cleared),
                              Toast.LENGTH_SHORT,
                            ).show()
                        }
                      }.onFailure { error ->
                        withContext(Dispatchers.Main) {
                          isClearThumbsConfirmShown = false
                          Toast
                            .makeText(
                              context,
                              context.getString(
                                R.string.pref_failed_to_clear,
                                error.message ?: context.getString(R.string.generic_unknown_error),
                              ),
                              Toast.LENGTH_LONG,
                            ).show()
                        }
                      }
                    }
                  },
                  onCancel = { isClearThumbsConfirmShown = false },
                )
              }

              PreferenceDivider()

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_advanced_clear_fonts_cache),
                title = { Text(text = stringResource(id = R.string.pref_advanced_clear_fonts_cache)) },
                summary = {
                  val sizeStr = formatFileSize(fontsCacheSize)
                  Text(
                    text = stringResource(R.string.pref_fonts_cache_size, sizeStr, fontsFileCount),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onClick = {
                  scope.launch(Dispatchers.IO) {
                    val fontsDir = File(context.filesDir, "fonts")
                    runCatching {
                      check(!fontsDir.exists() || fontsDir.deleteRecursively()) {
                        "Unable to clear fonts cache directory: ${fontsDir.absolutePath}"
                      }
                    }.onSuccess {
                      withContext(Dispatchers.Main) {
                        fontsCacheSize = 0L
                        fontsFileCount = 0
                        Toast
                          .makeText(
                            context,
                            fontsCacheClearedMessage,
                            Toast.LENGTH_SHORT,
                          ).show()
                      }
                    }.onFailure { error ->
                      withContext(Dispatchers.Main) {
                        Toast
                          .makeText(
                            context,
                            context.getString(
                              R.string.pref_failed_to_clear,
                              error.message ?: context.getString(R.string.generic_unknown_error),
                            ),
                            Toast.LENGTH_LONG,
                          ).show()
                      }
                    }
                  }
                },
              )
            }
          }

          // Logging Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_logging))
          }

          item {
            PreferenceCard {
              val activity = LocalActivity.current!!
              val verboseLogging by preferences.verboseLogging.collectAsState()

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_advanced_verbose_logging_title),
                value = verboseLogging,
                onValueChange = preferences.verboseLogging::set,
                title = { Text(stringResource(R.string.pref_advanced_verbose_logging_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_advanced_verbose_logging_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_advanced_dump_logs_title),
                title = { Text(stringResource(R.string.pref_advanced_dump_logs_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_advanced_dump_logs_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onClick = {
                  scope.launch(Dispatchers.IO) {
                    val deviceInfo = CrashActivity.collectDeviceInfo()
                    val logcat = CrashActivity.collectLogcat()

                    SafeClipboard.copyPlainText(
                      context = context,
                      label = "mpvrx_logs",
                      text = CrashActivity.concatLogs(deviceInfo, null, logcat),
                    )
                    CrashActivity.shareLogs(deviceInfo, null, logcat, activity)
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}

fun getSimplifiedPathFromUri(uri: String): String =
  File(Environment.getExternalStorageDirectory(), Uri.decode(uri).substringAfterLast(":")).canonicalPath

private fun formatFileSize(bytes: Long): String =
  when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
  }
