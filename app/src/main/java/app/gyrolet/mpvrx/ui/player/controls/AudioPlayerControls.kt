/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.palette.graphics.Palette
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.repository.JellyfinRepository
import app.gyrolet.mpvrx.repository.NavidromeRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.browser.dialogs.AddToPlaylistDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.toPlaylistCandidates
import app.gyrolet.mpvrx.ui.player.resolveUri
import app.gyrolet.mpvrx.ui.player.controls.components.MiniAudioVisualizer
import app.gyrolet.mpvrx.ui.player.controls.components.rememberSmoothedPositionMs
import app.gyrolet.mpvrx.ui.player.controls.components.AnimatedLyricWord
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.PlaylistItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import app.gyrolet.mpvrx.ui.utils.ReorderFeedback
import app.gyrolet.mpvrx.ui.utils.dragElevation
import app.gyrolet.mpvrx.ui.utils.rememberReorderFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.AudioVisualizerStyle
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.LyricsTranslationDisplayMode
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.RepeatMode
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.controls.components.AbLoopIcon
import app.gyrolet.mpvrx.ui.player.controls.components.SeekbarWithTimers
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.visualizer.AudioFeatures
import app.gyrolet.mpvrx.ui.player.visualizer.AudioSpectrumAnalyzer
import app.gyrolet.mpvrx.ui.player.visualizer.BlobOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.CuboidOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.GalaxyOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.ParticleOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.VisualizerPalette
import app.gyrolet.mpvrx.ui.player.visualizer.rememberAudioVisualizerFeatures
import app.gyrolet.mpvrx.ui.theme.fontFamilyForText
import app.gyrolet.mpvrx.ui.utils.isMpvOptionOwnedByConfig
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics

import app.gyrolet.mpvrx.utils.media.fileExtension
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private data class AudioPresentationMetadata(
  val artwork: Bitmap?,
  val artist: String?,
)

/**
 * One bounded native-metadata pipeline for the current song, swipe previews, and queue rows.
 *
 * Previously each consumer created its own MediaMetadataRetriever (and the current track created a
 * second retriever just for artist fallback), so entering Now Playing could parse the same file
 * several times and hold several native retrievers concurrently. The cache is byte-sized by
 * artwork memory, metadata-only/missing-art entries still consume a small fixed weight, and the
 * mutex intentionally serializes cache misses to cap native decoder/retriever pressure.
 */
private object AudioPresentationMetadataCache {
  private const val MAX_CACHE_KB = 16 * 1024
  private const val METADATA_ONLY_WEIGHT_KB = 64
  private val loadMutex = Mutex()
  private val cache =
    object : LruCache<String, AudioPresentationMetadata>(MAX_CACHE_KB) {
      override fun sizeOf(
        key: String,
        value: AudioPresentationMetadata,
      ): Int =
        maxOf(
          METADATA_ONLY_WEIGHT_KB,
          (value.artwork?.byteCount ?: 0) / 1024,
        )
    }

  private fun cacheKey(
    pathOrUri: String,
    artworkUri: String?,
  ): String = "$pathOrUri\u0000${artworkUri.orEmpty()}"

  fun peek(
    pathOrUri: String?,
    artworkUri: String?,
  ): AudioPresentationMetadata? {
    if (pathOrUri.isNullOrBlank()) return null
    return synchronized(cache) { cache.get(cacheKey(pathOrUri, artworkUri)) }
  }

  suspend fun resolve(
    context: android.content.Context,
    pathOrUri: String,
    artworkUri: String?,
  ): AudioPresentationMetadata =
    withContext(Dispatchers.IO) {
      val key = cacheKey(pathOrUri, artworkUri)
      synchronized(cache) { cache.get(key) }?.let { return@withContext it }

      loadMutex.withLock {
        synchronized(cache) { cache.get(key) }?.let { return@withLock it }

        val cleanPath =
          when {
            pathOrUri.startsWith("file://") -> Uri.parse(pathOrUri).path ?: pathOrUri.removePrefix("file://")
            pathOrUri.startsWith("content://") -> {
              val uri = Uri.parse(pathOrUri)
              uri.resolveUri(context, allowFdFallback = false)
            }
            else -> pathOrUri
          }
        val explicitArtwork = EmbeddedArtworkResolver.decodeArtworkUri(context, artworkUri)
        val isNetworkStream = pathOrUri.startsWith("http://", ignoreCase = true) || pathOrUri.startsWith("https://", ignoreCase = true)
        val retriever = if (!isNetworkStream) MediaMetadataRetriever() else null
        val loaded =
          try {
            if (retriever != null) {
              if (cleanPath != null && java.io.File(cleanPath).canRead()) {
                retriever.setDataSource(cleanPath)
              } else if (pathOrUri.startsWith("content://")) {
                val uri = Uri.parse(pathOrUri)
                try {
                  context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                  }
                } catch (_: Exception) {
                  retriever.setDataSource(context, uri)
                }
              } else {
                retriever.setDataSource(context, Uri.parse(pathOrUri))
              }
            }

            AudioPresentationMetadata(
              artwork = explicitArtwork ?: retriever?.let { EmbeddedArtworkResolver.decodeEmbeddedArtwork(cleanPath ?: pathOrUri, it) },
              artist = retriever?.let {
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                  ?: it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                  ?: it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                  ?: it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
              },
            )
          } catch (_: Exception) {
            val fallbackArtwork = explicitArtwork ?: if (cleanPath != null && !isNetworkStream) {
              EmbeddedArtworkResolver.decodeSidecar(cleanPath)
            } else null
            AudioPresentationMetadata(artwork = fallbackArtwork, artist = null)
          } finally {
            runCatching { retriever?.release() }
          }

        synchronized(cache) { cache.put(key, loaded) }
        loaded
      }
    }
}

@Composable
private fun rememberAudioPresentationMetadata(
  pathOrUri: String?,
  artworkUri: String? = null,
): AudioPresentationMetadata? {
  val context = LocalContext.current
  var metadata by remember(pathOrUri, artworkUri) {
    mutableStateOf(AudioPresentationMetadataCache.peek(pathOrUri, artworkUri))
  }
  LaunchedEffect(pathOrUri, artworkUri) {
    metadata =
      if (pathOrUri.isNullOrBlank()) {
        null
      } else {
        AudioPresentationMetadataCache.resolve(context, pathOrUri, artworkUri)
      }
  }
  return metadata
}

@Composable
private fun rememberAudioAlbumArt(
  pathOrUri: String?,
  artworkUri: String? = null,
): Bitmap? = rememberAudioPresentationMetadata(pathOrUri, artworkUri)?.artwork

private fun contrastingVisualizerTone(
  colorArgb: Int,
  backgrounds: List<Int>,
): Int {
  val hsl = FloatArray(3)
  ColorUtils.colorToHSL(colorArgb, hsl)
  if (hsl[1] >= 0.12f) hsl[1] = hsl[1].coerceIn(0.45f, 0.85f)
  val originalLightness = hsl[2].coerceIn(0.18f, 0.82f)
  val candidates =
    (0..32).map { step ->
      val lightness = 0.18f + step * 0.02f
      hsl[2] = lightness
      val color = ColorUtils.HSLToColor(hsl)
      val contrast =
        backgrounds.minOf { background ->
          ColorUtils.calculateContrast(
            ColorUtils.compositeColors(ColorUtils.setAlphaComponent(color, 220), background),
            background,
          )
        }
      Triple(color, contrast, abs(lightness - originalLightness))
    }
  return candidates.filter { it.second >= 3.0 }.minByOrNull { it.third }?.first
    ?: candidates.maxBy { it.second }.first
}

private fun VisualizerPalette.withThemeContrast(backgrounds: List<Int>): VisualizerPalette =
  copy(
    primary = contrastingVisualizerTone(primary, backgrounds),
    secondary = contrastingVisualizerTone(secondary, backgrounds),
    tertiary = contrastingVisualizerTone(tertiary, backgrounds),
  )

private fun artworkVisualizerPalette(
  bitmap: Bitmap,
  materialPalette: VisualizerPalette,
): VisualizerPalette {
  val extracted = Palette.from(bitmap).maximumColorCount(20).clearFilters().generate()
  val maximumPopulation = extracted.swatches.maxOfOrNull { it.population }?.coerceAtLeast(1) ?: 1
  val rankedSwatches =
    extracted.swatches
      .filter { it.hsl[1] >= 0.18f && it.hsl[2] in 0.12f..0.88f }
      .ifEmpty { extracted.swatches.filter { it.hsl[2] in 0.08f..0.92f }.ifEmpty { extracted.swatches } }
      .sortedByDescending { swatch ->
        val population = swatch.population.toFloat() / maximumPopulation
        val centeredLightness = 1f - abs(swatch.hsl[2] - 0.52f)
        swatch.hsl[1] * 0.58f + population * 0.27f + centeredLightness * 0.15f
      }
  val accents = mutableListOf<Palette.Swatch>()
  for (swatch in rankedSwatches) {
    val similar =
      accents.any { existing ->
        val hueDistance = abs(existing.hsl[0] - swatch.hsl[0])
        minOf(hueDistance, 360f - hueDistance) < 30f &&
          abs(existing.hsl[1] - swatch.hsl[1]) < 0.25f &&
          abs(existing.hsl[2] - swatch.hsl[2]) < 0.18f
      }
    if (!similar) accents += swatch
    if (accents.size == 3) break
  }
  val primary = accents.firstOrNull()?.rgb ?: materialPalette.primary
  val secondary = accents.getOrNull(1)?.rgb ?: primary
  return materialPalette.copy(
    primary = primary,
    secondary = secondary,
    tertiary = accents.getOrNull(2)?.rgb ?: secondary,
  )
}

@Composable
private fun Modifier.audioPlaybackGestures(viewModel: PlayerViewModel, onTap: () -> Unit, onHold: () -> Unit): Modifier {
  val tap by rememberUpdatedState(onTap)
  val hold by rememberUpdatedState(onHold)
  return this
    .semantics {
      onClick { tap(); true }
      onLongClick { hold(); true }
    }
    .pointerInput(viewModel) {
      detectTapGestures(
        onTap = { tap() },
        onLongPress = { hold() },
        onDoubleTap = { position ->
          when {
            position.x < size.width / 3f -> viewModel.handleLeftDoubleTap()
            position.x > size.width * 2f / 3f -> viewModel.handleRightDoubleTap()
            else -> viewModel.handleCenterDoubleTap()
          }
        },
      )
    }
}

@Composable
private fun AudioVisualizerViewport(
  viewModel: PlayerViewModel,
  style: AudioVisualizerStyle,
  palette: VisualizerPalette,
  isPlaying: Boolean,
  isSheetOpen: Boolean,
  features: AudioFeatures,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier.audioPlaybackGestures(viewModel, onClick, onLongClick),
    contentAlignment = Alignment.Center,
  ) {
    val rendererModifier =
      Modifier
        .fillMaxSize()
        .then(
          if (style == AudioVisualizerStyle.Cuboid) {
            Modifier
              .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
              .drawWithCache {
                val bottomFade =
                  Brush.verticalGradient(
                    0f to Color.White,
                    0.78f to Color.White,
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = size.height,
                  )
                onDrawWithContent {
                  drawContent()
                  drawRect(brush = bottomFade, blendMode = BlendMode.DstIn)
                }
              }
          } else {
            Modifier
          },
        )

    when (style) {
      AudioVisualizerStyle.Galaxy ->
        GalaxyOverlay(
          palette = palette,
          isSheetOpen = isSheetOpen,
          features = features,
          modifier = rendererModifier,
        )
      AudioVisualizerStyle.Blob ->
        BlobOverlay(
          palette = palette,
          isSheetOpen = isSheetOpen,
          features = features,
          modifier = rendererModifier,
        )
      AudioVisualizerStyle.Cuboid ->
        CuboidOverlay(
          isPlaying = isPlaying,
          palette = palette,
          isSheetOpen = isSheetOpen,
          features = features,
          modifier = rendererModifier,
        )
      AudioVisualizerStyle.Particle ->
        ParticleOverlay(
          palette = palette,
          isSheetOpen = isSheetOpen,
          features = features,
          modifier = rendererModifier,
        )
    }

  }
}

/** One capture owner feeds the main visualizer and seekbar ribbon. */
@Composable
private fun AudioSpectrumCaptureEffect(
  enabled: Boolean,
  features: AudioFeatures,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val analyzerActive = remember(features) { AtomicBoolean(false) }
  var hasRecordPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val recordPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasRecordPermission = granted
    }

  LaunchedEffect(enabled, hasRecordPermission) {
    if (enabled && !hasRecordPermission) {
      recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  DisposableEffect(enabled, hasRecordPermission, features) {
    val analyzer = if (enabled && hasRecordPermission) AudioSpectrumAnalyzer(features) else null
    val job =
      scope.launch(Dispatchers.Default) {
        while (isActive && analyzer != null) {
          val captureFresh = features.active && features.hasRecentCapture(1_500_000_000L)
          if (!analyzerActive.get() || !captureFresh) {
            analyzerActive.set(analyzer.start(0).isSuccess)
          }
          kotlinx.coroutines.delay(if (analyzerActive.get()) 1_500L else 400L)
        }
      }
    onDispose {
      job.cancel()
      analyzerActive.set(false)
      analyzer?.stop(resetFeatures = false)
    }
  }
}

@Composable
private fun CoverArtCardImage(
  bitmap: Bitmap?,
  artworkUrl: String? = null,
  contentScale: ContentScale = ContentScale.Crop,
) {
  val context = LocalContext.current
  val client = org.koin.compose.koinInject<okhttp3.OkHttpClient>()
  var remoteBitmap by remember(artworkUrl) {
    mutableStateOf<Bitmap?>(
      artworkUrl?.let { app.gyrolet.mpvrx.presentation.components.RemoteImageLoader.getFromMemory(it) }
    )
  }

  LaunchedEffect(artworkUrl) {
    if (artworkUrl.isNullOrBlank() || (!artworkUrl.startsWith("http://", ignoreCase = true) && !artworkUrl.startsWith("https://", ignoreCase = true))) {
      remoteBitmap = null
      return@LaunchedEffect
    }
    if (remoteBitmap == null) {
      remoteBitmap = withContext(Dispatchers.IO) {
        app.gyrolet.mpvrx.presentation.components.RemoteImageLoader.load(context, client, artworkUrl)
          ?: app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver.decodeArtworkUri(context, artworkUrl)
      }
    }
  }

  val finalBitmap = bitmap ?: remoteBitmap
  val imageBitmap = remember(finalBitmap) { finalBitmap?.asImageBitmap() }

  if (imageBitmap != null) {
    Image(
      bitmap = imageBitmap,
      contentDescription = null,
      contentScale = contentScale,
      modifier = Modifier.fillMaxSize(),
    )
  } else {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Audiotrack,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(64.dp),
      )
    }
  }
}

private enum class CoverSwipeDirection {
  NEXT,
  PREV,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerControls(
  viewModel: PlayerViewModel,
  mediaTitle: String?,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  modifier: Modifier = Modifier,
) {
  val speedConfigOwned = isMpvOptionOwnedByConfig("speed")
  val audioFiltersConfigOwned = isMpvOptionOwnedByConfig("af")
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val duration by PlaybackSession.propInt["duration"].collectAsState()
  val preciseDuration by viewModel.preciseDuration.collectAsState()
  val playbackState by PlaybackSession.state.collectAsStateWithLifecycle()
  val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentItem = playbackState.currentItem ?: queueState.currentItem
  val activeBook by app.gyrolet.mpvrx.ui.player.AudiobookPlayback.book.collectAsStateWithLifecycle()
  val audiobook = activeBook?.takeIf { it.book.id == currentItem?.audiobook?.bookId }
  val isAudiobook = currentItem?.audiobook != null
  val filePosition = viewModel.precisePosition.collectAsStateWithLifecycle()
  val playlistItems by viewModel.playlistItems.collectAsState()
  val isAudioOnly by viewModel.isAudioOnly.collectAsState()
  val filteredPlaylist =
    remember(playlistItems, isAudioOnly) {
      if (isAudioOnly) playlistItems else playlistItems.filter { it.isAudio }
    }

  var showInPlaceLyrics by rememberSaveable { mutableStateOf(false) }
  var wasLyricsActiveBeforeLandscape by rememberSaveable { mutableStateOf(false) }
  var tabletDualPaneTab by rememberSaveable { mutableIntStateOf(0) }
  var isLyricsFullscreen by remember { mutableStateOf(false) }
  var lastUserInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

  val resetInactivityTimer = {
    lastUserInteractionTime = System.currentTimeMillis()
    if (isLyricsFullscreen) {
      isLyricsFullscreen = false
    }
  }

  BackHandler(enabled = isLyricsFullscreen || showInPlaceLyrics) {
    if (isLyricsFullscreen) {
      resetInactivityTimer()
    } else if (showInPlaceLyrics) {
      showInPlaceLyrics = false
    }
  }

  val currentPath by PlaybackSession.propString["path"].collectAsState()
  val currentStreamFilename by PlaybackSession.propString["stream-open-filename"].collectAsState()
  val mediaPath = currentPath?.takeIf { it.isNotBlank() } ?: currentStreamFilename
  val currentMediaSource =
    currentItem?.originalUri?.takeIf { it.isNotBlank() }
      ?: currentItem?.playableUri?.takeIf { it.isNotBlank() }
      ?: mediaPath

  val audioCodec by PlaybackSession.propString["audio-codec-name"].collectAsState()
  val sampleRate by PlaybackSession.propInt["audio-params/samplerate"].collectAsState()
  val audioFormat by PlaybackSession.propString["audio-params/format"].collectAsState()
  val bitsPerSample by PlaybackSession.propString["metadata/by-key/BITS_PER_SAMPLE"].collectAsState()
  val bitsPerSampleAlt by PlaybackSession.propString["metadata/by-key/bits_per_sample"].collectAsState()
  val audioBitrateProp by PlaybackSession.propInt["audio-bitrate"].collectAsState()
  val generalBitrateProp by PlaybackSession.propInt["bitrate"].collectAsState()
  val playbackSpeed by PlaybackSession.propFloat["speed"].collectAsState()

  val cleanCodecName =
    remember(audioCodec, mediaPath) {
      val codec = audioCodec?.lowercase().orEmpty()
      val rawExt = mediaPath?.fileExtension().orEmpty().lowercase()
      val ext = if (rawExt in app.gyrolet.mpvrx.utils.storage.FileTypeUtils.AUDIO_EXTENSIONS) rawExt.uppercase() else ""
      when {
        codec.contains("flac") -> "FLAC"
        codec.contains("alac") -> "ALAC"
        codec.contains("mp3") -> "MP3"
        codec.contains("aac") -> "AAC"
        codec.contains("opus") -> "OPUS"
        codec.contains("vorbis") -> "VORBIS"
        codec.contains("wavpack") -> "WAVPACK"
        codec.contains("ape") -> "APE"
        codec.contains("dsd") -> "DSD"
        codec.contains("pcm") -> if (ext in setOf("WAV", "AIFF", "AIF")) ext else "PCM"
        codec.contains("wma") -> "WMA"
        codec.isNotBlank() && codec.length <= 10 && !codec.contains("/") && !codec.contains(":") -> {
          codec.replace("float", "").replace("_latm", "").uppercase().trim()
        }
        ext.isNotBlank() -> ext
        else -> ""
      }
    }

  val isLosslessCodecOrExt =
    remember(audioCodec, mediaPath) {
      val codec = audioCodec?.lowercase().orEmpty()
      val rawExt = mediaPath?.fileExtension().orEmpty().lowercase()
      val ext = if (rawExt in app.gyrolet.mpvrx.utils.storage.FileTypeUtils.AUDIO_EXTENSIONS) rawExt else ""
      codec.contains("flac") ||
        codec.contains("alac") ||
        codec.contains("pcm") ||
        codec.contains("wavpack") ||
        codec.contains("ape") ||
        codec.contains("dsd") ||
        codec.contains("tak") ||
        ext in setOf("flac", "wav", "aiff", "aif", "alac", "ape", "dsf", "dff")
    }

  val isHiRes =
    remember(sampleRate, isLosslessCodecOrExt) {
      isLosslessCodecOrExt && (sampleRate ?: 0) >= 88200
    }

  var showLosslessDetails by remember { mutableStateOf(false) }

  LaunchedEffect(currentItem?.stableId, mediaPath) {
    showLosslessDetails = false
  }

  val collapsedAudioBadgeLabel =
    remember(isHiRes, isLosslessCodecOrExt, cleanCodecName) {
      when {
        isHiRes -> "HI-RES LOSSLESS"
        isLosslessCodecOrExt -> "LOSSLESS"
        cleanCodecName.isNotBlank() -> cleanCodecName
        else -> ""
      }
    }

  val fullLosslessDetailString =
    remember(
      isHiRes,
      isLosslessCodecOrExt,
      sampleRate,
      audioFormat,
      bitsPerSample,
      bitsPerSampleAlt,
      audioBitrateProp,
      generalBitrateProp,
      cleanCodecName,
    ) {
      val sr = sampleRate ?: 0
      val khzStr =
        if (sr > 0) {
          val khz = sr / 1000f
          if (sr % 1000 == 0) "${sr / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", khz)
        } else {
          ""
        }

      val bitrateInt = (audioBitrateProp?.takeIf { it > 0 } ?: generalBitrateProp?.takeIf { it > 0 } ?: 0)
      val kbpsStr = if (bitrateInt > 0) "${bitrateInt / 1000} kbps" else ""

      val bps = bitsPerSample?.takeIf { it.isNotBlank() } ?: bitsPerSampleAlt?.takeIf { it.isNotBlank() }
      val bitStr =
        when {
          !bps.isNullOrBlank() && bps.toIntOrNull() != null -> "${bps.toInt()}-bit"
          audioFormat?.contains("24") == true || audioFormat == "s24" || audioFormat == "s24p" -> "24-bit"
          audioFormat?.contains("16") == true || audioFormat == "s16" || audioFormat == "s16p" -> "16-bit"
          audioFormat?.contains("32") == true || audioFormat == "s32" || audioFormat == "s32p" || audioFormat == "flt" || audioFormat == "fltp" -> "32-bit"
          audioFormat?.contains("8") == true || audioFormat == "u8" -> "8-bit"
          isHiRes -> "24-bit"
          isLosslessCodecOrExt -> "16-bit"
          else -> ""
        }

      if (isLosslessCodecOrExt) {
        val baseLabel = if (isHiRes) "HI-RES LOSSLESS" else "LOSSLESS"
        val specsCore =
          when {
            bitStr.isNotBlank() && khzStr.isNotBlank() -> "$bitStr/$khzStr"
            khzStr.isNotBlank() -> khzStr
            bitStr.isNotBlank() -> bitStr
            else -> ""
          }
        buildString {
          append(baseLabel)
          if (specsCore.isNotBlank()) {
            append(" - ").append(specsCore)
          }
          if (cleanCodecName.isNotBlank()) {
            append(" ").append(cleanCodecName)
          }
        }
      } else {
        val baseLabel = cleanCodecName.ifBlank { "AUDIO" }
        val specs =
          when {
            kbpsStr.isNotBlank() && khzStr.isNotBlank() -> "$kbpsStr/$khzStr"
            kbpsStr.isNotBlank() -> kbpsStr
            khzStr.isNotBlank() -> khzStr
            else -> ""
          }
        if (specs.isNotBlank()) {
          "$baseLabel - $specs"
        } else {
          baseLabel
        }
      }
    }

  val currentArtworkUri =
    audiobook?.book?.coverUri?.takeIf(String::isNotBlank)
      ?: currentItem?.artworkUri?.takeIf { it.isNotBlank() }
      ?: filteredPlaylist.firstOrNull { it.isPlaying || it.path == mediaPath || it.uri.toString() == mediaPath }?.tvgLogo?.takeIf { it.isNotBlank() }

  val currentAudioPresentation =
    rememberAudioPresentationMetadata(
      pathOrUri = currentMediaSource?.takeIf { isAudiobook } ?: mediaPath?.takeIf { it.isNotBlank() } ?: currentMediaSource,
      artworkUri = currentArtworkUri,
    )
  val albumArtBitmap = currentAudioPresentation?.artwork

  fun cleanSongTitle(
    title: String,
    artist: String?,
  ): String {
    val titleWithoutExt = title.stripAudioExtension()
    if (!artist.isNullOrBlank() && artist != "Unknown Artist") {
      val prefixes = listOf("$artist - ", "$artist – ", "$artist — ", "$artist- ", "$artist : ")
      for (prefix in prefixes) {
        if (titleWithoutExt.startsWith(prefix, ignoreCase = true)) {
          return titleWithoutExt.substring(prefix.length).trim()
        }
      }
      val suffixes = listOf(" - $artist", " – $artist", " — $artist", " -$artist")
      for (suffix in suffixes) {
        if (titleWithoutExt.endsWith(suffix, ignoreCase = true)) {
          return titleWithoutExt.substring(0, titleWithoutExt.length - suffix.length).trim()
        }
      }
    }
    return titleWithoutExt
  }

  var lastValidTitle by remember {
    mutableStateOf(
      currentItem?.title?.takeIf { it.isNotBlank() }?.stripAudioExtension()
        ?: mediaTitle?.takeIf { it.isNotBlank() }?.stripAudioExtension()
        ?: "Audio Track",
    )
  }
  LaunchedEffect(currentItem?.stableId, currentItem?.title, mediaTitle) {
    val updatedTitle = currentItem?.title?.takeIf { it.isNotBlank() } ?: mediaTitle
    if (!updatedTitle.isNullOrBlank()) {
      lastValidTitle = updatedTitle.stripAudioExtension()
    }
  }

  val context = LocalContext.current
  val rawArtist by PlaybackSession.propString["metadata/by-key/Artist"].collectAsState()
  val rawArtistAlt by PlaybackSession.propString["metadata/artist"].collectAsState()
  val rawAlbumArtist by PlaybackSession.propString["metadata/by-key/album_artist"].collectAsState()
  val rawPerformer by PlaybackSession.propString["metadata/by-key/PERFORMER"].collectAsState()
  val retrievedArtist = currentAudioPresentation?.artist

  val displayArtist =
    remember(audiobook?.book?.author, currentItem?.artist, rawArtist, rawArtistAlt, rawAlbumArtist, rawPerformer, retrievedArtist) {
      sequenceOf(audiobook?.book?.author, currentItem?.artist, rawArtist, rawArtistAlt, rawAlbumArtist, rawPerformer, retrievedArtist)
        .filterNotNull()
        .firstOrNull { it.isNotBlank() } ?: "Unknown Artist"
    }

  val audioPreferences = koinInject<AudioPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val audioVisualizerStyle by audioPreferences.audioVisualizerStyle.collectAsState()
  val audioWavySeekbar by audioPreferences.audioWavySeekbar.collectAsState()
  val backgroundPlaybackEnabled by audioPreferences.audioBackgroundPlayback.collectAsState()
  val colorScheme = MaterialTheme.colorScheme
  val palette =
    remember(colorScheme) {
      VisualizerPalette(
        background = colorScheme.surface.toArgb(),
        primary = colorScheme.primary.toArgb(),
        secondary = colorScheme.secondary.toArgb(),
        tertiary = colorScheme.tertiary.toArgb(),
      )
    }
  val artworkPalette by produceState(
    initialValue = palette,
    key1 = albumArtBitmap,
    key2 = palette,
  ) {
    val artwork = albumArtBitmap
    value = palette
    value =
      if (artwork == null) {
        palette
      } else {
        withContext(Dispatchers.Default) {
          runCatching { artworkVisualizerPalette(artwork, palette) }.getOrDefault(palette)
        }
      }
  }

   val isPlaying = paused == false
   val currentDurSec =
     if (preciseDuration > 0f) {
       preciseDuration
     } else if ((duration ?: 0) > 0) {
       duration!!.toFloat()
     } else {
       currentItem?.durationSeconds?.takeIf { it > 0 }?.toFloat() ?: 0f
     }
   val currentVolumePercent by viewModel.currentVolumePercent.collectAsState()
   val volumeScale = currentVolumePercent / 100f
   val visualizerFeatures = rememberAudioVisualizerFeatures(isPlaying, volumeScale)

  // Hardware buttons, Bluetooth devices and system panels can change STREAM_MUSIC without
  // going through the player's volume callbacks. Keep the visualizer's gain synchronized.
  LaunchedEffect(viewModel, isPlaying) {
    viewModel.syncCurrentVolumeState()
    while (isPlaying && isActive) {
      kotlinx.coroutines.delay(200L)
      viewModel.syncCurrentVolumeState()
    }
  }

  val repeatMode by viewModel.repeatMode.collectAsState()
  val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
  val playlistModeEnabled = viewModel.hasPlaylistSupport()
  val showVisualizer by viewModel.showVisualizerInAudioPlayer.collectAsState()
  val sheetShown by viewModel.sheetShown.collectAsState()
  val isSheetOpen = sheetShown != Sheets.None

  AudioSpectrumCaptureEffect(
    enabled =
      isPlaying &&
        !isSheetOpen &&
        ((showVisualizer && !showInPlaceLyrics) ||
          (audioWavySeekbar && !isLyricsFullscreen)),
    features = visualizerFeatures,
  )

  val abLoop by viewModel.abLoopState.collectAsState()
  val abLoopA = abLoop.a
  val abLoopB = abLoop.b

  var addToPlaylistDialogOpen by rememberSaveable { mutableStateOf(false) }

  val playerPreferences = koinInject<PlayerPreferences>()
  val actionHaptics = app.gyrolet.mpvrx.ui.utils.rememberAppHaptics()
  val playlistRepository = koinInject<PlaylistRepository>()
  val jellyfinRepository = koinInject<JellyfinRepository>()
  val jellyfinServers by jellyfinRepository.allServers.collectAsState(initial = emptyList())
  val navidromeRepository = koinInject<NavidromeRepository>()
  val navidromeServers by navidromeRepository.allServers.collectAsState(initial = emptyList())
  val coroutineScope = rememberCoroutineScope()
  val activeTrackPath = mediaPath?.takeIf { it.isNotBlank() } ?: currentMediaSource

  val jellyfinInfo = remember(activeTrackPath, mediaPath) {
    val path = mediaPath?.takeIf { it.isNotBlank() } ?: activeTrackPath
    if (path.isNullOrBlank()) null
    else {
      val uri = runCatching { Uri.parse(path) }.getOrNull()
      if (uri == null) null
      else {
        val pathSegments = uri.pathSegments
        val mediaIndex = pathSegments.indexOfFirst {
          it.equals("Videos", ignoreCase = true) ||
            it.equals("Audio", ignoreCase = true) ||
            it.equals("Items", ignoreCase = true)
        }
        if (mediaIndex != -1 && mediaIndex + 1 < pathSegments.size) {
          val itemId = pathSegments[mediaIndex + 1]
          val apiKey = uri.getQueryParameter("api_key") ?: uri.getQueryParameter("ApiKey")
          val scheme = uri.scheme ?: "http"
          val authority = uri.encodedAuthority
          val subPathSegments = pathSegments.subList(0, mediaIndex)
          val baseUrl = if (authority != null) {
            if (subPathSegments.isEmpty()) "$scheme://$authority"
            else "$scheme://$authority/" + subPathSegments.joinToString("/")
          } else null
          if (itemId.isNotBlank() && baseUrl != null) {
            Triple(baseUrl, itemId, apiKey)
          } else null
        } else null
      }
    }
  }

  val activeJellyfinServer = remember(jellyfinServers, jellyfinInfo) {
    if (jellyfinInfo == null) null
    else {
      jellyfinServers.firstOrNull { s ->
        s.serverUrl.contains(runCatching { Uri.parse(jellyfinInfo.first).host.orEmpty() }.getOrDefault("")) ||
          (!jellyfinInfo.third.isNullOrBlank() && s.accessToken == jellyfinInfo.third)
      } ?: jellyfinServers.firstOrNull()
    }
  }

  var jellyfinFavoriteOverride by remember(activeTrackPath, mediaPath) { mutableStateOf<Boolean?>(null) }

  LaunchedEffect(activeJellyfinServer, jellyfinInfo?.second) {
    val server = activeJellyfinServer
    val itemId = jellyfinInfo?.second
    if (server != null && !itemId.isNullOrBlank()) {
      val item = withContext(Dispatchers.IO) {
        jellyfinRepository.getItem(server, itemId).getOrNull()
      }
      if (item != null) {
        jellyfinFavoriteOverride = item.isFavorite
      }
    }
  }

  val navidromeInfo = remember(activeTrackPath, mediaPath, currentMediaSource) {
    val path = mediaPath?.takeIf { it.isNotBlank() } ?: activeTrackPath ?: currentMediaSource
    if (path.isNullOrBlank()) null
    else {
      val uri = runCatching { Uri.parse(path) }.getOrNull()
      if (uri == null) null
      else {
        if (uri.path?.contains("/rest/stream", ignoreCase = true) == true ||
            uri.path?.contains("stream.view", ignoreCase = true) == true ||
            uri.query?.contains("stream.view", ignoreCase = true) == true) {
          val songId = uri.getQueryParameter("id")
          val username = uri.getQueryParameter("u")
          val scheme = uri.scheme ?: "http"
          val authority = uri.encodedAuthority
          val baseUrl = if (authority != null) "$scheme://$authority" else null
          if (!songId.isNullOrBlank()) {
            Triple(baseUrl, songId, username)
          } else null
        } else null
      }
    }
  }

  val activeNavidromeServer = remember(navidromeServers, navidromeInfo) {
    if (navidromeInfo == null) null
    else {
      navidromeServers.firstOrNull { s ->
        s.serverUrl.contains(runCatching { Uri.parse(navidromeInfo.first.orEmpty()).host.orEmpty() }.getOrDefault("")) ||
          (!navidromeInfo.third.isNullOrBlank() && s.username == navidromeInfo.third)
      } ?: navidromeServers.firstOrNull()
    }
  }

  var navidromeFavoriteOverride by remember(activeTrackPath, mediaPath) { mutableStateOf<Boolean?>(null) }

  LaunchedEffect(activeNavidromeServer, navidromeInfo?.second) {
    val server = activeNavidromeServer
    val songId = navidromeInfo?.second
    if (server != null && !songId.isNullOrBlank()) {
      val song = withContext(Dispatchers.IO) {
        navidromeRepository.getSong(server, songId).getOrNull()
      }
      if (song != null) {
        navidromeFavoriteOverride = song.isFavorite
      }
    }
  }

  val isCurrentTrackFavoriteLocal by remember(activeTrackPath, mediaPath) {
    playlistRepository.observeIsFavorite((mediaPath?.takeIf { it.isNotBlank() } ?: activeTrackPath).orEmpty(), isAudio = true)
  }.collectAsState(initial = false)

  val isCurrentTrackFavorite = jellyfinFavoriteOverride ?: navidromeFavoriteOverride ?: isCurrentTrackFavoriteLocal

  val seekbarStyle by appearancePreferences.seekbarStyle.collectAsState()
  val invertDuration by playerPreferences.invertDuration.collectAsState()
  val showChapterIndicators by playerPreferences.showChapterIndicators.collectAsState()
  val chapters by viewModel.playbackChapters.collectAsState()
  val bookChapterIndex by remember(currentItem?.audiobook, chapters, audiobook?.tracks) {
    derivedStateOf {
      val seconds = currentItem?.audiobook?.let { info -> audiobook?.positionInBook(info.trackId, (filePosition.value * 1000).toLong())?.div(1000f) }
        ?: filePosition.value
      chapters.indexOfLast { it.start <= seconds }
    }
  }
  val seekbarChapters =
    remember(chapters, showChapterIndicators) {
      if (showChapterIndicators) chapters.toImmutableList() else persistentListOf()
    }

  LaunchedEffect(audiobook?.book?.id, audiobook?.tracks) {
    viewModel.refreshPlaylistItems(forceMetadata = !isAudiobook)
  }

  val configuration = LocalConfiguration.current
  val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
  val isTablet = configuration.smallestScreenWidthDp >= 600 || configuration.screenWidthDp >= 600
  val isTabletLandscape = !isPortrait && isTablet
  val isTabletPortrait = isPortrait && isTablet

  LaunchedEffect(isTabletLandscape) {
    if (isTabletLandscape) {
      if (showInPlaceLyrics || wasLyricsActiveBeforeLandscape) {
        tabletDualPaneTab = 1
        wasLyricsActiveBeforeLandscape = true
        showInPlaceLyrics = false
      }
    } else {
      if (wasLyricsActiveBeforeLandscape || tabletDualPaneTab == 1) {
        showInPlaceLyrics = true
        wasLyricsActiveBeforeLandscape = false
      }
    }
  }

  LaunchedEffect(showInPlaceLyrics, isPlaying, isTabletLandscape, lastUserInteractionTime) {
    if (showInPlaceLyrics && !isTabletLandscape && isPlaying) {
      kotlinx.coroutines.delay(5000L)
      isLyricsFullscreen = true
    } else {
      isLyricsFullscreen = false
    }
  }

  val ambientModeEnabled by audioPreferences.audioAmbientMode.collectAsState()

  val ambientColors by produceState<Pair<Color, Color>?>(
    initialValue = null,
    key1 = albumArtBitmap,
    key2 = ambientModeEnabled,
  ) {
    if (!ambientModeEnabled || albumArtBitmap == null) {
      value = null
      return@produceState
    }
    withContext(Dispatchers.Default) {
      runCatching {
        val palette = Palette.from(albumArtBitmap).maximumColorCount(16).generate()
        val vibrant = palette.getVibrantColor(
          palette.getDominantColor(
            palette.getMutedColor(0)
          )
        )
        val darkVibrant = palette.getDarkVibrantColor(
          palette.getDarkMutedColor(vibrant)
        )
        if (vibrant == 0 && darkVibrant == 0) return@runCatching null

        val topColor = Color(if (vibrant != 0) vibrant else darkVibrant).copy(alpha = 0.50f)
        val bottomColor = Color(if (darkVibrant != 0) darkVibrant else vibrant).copy(alpha = 0.30f)
        Pair(topColor, bottomColor)
      }.onSuccess { colors ->
        value = colors
      }.onFailure {
        value = null
      }
    }
  }

  val targetTopColor = if (ambientModeEnabled) (ambientColors?.first ?: Color.Transparent) else Color.Transparent
  val targetBottomColor = if (ambientModeEnabled) (ambientColors?.second ?: Color.Transparent) else Color.Transparent

  val animatedAmbientTop: Color by animateColorAsState(
    targetValue = targetTopColor,
    animationSpec = tween(durationMillis = 800),
    label = "ambient_top_color",
  )

  val animatedAmbientBottom: Color by animateColorAsState(
    targetValue = targetBottomColor,
    animationSpec = tween(durationMillis = 800),
    label = "ambient_bottom_color",
  )
  val visualizerPalette =
    remember(artworkPalette, targetTopColor, targetBottomColor) {
      val surface = artworkPalette.background
      val topBackdrop =
        ColorUtils.compositeColors(
          targetTopColor.copy(alpha = targetTopColor.alpha * 0.65f).toArgb(),
          ColorUtils.compositeColors(targetTopColor.toArgb(), surface),
        )
      val bottomBackdrop =
        ColorUtils.compositeColors(
          targetBottomColor.copy(alpha = targetBottomColor.alpha * 0.35f).toArgb(),
          ColorUtils.compositeColors(targetBottomColor.toArgb(), surface),
        )
      artworkPalette.withThemeContrast(
        listOf(surface, topBackdrop, bottomBackdrop, ColorUtils.blendARGB(topBackdrop, bottomBackdrop, 0.5f)),
      )
    }
  val edgeToEdgeVisualizer = showVisualizer && (!showInPlaceLyrics || isTabletLandscape)
  // In-place lyrics run flush against the screen edges, so the root padding moves onto
  // the header and the footer instead of boxing the lyrics in.
  val flushInPlaceLyrics = showInPlaceLyrics && isPortrait
  val controlsSidePadding = if (edgeToEdgeVisualizer || flushInPlaceLyrics) 16.dp else 0.dp
  // The header keeps the side and top padding the root gives up while the lyrics are flush.
  val headerContentPadding =
    if (flushInPlaceLyrics) Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp) else Modifier
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .drawWithCache {
          if (ambientModeEnabled && (animatedAmbientTop != Color.Transparent || animatedAmbientBottom != Color.Transparent)) {
            val topColor = animatedAmbientTop
            val bottomColor = animatedAmbientBottom
            val radialGradient = Brush.radialGradient(
              colors = listOf(
                topColor,
                bottomColor,
                Color.Transparent,
              ),
              center = Offset(size.width * 0.5f, size.height * 0.25f),
              radius = size.width * 1.3f,
            )
            val linearGradient = Brush.verticalGradient(
              colors = listOf(
                topColor.copy(alpha = topColor.alpha * 0.65f),
                bottomColor.copy(alpha = bottomColor.alpha * 0.35f),
                Color.Transparent,
              ),
              startY = 0f,
              endY = size.height * 0.80f,
            )
            onDrawBehind {
              drawRect(radialGradient)
              drawRect(linearGradient)
            }
          } else {
            onDrawBehind {}
          }
        }
        .windowInsetsPadding(
          if (edgeToEdgeVisualizer) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
          } else {
            WindowInsets.safeDrawing
          },
        )
        .padding(horizontal = if (edgeToEdgeVisualizer || flushInPlaceLyrics) 0.dp else 16.dp)
        .padding(top = if (edgeToEdgeVisualizer || flushInPlaceLyrics) 0.dp else 6.dp, bottom = 12.dp),
  ) {
    val headerBar = @Composable {
      Box(modifier = Modifier.fillMaxWidth()) {
        ReactiveIconButton(
          onClick = {
            if (isLyricsFullscreen) {
              isLyricsFullscreen = false
            } else if (showInPlaceLyrics) {
              showInPlaceLyrics = false
            } else {
              onBackPress()
            }
          },
          modifier = Modifier.align(Alignment.CenterStart),
        ) {
          Icon(
            imageVector = if (showInPlaceLyrics || isLyricsFullscreen) Icons.RoundedFilled.ArrowBack else Icons.RoundedFilled.ExpandMore,
            contentDescription = stringResource(if (showInPlaceLyrics || isLyricsFullscreen) R.string.back else R.string.ui_close),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(if (showInPlaceLyrics || isLyricsFullscreen) 28.dp else 32.dp),
          )
        }

        Text(
          text = stringResource(R.string.ui_now_playing),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          letterSpacing = 2.sp,
          modifier = Modifier.align(Alignment.Center),
        )

        Row(
          modifier = Modifier.align(Alignment.CenterEnd),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ReactiveIconButton(onClick = { onOpenSheet(Sheets.AudioProperties) }) {
            Icon(
              imageVector = Icons.RoundedFilled.Info,
              contentDescription = stringResource(R.string.player_sheets_more_title),
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(28.dp),
            )
          }
        }
      }
    }

    val losslessBadge = @Composable {
      if (collapsedAudioBadgeLabel.isNotBlank()) {
        Surface(
          shape = RoundedCornerShape(4.dp),
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
          border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
          modifier =
            Modifier.clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
            ) {
              showLosslessDetails = !showLosslessDetails
            },
        ) {
          Text(
            text =
              if (showLosslessDetails && fullLosslessDetailString.isNotBlank()) {
                fullLosslessDetailString
              } else {
                collapsedAudioBadgeLabel
              },
            style =
              MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 8.5.sp,
                letterSpacing = 0.8.sp,
              ),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
    }

    val animatableOffsetX = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val haptic = rememberAppHaptics()
    var activeCoverOverride by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(currentItem?.stableId, albumArtBitmap) {
      activeCoverOverride = null
    }

    val nextItem = remember(filteredPlaylist, mediaPath) {
      val idx = filteredPlaylist.indexOfFirst { it.isPlaying || it.path == mediaPath || it.uri.toString() == mediaPath }
      if (idx in 0 until filteredPlaylist.lastIndex) filteredPlaylist[idx + 1] else null
    }

    val prevItem = remember(filteredPlaylist, mediaPath) {
      val idx = filteredPlaylist.indexOfFirst { it.isPlaying || it.path == mediaPath || it.uri.toString() == mediaPath }
      if (idx > 0) filteredPlaylist[idx - 1] else null
    }

    val nextCoverBitmap =
      rememberAudioAlbumArt(
        pathOrUri = nextItem?.let { it.path.ifBlank { it.uri.toString() } },
        artworkUri = nextItem?.tvgLogo,
      )
    val prevCoverBitmap =
      rememberAudioAlbumArt(
        pathOrUri = prevItem?.let { it.path.ifBlank { it.uri.toString() } },
        artworkUri = prevItem?.tvgLogo,
      )

    @OptIn(ExperimentalFoundationApi::class)
    val centerVisualizerView = @Composable { visualizerModifier: Modifier ->
      BoxWithConstraints(
        modifier =
          visualizerModifier
            .then(if (showVisualizer) Modifier else Modifier.clipToBounds())
            .then(
              if (showVisualizer || showInPlaceLyrics) {
                Modifier
              } else {
                Modifier.audioPlaybackGestures(
                  viewModel,
                  onTap = { viewModel.toggleAudioVisualizer() },
                  onHold = { onOpenSheet(Sheets.VisualizerStyle) },
                )
              },
            ),
        contentAlignment = Alignment.Center,
      ) {
        val containerWidthPx = constraints.maxWidth.toFloat()
        val currentOffset = animatableOffsetX.value

        if (showInPlaceLyrics && !isTabletLandscape) {
          app.gyrolet.mpvrx.ui.player.controls.components.LyricsView(
            viewModel = viewModel,
            onOpenProviderSheet = { onOpenSheet(Sheets.LyricsProvider) },
            modifier = Modifier.fillMaxSize(),
            isLyricsFullscreen = isLyricsFullscreen,
            onTap = resetInactivityTimer,
          )
        } else {
          AnimatedContent(
            targetState = showVisualizer,
            contentAlignment = Alignment.Center,
            transitionSpec = {
              val contentTransform =
                if (targetState) {
                  (fadeIn(animationSpec = tween(350, easing = FastOutSlowInEasing)) +
                    scaleIn(animationSpec = tween(350, easing = FastOutSlowInEasing), initialScale = 0.90f))
                    .togetherWith(
                      fadeOut(animationSpec = tween(280)) +
                        scaleOut(animationSpec = tween(280), targetScale = 1.06f),
                    )
                } else {
                  (fadeIn(animationSpec = tween(350, easing = FastOutSlowInEasing)) +
                    scaleIn(animationSpec = spring(dampingRatio = 0.72f, stiffness = 400f), initialScale = 0.88f))
                    .togetherWith(
                      fadeOut(animationSpec = tween(280)) +
                        scaleOut(animationSpec = tween(280), targetScale = 1.06f),
                    )
                }
              contentTransform.using(SizeTransform(clip = false))
            },
            label = "visualizer_toggle",
            modifier = Modifier.fillMaxSize(),
          ) { isVisualizerActive ->
          if (isVisualizerActive) {
            AudioVisualizerViewport(
              viewModel = viewModel,
              style = audioVisualizerStyle,
              palette = visualizerPalette,
              isPlaying = isPlaying,
              isSheetOpen = isSheetOpen,
              features = visualizerFeatures,
              onClick = viewModel::toggleAudioVisualizer,
              onLongClick = { onOpenSheet(Sheets.VisualizerStyle) },
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            val coverShape = RoundedCornerShape(32.dp)
            val density = LocalDensity.current
            val gap = with(density) { 24.dp.toPx() }
            val stride = containerWidthPx + gap

            Box(
              modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (isTabletPortrait) 0.52f else if (isPortrait) 0.88f else 1f)
                .pointerInput(showVisualizer, containerWidthPx, isAudiobook) {
                  if (showVisualizer || isAudiobook || containerWidthPx <= 0f) return@pointerInput
                  detectHorizontalDragGestures(
                    onDragStart = {
                      coroutineScope.launch { animatableOffsetX.snapTo(0f) }
                    },
                    onDragEnd = {
                      val threshold = containerWidthPx * 0.25f
                      val dragVal = animatableOffsetX.value
                      coroutineScope.launch {
                        if (dragVal < -threshold) {
                          haptic.pickup()
                          animatableOffsetX.animateTo(
                            targetValue = -stride,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
                          )
                          activeCoverOverride = nextCoverBitmap ?: albumArtBitmap
                          animatableOffsetX.snapTo(0f)
                          if (viewModel.hasPlaylistSupport()) {
                            viewModel.playNext()
                          } else {
                            runCatching { PlaybackSession.command("playlist-next") }
                          }
                        } else if (dragVal > threshold) {
                          haptic.pickup()
                          animatableOffsetX.animateTo(
                            targetValue = stride,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
                          )
                          activeCoverOverride = prevCoverBitmap ?: albumArtBitmap
                          animatableOffsetX.snapTo(0f)
                          if (viewModel.hasPlaylistSupport()) {
                            viewModel.playPrevious()
                          } else {
                            runCatching { PlaybackSession.command("playlist-prev") }
                          }
                        } else {
                          animatableOffsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
                          )
                        }
                      }
                    },
                    onDragCancel = {
                      coroutineScope.launch {
                        animatableOffsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f))
                      }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                      change.consume()
                      coroutineScope.launch {
                        animatableOffsetX.snapTo(animatableOffsetX.value + dragAmount)
                      }
                    }
                  )
                },
              contentAlignment = Alignment.Center,
            ) {
              // 1. Previous Cover Art Card (Visible when dragging right -> currentOffset > 0)
              if (currentOffset > 0f) {
                Surface(
                  modifier = Modifier
                    .aspectRatio(1f)
                    .offset { IntOffset((-stride + currentOffset).roundToInt(), 0) }
                    .clip(coverShape),
                  shape = coverShape,
                  color = Color.Transparent,
                ) {
                  CoverArtCardImage(
                    bitmap = prevCoverBitmap,
                    artworkUrl = prevItem?.tvgLogo?.takeIf { it.isNotBlank() },
                    contentScale = if (isAudiobook) ContentScale.Fit else ContentScale.Crop,
                  )
                }
              }

              // 2. Next Cover Art Card (Visible when dragging left -> currentOffset < 0)
              if (currentOffset < 0f) {
                Surface(
                  modifier = Modifier
                    .aspectRatio(1f)
                    .offset { IntOffset((stride + currentOffset).roundToInt(), 0) }
                    .clip(coverShape),
                  shape = coverShape,
                  color = Color.Transparent,
                ) {
                  CoverArtCardImage(
                    bitmap = nextCoverBitmap,
                    artworkUrl = nextItem?.tvgLogo?.takeIf { it.isNotBlank() },
                    contentScale = if (isAudiobook) ContentScale.Fit else ContentScale.Crop,
                  )
                }
              }

              // 3. Current Cover Art Card
              Surface(
                modifier = Modifier
                  .aspectRatio(1f)
                  .offset { IntOffset(currentOffset.roundToInt(), 0) }
                  .clip(coverShape),
                shape = coverShape,
                color = Color.Transparent,
              ) {
                CoverArtCardImage(
                  bitmap = activeCoverOverride ?: albumArtBitmap,
                  artworkUrl = currentArtworkUri,
                  contentScale = if (isAudiobook) ContentScale.Fit else ContentScale.Crop,
                )
              }
            }
          }
        }
      }
    }
    }

    @OptIn(ExperimentalFoundationApi::class)
    val trackMetadataView = @Composable {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
      ) {
        val displayTitle =
          remember(audiobook?.book?.title, lastValidTitle, displayArtist) {
            audiobook?.book?.title ?: cleanSongTitle(lastValidTitle, displayArtist)
          }

        // 1. Song Title Only
        Text(
          text = displayTitle,
          style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
          fontFamily = fontFamilyForText(displayTitle),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
        )
        Spacer(modifier = Modifier.height(2.dp))

        // 2. Singer / Artist Name
        Text(
          text = displayArtist,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
          fontFamily = fontFamilyForText(displayArtist),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
        )
        Spacer(modifier = Modifier.height(2.dp))

        // 3. Track Info | A-B Loop Control
        audiobook?.book?.narrator?.takeIf(String::isNotBlank)?.let { narrator ->
          Text(
            text = "${stringResource(R.string.audiobook_narrator)}: $narrator",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.height(4.dp))
        }
        val playlistInfo = viewModel.getPlaylistInfo()
        val trackText = if (isAudiobook && bookChapterIndex >= 0) {
          "${stringResource(R.string.audiobook_chapter_number, bookChapterIndex + 1)} / ${chapters.size}"
        } else if (playlistInfo != null) "Track $playlistInfo" else "Audio Media"

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = trackText,
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.combinedClickable(
                onClick = { onOpenSheet(Sheets.Chapters) },
                onLongClick = {
                  if (viewModel.preparePlaybackBookmark()) onOpenSheet(Sheets.BookmarkEditor)
                },
              ),
            )
            Text(
              text = "|",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )

            // 4. Playback Speed (left of A-B Loop)
            Surface(
              shape = CircleShape,
              color = Color.Transparent,
              modifier =
                Modifier
                  .height(30.dp)
                  .clip(CircleShape)
                  .clickable(enabled = !speedConfigOwned, onClick = { onOpenSheet(Sheets.PlaybackSpeed) }),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 10.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Speed,
                  contentDescription = stringResource(R.string.ui_playback_speed),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (speedConfigOwned) 0.38f else 1f),
                  modifier = Modifier.size(16.dp),
                )
                Text(
                  text = String.format("%.2fx", playbackSpeed ?: 1f),
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (speedConfigOwned) 0.38f else 1f),
                )
              }
            }

            AnimatedContent(
              targetState = abLoop.isExpanded,
              transitionSpec = {
                (fadeIn(animationSpec = tween(200)) + expandHorizontally(animationSpec = tween(250)))
                  .togetherWith(fadeOut(animationSpec = tween(200)) + shrinkHorizontally(animationSpec = tween(250)))
              },
              label = "AudioABLoopExpand",
            ) { expanded ->
              if (expanded) {
                Row(
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Surface(
                    shape = CircleShape,
                    color =
                      if (abLoopA !=
                        null
                      ) {
                        MaterialTheme.colorScheme.primaryContainer
                      } else {
                        MaterialTheme.colorScheme.surfaceVariant
                      },
                    modifier = Modifier.height(30.dp).clip(CircleShape).clickable(onClick = { viewModel.setLoopA() }),
                  ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                      Text(
                        text = if (abLoopA != null) formatSec(abLoopA.toLong()) else "A",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color =
                          if (abLoopA !=
                            null
                          ) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                          } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                          },
                      )
                    }
                  }
                  Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier =
                      Modifier.size(30.dp).clip(CircleShape).clickable(onClick = {
                        viewModel.clearABLoop()
                        viewModel.toggleABLoopExpanded()
                      }),
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Icon(
                        imageVector = Icons.RoundedFilled.Close,
                        contentDescription = "Clear A-B Loop",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                      )
                    }
                  }
                  Surface(
                    shape = CircleShape,
                    color =
                      if (abLoopB !=
                        null
                      ) {
                        MaterialTheme.colorScheme.primaryContainer
                      } else {
                        MaterialTheme.colorScheme.surfaceVariant
                      },
                    modifier = Modifier.height(30.dp).clip(CircleShape).clickable(onClick = { viewModel.setLoopB() }),
                  ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                      Text(
                        text = if (abLoopB != null) formatSec(abLoopB.toLong()) else "B",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color =
                          if (abLoopB !=
                            null
                          ) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                          } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                          },
                      )
                    }
                  }
                }
              } else {
                Surface(
                  shape = CircleShape,
                  color = Color.Transparent,
                  modifier = Modifier.clip(CircleShape).clickable(onClick = viewModel::toggleABLoopExpanded),
                ) {
                  AbLoopIcon(
                    modifier = Modifier.size(30.dp),
                    tint =
                      if (abLoopA != null ||
                        abLoopB != null
                      ) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                    isASet = abLoopA != null,
                    isBSet = abLoopB != null,
                  )
                }
              }
            }
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            // Favorite Button (right before Add to Playlist)
            ReactiveIconButton(
              onClick = {
                val path = mediaPath?.takeIf { it.isNotBlank() } ?: activeTrackPath ?: return@ReactiveIconButton
                val server = activeJellyfinServer
                val itemId = jellyfinInfo?.second
                val navServer = activeNavidromeServer
                val navSongId = navidromeInfo?.second
                val newFavState = !isCurrentTrackFavorite

                coroutineScope.launch {
                  // Toggle local Room favorite state
                  playlistRepository.toggleFavorite(filePath = path, fileName = displayTitle, isAudio = true)
                  actionHaptics.selection(newFavState)
                  // Toggle Jellyfin server favorite status via API if playing from Jellyfin
                  if (server != null && !itemId.isNullOrBlank()) {
                    jellyfinFavoriteOverride = newFavState
                    withContext(Dispatchers.IO) {
                      jellyfinRepository.toggleFavorite(server = server, itemId = itemId, isFavorite = newFavState)
                    }
                  }
                  // Toggle Navidrome server favorite status via API if playing from Navidrome
                  if (navServer != null && !navSongId.isNullOrBlank()) {
                    navidromeFavoriteOverride = newFavState
                    withContext(Dispatchers.IO) {
                      navidromeRepository.toggleFavorite(server = navServer, songId = navSongId, isFavorite = newFavState)
                    }
                  }
                }
              },
              modifier = Modifier.size(40.dp),
            ) {
              Icon(
                imageVector = if (isCurrentTrackFavorite) Icons.RoundedFilled.Favorite else Icons.RoundedFilled.FavoriteBorder,
                contentDescription = if (isCurrentTrackFavorite) "Remove from Favorites" else "Add to Favorites",
                tint = if (isCurrentTrackFavorite) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp),
              )
            }

            ReactiveIconButton(
              onClick = { addToPlaylistDialogOpen = true },
              modifier = Modifier.size(40.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.PlaylistAdd,
                contentDescription = stringResource(R.string.ui_add_to_playlist),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp),
              )
            }
          }
        }
      }
    }

    val currentLyricStripView = @Composable {
      val lyricsState by viewModel.lyricsUiState.collectAsState()
      val translationDisplayMode by audioPreferences.lyricsTranslationDisplayMode.collectAsState()
      val activeLyrics = lyricsState.lyrics
      val syncedLines = activeLyrics?.synced
      val activeIndex = lyricsState.activeLineIndex
      val precisePosition by viewModel.precisePosition.collectAsStateWithLifecycle()
      val paused by PlaybackSession.propBoolean["pause"].collectAsState()
      val playbackSpeed by PlaybackSession.propFloat["speed"].collectAsState()

      val currentPosMs = remember(precisePosition, lyricsState.syncOffsetMs) {
        (precisePosition * 1000).toLong() + lyricsState.syncOffsetMs
      }
      val smoothPositionMs = rememberSmoothedPositionMs(currentPosMs, paused == false, playbackSpeed ?: 1f)

      val currentLine = syncedLines?.getOrNull(activeIndex)
      val hasSyncedLyrics = !syncedLines.isNullOrEmpty()
      val isInstrumentalGap = hasSyncedLyrics && (currentLine == null || currentLine.line.isBlank() || currentLine.line.trim().equals("[instrumental]", ignoreCase = true) || currentLine.line.trim().equals("instrumental", ignoreCase = true))

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable {
            if (isTabletLandscape) {
              tabletDualPaneTab = 1
            } else {
              showInPlaceLyrics = true
            }
            resetInactivityTimer()
          }
          .padding(horizontal = 4.dp, vertical = 4.dp),
      ) {
        Box(
          modifier = Modifier.weight(1f, fill = false),
          contentAlignment = Alignment.CenterStart,
        ) {
          if (lyricsState.isLoading) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Start,
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.lyrics_loading_label),
                style = MaterialTheme.typography.titleMedium.copy(
                  fontSize = 18.sp,
                  fontWeight = FontWeight.Bold,
                  fontFamily = fontFamilyForText(stringResource(R.string.lyrics_loading_label)),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
              )
            }
          } else if (hasSyncedLyrics) {
            if (isInstrumentalGap) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Audiotrack,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                  text = stringResource(R.string.instrumental),
                  style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = fontFamilyForText(stringResource(R.string.instrumental)),
                  ),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  textAlign = TextAlign.Start,
                )
              }
            } else {
              val rawTrans = currentLine?.translation?.trim()
              val displayText = if (translationDisplayMode == LyricsTranslationDisplayMode.Replace && !rawTrans.isNullOrBlank()) {
                rawTrans
              } else {
                currentLine?.line?.trim() ?: ""
              }

              AnimatedContent(
                targetState = Triple(currentLine, syncedLines?.getOrNull(activeIndex + 1)?.time, displayText),
                transitionSpec = {
                  (fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                    slideInVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { height -> (height * 0.4f).toInt() })
                    .togetherWith(
                      fadeOut(animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                        slideOutVertically(animationSpec = tween(280, easing = FastOutSlowInEasing)) { height -> -(height * 0.4f).toInt() },
                    ).using(
                      SizeTransform(clip = false),
                    )
                },
                label = "currentLyricTransition",
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.fillMaxWidth(),
              ) { (line, nextLineTime, text) ->
                val words = if (line != null && text == line.line.trim()) line.words else null
                val scrollState = rememberScrollState()
                val wordWidths = remember(line, text) { mutableStateMapOf<Int, Int>() }
                val lineStartMs = line?.time?.toLong() ?: 0L
                val lineEndMs = nextLineTime?.toLong()?.takeIf { it > lineStartMs } ?: (lineStartMs + 8_000L)
                val wordEndTimes = remember(words, lineStartMs, nextLineTime) {
                  words.orEmpty().mapIndexed { wordIndex, word ->
                    val wordStartMs = word.time.toLong()
                    words?.getOrNull(wordIndex + 1)?.time?.toLong()
                      ?.takeIf { it > wordStartMs }
                      ?: nextLineTime?.toLong()
                        ?.coerceAtMost(lineStartMs + 8_000L)
                        ?.takeIf { it > wordStartMs }
                      ?: (wordStartMs + 600L)
                  }
                }

                LaunchedEffect(line, nextLineTime, text, scrollState) {
                  snapshotFlow {
                    val maxScroll = scrollState.maxValue
                    val viewportWidth = scrollState.viewportSize
                    if (maxScroll <= 0 || maxScroll == Int.MAX_VALUE || viewportWidth <= 0) {
                      0
                    } else {
                      val positionMs = smoothPositionMs.value
                      val highlightPosition = if (!words.isNullOrEmpty()) {
                        val activeWordIndex = words.indexOfLast { it.time.toLong() <= positionMs }
                        if (activeWordIndex < 0 || wordWidths.size != words.size) {
                          0f
                        } else {
                          val wordStartMs = words[activeWordIndex].time.toLong()
                          val wordDurationMs = (wordEndTimes[activeWordIndex] - wordStartMs).coerceAtLeast(1L)
                          val progress = ((positionMs - wordStartMs).toFloat() / wordDurationMs).coerceIn(0f, 1f)
                          val precedingWidth = (0 until activeWordIndex).sumOf { wordWidths[it] ?: 0 }
                          precedingWidth + (wordWidths[activeWordIndex] ?: 0) * progress
                        }
                      } else {
                        val progress = ((positionMs - lineStartMs).toFloat() / (lineEndMs - lineStartMs))
                          .coerceIn(0f, 1f)
                        (maxScroll.toFloat() + viewportWidth) * progress
                      }
                      (highlightPosition - viewportWidth * 0.65f).roundToInt().coerceIn(0, maxScroll)
                    }
                  }.collect { offset ->
                    scrollState.scrollTo(offset)
                  }
                }

                if (!words.isNullOrEmpty()) {
                  val activeColor = MaterialTheme.colorScheme.onSurface
                  val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .horizontalScroll(scrollState, enabled = false),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    words.forEachIndexed { wordIndex, word ->
                      Box(Modifier.onSizeChanged { wordWidths[wordIndex] = it.width }) {
                        AnimatedLyricWord(
                          word = word,
                          endTimeMs = wordEndTimes[wordIndex],
                          positionMs = smoothPositionMs,
                          activeColor = activeColor,
                          inactiveColor = inactiveColor,
                          fontSize = 19.sp,
                        )
                      }
                    }
                  }
                } else {
                  Text(
                    text = text,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState, enabled = false),
                    style = MaterialTheme.typography.titleMedium.copy(
                      fontSize = 19.sp,
                      fontWeight = FontWeight.ExtraBold,
                      fontFamily = fontFamilyForText(text),
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Start,
                  )
                }
              }
            }
          } else if (!activeLyrics?.plain.isNullOrEmpty()) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Start,
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Audiotrack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text(
                text = stringResource(R.string.player_lyrics_title),
                style = MaterialTheme.typography.titleMedium.copy(
                  fontSize = 18.sp,
                  fontWeight = FontWeight.Bold,
                  fontFamily = fontFamilyForText(stringResource(R.string.player_lyrics_title)),
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
              )
            }
          } else {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Start,
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Lyrics,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text(
                text = if (isAudiobook) stringResource(R.string.audiobook_no_text) else stringResource(R.string.player_lyrics_title),
                style = MaterialTheme.typography.titleMedium.copy(
                  fontSize = 18.sp,
                  fontWeight = FontWeight.Normal,
                  fontFamily = fontFamilyForText(stringResource(R.string.player_lyrics_title)),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
              )
            }
          }
        }

        Spacer(Modifier.width(6.dp))
        Icon(
          imageVector = Icons.RoundedFilled.ChevronRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.size(18.dp),
        )
      }
    }

    val seekbarView = @Composable {
      val position by PlaybackSession.propInt["time-pos"].collectAsStateWithLifecycle()
      val remaining  by PlaybackSession.propFloat["playtime-remaining"].collectAsState()
      val precisePosition by viewModel.precisePosition.collectAsStateWithLifecycle()
      val currentPosSec = if (precisePosition > 0f) precisePosition else position?.toFloat() ?: 0f
      val isPaused = paused ?: false
      val bookOffset = currentItem?.audiobook?.let { audiobook?.positionInBook(it.trackId, 0) }?.div(1000f) ?: 0f
      val timelinePosition = if (audiobook != null) (bookOffset + currentPosSec).coerceIn(0f, audiobook.durationMs / 1000f) else currentPosSec
      val timelineDuration = audiobook?.durationMs?.div(1000f) ?: currentDurSec
      val effectiveRemaining =
        if (audiobook != null) (timelineDuration - timelinePosition).coerceAtLeast(0f) / (playbackSpeed ?: 1f).coerceAtLeast(0.1f)
        else (remaining ?: 0f).takeIf { it > 0f }
          ?: (currentDurSec - currentPosSec).coerceAtLeast(0f)

      androidx.compose.runtime.key(audiobook?.book?.id ?: currentItem?.stableId) {
      SeekbarWithTimers(
        position = timelinePosition,
        committedPosition = timelinePosition,
        duration = timelineDuration.coerceAtLeast(1f),
        remaining = effectiveRemaining,
        onValueChange = { value -> if (!isAudiobook) viewModel.seekPreviewTo(value) },
        onValueChangeFinished = viewModel::seekAudioTo,
        timersInverted = Pair(false, invertDuration),
        durationTimerOnCLick = { playerPreferences.invertDuration.set(!invertDuration) },
        positionTimerOnClick = {},
        chapters = seekbarChapters,
        skipSegments = persistentListOf(),
        paused = isPaused,
        seekbarStyle = seekbarStyle,
        showWavyVisualizer = audioWavySeekbar,
        waveFeatures = if (audioWavySeekbar) visualizerFeatures else null,
        wavePalette = visualizerPalette,
        waveSheetOpen = isSheetOpen,
        loopStart = abLoopA?.toFloat()?.plus(bookOffset),
        loopEnd = abLoopB?.toFloat()?.plus(bookOffset),
        isPortrait = true,
        applyHorizontalPadding = false,
        centerContent = losslessBadge,
        modifier = Modifier.fillMaxWidth(),
      )
      }
    }

    val playbackControlsRow = @Composable {
      val seekable by PlaybackSession.propBoolean["seekable"].collectAsStateWithLifecycle()
      val canSeek = seekable == true && playbackState.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
      val prevEnabled = if (isAudiobook) bookChapterIndex >= 0 else playlistModeEnabled
      val nextEnabled = if (isAudiobook) bookChapterIndex >= 0 && bookChapterIndex < chapters.lastIndex else playlistModeEnabled
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .then(if (isAudiobook) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ReactiveIconButton(
          onClick = {
            if (isAudiobook) viewModel.stepPlaybackChapter(-1) else viewModel.playPrevious()
          },
          enabled = prevEnabled,
          modifier = Modifier.size(if (isAudiobook) 48.dp else 56.dp),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.SkipPrevious,
            contentDescription = if (isAudiobook) stringResource(R.string.audiobook_previous_chapter) else null,
            tint =
              if (prevEnabled) {
                MaterialTheme.colorScheme.onSurface
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              },
            modifier = Modifier.size(if (isAudiobook) 28.dp else 38.dp),
          )
        }
        if (isAudiobook) {
          AudioSeekButton(forward = false, seconds = 30, enabled = canSeek) {
            viewModel.seekBy(-30)
            actionHaptics.confirm()
          }
        }
        ReactiveSurfaceButton(
          onClick = { viewModel.pauseUnpause() },
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(if (isPortrait) 76.dp else 64.dp),
          shadowElevation = 8.dp,
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = if (isPlaying) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.size(if (isPortrait) 44.dp else 36.dp),
            )
          }
        }
        if (isAudiobook) {
          AudioSeekButton(forward = true, seconds = 30, enabled = canSeek) {
            viewModel.seekBy(30)
            actionHaptics.confirm()
          }
        }
        ReactiveIconButton(
          onClick = {
            if (isAudiobook) viewModel.stepPlaybackChapter(1) else viewModel.playNext()
          },
          enabled = nextEnabled,
          modifier = Modifier.size(if (isAudiobook) 48.dp else 56.dp),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.SkipNext,
            contentDescription = if (isAudiobook) stringResource(R.string.audiobook_next_chapter) else null,
            tint =
              if (nextEnabled) {
                MaterialTheme.colorScheme.onSurface
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              },
            modifier = Modifier.size(if (isAudiobook) 28.dp else 38.dp),
          )
        }
      }
    }

    val playbackModeButtons = @Composable {
      if (isAudiobook) {
        val timer by app.gyrolet.mpvrx.ui.player.AudiobookPlayback.timer.collectAsStateWithLifecycle()
        ReactiveIconButton(onClick = { onOpenSheet(Sheets.AudiobookRewind) }, modifier = Modifier.size(40.dp)) {
          Icon(Icons.RoundedFilled.Replay, stringResource(R.string.audiobook_smart_rewind), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReactiveIconButton(onClick = { onOpenSheet(Sheets.AudiobookSleepTimer) }, modifier = Modifier.size(40.dp)) {
          Icon(Icons.RoundedFilled.Timer, stringResource(R.string.audiobook_sleep_timer),
            tint = if (timer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        ReactiveIconButton(
          onClick = { viewModel.toggleShuffle(); actionHaptics.selection(!shuffleEnabled) },
          enabled = playlistModeEnabled,
          modifier = Modifier.size(40.dp),
        ) {
          Icon(if (shuffleEnabled) Icons.RoundedFilled.ShuffleOn else Icons.RoundedFilled.Shuffle, null,
            tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReactiveIconButton(onClick = { viewModel.cycleRepeatMode(); actionHaptics.confirm() }, modifier = Modifier.size(40.dp)) {
          Icon(when (repeatMode) {
            RepeatMode.OFF -> Icons.RoundedFilled.Repeat
            RepeatMode.ONE -> Icons.RoundedFilled.RepeatOne
            RepeatMode.ALL -> Icons.RoundedFilled.RepeatOn
          }, null, tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }

    val bottomActionRow = @Composable {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ReactiveIconButton(
          onClick = { onOpenSheet(Sheets.Equalizer) },
          enabled = !audioFiltersConfigOwned,
          modifier =
            Modifier
              .clip(
                CircleShape,
              ).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
              .size(48.dp),
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = Icons.RoundedFilled.Equalizer,
              contentDescription = "Equalizer",
              tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (audioFiltersConfigOwned) 0.38f else 1f),
              modifier = Modifier.size(24.dp),
            )
          }
        }
        if (isTabletLandscape) {
          Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
          ) {
            Row(
              modifier =
                Modifier
                  .clip(CircleShape)
                  .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                  )
                  .horizontalScroll(rememberScrollState())
                  .padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              playbackModeButtons()
              ReactiveIconButton(
                onClick = { viewModel.toggleAudioVisualizer() },
                onLongClick = { onOpenSheet(Sheets.VisualizerStyle) },
                modifier = Modifier.size(40.dp),
              ) {
                Icon(
                  imageVector = if (showVisualizer) Icons.RoundedFilled.AutoAwesome else Icons.RoundedFilled.Audiotrack,
                  contentDescription = null,
                  tint = if (showVisualizer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (!isAudiobook) ReactiveIconButton(
                onClick = {
                  val act = context as? PlayerActivity
                  if (act != null) {
                    act.toggleAudioBackgroundPlayback()
                  } else {
                    audioPreferences.audioBackgroundPlayback.set(!backgroundPlaybackEnabled)
                  }
                },
                modifier = Modifier.size(40.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Headset,
                  contentDescription = stringResource(R.string.btn_label_background_playback),
                  tint = if (backgroundPlaybackEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else {
          Spacer(modifier = Modifier.width(12.dp))
          Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
          ) {
            Row(
              modifier =
                Modifier
                  .clip(CircleShape)
                  .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                  )
                  .horizontalScroll(rememberScrollState())
                  .padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              playbackModeButtons()
              ReactiveIconButton(
                onClick = { viewModel.toggleAudioVisualizer() },
                onLongClick = { onOpenSheet(Sheets.VisualizerStyle) },
                modifier = Modifier.size(40.dp),
              ) {
                Icon(
                  imageVector = if (showVisualizer) Icons.RoundedFilled.AutoAwesome else Icons.RoundedFilled.Audiotrack,
                  contentDescription = null,
                  tint = if (showVisualizer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (!isAudiobook) ReactiveIconButton(
                onClick = {
                  val act = context as? PlayerActivity
                  if (act != null) {
                    act.toggleAudioBackgroundPlayback()
                  } else {
                    audioPreferences.audioBackgroundPlayback.set(!backgroundPlaybackEnabled)
                  }
                },
                modifier = Modifier.size(40.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Headset,
                  contentDescription = stringResource(R.string.btn_label_background_playback),
                  tint = if (backgroundPlaybackEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
          Spacer(modifier = Modifier.width(12.dp))
          ReactiveIconButton(
            onClick = { onOpenSheet(Sheets.Playlist) },
            modifier =
              Modifier
                .clip(
                  CircleShape,
                ).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
                .size(48.dp),
          ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
              Icon(
                imageVector = Icons.RoundedFilled.QueueMusic,
                contentDescription = if (isAudiobook) stringResource(R.string.audiobook_chapters) else stringResource(R.string.player_up_next_title),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
              )
            }
          }
        }
      }
    }

    if (isPortrait) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .clickable(
            enabled = showInPlaceLyrics,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) { resetInactivityTimer() },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (edgeToEdgeVisualizer) {
          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            centerVisualizerView(Modifier.fillMaxSize())
            Column(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                  .padding(horizontal = controlsSidePadding)
                  .padding(top = 6.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              headerBar()
            }
          }
        } else {
          androidx.compose.animation.AnimatedVisibility(
            visible = !isLyricsFullscreen,
            enter = fadeIn(animationSpec = tween(300)) +
              androidx.compose.animation.expandVertically(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)) +
              androidx.compose.animation.shrinkVertically(animationSpec = tween(300)),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().then(headerContentPadding),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              headerBar()
              if (!showInPlaceLyrics) Spacer(modifier = Modifier.height(16.dp))
            }
          }

          centerVisualizerView(Modifier.weight(1f).fillMaxWidth())
        }

        androidx.compose.animation.AnimatedVisibility(
          visible = !isLyricsFullscreen,
          enter = fadeIn(animationSpec = tween(300)) + androidx.compose.animation.expandVertically(animationSpec = tween(300)),
          exit = fadeOut(animationSpec = tween(300)) + androidx.compose.animation.shrinkVertically(animationSpec = tween(300)),
        ) {
          Column(
            modifier = Modifier.padding(horizontal = controlsSidePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Spacer(modifier = Modifier.height(4.dp))
            trackMetadataView()
            if (!showInPlaceLyrics) {
              Spacer(modifier = Modifier.height(6.dp))
              currentLyricStripView()
              Spacer(modifier = Modifier.height(4.dp))
            } else {
              Spacer(modifier = Modifier.height(10.dp))
            }
            seekbarView()
            Spacer(modifier = Modifier.height(28.dp))
            playbackControlsRow()
            Spacer(modifier = Modifier.height(30.dp))
            bottomActionRow()
          }
        }
      }
    } else if (isTabletLandscape) {
      Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
          verticalArrangement = Arrangement.SpaceBetween,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (edgeToEdgeVisualizer) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
              centerVisualizerView(Modifier.fillMaxSize())
              Column(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = controlsSidePadding)
                    .padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                headerBar()
              }
            }
          } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              headerBar()
              Spacer(modifier = Modifier.height(6.dp))
            }
            centerVisualizerView(
              Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp, horizontal = 24.dp),
            )
          }
          Column(
            modifier = Modifier.padding(horizontal = controlsSidePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Spacer(modifier = Modifier.height(6.dp))
            trackMetadataView()
            if (tabletDualPaneTab != 1) {
              Spacer(modifier = Modifier.height(4.dp))
              currentLyricStripView()
              Spacer(modifier = Modifier.height(4.dp))
            } else {
              Spacer(modifier = Modifier.height(8.dp))
            }
            seekbarView()
            Spacer(modifier = Modifier.height(14.dp))
            playbackControlsRow()
            Spacer(modifier = Modifier.height(16.dp))
            bottomActionRow()
          }
        }

        Surface(
          modifier = Modifier
            .weight(1.1f)
            .fillMaxHeight()
            .then(
              if (edgeToEdgeVisualizer) {
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 6.dp)
              } else {
                Modifier
              },
            )
            .padding(end = controlsSidePadding)
            .clip(RoundedCornerShape(24.dp)),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          shape = RoundedCornerShape(24.dp),
        ) {
          DualPaneSidePanel(
            viewModel = viewModel,
            playlist = filteredPlaylist,
            selectedTab = tabletDualPaneTab,
            onTabSelected = { tabletDualPaneTab = it },
            onOpenSheet = onOpenSheet,
          )
        }
      }
    } else {
      Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        centerVisualizerView(Modifier.weight(1f).fillMaxHeight())
        Column(
          modifier =
            Modifier.weight(1.2f).fillMaxHeight().padding(end = controlsSidePadding).then(
              if (edgeToEdgeVisualizer) {
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 6.dp)
              } else {
                Modifier
              },
            ),
          verticalArrangement = Arrangement.SpaceBetween,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          headerBar()
          trackMetadataView()
          seekbarView()
          playbackControlsRow()
          bottomActionRow()
        }
      }
    }

    if (addToPlaylistDialogOpen && !mediaPath.isNullOrBlank()) {
      val displayTitle = remember(lastValidTitle, displayArtist) {
        cleanSongTitle(lastValidTitle, displayArtist)
      }
      val videoForPlaylist =
        remember(mediaPath, displayTitle) {
          Video(
            id = mediaPath.hashCode().toLong(),
            title = displayTitle,
            displayName = displayTitle,
            path = mediaPath,
            uri = Uri.parse(mediaPath),
            duration = duration?.toLong() ?: currentItem?.durationSeconds?.toLong() ?: 0L,
            durationFormatted = "",
            size = 0L,
            sizeFormatted = "",
            dateModified = 0L,
            dateAdded = 0L,
            mimeType = "audio/*",
            bucketId = "",
            bucketDisplayName = "",
            width = 0,
            height = 0,
            fps = 0f,
            resolution = "",
            isAudio = true,
          )
        }

      val isJellyfinMedia = remember(mediaPath) {
        !mediaPath.isNullOrBlank() &&
          (mediaPath.contains("api_key=", ignoreCase = true) ||
            mediaPath.contains("/Items/", ignoreCase = true) ||
            mediaPath.contains("/Audio/", ignoreCase = true) ||
            mediaPath.contains("jellyfin", ignoreCase = true) ||
            mediaPath.contains("/rest/stream", ignoreCase = true))
      }

      AddToPlaylistDialog(
        isOpen = true,
        candidates = listOf(videoForPlaylist).toPlaylistCandidates(),
        onDismiss = { addToPlaylistDialogOpen = false },
        onSuccess = { addToPlaylistDialogOpen = false },
        isJellyfin = isJellyfinMedia,
      )
    }
  }
}

@Composable
private fun DualPaneSidePanel(
  viewModel: PlayerViewModel,
  playlist: List<PlaylistItem>,
  selectedTab: Int = 0,
  onTabSelected: (Int) -> Unit = {},
  onOpenSheet: (Sheets) -> Unit = {},
) {
  val playbackState by PlaybackSession.state.collectAsStateWithLifecycle()
  val isAudiobook = playbackState.currentItem?.audiobook != null

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      androidx.compose.material3.FilterChip(
        selected = selectedTab == 0,
        onClick = { onTabSelected(0) },
        label = {
          Text(
            text = if (isAudiobook) stringResource(R.string.audiobook_chapters) else stringResource(R.string.player_up_next_title),
            fontWeight = FontWeight.Bold,
          )
        },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
          selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
      )
      androidx.compose.material3.FilterChip(
        selected = selectedTab == 1,
        onClick = { onTabSelected(1) },
        label = { Text(stringResource(R.string.player_lyrics_title), fontWeight = FontWeight.Bold) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
          selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
      )
    }

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      if (selectedTab == 0) {
        UpNextPlaylistContent(
          viewModel = viewModel,
          playlist = playlist,
        )
      } else {
        app.gyrolet.mpvrx.ui.player.controls.components.LyricsView(
          viewModel = viewModel,
          onOpenProviderSheet = { onOpenSheet(Sheets.LyricsProvider) },
          showTitleHeader = false,
        )
      }
    }
  }
}

@Composable
private fun UpNextPlaylistContent(
  viewModel: PlayerViewModel,
  playlist: List<PlaylistItem>,
) {
  val lazyListState = rememberLazyListState()
  val isM3U = viewModel.isPlaylistM3U()
  val playbackState by PlaybackSession.state.collectAsStateWithLifecycle()

  if (playbackState.currentItem?.audiobook != null) {
    val chapters by viewModel.playbackChapters.collectAsStateWithLifecycle()
    val filePosition by viewModel.precisePosition.collectAsStateWithLifecycle()
    val activeBook by app.gyrolet.mpvrx.ui.player.AudiobookPlayback.book.collectAsStateWithLifecycle()
    val currentItem = playbackState.currentItem
    val audiobook = activeBook?.takeIf { it.book.id == currentItem?.audiobook?.bookId }
    val currentChapterIndex by remember(currentItem?.audiobook, chapters, audiobook?.tracks, filePosition) {
      derivedStateOf {
        val seconds = currentItem?.audiobook?.let { info -> audiobook?.positionInBook(info.trackId, (filePosition * 1000).toLong())?.div(1000f) }
          ?: filePosition
        chapters.indexOfLast { it.start <= seconds }
      }
    }

    LaunchedEffect(currentChapterIndex) {
      if (currentChapterIndex >= 0) {
        lazyListState.scrollToItem(currentChapterIndex)
      }
    }

    Column(modifier = Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 12.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.audiobook_chapters),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Surface(
          shape = RoundedCornerShape(50),
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        ) {
          Text(
            text = "${chapters.size} chapters",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      if (chapters.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.playback_bookmarks_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyColumn(
          state = lazyListState,
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(chapters.size, key = { index -> "${chapters[index].name}_${chapters[index].start}" }) { index ->
            val chapter = chapters[index]
            val isSelected = currentChapterIndex == index
            val bgColor = if (isSelected) {
              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
              MaterialTheme.colorScheme.surfaceContainer
            }
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { viewModel.seekToPlaybackChapter(chapter) },
              shape = RoundedCornerShape(12.dp),
              color = bgColor,
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = "${index + 1}. ${chapter.name}",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
                Text(
                  text = `is`.xyz.mpv.Utils.prettyTime(chapter.start.toInt()),
                  style = MaterialTheme.typography.labelMedium,
                  color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(start = 12.dp),
                )
              }
            }
          }
        }
      }
    }
    return
  }

  var displayPlaylist by remember(playlist) { mutableStateOf(playlist) }
  LaunchedEffect(playlist) {
    displayPlaylist = playlist
  }

  val showDragHandle = !isM3U && playbackState.currentItem?.audiobook == null && displayPlaylist.size > 1

  val playingItemIndex by remember(displayPlaylist) {
    derivedStateOf { displayPlaylist.indexOfFirst { it.isPlaying } }
  }

  LaunchedEffect(playingItemIndex) {
    if (playingItemIndex >= 0) {
      lazyListState.animateScrollToItem(playingItemIndex)
    }
  }

  var dragStartIndex by remember { mutableIntStateOf(-1) }
  var dragEndIndex by remember { mutableIntStateOf(-1) }
  val reorderFeedback = rememberReorderFeedback()

  val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
    if (showDragHandle) {
      if (dragStartIndex == -1) {
        dragStartIndex = from.index
      }
      dragEndIndex = to.index
      displayPlaylist = displayPlaylist.toMutableList().apply {
        add(to.index, removeAt(from.index))
      }
      reorderFeedback.move(from.index, to.index)
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 12.dp, start = 4.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = "Coming up next",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
      ) {
        Text(
          text = "${displayPlaylist.size} tracks",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
    }

    if (displayPlaylist.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "No songs in queue",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // A queue can contain the same URI more than once.
        items(displayPlaylist.size, key = { index -> displayPlaylist[index].index }) { index ->
          val item = displayPlaylist[index]
          if (showDragHandle) {
            ReorderableItem(reorderableLazyListState, key = item.index) { isDragging ->
              val isDraggingPrev = remember { mutableStateOf(false) }
              LaunchedEffect(isDragging) {
                if (isDraggingPrev.value && !isDragging) {
                  if (dragStartIndex != -1 && dragEndIndex != -1 && dragStartIndex != dragEndIndex) {
                    viewModel.reorderPlaylistItem(dragStartIndex, dragEndIndex)
                  }
                  dragStartIndex = -1
                  dragEndIndex = -1
                }
                isDraggingPrev.value = isDragging
              }

              UpNextPlaylistItemRow(
                item = item,
                isPlaying = item.isPlaying,
                onClick = { viewModel.playPlaylistItem(item.index) },
                scope = this,
                isDragging = isDragging,
                reorderFeedback = reorderFeedback,
              )
            }
          } else {
            UpNextPlaylistItemRow(
              item = item,
              isPlaying = item.isPlaying,
              onClick = { viewModel.playPlaylistItem(item.index) },
              scope = null,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun UpNextPlaylistItemRow(
  item: PlaylistItem,
  isPlaying: Boolean,
  onClick: () -> Unit,
  scope: ReorderableCollectionItemScope?,
  isDragging: Boolean = false,
  reorderFeedback: ReorderFeedback? = null,
) {
  val bgColor = if (isPlaying) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
  } else {
    MaterialTheme.colorScheme.surfaceContainer
  }

  val itemCoverArt =
    rememberAudioAlbumArt(
      pathOrUri = item.path.ifBlank { item.uri.toString() },
      artworkUri = item.tvgLogo,
    )

  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = bgColor,
    shadowElevation = dragElevation(isDragging, app.gyrolet.mpvrx.ui.theme.AppMotion.playerReducedMotion()),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (scope != null) {
        Icon(
          imageVector = Icons.RoundedFilled.DragHandle,
          contentDescription = "Reorder",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = with(scope) {
            Modifier
              .size(24.dp)
              .draggableHandle(
                interactionSource = reorderFeedback?.interactions,
                onDragStarted = { reorderFeedback?.start() },
              )
          },
        )
        Spacer(modifier = Modifier.width(8.dp))
      }

      Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
      ) {
        val itemImageBitmap = remember(itemCoverArt) { itemCoverArt?.asImageBitmap() }
        val hasRemoteImage = item.tvgLogo.isNotBlank() && (item.tvgLogo.startsWith("http://", ignoreCase = true) || item.tvgLogo.startsWith("https://", ignoreCase = true))
        if (itemImageBitmap != null || hasRemoteImage) {
          if (itemImageBitmap != null) {
            Image(
              bitmap = itemImageBitmap,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            RemoteImage(
              url = item.tvgLogo,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }
          if (isPlaying) {
            val paused by PlaybackSession.propBoolean["pause"].collectAsState()
            val isPlaybackActive = paused != true
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
              contentAlignment = Alignment.Center,
            ) {
              MiniAudioVisualizer(
                isPlaying = isPlaybackActive,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(width = 18.dp, height = 16.dp),
              )
            }
          }
        } else {
          if (isPlaying) {
            val paused by PlaybackSession.propBoolean["pause"].collectAsState()
            val isPlaybackActive = paused != true
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              MiniAudioVisualizer(
                isPlaying = isPlaybackActive,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(width = 18.dp, height = 16.dp),
              )
            }
          } else {
            Icon(
              imageVector = Icons.RoundedFilled.Audiotrack,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.title.stripAudioExtension(),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
          color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (item.duration.isNotBlank()) {
          Text(
            text = item.duration,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}



private fun formatSec(totalSeconds: Long): String {
  val secs = totalSeconds.coerceAtLeast(0L)
  val hours = secs / 3600
  val minutes = (secs % 3600) / 60
  val remainingSecs = secs % 60
  return if (hours > 0) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainingSecs)
  } else {
    String.format(Locale.US, "%d:%02d", minutes, remainingSecs)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSeekButton(
  forward: Boolean,
  seconds: Int,
  enabled: Boolean,
  modifier: Modifier = Modifier.size(48.dp),
  onClick: () -> Unit,
) {
  val duration = pluralStringResource(R.plurals.seconds, seconds, seconds)
  val label = stringResource(if (forward) R.string.player_seek_forward else R.string.player_seek_backward, duration)
  val tint =
    if (enabled) {
      MaterialTheme.colorScheme.onSurface
    } else {
      MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text(label) } },
    state = rememberTooltipState(),
  ) {
    ReactiveIconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
      Icon(
        imageVector = if (forward) Icons.RoundedFilled.FastForward else Icons.RoundedFilled.FastRewind,
        contentDescription = label,
        tint = tint,
        modifier = Modifier.size(28.dp),
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactiveIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  enabled: Boolean = true,
  onLongClickLabel: String? = null,
  content: @Composable () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val reducedMotion = app.gyrolet.mpvrx.ui.theme.AppMotion.playerReducedMotion()

  val scale by animateFloatAsState(
    targetValue = if (isPressed && enabled && !reducedMotion) 0.96f else 1f,
    animationSpec =
      if (reducedMotion) androidx.compose.animation.core.snap() else
        app.gyrolet.mpvrx.ui.theme.AppMotion.Spatial.ExpressiveFast,
    label = "reactive_icon_button_scale",
  )

  if (onLongClick != null) {
    Box(
      modifier =
        modifier
          .tvFocusHighlight(CircleShape, enabled = enabled, focusedScale = 1.08f)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
          }
          .clip(CircleShape)
          .combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = false, radius = 24.dp),
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
          )
          .padding(8.dp),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  } else {
    IconButton(
      onClick = onClick,
      enabled = enabled,
      interactionSource = interactionSource,
      modifier =
        modifier
          .tvFocusHighlight(CircleShape, enabled = enabled, focusedScale = 1.08f)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
          },
    ) {
      content()
    }
  }
}

@Composable
private fun ReactiveSurfaceButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  shape: Shape = CircleShape,
  color: Color = MaterialTheme.colorScheme.primary,
  shadowElevation: Dp = 0.dp,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val reducedMotion = app.gyrolet.mpvrx.ui.theme.AppMotion.playerReducedMotion()

  val scale by animateFloatAsState(
    targetValue = if (isPressed && enabled && !reducedMotion) 0.95f else 1f,
    animationSpec =
      if (reducedMotion) androidx.compose.animation.core.snap() else
        app.gyrolet.mpvrx.ui.theme.AppMotion.Spatial.ExpressiveFast,
    label = "reactive_surface_button_scale",
  )

  Surface(
    onClick = onClick,
    shape = shape,
    color = color,
    shadowElevation = shadowElevation,
    enabled = enabled,
    interactionSource = interactionSource,
    modifier =
      modifier
        .tvFocusHighlight(shape, enabled = enabled, focusedScale = 1.06f)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        },
  ) {
    content()
  }
}

private fun String.stripAudioExtension(): String {
  val dotIndex = lastIndexOf('.')
  if (dotIndex <= 0) return this
  val ext = substring(dotIndex + 1)
  return if (ext.length in 2..5 && ext.none { it.isWhitespace() }) substring(0, dotIndex) else this
}
