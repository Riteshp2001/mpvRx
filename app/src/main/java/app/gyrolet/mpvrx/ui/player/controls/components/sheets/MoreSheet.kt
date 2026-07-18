package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.player.anime4k.Anime4KUiState
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.spacing
import `is`.xyz.mpv.MPVLib
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MoreSheet(
  remainingTime: Int,
  onStartTimer: (Int) -> Unit,
  onDismissRequest: () -> Unit,
  onEnterFiltersPanel: () -> Unit,
  onEnterLuaScriptsPanel: () -> Unit,
  anime4KUiState: Anime4KUiState,
  onAnime4KModeSelected: (Anime4KManager.Mode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val advancedPreferences = koinInject<AdvancedPreferences>()
  val statisticsPage by advancedPreferences.enabledStatisticsPage.collectAsState()
  val enableLuaScripts by advancedPreferences.enableLuaScripts.collectAsState()
  val selectedLuaScripts by advancedPreferences.selectedLuaScripts.collectAsState()
  val mpvConfStorageLocation by advancedPreferences.mpvConfStorageUri.collectAsState()
  
  PlayerSheet(
    onDismissRequest,
    modifier,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(MaterialTheme.spacing.medium)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(id = R.string.player_sheets_more_title),
          style = MaterialTheme.typography.headlineMedium,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
        ) {
          var isSleepTimerDialogShown by remember { mutableStateOf(false) }
          TextButton(onClick = { isSleepTimerDialogShown = true }) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
              Icon(imageVector = Icons.RoundedFilled.Timer, contentDescription = null)
              Text(
                text =
                  if (remainingTime == 0) {
                    stringResource(R.string.timer_title)
                  } else {
                    stringResource(
                      R.string.timer_remaining,
                      DateUtils.formatElapsedTime(remainingTime.toLong()),
                    )
                  },
              )
              if (isSleepTimerDialogShown) {
                TimePickerDialog(
                  remainingTime = remainingTime,
                  onDismissRequest = { isSleepTimerDialogShown = false },
                  onTimeSelect = onStartTimer,
                )
              }
            }
          }
          TextButton(onClick = onEnterFiltersPanel) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
              Icon(imageVector = Icons.RoundedFilled.Tune, contentDescription = null)
              Text(text = stringResource(id = R.string.player_sheets_filters_title))
            }
          }
          TextButton(
            onClick = onEnterLuaScriptsPanel,
            enabled = mpvConfStorageLocation.isNotBlank(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Code,
                contentDescription = null,
                tint =
                  if (enableLuaScripts && selectedLuaScripts.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    LocalContentColor.current
                  },
              )
              Text(
                text =
                  if (selectedLuaScripts.isEmpty()) {
                    "Scripts"
                  } else {
                    "Scripts (${selectedLuaScripts.size})"
                  },
                color =
                  if (enableLuaScripts && selectedLuaScripts.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    LocalContentColor.current
                  },
              )
            }
          }
        }
      }
      Text(
        text = stringResource(R.string.player_sheets_stats_page_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
      )
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
      ) {
        items(8) { page ->
          FilterChip(
            label = {
              Text(
                if (page == 7) {
                  "Console"
                } else {
                  stringResource(
                    if (page == 0) {
                      R.string.player_sheets_tracks_off
                    } else {
                      R.string.player_sheets_stats_page_chip
                    },
                    page,
                  )
                }
              )
            },
            onClick = {
              val isConsoleOpen = MPVLib.getPropertyBoolean("user-data/mpv/console/open") == true
              
              // If we are choosing any page OTHER than Console, close the console if it's currently open
              if (page != 7 && isConsoleOpen) {
                MPVLib.command("script-message-to", "console", "disable")
              }
              
              when (page) {
                0 -> {
                  if (statisticsPage in 1..5) MPVLib.command("script-binding", "stats/display-stats-toggle")
                }
                6 -> {
                  if (statisticsPage in 1..5) MPVLib.command("script-binding", "stats/display-stats-toggle")
                }
                7 -> {
                  if (statisticsPage in 1..5) MPVLib.command("script-binding", "stats/display-stats-toggle")
                  // Enable console only if it is not already open
                  if (!isConsoleOpen) {
                    MPVLib.command("script-message-to", "console", "enable")
                  }
                }
                else -> {
                  if (statisticsPage == 0 || statisticsPage == 6 || statisticsPage == 7) {
                    MPVLib.command("script-binding", "stats/display-stats-toggle")
                  }
                  MPVLib.command("script-binding", "stats/display-page-$page")
                }
              }
              advancedPreferences.enabledStatisticsPage.set(page)
            },
            selected = statisticsPage == page,
            leadingIcon = null,
          )
        }
      }

      // Standard Anime4K needs legacy gpu or gpu-next with Vulkan.
      if (anime4KUiState.isAvailable) {
        Text(
            text = stringResource(R.string.anime4k_mode_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        
        if (anime4KUiState.isHighResolution) {
            Text(
                text = "Not available for 4K/8K video",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
        ) {
          items(Anime4KManager.Mode.entries) { mode ->
            FilterChip(
              label = { Text(stringResource(mode.titleRes)) },
              selected = anime4KUiState.selectedMode == mode.name,
              enabled = !anime4KUiState.isHighResolution || mode == Anime4KManager.Mode.OFF,
              leadingIcon = null,
              onClick = { onAnime4KModeSelected(mode) },
            )
          }
        }
      }


    }
  }
}

private val sleepTimerPresets = listOf(15, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimePickerDialog(
  onDismissRequest: () -> Unit,
  onTimeSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  remainingTime: Int = 0,
) {
  Dialog(
    onDismissRequest = onDismissRequest,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      shape = AppShapeScale.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 6.dp,
      modifier = modifier
          .width(360.dp)
          .padding(MaterialTheme.spacing.medium),
    ) {
      Column(
        modifier =
          Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        // Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
              text = stringResource(R.string.timer_title), // "Sleep Timer"
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.timer_picker_enter_timer),
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onSurface
            )
        }

        val state =
          rememberTimePickerState(
            remainingTime / 3600,
            (remainingTime % 3600) / 60,
            is24Hour = true,
          )

        TimeInput(state = state)
        
        // Quick Presets
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Quick Presets",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sleepTimerPresets.forEach { minutes ->
                    FilterChip(
                        selected = false,
                        onClick = { 
                            onTimeSelect(minutes * 60)
                            onDismissRequest()
                        },
                        label = { Text("${minutes}m") },
                        leadingIcon = null,
                    )
                }
            }
        }

        // Actions
        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          TextButton(onClick = {
             onTimeSelect(0)
             onDismissRequest()
          }) {
              Text(stringResource(id = R.string.generic_reset))
          }
          Spacer(Modifier.weight(1f))
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            TextButton(onClick = onDismissRequest) {
              Text(stringResource(id = R.string.generic_cancel))
            }
            Button(
              onClick = {
                onTimeSelect(state.hour * 3600 + state.minute * 60)
                onDismissRequest()
              },
            ) {
              Text(stringResource(id = R.string.generic_ok))
            }
          }
        }
      }
    }
  }
  }

@Composable
fun SectionHeaderWithInfo(
  title: String,
  onInfoClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Start,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.width(8.dp))
    IconButton(onClick = onInfoClick, modifier = Modifier.size(24.dp)) {
      Icon(
        imageVector = Icons.RoundedFilled.Info,
        contentDescription = "Info",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(16.dp)
      )
    }
  }
}
