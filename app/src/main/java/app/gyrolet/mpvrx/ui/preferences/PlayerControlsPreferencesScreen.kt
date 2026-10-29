/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.PlayerClockFormat
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SeekbarStyle
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.SeekbarStyleLivePreview
import app.gyrolet.mpvrx.ui.preferences.components.AppSliderPreference
import app.gyrolet.mpvrx.ui.preferences.components.PlayerButtonChip
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference as ListPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt

// Enum to identify which region we are editing
@Serializable
enum class ControlRegion(@StringRes val titleRes: Int) {
  TOP_RIGHT(R.string.pref_layout_top_right_controls),
  BOTTOM_RIGHT(R.string.pref_layout_bottom_right_controls),
  BOTTOM_LEFT(R.string.pref_layout_bottom_left_controls),
  PORTRAIT_BOTTOM(R.string.pref_layout_portrait_bottom_controls),
  ;

  val isPortrait: Boolean get() = this == PORTRAIT_BOTTOM
}

@Serializable
object PlayerControlsPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val television = app.gyrolet.mpvrx.ui.tv.isTelevision()
    val backstack = LocalBackStack.current
    val appearancePrefs = koinInject<AppearancePreferences>()
    val playerPrefs = koinInject<PlayerPreferences>()

    // Get the current state for all four regions
    val topRState by appearancePrefs.topRightControls.collectAsState()
    val bottomRState by appearancePrefs.bottomRightControls.collectAsState()
    val bottomLState by appearancePrefs.bottomLeftControls.collectAsState()
    val portraitBottomState by appearancePrefs.portraitBottomControls.collectAsState()

    val topRightButtons =
      remember(topRState) {
        appearancePrefs.parseButtons(topRState, mutableSetOf())
      }

    val bottomRightButtons =
      remember(bottomRState) {
        appearancePrefs.parseButtons(bottomRState, mutableSetOf())
      }

    val bottomLeftButtons =
      remember(bottomLState) {
        appearancePrefs.parseButtons(bottomLState, mutableSetOf())
      }

    val portraitBottomButtons =
      remember(portraitBottomState) {
        appearancePrefs.parseButtons(portraitBottomState, mutableSetOf())
      }

    Scaffold(
      contentWindowInsets = settingsContentWindowInsets(),
      topBar = {
        SettingsTopAppBar(
          title = stringResource(id = if (television) R.string.pref_section_display_controls else R.string.pref_layout_title),
          onBack = { backstack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(PlayerControlsPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          if (!television) {
            // Landscape Controls Section
            item {
              PreferenceSectionHeader(position = PreferenceSectionPosition.First,
                title = stringResource(R.string.pref_section_landscape_controls),
                modifier = Modifier.settingsSearchTarget(R.string.pref_layout_title),
              )
            }

            item {
              PreferenceCard {
                // Each region owns its own fully rounded surface, rather than sharing
                // a rectangular backdrop with neighboring regions.
                ExpressivePreferenceItem(standalone = true) {
                  Column {
                    PreferenceCategoryWithEditButton(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_layout_top_right_controls),
                      title = stringResource(R.string.pref_layout_top_right_controls),
                      onClick = { backstack.navigateTo(ControlLayoutEditorScreen(ControlRegion.TOP_RIGHT)) },
                    )
                    PreferenceIconSummary(buttons = topRightButtons)
                  }
                }
                ExpressivePreferenceItem(standalone = true) {
                  Column {
                    PreferenceCategoryWithEditButton(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_layout_bottom_right_controls),
                      title = stringResource(R.string.pref_layout_bottom_right_controls),
                      onClick = { backstack.navigateTo(ControlLayoutEditorScreen(ControlRegion.BOTTOM_RIGHT)) },
                    )
                    PreferenceIconSummary(buttons = bottomRightButtons)
                  }
                }
                ExpressivePreferenceItem(standalone = true) {
                  Column {
                    PreferenceCategoryWithEditButton(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_layout_bottom_left_controls),
                      title = stringResource(R.string.pref_layout_bottom_left_controls),
                      onClick = { backstack.navigateTo(ControlLayoutEditorScreen(ControlRegion.BOTTOM_LEFT)) },
                    )
                    PreferenceIconSummary(buttons = bottomLeftButtons)
                  }
                }
              }
            }

            // Portrait Controls Section
            item {
              PreferenceSectionHeader(position = PreferenceSectionPosition.Middle, title = stringResource(R.string.pref_section_portrait_controls))
            }

            item {
              PreferenceCard {
                ExpressivePreferenceItem(standalone = true) {
                  Column {
                    PreferenceCategoryWithEditButton(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_layout_portrait_bottom_controls),
                      title = stringResource(R.string.pref_layout_portrait_bottom_controls),
                      onClick = { backstack.navigateTo(ControlLayoutEditorScreen(ControlRegion.PORTRAIT_BOTTOM)) },
                    )
                    PreferenceIconSummary(buttons = portraitBottomButtons)
                  }
                }
              }
            }
          }

          // Persistent in-video HUD controls, immediately after portrait layout controls.
          item {
            PreferenceSectionHeader(
              position = PreferenceSectionPosition.Middle,
              title = stringResource(R.string.pref_persistent_status_title),
              modifier = Modifier.settingsSearchTarget(R.string.pref_persistent_status_title),
            )
          }
          item {
            PreferenceCard {
              ExpressivePreferenceItem {
                app.gyrolet.mpvrx.ui.preferences.components.AppPreference(
                  title = { Text(stringResource(R.string.pref_persistent_status_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_persistent_status_summary),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  },
                  onClick = { backstack.navigateTo(PersistentStatusBarPreferencesScreen) },
                )
              }
            }
          }

          if (!television) {
            // Seekbar Section
            item {
              PreferenceSectionHeader(position = PreferenceSectionPosition.Middle,
                title = stringResource(R.string.pref_section_seekbar_style),
                modifier = Modifier.settingsSearchTarget(R.string.pref_section_seekbar_style),
              )
            }

            item {
              val seekbarStyle by appearancePrefs.seekbarStyle.collectAsState()
              val useWavySeekbar by playerPrefs.useWavySeekbar.collectAsState()

              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                  verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Text(
                    text = stringResource(R.string.pref_layout_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  SeekbarStyleLivePreview(
                    style = seekbarStyle,
                    useWavySeekbar = useWavySeekbar,
                    modifier = Modifier.fillMaxWidth(),
                  )
                }
                PreferenceCard {
                  SeekbarStyle.entries.forEach { style ->
                    val isSelected = seekbarStyle == style
                    // Every option is an independent Material Expressive tile:
                    // OFF, selected, and a single available option have identical curvature.
                    ExpressivePreferenceItem(selected = isSelected, standalone = true) {
                      ListItem(
                        trailingContent = {
                          RadioButton(selected = isSelected, onClick = null)
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                          containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        modifier = Modifier.selectable(
                          selected = isSelected,
                          role = Role.RadioButton,
                          onClick = { appearancePrefs.seekbarStyle.set(style) },
                        ),
                      ) {
                        Text(style.name)
                      }
                    }
                  }
                }
              }
            }
          }

          // Appearance Section
          item {
            PreferenceSectionHeader(position = PreferenceSectionPosition.Last, title = stringResource(R.string.pref_section_player_controls_appearance))
          }

          item {
            val forceDarkPlayerButtonsBackground by appearancePrefs.forceDarkPlayerButtonsBackground.collectAsState()
            val playerButtonsBackgroundOpacity by appearancePrefs.playerButtonsBackgroundOpacity.collectAsState()
            val storedPlayerTimeToDisappear by playerPrefs.playerTimeToDisappear.collectAsState()
            val playerTimeToDisappear =
              if (television) storedPlayerTimeToDisappear.coerceAtLeast(4000) else storedPlayerTimeToDisappear
            val clockFormat by playerPrefs.clockFormat.collectAsState()
            val predefinedTimeValues =
              if (television) listOf(4000, 4500, 5000, 7500, 10000)
              else listOf(500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000)
            val isCustomTimeValue = !predefinedTimeValues.contains(playerTimeToDisappear)

            var showCustomTimeDialog by remember { mutableStateOf(false) }
            var customTimeValue by remember { mutableStateOf("") }

            PreferenceCard {
              DevicePreference(R.string.pref_player_buttons_opacity) {
                ExpressivePreferenceItem(standalone = true) {
                  AppSliderPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_player_buttons_opacity),
                    value = playerButtonsBackgroundOpacity,
                    onValueChange = { appearancePrefs.playerButtonsBackgroundOpacity.set(it) },
                    sliderValue = playerButtonsBackgroundOpacity,
                    onSliderValueChange = { appearancePrefs.playerButtonsBackgroundOpacity.set(it) },
                    valueRange = 0f..1f,
                    // 5% increments, so the 55% default is reachable again.
                    valueSteps = 19,
                    title = { Text(stringResource(R.string.pref_player_buttons_opacity)) },
                    summary = {
                      Text(
                        text = "${(playerButtonsBackgroundOpacity.coerceIn(0f, 1f) * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                  )
                }

                PreferenceDivider()

                SwitchPreference(
                  groupIndex = 0,
                  groupSize = 1,
                  modifier =
                    Modifier.settingsSearchTarget(
                      R.string.pref_appearance_force_dark_player_buttons_background_title,
                    ),
                  value = forceDarkPlayerButtonsBackground,
                  onValueChange = { appearancePrefs.forceDarkPlayerButtonsBackground.set(it) },
                  title = {
                    Text(
                      text = stringResource(R.string.pref_appearance_force_dark_player_buttons_background_title),
                    )
                  },
                  summary = {
                    Text(
                      text = stringResource(R.string.pref_appearance_force_dark_player_buttons_background_summary),
                    )
                  },
                )

                PreferenceDivider()
              }

              ExpressivePreferenceItem(standalone = true) {
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_player_display_hide_player_control_time),
                value = if (isCustomTimeValue) -1 else playerTimeToDisappear,
                onValueChange = { newValue ->
                  if (newValue == -1) {
                    customTimeValue = playerTimeToDisappear.toString()
                    showCustomTimeDialog = true
                  } else {
                    playerPrefs.playerTimeToDisappear.set(newValue)
                  }
                },
                values = predefinedTimeValues + listOf(-1),
                valueToText = { value ->
                  if (value == -1) {
                    AnnotatedString(stringResource(R.string.pref_gesture_double_tap_custom))
                  } else {
                    AnnotatedString(stringResource(R.string.pref_time_ms_summary, value))
                  }
                },
                title = { Text(text = stringResource(R.string.pref_player_display_hide_player_control_time)) },
                summary = {
                  Text(
                    text =
                      if (isCustomTimeValue) {
                        stringResource(R.string.pref_custom_time_summary_format, playerTimeToDisappear)
                      } else {
                        stringResource(R.string.pref_time_ms_summary, playerTimeToDisappear)
                      },
                  )
                },
              )
              }

              PreferenceDivider()

              ExpressivePreferenceItem(standalone = true) {
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.ui_time_network_clock),
                value = clockFormat,
                onValueChange = { playerPrefs.clockFormat.set(it) },
                values = PlayerClockFormat.entries,
                valueToText = { AnnotatedString(it.displayName) },
                title = {
                  Text(
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_time_network_clock),
                  )
                },
                summary = { Text(clockFormat.displayName) },
              )
              }
            }

            if (showCustomTimeDialog) {
              AlertDialog(
                onDismissRequest = { showCustomTimeDialog = false },
                title = { Text(text = stringResource(R.string.pref_player_display_hide_player_control_time)) },
                text = {
                  Column(
                    modifier =
                      Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                  ) {
                    Text(
                      text = stringResource(R.string.pref_custom_time_dialog_text),
                      modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                      value = customTimeValue,
                      onValueChange = { customTimeValue = it },
                      label = { Text(stringResource(R.string.pref_custom_time_label)) },
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                      modifier = Modifier.fillMaxWidth(),
                      singleLine = true,
                    )
                  }
                },
                confirmButton = {
                  TextButton(
                    onClick = {
                      val value = customTimeValue.toIntOrNull()
                      if (value != null && value in 100..1000000000000) {
                        playerPrefs.playerTimeToDisappear.set(if (television) value.coerceAtLeast(4000) else value)
                        showCustomTimeDialog = false
                      }
                    },
                  ) {
                    Text(stringResource(R.string.generic_ok))
                  }
                },
                dismissButton = {
                  TextButton(onClick = { showCustomTimeDialog = false }) {
                    Text(stringResource(R.string.generic_cancel))
                  }
                },
              )
            }
          }
        }
      }
    }
  }

  /**
   * Custom composable for the category header with an Edit button.
   */
  @Composable
  private fun PreferenceCategoryWithEditButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    Row(
      modifier =
        modifier
          .fillMaxWidth()
          .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
      // Apply padding to Row - minimal padding for tighter appearance
      verticalAlignment = Alignment.CenterVertically, // Align items vertically
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f), // Text takes all available space, pushing button to end
      )
      IconButton(onClick = onClick) {
        Icon(
          imageVector = Icons.RoundedFilled.Edit,
          contentDescription = stringResource(R.string.pref_edit_region_content_desc, title),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  /**
   * Custom composable to show a row of icons for the summary.
   */
  @OptIn(ExperimentalLayoutApi::class)
  @Composable
  private fun PreferenceIconSummary(buttons: List<PlayerButton>) {
    FlowRow(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp), // Increased spacing
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
      if (buttons.isEmpty()) {
        Text(
          stringResource(R.string.pref_none),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.outline,
        )
      } else {
        buttons.forEach { button ->
          // Use the chip in "preview mode" (no badge, enabled=true but no onClick)
          PlayerButtonChip(
            button = button,
            enabled = true,
            onClick = null,
            badgeIcon = null,
            badgeColor = null,
          )
        }
      }
    }
  }
}
