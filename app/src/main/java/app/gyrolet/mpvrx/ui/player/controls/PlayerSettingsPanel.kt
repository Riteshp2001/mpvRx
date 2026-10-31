/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.rememberNavBackStack
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.panels.DraggablePanel
import app.gyrolet.mpvrx.ui.preferences.AudioPreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.ControlLayoutEditorScreen
import app.gyrolet.mpvrx.ui.preferences.ControlRegion
import app.gyrolet.mpvrx.ui.preferences.DecoderPreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.GesturePreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.LiquidGlassPreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.LocalPlayerSettingsPanel
import app.gyrolet.mpvrx.ui.preferences.LocalPlayerSettingsConfigOptions
import app.gyrolet.mpvrx.ui.preferences.PersistentStatusBarPreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.PlayerControlsPreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.PlayerPreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.SearchablePreference
import app.gyrolet.mpvrx.ui.preferences.SearchablePreferences
import app.gyrolet.mpvrx.ui.preferences.SettingsSearchNavigation
import app.gyrolet.mpvrx.ui.preferences.SubtitlesPreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.searchTargetKey
import app.gyrolet.mpvrx.ui.preferences.searchCategoryTitleRes
import app.gyrolet.mpvrx.ui.preferences.isLivePlayerSetting
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.ScreenNavDisplay
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
private object PlayerSettingsHome : Screen {
  @Composable
  override fun Content() = Unit
}

private data class SettingsCategory(
  @StringRes val title: Int,
  val icon: AppIcon,
  val screen: Screen,
)

private fun playerSettingsCategories() = listOf(
  SettingsCategory(R.string.pref_player, Icons.RoundedFilled.Settings, PlayerPreferencesScreen),
  SettingsCategory(R.string.pref_audio, Icons.RoundedFilled.Audiotrack, AudioPreferencesScreen),
  SettingsCategory(R.string.pref_subtitles, Icons.RoundedFilled.Subtitles, SubtitlesPreferencesScreen),
  SettingsCategory(R.string.pref_decoder, Icons.RoundedFilled.DeveloperBoard, DecoderPreferencesScreen),
  SettingsCategory(R.string.pref_appearance_liquid_glass_title, Icons.RoundedFilled.Tune, LiquidGlassPreferencesScreen),
  SettingsCategory(R.string.pref_gesture, Icons.RoundedFilled.Gesture, GesturePreferencesScreen),
  SettingsCategory(R.string.pref_layout_title, Icons.RoundedFilled.GridView, PlayerControlsPreferencesScreen),
)

private fun playerSettingsScreens(categories: List<SettingsCategory>): Set<Screen> = buildSet {
  categories.forEach { add(it.screen) }
  add(PersistentStatusBarPreferencesScreen)
  ControlRegion.entries.forEach { add(ControlLayoutEditorScreen(it)) }
}

/** Restore-only keys for older saved drawer navigation; removed by the supported-screen filter. */
@Serializable
private enum class PlayerLiveSettings : Screen {
  Picture, SubtitleStyle;

  @Composable
  override fun Content() = Unit
}

@Composable
internal fun PlayerSettingsPanel(
  active: Boolean,
  onDismissRequest: () -> Unit,
) {
  @Suppress("UNCHECKED_CAST")
  val backStack = rememberNavBackStack(PlayerSettingsHome) as NavBackStack<Screen>
  val savedContent = rememberSaveableStateHolder()
  val glassActive = liquidGlassActive()
  val categories = remember(glassActive) {
    playerSettingsCategories().filter { it.screen != LiquidGlassPreferencesScreen || glassActive }
  }
  LaunchedEffect(categories) {
    val supportedScreens = playerSettingsScreens(categories)
    Snapshot.withMutableSnapshot {
      backStack.removeAll {
        it is PlayerLiveSettings ||
          (it != PlayerSettingsHome && it !in supportedScreens)
      }
      if (backStack.isEmpty()) backStack.add(PlayerSettingsHome)
    }
  }
  val resources = LocalResources.current
  val keyboard = LocalSoftwareKeyboardController.current
  var searchMode by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  val close: () -> Unit = {
    keyboard?.hide()
    onDismissRequest()
  }
  val back: () -> Unit = {
    if (searchMode || query.isNotBlank()) {
      searchMode = false
      query = ""
      keyboard?.hide()
    } else if (backStack.size > 1) {
      backStack.popSafely()
    } else {
      close()
    }
  }
  BackHandler(enabled = active, onBack = back)
  LaunchedEffect(active) {
    if (!active) keyboard?.hide()
  }

  Box(Modifier.fillMaxSize().imePadding()) {
    Box(Modifier.fillMaxSize().clickable(
      interactionSource = remember { MutableInteractionSource() },
      indication = null,
      onClick = close,
    ))
    savedContent.SaveableStateProvider("player-settings-panel") {
      DraggablePanel(
        modifier = Modifier.semantics { paneTitle = resources.getString(R.string.player_settings_title) },
        scrollContent = false,
        portraitHeightFraction = 0.94f,
        maxPanelWidth = 520.dp,
        shape = MaterialTheme.shapes.extraLargeIncreased,
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        header = {
          val current = backStack.lastOrNull()
          val title = when {
            searchMode || current == PlayerSettingsHome -> resources.getString(R.string.player_settings_title)
            current == PersistentStatusBarPreferencesScreen -> resources.getString(R.string.pref_persistent_status_title)
            current is ControlLayoutEditorScreen -> resources.getString(current.region.titleRes)
            else -> categories.firstOrNull { it.screen == current }?.let { resources.getString(it.title) }
              ?: resources.getString(R.string.player_settings_title)
          }
          SettingsPanelHeader(
            title = title,
            canGoBack = searchMode || query.isNotBlank() || backStack.size > 1,
            onBack = back,
            onSearch = { searchMode = true },
            onClose = close,
          )
        },
      ) {
        val colors = MaterialTheme.colorScheme
        MaterialTheme(colorScheme = colors.copy(background = Color.Transparent)) {
          CompositionLocalProvider(
            LocalBackStack provides backStack,
            LocalPlayerSettingsPanel provides true,
            LocalPlayerSettingsConfigOptions provides currentMpvConfigOverrideOptions(),
          ) {
            Box(
              Modifier.fillMaxSize()
                .padding(horizontal = 4.dp)
                .padding(bottom = 12.dp)
                .clipToBounds(),
            ) {
              ScreenNavDisplay(backStack = backStack, onBack = back) { screen ->
                when (screen) {
                  PlayerSettingsHome -> SettingsPanelHome(categories, query, { query = it }, backStack) {
                    query = ""
                    keyboard?.hide()
                  }
                  else -> screen.Content()
                }
              }
              // Keep the current page mounted beneath search, preserving its scroll position
              // and any expanded sections when search is dismissed without choosing a result.
              if (searchMode) {
                Surface(Modifier.fillMaxSize(), color = colors.surfaceContainer) {
                  SettingsPanelHome(categories, query, { query = it }, backStack, requestSearchFocus = true) {
                    searchMode = false
                    query = ""
                    keyboard?.hide()
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

@Composable
private fun SettingsPanelHeader(
  title: String,
  canGoBack: Boolean,
  onBack: () -> Unit,
  onSearch: () -> Unit,
  onClose: () -> Unit,
) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    if (canGoBack) {
      IconButton(onClick = onBack) { Icon(Icons.RoundedFilled.ArrowBack, stringResource(R.string.back)) }
    } else {
      Icon(Icons.RoundedFilled.Settings, null, Modifier.padding(12.dp).size(24.dp))
    }
    Text(
      title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
      maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    IconButton(onClick = onSearch) { Icon(Icons.RoundedFilled.Search, stringResource(R.string.settings_search_title)) }
    IconButton(onClick = onClose) { Icon(Icons.RoundedFilled.Close, stringResource(R.string.player_settings_close)) }
  }
}

@Composable
private fun SettingsPanelHome(
  categories: List<SettingsCategory>,
  query: String,
  onQueryChange: (String) -> Unit,
  backStack: NavBackStack<Screen>,
  requestSearchFocus: Boolean = false,
  onNavigate: () -> Unit,
) {
  val resources = LocalResources.current
  val searchFocus = remember { FocusRequester() }
  LaunchedEffect(requestSearchFocus) {
    if (requestSearchFocus) searchFocus.requestFocus()
  }
  val store = koinInject<PreferenceStore>()
  val favoritesPreference = remember(store) { store.getStringSet("player_settings_favorites", emptySet()) }
  val favorites by favoritesPreference.collectAsState()
  val screens = remember(categories) { playerSettingsScreens(categories) }
  val categoryEntries = remember(categories) {
    categories.map {
      SearchablePreference(
        titleRes = it.title,
        category = "Player",
        screen = it.screen,
      )
    } + SearchablePreference(
      titleRes = R.string.pref_persistent_status_title,
      category = "Player",
      screen = PersistentStatusBarPreferencesScreen,
      keywords = listOf("hud", "status", "clock", "battery"),
    )
  }
  val entries = remember(screens) {
    (SearchablePreferences.forScreens(screens).filter {
      isLivePlayerSetting(it.screen, it.targetRes ?: it.titleRes)
    } + categoryEntries).distinctBy { it.favoriteKey() }
  }
  val results = remember(query, resources, screens, categoryEntries) {
    val matches = SearchablePreferences.search(query, includeSecureFolder = false, screens = screens) {
      resources.getString(it)
    }.map { it.preference }.filter {
      it.screen in screens && isLivePlayerSetting(it.screen, it.targetRes ?: it.titleRes)
    }
    val terms = query.trim().split(Regex("\\s+"))
    val matchingCategories = categoryEntries.filter { entry ->
      val text = entry.titleRes?.let { resources.getString(it) }.orEmpty() + " " + entry.keywords.joinToString(" ")
      query.isNotBlank() && terms.all { text.contains(it, ignoreCase = true) }
    }
    (matches + matchingCategories).distinctBy { it.favoriteKey() }
  }
  fun showScreen(screen: Screen) {
    // Search can revisit a page already in this drawer's history. Reuse that entry instead of
    // accumulating duplicate navigation keys or losing the page's existing state.
    Snapshot.withMutableSnapshot {
      val existing = backStack.indexOf(screen)
      if (existing >= 0) {
        while (backStack.lastIndex > existing) backStack.removeAt(backStack.lastIndex)
      } else {
        backStack.navigateTo(screen)
      }
    }
  }
  fun navigateToSetting(entry: SearchablePreference) {
    SettingsSearchNavigation.open(entry)
    showScreen(entry.screen)
    onNavigate()
  }
  Column(Modifier.fillMaxSize()) {
    InlineSearchBar(
      query = query,
      onQueryChange = onQueryChange,
      onSearch = onQueryChange,
      inputFieldModifier = Modifier.focusRequester(searchFocus),
      placeholder = { Text(stringResource(R.string.player_settings_search)) },
      leadingIcon = { Icon(Icons.RoundedFilled.Search, null) },
      trailingIcon = {
        if (query.isNotEmpty()) {
          IconButton(onClick = { onQueryChange("") }) {
            Icon(Icons.RoundedFilled.Close, stringResource(R.string.player_settings_clear_search))
          }
        }
      },
      windowInsets = WindowInsets(0),
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
    LazyColumn(
      modifier = Modifier.weight(1f),
      contentPadding = PaddingValues(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (query.isNotBlank()) {
        if (results.isEmpty()) item {
          Text(stringResource(R.string.settings_search_no_results), Modifier.padding(12.dp))
        }
        itemsIndexed(results, key = { _, item -> item.favoriteKey() }) { index, entry ->
          SettingsResultRow(entry, index, results.size, entry.favoriteKey() in favorites,
            onOpen = { navigateToSetting(entry) },
            onFavorite = {
              val key = entry.favoriteKey()
              favoritesPreference.set(if (key in favorites) favorites - key else favorites + key)
            },
          )
        }
      } else {
        val pinned = entries.filter { it.favoriteKey() in favorites }
        if (pinned.isNotEmpty()) {
          item {
            Text(stringResource(R.string.player_settings_favorites), Modifier.padding(8.dp),
              style = MaterialTheme.typography.labelLarge)
          }
          itemsIndexed(pinned, key = { _, item -> item.favoriteKey() }) { index, entry ->
            SettingsResultRow(entry, index, pinned.size, true, { navigateToSetting(entry) }, {
              favoritesPreference.set(favorites - entry.favoriteKey())
            })
          }
        }
        item {
          Text(stringResource(R.string.player_settings_categories), Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelLarge)
        }
        itemsIndexed(categories) { index, category ->
          Surface(
            onClick = {
              showScreen(category.screen)
              onNavigate()
            },
            shape = rememberConnectedShape(index, categories.size, ConnectedLayout.Vertical),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
          ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp),
              verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              Icon(category.icon, null, Modifier.size(24.dp))
              Text(stringResource(category.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
              Icon(Icons.RoundedFilled.ChevronRight, null, Modifier.size(20.dp))
            }
          }
        }
        if (pinned.isEmpty()) item {
          Text(stringResource(R.string.player_settings_pin_hint), Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

private fun SearchablePreference.favoriteKey(): String {
  val presetTarget = (targetRes ?: titleRes) in setOf(
    R.string.ui_landscape_seekbar_position,
    R.string.ui_portrait_seekbar_position,
  )
  // Keep existing pins when these settings move from the parent page into the region editors.
  val owner = if (screen is ControlLayoutEditorScreen && presetTarget) PlayerControlsPreferencesScreen else screen
  return "${owner.javaClass.name}:$searchTargetKey"
}

@Composable
private fun SettingsResultRow(
  entry: SearchablePreference,
  index: Int,
  count: Int,
  favorite: Boolean,
  onOpen: () -> Unit,
  onFavorite: () -> Unit,
) {
  val resources = LocalResources.current
  val pinLabel = stringResource(if (favorite) R.string.player_settings_unpin else R.string.player_settings_pin)
  Surface(
    onClick = onOpen,
    shape = rememberConnectedShape(index, count, ConnectedLayout.Vertical),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          entry.titleRes?.let { resources.getString(it) } ?: entry.title.orEmpty(),
          style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(searchCategoryTitleRes(entry.category)),
          style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      IconButton(onClick = onFavorite, modifier = Modifier.semantics { stateDescription = pinLabel }) {
        Icon(if (favorite) Icons.RoundedFilled.Favorite else Icons.RoundedFilled.FavoriteBorder, pinLabel,
          tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}
