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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.palette.graphics.Palette
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.browser.dialogs.AddToPlaylistDialog
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.PlaylistItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.AudioVisualizerStyle
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.RepeatMode
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.controls.components.AbLoopIcon
import app.gyrolet.mpvrx.ui.player.controls.components.SeekbarWithTimers
import app.gyrolet.mpvrx.ui.player.visualizer.AudioFeatures
import app.gyrolet.mpvrx.ui.player.visualizer.AudioSpectrumAnalyzer
import app.gyrolet.mpvrx.ui.player.visualizer.BlobOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.CuboidOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.GalaxyOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.ParticleOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.VisualizerPalette
import app.gyrolet.mpvrx.ui.player.visualizer.rememberAudioVisualizerFeatures

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

  fun peek(pathOrUri: String?): AudioPresentationMetadata? {
    if (pathOrUri.isNullOrBlank()) return null
    return synchronized(cache) { cache.get(pathOrUri) }
  }

  suspend fun resolve(
    context: android.content.Context,
    pathOrUri: String,
  ): AudioPresentationMetadata =
    withContext(Dispatchers.IO) {
      synchronized(cache) { cache.get(pathOrUri) }?.let { return@withContext it }

      loadMutex.withLock {
        synchronized(cache) { cache.get(pathOrUri) }?.let { return@withLock it }

        val cleanPath =
          when {
            pathOrUri.startsWith("file://") -> pathOrUri.removePrefix("file://")
            pathOrUri.startsWith("content://") -> null
            else -> pathOrUri
          }
        val retriever = MediaMetadataRetriever()
        val loaded =
          try {
            if (cleanPath != null) {
              retriever.setDataSource(cleanPath)
            } else {
              retriever.setDataSource(context, Uri.parse(pathOrUri))
            }

            AudioPresentationMetadata(
              artwork = EmbeddedArtworkResolver.decodeEmbeddedArtwork(cleanPath, retriever),
              artist =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                  ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                  ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                  ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
            )
          } catch (_: Exception) {
            AudioPresentationMetadata(artwork = null, artist = null)
          } finally {
            runCatching { retriever.release() }
          }

        synchronized(cache) { cache.put(pathOrUri, loaded) }
        loaded
      }
    }
}

@Composable
private fun rememberAudioPresentationMetadata(pathOrUri: String?): AudioPresentationMetadata? {
  val context = LocalContext.current
  var metadata by remember(pathOrUri) {
    mutableStateOf(AudioPresentationMetadataCache.peek(pathOrUri))
  }
  LaunchedEffect(pathOrUri) {
    metadata =
      if (pathOrUri.isNullOrBlank()) {
        null
      } else {
        AudioPresentationMetadataCache.resolve(context, pathOrUri)
      }
  }
  return metadata
}

@Composable
private fun rememberAudioAlbumArt(pathOrUri: String?): Bitmap? =
  rememberAudioPresentationMetadata(pathOrUri)?.artwork

/**
 * Cuboid is a Compose Canvas and does not pass through the GLSurfaceView VisualizerOverlay, so it
 * needs the same scoped Android spectrum capture explicitly. The capture exists only while Cuboid
 * is actually visible; album-art mode, lyrics and modal sheets release it immediately.
 */
@Composable
private fun CuboidSpectrumCaptureEffect(
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
private fun CoverArtCardImage(bitmap: Bitmap?) {
  val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
  if (imageBitmap != null) {
    Image(
      bitmap = imageBitmap,
      contentDescription = null,
      contentScale = ContentScale.Crop,
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
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val duration by PlaybackSession.propInt["duration"].collectAsState()
  val preciseDuration by viewModel.preciseDuration.collectAsState()

  var showInPlaceLyrics by rememberSaveable { mutableStateOf(false) }
  var wasLyricsActiveBeforeLandscape by rememberSaveable { mutableStateOf(false) }
  var isLyricsFullscreen by remember { mutableStateOf(false) }
  var lastUserInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

  val resetInactivityTimer = {
    lastUserInteractionTime = System.currentTimeMillis()
    if (isLyricsFullscreen) {
      isLyricsFullscreen = false
    }
  }

  BackHandler(enabled = isLyricsFullscreen) {
    resetInactivityTimer()
  }

  val currentPath by PlaybackSession.propString["path"].collectAsState()
  val currentStreamFilename by PlaybackSession.propString["stream-open-filename"].collectAsState()
  val mediaPath = currentPath?.takeIf { it.isNotBlank() } ?: currentStreamFilename

  val audioCodec by PlaybackSession.propString["audio-codec-name"].collectAsState()
  val sampleRate by PlaybackSession.propInt["audio-params/samplerate"].collectAsState()
  val audioFormat by PlaybackSession.propString["audio-params/format"].collectAsState()
  val bitsPerSample by PlaybackSession.propString["metadata/by-key/BITS_PER_SAMPLE"].collectAsState()
  val bitsPerSampleAlt by PlaybackSession.propString["metadata/by-key/bits_per_sample"].collectAsState()
  val playbackSpeed by PlaybackSession.propFloat["speed"].collectAsState()

  val isLosslessCodecOrExt =
    remember(audioCodec, mediaPath) {
      val codec = audioCodec?.lowercase().orEmpty()
      val ext = mediaPath?.fileExtension().orEmpty()
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

  LaunchedEffect(mediaPath) {
    showLosslessDetails = false
  }

  val fullLosslessDetailString =
    remember(isHiRes, sampleRate, audioFormat, bitsPerSample, bitsPerSampleAlt, audioCodec, isLosslessCodecOrExt) {
      val baseLabel = if (isHiRes) "HI-RES LOSSLESS" else "LOSSLESS"
      val sr = sampleRate ?: 0
      val khzStr =
        if (sr > 0) {
          val khz = sr / 1000f
          if (sr % 1000 == 0) "${sr / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", khz)
        } else {
          ""
        }

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

      val specsStr =
        when {
          bitStr.isNotBlank() && khzStr.isNotBlank() -> "$bitStr/$khzStr"
          khzStr.isNotBlank() -> khzStr
          bitStr.isNotBlank() -> bitStr
          else -> ""
        }

      val codecName = audioCodec?.uppercase().orEmpty()
      buildString {
        append(baseLabel)
        if (specsStr.isNotBlank()) {
          append(" - ").append(specsStr)
        }
        if (codecName.isNotBlank()) {
          append(" ").append(codecName)
        }
      }
    }

  val currentAudioPresentation = rememberAudioPresentationMetadata(mediaPath)
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
      mediaTitle?.takeIf { it.isNotBlank() }?.stripAudioExtension() ?: "Audio Track",
    )
  }
  LaunchedEffect(mediaTitle) {
    if (!mediaTitle.isNullOrBlank()) {
      lastValidTitle = mediaTitle.stripAudioExtension()
    }
  }

  val context = LocalContext.current
  val rawArtist by PlaybackSession.propString["metadata/by-key/Artist"].collectAsState()
  val rawArtistAlt by PlaybackSession.propString["metadata/artist"].collectAsState()
  val rawAlbumArtist by PlaybackSession.propString["metadata/by-key/album_artist"].collectAsState()
  val rawPerformer by PlaybackSession.propString["metadata/by-key/PERFORMER"].collectAsState()
  val retrievedArtist = currentAudioPresentation?.artist

  val displayArtist =
    remember(rawArtist, rawArtistAlt, rawAlbumArtist, rawPerformer, retrievedArtist) {
      sequenceOf(rawArtist, rawArtistAlt, rawAlbumArtist, rawPerformer, retrievedArtist)
        .filterNotNull()
        .firstOrNull { it.isNotBlank() } ?: "Unknown Artist"
    }

  val audioPreferences = koinInject<AudioPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val audioVisualizerStyle by audioPreferences.audioVisualizerStyle.collectAsState()
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

   val isPlaying = paused == false
   val currentDurSec = if (preciseDuration > 0f) preciseDuration else duration?.toFloat() ?: 0f
   val currentVolumePercent by viewModel.currentVolumePercent.collectAsState()
   val volumeScale = currentVolumePercent / 100f
   val visualizerFeatures = rememberAudioVisualizerFeatures(isPlaying, volumeScale)

  val repeatMode by viewModel.repeatMode.collectAsState()
  val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
  val playlistModeEnabled = viewModel.hasPlaylistSupport()
  val showVisualizer by viewModel.showVisualizerInAudioPlayer.collectAsState()
  val sheetShown by viewModel.sheetShown.collectAsState()
  val isSheetOpen = sheetShown != Sheets.None

  CuboidSpectrumCaptureEffect(
    enabled =
      showVisualizer &&
        !showInPlaceLyrics &&
        !isSheetOpen &&
        audioVisualizerStyle == AudioVisualizerStyle.Cuboid,
    features = visualizerFeatures,
  )

  val abLoop by viewModel.abLoopState.collectAsState()
  val abLoopA = abLoop.a
  val abLoopB = abLoop.b

  var addToPlaylistDialogOpen by rememberSaveable { mutableStateOf(false) }

  val playerPreferences = koinInject<PlayerPreferences>()
  val seekbarStyle by appearancePreferences.seekbarStyle.collectAsState()
  val invertDuration by playerPreferences.invertDuration.collectAsState()
  val showChapterIndicators by playerPreferences.showChapterIndicators.collectAsState()
  val chapters by viewModel.chapters.collectAsState()
  val seekbarChapters =
    remember(chapters, showChapterIndicators) {
      if (showChapterIndicators) chapters.toImmutableList() else persistentListOf()
    }

  LaunchedEffect(Unit) {
    viewModel.refreshPlaylistItems()
  }

  val playlistItems by viewModel.playlistItems.collectAsState()
  val isAudioOnly by viewModel.isAudioOnly.collectAsState()
  val filteredPlaylist =
    remember(playlistItems) {
      playlistItems.filter { it.isAudio }
    }

  val configuration = LocalConfiguration.current
  val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val isTabletLandscape = !isPortrait && isTablet
  val isTabletPortrait = isPortrait && isTablet

  LaunchedEffect(isTabletLandscape) {
    if (isTabletLandscape) {
      if (showInPlaceLyrics) {
        wasLyricsActiveBeforeLandscape = true
        showInPlaceLyrics = false
      }
    } else {
      if (wasLyricsActiveBeforeLandscape) {
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

  val targetTopColor = if (ambientModeEnabled && (!showVisualizer || showInPlaceLyrics)) (ambientColors?.first ?: Color.Transparent) else Color.Transparent
  val targetBottomColor = if (ambientModeEnabled && (!showVisualizer || showInPlaceLyrics)) (ambientColors?.second ?: Color.Transparent) else Color.Transparent

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

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .drawWithCache {
          if (ambientModeEnabled && (!showVisualizer || showInPlaceLyrics) && (animatedAmbientTop != Color.Transparent || animatedAmbientBottom != Color.Transparent)) {
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
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
  ) {
    val headerBar = @Composable {
      Box(modifier = Modifier.fillMaxWidth()) {
        ReactiveIconButton(
          onClick = onBackPress,
          modifier = Modifier.align(Alignment.CenterStart),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.ExpandMore,
            contentDescription = stringResource(R.string.ui_close),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(32.dp),
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
      if (isLosslessCodecOrExt) {
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
                if (isHiRes) "HI-RES LOSSLESS" else "LOSSLESS"
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
    val haptic = LocalHapticFeedback.current
    var activeCoverOverride by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(albumArtBitmap) {
      if (albumArtBitmap != null) {
        activeCoverOverride = null
      }
    }

    val nextItem = remember(filteredPlaylist, mediaPath) {
      val idx = filteredPlaylist.indexOfFirst { it.isPlaying || it.path == mediaPath || it.uri.toString() == mediaPath }
      if (idx in 0 until filteredPlaylist.lastIndex) filteredPlaylist[idx + 1] else null
    }

    val prevItem = remember(filteredPlaylist, mediaPath) {
      val idx = filteredPlaylist.indexOfFirst { it.isPlaying || it.path == mediaPath || it.uri.toString() == mediaPath }
      if (idx > 0) filteredPlaylist[idx - 1] else null
    }

    val nextCoverBitmap = rememberAudioAlbumArt(nextItem?.let { it.path.ifBlank { it.uri.toString() } })
    val prevCoverBitmap = rememberAudioAlbumArt(prevItem?.let { it.path.ifBlank { it.uri.toString() } })

    @OptIn(ExperimentalFoundationApi::class)
    val centerVisualizerView = @Composable { visualizerModifier: Modifier ->
      BoxWithConstraints(
        modifier =
          visualizerModifier
            .combinedClickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = { viewModel.toggleAudioVisualizer() },
              onLongClick = { onOpenSheet(Sheets.VisualizerStyle) },
            ),
        contentAlignment = Alignment.Center,
      ) {
        val containerWidthPx = constraints.maxWidth.toFloat()
        val currentOffset = animatableOffsetX.value

        if (showInPlaceLyrics && !isTabletLandscape) {
          app.gyrolet.mpvrx.ui.player.controls.components.LyricsView(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            isLyricsFullscreen = isLyricsFullscreen,
            onTap = resetInactivityTimer,
          )
        } else {
          AnimatedContent(
            targetState = showVisualizer,
            transitionSpec = {
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
            },
            label = "visualizer_toggle",
            modifier = Modifier.fillMaxHeight().fillMaxWidth(if (isTabletPortrait && !showVisualizer) 0.65f else 1.0f),
          ) { isVisualizerActive ->
          if (isVisualizerActive) {
            val surfaceBg = MaterialTheme.colorScheme.surface
            Box(
              modifier =
                Modifier
                  .fillMaxSize()
                  .drawWithContent {
                    drawContent()
                    // Immersive top & bottom soft gradient vignetting so visualizer seamlessly dissolves into dark background
                    drawRect(
                      brush =
                        Brush.verticalGradient(
                          colors = listOf(
                            surfaceBg.copy(alpha = 0.95f),
                            surfaceBg.copy(alpha = 0.45f),
                            Color.Transparent,
                          ),
                          startY = 0f,
                          endY = size.height * 0.22f,
                        ),
                    )
                    drawRect(
                      brush =
                        Brush.verticalGradient(
                          colors = listOf(
                            Color.Transparent,
                            surfaceBg.copy(alpha = 0.50f),
                            surfaceBg.copy(alpha = 0.96f),
                          ),
                          startY = size.height * 0.72f,
                          endY = size.height,
                        ),
                    )
                    // Subtle side edge softening
                    drawRect(
                      brush =
                        Brush.horizontalGradient(
                          colors = listOf(
                            surfaceBg.copy(alpha = 0.60f),
                            Color.Transparent,
                            Color.Transparent,
                            surfaceBg.copy(alpha = 0.60f),
                          ),
                          startX = 0f,
                          endX = size.width,
                        ),
                    )
                  },
              contentAlignment = Alignment.Center,
            ) {
              val glowColor = Color(palette.primary).copy(alpha = 0.16f)
              Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                  brush =
                    Brush.radialGradient(
                      colors = listOf(glowColor, Color.Transparent),
                      center = center,
                      radius = size.minDimension * 0.90f,
                    ),
                )
              }

              when (audioVisualizerStyle) {
                AudioVisualizerStyle.Galaxy ->
                  GalaxyOverlay(
                    palette = palette,
                    isSheetOpen = isSheetOpen,
                    volumeScale = volumeScale,
                    features = visualizerFeatures,
                    modifier = Modifier.fillMaxSize(),
                  )
                AudioVisualizerStyle.Blob ->
                  BlobOverlay(
                    palette = palette,
                    isSheetOpen = isSheetOpen,
                    volumeScale = volumeScale,
                    features = visualizerFeatures,
                    modifier = Modifier.fillMaxSize(),
                  )
                AudioVisualizerStyle.Cuboid ->
                  if (!isSheetOpen) {
                    CuboidOverlay(
                      isPlaying = isPlaying,
                      palette = palette,
                      isSheetOpen = false,
                      volumeScale = volumeScale,
                      features = visualizerFeatures,
                      modifier = Modifier.fillMaxSize(),
                    )
                  }
                AudioVisualizerStyle.Particle ->
                  ParticleOverlay(
                    palette = palette,
                    isSheetOpen = isSheetOpen,
                    volumeScale = volumeScale,
                    features = visualizerFeatures,
                    modifier = Modifier.fillMaxSize(),
                  )
              }
            }
          } else {
            val coverShape = RoundedCornerShape(32.dp)
            val density = LocalDensity.current
            val gap = with(density) { 24.dp.toPx() }
            val stride = containerWidthPx + gap

            Box(
              modifier = Modifier
                .fillMaxSize()
                .pointerInput(showVisualizer, containerWidthPx) {
                  if (showVisualizer || containerWidthPx <= 0f) return@pointerInput
                  detectHorizontalDragGestures(
                    onDragStart = {
                      coroutineScope.launch { animatableOffsetX.snapTo(0f) }
                    },
                    onDragEnd = {
                      val threshold = containerWidthPx * 0.25f
                      val dragVal = animatableOffsetX.value
                      coroutineScope.launch {
                        if (dragVal < -threshold) {
                          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                  CoverArtCardImage(bitmap = prevCoverBitmap)
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
                  CoverArtCardImage(bitmap = nextCoverBitmap)
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
                CoverArtCardImage(bitmap = activeCoverOverride ?: albumArtBitmap)
              }
            }
          }
        }
      }
    }
    }

    val trackMetadataView = @Composable {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
      ) {
        val displayTitle =
          remember(lastValidTitle, displayArtist) {
            cleanSongTitle(lastValidTitle, displayArtist)
          }

        // 1. Song Title Only
        Text(
          text = displayTitle,
          style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
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
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
        )
        Spacer(modifier = Modifier.height(2.dp))

        // 3. Track Info | A-B Loop Control
        val playlistInfo = viewModel.getPlaylistInfo()
        val trackText = if (playlistInfo != null) "Track $playlistInfo" else "Audio Media"

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = trackText,
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
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
                  .clickable(onClick = { onOpenSheet(Sheets.PlaybackSpeed) }),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 10.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Speed,
                  contentDescription = stringResource(R.string.ui_playback_speed),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(16.dp),
                )
                Text(
                  text = String.format("%.2fx", playbackSpeed ?: 1f),
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    val seekbarView = @Composable {
      val position by PlaybackSession.propInt["time-pos"].collectAsStateWithLifecycle()
      val precisePosition by viewModel.precisePosition.collectAsStateWithLifecycle()
      val currentPosSec = if (precisePosition > 0f) precisePosition else position?.toFloat() ?: 0f

      SeekbarWithTimers(
        position = currentPosSec,
        committedPosition = currentPosSec,
        duration = currentDurSec.coerceAtLeast(1f),
        onValueChangeStarted = viewModel::beginLegacySeekPreview,
        onValueChange = { value ->
          viewModel.updateLegacySeekPreview(value.toDouble(), currentDurSec.toDouble())
        },
        onValueChangeFinished = { targetPosition ->
          viewModel.commitLegacySeekPreview(targetPosition.toDouble(), currentDurSec.toDouble())
        },
        timersInverted = Pair(false, invertDuration),
        durationTimerOnCLick = { playerPreferences.invertDuration.set(!invertDuration) },
        positionTimerOnClick = {},
        chapters = seekbarChapters,
        skipSegments = persistentListOf(),
        paused = paused ?: false,
        seekbarStyle = seekbarStyle,
        loopStart = abLoopA?.toFloat(),
        loopEnd = abLoopB?.toFloat(),
        isPortrait = isPortrait,
        applyHorizontalPadding = false,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    val playbackControlsRow = @Composable {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ReactiveIconButton(onClick = { viewModel.playPrevious() }, enabled = playlistModeEnabled) {
          Icon(
            imageVector = Icons.RoundedFilled.SkipPrevious,
            contentDescription = null,
            tint =
              if (playlistModeEnabled) {
                MaterialTheme.colorScheme.onSurface
              } else {
                MaterialTheme.colorScheme.onSurface
                  .copy(
                    alpha = 0.38f,
                  )
              },
            modifier = Modifier.size(28.dp),
          )
        }
        ReactiveIconButton(onClick = { viewModel.seekBy(-30) }) {
          Icon(
            imageVector = Icons.RoundedFilled.FastRewind,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(34.dp),
          )
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
        ReactiveIconButton(onClick = { viewModel.seekBy(30) }) {
          Icon(
            imageVector = Icons.RoundedFilled.FastForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(34.dp),
          )
        }
        ReactiveIconButton(onClick = { viewModel.playNext() }, enabled = playlistModeEnabled) {
          Icon(
            imageVector = Icons.RoundedFilled.SkipNext,
            contentDescription = null,
            tint =
              if (playlistModeEnabled) {
                MaterialTheme.colorScheme.onSurface
              } else {
                MaterialTheme.colorScheme.onSurface
                  .copy(
                    alpha = 0.38f,
                  )
              },
            modifier = Modifier.size(28.dp),
          )
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
              tint = MaterialTheme.colorScheme.onSurface,
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
              ReactiveIconButton(
                onClick = viewModel::toggleShuffle,
                enabled = playlistModeEnabled,
                modifier = Modifier.size(40.dp),
              ) {
                Icon(
                  imageVector = if (shuffleEnabled) Icons.RoundedFilled.ShuffleOn else Icons.RoundedFilled.Shuffle,
                  contentDescription = null,
                  tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              ReactiveIconButton(
                onClick = viewModel::cycleRepeatMode,
                modifier = Modifier.size(40.dp),
              ) {
                Icon(
                  imageVector =
                    when (repeatMode) {
                      RepeatMode.OFF -> Icons.RoundedFilled.Repeat
                      RepeatMode.ONE -> Icons.RoundedFilled.RepeatOne
                      RepeatMode.ALL -> Icons.RoundedFilled.RepeatOn
                    },
                  contentDescription = null,
                  tint =
                    if (repeatMode !=
                      RepeatMode.OFF
                    ) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
              }
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
              ReactiveIconButton(
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
              ReactiveIconButton(
                onClick = viewModel::toggleShuffle,
                enabled = playlistModeEnabled,
                modifier = Modifier.size(40.dp),
              ) {
                Icon(
                  imageVector = if (shuffleEnabled) Icons.RoundedFilled.ShuffleOn else Icons.RoundedFilled.Shuffle,
                  contentDescription = null,
                  tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              ReactiveIconButton(
                onClick = viewModel::cycleRepeatMode,
                modifier = Modifier.size(40.dp),
              ) {
                Icon(
                  imageVector =
                    when (repeatMode) {
                      RepeatMode.OFF -> Icons.RoundedFilled.Repeat
                      RepeatMode.ONE -> Icons.RoundedFilled.RepeatOne
                      RepeatMode.ALL -> Icons.RoundedFilled.RepeatOn
                    },
                  contentDescription = null,
                  tint =
                    if (repeatMode !=
                      RepeatMode.OFF
                    ) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
              }
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
              ReactiveIconButton(
                onClick = { showInPlaceLyrics = !showInPlaceLyrics },
                modifier = Modifier.size(40.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Lyrics,
                  contentDescription = "Lyrics",
                  tint = if (showInPlaceLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              ReactiveIconButton(
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
                contentDescription = "Playlist",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
              )
            }
          }
        }
      }
    }

    val isTabletPortrait = isPortrait && (isTablet || configuration.screenWidthDp >= 600)

    if (isPortrait) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) { resetInactivityTimer() },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        androidx.compose.animation.AnimatedVisibility(
          visible = !isLyricsFullscreen,
          enter = fadeIn(animationSpec = tween(300)) + androidx.compose.animation.expandVertically(animationSpec = tween(300)),
          exit = fadeOut(animationSpec = tween(300)) + androidx.compose.animation.shrinkVertically(animationSpec = tween(300)),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            headerBar()
            losslessBadge()
            Spacer(modifier = Modifier.height(16.dp))
          }
        }

        val visualizerModifier = Modifier.weight(1f).fillMaxWidth()
        centerVisualizerView(visualizerModifier)

        androidx.compose.animation.AnimatedVisibility(
          visible = !isLyricsFullscreen,
          enter = fadeIn(animationSpec = tween(300)) + androidx.compose.animation.expandVertically(animationSpec = tween(300)),
          exit = fadeOut(animationSpec = tween(300)) + androidx.compose.animation.shrinkVertically(animationSpec = tween(300)),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(16.dp))
            trackMetadataView()
            Spacer(modifier = Modifier.height(16.dp))
            seekbarView()
            Spacer(modifier = Modifier.height(16.dp))
            playbackControlsRow()
            Spacer(modifier = Modifier.height(24.dp))
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
          headerBar()
          Spacer(modifier = Modifier.height(4.dp))
          losslessBadge()
          Spacer(modifier = Modifier.height(6.dp))
          centerVisualizerView(
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .padding(vertical = 12.dp, horizontal = 24.dp),
          )
          Spacer(modifier = Modifier.height(6.dp))
          trackMetadataView()
          Spacer(modifier = Modifier.height(8.dp))
          seekbarView()
          Spacer(modifier = Modifier.height(8.dp))
          playbackControlsRow()
          Spacer(modifier = Modifier.height(12.dp))
          bottomActionRow()
        }

        Surface(
          modifier = Modifier
            .weight(1.1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp)),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          shape = RoundedCornerShape(24.dp),
        ) {
          DualPaneSidePanel(
            viewModel = viewModel,
            playlist = filteredPlaylist,
            initialLyricsActive = wasLyricsActiveBeforeLandscape,
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
          modifier = Modifier.weight(1.2f).fillMaxHeight(),
          verticalArrangement = Arrangement.SpaceBetween,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          headerBar()
          losslessBadge()
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
            duration = duration?.toLong() ?: 0L,
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

      AddToPlaylistDialog(
        isOpen = true,
        videos = listOf(videoForPlaylist),
        onDismiss = { addToPlaylistDialogOpen = false },
        onSuccess = { addToPlaylistDialogOpen = false },
      )
    }
  }
}

@Composable
private fun DualPaneSidePanel(
  viewModel: PlayerViewModel,
  playlist: List<PlaylistItem>,
  initialLyricsActive: Boolean = false,
) {
  var selectedTab by remember(initialLyricsActive) { mutableIntStateOf(if (initialLyricsActive) 1 else 0) }

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
        onClick = { selectedTab = 0 },
        label = { Text(stringResource(R.string.player_up_next_title), fontWeight = FontWeight.Bold) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
          selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
      )
      androidx.compose.material3.FilterChip(
        selected = selectedTab == 1,
        onClick = { selectedTab = 1 },
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

  var displayPlaylist by remember(playlist) { mutableStateOf(playlist) }
  LaunchedEffect(playlist) {
    displayPlaylist = playlist
  }

  val showDragHandle = !isM3U && displayPlaylist.size > 1

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

  val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
    if (showDragHandle) {
      if (dragStartIndex == -1) {
        dragStartIndex = from.index
      }
      dragEndIndex = to.index
      displayPlaylist = displayPlaylist.toMutableList().apply {
        add(to.index, removeAt(from.index))
      }
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
        items(displayPlaylist.size, key = { index -> displayPlaylist[index].uri.toString() }) { index ->
          val item = displayPlaylist[index]
          if (showDragHandle) {
            ReorderableItem(reorderableLazyListState, key = item.uri.toString()) { isDragging ->
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
) {
  val bgColor = if (isPlaying) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
  } else {
    MaterialTheme.colorScheme.surfaceContainer
  }

  val itemCoverArt = rememberAudioAlbumArt(item.path.ifBlank { item.uri.toString() })

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    color = bgColor,
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
              .draggableHandle()
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
          if (itemImageBitmap != null) {
            Image(
              bitmap = itemImageBitmap,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
            if (isPlaying) {
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Equalizer,
                  contentDescription = "Now Playing",
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(22.dp),
                )
              }
            }
          } else {
            if (isPlaying) {
              Icon(
                imageVector = Icons.RoundedFilled.Equalizer,
                contentDescription = "Now Playing",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
              )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactiveIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val haptic = LocalHapticFeedback.current

  val scale by animateFloatAsState(
    targetValue = if (isPressed) 0.82f else 1f,
    animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
    label = "reactive_icon_button_scale",
  )

  if (onLongClick != null) {
    Box(
      modifier =
        modifier
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
          }
          .clip(CircleShape)
          .combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = false, radius = 24.dp),
            enabled = enabled,
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onClick()
            },
            onLongClick = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onLongClick()
            },
          )
          .padding(8.dp),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  } else {
    IconButton(
      onClick = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
      },
      enabled = enabled,
      interactionSource = interactionSource,
      modifier =
        modifier.graphicsLayer {
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
  val haptic = LocalHapticFeedback.current

  val scale by animateFloatAsState(
    targetValue = if (isPressed) 0.88f else 1f,
    animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
    label = "reactive_surface_button_scale",
  )

  Surface(
    onClick = {
      haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
      onClick()
    },
    shape = shape,
    color = color,
    shadowElevation = shadowElevation,
    enabled = enabled,
    interactionSource = interactionSource,
    modifier =
      modifier.graphicsLayer {
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
