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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.rememberNavBackStack
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.SecureFolderPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.securefolder.SecureFolderGateScreen
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.LocalEmphasizedTypography
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.ScreenNavDisplay
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

private data class SettingsDestination(
  val title: String,
  val summary: String,
  val icon: AppIcon,
  val screen: Screen,
)

private data class SettingsSection(
  val title: String,
  val tint: Color,
  val items: List<SettingsDestination>,
)

@Serializable
object PreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val colorScheme = MaterialTheme.colorScheme
    val sections = settingsSections()

    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600

    if (isTablet) {
      @Suppress("UNCHECKED_CAST")
      val detailBackstack = rememberNavBackStack(AppearancePreferencesScreen) as NavBackStack<Screen>
      val selectedScreen = detailBackstack.first()
      Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(0.4f).tvFocusGroup()) {
          SettingsPane(
            sections = sections,
            selectedScreen = selectedScreen,
            onScreenSelected = { screen ->
              if (screen != selectedScreen) {
                // Replace the category atomically without ever exposing an empty stack.
                Snapshot.withMutableSnapshot {
                  detailBackstack[0] = screen
                  while (detailBackstack.size > 1) detailBackstack.removeAt(detailBackstack.lastIndex)
                }
              }
            },
          )
        }
        VerticalDivider(
          modifier = Modifier.fillMaxHeight(),
          color = colorScheme.outlineVariant.copy(alpha = 0.5f),
          thickness = 1.dp,
        )
        Box(modifier = Modifier.weight(0.6f).tvFocusGroup()) {
          CompositionLocalProvider(LocalBackStack provides detailBackstack) {
            ScreenNavDisplay(
              backStack = detailBackstack,
              modifier = Modifier.fillMaxSize(),
              opaqueBackground = true,
            ) { screen ->
              CompositionLocalProvider(LocalShowSettingsBackArrow provides (screen != selectedScreen)) {
                screen.Content()
              }
            }
          }
        }
      }
    } else {
      SettingsPane(
        sections = sections,
        selectedScreen = null,
        onScreenSelected = { backstack.navigateTo(it) },
      )
    }
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun SettingsPane(
    sections: List<SettingsSection>,
    selectedScreen: Screen?,
    onScreenSelected: (Screen) -> Unit,
  ) {
    val backstack = LocalBackStack.current
    val colorScheme = MaterialTheme.colorScheme
    val emphasizedTypography = LocalEmphasizedTypography.current
    val initialFocusRequester = rememberTvInitialFocusRequester()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_preferences),
              style = emphasizedTypography.titleLarge,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              Icon(
                Icons.RoundedFilled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier =
          Modifier
            .fillMaxSize()
            .tvFocusGroup()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        item {
          SettingsSearchEntry(
            onClick = { backstack.navigateTo(SettingsSearchScreen) },
            modifier =
              Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp)
                .tvInitialFocus(initialFocusRequester),
          )
        }

        items(sections, key = { it.title }) { section ->
          SettingsSectionBlock(
            section = section,
            selectedScreen = selectedScreen,
            onItemClick = { destination -> onScreenSelected(destination.screen) },
          )
        }
      }
    }
  }

  @Composable
  private fun settingsSections(): List<SettingsSection> {
    val colorScheme = MaterialTheme.colorScheme
    val secureFolderPreferences = koinInject<SecureFolderPreferences>()
    val isSecureFolderEntryHidden by secureFolderPreferences.isEntryPointHidden.collectAsState()
    return listOf(
      SettingsSection(
        title = stringResource(R.string.pref_section_appearance),
        tint = colorScheme.onSurfaceVariant,
        items =
          listOf(
            SettingsDestination(
              title = stringResource(R.string.pref_appearance_title),
              summary = stringResource(R.string.pref_appearance_summary),
              icon = Icons.RoundedFilled.Palette,
              screen = AppearancePreferencesScreen,
            ),
            SettingsDestination(
              title = stringResource(R.string.pref_appearance_category_liquid_glass),
              summary =
                listOf(
                  stringResource(R.string.pref_liquid_glass_optics),
                  stringResource(R.string.pref_liquid_glass_colors),
                  stringResource(R.string.pref_liquid_glass_highlights),
                  stringResource(R.string.pref_liquid_glass_shadows),
                ).joinToString(", "),
              icon = Icons.RoundedFilled.BlurOn,
              screen = LiquidGlassPreferencesScreen,
            ),
          ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_playback),
        tint = colorScheme.onSurfaceVariant,
        items =
          listOf(
            SettingsDestination(
              title = stringResource(R.string.pref_player),
              summary =
                listOf(
                  stringResource(R.string.pref_section_general),
                  stringResource(R.string.pref_player_seeking_title),
                  stringResource(R.string.pref_section_display_controls),
                  stringResource(R.string.pref_screenshots_section),
                ).joinToString(", "),
              icon = Icons.RoundedFilled.Slideshow,
              screen = PlayerPreferencesScreen,
            ),
            SettingsDestination(
              title = stringResource(R.string.pref_audio),
              summary = stringResource(R.string.pref_audio_summary),
              icon = Icons.RoundedFilled.Audiotrack,
              screen = AudioPreferencesScreen,
            ),
            SettingsDestination(
              title = stringResource(R.string.pref_subtitles),
              summary = stringResource(R.string.pref_subtitles_summary),
              icon = Icons.RoundedFilled.Subtitles,
              screen = SubtitlesPreferencesScreen,
            ),
            SettingsDestination(
              title = stringResource(R.string.pref_decoder),
              summary = stringResource(R.string.pref_decoder_summary),
              icon = Icons.RoundedFilled.DeveloperBoard,
              screen = DecoderPreferencesScreen,
            ),
          ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_gestures_controls),
        tint = colorScheme.onSurfaceVariant,
        items =
          listOf(
            SettingsDestination(
              title = stringResource(R.string.pref_layout_title),
              summary = stringResource(R.string.pref_layout_summary),
              icon = Icons.RoundedFilled.GridView,
              screen = PlayerControlsPreferencesScreen,
            ),
            SettingsDestination(
              title = stringResource(R.string.pref_gesture),
              summary = stringResource(R.string.pref_gesture_summary),
              icon = Icons.RoundedFilled.Gesture,
              screen = GesturePreferencesScreen,
            ),
          ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_storage),
        tint = colorScheme.onSurfaceVariant,
        items =
          listOfNotNull(
            SettingsDestination(
              title = stringResource(R.string.pref_folders_title),
              summary = stringResource(R.string.pref_folders_summary),
              icon = Icons.RoundedFilled.Folder,
              screen = FoldersPreferencesScreen,
            ),
            if (!isSecureFolderEntryHidden) {
              SettingsDestination(
                title = stringResource(R.string.secure_folder_title),
                summary = stringResource(R.string.secure_folder_summary),
                icon = Icons.RoundedFilled.Lock,
                screen = SecureFolderGateScreen,
              )
            } else {
              null
            },
          ),
      ),
      SettingsSection(
        title = stringResource(R.string.ui_network),
        tint = colorScheme.onSurfaceVariant,
        items =
          listOf(
            SettingsDestination(
              title = stringResource(R.string.ui_network),
              summary = stringResource(R.string.pref_network_summary),
              icon = Icons.RoundedFilled.Language,
              screen = NetworkConfigurationPreferencesScreen,
            ),
            SettingsDestination(
              title = stringResource(R.string.pref_media_servers_title),
              summary = stringResource(R.string.pref_media_servers_summary),
              icon = Icons.RoundedFilled.BringYourOwnIp,
              screen = MediaServersPreferencesScreen,
            ),
          ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_ai),
        tint = colorScheme.onSurfaceVariant,
        items =
          listOf(
            SettingsDestination(
              title = stringResource(R.string.pref_section_ai_title),
              summary = stringResource(R.string.pref_section_ai_summary),
              icon = Icons.RoundedFilled.AutoAwesome,
              screen = AiIntegrationScreen,
            ),
          ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_advanced),
        tint = colorScheme.onSurfaceVariant,
        items =
          listOf(
            SettingsDestination(
              title = stringResource(R.string.pref_advanced),
              summary =
                listOf(
                  stringResource(R.string.pref_section_app_language),
                  stringResource(R.string.pref_section_backup_restore),
                  stringResource(R.string.pref_section_mpv_config),
                ).joinToString(", "),
              icon = Icons.Alternatives.AdvancedSettings,
              screen = AdvancedPreferencesScreen,
            ),
            SettingsDestination(
              title = stringResource(R.string.pref_codecs_title),
              summary = stringResource(R.string.pref_codecs_summary),
              icon = Icons.RoundedFilled.Memory,
              screen = CodecCapabilitiesScreen,
            ),
          ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_about),
        tint = colorScheme.onSurfaceVariant,
        items =
          listOf(
            SettingsDestination(
              title = stringResource(R.string.pref_about_title),
              summary = stringResource(R.string.pref_about_summary),
              icon = Icons.RoundedFilled.Info,
              screen = AboutScreen,
            ),
            SettingsDestination(
              title = stringResource(R.string.pref_hall_of_fame_title),
              summary = stringResource(R.string.pref_hall_of_fame_summary),
              icon = Icons.RoundedFilled.Star,
              screen = HallOfFameScreen,
            ),
          ),
      ),
    )
  }
}

@Composable
private fun SettingsSearchEntry(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(MaterialTheme.shapes.extraExtraLarge, focusedScale = 1.02f)
        .clip(MaterialTheme.shapes.extraExtraLarge)
        .clickable(onClick = onClick),
    shape = MaterialTheme.shapes.extraExtraLarge,
    color = MaterialTheme.colorScheme.secondaryContainer,
    tonalElevation = 1.dp,
    shadowElevation = 1.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 18.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Search,
        contentDescription = null,
        modifier = Modifier.size(28.dp),
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.settings_search_hint),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
      Icon(
        imageVector = Icons.RoundedFilled.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
      )
    }
  }
}

@Composable
private fun SettingsSectionBlock(
  section: SettingsSection,
  selectedScreen: Screen?,
  onItemClick: (SettingsDestination) -> Unit,
) {
  val emphasizedTypography = LocalEmphasizedTypography.current

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(top = 10.dp, bottom = 14.dp),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 24.dp),
    ) {
      Text(
        text = section.title,
        modifier = Modifier.semantics { heading() },
        style = emphasizedTypography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    SettingsDestinationGroup(
      section = section,
      selectedScreen = selectedScreen,
      onItemClick = onItemClick,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
  }
}

@Composable
private fun SettingsDestinationGroup(
  section: SettingsSection,
  selectedScreen: Screen?,
  onItemClick: (SettingsDestination) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
  ) {
    section.items.forEachIndexed { index, item ->
      SettingsDestinationRow(
        item = item,
        index = index,
        itemCount = section.items.size,
        tint = section.tint,
        isSelected = selectedScreen == item.screen,
        onClick = { onItemClick(item) },
      )
    }
  }
}

@Composable
private fun SettingsDestinationRow(
  item: SettingsDestination,
  index: Int,
  itemCount: Int,
  tint: Color,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  val shape =
    rememberConnectedShape(
      index = index,
      itemCount = itemCount,
      layout = ConnectedLayout.Vertical,
    )
  val containerColor =
    if (isSelected) {
      MaterialTheme.colorScheme.secondaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainerLow
    }
  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .tvFocusHighlight(shape, focusedScale = 1.01f)
        .clip(shape)
        .semantics { selected = isSelected }
        .clickable(onClick = onClick),
    shape = shape,
    color = containerColor,
    tonalElevation = 1.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
      ) {
        Box(
          modifier = Modifier.size(48.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else tint,
          )
        }
      }

      Spacer(modifier = Modifier.width(14.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = item.summary,
          style = MaterialTheme.typography.bodyMedium,
          color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Spacer(modifier = Modifier.width(10.dp))

      Icon(
        imageVector = Icons.RoundedFilled.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
