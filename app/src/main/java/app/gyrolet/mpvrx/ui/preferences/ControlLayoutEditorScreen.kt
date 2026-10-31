/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlaybackControlsPlacement
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.allPlayerButtons
import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.outsideShadow
import app.gyrolet.mpvrx.ui.player.controls.components.AbLoopIcon
import app.gyrolet.mpvrx.ui.player.controls.VideoControlsLayoutPreset
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference
import app.gyrolet.mpvrx.ui.preferences.components.PlayerButtonChip
import app.gyrolet.mpvrx.ui.preferences.components.PlayerLayoutPreview
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import app.gyrolet.mpvrx.ui.icons.Icon as AppSymbolIcon

@Serializable
data class ControlLayoutEditorScreen(
  val region: ControlRegion,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val preferences = koinInject<AppearancePreferences>()

    val prefToEdit: Preference<String> = when (region) {
      ControlRegion.TOP_RIGHT -> preferences.topRightControls
      ControlRegion.BOTTOM_RIGHT -> preferences.bottomRightControls
      ControlRegion.BOTTOM_LEFT -> preferences.bottomLeftControls
      ControlRegion.PORTRAIT_BOTTOM -> preferences.portraitBottomControls
    }
    val selection by prefToEdit.collectAsState()
    val selectedButtons = remember(selection) { preferences.parseButtons(selection, mutableSetOf()) }
    val topRightState by preferences.topRightControls.collectAsState()
    val topLeftState by preferences.topLeftControls.collectAsState()
    val bottomRightState by preferences.bottomRightControls.collectAsState()
    val bottomLeftState by preferences.bottomLeftControls.collectAsState()
    val portraitState by preferences.portraitBottomControls.collectAsState()
    val disabledButtons = remember(region, topRightState, topLeftState, bottomRightState, bottomLeftState) {
      val otherRegions = when (region) {
        ControlRegion.TOP_RIGHT -> listOf(topLeftState, bottomRightState, bottomLeftState)
        ControlRegion.BOTTOM_RIGHT -> listOf(topLeftState, topRightState, bottomLeftState)
        ControlRegion.BOTTOM_LEFT -> listOf(topLeftState, topRightState, bottomRightState)
        ControlRegion.PORTRAIT_BOTTOM -> emptyList()
      }
      preferences.parseButtons(otherRegions.joinToString(","), mutableSetOf()).toSet()
    }
    val (topRightButtons, bottomRightButtons, bottomLeftButtons) =
      remember(topRightState, bottomRightState, bottomLeftState) {
        val used = mutableSetOf<PlayerButton>()
        listOf(
          preferences.parseButtons(topRightState, used),
          preferences.parseButtons(bottomRightState, used),
          preferences.parseButtons(bottomLeftState, used),
        )
      }
    val portraitButtons = remember(portraitState) { preferences.parseButtons(portraitState, mutableSetOf()) }
    val seekbarPreference =
      if (region.isPortrait) preferences.portraitSeekbarPlacement else preferences.landscapeSeekbarPlacement
    val seekbarPlacement by seekbarPreference.collectAsState()
    val preset = VideoControlsLayoutPreset.fromPreferences(seekbarPlacement)
    val seekbarTitle = if (region.isPortrait) R.string.ui_portrait_seekbar_position else R.string.ui_landscape_seekbar_position
    val playbackPreference =
      if (region.isPortrait) preferences.portraitPlaybackControlsPlacement else preferences.landscapePlaybackControlsPlacement
    val playbackControlsPlacement by playbackPreference.collectAsState()
    val playbackTitle =
      if (region.isPortrait) R.string.pref_layout_portrait_playback_position else R.string.pref_layout_landscape_playback_position
    var previewPlaylist by rememberSaveable(region) { mutableStateOf(true) }
    val embeddedInPlayer = LocalPlayerSettingsPanel.current
    val title = stringResource(region.titleRes)
    var showResetDialog by rememberSaveable(region) { mutableStateOf(false) }

    fun updateSelectedButtons(change: (List<PlayerButton>) -> List<PlayerButton>) {
      val current = preferences.parseButtons(prefToEdit.get(), mutableSetOf())
      val next = change(current).distinct().filterNot { it in disabledButtons }
      val value = next.joinToString(",") { it.name }
      if (prefToEdit.get() != value) prefToEdit.set(value)
    }

    if (showResetDialog) {
      ConfirmDialog(
        title = stringResource(R.string.controls_reset_default_title),
        subtitle = stringResource(R.string.controls_reset_default_summary),
        onConfirm = {
          updateSelectedButtons { preferences.parseButtons(prefToEdit.defaultValue(), mutableSetOf()) }
          showResetDialog = false
        },
        onCancel = {
          showResetDialog = false
        },
      )
    }

    Scaffold(
      contentWindowInsets = settingsContentWindowInsets(),
      topBar = {
        if (embeddedInPlayer) {
          androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            app.gyrolet.mpvrx.presentation.components.AppDialogTextButton(onClick = { showResetDialog = true }) {
              Text(stringResource(R.string.pref_layout_reset_default))
            }
          }
        } else TopAppBar(
          title = { Text(text = title) },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              AppSymbolIcon(
                Icons.RoundedFilled.ArrowBack,
                contentDescription =
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.back),
              )
            }
          },
          actions = {
            IconButton(onClick = { showResetDialog = true }) {
              AppSymbolIcon(
                Icons.RoundedFilled.Refresh,
                contentDescription =
                  androidx.compose.ui.res.stringResource(
                    app.gyrolet.mpvrx.R.string.pref_layout_reset_default,
                  ),
              )
            }
          },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val gridState = rememberLazyGridState()
        val searchTarget by SettingsSearchNavigation.target.collectAsState()
        LaunchedEffect(searchTarget?.requestId, region) {
          if (searchTarget?.screen == this@ControlLayoutEditorScreen) gridState.scrollToItem(0)
        }
        val reorderableState =
          rememberReorderableLazyGridState(gridState) { from, to ->
            val fromKey = from.key as? PlayerButton
            val toKey = to.key as? PlayerButton

            updateSelectedButtons { current ->
              val fromIndex = current.indexOf(fromKey)
              val toIndex = current.indexOf(toKey)
              if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
                current.toMutableList().apply {
                  add(toIndex, removeAt(fromIndex))
                }
              } else current
            }
          }

        val editorContent: @Composable (Modifier) -> Unit = { gridModifier ->
          LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 72.dp),
            contentPadding =
              androidx.compose.foundation.layout
                .PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = gridModifier,
          ) {
            item(key = "layout-preset", span = { GridItemSpan(maxLineSpan) }) {
              androidx.compose.material3.Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
              ) {
                AppListPreference(
                  modifier = Modifier.settingsSearchTarget(seekbarTitle),
                  value = preset,
                  onValueChange = { seekbarPreference.set(it.seekbarPlacement) },
                  values = VideoControlsLayoutPreset.entries,
                  valueToText = { AnnotatedString(stringResource(it.titleRes)) },
                  title = { Text(stringResource(R.string.player_layout_preset_title)) },
                  summary = { Text(stringResource(preset.titleRes)) },
                )
              }
            }
            item(key = "playback-placement", span = { GridItemSpan(maxLineSpan) }) {
              androidx.compose.material3.Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
              ) {
                AppListPreference(
                  modifier = Modifier.settingsSearchTarget(playbackTitle),
                  value = playbackControlsPlacement,
                  onValueChange = { playbackPreference.set(it) },
                  values = PlaybackControlsPlacement.entries,
                  valueToText = { AnnotatedString(stringResource(it.titleRes)) },
                  title = { Text(stringResource(playbackTitle)) },
                  summary = { Text(stringResource(playbackControlsPlacement.titleRes)) },
                )
              }
            }
            // --- 1. Header & Active Selected Zone ---
            item(span = { GridItemSpan(maxLineSpan) }) {
              androidx.compose.material3.Text(
                text =
                  androidx.compose.ui.res.stringResource(
                    app.gyrolet.mpvrx.R.string.ui_long_press_to_reorder_items_tap_the_icon_to_remove_them,
                  ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
              )
            }

            // --- 2. Selected Controls (Reorderable) ---
            if (selectedButtons.isEmpty()) {
              item(span = { GridItemSpan(maxLineSpan) }) {
                androidx.compose.material3.Surface(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .height(120.dp),
                  shape =
                    androidx.compose.foundation.shape
                      .RoundedCornerShape(24.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLowest,
                  border =
                    BorderStroke(
                      1.dp,
                      MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                  androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                  ) {
                    AppSymbolIcon(
                      imageVector = Icons.RoundedFilled.AddCircle,
                      contentDescription = null,
                      modifier =
                        Modifier
                          .size(32.dp)
                          .padding(bottom = 8.dp),
                      tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    )
                    androidx.compose.material3.Text(
                      text =
                        androidx.compose.ui.res.stringResource(
                          app.gyrolet.mpvrx.R.string.ui_drop_zone_is_empty,
                        ),
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.Text(
                      text =
                        androidx.compose.ui.res.stringResource(
                          app.gyrolet.mpvrx.R.string.ui_tap_buttons_from_the_available_palette_below,
                        ),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                  }
                }
              }
            } else {
              items(
                count = selectedButtons.size,
                key = { selectedButtons[it] },
                span = { index ->
                  val button = selectedButtons[index]
                  if (button == PlayerButton.CURRENT_CHAPTER || button == PlayerButton.VIDEO_TITLE) {
                    GridItemSpan(maxLineSpan)
                  } else {
                    GridItemSpan(1)
                  }
                },
              ) { index ->
                val button = selectedButtons[index]
                ReorderableItem(reorderableState, key = button) { isDragging ->
                  val elevation by animateFloatAsState(
                    targetValue = if (isDragging) 8f else 0f,
                    label = "drag_elevation",
                  )

                  // Wrap in Box to control alignment/filling within the grid cell
                  androidx.compose.material3.Surface(
                    modifier =
                      Modifier
                        .draggableHandle()
                        .then(
                          if (button == PlayerButton.CURRENT_CHAPTER || button == PlayerButton.VIDEO_TITLE) {
                            Modifier.wrapContentWidth(Alignment.Start)
                          } else {
                            Modifier
                          },
                        ).outsideShadow(
                          androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                          radius = elevation.dp,
                        ),
                    shape =
                      androidx.compose.foundation.shape
                        .RoundedCornerShape(24.dp),
                    // Match chip border radius
                    color = Color.Transparent,
                  ) {
                    PlayerButtonChip(
                      button = button,
                      enabled = true,
                      onClick = { updateSelectedButtons { it - button } },
                      badgeIcon = Icons.RoundedFilled.RemoveCircle,
                      badgeColor = Color(0xFFEF5350),
                    )
                  }
                }
              }
            }

            // --- 3. SPaceing ---
            item(span = { GridItemSpan(maxLineSpan) }) {
              Spacer(modifier = Modifier.height(16.dp))
            }

            // --- 4. Available Controls  ---
            item(span = { GridItemSpan(maxLineSpan) }) {
              androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                shape =
                  androidx.compose.foundation.shape
                    .RoundedCornerShape(24.dp),
                colors =
                  androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                  ),
              ) {
                FlowRow(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .padding(16.dp),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  val availableButtons = allPlayerButtons.filter { it !in selectedButtons }
                  availableButtons.forEach { button ->
                    val isEnabled = button !in disabledButtons
                    PlayerButtonChip(
                      button = button,
                      enabled = isEnabled,
                      onClick = { updateSelectedButtons { it + button } },
                      badgeIcon = Icons.RoundedFilled.AddCircle,
                      badgeColor =
                        if (isEnabled) {
                          MaterialTheme.colorScheme.primary
                        } else {
                          MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    )
                  }

                  if (availableButtons.isEmpty()) {
                    androidx.compose.foundation.layout.Box(
                      modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                      contentAlignment = Alignment.Center,
                    ) {
                      androidx.compose.material3.Text(
                        text =
                          androidx.compose.ui.res.stringResource(
                            app.gyrolet.mpvrx.R.string.ui_all_available_buttons_are_in_use,
                          ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                }
              }
            }

            // --- 5. Icons Legend ---
            item(span = { GridItemSpan(maxLineSpan) }) {
              IconsLegend()
              Spacer(Modifier.height(16.dp))
            }
          }
        }
        val previewContent: @Composable (Modifier) -> Unit = { modifier ->
          PlayerLayoutPreview(
            region = region,
            topRightButtons = topRightButtons,
            bottomRightButtons = bottomRightButtons,
            bottomLeftButtons = bottomLeftButtons,
            portraitBottomButtons = portraitButtons,
            preset = preset,
            playlist = previewPlaylist,
            onPlaylistChange = { previewPlaylist = it },
            modifier = modifier,
          )
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
          val sideBySide = maxWidth >= 560.dp || (maxWidth >= 400.dp && maxHeight < 360.dp)
          val previewHeight = (maxHeight * 0.36f).coerceIn(96.dp, 260.dp).coerceAtMost(maxHeight / 2)
          if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
              previewContent(Modifier.weight(0.45f).fillMaxHeight().padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
              editorContent(Modifier.weight(0.55f).fillMaxHeight())
            }
          } else {
            Column(Modifier.fillMaxSize()) {
              previewContent(Modifier.fillMaxWidth().height(previewHeight).padding(horizontal = 16.dp, vertical = 4.dp))
              editorContent(Modifier.weight(1f).fillMaxWidth())
            }
          }
        }
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI Components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IconsLegend() {
  androidx.compose.material3.Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(top = 24.dp, bottom = 8.dp),
    shape =
      androidx.compose.foundation.shape
        .RoundedCornerShape(24.dp),
    colors =
      androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
  ) {
    androidx.compose.foundation.layout.Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Header
      androidx.compose.foundation.layout.Column {
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_icons_legend),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_what_is_each_icon_for),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // FlowRow grid of icons
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // We use allPlayerButtons to avoid showing NONE and defaults like Back/Title which are static
        allPlayerButtons.forEach { button ->
          androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.wrapContentWidth(),
          ) {
            if (button == PlayerButton.AB_LOOP) {
              AbLoopIcon(
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              val modifier =
                if (button == PlayerButton.VERTICAL_FLIP) {
                  Modifier.rotate(90f)
                } else {
                  Modifier
                }

              AppSymbolIcon(
                imageVector = button.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                  Modifier
                    .size(20.dp)
                    .then(modifier),
              )
            }

            Text(
              text =
                app.gyrolet.mpvrx.preferences
                  .getPlayerButtonLabel(button),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(end = 4.dp),
            )
          }
        }
      }
    }
  }
}
