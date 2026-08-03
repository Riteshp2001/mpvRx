/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls

import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.PlaylistItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.gyrolet.mpvrx.ui.player.visualizer.BlobOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.CuboidOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.GalaxyOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.ParticleOverlay
import app.gyrolet.mpvrx.ui.player.visualizer.VisualizerPalette

import app.gyrolet.mpvrx.ui.theme.AppTheme
import app.gyrolet.mpvrx.ui.theme.DarkMode
import `is`.xyz.mpv.MPVLib
import app.gyrolet.mpvrx.utils.media.fileExtension
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Locale

@Composable
private fun rememberAudioAlbumArt(pathOrUri: String?): Bitmap? {
  val context = LocalContext.current
  var bitmap by remember(pathOrUri) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(pathOrUri) {
    if (pathOrUri.isNullOrBlank()) {
      bitmap = null
      return@LaunchedEffect
    }
    withContext(Dispatchers.IO) {
      runCatching {
        val cleanPath =
          when {
            pathOrUri.startsWith("file://") -> pathOrUri.removePrefix("file://")
            pathOrUri.startsWith("content://") -> null
            else -> pathOrUri
          }
        val retriever = MediaMetadataRetriever()
        if (cleanPath != null) {
          retriever.setDataSource(cleanPath)
        } else {
          retriever.setDataSource(context, Uri.parse(pathOrUri))
        }
        val art = EmbeddedArtworkResolver.decodeEmbeddedArtwork(cleanPath, retriever)
        retriever.release()
        art
      }.onSuccess { loadedBitmap ->
        bitmap = loadedBitmap
      }.onFailure {
        bitmap = null
      }
    }
  }
  return bitmap
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
  val paused by MPVLib.propBoolean["pause"].collectAsState()
  val duration by MPVLib.propInt["duration"].collectAsState()
  val position by MPVLib.propInt["time-pos"].collectAsState()
  val precisePosition by viewModel.precisePosition.collectAsState()
  val preciseDuration by viewModel.preciseDuration.collectAsState()

  val currentPath by MPVLib.propString["path"].collectAsState()
  val currentStreamFilename by MPVLib.propString["stream-open-filename"].collectAsState()
  val mediaPath = currentPath?.takeIf { it.isNotBlank() } ?: currentStreamFilename

  val audioCodec by MPVLib.propString["audio-codec-name"].collectAsState()
  val sampleRate by MPVLib.propInt["audio-params/samplerate"].collectAsState()

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

  val albumArtBitmap = rememberAudioAlbumArt(mediaPath)

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
  val rawArtist by MPVLib.propString["metadata/by-key/Artist"].collectAsState()
  val rawArtistAlt by MPVLib.propString["metadata/artist"].collectAsState()
  val rawAlbumArtist by MPVLib.propString["metadata/by-key/album_artist"].collectAsState()
  val rawPerformer by MPVLib.propString["metadata/by-key/PERFORMER"].collectAsState()

  var retrievedArtist by remember(mediaPath) { mutableStateOf<String?>(null) }
  LaunchedEffect(mediaPath) {
    if (!mediaPath.isNullOrBlank()) {
      withContext(Dispatchers.IO) {
        runCatching {
          val retriever = MediaMetadataRetriever()
          if (mediaPath.startsWith("content://")) {
            retriever.setDataSource(context, Uri.parse(mediaPath))
          } else {
            retriever.setDataSource(mediaPath.removePrefix("file://"))
          }
          val art =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
              ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
              ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
              ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
          retriever.release()
          art
        }.getOrNull()?.let { retrievedArtist = it }
      }
    }
  }

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
  val appTheme by appearancePreferences.appTheme.collectAsState()
  val darkMode by appearancePreferences.darkMode.collectAsState()
  val amoledMode by appearancePreferences.amoledMode.collectAsState()
  val useDarkTheme =
    when (darkMode) {
      DarkMode.Dark -> true
      DarkMode.Light -> false
      DarkMode.System -> isSystemInDarkTheme()
    }
  val colorScheme = MaterialTheme.colorScheme
  val palette =
    remember(appTheme, useDarkTheme, amoledMode, colorScheme) {
      if (appTheme == AppTheme.Dynamic) {
        VisualizerPalette(
          background = colorScheme.surface.toArgb(),
          primary = colorScheme.primary.toArgb(),
          secondary = colorScheme.secondary.toArgb(),
          tertiary = colorScheme.tertiary.toArgb(),
        )
      } else {
        appTheme
          .toVisualizerPalette(useDarkTheme = useDarkTheme, amoledMode = amoledMode)
          .copy(background = colorScheme.surface.toArgb())
      }
    }

  val isPlaying = paused == false
  val currentPosSec = if (precisePosition > 0f) precisePosition else position?.toFloat() ?: 0f
  val currentDurSec = if (preciseDuration > 0f) preciseDuration else duration?.toFloat() ?: 0f

  val repeatMode by viewModel.repeatMode.collectAsState()
  val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
  val playlistModeEnabled = viewModel.hasPlaylistSupport()
  val showVisualizer by viewModel.showVisualizerInAudioPlayer.collectAsState()
  val sheetShown by viewModel.sheetShown.collectAsState()
  val isSheetOpen = sheetShown != Sheets.None

  val abLoop by viewModel.abLoopState.collectAsState()
  val abLoopA = abLoop.a
  val abLoopB = abLoop.b

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
    remember(playlistItems, isAudioOnly) {
      val audioOnly = playlistItems.filter { it.isAudio }
      if (audioOnly.isNotEmpty()) {
        audioOnly
      } else {
        playlistItems
      }
    }

  val configuration = LocalConfiguration.current
  val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val isTabletLandscape = !isPortrait && isTablet

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
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
        ) {
          Text(
            text = if (isHiRes) "HI-RES LOSSLESS" else "LOSSLESS",
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

    @OptIn(ExperimentalFoundationApi::class)
    val centerVisualizerView = @Composable { visualizerModifier: Modifier ->
      BoxWithConstraints(
        modifier =
          visualizerModifier
            .clipToBounds()
            .combinedClickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = { viewModel.toggleAudioVisualizer() },
              onLongClick = { onOpenSheet(Sheets.VisualizerStyle) },
            ),
        contentAlignment = Alignment.Center,
      ) {
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
          modifier = Modifier.fillMaxSize(),
        ) { isVisualizerActive ->
          if (isVisualizerActive) {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              when (audioVisualizerStyle) {
                AudioVisualizerStyle.Galaxy ->
                  GalaxyOverlay(
                    isPlaying = isPlaying,
                    palette = palette,
                    isSheetOpen = isSheetOpen,
                    modifier = Modifier.fillMaxSize(),
                  )
                AudioVisualizerStyle.Blob ->
                  BlobOverlay(
                    isPlaying = isPlaying,
                    palette = palette,
                    isSheetOpen = isSheetOpen,
                    modifier = Modifier.fillMaxSize(),
                  )
                AudioVisualizerStyle.Cuboid ->
                  CuboidOverlay(
                    isPlaying = isPlaying,
                    palette = palette,
                    isSheetOpen = isSheetOpen,
                    modifier = Modifier.fillMaxSize(),
                  )
                AudioVisualizerStyle.Particle ->
                  ParticleOverlay(
                    isPlaying = isPlaying,
                    palette = palette,
                    isSheetOpen = isSheetOpen,
                    modifier = Modifier.fillMaxSize(),
                  )
              }

            }
          } else {
            val coverShape = RoundedCornerShape(32.dp)
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              Surface(
                modifier =
                  Modifier
                    .aspectRatio(1f)
                    .clip(coverShape),
                shape = coverShape,
                color = Color.Transparent,
              ) {
                Crossfade(
                  targetState = albumArtBitmap,
                  animationSpec = tween(300),
                  label = "cover_crossfade",
                  modifier = Modifier.fillMaxSize(),
                ) { currentBitmap ->
                  if (currentBitmap != null) {
                    Image(
                      bitmap = currentBitmap.asImageBitmap(),
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
      }
    }

    val seekbarView = @Composable {
      SeekbarWithTimers(
        position = currentPosSec,
        committedPosition = currentPosSec,
        duration = currentDurSec.coerceAtLeast(1f),
        onValueChange = { value -> viewModel.seekTo(value.toInt(), fast = true) },
        onValueChangeFinished = { targetPosition -> viewModel.seekTo(targetPosition.toInt(), fast = false) },
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
        horizontalArrangement = Arrangement.SpaceBetween,
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
        Row(
          modifier =
            Modifier
              .clip(
                RoundedCornerShape(50),
              ).background(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
              ).padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ReactiveIconButton(onClick = viewModel::toggleShuffle, enabled = playlistModeEnabled) {
            Icon(
              imageVector = if (shuffleEnabled) Icons.RoundedFilled.ShuffleOn else Icons.RoundedFilled.Shuffle,
              contentDescription = null,
              tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          ReactiveIconButton(onClick = viewModel::cycleRepeatMode) {
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
          ) {
            Icon(
              imageVector = if (showVisualizer) Icons.RoundedFilled.AutoAwesome else Icons.RoundedFilled.Audiotrack,
              contentDescription = null,
              tint = if (showVisualizer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          ReactiveIconButton(onClick = {
            val act = context as? PlayerActivity
            if (act != null) {
              act.toggleAudioBackgroundPlayback()
            } else {
              audioPreferences.audioBackgroundPlayback.set(!backgroundPlaybackEnabled)
            }
          }) {
            Icon(
              imageVector = Icons.RoundedFilled.Headset,
              contentDescription = stringResource(R.string.btn_label_background_playback),
              tint = if (backgroundPlaybackEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        if (!isTabletLandscape) {
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
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        headerBar()
        losslessBadge()
        Spacer(modifier = Modifier.height(16.dp))
        val visualizerModifier =
          if (isTabletPortrait) {
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .padding(horizontal = 48.dp, vertical = 12.dp)
          } else {
            Modifier.weight(1f).fillMaxWidth()
          }
        centerVisualizerView(visualizerModifier)
        Spacer(modifier = Modifier.height(16.dp))
        trackMetadataView()
        Spacer(modifier = Modifier.height(16.dp))
        seekbarView()
        Spacer(modifier = Modifier.height(16.dp))
        playbackControlsRow()
        Spacer(modifier = Modifier.height(24.dp))
        bottomActionRow()
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
  }
}

@Composable
private fun DualPaneSidePanel(
  viewModel: PlayerViewModel,
  playlist: List<PlaylistItem>,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
  ) {
    UpNextPlaylistContent(
      viewModel = viewModel,
      playlist = playlist,
    )
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
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
          if (itemCoverArt != null) {
            Image(
              bitmap = itemCoverArt.asImageBitmap(),
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
