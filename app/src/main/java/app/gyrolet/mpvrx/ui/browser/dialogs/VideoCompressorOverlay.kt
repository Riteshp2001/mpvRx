/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import app.gyrolet.mpvrx.ui.components.IconSwitch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.launchSafely
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class CompressorScreenState {
  CONFIG,
  COMPRESSING,
  RESULT,
  ERROR,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCompressorOverlay(
  isOpen: Boolean,
  videos: List<Video>,
  onDismiss: () -> Unit,
) {
  if (!isOpen) return
  if (videos.isEmpty()) return

  val context = LocalContext.current
  val application = context.applicationContext as Application
  val viewModel: VideoCompressorViewModel =
    viewModel(
      key = "video_compressor_overlay",
      factory = VideoCompressorViewModel.factory(application),
    )
  val state by viewModel.uiState.collectAsState()
  val scope = rememberCoroutineScope()

  var showInfoDialog by rememberSaveable { mutableStateOf(false) }
  var showSettings by rememberSaveable { mutableStateOf(false) }
  var presetEditTarget by remember { mutableStateOf<VideoCompressionPreset?>(null) }

  LaunchedEffect(videos.map { it.id to it.uri }) {
    viewModel.loadVideos(context, videos)
  }

  val saveLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
      if (uri != null) {
        viewModel.saveToUri(context, uri)
      }
    }

  fun closeOverlay() {
    viewModel.resetSession()
    showInfoDialog = false
    onDismiss()
  }

  fun saveResult() {
    val filename =
      state.originalName
        ?.substringBeforeLast(".")
        ?.let { "${it}_compressed.mp4" }
        ?: "CompressedVideo.mp4"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      viewModel.saveToGallery(context)
    } else {
      saveLauncher.launchSafely(context, filename)
    }
  }

  BackHandler(enabled = true) {
    if (state.isCompressing) {
      viewModel.cancelCompression()
    }
    closeOverlay()
  }

  val screen =
    when {
      state.isCompressing -> CompressorScreenState.COMPRESSING
      state.error != null -> CompressorScreenState.ERROR
      state.compressedUri != null -> CompressorScreenState.RESULT
      else -> CompressorScreenState.CONFIG
    }

  Dialog(
    onDismissRequest = {
      if (state.isCompressing) {
        viewModel.cancelCompression()
      }
      closeOverlay()
    },
    properties =
      DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
        dismissOnBackPress = true,
        dismissOnClickOutside = false,
      ),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      AnimatedContent(
        targetState = screen,
        transitionSpec = {
          if (targetState == CompressorScreenState.COMPRESSING) {
            slideInVertically { it / 3 } + fadeIn() togetherWith fadeOut()
          } else {
            slideInHorizontally { it / 6 } + fadeIn() togetherWith slideOutHorizontally { -it / 6 } + fadeOut()
          }
        },
        label = "compressor-overlay-stage",
      ) { target ->
        when (target) {
          CompressorScreenState.CONFIG -> {
            CompressorConfigSurface(
              state = state,
              onClose = ::closeOverlay,
              onShowInfo = { showInfoDialog = true },
              onShowSettings = { showSettings = true },
              onStart = { viewModel.startCompression(context) },
              onApplyPreset = viewModel::applyPreset,
              onSetTargetSize = viewModel::setTargetSize,
              onSetTargetSizePreset = viewModel::setTargetSizePreset,
              onSetVideoCodec = viewModel::setVideoCodec,
              onSetResolution = viewModel::setResolution,
              onSetFps = viewModel::setFps,
              onToggleRemoveAudio = viewModel::toggleRemoveAudio,
              onSetAudioBitrate = viewModel::setAudioBitrate,
              onUpdateAudioVolume = viewModel::updateAudioVolume,
              onSetSaveMode = viewModel::setSaveMode,
              onEditPreset = { preset ->
                presetEditTarget = preset
                showSettings = true
              },
            )
          }

          CompressorScreenState.COMPRESSING -> {
            CompressorProgressSurface(
              state = state,
              onCancel = {
                viewModel.cancelCompression()
              },
            )
          }

          CompressorScreenState.RESULT -> {
            CompressorResultSurface(
              state = state,
              onClose = ::closeOverlay,
              onShare = {
                shareCompressedVideo(context, state.compressedUri, state.originalName)
              },
              onSave = ::saveResult,
            )
          }

          CompressorScreenState.ERROR -> {
            CompressorIssueSurface(
              title = stringResource(R.string.compressor_failed),
              message = state.error ?: "Unknown error",
              actionLabel = "Try again",
              onClose = ::closeOverlay,
              onAction = {
                viewModel.resetSession()
                viewModel.loadVideos(context, videos)
              },
              logs = state.errorLog,
            )
          }
        }
      }
    }
  }

  if (showSettings) {
    CompressorSettingsSheet(
      state = state,
      initialPreset = presetEditTarget,
      onDismiss = {
        showSettings = false
        presetEditTarget = null
      },
      onToggleShowStorageSaved = viewModel::toggleShowStorageSaved,
      onToggleShowTargetSizePreset = viewModel::toggleShowTargetSizePreset,
      onSaveQualityPreset = viewModel::saveQualityPreset,
      onResetQualityPresets = viewModel::resetQualityPresets,
      onSaveTargetSizePreset = viewModel::saveTargetSizePreset,
      onDeleteTargetSizePreset = viewModel::deleteTargetSizePreset,
      onResetTargetSizePresets = viewModel::resetTargetSizePresets,
      onSaveDefaultVideoConfig = viewModel::saveDefaultVideoConfig,
      onResetDefaultVideoConfig = viewModel::resetDefaultVideoConfig,
      onSaveDefaultAudioConfig = viewModel::saveDefaultAudioConfig,
      onResetDefaultAudioConfig = viewModel::resetDefaultAudioConfig,
    )
  }

  if (showInfoDialog) {
    val infoText =
      buildString {
        appendLine("App: ${state.appInfoVersion}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE}")
        append("Supported encoders: ${state.supportedCodecs.joinToString()}")
      }
    CompressorInfoDialog(
      state = state,
      onDismiss = { showInfoDialog = false },
      onToggleShowBitrate = viewModel::toggleShowBitrate,
      onToggleBitrateUnit = viewModel::toggleBitrateUnit,
      onTogglePreserveMetadata = viewModel::togglePreserveMetadata,
      onCopy = {
        scope.launch {
          SafeClipboard.copyPlainText(context, "compressor-info", infoText)
        }
      },
      onShare = {
        scope.launch {
          runCatching {
            val sendIntent =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, infoText)
              }
            context.startActivity(Intent.createChooser(sendIntent, "Share device info"))
          }
        }
      },
    )
  }
}

private fun shareCompressedVideo(
  context: android.content.Context,
  uri: Uri?,
  originalName: String?,
) {
  if (uri == null) return
  runCatching {
    val file = File(uri.path ?: return)
    val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val title = originalName?.substringBeforeLast(".")?.let { "${it}_compressed.mp4" } ?: "compressed_video.mp4"
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, contentUri)
        putExtra(Intent.EXTRA_TITLE, title)
        clipData = ClipData.newRawUri(title, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    context.startActivity(Intent.createChooser(intent, "Share compressed video"))
  }.onFailure {
    Toast
      .makeText(
        context,
        context.getString(
          R.string.toast_cannot_share_video,
          it.message ?: context.getString(R.string.generic_unknown_error),
        ),
        Toast.LENGTH_SHORT,
      ).show()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressorConfigSurface(
  state: VideoCompressorUiState,
  onClose: () -> Unit,
  onShowInfo: () -> Unit,
  onShowSettings: () -> Unit,
  onStart: () -> Unit,
  onApplyPreset: (VideoCompressionPreset) -> Unit,
  onSetTargetSize: (Float) -> Unit,
  onSetTargetSizePreset: (Float) -> Unit,
  onSetVideoCodec: (String) -> Unit,
  onSetResolution: (Int) -> Unit,
  onSetFps: (Int) -> Unit,
  onToggleRemoveAudio: () -> Unit,
  onSetAudioBitrate: (Int) -> Unit,
  onUpdateAudioVolume: (Float) -> Unit,
  onSetSaveMode: (VideoCompressorSaveMode) -> Unit,
  onEditPreset: (VideoCompressionPreset) -> Unit,
) {
  val pagerState = rememberPagerState(pageCount = { 3 })
  val scope = rememberCoroutineScope()
  val tabs = listOf("Presets", "Video", "Audio")
  val originalMb = state.originalSize / (1024f * 1024f)
  val actualEstimate = maxOf(state.targetSizeMb, state.minimumSizeMb)
  val isLarger = originalMb > 0f && actualEstimate > (originalMb + 0.01f)

  if (state.sourceVideo == null || state.originalWidth <= 0 || state.originalHeight <= 0) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        androidx.compose.material3.CircularProgressIndicator()
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_loading_video_info),
        )
      }
    }
    return
  }

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        title = {
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_compressor),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
        },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(
              Icons.RoundedFilled.Close,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_close),
            )
          }
        },
        actions = {
          IconButton(onClick = onShowSettings) {
            Icon(
              Icons.RoundedFilled.Settings,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_settings),
            )
          }
          IconButton(onClick = onShowInfo) {
            Icon(
              Icons.RoundedFilled.Info,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.info),
            )
          }
        },
      )
    },
  ) { innerPadding ->
    BoxWithConstraints(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding),
    ) {
      val splitLayout = maxWidth >= 760.dp
      if (splitLayout) {
        Row(modifier = Modifier.fillMaxSize()) {
          NavigationRail(
            modifier = Modifier.padding(top = 12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          ) {
            Spacer(modifier = Modifier.weight(1f))
            tabs.forEachIndexed { index, label ->
              NavigationRailItem(
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                icon = {
                  when (index) {
                    0 -> Icon(Icons.RoundedFilled.Settings, contentDescription = null)
                    1 -> Icon(Icons.RoundedFilled.Movie, contentDescription = null)
                    else -> Icon(Icons.RoundedFilled.Audiotrack, contentDescription = null)
                  }
                },
                label = { Text(label) },
              )
            }
            Spacer(modifier = Modifier.weight(1f))
          }

          VerticalDivider()

          Column(
            modifier =
              Modifier
                .fillMaxSize()
                .weight(1f),
          ) {
            Column(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 24.dp, vertical = 20.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              CompressorInfoCard(state = state)
              CompressorDestinationCard(state = state, onSetSaveMode = onSetSaveMode)
            }

            Box(modifier = Modifier.weight(1f)) {
              HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize(),
              ) { page ->
                when (page) {
                  0 ->
                    CompressorPresetsTab(
                      state = state,
                      onApplyPreset = onApplyPreset,
                      onSetTargetSize = onSetTargetSize,
                      onSetTargetSizePreset = onSetTargetSizePreset,
                      onEditPreset = onEditPreset,
                    )
                  1 -> CompressorVideoTab(state, onSetTargetSize, onSetVideoCodec, onSetResolution, onSetFps)
                  else -> CompressorAudioTab(state, onToggleRemoveAudio, onSetAudioBitrate, onUpdateAudioVolume)
                }
              }
            }

            CompressorBottomBar(
              enabled = !isLarger,
              onStart = onStart,
              isBatch = state.isBatch,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      } else {
        Column(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CompressorInfoCard(state = state)
            CompressorDestinationCard(state = state, onSetSaveMode = onSetSaveMode)
          }

          PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, label ->
              Tab(
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = { Text(label) },
              )
            }
          }

          Box(modifier = Modifier.weight(1f)) {
            HorizontalPager(
              state = pagerState,
              modifier = Modifier.fillMaxSize(),
            ) { page ->
              when (page) {
                0 ->
                  CompressorPresetsTab(
                    state = state,
                    onApplyPreset = onApplyPreset,
                    onSetTargetSize = onSetTargetSize,
                    onSetTargetSizePreset = onSetTargetSizePreset,
                    onEditPreset = onEditPreset,
                  )
                1 -> CompressorVideoTab(state, onSetTargetSize, onSetVideoCodec, onSetResolution, onSetFps)
                else -> CompressorAudioTab(state, onToggleRemoveAudio, onSetAudioBitrate, onUpdateAudioVolume)
              }
            }
          }

          CompressorBottomBar(
            enabled = !isLarger,
            onStart = onStart,
            isBatch = state.isBatch,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompressorDestinationCard(
  state: VideoCompressorUiState,
  onSetSaveMode: (VideoCompressorSaveMode) -> Unit,
) {
  ElevatedCard(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = AppShapeScale.extraLarge,
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_save_to),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        if (state.isBatch) {
          Text(
            "${state.queueSize} videos selected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(
          selected = state.saveMode == VideoCompressorSaveMode.CURRENT_FOLDER,
          onClick = { onSetSaveMode(VideoCompressorSaveMode.CURRENT_FOLDER) },
          label = {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_current_folder),
            )
          },
        )
        FilterChip(
          selected = state.saveMode == VideoCompressorSaveMode.MOVIES_COMPRESSOR,
          onClick = { onSetSaveMode(VideoCompressorSaveMode.MOVIES_COMPRESSOR) },
          label = {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_movies_compressor),
            )
          },
        )
      }

      Text(
        text = state.destinationDisplayPath.ifBlank { "Destination will be resolved when compression starts." },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun CompressorBottomBar(
  enabled: Boolean,
  onStart: () -> Unit,
  isBatch: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .background(
          Brush.verticalGradient(
            colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
          ),
        ),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
          .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
      Button(
        onClick = onStart,
        enabled = enabled,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = AppShapeScale.largeIncreased,
      ) {
        Text(
          if (isBatch) {
            androidx.compose.ui.res.stringResource(
              R.string.compressor_start_batch,
            )
          } else {
            androidx.compose.ui.res
              .stringResource(R.string.compressor_start)
          },
        )
      }
    }
  }
}

@Composable
private fun CompressorInfoCard(state: VideoCompressorUiState) {
  ElevatedCard(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = AppShapeScale.extraLarge,
  ) {
    Row(
      modifier = Modifier.padding(20.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = if (state.isBatch) "Source preview" else "Original",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.originalName.isNullOrBlank()) {
          Text(
            text = state.originalName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = state.formattedOriginalSize,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = "${state.originalWidth}x${state.originalHeight} - ${state.originalFps.toInt()}fps",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.showBitrate && state.formattedOriginalBitrate.isNotBlank()) {
          Text(
            text = state.formattedOriginalBitrate,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      VerticalDivider(modifier = Modifier.height(56.dp))

      Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.End,
      ) {
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_estimated),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = state.estimatedSize,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
        )
        val targetHeight = if (state.targetResolutionHeight > 0) state.targetResolutionHeight else state.originalHeight
        val targetWidth =
          if (state.originalHeight > 0) {
            (state.originalWidth.toFloat() / state.originalHeight * targetHeight).toInt()
          } else {
            0
          }
        val targetFps = if (state.targetFps > 0) state.targetFps else state.originalFps.toInt()
        Text(
          text = "${targetWidth}x$targetHeight - ${targetFps}fps",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          if (state.showBitrate && state.formattedBitrate.isNotBlank()) {
            Text(
              text = state.formattedBitrate,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          val originalMb = state.originalSize / (1024f * 1024f)
          val actualEstimate = maxOf(state.targetSizeMb, state.minimumSizeMb)
          if (originalMb > 0f) {
            val percent = ((1f - (actualEstimate / originalMb)) * 100f).toInt()
            Text(
              text = if (percent >= 0) "-$percent%" else "+${-percent}%",
              style = MaterialTheme.typography.labelSmall,
              color = if (percent >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompressorPresetsTab(
  state: VideoCompressorUiState,
  onApplyPreset: (VideoCompressionPreset) -> Unit,
  onSetTargetSize: (Float) -> Unit,
  onSetTargetSizePreset: (Float) -> Unit,
  onEditPreset: (VideoCompressionPreset) -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Text(
      androidx.compose.ui.res
        .stringResource(app.gyrolet.mpvrx.R.string.ui_change_video_quality),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )

    val presets =
      listOf(
        Triple(VideoCompressionPreset.HIGH, "High", describeQualityConfig(state.highPresetConfig)),
        Triple(VideoCompressionPreset.MEDIUM, "Medium", describeQualityConfig(state.mediumPresetConfig)),
        Triple(VideoCompressionPreset.LOW, "Low", describeQualityConfig(state.lowPresetConfig)),
      )

    presets.forEach { (preset, title, subtitle) ->
      val enabled =
        when (preset) {
          VideoCompressionPreset.MEDIUM -> state.originalHeight >= 1080
          VideoCompressionPreset.LOW -> state.originalHeight >= 720
          else -> true
        }
      OutlinedCard(
        onClick = { onApplyPreset(preset) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors =
          CardDefaults.outlinedCardColors(
            containerColor =
              if (state.activePreset == preset) {
                MaterialTheme.colorScheme.secondaryContainer
              } else {
                MaterialTheme.colorScheme.surface
              },
          ),
        shape = AppShapeScale.largeIncreased,
      ) {
        Row(
          modifier = Modifier.padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
              subtitle,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (state.activePreset == preset) {
            Icon(Icons.RoundedFilled.Check, contentDescription = null)
          }
          IconButton(onClick = { onEditPreset(preset) }) {
            Icon(
              Icons.RoundedFilled.Edit,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_settings),
            )
          }
        }
      }
    }

    val originalMb = state.originalSize / (1024f * 1024f)

    if (state.showTargetSizePreset) {
      val sizePresets = state.targetSizePresets.filter { it.sizeMb < originalMb }

      if (sizePresets.isNotEmpty()) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_target_size_presets),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          sizePresets.forEach { preset ->
            FilterChip(
              selected = state.targetSizeMb == preset.sizeMb,
              onClick = { onSetTargetSizePreset(preset.sizeMb) },
              label = {
                Text(
                  text =
                    buildString {
                      if (preset.sizeMb >= 1024) {
                        append("${(preset.sizeMb / 1024).toInt()} GB")
                      } else {
                        append("${preset.sizeMb.toInt()} MB")
                      }
                      append(" - ")
                      append(preset.label)
                    },
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CompressorVideoTab(
  state: VideoCompressorUiState,
  onSetTargetSize: (Float) -> Unit,
  onSetVideoCodec: (String) -> Unit,
  onSetResolution: (Int) -> Unit,
  onSetFps: (Int) -> Unit,
) {
  val scrollState = rememberScrollState()
  var sliderValue by remember(state.targetSizeMb) { mutableFloatStateOf(state.targetSizeMb) }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(scrollState)
        .padding(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text(
      androidx.compose.ui.res
        .stringResource(app.gyrolet.mpvrx.R.string.ui_advanced_options),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )

    Text(
      androidx.compose.ui.res
        .stringResource(app.gyrolet.mpvrx.R.string.ui_target_size),
      style = MaterialTheme.typography.labelLarge,
    )
    Text(
      text = String.format(Locale.US, "%.1f MB", sliderValue),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
    )
    Slider(
      value = sliderValue,
      onValueChange = {
        sliderValue = it
        onSetTargetSize(it)
      },
      valueRange = 0.1f..maxOf(10f, state.targetSizeMb, (state.originalSize.toFloat() / (1024f * 1024f))),
    )

    Text(
      androidx.compose.ui.res
        .stringResource(app.gyrolet.mpvrx.R.string.ui_encoding),
      style = MaterialTheme.typography.labelLarge,
    )
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (state.supportedCodecs.contains(androidx.media3.common.MimeTypes.VIDEO_AV1)) {
        FilterChip(
          selected = state.videoCodec == androidx.media3.common.MimeTypes.VIDEO_AV1,
          onClick = { onSetVideoCodec(androidx.media3.common.MimeTypes.VIDEO_AV1) },
          label = {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_av1),
            )
          },
        )
      }
      if (state.supportedCodecs.contains(androidx.media3.common.MimeTypes.VIDEO_H265)) {
        FilterChip(
          selected = state.videoCodec == androidx.media3.common.MimeTypes.VIDEO_H265,
          onClick = { onSetVideoCodec(androidx.media3.common.MimeTypes.VIDEO_H265) },
          label = {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_h_265),
            )
          },
        )
      }
      FilterChip(
        selected = state.videoCodec == androidx.media3.common.MimeTypes.VIDEO_H264,
        onClick = { onSetVideoCodec(androidx.media3.common.MimeTypes.VIDEO_H264) },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_h_264),
          )
        },
      )
    }

    Text(
      androidx.compose.ui.res
        .stringResource(app.gyrolet.mpvrx.R.string.ui_resolution),
      style = MaterialTheme.typography.labelLarge,
    )
    val originalShortSide = minOf(state.originalWidth, state.originalHeight)
    val currentShortSide =
      if (state.originalHeight > state.originalWidth && state.targetResolutionHeight > 0 && state.originalHeight > 0) {
        (state.targetResolutionHeight.toLong() * state.originalWidth / state.originalHeight).toInt()
      } else if (state.targetResolutionHeight > 0) {
        state.targetResolutionHeight
      } else {
        originalShortSide
      }
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val options =
        buildList {
          add(originalShortSide to "Original")
          listOf(
            2160,
            1440,
            1080,
            720,
            540,
            480,
            (originalShortSide * 3) / 4,
            originalShortSide / 2,
            originalShortSide / 4,
          ).filter { it > 0 && it < originalShortSide }
            .distinct()
            .forEach { add(it to "${it}p") }
        }
      options.forEach { (value, label) ->
        FilterChip(
          selected =
            currentShortSide == value || (label == "Original" && state.targetResolutionHeight == state.originalHeight),
          onClick = { onSetResolution(value) },
          label = { Text(label) },
        )
      }
    }

    Text(
      androidx.compose.ui.res
        .stringResource(app.gyrolet.mpvrx.R.string.ui_framerate),
      style = MaterialTheme.typography.labelLarge,
    )
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = state.targetFps == 0,
        onClick = { onSetFps(0) },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(R.string.compressor_original_fps, state.originalFps.toInt()),
          )
        },
      )
      FilterChip(
        selected = state.targetFps == 60,
        enabled = state.originalFps >= 50f,
        onClick = { onSetFps(60) },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_60fps),
          )
        },
      )
      FilterChip(
        selected = state.targetFps == 30,
        onClick = { onSetFps(30) },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_30fps),
          )
        },
      )
      FilterChip(
        selected = state.targetFps == 24,
        onClick = { onSetFps(24) },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_24fps),
          )
        },
      )
    }
  }
}

@Composable
private fun CompressorAudioTab(
  state: VideoCompressorUiState,
  onToggleRemoveAudio: () -> Unit,
  onSetAudioBitrate: (Int) -> Unit,
  onUpdateAudioVolume: (Float) -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text(
      androidx.compose.ui.res
        .stringResource(app.gyrolet.mpvrx.R.string.ui_audio_options),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.ui_remove_audio),
        style = MaterialTheme.typography.bodyLarge,
      )
      IconSwitch(checked = state.removeAudio, onCheckedChange = { onToggleRemoveAudio() })
    }

    AnimatedVisibility(visible = !state.removeAudio) {
      Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_audio_bitrate),
          style = MaterialTheme.typography.labelLarge,
        )
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          listOf(0, 320000, 256000, 192000, 160000, 128000, 96000, 64000).forEach { bitrate ->
            if (bitrate == 0 || state.originalAudioBitrate <= 0 || bitrate <= state.originalAudioBitrate) {
              val effective = if (state.audioBitrate == 0) state.originalAudioBitrate else state.audioBitrate
              val chipValue = if (bitrate == 0) state.originalAudioBitrate else bitrate
              FilterChip(
                selected = effective == chipValue,
                onClick = { onSetAudioBitrate(bitrate) },
                label = {
                  Text(
                    if (bitrate == 0) {
                      "Original - ${maxOf(state.originalAudioBitrate, 0) / 1000}k"
                    } else {
                      "${bitrate / 1000}k"
                    },
                  )
                },
              )
            }
          }
        }

        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.compressor_volume),
          style = MaterialTheme.typography.labelLarge,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            text = "${(state.audioVolume * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(48.dp),
          )
          Slider(
            value = state.audioVolume.coerceIn(0f, 2f),
            onValueChange = onUpdateAudioVolume,
            valueRange = 0f..2f,
            steps = 19,
            modifier = Modifier.weight(1f),
          )
        }
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.compressor_volume_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun CompressorProgressSurface(
  state: VideoCompressorUiState,
  onCancel: () -> Unit,
) {
  val context = LocalContext.current
  var thumbnail by remember(state.sourceUri) { mutableStateOf<ImageBitmap?>(null) }

  LaunchedEffect(state.sourceUri) {
    val sourceUri = state.sourceUri ?: return@LaunchedEffect
    thumbnail =
      withContext(Dispatchers.IO) {
        runCatching {
          val retriever = MediaMetadataRetriever()
          try {
            retriever.setDataSource(context, sourceUri)
            retriever.getFrameAtTime(0)?.toSafeImageBitmap()
          } finally {
            runCatching { retriever.release() }
          }
        }.getOrNull()
      }
  }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.widthIn(max = 720.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceContainer, AppShapeScale.extraLarge),
      ) {
        thumbnail?.let {
          Image(
            bitmap = it,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
          )
        }
      }

      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = AppShapeScale.extraLarge,
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_compressing_video),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text =
              if (state.isBatch) {
                "File ${state.currentQueueIndex + 1} of ${state.queueSize} - ${state.originalName ?: state.sourceVideo?.displayName.orEmpty()}"
              } else {
                state.originalName ?: state.sourceVideo?.displayName.orEmpty()
              },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            state.formattedCurrentOutputSize,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
          )

          if (!state.progressAvailable) {
            LinearProgressIndicator(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .height(4.dp),
            )
          } else {
            LinearProgressIndicator(
              progress = { state.currentItemProgress.coerceIn(0f, 1f) },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .height(4.dp),
            )
          }
          if (state.progressAvailable) {
            Text(
              text = "Overall ${(state.progress * 100f).toInt()}% - Current ${(state.currentItemProgress * 100f).toInt()}%",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(top = 2.dp),
            )
          }
        }
      }

      Button(
        onClick = onCancel,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
          ),
        shape = AppShapeScale.largeIncreased,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
        )
      }
    }
  }
}

@Composable
private fun CompressorResultSurface(
  state: VideoCompressorUiState,
  onClose: () -> Unit,
  onShare: () -> Unit,
  onSave: () -> Unit,
) {
  val reduction =
    if (state.originalSize > 0L) {
      (((state.originalSize - state.compressedSize).toFloat() / state.originalSize) * 100f).toInt()
    } else {
      0
    }

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        title = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_compressor),
            fontWeight = FontWeight.Bold,
          )
        },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(
              Icons.RoundedFilled.Close,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_close),
            )
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier =
          Modifier
            .widthIn(max = 640.dp)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          shape = AppShapeScale.full,
        ) {
          Icon(
            Icons.RoundedFilled.Check,
            contentDescription = null,
            modifier = Modifier.padding(24.dp).size(48.dp),
          )
        }
        Text(
          if (state.isBatch) "Batch Compression Complete!" else "Compression Complete!",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
        )
        if (state.isBatch) {
          Text(
            "${state.completedCount} videos saved",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Text(
            "${state.formattedOriginalSize} -> ${state.formattedCompressedSize}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (reduction > 0) {
            Text(
              stringResource(R.string.compressor_reduction_percent, reduction),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        if (state.showStorageSaved && state.totalSavedBytes > 0L) {
          Text(
            stringResource(R.string.compressor_total_saved, state.formattedTotalSaved),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }

        ElevatedCard(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_saved_to),
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              state.destinationDisplayPath.ifBlank { "Unknown destination" },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        if (state.warnings.isNotEmpty()) {
          ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_warnings),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
              state.warnings.forEach {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
              }
            }
          }
        }

        if (!state.isBatch) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Button(
              onClick = onShare,
              modifier = Modifier.weight(1f),
              shape = AppShapeScale.largeIncreased,
            ) {
              Icon(Icons.RoundedFilled.Share, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.generic_share),
              )
            }
            FilledTonalButton(
              onClick = onSave,
              modifier = Modifier.weight(1f),
              shape = AppShapeScale.largeIncreased,
            ) {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_save_copy),
              )
            }
          }
        }

        TextButton(onClick = onClose) {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_back_to_list),
          )
        }
      }
    }
  }
}

@Composable
private fun CompressorIssueSurface(
  title: String,
  message: String,
  actionLabel: String,
  onClose: () -> Unit,
  onAction: () -> Unit,
  logs: String?,
) {
  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        title = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_compressor),
            fontWeight = FontWeight.Bold,
          )
        },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(
              Icons.RoundedFilled.Close,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_close),
            )
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = AppShapeScale.extraLarge,
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(Icons.RoundedFilled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
          }
          Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (!logs.isNullOrBlank()) {
            HorizontalDivider()
            Text(
              logs,
              modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_close),
              )
            }
            Button(onClick = onAction, modifier = Modifier.weight(1f)) {
              Text(actionLabel)
            }
          }
        }
      }
    }
  }
}

private fun describeQualityConfig(config: QualityPresetConfig): String =
  buildString {
    if (config.resolutionShortSide > 0) {
      append("${config.resolutionShortSide}p")
    }
    if (config.targetFps > 0) {
      if (isNotEmpty()) append(" - ")
      append("${config.targetFps}fps")
    }
    if (config.sizeRatio > 0f) {
      if (isNotEmpty()) append(" - ")
      append("${(config.sizeRatio * 100).toInt()}% of original size")
    }
    if (isEmpty()) append("Optimized bitrate only")
  }

private fun describeDefaultVideoConfig(config: DefaultVideoConfig): String =
  buildString {
    append(config.defaultVideoCodec.substringAfter("/").uppercase(Locale.US))
    if (config.defaultTargetResolutionHeight > 0) {
      append(" • ${config.defaultTargetResolutionHeight}p")
    }
    if (config.defaultTargetFps > 0) {
      append(" • ${config.defaultTargetFps}fps")
    }
    if (config.defaultSizeRatio > 0f) {
      append(" • ${(config.defaultSizeRatio * 100).toInt()}% size")
    }
  }

private fun describeDefaultAudioConfig(config: DefaultAudioConfig): String =
  buildString {
    if (config.defaultRemoveAudio) {
      append("Remove audio")
    } else {
      append("${config.defaultAudioBitrate / 1000}k • ${(config.defaultAudioVolume * 100).toInt()}% volume")
    }
  }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressorSettingsSheet(
  state: VideoCompressorUiState,
  initialPreset: VideoCompressionPreset?,
  onDismiss: () -> Unit,
  onToggleShowStorageSaved: () -> Unit,
  onToggleShowTargetSizePreset: () -> Unit,
  onSaveQualityPreset: (VideoCompressionPreset, QualityPresetConfig) -> Unit,
  onResetQualityPresets: () -> Unit,
  onSaveTargetSizePreset: (TargetSizePreset) -> Unit,
  onDeleteTargetSizePreset: (TargetSizePreset) -> Unit,
  onResetTargetSizePresets: () -> Unit,
  onSaveDefaultVideoConfig: (DefaultVideoConfig) -> Unit,
  onResetDefaultVideoConfig: () -> Unit,
  onSaveDefaultAudioConfig: (DefaultAudioConfig) -> Unit,
  onResetDefaultAudioConfig: () -> Unit,
) {
  var editingPreset by remember { mutableStateOf<VideoCompressionPreset?>(null) }
  var editingTargetPreset by remember { mutableStateOf<TargetSizePreset?>(null) }
  var addingTargetPreset by remember { mutableStateOf(false) }
  var editingDefaultVideo by remember { mutableStateOf(false) }
  var editingDefaultAudio by remember { mutableStateOf(false) }

  LaunchedEffect(initialPreset) {
    if (initialPreset != null) {
      editingPreset = initialPreset
    }
  }

  ModalBottomSheet(
    onDismissRequest = {
      editingPreset = null
      editingTargetPreset = null
      addingTargetPreset = false
      editingDefaultVideo = false
      editingDefaultAudio = false
      onDismiss()
    },
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp)
          .padding(bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        stringResource(R.string.compressor_settings),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          stringResource(R.string.compressor_storage_saved),
          style = MaterialTheme.typography.bodyLarge,
        )
        IconSwitch(checked = state.showStorageSaved, onCheckedChange = { onToggleShowStorageSaved() })
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          stringResource(R.string.compressor_show_target_size_presets),
          style = MaterialTheme.typography.bodyLarge,
        )
        IconSwitch(checked = state.showTargetSizePreset, onCheckedChange = { onToggleShowTargetSizePreset() })
      }

      HorizontalDivider()

      Text(
        stringResource(R.string.compressor_preset_configs),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      val presetConfigs =
        listOf(
          VideoCompressionPreset.HIGH to state.highPresetConfig,
          VideoCompressionPreset.MEDIUM to state.mediumPresetConfig,
          VideoCompressionPreset.LOW to state.lowPresetConfig,
        )
      presetConfigs.forEach { (preset, config) ->
        OutlinedCard(
          onClick = { editingPreset = preset },
          modifier = Modifier.fillMaxWidth(),
          shape = AppShapeScale.largeIncreased,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(preset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
              Text(
                describeQualityConfig(config),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Icon(Icons.RoundedFilled.Edit, contentDescription = null)
          }
        }
      }
      TextButton(onClick = onResetQualityPresets) {
        Text(stringResource(R.string.compressor_reset_presets))
      }

      HorizontalDivider()

      Text(
        stringResource(R.string.ui_target_size_presets),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      state.targetSizePresets.forEach { preset ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedCard(
            onClick = { editingTargetPreset = preset },
            modifier = Modifier.weight(1f),
            shape = AppShapeScale.largeIncreased,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(preset.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                  if (preset.sizeMb >= 1024) {
                    "${(preset.sizeMb / 1024).toInt()} GB"
                  } else {
                    "${preset.sizeMb.toInt()} MB"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Icon(Icons.RoundedFilled.Edit, contentDescription = null)
            }
          }
          IconButton(onClick = { onDeleteTargetSizePreset(preset) }) {
            Icon(
              Icons.RoundedFilled.Delete,
              contentDescription = stringResource(R.string.compressor_delete_preset),
            )
          }
        }
      }
      Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { addingTargetPreset = true }) {
          Text(stringResource(R.string.compressor_add_preset))
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onResetTargetSizePresets) {
          Text(stringResource(R.string.compressor_reset_presets))
        }
      }

      HorizontalDivider()

      Text(
        stringResource(R.string.compressor_defaults),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      OutlinedCard(
        onClick = { editingDefaultVideo = true },
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapeScale.largeIncreased,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.compressor_default_video),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Medium,
            )
            Text(
              describeDefaultVideoConfig(state.defaultVideoConfig),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Icon(Icons.RoundedFilled.Edit, contentDescription = null)
        }
      }
      OutlinedCard(
        onClick = { editingDefaultAudio = true },
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapeScale.largeIncreased,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.compressor_default_audio),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Medium,
            )
            Text(
              describeDefaultAudioConfig(state.defaultAudioConfig),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Icon(Icons.RoundedFilled.Edit, contentDescription = null)
        }
      }
      Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onResetDefaultVideoConfig) {
          Text(stringResource(R.string.compressor_reset_video_defaults))
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onResetDefaultAudioConfig) {
          Text(stringResource(R.string.compressor_reset_audio_defaults))
        }
      }
    }
  }

  editingPreset?.let { preset ->
    val config =
      when (preset) {
        VideoCompressionPreset.HIGH -> state.highPresetConfig
        VideoCompressionPreset.MEDIUM -> state.mediumPresetConfig
        VideoCompressionPreset.LOW -> state.lowPresetConfig
        VideoCompressionPreset.CUSTOM -> state.highPresetConfig
      }
    QualityPresetConfigEditor(
      title = preset.name,
      config = config,
      onDismiss = { editingPreset = null },
      onSave = { saved ->
        onSaveQualityPreset(preset, saved)
        editingPreset = null
      },
    )
  }

  if (editingTargetPreset != null || addingTargetPreset) {
    TargetSizePresetEditor(
      preset = editingTargetPreset,
      onDismiss = {
        editingTargetPreset = null
        addingTargetPreset = false
      },
      onSave = { saved ->
        onSaveTargetSizePreset(saved)
        editingTargetPreset = null
        addingTargetPreset = false
      },
    )
  }

  if (editingDefaultVideo) {
    DefaultVideoConfigEditor(
      config = state.defaultVideoConfig,
      onDismiss = { editingDefaultVideo = false },
      onSave = { saved ->
        onSaveDefaultVideoConfig(saved)
        editingDefaultVideo = false
      },
    )
  }

  if (editingDefaultAudio) {
    DefaultAudioConfigEditor(
      config = state.defaultAudioConfig,
      onDismiss = { editingDefaultAudio = false },
      onSave = { saved ->
        onSaveDefaultAudioConfig(saved)
        editingDefaultAudio = false
      },
    )
  }
}

@Composable
private fun QualityPresetConfigEditor(
  title: String,
  config: QualityPresetConfig,
  onDismiss: () -> Unit,
  onSave: (QualityPresetConfig) -> Unit,
) {
  var resolution by remember(config) { mutableStateOf(config.resolutionShortSide.toString()) }
  var fps by remember(config) { mutableStateOf(config.targetFps.toString()) }
  var ratio by remember(config) { mutableStateOf(config.sizeRatio.toString()) }
  var bitrate by remember(config) { mutableStateOf(config.audioBitrate.toString()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Edit $title") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = resolution,
          onValueChange = { resolution = it.filter { char -> char.isDigit() } },
          label = { Text(stringResource(R.string.compressor_target_resolution)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = fps,
          onValueChange = { fps = it.filter { char -> char.isDigit() } },
          label = { Text(stringResource(R.string.compressor_target_fps)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = ratio,
          onValueChange = { ratio = it.filter { char -> char.isDigit() || char == '.' } },
          label = { Text(stringResource(R.string.compressor_size_ratio)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = bitrate,
          onValueChange = { bitrate = it.filter { char -> char.isDigit() } },
          label = { Text(stringResource(R.string.ui_audio_bitrate)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          stringResource(R.string.compressor_zero_keeps_original),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(
            QualityPresetConfig(
              resolutionShortSide = resolution.toIntOrNull() ?: 0,
              targetFps = fps.toIntOrNull() ?: 0,
              sizeRatio = ratio.toFloatOrNull() ?: 0f,
              audioBitrate = bitrate.toIntOrNull() ?: 0,
            ),
          )
        },
      ) {
        Text(stringResource(R.string.ui_done))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.ui_close))
      }
    },
  )
}

@Composable
private fun TargetSizePresetEditor(
  preset: TargetSizePreset?,
  onDismiss: () -> Unit,
  onSave: (TargetSizePreset) -> Unit,
) {
  var name by remember(preset) { mutableStateOf(preset?.label ?: "") }
  var sizeMb by remember(preset) { mutableStateOf(preset?.sizeMb?.toString() ?: "") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.compressor_target_size_presets_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text(stringResource(R.string.compressor_target_preset_name)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = sizeMb,
          onValueChange = { sizeMb = it.filter { char -> char.isDigit() || char == '.' } },
          label = { Text(stringResource(R.string.compressor_target_preset_size_mb)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(
        enabled = name.isNotBlank() && (sizeMb.toFloatOrNull() ?: 0f) > 0f,
        onClick = {
          onSave(
            TargetSizePreset(
              id = preset?.id ?: "custom_${System.currentTimeMillis()}",
              sizeMb = sizeMb.toFloatOrNull() ?: 0f,
              label = name.trim(),
              isCustom = true,
            ),
          )
        },
      ) {
        Text(stringResource(R.string.ui_done))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.ui_close))
      }
    },
  )
}

@Composable
private fun DefaultVideoConfigEditor(
  config: DefaultVideoConfig,
  onDismiss: () -> Unit,
  onSave: (DefaultVideoConfig) -> Unit,
) {
  var codec by remember(config) { mutableStateOf(config.defaultVideoCodec) }
  var resolution by remember(config) { mutableStateOf(config.defaultTargetResolutionHeight.toString()) }
  var fps by remember(config) { mutableStateOf(config.defaultTargetFps.toString()) }
  var ratio by remember(config) { mutableStateOf(config.defaultSizeRatio.toString()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.compressor_default_video)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = codec == androidx.media3.common.MimeTypes.VIDEO_H265,
            onClick = { codec = androidx.media3.common.MimeTypes.VIDEO_H265 },
            label = {
              Text(
                stringResource(app.gyrolet.mpvrx.R.string.ui_h_265),
              )
            },
          )
          FilterChip(
            selected = codec == androidx.media3.common.MimeTypes.VIDEO_H264,
            onClick = { codec = androidx.media3.common.MimeTypes.VIDEO_H264 },
            label = {
              Text(
                stringResource(app.gyrolet.mpvrx.R.string.ui_h_264),
              )
            },
          )
        }
        OutlinedTextField(
          value = resolution,
          onValueChange = { resolution = it.filter { char -> char.isDigit() } },
          label = { Text(stringResource(R.string.compressor_target_resolution)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = fps,
          onValueChange = { fps = it.filter { char -> char.isDigit() } },
          label = { Text(stringResource(R.string.compressor_target_fps)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = ratio,
          onValueChange = { ratio = it.filter { char -> char.isDigit() || char == '.' } },
          label = { Text(stringResource(R.string.compressor_size_ratio)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          stringResource(R.string.compressor_zero_keeps_original),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(
            DefaultVideoConfig(
              defaultVideoCodec = codec,
              defaultTargetResolutionHeight = resolution.toIntOrNull() ?: 0,
              defaultTargetFps = fps.toIntOrNull() ?: 0,
              defaultSizeRatio = ratio.toFloatOrNull() ?: 0f,
            ),
          )
        },
      ) {
        Text(stringResource(R.string.ui_done))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.ui_close))
      }
    },
  )
}

@Composable
private fun DefaultAudioConfigEditor(
  config: DefaultAudioConfig,
  onDismiss: () -> Unit,
  onSave: (DefaultAudioConfig) -> Unit,
) {
  var bitrate by remember(config) { mutableStateOf(config.defaultAudioBitrate.toString()) }
  var removeAudio by remember(config) { mutableStateOf(config.defaultRemoveAudio) }
  var volume by remember(config) { mutableFloatStateOf(config.defaultAudioVolume) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.compressor_default_audio)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = bitrate,
          onValueChange = { bitrate = it.filter { char -> char.isDigit() } },
          label = { Text(stringResource(R.string.ui_audio_bitrate)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            stringResource(R.string.ui_remove_audio),
            style = MaterialTheme.typography.bodyLarge,
          )
          IconSwitch(checked = removeAudio, onCheckedChange = { removeAudio = it })
        }
        if (!removeAudio) {
          Text(
            stringResource(R.string.compressor_volume),
            style = MaterialTheme.typography.labelLarge,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = "${(volume * 100).toInt()}%",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.width(48.dp),
            )
            Slider(
              value = volume.coerceIn(0f, 2f),
              onValueChange = { volume = it },
              valueRange = 0f..2f,
              steps = 19,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(
            DefaultAudioConfig(
              defaultAudioBitrate = bitrate.toIntOrNull() ?: 0,
              defaultRemoveAudio = removeAudio,
              defaultAudioVolume = volume,
            ),
          )
        },
      ) {
        Text(stringResource(R.string.ui_done))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.ui_close))
      }
    },
  )
}

@Composable
private fun CompressorInfoDialog(
  state: VideoCompressorUiState,
  onDismiss: () -> Unit,
  onToggleShowBitrate: () -> Unit,
  onToggleBitrateUnit: () -> Unit,
  onTogglePreserveMetadata: () -> Unit,
  onCopy: () -> Unit,
  onShare: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_device_and_app_info),
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          androidx.compose.ui.res
            .stringResource(R.string.compressor_version, state.appInfoVersion),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(R.string.compressor_device, Build.MANUFACTURER, Build.MODEL),
        )
        Text(
          androidx.compose.ui.res
            .stringResource(R.string.compressor_android_version, Build.VERSION.RELEASE),
        )
        HorizontalDivider()
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_show_bitrate),
          )
          IconSwitch(checked = state.showBitrate, onCheckedChange = { onToggleShowBitrate() })
        }
        if (state.showBitrate) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_use_mbps),
            )
            IconSwitch(checked = state.useMbps, onCheckedChange = { onToggleBitrateUnit() })
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_preserve_metadata),
          )
          IconSwitch(checked = state.preserveMetadata, onCheckedChange = { onTogglePreserveMetadata() })
        }
        HorizontalDivider()
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_supported_codecs),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        state.supportedCodecs.forEach {
          Text("- ${it.substringAfter("/")}", style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Row {
        TextButton(
          onClick = onShare,
        ) {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.generic_share),
          )
        }
        TextButton(
          onClick = onCopy,
        ) {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_copy),
          )
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_close),
        )
      }
    },
  )
}

private fun Bitmap.toSafeImageBitmap(): ImageBitmap = asImageBitmap()
