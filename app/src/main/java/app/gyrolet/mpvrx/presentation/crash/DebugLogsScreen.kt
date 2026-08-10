/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val DEBUG_LOG_POLL_INTERVAL_MS = 1_500L
private const val DEBUG_LOG_TAG = "MpvRxDebugLogs"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebugLogsScreen(onNavigateBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()

  var query by remember { mutableStateOf("") }
  var selectedLevels by remember { mutableStateOf(DebugLogLevel.entries.toSet()) }
  var liveEntries by remember { mutableStateOf<List<DebugLogEntry>>(emptyList()) }
  var pausedEntries by remember { mutableStateOf<List<DebugLogEntry>?>(null) }
  var expandedEntryIds by remember { mutableStateOf<Set<String>>(emptySet()) }
  var isPaused by remember { mutableStateOf(false) }
  var autoScrollEnabled by remember { mutableStateOf(true) }
  var menuExpanded by remember { mutableStateOf(false) }
  var clearedAt by remember { mutableStateOf(0L) }
  var readError by remember { mutableStateOf<String?>(null) }
  var hasLoadedOnce by remember { mutableStateOf(false) }
  var readSource by remember { mutableStateOf("") }

  // Generate one guaranteed app-side event before the first read. Besides being useful context,
  // this makes it obvious when a vendor is preventing the app from reading its own logcat.
  LaunchedEffect(Unit) {
    Log.i(DEBUG_LOG_TAG, "Debug log viewer opened for pid=${Process.myPid()}")
  }

  // Keep collecting while the UI is paused. Pause freezes the visible snapshot, not collection,
  // so resuming catches up immediately instead of losing everything emitted while paused.
  LaunchedEffect(clearedAt) {
    while (isActive) {
      val result = withContext(Dispatchers.IO) { runCatching { DebugLogReader.readSnapshot() } }
      result
        .onSuccess { snapshot ->
          val nextEntries = snapshot.entries.filter { it.timeMillis >= clearedAt }
          // Some Android/vendor builds occasionally return an empty snapshot for one poll. Keep
          // the last good snapshot instead of making the entire screen flash to an empty state.
          if (nextEntries.isNotEmpty() || liveEntries.isEmpty()) {
            liveEntries = nextEntries
          }
          readSource = snapshot.source
          readError = null
          hasLoadedOnce = true
        }.onFailure { error ->
          readError = error.message ?: "Unable to read app logs"
          hasLoadedOnce = true
        }
      delay(DEBUG_LOG_POLL_INTERVAL_MS)
    }
  }

  val sourceEntries = pausedEntries ?: liveEntries
  val levelCounts =
    remember(sourceEntries) {
      DebugLogLevel.entries.associateWith { level -> sourceEntries.count { it.level == level } }
    }
  val filteredEntries =
    remember(sourceEntries, query, selectedLevels) {
      val needle = query.trim()
      sourceEntries.filter { entry ->
        entry.level in selectedLevels &&
          (needle.isEmpty() ||
            entry.level.label.contains(needle, ignoreCase = true) ||
            entry.tag.contains(needle, ignoreCase = true) ||
            entry.message.contains(needle, ignoreCase = true))
      }
    }

  val isAtLatest = filteredEntries.isEmpty() || !listState.canScrollForward
  val showJumpToLatest = filteredEntries.isNotEmpty() && (!autoScrollEnabled || !isAtLatest)

  // If the user scrolls away from the bottom, stop forcing them back down on every log update.
  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
      .collectLatest { (scrolling, canScrollForward) ->
        if (scrolling && canScrollForward) {
          autoScrollEnabled = false
        } else if (!canScrollForward) {
          autoScrollEnabled = true
        }
      }
  }

  LaunchedEffect(filteredEntries.size, autoScrollEnabled, isPaused) {
    if (!isPaused && autoScrollEnabled && filteredEntries.isNotEmpty()) {
      listState.scrollToItem(filteredEntries.lastIndex)
    }
  }

  fun visibleText(includeDeviceInfo: Boolean = false): String =
    buildDebugLogText(
      entries = filteredEntries,
      includeDeviceInfo = includeDeviceInfo,
    )

  fun togglePause() {
    if (isPaused) {
      isPaused = false
      pausedEntries = null
    } else {
      pausedEntries = liveEntries
      isPaused = true
    }
  }

  fun clearVisibleHistory() {
    clearedAt = System.currentTimeMillis()
    liveEntries = emptyList()
    pausedEntries = if (isPaused) emptyList() else null
    expandedEntryIds = emptySet()
    autoScrollEnabled = true
    readError = null
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = "Debug Logs",
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text =
                buildString {
                  append(if (isPaused) "Paused" else if (readError != null) "Retrying" else "Live")
                  append(" • ")
                  if (filteredEntries.size == sourceEntries.size) {
                    append(sourceEntries.size)
                    append(" logs")
                  } else {
                    append(filteredEntries.size)
                    append(" of ")
                    append(sourceEntries.size)
                  }
                  if (readSource == "pid-fallback") append(" • compatibility mode")
                },
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onNavigateBack) {
            Icon(Icons.RoundedFilled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(onClick = ::togglePause) {
            Icon(
              imageVector = if (isPaused) Icons.RoundedFilled.PlayArrow else Icons.RoundedFilled.Pause,
              contentDescription = if (isPaused) "Resume logs" else "Pause logs",
            )
          }
          Box {
            IconButton(onClick = { menuExpanded = true }) {
              Icon(Icons.RoundedFilled.MoreVert, contentDescription = "Log options")
            }
            DropdownMenu(
              expanded = menuExpanded,
              onDismissRequest = { menuExpanded = false },
            ) {
              DropdownMenuItem(
                text = { Text("Share visible logs") },
                leadingIcon = { Icon(Icons.RoundedFilled.Share, contentDescription = null) },
                enabled = filteredEntries.isNotEmpty(),
                onClick = {
                  menuExpanded = false
                  shareDebugLogs(context, visibleText(includeDeviceInfo = true))
                },
              )
              DropdownMenuItem(
                text = { Text("Export visible logs") },
                leadingIcon = { Icon(Icons.RoundedFilled.Download, contentDescription = null) },
                enabled = filteredEntries.isNotEmpty(),
                onClick = {
                  menuExpanded = false
                  exportDebugLogs(context, visibleText(includeDeviceInfo = true))
                },
              )
              DropdownMenuItem(
                text = { Text("Copy visible logs") },
                leadingIcon = { Icon(Icons.RoundedFilled.ContentCopy, contentDescription = null) },
                enabled = filteredEntries.isNotEmpty(),
                onClick = {
                  menuExpanded = false
                  SafeClipboard.copyPlainText(
                    context = context,
                    label = "mpvrx_debug_logs",
                    text = visibleText(),
                  )
                },
              )
              DropdownMenuItem(
                text = { Text("Clear captured logs") },
                leadingIcon = { Icon(Icons.RoundedFilled.Clear, contentDescription = null) },
                enabled = sourceEntries.isNotEmpty(),
                onClick = {
                  menuExpanded = false
                  clearVisibleHistory()
                },
              )
            }
          }
        },
      )
    },
    floatingActionButton = {
      AnimatedVisibility(visible = showJumpToLatest) {
        SmallFloatingActionButton(
          onClick = {
            autoScrollEnabled = true
            scope.launch {
              if (filteredEntries.isNotEmpty()) {
                listState.animateScrollToItem(filteredEntries.lastIndex)
              }
            }
          },
        ) {
          Icon(Icons.RoundedFilled.KeyboardArrowDown, contentDescription = "Jump to latest log")
        }
      }
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier =
          Modifier
            .widthIn(max = 900.dp)
            .fillMaxSize()
            .padding(horizontal = 16.dp),
      ) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          placeholder = { Text("Search tag or message") },
          leadingIcon = { Icon(Icons.RoundedFilled.Search, contentDescription = null) },
          trailingIcon = {
            if (query.isNotEmpty()) {
              IconButton(onClick = { query = "" }) {
                Icon(Icons.RoundedFilled.Close, contentDescription = "Clear search")
              }
            }
          },
        )

        Spacer(Modifier.height(8.dp))

        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = selectedLevels.size == DebugLogLevel.entries.size,
            onClick = { selectedLevels = DebugLogLevel.entries.toSet() },
            label = { Text("All ${sourceEntries.size}") },
          )
          DebugLogLevel.entries.forEach { level ->
            FilterChip(
              selected = level in selectedLevels,
              onClick = {
                selectedLevels =
                  if (level in selectedLevels) {
                    selectedLevels - level
                  } else {
                    selectedLevels + level
                  }
              },
              label = { Text("${level.label} ${levelCounts[level] ?: 0}") },
              leadingIcon = { DebugLogLevelBadge(level, compact = true) },
            )
          }
        }

        AnimatedVisibility(visible = readError != null && sourceEntries.isNotEmpty()) {
          Surface(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
          ) {
            Text(
              text = "Logcat temporarily unavailable. Showing the last captured logs and retrying automatically.",
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }

        when {
          !hasLoadedOnce && sourceEntries.isEmpty() -> {
            DebugLogMessageState(
              title = "Loading logs…",
              message = "Reading the current mpvRx process logcat.",
            )
          }
          readError != null && sourceEntries.isEmpty() -> {
            DebugLogMessageState(
              title = "Unable to read logs",
              message = "Android did not return the app logcat. mpvRx will keep retrying automatically.\n\n${readError.orEmpty()}",
              isError = true,
            )
          }
          filteredEntries.isEmpty() -> {
            val hasActiveFilter = query.isNotBlank() || selectedLevels.size != DebugLogLevel.entries.size
            DebugLogMessageState(
              title = if (hasActiveFilter) "No matching logs" else if (isPaused) "Logs paused" else "Waiting for logs…",
              message =
                if (hasActiveFilter) {
                  "Try clearing the search or enabling more log levels."
                } else if (isPaused) {
                  "Resume to see the latest captured entries."
                } else {
                  "Use mpvRx normally and new app logs will appear here."
                },
            )
          }
          else -> {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(top = 10.dp, bottom = 88.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              items(
                items = filteredEntries,
                key = { entry -> entry.id },
                contentType = { entry -> entry.level },
              ) { entry ->
                DebugLogEntryCard(
                  entry = entry,
                  expanded = entry.id in expandedEntryIds,
                  onToggleExpanded = {
                    expandedEntryIds =
                      if (entry.id in expandedEntryIds) {
                        expandedEntryIds - entry.id
                      } else {
                        expandedEntryIds + entry.id
                      }
                  },
                  onCopy = {
                    SafeClipboard.copyPlainText(
                      context = context,
                      label = "mpvrx_log_entry",
                      text = formatDebugLogEntry(entry),
                    )
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DebugLogMessageState(
  title: String,
  message: String,
  isError: Boolean = false,
) {
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DebugLogEntryCard(
  entry: DebugLogEntry,
  expanded: Boolean,
  onToggleExpanded: () -> Unit,
  onCopy: () -> Unit,
) {
  val levelColor = debugLogLevelColor(entry.level)
  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .animateContentSize()
        .combinedClickable(
          onClick = onToggleExpanded,
          onLongClick = onCopy,
        ),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        DebugLogLevelBadge(entry.level)
        Text(
          text = entry.timestamp,
          style = MaterialTheme.typography.labelMedium,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entry.tag.isNotBlank()) {
          Text(
            text = entry.tag,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = levelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Text(
        text = entry.message,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = levelColor,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
      )

      if (expanded && entry.pid != null) {
        Text(
          text = buildString {
            append("pid ")
            append(entry.pid)
            entry.tid?.let {
              append(" • tid ")
              append(it)
            }
            append(" • long-press to copy")
          },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun DebugLogLevelBadge(
  level: DebugLogLevel,
  compact: Boolean = false,
) {
  val size = if (compact) 22.dp else 28.dp
  Surface(
    modifier = Modifier.size(size),
    shape = RoundedCornerShape(if (compact) 6.dp else 8.dp),
    color = debugLogLevelColor(level),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = level.code,
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.surface,
      )
    }
  }
}

@Composable
private fun debugLogLevelColor(level: DebugLogLevel) =
  when (level) {
    DebugLogLevel.Verbose -> MaterialTheme.colorScheme.onSurfaceVariant
    DebugLogLevel.Debug -> MaterialTheme.colorScheme.secondary
    DebugLogLevel.Info -> MaterialTheme.colorScheme.primary
    DebugLogLevel.Warn -> MaterialTheme.colorScheme.tertiary
    DebugLogLevel.Error -> MaterialTheme.colorScheme.error
  }

private fun formatDebugLogEntry(entry: DebugLogEntry): String =
  buildString {
    append(entry.timestamp)
    entry.pid?.let { pid ->
      append(' ')
      append(pid)
      entry.tid?.let { tid ->
        append('/')
        append(tid)
      }
    }
    append(' ')
    append(entry.level.code)
    append('/')
    append(entry.tag.ifBlank { "unknown" })
    append(": ")
    append(entry.message)
  }

private fun buildDebugLogText(
  entries: List<DebugLogEntry>,
  includeDeviceInfo: Boolean,
): String {
  if (entries.isEmpty()) return ""
  return buildString {
    if (includeDeviceInfo) {
      appendLine(CrashActivity.collectDeviceInfo())
      appendLine()
      appendLine("Logcat:")
    }
    entries.forEach { entry -> appendLine(formatDebugLogEntry(entry)) }
  }.trimEnd()
}

private fun shareDebugLogs(
  context: Context,
  text: String,
) {
  if (text.isBlank()) return
  val byteCount = text.toByteArray(Charsets.UTF_8).size
  if (byteCount > 256 * 1024) {
    exportDebugLogs(context, text, chooserTitle = "Share debug logs")
    return
  }

  context.startActivity(
    Intent.createChooser(
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
      },
      "Share debug logs",
    ),
  )
}

private fun exportDebugLogs(
  context: Context,
  text: String,
  chooserTitle: String = "Export debug logs",
) {
  if (text.isBlank()) return

  val exportDirectory = File(context.cacheDir, "shared_logs").apply { mkdirs() }
  val file = File(exportDirectory, "mpvrx-debug-${System.currentTimeMillis()}.txt")
  file.writeText(text)

  exportDirectory
    .listFiles { candidate -> candidate.isFile && candidate.name.startsWith("mpvrx-debug-") }
    ?.sortedByDescending(File::lastModified)
    ?.drop(5)
    ?.forEach { candidate -> candidate.delete() }

  val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
  context.startActivity(
    Intent.createChooser(
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        clipData = android.content.ClipData.newRawUri(file.name, uri)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      },
      chooserTitle,
    ),
  )
}
