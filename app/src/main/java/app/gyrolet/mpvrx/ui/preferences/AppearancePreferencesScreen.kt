/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MultiChoiceSegmentedButton
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.ThumbnailMode
import app.gyrolet.mpvrx.preferences.ThumbnailQuality
import app.gyrolet.mpvrx.preferences.TreeFlattenDepth
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.ControlsAnimationStyle
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.player.VideoOpenAnimation
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.preferences.components.RestartRequiredDialog
import app.gyrolet.mpvrx.ui.preferences.components.ThemePicker
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.CustomThemeDefinition
import app.gyrolet.mpvrx.ui.theme.WallpaperDerivedThemeName
import app.gyrolet.mpvrx.ui.theme.LocalThemeTransitionState
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import app.gyrolet.mpvrx.ui.preferences.components.AppSliderPreference as SliderPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Serializable
object AppearancePreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val preferences = koinInject<AppearancePreferences>()
    val browserPreferences = koinInject<BrowserPreferences>()
    val gesturePreferences = koinInject<GesturePreferences>()
    val playerPreferences = koinInject<PlayerPreferences>()
    val thumbnailRepository = koinInject<ThumbnailRepository>()
    val backstack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val systemDarkTheme = isSystemInDarkTheme()
    val themeTransition = LocalThemeTransitionState.current

    val darkMode by preferences.darkMode.collectAsState()
    val appTheme by preferences.appTheme.collectAsState()
    val customTheme by preferences.customTheme.collectAsState()
    val selectedCustomThemeName by preferences.selectedCustomThemeName.collectAsState()
    val customWallpaperUri by preferences.customWallpaperUri.collectAsState()
    var pendingThumbnailMode by remember { mutableStateOf<ThumbnailMode?>(null) }
    var pendingAppUiScale by remember { mutableStateOf<Float?>(null) }
    var isThemeSectionExpanded by rememberSaveable { mutableStateOf(true) }
    var isAppFontSheetOpen by rememberSaveable { mutableStateOf(false) }
    val storedThumbnailMode by browserPreferences.thumbnailMode.collectAsState()
    val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
    val thumbnailFramePosition by browserPreferences.thumbnailFramePosition.collectAsState()
    val dualPaneForTablet by browserPreferences.dualPaneForTablet.collectAsState()
    val treeFlattenDepth by browserPreferences.treeFlattenDepth.collectAsState()
    val thumbnailCacheClearedMessage = stringResource(R.string.pref_thumbnail_cache_cleared)

    val thumbnailMode = storedThumbnailMode
    val customThemes = remember(customTheme) { CustomThemeDefinition.parseCollection(customTheme) }
    val selectedCustomTheme = customThemes.firstOrNull { it.name == selectedCustomThemeName }
    // The wallpaper-derived theme is managed from the wallpaper editor, so it's hidden from the picker.
    val pickerThemes = remember(customThemes) { customThemes.filterNot { it.name == WallpaperDerivedThemeName } }
    val selectedThemeLabel =
      if (selectedCustomTheme?.name == WallpaperDerivedThemeName) {
        stringResource(R.string.pref_appearance_custom_wallpaper_title)
      } else {
        selectedCustomTheme?.name ?: stringResource(appTheme.titleRes)
      }

    // Determine if we're in dark mode for theme preview
    val isDarkMode =
      when (darkMode) {
        DarkMode.Dark -> true
        DarkMode.Light -> false
        DarkMode.System -> systemDarkTheme
      }

    if (pendingThumbnailMode != null) {
      ConfirmDialog(
        title = stringResource(R.string.pref_appearance_thumbnail_generation_change_title),
        subtitle = stringResource(R.string.pref_appearance_thumbnail_generation_change_summary),
        onConfirm = {
          val selectedMode = pendingThumbnailMode
          pendingThumbnailMode = null
          if (selectedMode != null) {
            browserPreferences.thumbnailMode.set(selectedMode)
            scope.launch {
              val result =
                withContext(Dispatchers.IO) {
                  runCatching { thumbnailRepository.clearThumbnailCache() }
                }
              result
                .onSuccess {
                  Toast
                    .makeText(
                      context,
                      thumbnailCacheClearedMessage,
                      Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure { error ->
                  Toast
                    .makeText(
                      context,
                      context.resources.getString(
                        R.string.pref_thumbnail_cache_clear_failed,
                        error.message ?: "Unknown error",
                      ),
                      Toast.LENGTH_LONG,
                    ).show()
                }
            }
          }
        },
        onCancel = { pendingThumbnailMode = null },
      )
    }

    pendingAppUiScale?.let { scale ->
      RestartRequiredDialog(
        title = stringResource(R.string.pref_appearance_ui_scale_restart_title),
        message =
          stringResource(
            R.string.pref_appearance_ui_scale_restart_message,
            (scale * 100).roundToInt(),
          ),
        confirmLabel = stringResource(R.string.pref_appearance_ui_scale_restart_now),
        onDismiss = { pendingAppUiScale = null },
        onConfirm = {
          pendingAppUiScale = null
          preferences.appUiScale.set(scale)
          (context as? Activity)?.recreate()
        },
      )
    }

    if (isAppFontSheetOpen) {
      GoogleFontsSheet(
        preferences = preferences,
        onDismiss = { isAppFontSheetOpen = false },
      )
    }

    Scaffold(
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.pref_appearance_title),
          onBack = { backstack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(AppearancePreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          item {
            PreferenceSectionHeader(
              title = stringResource(id = R.string.pref_appearance_category_theme),
              modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_title),
            )
          }

          item {
            PreferenceCard {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable { isThemeSectionExpanded = !isThemeSectionExpanded }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(
                  modifier = Modifier.weight(1f),
                  verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                  Text(
                    text = stringResource(id = R.string.pref_appearance_category_theme),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    text = "${stringResource(darkMode.titleRes)} · $selectedThemeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                  )
                }
                Icon(
                  imageVector = if (isThemeSectionExpanded) Icons.RoundedFilled.ExpandLess else Icons.RoundedFilled.ExpandMore,
                  contentDescription = null,
                  modifier = Modifier.size(20.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              AnimatedVisibility(visible = isThemeSectionExpanded) {
                Column {
                  PreferenceDivider()

                  Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    MultiChoiceSegmentedButton(
                      choices =
                        DarkMode.entries
                          .map {
                            stringResource(
                              it.titleRes,
                            )
                          }.toImmutableList(),
                      selectedIndices = persistentListOf(DarkMode.entries.indexOf(darkMode)),
                      onClick = { index, position ->
                        if (themeTransition?.isAnimating != true) {
                          themeTransition?.startTransition(position)
                          scope.launch {
                            delay(50)
                            preferences.darkMode.set(DarkMode.entries[index])
                          }
                        }
                      },
                    )
                  }

                  PreferenceDivider()

                  val amoledMode by preferences.amoledMode.collectAsState()
                  ThemePicker(
                    currentTheme = appTheme,
                    customThemes = pickerThemes,
                    selectedCustomThemeName = selectedCustomThemeName,
                    isDarkMode = isDarkMode,
                    onThemeSelected = { theme, position ->
                      if ((theme != appTheme || selectedCustomThemeName.isNotBlank()) && themeTransition?.isAnimating != true) {
                        themeTransition?.startTransition(position)
                        scope.launch {
                          delay(50)
                          preferences.appTheme.set(theme)
                          preferences.selectedCustomThemeName.set("")
                        }
                      }
                    },
                    onAddCustomTheme = { backstack.navigateTo(CustomThemeEditorScreen()) },
                    onCustomThemeSelected = { theme, position ->
                      if (theme.name != selectedCustomThemeName && themeTransition?.isAnimating != true) {
                        themeTransition?.startTransition(position)
                        scope.launch {
                          delay(50)
                          preferences.selectedCustomThemeName.set(theme.name)
                        }
                      }
                    },
                    onEditCustomTheme = { name -> backstack.navigateTo(CustomThemeEditorScreen(name)) },
                    onDeleteCustomTheme = { name ->
                      val remainingThemes = customThemes.filterNot { it.name == name }
                      preferences.customTheme.set(CustomThemeDefinition.serializeCollection(remainingThemes))
                      if (selectedCustomThemeName == name) preferences.selectedCustomThemeName.set("")
                    },
                    modifier = Modifier.padding(vertical = 8.dp),
                  )

                  PreferenceDivider()

                  WallpaperPreferenceCard(
                    wallpaperUri = customWallpaperUri,
                    onClick = { backstack.navigateTo(WallpaperEditorScreen()) },
                  )

                   Preference(
                     modifier = Modifier.settingsSearchTarget(R.string.pref_app_icon_title),
                     title = { Text(stringResource(R.string.pref_app_icon_title)) },
                     summary = {
                       Text(
                         stringResource(R.string.pref_app_icon_summary),
                         color = MaterialTheme.colorScheme.outline,
                       )
                     },
                     onClick = { backstack.navigateTo(AppIconPreferencesScreen) },
                   )

                  PreferenceDivider()

                  SwitchPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_amoled_mode_title),
                    value = amoledMode,
                    onValueChange = { newValue ->
                      if (themeTransition?.isAnimating != true) {
                        themeTransition?.startTransition(
                          androidx.compose.ui.geometry.Offset.Zero,
                        )
                        scope.launch {
                          delay(50)
                          preferences.amoledMode.set(newValue)
                        }
                      }
                    },
                    title = {
                      Text(
                        text = stringResource(id = R.string.pref_appearance_amoled_mode_title),
                      )
                    },
                    summary = {
                      Text(
                        text =
                          stringResource(
                            id = R.string.pref_appearance_amoled_mode_summary,
                          ),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    enabled = darkMode != DarkMode.Light,
                  )

                  PreferenceDivider()

                  val useSystemFont by preferences.useSystemFont.collectAsState()
                  val googleFontFamily by preferences.googleFontFamily.collectAsState()
                  val activeFontName =
                    when {
                      useSystemFont -> stringResource(R.string.pref_appearance_system_font_title)
                      googleFontFamily.isNotBlank() -> googleFontFamily
                      else -> stringResource(R.string.app_font_google_sans_flex)
                    }
                  Preference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_app_font_title),
                    title = { Text(stringResource(R.string.pref_appearance_app_font_title)) },
                    summary = {
                      Text(
                        text = stringResource(R.string.pref_appearance_app_font_summary, activeFontName),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    onClick = { isAppFontSheetOpen = true },
                  )

                  PreferenceDivider()

                  val appUiScale by preferences.appUiScale.collectAsState()
                  SliderPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_ui_scale_title),
                    value = pendingAppUiScale ?: appUiScale,
                    onValueChange = { pendingAppUiScale = it },
                    title = {
                      Text(stringResource(R.string.pref_appearance_ui_scale_title))
                    },
                    valueRange = 0.5f..1.5f,
                    valueSteps = 19,
                    summary = {
                      Text(
                        stringResource(
                          R.string.pref_appearance_ui_scale_summary,
                          ((pendingAppUiScale ?: appUiScale) * 100).roundToInt(),
                        ),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    onSliderValueChange = { pendingAppUiScale = it },
                    sliderValue = pendingAppUiScale ?: appUiScale,
                  )

                  PreferenceDivider()

                  val navigationBarGlow by preferences.navigationBarGlow.collectAsState()
                  SwitchPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.navbar_glow_title),
                    value = navigationBarGlow,
                    onValueChange = preferences.navigationBarGlow::set,
                    title = { Text(stringResource(R.string.navbar_glow_title)) },
                    summary = {
                      Text(stringResource(R.string.navbar_glow_summary), color = MaterialTheme.colorScheme.outline)
                    },
                  )
                }
              }
            }
          }

          item {
            PreferenceSectionHeader(
              title = stringResource(id = R.string.pref_appearance_category_file_browser),
            )
          }

          item {
            ExpressivePreferenceGroup {
              val itemCount = 7
              val unlimitedNameLines by preferences.unlimitedNameLines.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_unlimited_name_lines_title),
                value = unlimitedNameLines,
                onValueChange = { preferences.unlimitedNameLines.set(it) },
                title = {
                  Text(
                    text = stringResource(id = R.string.pref_appearance_unlimited_name_lines_title),
                  )
                },
                summary = {
                  Text(
                    text =
                      stringResource(
                        id = R.string.pref_appearance_unlimited_name_lines_summary,
                      ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 0,
                groupSize = itemCount,
              )

              val showUnplayedOldVideoLabel by preferences.showUnplayedOldVideoLabel.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_show_unplayed_old_video_label_title),
                value = showUnplayedOldVideoLabel,
                onValueChange = { preferences.showUnplayedOldVideoLabel.set(it) },
                title = {
                  Text(
                    text =
                      stringResource(
                        id = R.string.pref_appearance_show_unplayed_old_video_label_title,
                      ),
                  )
                },
                summary = {
                  Text(
                    text =
                      stringResource(
                        id = R.string.pref_appearance_show_unplayed_old_video_label_summary,
                      ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 1,
                groupSize = itemCount,
              )

              val unplayedOldVideoDays by preferences.unplayedOldVideoDays.collectAsState()
              ExpressivePreferenceTile(index = 2, itemCount = itemCount) {
                SliderPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_unplayed_old_video_days_title),
                  value = unplayedOldVideoDays.toFloat(),
                  onValueChange = { preferences.unplayedOldVideoDays.set(it.roundToInt()) },
                  title = {
                    Text(
                      text = stringResource(id = R.string.pref_appearance_unplayed_old_video_days_title),
                    )
                  },
                  valueRange = 0f..30f,
                  valueSteps = 29,
                  summary = {
                    Text(
                      text =
                        if (unplayedOldVideoDays == 0) {
                          "Unlimited — NEW stays until the watched threshold is reached"
                        } else {
                          stringResource(
                            id = R.string.pref_appearance_unplayed_old_video_days_summary,
                            unplayedOldVideoDays,
                          )
                        },
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  onSliderValueChange = { preferences.unplayedOldVideoDays.set(it.roundToInt()) },
                  sliderValue = unplayedOldVideoDays.toFloat(),
                  enabled = showUnplayedOldVideoLabel,
                )
              }

              val autoScrollToLastPlayed by browserPreferences.autoScrollToLastPlayed.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_auto_scroll_title),
                value = autoScrollToLastPlayed,
                onValueChange = { browserPreferences.autoScrollToLastPlayed.set(it) },
                title = {
                  Text(text = stringResource(R.string.pref_appearance_auto_scroll_title))
                },
                summary = {
                  Text(
                    text = stringResource(R.string.pref_appearance_auto_scroll_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 3,
                groupSize = itemCount,
              )

              ExpressivePreferenceTile(index = 4, itemCount = itemCount) {
                ListPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_tree_flatten_depth_title),
                  value = treeFlattenDepth,
                  onValueChange = browserPreferences.treeFlattenDepth::set,
                  values = TreeFlattenDepth.entries,
                  valueToText = { AnnotatedString(it.displayName) },
                  title = { Text(stringResource(R.string.pref_tree_flatten_depth_title)) },
                  summary = {
                    Text(
                      stringResource(
                        R.string.pref_tree_flatten_depth_summary,
                        treeFlattenDepth.displayName,
                      ),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )
              }

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.ui_dual_pane_view),
                value = dualPaneForTablet,
                onValueChange = { browserPreferences.dualPaneForTablet.set(it) },
                title = {
                  Text(
                    text =
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.ui_dual_pane_view,
                      ),
                  )
                },
                summary = {
                  Text(
                    text =
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.ui_enable_dual_pane_layout_on_tablets_in_folder_view,
                      ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 5,
                groupSize = itemCount,
              )

              val deleteFolderAllContents by browserPreferences.deleteFolderAllContents.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.ui_delete_folder_all_contents),
                value = deleteFolderAllContents,
                onValueChange = { browserPreferences.deleteFolderAllContents.set(it) },
                title = {
                  Text(
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.ui_delete_folder_all_contents,
                    ),
                  )
                },
                summary = {
                  Text(
                    text =
                      if (deleteFolderAllContents) {
                        "Deletes entire folder (all files)"
                      } else {
                        "Only media files (audio/video) are deleted"
                      },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 6,
                groupSize = itemCount,
              )
            }
          }

          item {
            PreferenceSectionHeader(
              title = stringResource(id = R.string.pref_appearance_category_thumbnails),
            )
          }

          item {
            ExpressivePreferenceGroup {
              val itemCount = if (thumbnailMode == ThumbnailMode.FrameAtPosition) 6 else 5
              val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_show_video_thumbnails_title),
                value = showVideoThumbnails,
                onValueChange = { browserPreferences.showVideoThumbnails.set(it) },
                title = {
                  Text(
                    text = stringResource(id = R.string.pref_appearance_show_video_thumbnails_title),
                  )
                },
                summary = {
                  Text(
                    text =
                      stringResource(
                        id = R.string.pref_appearance_show_video_thumbnails_summary,
                      ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 0,
                groupSize = itemCount,
              )

              ExpressivePreferenceTile(index = 1, itemCount = itemCount) {
                ListPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_thumbnail_generation_title),
                  value = thumbnailMode,
                  onValueChange = { newMode ->
                    if (newMode != thumbnailMode) {
                      pendingThumbnailMode = newMode
                    }
                  },
                  values = ThumbnailMode.entries,
                  valueToText = { AnnotatedString(it.displayName) },
                  title = {
                    Text(
                      text = stringResource(id = R.string.pref_appearance_thumbnail_generation_title),
                    )
                  },
                  summary = {
                    Text(
                      text =
                        when (thumbnailMode) {
                          ThumbnailMode.FrameAtPosition ->
                            "${thumbnailMode.displayName} (${thumbnailFramePosition.roundToInt()}%)"
                          else -> thumbnailMode.displayName
                        },
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  enabled = showVideoThumbnails,
                )
              }

              ExpressivePreferenceTile(index = 2, itemCount = itemCount) {
                ListPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_thumbnail_quality_title),
                  value = thumbnailQuality,
                  onValueChange = { newQuality ->
                    if (newQuality != thumbnailQuality) {
                      scope.launch {
                        withContext(Dispatchers.IO) {
                          thumbnailRepository.clearThumbnailCache()
                        }
                        browserPreferences.thumbnailQuality.set(newQuality)
                      }
                    }
                  },
                  values = ThumbnailQuality.entries,
                  valueToText = { AnnotatedString(it.displayName) },
                  title = {
                    Text(
                      text = stringResource(id = R.string.pref_appearance_thumbnail_quality_title),
                    )
                  },
                  summary = {
                    Text(
                      text =
                        stringResource(
                          id = R.string.pref_appearance_thumbnail_quality_summary,
                          thumbnailQuality.maxSizePx,
                        ),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  enabled = showVideoThumbnails,
                )
              }

              if (thumbnailMode == ThumbnailMode.FrameAtPosition) {
                ExpressivePreferenceTile(index = 3, itemCount = itemCount) {
                  SliderPreference(
                    value = thumbnailFramePosition,
                    onValueChange = { browserPreferences.thumbnailFramePosition.set(it) },
                    sliderValue = thumbnailFramePosition,
                    onSliderValueChange = { browserPreferences.thumbnailFramePosition.set(it) },
                    title = {
                      Text(
                        text = stringResource(id = R.string.pref_appearance_thumbnail_position_title),
                      )
                    },
                    valueRange = 0f..100f,
                    valueSteps = 99,
                    summary = {
                      Text(
                        text =
                          stringResource(
                            id = R.string.pref_appearance_thumbnail_position_summary,
                            thumbnailFramePosition.roundToInt(),
                          ),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    enabled = showVideoThumbnails,
                  )
                }
              }

              val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_gesture_tap_thumbnail_to_select_title),
                value = tapThumbnailToSelect,
                onValueChange = { gesturePreferences.tapThumbnailToSelect.set(it) },
                title = {
                  Text(
                    text = stringResource(id = R.string.pref_gesture_tap_thumbnail_to_select_title),
                  )
                },
                summary = {
                  Text(
                    text =
                      stringResource(
                        id = R.string.pref_gesture_tap_thumbnail_to_select_summary,
                      ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                enabled = showVideoThumbnails,
                groupIndex = if (thumbnailMode == ThumbnailMode.FrameAtPosition) 4 else 3,
                groupSize = itemCount,
              )

              val showNetworkThumbnails by preferences.showNetworkThumbnails.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_show_network_thumbnails_title),
                value = showNetworkThumbnails,
                onValueChange = { preferences.showNetworkThumbnails.set(it) },
                title = {
                  Text(
                    text =
                      stringResource(
                        id = R.string.pref_appearance_show_network_thumbnails_title,
                      ),
                  )
                },
                summary = {
                  Text(
                    text =
                      stringResource(
                        id = R.string.pref_appearance_show_network_thumbnails_summary,
                      ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                enabled = showVideoThumbnails,
                groupIndex = itemCount - 1,
                groupSize = itemCount,
              )
            }
          }

          item {
            PreferenceSectionHeader(
              title = stringResource(id = R.string.pref_appearance_category_navigation),
            )
          }

          item {
            ExpressivePreferenceGroup {
              val showMusicTab by preferences.showMusicTab.collectAsState()
              val showProfileTab by preferences.showProfileTab.collectAsState()
              val showNetworkTab by preferences.showNetworkTab.collectAsState()
              val showJellyfinTab by preferences.showJellyfinTab.collectAsState()

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_nav_music_title),
                value = showMusicTab,
                onValueChange = preferences.showMusicTab::set,
                title = { Text(text = stringResource(id = R.string.pref_nav_music_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_nav_music_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 0,
                groupSize = 6,
              )

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_nav_profile_title),
                value = showProfileTab,
                onValueChange = preferences.showProfileTab::set,
                title = { Text(text = stringResource(id = R.string.pref_nav_profile_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_nav_profile_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 1,
                groupSize = 6,
              )

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_nav_network_title),
                value = showNetworkTab,
                onValueChange = preferences.showNetworkTab::set,
                title = { Text(text = stringResource(id = R.string.pref_nav_network_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_nav_network_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 2,
                groupSize = 6,
              )

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_nav_jellyfin_title),
                value = showJellyfinTab,
                onValueChange = preferences.showJellyfinTab::set,
                title = { Text(text = stringResource(id = R.string.pref_nav_jellyfin_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_nav_jellyfin_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 3,
                groupSize = 6,
              )

              val showQuickPlayFab by preferences.showQuickPlayFab.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_quick_play_fab_title),
                value = showQuickPlayFab,
                onValueChange = preferences.showQuickPlayFab::set,
                title = { Text(text = stringResource(id = R.string.pref_quick_play_fab_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_quick_play_fab_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 4,
                groupSize = 6,
              )

              val quickPlayFabDirect by preferences.quickPlayFabDirect.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_quick_play_fab_direct_title),
                value = quickPlayFabDirect,
                onValueChange = preferences.quickPlayFabDirect::set,
                enabled = showQuickPlayFab,
                title = { Text(text = stringResource(id = R.string.pref_quick_play_fab_direct_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_quick_play_fab_direct_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                groupIndex = 5,
                groupSize = 6,
              )
            }
          }

          // ── Animations ────────────────────────────────────────
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_animations))
          }

          item {
            ExpressivePreferenceGroup {
              val controlsAnimStyle by playerPreferences.controlsAnimStyle.collectAsState()
              ExpressivePreferenceTile(index = 0, itemCount = 4) {
                ListPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_anim_controls_style_title),
                  value = controlsAnimStyle,
                  onValueChange = playerPreferences.controlsAnimStyle::set,
                  values = ControlsAnimationStyle.entries,
                  valueToText = { AnnotatedString(it.displayName) },
                  title = { Text(stringResource(R.string.pref_anim_controls_style_title)) },
                  summary = {
                    Text(
                      controlsAnimStyle.displayName,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )
              }

              val videoOpenAnim by playerPreferences.videoOpenAnimation.collectAsState()
              ExpressivePreferenceTile(index = 1, itemCount = 4) {
                ListPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_anim_video_open_title),
                  value = videoOpenAnim,
                  onValueChange = playerPreferences.videoOpenAnimation::set,
                  values = VideoOpenAnimation.entries,
                  valueToText = { AnnotatedString(it.displayName) },
                  title = { Text(stringResource(R.string.pref_anim_video_open_title)) },
                  summary = {
                    Text(
                      videoOpenAnim.displayName,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )
              }

              val appNavStyle by playerPreferences.appNavStyle.collectAsState()
              ExpressivePreferenceTile(index = 2, itemCount = 4) {
                ListPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_anim_screen_nav_style_title),
                  value = appNavStyle,
                  onValueChange = playerPreferences.appNavStyle::set,
                  values = NavigationAnimStyle.entries,
                  valueToText = { AnnotatedString(it.displayName) },
                  title = { Text(stringResource(R.string.pref_anim_screen_nav_style_title)) },
                  summary = {
                    Text(
                      appNavStyle.displayName,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )
              }

              val animSpeed by playerPreferences.animationSpeed.collectAsState()
              ExpressivePreferenceTile(index = 3, itemCount = 4) {
                SliderPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_anim_speed_title),
                  value = animSpeed,
                  onValueChange = { playerPreferences.animationSpeed.set(it) },
                  title = { Text(stringResource(R.string.pref_anim_speed_title)) },
                  valueRange = 0.25f..2.5f,
                  summary = {
                    val label =
                      when {
                        animSpeed < 0.6f ->
                          stringResource(
                            R.string.pref_anim_speed_very_fast,
                            animSpeed,
                          )
                        animSpeed < 0.9f -> stringResource(R.string.pref_anim_speed_fast, animSpeed)
                        animSpeed < 1.1f -> stringResource(R.string.pref_anim_speed_normal, animSpeed)
                        animSpeed < 1.6f -> stringResource(R.string.pref_anim_speed_slow, animSpeed)
                        else -> stringResource(R.string.pref_anim_speed_very_slow, animSpeed)
                      }
                    Text(label, color = MaterialTheme.colorScheme.outline)
                  },
                  onSliderValueChange = { playerPreferences.animationSpeed.set(it) },
                  sliderValue = animSpeed,
                )
              }
            }
          }
        }
      }
    }
  }
}
