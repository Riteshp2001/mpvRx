package app.gyrolet.mpvrx.preferences

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.ui.theme.AppTheme
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.spacing
import kotlinx.collections.immutable.ImmutableList

class AppearancePreferences(
  preferenceStore: PreferenceStore,
) {
  val darkMode = preferenceStore.getEnum("dark_mode", DarkMode.System)
  val appTheme = preferenceStore.getEnum("app_theme", AppTheme.Dynamic)
  val amoledMode = preferenceStore.getBoolean("amoled_mode", false)
  val useSystemFont = preferenceStore.getBoolean("use_system_font", false)
  val unlimitedNameLines = preferenceStore.getBoolean("unlimited_name_lines", false)
  val hidePlayerButtonsBackground = preferenceStore.getBoolean("hide_player_buttons_background", false)
  val showUnplayedOldVideoLabel = preferenceStore.getBoolean("show_unplayed_old_video_label", true)
  val unplayedOldVideoDays = preferenceStore.getInt("unplayed_old_video_days", 7)
  val showNetworkThumbnails = preferenceStore.getBoolean("show_network_thumbnails", false)
  val seekbarStyle = preferenceStore.getEnum("seekbar_style", SeekbarStyle.Thick)
  val portraitPlaybackControlsPosition =
    preferenceStore.getEnum("portrait_playback_controls_position", PortraitPlaybackControlsPosition.Center)
  val navigationStyle = preferenceStore.getEnum("navigation_style", NavigationStyle.Slide)
  val showHomeTab = preferenceStore.getBoolean("show_home_tab", true)
  val showRecentsTab = preferenceStore.getBoolean("show_recents_tab", true)
  val showPlaylistsTab = preferenceStore.getBoolean("show_playlists_tab", true)
  val showNetworkTab = preferenceStore.getBoolean("show_network_tab", false)

  val topLeftControls =
    preferenceStore.getString(
      "top_left_controls",
      "BACK_ARROW,VIDEO_TITLE",
    )

  val topRightControls =
    preferenceStore.getString(
      "top_right_controls",
      "CAST,CURRENT_CHAPTER,DECODER,AUDIO_TRACK,SUBTITLES,MORE_OPTIONS",
    )

  val bottomRightControls =
    preferenceStore.getString(
      "bottom_right_controls",
      "FRAME_NAVIGATION,VIDEO_ZOOM,PICTURE_IN_PICTURE,ASPECT_RATIO",
    )

  val bottomLeftControls =
    preferenceStore.getString(
      "bottom_left_controls",
      "BACKGROUND_PLAYBACK,LOCK_CONTROLS,SCREEN_ROTATION,PLAYBACK_SPEED,REPEAT_MODE,SHUFFLE,AB_LOOP",
    )

  val portraitBottomControls =
    preferenceStore.getString(
      "portrait_bottom_controls",
      "CAST,SCREEN_ROTATION,DECODER,AUDIO_TRACK,SUBTITLES,BOOKMARKS_CHAPTERS,PLAYBACK_SPEED,BACKGROUND_PLAYBACK,REPEAT_MODE,SHUFFLE,VIDEO_ZOOM,FRAME_NAVIGATION,ASPECT_RATIO,PICTURE_IN_PICTURE,LOCK_CONTROLS,MORE_OPTIONS",
    )

  private val castButtonMigrationComplete =
    preferenceStore.getBoolean("cast_button_migration_complete", false)

  init {
    if (!castButtonMigrationComplete.get()) {
      val landscapeButtons =
        listOf(
          topLeftControls.get(),
          topRightControls.get(),
          bottomRightControls.get(),
          bottomLeftControls.get(),
        ).flatMap { it.split(',') }
          .map { it.trim().uppercase() }
      if ("CAST" !in landscapeButtons) {
        topRightControls.set("CAST,${topRightControls.get()}")
      }
      val portraitButtons = portraitBottomControls.get().split(',').map { it.trim().uppercase() }
      if ("CAST" !in portraitButtons) {
        portraitBottomControls.set("CAST,${portraitBottomControls.get()}")
      }
      castButtonMigrationComplete.set(true)
    }
  }

  fun parseButtons(
    csv: String,
    usedButtons: MutableSet<PlayerButton>,
  ): List<PlayerButton> =
    csv
      .splitToSequence(',')
      .map { it.trim().uppercase() }
      .mapNotNull { name ->
        try {
          PlayerButton.valueOf(name)
        } catch (_: IllegalArgumentException) {
          null
        }
      }.filter { it != PlayerButton.NONE }
      .filter { usedButtons.add(it) }
      .toList()
}

enum class PortraitPlaybackControlsPosition(val displayName: String) {
  Center("Center of screen"),
  BelowSeekbar("Between seekbar and controls"),
}

@Composable
fun MultiChoiceSegmentedButton(
  choices: ImmutableList<String>,
  selectedIndices: ImmutableList<Int>,
  onClick: (Int, Offset) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(MaterialTheme.spacing.medium),
    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
  ) {
    choices.forEachIndexed { index, choice ->
      var buttonCenter by remember(choice) { mutableStateOf(Offset.Zero) }
      ToggleButton(
        checked = selectedIndices.contains(index),
        onCheckedChange = { onClick(index, buttonCenter) },
        modifier = Modifier
          .weight(1f)
          .defaultMinSize(minHeight = MaterialTheme.spacing.extraLarge)
          .onGloballyPositioned { buttonCenter = it.boundsInWindow().center }
          .semantics { role = Role.RadioButton },
        colors = ToggleButtonDefaults.toggleButtonColors(
          checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
          checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
          contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        ),
        shapes = when (index) {
          0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
          choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
          else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        },
      ) {
        Text(text = choice)
      }
    }
  }
}

