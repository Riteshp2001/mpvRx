package app.gyrolet.mpvrx.preferences

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
  val navigationStyle = preferenceStore.getEnum("navigation_style", NavigationStyle.Slide)
  val showHomeTab = preferenceStore.getBoolean("show_home_tab", true)
  val showRecentsTab = preferenceStore.getBoolean("show_recents_tab", true)
  val showPlaylistsTab = preferenceStore.getBoolean("show_playlists_tab", true)
  val showNetworkTab = preferenceStore.getBoolean("show_network_tab", false)

  // Liquid Glass Effects
  val enableLiquidGlass = preferenceStore.getBoolean("enable_liquid_glass", false)
  val liquidToggleColor = preferenceStore.getInt("liquid_toggle_color", 0xFF000080.toInt())
  val liquidSeekbarColor = preferenceStore.getInt("liquid_seekbar_color", 0xFFFF4500.toInt())

  // Liquid Dialog Parameters
  val liquidDialogBlur = preferenceStore.getFloat("liquid_dialog_blur", 32f)
  val liquidDialogSaturation = preferenceStore.getFloat("liquid_dialog_saturation", 1.3f)
  val liquidDialogBrightness = preferenceStore.getFloat("liquid_dialog_brightness", 0.08f)
  val liquidDialogLensRadius = preferenceStore.getFloat("liquid_dialog_lens_radius", 55f)
  val liquidDialogLensDepth = preferenceStore.getFloat("liquid_dialog_lens_depth", 85f)
  val liquidDialogContainerAlpha = preferenceStore.getFloat("liquid_dialog_container_alpha", 0.35f)

  // Liquid Button Parameters
  val liquidButtonBlur = preferenceStore.getFloat("liquid_button_blur", 26f)
  val liquidButtonLensRadius = preferenceStore.getFloat("liquid_button_lens_radius", 42f)
  val liquidButtonLensDepth = preferenceStore.getFloat("liquid_button_lens_depth", 72f)

  val topLeftControls =
    preferenceStore.getString(
      "top_left_controls",
      "BACK_ARROW,VIDEO_TITLE",
    )

  val topRightControls =
    preferenceStore.getString(
      "top_right_controls",
      "CURRENT_CHAPTER,DECODER,AUDIO_TRACK,SUBTITLES,TIME_NETWORK,VIDEO_FILTERS,MORE_OPTIONS",
    )

  val bottomRightControls =
    preferenceStore.getString(
      "bottom_right_controls",
      "FRAME_NAVIGATION,VIDEO_ZOOM,PICTURE_IN_PICTURE,ASPECT_RATIO,AMBIENT_MODE",
    )

  val bottomLeftControls =
    preferenceStore.getString(
      "bottom_left_controls",
      "BACKGROUND_PLAYBACK,LOCK_CONTROLS,SCREEN_ROTATION,PLAYBACK_SPEED,REPEAT_MODE,SHUFFLE,AB_LOOP",
    )

  val portraitBottomControls =
    preferenceStore.getString(
      "portrait_bottom_controls",
      "SCREEN_ROTATION,DECODER,AUDIO_TRACK,SUBTITLES,BOOKMARKS_CHAPTERS,PLAYBACK_SPEED,BACKGROUND_PLAYBACK,REPEAT_MODE,SHUFFLE,VIDEO_ZOOM,FRAME_NAVIGATION,ASPECT_RATIO,PICTURE_IN_PICTURE,LOCK_CONTROLS,TIME_NETWORK,AMBIENT_MODE,VIDEO_FILTERS,MORE_OPTIONS",
    )

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

@Composable
fun MultiChoiceSegmentedButton(
  choices: ImmutableList<String>,
  selectedIndices: ImmutableList<Int>,
  onClick: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  MultiChoiceSegmentedButtonRow(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(MaterialTheme.spacing.medium),
  ) {
    choices.forEachIndexed { index, choice ->
      SegmentedButton(
        checked = selectedIndices.contains(index),
        onCheckedChange = { onClick(index) },
        shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
      ) {
        Text(text = choice)
      }
    }
  }
}

