/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.downloads

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.download.AppDownload
import app.gyrolet.mpvrx.domain.download.AppDownloadManager
import app.gyrolet.mpvrx.domain.download.AppDownloadStatus
import app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine
import app.gyrolet.mpvrx.domain.download.formatBytes
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.DownloadPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.domain.thumbnail.getArtwork
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaUtils
import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton

@Serializable
object DownloadsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val downloadManager = koinInject<AppDownloadManager>()
    val ytdlpEngine = koinInject<YtdlpDownloadEngine>()
    val downloadPreferences = koinInject<DownloadPreferences>()
    val locationPath by downloadPreferences.downloadLocationPath.collectAsState()
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingYtdlpDelete by remember { mutableStateOf<YtdlpDownloadEngine.Job?>(null) }

    val downloads by downloadManager.downloads.collectAsState()
    val ytdlpJobs by ytdlpEngine.jobs.collectAsState()
    val activeSnapshot by downloadManager.activeSnapshot.collectAsState()

    // Progress notifications need POST_NOTIFICATIONS on Android 13+.
    val notificationPermissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }

    val locationPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
        }
        val resolved = downloadManager.locations.setLocationFromTree(uri)
        if (resolved == null) {
          Toast.makeText(context, R.string.downloads_location_invalid, Toast.LENGTH_LONG).show()
        }
      }

    var pendingDelete by remember { mutableStateOf<AppDownload?>(null) }

    val counts = DownloadFilter.entries.associateWith { category ->
      downloads.count { category.matches(it) } + ytdlpJobs.count { category.matches(it) }
    }
    val search = query.trim()
    val matchingDownloads = downloads.filter {
      filter.matches(it) && (it.displayTitle.contains(search, true) || it.entity.fileName.contains(search, true))
    }
    val matchingYtdlp = ytdlpJobs.filter {
      filter.matches(it) && (it.title.contains(search, true) || it.outputFile.orEmpty().contains(search, true))
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.downloads_title),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.popSafely() }) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        item(key = "overview") {
          DownloadOverview(filter = filter, counts = counts, onSelect = { filter = it })
        }
        item(key = "search") {
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            label = { Text(stringResource(R.string.downloads_search_hint)) },
            leadingIcon = { Icon(Icons.RoundedFilled.Search, contentDescription = null) },
            trailingIcon = {
              if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                  Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.search_clear_query))
                }
              }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
          )
        }
        item(key = "location") {
          DownloadLocationCard(
            path = locationPath.ifBlank { downloadManager.locations.defaultRoot().absolutePath },
            isCustom = locationPath.isNotBlank(),
            onPick = { locationPicker.launch(null) },
            onReset = { downloadManager.locations.clearCustomLocation() },
          )
        }

        if (matchingDownloads.isEmpty() && matchingYtdlp.isEmpty()) {
          item(key = "empty") {
            EmptyState(
              icon = Icons.RoundedFilled.FileDownload,
              title = stringResource(if (downloads.isEmpty() && ytdlpJobs.isEmpty()) R.string.downloads_empty else R.string.ui_no_results_found),
              message = stringResource(if (downloads.isEmpty() && ytdlpJobs.isEmpty()) R.string.downloads_empty_hint else R.string.downloads_no_matches_hint),
              modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
          }
        }

        for (category in listOf(DownloadFilter.ACTIVE, DownloadFilter.ATTENTION)) {
          val activeDownloads = matchingDownloads.filter { category.matches(it) }
          val activeYtdlp = matchingYtdlp.filter { category.matches(it) }
          val count = activeDownloads.size + activeYtdlp.size
          if (count == 0) continue
          item(key = "header_$category") { SectionHeader(stringResource(category.label), count) }
          itemsIndexed(activeYtdlp, key = { _, job -> "ytdlp_${job.id}" }) { index, job ->
            YtdlpJobRow(
              job = job,
              shape = rememberConnectedShape(index, count, ConnectedLayout.Vertical),
              onCancel = { ytdlpEngine.cancel(job.id) },
              onRetry = { ytdlpEngine.retry(job.id) },
              onRemove = { ytdlpEngine.remove(job.id) },
            )
          }
          itemsIndexed(activeDownloads, key = { _, download -> "dl_${download.id}" }) { index, download ->
            ActiveDownloadRow(
              download = download,
              shape = rememberConnectedShape(index + activeYtdlp.size, count, ConnectedLayout.Vertical),
              speedBytesPerSec = activeSnapshot?.takeIf { it.id == download.id }?.speedBytesPerSec ?: 0L,
              onRetry = { downloadManager.retry(download.id) },
              onCancel = { downloadManager.remove(download, deleteFile = true) },
            )
          }
        }

        val completedDownloads = matchingDownloads.filter { it.isCompleted }
        val completedYtdlp = matchingYtdlp.filter { it.state == YtdlpDownloadEngine.JobState.SUCCESS }
        val completedCount = completedDownloads.size + completedYtdlp.size
        if (completedDownloads.isNotEmpty() || completedYtdlp.isNotEmpty()) {
          item(key = "completed_header") { SectionHeader(stringResource(R.string.downloads_completed_section), completedCount) }
          itemsIndexed(completedYtdlp, key = { _, job -> "ytdlp_done_${job.id}" }) { index, job ->
            CompletedRow(
              title = job.title,
              shape = rememberConnectedShape(index, completedCount, ConnectedLayout.Vertical),
              subtitle = job.outputFile?.let { File(it).name }.orEmpty(),
              posterUrl = job.posterUrl,
              mediaPath = job.outputFile,
              playable = job.outputFile?.let { File(it).isFile } == true,
              onPlay = {
                job.outputFile?.let { path ->
                  MediaUtils.playFile(source = path, context = context, launchSource = "downloads", title = job.title)
                }
              },
              onDelete = {
                pendingYtdlpDelete = job
              },
            )
          }
          itemsIndexed(completedDownloads, key = { _, download -> "dl_done_${download.id}" }) { index, download ->
            CompletedRow(
              title = download.displayTitle,
              shape = rememberConnectedShape(index + completedYtdlp.size, completedCount, ConnectedLayout.Vertical),
              posterUrl = download.entity.posterUrl,
              mediaPath = download.file.absolutePath,
              subtitle = buildString {
                append(download.entity.fileName)
                if (download.entity.totalBytes > 0) {
                  append("  •  ")
                  append(formatBytes(download.entity.totalBytes))
                }
              },
              playable = download.isPlayable,
              onPlay = {
                MediaUtils.playFile(
                  source = download.file.absolutePath,
                  context = context,
                  launchSource = "downloads",
                  title = download.displayTitle,
                  subtitles = downloadManager.sidecarSubtitles(download).map { Uri.fromFile(it) },
                  posterUrl = download.entity.posterUrl,
                  isAudio = download.entity.isAudio,
                )
              },
              onDelete = { pendingDelete = download },
            )
          }
        }
      }
    }

    pendingYtdlpDelete?.let { job ->
      AlertDialog(
        onDismissRequest = { pendingYtdlpDelete = null },
        title = { Text(stringResource(R.string.downloads_delete_confirm_title)) },
        singleRowActions = true,
        text = { Text(stringResource(R.string.downloads_delete_confirm_message, job.title)) },
        confirmButton = {
          app.gyrolet.mpvrx.presentation.components.DeleteSizeButton(
            paths = (job.artifactFiles + listOfNotNull(job.outputFile)).toList(),
            onClick = {
              ytdlpEngine.remove(job.id, deleteFiles = true)
              pendingYtdlpDelete = null
            },
          )
        },
        dismissButton = {
          Column {
            TextButton(onClick = {
              ytdlpEngine.remove(job.id, deleteFiles = false)
              pendingYtdlpDelete = null
            }) { Text(stringResource(R.string.downloads_remove_entry)) }
            TextButton(onClick = { pendingYtdlpDelete = null }) { Text(stringResource(R.string.generic_cancel)) }
          }
        },
      )
    }

    pendingDelete?.let { download ->
      AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(R.string.downloads_delete_confirm_title)) },
        singleRowActions = true,
        text = { Text(stringResource(R.string.downloads_delete_confirm_message, download.displayTitle)) },
        confirmButton = {
          app.gyrolet.mpvrx.presentation.components.DeleteSizeButton(
            paths = listOf(download.file.absolutePath),
            onClick = {
              downloadManager.remove(download, deleteFile = true)
              pendingDelete = null
            },
          )
        },
        dismissButton = {
          Column {
            TextButton(
              onClick = {
                downloadManager.remove(download, deleteFile = false)
                pendingDelete = null
              },
            ) { Text(stringResource(R.string.downloads_remove_entry)) }
            TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.generic_cancel)) }
          }
        },
      )
    }
  }
}

private enum class DownloadFilter(val label: Int) {
  ALL(R.string.downloads_all),
  ACTIVE(R.string.downloads_active_section),
  COMPLETED(R.string.downloads_completed_section),
  ATTENTION(R.string.downloads_attention_section),
  ;

  fun matches(download: AppDownload): Boolean = when (this) {
    ALL -> true
    ACTIVE -> download.isActive
    COMPLETED -> download.isCompleted
    ATTENTION -> download.status == AppDownloadStatus.FAILED || download.status == AppDownloadStatus.CANCELLED
  }

  fun matches(job: YtdlpDownloadEngine.Job): Boolean = when (this) {
    ALL -> true
    ACTIVE -> job.isActive
    COMPLETED -> job.state == YtdlpDownloadEngine.JobState.SUCCESS
    ATTENTION -> job.state == YtdlpDownloadEngine.JobState.FAILED || job.state == YtdlpDownloadEngine.JobState.CANCELLED
  }
}

@Composable
private fun DownloadOverview(
  filter: DownloadFilter,
  counts: Map<DownloadFilter, Int>,
  onSelect: (DownloadFilter) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().selectableGroup(),
    verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
  ) {
    DownloadFilter.entries.chunked(2).forEachIndexed { row, filters ->
      Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        filters.forEachIndexed { column, category ->
          val isSelected = category == filter
          Surface(
            onClick = { onSelect(category) },
            modifier = Modifier.weight(1f).fillMaxHeight().semantics {
              selected = isSelected
              role = Role.RadioButton
            },
            shape = rememberConnectedShape(row * 2 + column, 4, ConnectedLayout.Grid, columns = 2),
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text = counts.getValue(category).toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
              )
              Text(text = stringResource(category.label), style = MaterialTheme.typography.labelLarge)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)
      .semantics { heading() },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f),
    )
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
      Text(count.toString(), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
    }
  }
}

@Composable
private fun DownloadLocationCard(
  path: String,
  isCustom: Boolean,
  onPick: () -> Unit,
  onReset: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(AppConnectedShapeTokens.outerCorner),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        Icons.RoundedFilled.Folder,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = stringResource(R.string.downloads_location),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = if (isCustom) path else stringResource(R.string.downloads_location_default),
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (isCustom) {
        IconButton(onClick = onReset) {
          Icon(
            Icons.RoundedFilled.Close,
            contentDescription = stringResource(R.string.downloads_location_reset),
            modifier = Modifier.size(20.dp),
          )
        }
      }
      IconButton(onClick = onPick) {
        Icon(
          Icons.RoundedFilled.Edit,
          contentDescription = stringResource(R.string.downloads_location),
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}

@Composable
private fun DownloadThumbnail(posterUrl: String?, mediaPath: String?) {
  val repository = koinInject<ThumbnailRepository>()
  val revision by repository.cacheRevision.collectAsState()
  val generator by koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>().thumbnailGenerator.collectAsState()
  val density = LocalDensity.current
  val widthPx = with(density) { 88.dp.roundToPx() }
  val heightPx = (widthPx * 9f / 16f).toInt().coerceAtLeast(1)
  val localThumbnail by produceState<ImageBitmap?>(null, mediaPath, widthPx, heightPx, generator, revision) {
    value = null
    if (!mediaPath.isNullOrBlank()) {
      val audio = mediaPath.substringAfterLast('.', "").lowercase() in app.gyrolet.mpvrx.utils.storage.FileTypeUtils.AUDIO_EXTENSIONS
      value = repository.getArtwork(mediaPath, isAudio = audio, widthPx = widthPx, heightPx = heightPx)?.asImageBitmap()
    }
  }
  Box(
    modifier = Modifier.width(88.dp).aspectRatio(16f / 9f)
      .clip(RoundedCornerShape(16.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.RoundedFilled.FileDownload,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(24.dp),
    )
    localThumbnail?.let { bitmap ->
      Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    }
    posterUrl?.takeIf { it.isNotBlank() && mediaPath.isNullOrBlank() }?.let { url ->
      RemoteImage(
        url = url,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    }
  }
}

@Composable
private fun DownloadMediaRow(
  title: String,
  shape: Shape,
  subtitle: String,
  posterUrl: String?,
  mediaPath: String? = null,
  isError: Boolean = false,
  progress: Float? = null,
  indeterminate: Boolean = false,
  actions: @Composable RowScope.() -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = shape,
    colors = CardDefaults.cardColors(
      containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
      contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        DownloadThumbnail(posterUrl, mediaPath)
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isError) 4 else 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (indeterminate) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
      } else if (progress != null) {
        LinearProgressIndicator(
          progress = { progress.coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth().height(6.dp),
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = actions,
      )
    }
  }
}

@Composable
private fun ActiveDownloadRow(
  download: AppDownload,
  shape: Shape,
  speedBytesPerSec: Long,
  onRetry: () -> Unit,
  onCancel: () -> Unit,
) {
  val status = download.status
  DownloadMediaRow(
    shape = shape,
    title = download.displayTitle,
    subtitle = downloadStatusLine(download, speedBytesPerSec),
    posterUrl = download.entity.posterUrl,
    isError = status == AppDownloadStatus.FAILED,
    progress = (download.entity.progress / 100f).takeIf { status == AppDownloadStatus.RUNNING },
    indeterminate = status == AppDownloadStatus.QUEUED ||
      status == AppDownloadStatus.RUNNING && download.entity.totalBytes <= 0,
  ) {
    if (status == AppDownloadStatus.FAILED || status == AppDownloadStatus.CANCELLED) {
      FilledTonalIconButton(onClick = onRetry) {
        Icon(Icons.RoundedFilled.Refresh, contentDescription = stringResource(R.string.downloads_retry))
      }
    }
    IconButton(onClick = onCancel) {
      Icon(
        Icons.RoundedFilled.Close,
        contentDescription = stringResource(if (download.isActive) R.string.downloads_cancel else R.string.downloads_remove_entry),
      )
    }
  }
}

@Composable
private fun YtdlpJobRow(
  job: YtdlpDownloadEngine.Job,
  shape: Shape,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onRemove: () -> Unit,
) {
  DownloadMediaRow(
    shape = shape,
    title = job.title,
    subtitle = ytdlpStatusLine(job),
    posterUrl = job.posterUrl,
    isError = job.state == YtdlpDownloadEngine.JobState.FAILED,
    progress = (job.progressPercent / 100f).takeIf { job.state == YtdlpDownloadEngine.JobState.RUNNING },
    indeterminate = job.state == YtdlpDownloadEngine.JobState.QUEUED ||
      job.state == YtdlpDownloadEngine.JobState.RUNNING && job.progressPercent <= 0f,
  ) {
    when (job.state) {
      YtdlpDownloadEngine.JobState.FAILED, YtdlpDownloadEngine.JobState.CANCELLED -> {
        FilledTonalIconButton(onClick = onRetry) {
          Icon(Icons.RoundedFilled.Refresh, contentDescription = stringResource(R.string.downloads_retry))
        }
        IconButton(onClick = onRemove) {
          Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.downloads_remove_entry))
        }
      }
      else -> {
        IconButton(onClick = onCancel) {
          Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.downloads_cancel))
        }
      }
    }
  }
}

@Composable
private fun CompletedRow(
  title: String,
  shape: Shape,
  subtitle: String,
  posterUrl: String?,
  mediaPath: String?,
  playable: Boolean,
  onPlay: () -> Unit,
  onDelete: () -> Unit,
) {
  DownloadMediaRow(
    shape = shape,
    title = title,
    subtitle = subtitle,
    posterUrl = posterUrl,
    mediaPath = mediaPath,
  ) {
    FilledTonalIconButton(onClick = onPlay, enabled = playable) {
      Icon(
        Icons.RoundedFilled.PlayArrow,
        contentDescription = stringResource(R.string.downloads_play),
        tint = if (playable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
      )
    }
    IconButton(onClick = onDelete) {
      Icon(Icons.RoundedFilled.Delete, contentDescription = stringResource(R.string.downloads_delete_file))
    }
  }
}

@Composable
private fun downloadStatusLine(
  download: AppDownload,
  speedBytesPerSec: Long = 0L,
): String {
  val entity = download.entity
  return when (download.status) {
    AppDownloadStatus.QUEUED -> stringResource(R.string.downloads_queued)
    AppDownloadStatus.FAILED ->
      stringResource(R.string.downloads_failed) +
        entity.failureReason?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    AppDownloadStatus.CANCELLED -> stringResource(R.string.downloads_cancelled)
    AppDownloadStatus.SUCCESS -> stringResource(R.string.downloads_downloaded)
    AppDownloadStatus.RUNNING ->
      buildString {
        append("${entity.progress}%")
        if (entity.totalBytes > 0) {
          val downloadedBytes = entity.totalBytes * entity.progress / 100
          append("  •  ${formatBytes(downloadedBytes)}/${formatBytes(entity.totalBytes)}")
        }
        if (speedBytesPerSec > 0) append("  •  ${formatBytes(speedBytesPerSec)}/s")
      }
  }
}

@Composable
private fun ytdlpStatusLine(job: YtdlpDownloadEngine.Job): String =
  when (job.state) {
    YtdlpDownloadEngine.JobState.QUEUED -> stringResource(R.string.downloads_queued)
    YtdlpDownloadEngine.JobState.RUNNING ->
      "${"%.1f".format(Locale.US, job.progressPercent)}% ${job.detail}".trim()
    YtdlpDownloadEngine.JobState.FAILED ->
      stringResource(R.string.downloads_failed) + job.error?.let { ": $it" }.orEmpty()
    YtdlpDownloadEngine.JobState.CANCELLED -> stringResource(R.string.downloads_cancelled)
    YtdlpDownloadEngine.JobState.SUCCESS -> stringResource(R.string.downloads_downloaded)
  }
