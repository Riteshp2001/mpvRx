package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.theme.LocalEmphasizedTypography
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable

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
    val emphasizedTypography = LocalEmphasizedTypography.current
    val sections = settingsSections()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_preferences),
              style = emphasizedTypography.headlineSmall,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              Icon(
                Icons.Outlined.ArrowBack,
                contentDescription = null,
                tint = colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        item {
          SettingsSearchEntry(
            onClick = { backstack.add(SettingsSearchScreen) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
          )
        }

        items(sections, key = { it.title }) { section ->
          SettingsSectionBlock(
            section = section,
            onItemClick = { backstack.add(it.screen) },
          )
        }
      }
    }
  }

  @Composable
  private fun settingsSections(): List<SettingsSection> {
    val colorScheme = MaterialTheme.colorScheme
    return listOf(
      SettingsSection(
        title = stringResource(R.string.pref_section_appearance),
        tint = colorScheme.onSurfaceVariant,
        items = listOf(
          SettingsDestination(
            title = stringResource(R.string.pref_appearance_title),
            summary = stringResource(R.string.pref_appearance_summary),
            icon = Icons.Outlined.Palette,
            screen = AppearancePreferencesScreen,
          ),
        ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_playback),
        tint = colorScheme.onSurfaceVariant,
        items = listOf(
          SettingsDestination(
            title = stringResource(R.string.pref_player),
            summary = stringResource(R.string.pref_player_summary),
            icon = Icons.Default.Slideshow,
            screen = PlayerPreferencesScreen,
          ),
          SettingsDestination(
            title = stringResource(R.string.pref_decoder),
            summary = stringResource(R.string.pref_decoder_summary),
            icon = Icons.Default.DeveloperBoard,
            screen = DecoderPreferencesScreen,
          ),
          SettingsDestination(
            title = stringResource(R.string.pref_audio),
            summary = stringResource(R.string.pref_audio_summary),
            icon = Icons.Outlined.Audiotrack,
            screen = AudioPreferencesScreen,
          ),
          SettingsDestination(
            title = stringResource(R.string.pref_haptics),
            summary = stringResource(R.string.pref_haptics_summary),
            icon = Icons.Outlined.Headset,
            screen = HapticsPreferencesScreen,
          ),
        ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_gestures_controls),
        tint = colorScheme.onSurfaceVariant,
        items = listOf(
          SettingsDestination(
            title = stringResource(R.string.pref_gesture),
            summary = stringResource(R.string.pref_gesture_summary),
            icon = Icons.Outlined.Gesture,
            screen = GesturePreferencesScreen,
          ),
          SettingsDestination(
            title = stringResource(R.string.pref_layout_title),
            summary = stringResource(R.string.pref_layout_summary),
            icon = Icons.Default.GridView,
            screen = PlayerControlsPreferencesScreen,
          ),
        ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_subtitles),
        tint = colorScheme.onSurfaceVariant,
        items = listOf(
          SettingsDestination(
            title = stringResource(R.string.pref_subtitles),
            summary = stringResource(R.string.pref_subtitles_summary),
            icon = Icons.Outlined.Subtitles,
            screen = SubtitlesPreferencesScreen,
          ),
        ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_storage),
        tint = colorScheme.onSurfaceVariant,
        items = listOf(
          SettingsDestination(
            title = stringResource(R.string.pref_folders_title),
            summary = "Media library folders, hidden paths, fonts, and subtitle directories.",
            icon = Icons.Outlined.Folder,
            screen = FoldersPreferencesScreen,
          ),
        ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_ai),
        tint = colorScheme.onSurfaceVariant,
        items = listOf(
          SettingsDestination(
            title = stringResource(R.string.pref_section_ai_title),
            summary = "Provider, model, API keys, rename tools, translation, and offline models.",
            icon = Icons.Default.AutoAwesome,
            screen = AiIntegrationScreen,
          ),
        ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_advanced),
        tint = colorScheme.onSurfaceVariant,
        items = listOf(
          SettingsDestination(
            title = stringResource(R.string.pref_advanced),
            summary = stringResource(R.string.pref_advanced_summary),
            icon = Icons.Alternatives.AdvancedSettings,
            screen = AdvancedPreferencesScreen,
          ),
        ),
      ),
      SettingsSection(
        title = stringResource(R.string.pref_section_about),
        tint = colorScheme.onSurfaceVariant,
        items = listOf(
          SettingsDestination(
            title = stringResource(R.string.pref_about_title),
            summary = stringResource(R.string.pref_about_summary),
            icon = Icons.Outlined.Info,
            screen = AboutScreen,
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
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
    shape = MaterialTheme.shapes.extraExtraLarge,
    color = MaterialTheme.colorScheme.secondaryContainer,
    tonalElevation = 1.dp,
    shadowElevation = 1.dp,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Outlined.Search,
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
        imageVector = Icons.Default.ChevronRight,
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
  onItemClick: (SettingsDestination) -> Unit,
) {
  val emphasizedTypography = LocalEmphasizedTypography.current

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 10.dp, bottom = 14.dp),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 24.dp),
    ) {
      Text(
        text = section.title,
        style = emphasizedTypography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    SettingsDestinationGroup(
      section = section,
      onItemClick = onItemClick,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
  }
}

@Composable
private fun SettingsDestinationGroup(
  section: SettingsSection,
  onItemClick: (SettingsDestination) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLargeIncreased,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 1.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      section.items.forEachIndexed { index, item ->
        SettingsDestinationRow(
          item = item,
          tint = section.tint,
          onClick = { onItemClick(item) },
        )
        if (index < section.items.lastIndex) {
          HorizontalDivider(
            modifier = Modifier.padding(start = 14.dp, end = 18.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
          )
        }
      }
    }
  }
}

@Composable
private fun SettingsDestinationRow(
  item: SettingsDestination,
  tint: Color,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      shape = MaterialTheme.shapes.largeIncreased,
      color = tint.copy(alpha = 0.18f),
    ) {
      Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = item.icon,
          contentDescription = null,
          modifier = Modifier.size(26.dp),
          tint = tint,
        )
      }
    }

    Spacer(modifier = Modifier.width(14.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = item.summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    Spacer(modifier = Modifier.width(10.dp))

    Icon(
      imageVector = Icons.Default.ChevronRight,
      contentDescription = null,
      modifier = Modifier.size(24.dp),
      tint = MaterialTheme.colorScheme.outline,
    )
  }
}
