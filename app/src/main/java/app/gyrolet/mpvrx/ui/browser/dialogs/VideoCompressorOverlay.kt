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
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
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
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()

  var showInfoDialog by rememberSaveable { mutableStateOf(false) }

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
      saveLauncher.launch(filename)
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
    onDismissRequest = {},
    properties =
      DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
        dismissOnBackPress = false,
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
              onStart = { viewModel.startCompression(context) },
              onApplyPreset = viewModel::applyPreset,
              onSetTargetSize = viewModel::setTargetSize,
              onSetVideoCodec = viewModel::setVideoCodec,
              onSetResolution = viewModel::setResolution,
              onSetFps = viewModel::setFps,
              onToggleRemoveAudio = viewModel::toggleRemoveAudio,
              onSetAudioBitrate = viewModel::setAudioBitrate,
              onSetSaveMode = viewModel::setSaveMode,
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
              title = "Compression failed",
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
          clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("compressor-info", infoText)))
          Toast.makeText(context, "Copied device info", Toast.LENGTH_SHORT).show()
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
    Toast.makeText(context, "Cannot share video: ${it.message}", Toast.LENGTH_SHORT).show()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressorConfigSurface(
  state: VideoCompressorUiState,
  onClose: () -> Unit,
  onShowInfo: () -> Unit,
  onStart: () -> Unit,
  onApplyPreset: (VideoCompressionPreset) -> Unit,
  onSetTargetSize: (Float) -> Unit,
  onSetVideoCodec: (String) -> Unit,
  onSetResolution: (Int) -> Unit,
  onSetFps: (Int) -> Unit,
  onToggleRemoveAudio: () -> Unit,
  onSetAudioBitrate: (Int) -> Unit,
  onSetSaveMode: (VideoCompressorSaveMode) -> Unit,
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
        Text("Loading video info")
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
            text = "Compressor",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
        },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close")
          }
        },
        actions = {
          IconButton(onClick = onShowInfo) {
            Icon(Icons.Filled.Info, contentDescription = "Info")
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
                    0 -> Icon(Icons.Filled.Settings, contentDescription = null)
                    1 -> Icon(Icons.Default.Movie, contentDescription = null)
                    else -> Icon(Icons.Default.Audiotrack, contentDescription = null)
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
                  0 -> CompressorPresetsTab(state, onApplyPreset, onSetTargetSize)
                  1 -> CompressorVideoTab(state, onSetTargetSize, onSetVideoCodec, onSetResolution, onSetFps)
                  else -> CompressorAudioTab(state, onToggleRemoveAudio, onSetAudioBitrate)
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
                0 -> CompressorPresetsTab(state, onApplyPreset, onSetTargetSize)
                1 -> CompressorVideoTab(state, onSetTargetSize, onSetVideoCodec, onSetResolution, onSetFps)
                else -> CompressorAudioTab(state, onToggleRemoveAudio, onSetAudioBitrate)
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
    shape = RoundedCornerShape(24.dp),
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
        Text("Save to", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
          label = { Text("Current folder") },
        )
        FilterChip(
          selected = state.saveMode == VideoCompressorSaveMode.MOVIES_COMPRESSOR,
          onClick = { onSetSaveMode(VideoCompressorSaveMode.MOVIES_COMPRESSOR) },
          label = { Text("Movies/Compressor") },
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
        shape = RoundedCornerShape(18.dp),
      ) {
        Text(if (isBatch) "Start Batch Compression" else "Start Compression")
      }
    }
  }
}

@Composable
private fun CompressorInfoCard(state: VideoCompressorUiState) {
  ElevatedCard(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = RoundedCornerShape(24.dp),
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
          text = "Estimated",
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
          text = "${targetWidth}x${targetHeight} - ${targetFps}fps",
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
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Text("Change Video Quality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

    val presets =
      listOf(
        Triple(VideoCompressionPreset.HIGH, "High", "Optimized bitrate only"),
        Triple(VideoCompressionPreset.MEDIUM, "Medium", "1080p - 30fps"),
        Triple(VideoCompressionPreset.LOW, "Low", "720p - 30fps"),
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
        shape = RoundedCornerShape(20.dp),
      ) {
        Row(
          modifier = Modifier.padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (state.activePreset == preset) {
            Icon(Icons.Default.Check, contentDescription = null)
          }
        }
      }
    }

    val sizePresets =
      listOf(
        10f to "Discord / GitHub",
        25f to "Email",
        50f to "Stories",
        100f to "Messenger / Bluesky",
        500f to "Nitro / Reels",
        512f to "Twitter / X",
        2048f to "WhatsApp / Telegram",
        4096f to "TG Premium / Feed",
        8192f to "X Premium",
      ).filter { it.first < (state.originalSize.toFloat() / (1024f * 1024f)) }

    if (sizePresets.isNotEmpty()) {
      Text("Target Size Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        sizePresets.forEach { (size, label) ->
          FilterChip(
            selected = state.targetSizeMb == size,
            onClick = { onSetTargetSize(size) },
            label = {
              Text(
                text = buildString {
                  if (size >= 1024) {
                    append("${(size / 1024).toInt()} GB")
                  } else {
                    append("${size.toInt()} MB")
                  }
                  append(" - ")
                  append(label)
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
    Text("Advanced Options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

    Text("Target Size", style = MaterialTheme.typography.labelLarge)
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

    Text("Encoding", style = MaterialTheme.typography.labelLarge)
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (state.supportedCodecs.contains(androidx.media3.common.MimeTypes.VIDEO_AV1)) {
        FilterChip(
          selected = state.videoCodec == androidx.media3.common.MimeTypes.VIDEO_AV1,
          onClick = { onSetVideoCodec(androidx.media3.common.MimeTypes.VIDEO_AV1) },
          label = { Text("AV1") },
        )
      }
      if (state.supportedCodecs.contains(androidx.media3.common.MimeTypes.VIDEO_H265)) {
        FilterChip(
          selected = state.videoCodec == androidx.media3.common.MimeTypes.VIDEO_H265,
          onClick = { onSetVideoCodec(androidx.media3.common.MimeTypes.VIDEO_H265) },
          label = { Text("H.265") },
        )
      }
      FilterChip(
        selected = state.videoCodec == androidx.media3.common.MimeTypes.VIDEO_H264,
        onClick = { onSetVideoCodec(androidx.media3.common.MimeTypes.VIDEO_H264) },
        label = { Text("H.264") },
      )
    }

    Text("Resolution", style = MaterialTheme.typography.labelLarge)
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
          listOf(2160, 1440, 1080, 720, 540, 480, (originalShortSide * 3) / 4, originalShortSide / 2, originalShortSide / 4)
            .filter { it > 0 && it < originalShortSide }
            .distinct()
            .forEach { add(it to "${it}p") }
        }
      options.forEach { (value, label) ->
        FilterChip(
          selected = currentShortSide == value || (label == "Original" && state.targetResolutionHeight == state.originalHeight),
          onClick = { onSetResolution(value) },
          label = { Text(label) },
        )
      }
    }

    Text("Framerate", style = MaterialTheme.typography.labelLarge)
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = state.targetFps == 0,
        onClick = { onSetFps(0) },
        label = { Text("Original - ${state.originalFps.toInt()}") },
      )
      FilterChip(
        selected = state.targetFps == 60,
        enabled = state.originalFps >= 50f,
        onClick = { onSetFps(60) },
        label = { Text("60fps") },
      )
      FilterChip(
        selected = state.targetFps == 30,
        onClick = { onSetFps(30) },
        label = { Text("30fps") },
      )
      FilterChip(
        selected = state.targetFps == 24,
        onClick = { onSetFps(24) },
        label = { Text("24fps") },
      )
    }
  }
}

@Composable
private fun CompressorAudioTab(
  state: VideoCompressorUiState,
  onToggleRemoveAudio: () -> Unit,
  onSetAudioBitrate: (Int) -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text("Audio Options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("Remove audio", style = MaterialTheme.typography.bodyLarge)
      Switch(checked = state.removeAudio, onCheckedChange = { onToggleRemoveAudio() })
    }

    AnimatedVisibility(visible = !state.removeAudio) {
      Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Audio Bitrate", style = MaterialTheme.typography.labelLarge)
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
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)),
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
        shape = RoundedCornerShape(24.dp),
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Text("COMPRESSING VIDEO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        shape = RoundedCornerShape(18.dp),
      ) {
        Text("Cancel")
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
        title = { Text("Compressor", fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close")
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
          shape = RoundedCornerShape(999.dp),
        ) {
          Icon(
            Icons.Default.Check,
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
            Text("-$reduction%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
          }
        }

        ElevatedCard(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text("Saved to", fontWeight = FontWeight.SemiBold)
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
              Text("Warnings", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
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
              shape = RoundedCornerShape(18.dp),
            ) {
              Icon(Icons.Default.Share, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text("Share")
            }
            FilledTonalButton(
              onClick = onSave,
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(18.dp),
            ) {
              Text("Save copy")
            }
          }
        }

        TextButton(onClick = onClose) {
          Text("Back to list")
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
        title = { Text("Compressor", fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close")
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
        shape = RoundedCornerShape(24.dp),
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
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
              Text("Close")
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
        Text("Device and App Info", style = MaterialTheme.typography.titleLarge)
        Text("Compressor v${state.appInfoVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Text("Android: ${Build.VERSION.RELEASE}")
        HorizontalDivider()
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Show bitrate")
          Switch(checked = state.showBitrate, onCheckedChange = { onToggleShowBitrate() })
        }
        if (state.showBitrate) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("Use Mbps")
            Switch(checked = state.useMbps, onCheckedChange = { onToggleBitrateUnit() })
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Preserve metadata")
          Switch(checked = state.preserveMetadata, onCheckedChange = { onTogglePreserveMetadata() })
        }
        HorizontalDivider()
        Text("Supported Codecs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        state.supportedCodecs.forEach {
          Text("- ${it.substringAfter("/")}", style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Row {
        TextButton(onClick = onShare) { Text("Share") }
        TextButton(onClick = onCopy) { Text("Copy") }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Close")
      }
    },
  )
}

private fun Bitmap.toSafeImageBitmap(): ImageBitmap = asImageBitmap()
