/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  title: String,
  sortType: String,
  onSortTypeChange: (String) -> Unit,
  sortOrderAsc: Boolean,
  onSortOrderChange: (Boolean) -> Unit,
  types: List<String>,
  icons: List<AppIcon>,
  getLabelForType: (String, Boolean) -> Pair<String, String>,
  modifier: Modifier = Modifier,
  mediaTypeToggles: List<VisibilityToggle> = emptyList(),
  visibilityToggles: List<VisibilityToggle> = emptyList(),
  viewModeSelector: MultiViewModeSelector? = null,
  layoutModeSelector: ViewModeSelector? = null,
  manualGridToggle: VisibilityToggle? = null,
  folderGridColumnSelector: GridColumnSelector? = null,
  videoGridColumnSelector: GridColumnSelector? = null,
  showSortOptions: Boolean = true,
  enableViewModeOptions: Boolean = true,
  enableLayoutModeOptions: Boolean = true,
) {
  if (!isOpen) return

  val haptics = rememberAppHaptics()
  var isFieldsExpanded by rememberSaveable { mutableStateOf(false) }

  val (ascLabel, descLabel) = getLabelForType(sortType, sortOrderAsc)

  AppPickerSheet(
    onDismissRequest = onDismiss,
    title = title,
    actions = {
      TextButton(onClick = onDismiss) {
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_done),
        )
      }
    },
    modifier = modifier,
  ) {
      Column {
        HorizontalDivider()
        Column(
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (showSortOptions) {
            DialogSectionTitle(
              text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_sort_by),
            )
            SortTypeSelector(
              sortType = sortType,
              onSortTypeChange = onSortTypeChange,
              types = types,
              icons = icons,
              modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            SortOrderSelector(
              sortOrderAsc = sortOrderAsc,
              onSortOrderChange = onSortOrderChange,
              ascLabel = ascLabel,
              descLabel = descLabel,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          if (viewModeSelector != null) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            DialogSectionTitle(text = viewModeSelector.label)
            SingleChoiceSegmentedButtonRow(
              modifier = Modifier.fillMaxWidth(),
            ) {
              viewModeSelector.options.forEachIndexed { index, option ->
                SegmentedButton(
                  selected = option.isSelected,
                  onClick = {
                    if (enableViewModeOptions && !option.isSelected) {
                      option.onClick()
                      haptics.selection(true)
                    }
                  },
                  enabled = enableViewModeOptions,
                  shape = SegmentedButtonDefaults.itemShape(index = index, count = viewModeSelector.options.size),
                  colors = themedSegmentedButtonColors(),
                ) {
                  Text(text = option.label)
                }
              }
            }
          }

          if (layoutModeSelector != null) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            DialogSectionTitle(text = layoutModeSelector.label)
            // A page with three layouts owns its own selection, so the third option decides whether
            // the middle one is the one showing; with only two the flag still describes both.
            val thirdSelected = layoutModeSelector.thirdOption?.isSelected == true
            val isFirstSelected = layoutModeSelector.isFirstOptionSelected && !thirdSelected
            val options =
              buildList {
                add(
                  ViewModeOption(
                    label = layoutModeSelector.firstOptionLabel,
                    icon = layoutModeSelector.firstOptionIcon,
                    isSelected = isFirstSelected,
                    onClick = { layoutModeSelector.onViewModeChange(true) },
                  ),
                )
                add(
                  ViewModeOption(
                    label = layoutModeSelector.secondOptionLabel,
                    icon = layoutModeSelector.secondOptionIcon,
                    isSelected = !layoutModeSelector.isFirstOptionSelected && !thirdSelected,
                    onClick = { layoutModeSelector.onViewModeChange(false) },
                  ),
                )
                layoutModeSelector.thirdOption?.let(::add)
              }
            SingleChoiceSegmentedButtonRow(
              modifier = Modifier.fillMaxWidth(),
            ) {
              options.forEachIndexed { index, option ->
                SegmentedButton(
                  selected = option.isSelected,
                  onClick = {
                    if (enableLayoutModeOptions && !option.isSelected) {
                      option.onClick()
                      haptics.selection(true)
                    }
                  },
                  enabled = enableLayoutModeOptions,
                  shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                  colors = themedSegmentedButtonColors(),
                  icon = {
                    Icon(
                      imageVector = option.icon,
                      contentDescription = option.label,
                      modifier = Modifier.size(16.dp),
                    )
                  },
                ) {
                  Text(text = option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
            }
            val showSeparateLayout = layoutModeSelector.checkboxLabel != null && layoutModeSelector.onCheckboxChange != null
            val showManualGrid = manualGridToggle != null && !isFirstSelected
            if (showSeparateLayout || showManualGrid) {
              Spacer(modifier = Modifier.height(4.dp))
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (layoutModeSelector.checkboxLabel != null && layoutModeSelector.onCheckboxChange != null) {
                  Row(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                      .toggleable(
                        value = layoutModeSelector.isCheckboxChecked,
                        enabled = enableLayoutModeOptions,
                        role = androidx.compose.ui.semantics.Role.Checkbox,
                        onValueChange = { checked ->
                          layoutModeSelector.onCheckboxChange.invoke(checked)
                          haptics.selection(checked)
                        },
                      ),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Checkbox(
                      checked = layoutModeSelector.isCheckboxChecked,
                      onCheckedChange = null,
                      enabled = enableLayoutModeOptions,
                    )
                    Text(
                      text = layoutModeSelector.checkboxLabel,
                      modifier = Modifier.weight(1f),
                      style = MaterialTheme.typography.bodyMedium,
                    )
                  }
                }
                if (manualGridToggle != null && showManualGrid) {
                  val isEnabled = enableLayoutModeOptions && manualGridToggle.enabled
                  Row(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                      .toggleable(
                        value = manualGridToggle.checked,
                        enabled = isEnabled,
                        role = androidx.compose.ui.semantics.Role.Checkbox,
                        onValueChange = { checked ->
                          manualGridToggle.onCheckedChange(checked)
                          haptics.selection(checked)
                        },
                      ),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Checkbox(checked = manualGridToggle.checked, onCheckedChange = null, enabled = isEnabled)
                    Text(
                      text = manualGridToggle.label,
                      modifier = Modifier.weight(1f),
                      style = MaterialTheme.typography.bodyMedium,
                    )
                  }
                }
              }
            }
          }

          GridColumnsNextSection(
            folderGridColumnSelector = folderGridColumnSelector,
            videoGridColumnSelector = videoGridColumnSelector,
          )

          if (mediaTypeToggles.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            DialogSectionTitle(
              text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_media_types),
            )
            ToggleChipRow(mediaTypeToggles)
          }

          if (visibilityToggles.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Column(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .animateContentSize(
                    animationSpec = AppMotion.spatial(tween(durationMillis = 250), snap()),
                  ),
            ) {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { isFieldsExpanded = !isFieldsExpanded }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text =
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_fields),
                  style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                  imageVector = if (isFieldsExpanded) Icons.RoundedFilled.KeyboardArrowUp else Icons.RoundedFilled.KeyboardArrowDown,
                  contentDescription =
                    androidx.compose.ui.res.stringResource(
                      if (isFieldsExpanded) {
                        app.gyrolet.mpvrx.R.string.generic_collapse
                      } else {
                        app.gyrolet.mpvrx.R.string.generic_expand
                      },
                    ),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(20.dp),
                )
              }
              if (isFieldsExpanded) {
                ToggleChipRow(visibilityToggles)
              }
            }
          }
        }
      }
    }
}

@Composable
private fun SortTypeSelector(
  sortType: String,
  onSortTypeChange: (String) -> Unit,
  types: List<String>,
  icons: List<AppIcon>,
  modifier: Modifier = Modifier,
) {
  val haptics = rememberAppHaptics()
  val initialFocusRequester = rememberTvInitialFocusRequester(types.isNotEmpty(), requestKey = sortType)
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusGroup()
        .selectableGroup()
        .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    types.forEachIndexed { index, type ->
      val selected = sortType == type
      val containerColor by animateColorAsState(
        targetValue =
          if (selected) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
          },
        animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
        label = "sortSelectionColor",
      )
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(2.dp),
      ) {
        Box(
          modifier =
            Modifier
              .size(64.dp)
              .clip(AppShapeScale.large)
              .background(containerColor)
              .then(if (selected) Modifier.tvInitialFocus(initialFocusRequester) else Modifier)
              .tvFocusHighlight(AppShapeScale.large, focusedScale = 1.04f)
              .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                  if (!selected) {
                    onSortTypeChange(type)
                    haptics.selection(true)
                  }
                },
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
              ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icons[index],
            contentDescription = type,
            modifier = Modifier.size(30.dp),
            tint =
              if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
          )
        }
        Text(
          text = type,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
          color =
            if (selected) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
    }
  }
}

@Composable
private fun SortOrderSelector(
  sortOrderAsc: Boolean,
  onSortOrderChange: (Boolean) -> Unit,
  ascLabel: String,
  descLabel: String,
  modifier: Modifier = Modifier,
) {
  val haptics = rememberAppHaptics()
  val options = listOf(ascLabel, descLabel)
  val selectedIndex = if (sortOrderAsc) 0 else 1

  SingleChoiceSegmentedButtonRow(
    modifier = modifier,
  ) {
    options.forEachIndexed { index, label ->
      SegmentedButton(
        selected = index == selectedIndex,
        onClick = {
          if (index != selectedIndex) {
            onSortOrderChange(index == 0)
            haptics.selection(true)
          }
        },
        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
        colors = themedSegmentedButtonColors(),
        icon = {
          Icon(
            imageVector =
              if (index ==
                0
              ) {
                Icons.RoundedFilled.KeyboardArrowUp
              } else {
                Icons.RoundedFilled.KeyboardArrowDown
              },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
      ) {
        Text(text = label)
      }
    }
  }
}

@Composable
private fun DialogSectionTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
  )
}

/** Shared by the Media Types and Fields groups so the two chip rows cannot drift apart. */
@Composable
private fun ToggleChipRow(toggles: List<VisibilityToggle>) {
  FlowRow(
    modifier =
      Modifier
        .fillMaxWidth()
        .wrapContentHeight(align = Alignment.Top),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    toggles.forEach { toggle ->
      FilterChip(
        selected = toggle.checked,
        onClick = { toggle.onCheckedChange(!toggle.checked) },
        enabled = toggle.enabled,
        label = { Text(text = toggle.label) },
        border =
          FilterChipDefaults.filterChipBorder(
            enabled = toggle.enabled,
            selected = toggle.checked,
            selectedBorderWidth = 1.dp,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
          ),
      )
    }
  }
}

@Composable
private fun GridColumnsNextSection(
  folderGridColumnSelector: GridColumnSelector?,
  videoGridColumnSelector: GridColumnSelector?,
) {
  if (folderGridColumnSelector == null && videoGridColumnSelector == null) return

  val haptic = rememberAppHaptics()

  HorizontalDivider(modifier = Modifier.padding(top = 10.dp))

  if (folderGridColumnSelector != null && videoGridColumnSelector != null) {
    DialogSectionTitle(text = "Grid Columns")
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = folderGridColumnSelector.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = if (folderGridColumnSelector.unitSuffix.isEmpty()) "${folderGridColumnSelector.currentValue}" else "${folderGridColumnSelector.currentValue} ${folderGridColumnSelector.unitSuffix}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Slider(
          value = folderGridColumnSelector.currentValue.toFloat(),
          onValueChange = {
            val newValue = it.roundToInt()
            if (newValue != folderGridColumnSelector.currentValue) {
              folderGridColumnSelector.onValueChange(newValue)
              haptic.tick()
            }
          },
          valueRange = folderGridColumnSelector.valueRange,
          steps = folderGridColumnSelector.steps,
          modifier = Modifier.fillMaxWidth().tvFocusHighlight(RoundedCornerShape(8.dp), focusedScale = 1.01f),
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = videoGridColumnSelector.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = if (videoGridColumnSelector.unitSuffix.isEmpty()) "${videoGridColumnSelector.currentValue}" else "${videoGridColumnSelector.currentValue} ${videoGridColumnSelector.unitSuffix}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Slider(
          value = videoGridColumnSelector.currentValue.toFloat(),
          onValueChange = {
            val newValue = it.roundToInt()
            if (newValue != videoGridColumnSelector.currentValue) {
              videoGridColumnSelector.onValueChange(newValue)
              haptic.tick()
            }
          },
          valueRange = videoGridColumnSelector.valueRange,
          steps = videoGridColumnSelector.steps,
          modifier = Modifier.fillMaxWidth().tvFocusHighlight(RoundedCornerShape(8.dp), focusedScale = 1.01f),
        )
      }
    }
  } else {
    val selector = folderGridColumnSelector ?: videoGridColumnSelector!!
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      DialogSectionTitle(text = selector.label)
      Text(
        text = if (selector.unitSuffix.isEmpty()) "${selector.currentValue}" else "${selector.currentValue} ${selector.unitSuffix}",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Slider(
      value = selector.currentValue.toFloat(),
      onValueChange = {
        val newValue = it.roundToInt()
        if (newValue != selector.currentValue) {
          selector.onValueChange(newValue)
          haptic.tick()
        }
      },
      valueRange = selector.valueRange,
      steps = selector.steps,
      modifier = Modifier.fillMaxWidth().tvFocusHighlight(RoundedCornerShape(8.dp), focusedScale = 1.01f),
    )
  }
}

data class VisibilityToggle(
  val label: String,
  val checked: Boolean,
  val onCheckedChange: (Boolean) -> Unit,
  val enabled: Boolean = true,
)

data class ViewModeOption(
  val label: String,
  val icon: AppIcon,
  val isSelected: Boolean,
  val onClick: () -> Unit,
)

data class MultiViewModeSelector(
  val label: String,
  val options: List<ViewModeOption>,
)

data class ViewModeSelector(
  val label: String,
  val firstOptionLabel: String,
  val secondOptionLabel: String,
  val firstOptionIcon: AppIcon,
  val secondOptionIcon: AppIcon,
  val isFirstOptionSelected: Boolean,
  val onViewModeChange: (Boolean) -> Unit,
  val checkboxLabel: String? = null,
  val isCheckboxChecked: Boolean = false,
  val onCheckboxChange: ((Boolean) -> Unit)? = null,
  /**
   * A third choice, for pages that have one — the snapshots' mosaic. When it is present it owns the
   * selection state: the caller passes [isFirstOptionSelected] = false and marks this one selected.
   */
  val thirdOption: ViewModeOption? = null,
)

data class GridColumnSelector(
  val label: String,
  val currentValue: Int,
  val onValueChange: (Int) -> Unit,
  val valueRange: ClosedFloatingPointRange<Float> = 1f..4f,
  val steps: Int = 2,
  val unitSuffix: String = "cols",
)
