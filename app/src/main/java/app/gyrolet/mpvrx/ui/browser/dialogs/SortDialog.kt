package app.gyrolet.mpvrx.ui.browser.dialogs

import kotlin.math.roundToInt
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale

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
  visibilityToggles: List<VisibilityToggle> = emptyList(),
  viewModeSelector: MultiViewModeSelector? = null,
  layoutModeSelector: ViewModeSelector? = null,
  folderGridColumnSelector: GridColumnSelector? = null,
  videoGridColumnSelector: GridColumnSelector? = null,
  showSortOptions: Boolean = true,
  enableViewModeOptions: Boolean = true,
  enableLayoutModeOptions: Boolean = true,
) {
  if (!isOpen) return

  var isFieldsExpanded by rememberSaveable { mutableStateOf(false) }

  val (ascLabel, descLabel) = getLabelForType(sortType, sortOrderAsc)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column {
        HorizontalDivider()
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        ) {
          if (showSortOptions) {
            DialogSectionTitle(text = "Sort by")
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
                  onClick = { if (enableViewModeOptions) option.onClick() },
                  shape = SegmentedButtonDefaults.itemShape(index = index, count = viewModeSelector.options.size),
                  colors = SegmentedButtonDefaults.colors(
                    activeContentColor = MaterialTheme.colorScheme.primary,
                    activeBorderColor = MaterialTheme.colorScheme.primary,
                  ),
                ) {
                  Text(text = option.label)
                }
              }
            }
          }

          if (layoutModeSelector != null) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            DialogSectionTitle(text = layoutModeSelector.label)
            SingleChoiceSegmentedButtonRow(
              modifier = Modifier.fillMaxWidth(),
            ) {
              val isFirstSelected = layoutModeSelector.isFirstOptionSelected
              SegmentedButton(
                selected = isFirstSelected,
                onClick = { if (enableLayoutModeOptions) layoutModeSelector.onViewModeChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                colors = SegmentedButtonDefaults.colors(
                  activeContentColor = MaterialTheme.colorScheme.primary,
                  activeBorderColor = MaterialTheme.colorScheme.primary,
                ),
                icon = {
                  Icon(
                    imageVector = layoutModeSelector.firstOptionIcon,
                    contentDescription = layoutModeSelector.firstOptionLabel,
                    modifier = Modifier.size(16.dp),
                  )
                },
              ) {
                Text(text = layoutModeSelector.firstOptionLabel)
              }
              SegmentedButton(
                selected = !isFirstSelected,
                onClick = { if (enableLayoutModeOptions) layoutModeSelector.onViewModeChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                colors = SegmentedButtonDefaults.colors(
                  activeContentColor = MaterialTheme.colorScheme.primary,
                  activeBorderColor = MaterialTheme.colorScheme.primary,
                ),
                icon = {
                  Icon(
                    imageVector = layoutModeSelector.secondOptionIcon,
                    contentDescription = layoutModeSelector.secondOptionLabel,
                    modifier = Modifier.size(16.dp),
                  )
                },
              ) {
                Text(text = layoutModeSelector.secondOptionLabel)
              }
            }
          }

          GridColumnsNextSection(
            folderGridColumnSelector = folderGridColumnSelector,
            videoGridColumnSelector = videoGridColumnSelector,
          )

          if (visibilityToggles.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = 250))
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { isFieldsExpanded = !isFieldsExpanded }
                  .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_fields),
                  style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                  imageVector = if (isFieldsExpanded) Icons.RoundedFilled.KeyboardArrowUp else Icons.RoundedFilled.KeyboardArrowDown,
                  contentDescription = if (isFieldsExpanded) "Collapse" else "Expand",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(20.dp)
                )
              }
              if (isFieldsExpanded) {
                FlowRow(
                  modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  visibilityToggles.forEach { toggle ->
                    FilterChip(
                      selected = toggle.checked,
                      onClick = { toggle.onCheckedChange(!toggle.checked) },
                      label = { Text(text = toggle.label) },
                      border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = toggle.checked,
                        selectedBorderWidth = 1.dp,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                      ),
                    )
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_done))
      }
    },
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
    modifier = modifier
      .widthIn(max = 500.dp)
      .fillMaxWidth(0.88f),
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
    ),
  )
}

@Composable
private fun SortTypeSelector(
  sortType: String,
  onSortTypeChange: (String) -> Unit,
  types: List<String>,
  icons: List<AppIcon>,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    types.forEachIndexed { index, type ->
      val selected = sortType == type
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(2.dp),
      ) {
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(AppShapeScale.large)
            .background(
              color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
              } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
              },
            )
            .clickable(
              onClick = { onSortTypeChange(type) },
              interactionSource = remember { MutableInteractionSource() },
              indication = ripple(bounded = true),
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icons[index],
            contentDescription = type,
            modifier = Modifier.size(30.dp),
            tint = if (selected) {
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
          color = if (selected) {
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
  val options = listOf(ascLabel, descLabel)
  val selectedIndex = if (sortOrderAsc) 0 else 1

  SingleChoiceSegmentedButtonRow(
    modifier = modifier,
  ) {
    options.forEachIndexed { index, label ->
      SegmentedButton(
        selected = index == selectedIndex,
        onClick = { onSortOrderChange(index == 0) },
        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
        colors = SegmentedButtonDefaults.colors(
          activeContentColor = MaterialTheme.colorScheme.primary,
          activeBorderColor = MaterialTheme.colorScheme.primary,
        ),
        icon = {
          Icon(
            imageVector = if (index == 0) Icons.RoundedFilled.KeyboardArrowUp else Icons.RoundedFilled.KeyboardArrowDown,
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

@Composable
private fun GridColumnsNextSection(
  folderGridColumnSelector: GridColumnSelector?,
  videoGridColumnSelector: GridColumnSelector?,
) {
  if (folderGridColumnSelector == null && videoGridColumnSelector == null) return

  HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
  DialogSectionTitle(text = "Grid Columns")

  val haptic = LocalHapticFeedback.current

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    if (folderGridColumnSelector != null) {
      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_folder_grid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "${folderGridColumnSelector.currentValue} cols",
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
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
          },
          valueRange = folderGridColumnSelector.valueRange,
          steps = folderGridColumnSelector.steps,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    if (videoGridColumnSelector != null) {
      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_video_grid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "${videoGridColumnSelector.currentValue} cols",
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
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
          },
          valueRange = videoGridColumnSelector.valueRange,
          steps = videoGridColumnSelector.steps,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

data class VisibilityToggle(
  val label: String,
  val checked: Boolean,
  val onCheckedChange: (Boolean) -> Unit,
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
)

data class GridColumnSelector(
  val label: String,
  val currentValue: Int,
  val onValueChange: (Int) -> Unit,
  val valueRange: ClosedFloatingPointRange<Float> = 1f..4f,
  val steps: Int = 2,
)


