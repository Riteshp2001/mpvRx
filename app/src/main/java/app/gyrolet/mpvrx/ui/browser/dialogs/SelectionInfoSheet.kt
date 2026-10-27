/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import android.content.ClipData
import android.content.Intent
import android.util.Log
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.presentation.components.ConnectedInfoStatCard
import app.gyrolet.mpvrx.presentation.components.ConnectedInfoSurface
import app.gyrolet.mpvrx.ui.browser.selection.summarizeSelection
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.mediainfo.MediaInfoActivity
import app.gyrolet.mpvrx.ui.mediainfo.mediaInfoDetailContainerColor
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

private data class SelectionInfoRequest(
  val videos: List<Video>,
  val folders: List<VideoFolder>,
)

/**
 * Keeps a snapshot while the details page is open, even if the browser refreshes its selection underneath it.
 * A single file retains the existing full MediaInfo experience.
 */
@Composable
fun rememberSelectionInfoAction(
  videos: () -> List<Video>,
  onSingleOpened: () -> Unit,
  folders: () -> List<VideoFolder> = { emptyList() },
  loadFolderVideos: (suspend (VideoFolder) -> List<Video>)? = null,
): () -> Unit {
  val context = LocalContext.current
  var request by remember { mutableStateOf<SelectionInfoRequest?>(null) }

  request?.let { selection ->
    SelectionInfoPage(
      selection = selection,
      loadFolderVideos = loadFolderVideos,
      onDismiss = { request = null },
    )
  }

  return {
    val selectedVideos = videos().toList()
    val selectedFolders = folders().distinctBy { it.path }
    if (selectedFolders.isEmpty() && selectedVideos.size == 1) {
      context.startActivity(
        Intent(context, MediaInfoActivity::class.java).apply {
          action = Intent.ACTION_VIEW
          data = selectedVideos.single().uri
        },
      )
      onSingleOpened()
    } else {
      request = SelectionInfoRequest(selectedVideos, selectedFolders)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionInfoPage(
  selection: SelectionInfoRequest,
  loadFolderVideos: (suspend (VideoFolder) -> List<Video>)?,
  onDismiss: () -> Unit,
) {
  var resolvedVideos by remember(selection) {
    mutableStateOf<List<Video>?>(selection.videos.takeIf { selection.folders.isEmpty() })
  }
  var folderContents by remember(selection) { mutableStateOf<Map<String, List<Video>>>(emptyMap()) }
  var scannedFolders by remember(selection) { mutableStateOf(0) }
  var scanError by remember(selection) { mutableStateOf<String?>(null) }
  val context = LocalContext.current
  val errorMessage = stringResource(R.string.selection_info_scan_failed)

  LaunchedEffect(selection) {
    if (selection.folders.isEmpty()) return@LaunchedEffect
    try {
      val loader = checkNotNull(loadFolderVideos) { "No folder metadata loader supplied" }
      val contents = linkedMapOf<String, List<Video>>()
      for (folder in selection.folders) {
        val files = withContext(Dispatchers.IO) { loader(folder) }
        if (files.isEmpty() && folder.videoCount > 0) {
          throw IOException(context.getString(R.string.selection_info_folder_unreadable, folder.name))
        }
        contents[folder.path] = summarizeSelection(files).videos
        scannedFolders++
      }
      folderContents = contents
      resolvedVideos = selection.videos + contents.values.flatten()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Log.e("SelectionInfoPage", "Unable to load selected folders", error)
      scanError = error.localizedMessage ?: errorMessage
    }
  }

  val summary = remember(resolvedVideos) { resolvedVideos?.let(::summarizeSelection) }
  val unknown = stringResource(R.string.generic_unknown)
  val fpsFormat = stringResource(R.string.selection_info_fps_value)
  val configuration = LocalConfiguration.current
  val dateFormat = remember(configuration) {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, configuration.locales[0])
  }
  fun size(value: Long) = VideoScanUtils.formatFileSize(value)
  fun duration(value: Long) = VideoScanUtils.formatDuration(value)
  val quickStats = buildList {
    if (summary != null) {
      add(
        stringResource(R.string.selection_info_total_size) to
          if (summary.knownSizeCount > 0 || summary.videos.isEmpty()) size(summary.totalSize) else unknown,
      )
      add(
        stringResource(R.string.selection_info_total_duration) to
          if (summary.knownDurationCount > 0 || summary.videos.isEmpty()) duration(summary.totalDuration) else unknown,
      )
      val files = summary.videos
      if (files.isNotEmpty()) {
        add(
          stringResource(R.string.selection_info_average_size) to
            if (summary.knownSizeCount > 0) size(summary.totalSize / summary.knownSizeCount) else unknown,
        )
        add(
          stringResource(R.string.selection_info_average_duration) to
            if (summary.knownDurationCount > 0) {
              duration(summary.totalDuration / summary.knownDurationCount)
            } else {
              unknown
            },
        )
      }
    }
  }
  val metadataBreakdowns = mutableMapOf<String, List<Pair<String, Int>>>()
  val metadataRows = buildList<Pair<String, String>> {
    fun addBreakdown(label: String, files: List<Video>, value: (Video) -> String) {
      val counts = metadataBreakdown(files, unknown, value)
      metadataBreakdowns[label] = counts
      add(label to counts.joinToString("\n") { (name, count) -> "$name: $count" })
    }
    if (summary != null) {
      val files = summary.videos
      if (files.isNotEmpty()) {
        if (summary.knownSizeCount < files.size || summary.knownDurationCount < files.size) {
          add(
            stringResource(R.string.selection_info_missing_metadata) to
              stringResource(
                R.string.selection_info_missing_metadata_value,
                files.size - summary.knownSizeCount,
                files.size - summary.knownDurationCount,
              ),
          )
        }
        addBreakdown(stringResource(R.string.selection_info_formats), files) { it.mimeType }
        val videoFiles = files.filterNot { it.isAudio }
        if (videoFiles.isNotEmpty()) {
          addBreakdown(stringResource(R.string.selection_info_resolutions), videoFiles) {
            it.resolution.substringBefore("@").takeUnless { value -> value == "--" }.orEmpty()
          }
          addBreakdown(stringResource(R.string.selection_info_codecs), videoFiles) { it.videoCodec }
          addBreakdown(stringResource(R.string.selection_info_frame_rates), videoFiles) {
            if (it.fps.isFinite() && it.fps > 0) {
              String.format(configuration.locales[0], fpsFormat, it.fps)
            } else {
              ""
            }
          }
          add(
            stringResource(R.string.selection_info_subtitles) to
              stringResource(
                R.string.selection_info_subtitles_value,
                videoFiles.count { it.hasEmbeddedSubtitles },
                videoFiles.size,
              ),
          )
        }
        summary.longest?.let {
          add(stringResource(R.string.selection_info_longest) to "${it.displayName} (${duration(it.duration)})")
        }
        summary.largest?.let {
          add(stringResource(R.string.selection_info_largest) to "${it.displayName} (${size(it.size)})")
        }
        val modifiedDates = files.map { it.dateModified }.filter { it > 0L }
        if (modifiedDates.isNotEmpty()) {
          val first = dateFormat.format(Date(modifiedDates.min() * 1000))
          val last = dateFormat.format(Date(modifiedDates.max() * 1000))
          add(stringResource(R.string.selection_info_modified) to if (first == last) first else "$first - $last")
        }
      }
    }
  }
  val title = stringResource(R.string.selection_info_title)
  val countLabels = buildList {
    summary?.let {
      if (it.videoCount > 0) {
        add(pluralStringResource(R.plurals.selection_info_video_count, it.videoCount, it.videoCount))
      }
      if (it.audioCount > 0) {
        add(pluralStringResource(R.plurals.selection_info_audio_count, it.audioCount, it.audioCount))
      }
    }
    if (selection.folders.isNotEmpty()) {
      add(
        pluralStringResource(
          R.plurals.selection_info_folder_count,
          selection.folders.size,
          selection.folders.size,
        ),
      )
    }
  }
  val countText = countLabels.joinToString(" / ")
  fun summaryText(): String = buildString {
    appendLine(title)
    appendLine(countText)
    (quickStats + metadataRows).forEach { (label, value) -> appendLine("$label: $value") }
    selection.folders.forEach { appendLine(it.path) }
    summary?.videos?.forEach { appendLine(it.path) }
  }
  val tabs = buildList {
    add(SelectionInfoTab.Overview)
    if (selection.folders.isNotEmpty()) add(SelectionInfoTab.Folders)
    if (!summary?.videos.isNullOrEmpty()) add(SelectionInfoTab.Items)
  }
  var selectedTab by remember(selection) { mutableStateOf(SelectionInfoTab.Overview) }
  var valueDetail by remember(selection) { mutableStateOf<Pair<String, String>?>(null) }
  var isSharing by remember(selection) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val overviewState = rememberLazyListState()
  val foldersState = rememberLazyListState()
  val itemsState = rememberLazyListState()
  val showValue: (String, String) -> Unit = { label, value -> valueDetail = label to value }

  // A full-size dialog keeps the selection snapshot and folder loader in the browser's lifetime,
  // while giving collection details the same page layout as single-file MediaInfo.
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      contentWindowInsets = WindowInsets.safeDrawing,
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (countText.isNotEmpty()) {
                Text(
                  countText,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          },
          navigationIcon = {
            IconButton(onClick = onDismiss) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
          },
          actions = {
            if (summary != null && scanError == null) {
              Row(
                modifier = Modifier.padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                FilledTonalIconButton(
                  onClick = {
                    SafeClipboard.copyPlainText(context, title, summaryText())
                  },
                ) {
                  Icon(
                    Icons.RoundedFilled.ContentCopy,
                    contentDescription = stringResource(R.string.selection_info_copy_summary),
                  )
                }
                FilledTonalIconButton(
                  enabled = !isSharing,
                  onClick = {
                    isSharing = true
                    scope.launch {
                      try {
                        // Share a report like MediaInfo; a large selection must not fill an Intent's Binder payload.
                        val uri = withContext(Dispatchers.IO) {
                          val report = File.createTempFile("selection_info_", ".txt", context.cacheDir)
                          report.writeText(summaryText())
                          FileProvider.getUriForFile(context, context.packageName + ".provider", report)
                        }
                        context.startActivity(
                          Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                              type = "text/plain"
                              putExtra(Intent.EXTRA_SUBJECT, title)
                              putExtra(Intent.EXTRA_STREAM, uri)
                              clipData = ClipData.newRawUri(title, uri)
                              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            title,
                          ),
                        )
                      } catch (cancelled: CancellationException) {
                        throw cancelled
                      } catch (error: Exception) {
                        Log.e("SelectionInfoPage", "Unable to share selection details", error)
                        Toast.makeText(
                          context,
                          context.getString(
                            R.string.pref_export_failed_toast,
                            error.localizedMessage ?: context.getString(R.string.generic_unknown_error),
                          ),
                          Toast.LENGTH_LONG,
                        ).show()
                      } finally {
                        isSharing = false
                      }
                    }
                  },
                ) {
                  Icon(Icons.RoundedFilled.Share, contentDescription = stringResource(R.string.generic_share))
                }
              }
            }
          },
          windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
      },
      bottomBar = {
        if (summary != null && scanError == null) {
          SelectionInfoTabs(tabs, selectedTab, onSelect = { selectedTab = it })
        }
      },
    ) { padding ->
      Box(
        modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface),
      ) {
        when {
          scanError != null -> {
            Column(
              modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
              verticalArrangement = Arrangement.Center,
            ) {
              SelectionInfoCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  Icon(
                    Icons.RoundedFilled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                  )
                  Text(
                    errorMessage,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                  )
                  Text(scanError.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer)
                }
              }
            }
          }
          summary == null -> {
            Column(
              modifier = Modifier.align(Alignment.Center).padding(32.dp),
              verticalArrangement = Arrangement.spacedBy(20.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              Text(
                stringResource(R.string.selection_info_scanning, scannedFolders, selection.folders.size),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          else -> {
            when (selectedTab) {
              SelectionInfoTab.Overview -> {
                LazyColumn(
                  state = overviewState,
                  modifier = Modifier.fillMaxSize(),
                  contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                  verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                  item(key = "hero") {
                    SelectionInfoCard(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) {
                      Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                      ) {
                        Surface(
                          shape = MaterialTheme.shapes.large,
                          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                          contentColor = MaterialTheme.colorScheme.primary,
                        ) {
                          Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                            Icon(
                              when {
                                summary.videos.isEmpty() -> Icons.RoundedFilled.Folder
                                summary.videoCount == 0 -> Icons.RoundedFilled.Audiotrack
                                else -> Icons.RoundedFilled.VideoLibrary
                              },
                              contentDescription = null,
                              modifier = Modifier.size(30.dp),
                            )
                          }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                          Text(
                            stringResource(R.string.media_info_tab_overview),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                          )
                          Text(
                            pluralStringResource(
                              R.plurals.selection_info_file_count,
                              summary.videos.size,
                              summary.videos.size,
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                          )
                        }
                      }
                    }
                  }
                  if (countLabels.isNotEmpty()) {
                    item(key = "counts") {
                      Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
                      ) {
                        countLabels.forEachIndexed { index, label ->
                          Surface(
                            shape = rememberConnectedShape(index, countLabels.size, ConnectedLayout.Horizontal, tokens = AppConnectedControlShapeTokens),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                          ) {
                            Text(
                              label,
                              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                              style = MaterialTheme.typography.labelMedium,
                              fontWeight = FontWeight.Bold,
                            )
                          }
                        }
                      }
                    }
                  }
                  item(key = "stats") { SelectionInfoStats(quickStats, showValue) }
                  item(key = "note") {
                    Text(
                      stringResource(R.string.selection_info_metadata_note),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  if (summary.videos.isEmpty()) {
                    item(key = "empty") {
                      SelectionInfoCard {
                        Text(
                          stringResource(R.string.selection_info_no_media),
                          modifier = Modifier.padding(20.dp),
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                    }
                  }
                  if (metadataRows.isNotEmpty()) {
                    item(key = "metadata") {
                      Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                        SelectionInfoHeading(stringResource(R.string.info))
                        SelectionInfoProperties(metadataRows, showValue, metadataBreakdowns)
                      }
                    }
                  }
                }
              }
              SelectionInfoTab.Folders -> {
                LazyColumn(
                  state = foldersState,
                  modifier = Modifier.fillMaxSize(),
                  contentPadding = PaddingValues(16.dp),
                  verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                  itemsIndexed(selection.folders, key = { _, folder -> folder.path }) { _, folder ->
                    val files = folderContents[folder.path].orEmpty()
                    val folderSummary = remember(files) { summarizeSelection(files) }
                    val folderRows = buildList {
                      add(stringResource(R.string.media_info_file_path) to folder.path)
                      add(
                        stringResource(R.string.selection_info_total_size) to
                          if (folderSummary.knownSizeCount > 0 || files.isEmpty()) {
                            size(folderSummary.totalSize)
                          } else {
                            unknown
                          },
                      )
                      add(
                        stringResource(R.string.selection_info_total_duration) to
                          if (folderSummary.knownDurationCount > 0 || files.isEmpty()) {
                            duration(folderSummary.totalDuration)
                          } else {
                            unknown
                          },
                      )
                      add(
                        stringResource(R.string.selection_info_modified) to
                          if (folder.lastModified > 0) {
                            dateFormat.format(Date(folder.lastModified * 1000))
                          } else {
                            unknown
                          },
                      )
                    }
                    SelectionInfoProperties(folderRows, showValue, header = {
                      Row(
                        modifier = Modifier.padding(MaterialTheme.spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                      ) {
                        Icon(
                          Icons.RoundedFilled.Folder,
                          contentDescription = null,
                          tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                          Text(
                            folder.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                          )
                          Text(
                            pluralStringResource(
                              R.plurals.selection_info_file_count,
                              files.size,
                              files.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        }
                      }
                    })
                  }
                }
              }
              SelectionInfoTab.Items -> {
                LazyColumn(
                  state = itemsState,
                  modifier = Modifier.fillMaxSize(),
                  contentPadding = PaddingValues(16.dp),
                  verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
                ) {
                  itemsIndexed(
                    summary.videos,
                    key = { _, video -> video.path.ifBlank { video.uri.toString() } },
                  ) { index, video ->
                    ConnectedInfoSurface(
                      index = index,
                      itemCount = summary.videos.size,
                      onClick = {
                        context.startActivity(
                          Intent(context, MediaInfoActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = video.uri
                          },
                        )
                      },
                      modifier = Modifier.fillMaxWidth(),
                    ) {
                      Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                      ) {
                        Icon(
                          if (video.isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.Movie,
                          contentDescription = null,
                          modifier = Modifier.size(24.dp),
                          tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                          Text(video.displayName, style = MaterialTheme.typography.titleSmall)
                          Text(
                            listOf(
                              if (video.size > 0) size(video.size) else unknown,
                              if (video.duration > 0) duration(video.duration) else unknown,
                            ).joinToString(" / "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                          Text(
                            video.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                          )
                        }
                        Icon(
                          Icons.RoundedFilled.Info,
                          contentDescription = stringResource(R.string.selection_info_open_media_info),
                          modifier = Modifier.size(20.dp),
                          tint = MaterialTheme.colorScheme.primary,
                        )
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

    valueDetail?.let { (label, value) ->
      SelectionInfoValueDialog(label, value, onDismiss = { valueDetail = null })
    }
  }
}

private fun metadataBreakdown(
  videos: List<Video>,
  unknown: String,
  value: (Video) -> String,
): List<Pair<String, Int>> =
  videos.groupingBy { value(it).ifBlank { unknown } }.eachCount()
    .toList().sortedByDescending { it.second }

private enum class SelectionInfoTab(@StringRes val labelRes: Int) {
  Overview(R.string.media_info_tab_overview),
  Folders(R.string.selection_info_folders),
  Items(R.string.selection_info_items),
}

@Composable
private fun SelectionInfoTabs(
  tabs: List<SelectionInfoTab>,
  selectedTab: SelectionInfoTab,
  onSelect: (SelectionInfoTab) -> Unit,
) {
  val reduceMotion = AppMotion.shouldReduceMotion()
  Surface(color = MaterialTheme.colorScheme.surface) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
    ) {
      tabs.forEachIndexed { index, tab ->
        val isSelected = selectedTab == tab
        val containerColor by animateColorAsState(
          targetValue =
            if (isSelected) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
          animationSpec = if (reduceMotion) snap() else AppMotion.Effect.Color,
          label = "selectionInfoTabColor",
        )
        Surface(
          onClick = { onSelect(tab) },
          shape = rememberConnectedShape(
            index,
            tabs.size,
            ConnectedLayout.Horizontal,
            tokens = AppConnectedControlShapeTokens,
          ),
          color = containerColor,
          contentColor =
            if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.heightIn(min = 48.dp).semantics {
            selected = isSelected
            role = Role.Tab
          },
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              when (tab) {
                SelectionInfoTab.Overview -> Icons.RoundedFilled.Info
                SelectionInfoTab.Folders -> Icons.RoundedFilled.Folder
                SelectionInfoTab.Items -> Icons.RoundedFilled.ViewList
              },
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Text(
              stringResource(tab.labelRes),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SelectionInfoCard(
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(containerColor = containerColor),
    content = content,
  )
}

@Composable
private fun SelectionInfoHeading(title: String) {
  Text(
    title,
    modifier = Modifier.semantics { heading() },
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary,
  )
}

@Composable
private fun SelectionInfoStats(
  rows: List<Pair<String, String>>,
  onValue: (String, String) -> Unit,
) {
  val fontScale = LocalDensity.current.fontScale
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val columns = if (maxWidth >= 320.dp && fontScale < 1.3f) 2 else 1
    Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
      rows.chunked(columns).forEachIndexed { rowIndex, row ->
        Row(
          modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
        ) {
          row.forEachIndexed { columnIndex, (label, value) ->
            val index = rowIndex * columns + columnIndex
            SelectionInfoStat(
              label = label,
              value = value,
              icon = if (index % 2 == 0) Icons.RoundedFilled.SdCard else Icons.RoundedFilled.Timer,
              index = index,
              count = rows.size,
              columns = columns,
              onClick = { onValue(label, value) },
              modifier = Modifier.weight(1f).fillMaxHeight(),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SelectionInfoStat(
  label: String,
  value: String,
  icon: AppIcon,
  index: Int,
  count: Int,
  columns: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  ConnectedInfoStatCard(
    title = label,
    value = value,
    icon = icon,
    accentColor = when (index) {
      0 -> MaterialTheme.colorScheme.primary
      1 -> MaterialTheme.colorScheme.secondary
      2 -> MaterialTheme.colorScheme.tertiary
      else -> Color(0xFFFFB300)
    },
    index = index,
    itemCount = count,
    columns = columns,
    onClick = onClick,
    modifier = modifier,
  )
}

@Composable
private fun SelectionInfoProperties(
  rows: List<Pair<String, String>>,
  onValue: (String, String) -> Unit,
  breakdowns: Map<String, List<Pair<String, Int>>> = emptyMap(),
  header: (@Composable () -> Unit)? = null,
) {
  val leadingRows = if (header == null) 0 else 1
  val itemCount = rows.size + leadingRows
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
    if (header != null) {
      ConnectedInfoSurface(index = 0, itemCount = itemCount, content = header)
    }
    rows.forEachIndexed { index, (label, value) ->
      ConnectedInfoSurface(
        index = index + leadingRows,
        itemCount = itemCount,
        onClick = { onValue(label, value) },
      ) {
        Column(
          Modifier.padding(MaterialTheme.spacing.medium),
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
        ) {
          Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          val counts = breakdowns[label]
          if (counts.isNullOrEmpty()) {
            Text(
              value,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          } else {
            SelectionInfoBreakdown(counts)
          }
        }
      }
    }
  }
}

@Composable
private fun SelectionInfoBreakdown(counts: List<Pair<String, Int>>) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    counts.forEach { (name, count) ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          modifier = Modifier.weight(1f, fill = false),
          shape = rememberConnectedShape(0, 2, ConnectedLayout.Horizontal, tokens = AppConnectedControlShapeTokens),
          color = mediaInfoDetailContainerColor(),
          contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
          Text(
            name,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small, vertical = MaterialTheme.spacing.smaller),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        Surface(
          shape = rememberConnectedShape(1, 2, ConnectedLayout.Horizontal, tokens = AppConnectedControlShapeTokens),
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
          Text(
            count.toString(),
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small, vertical = MaterialTheme.spacing.smaller),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

@Composable
private fun SelectionInfoValueDialog(label: String, value: String, onDismiss: () -> Unit) {
  val context = LocalContext.current
  AppAlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
    text = {
      SelectionContainer {
        Text(
          value,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        )
      }
    },
    confirmButton = {
      AppDialogTextButton(
        onClick = {
          SafeClipboard.copyPlainText(context, label, value)
          onDismiss()
        },
      ) {
        Text(stringResource(R.string.ui_copy))
      }
    },
    dismissButton = {
      AppDialogTextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) }
    },
  )
}
