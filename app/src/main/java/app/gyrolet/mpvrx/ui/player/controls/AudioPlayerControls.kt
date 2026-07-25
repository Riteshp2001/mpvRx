package app.gyrolet.mpvrx.ui.player.controls

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.AudioVisualizerStyle
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.RepeatMode
import app.gyrolet.mpvrx.ui.player.Sheets
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import app.gyrolet.mpvrx.ui.player.controls.components.AbLoopIcon
import app.gyrolet.mpvrx.ui.player.controls.components.SeekbarWithTimers
import app.gyrolet.mpvrx.ui.player.visualizer.BlobOverlay
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.ui.player.visualizer.GalaxyOverlay
import app.gyrolet.mpvrx.ui.theme.DarkMode
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Locale

import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver

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
        val cleanPath = when {
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

  val isLosslessCodecOrExt = remember(audioCodec, mediaPath) {
    val codec = audioCodec?.lowercase().orEmpty()
    val ext = mediaPath?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('.', "")?.lowercase().orEmpty()
    codec.contains("flac") ||
      codec.contains("alac") ||
      codec.contains("pcm") ||
      codec.contains("wavpack") ||
      codec.contains("ape") ||
      codec.contains("dsd") ||
      codec.contains("tak") ||
      ext in setOf("flac", "wav", "aiff", "aif", "alac", "ape", "dsf", "dff")
  }

  val isHiRes = remember(sampleRate, isLosslessCodecOrExt) {
    isLosslessCodecOrExt && (sampleRate ?: 0) >= 88200
  }

  val albumArtBitmap = rememberAudioAlbumArt(mediaPath)

  var lastValidTitle by remember { mutableStateOf(mediaTitle?.takeIf { it.isNotBlank() } ?: "Audio Track") }
  LaunchedEffect(mediaTitle) {
    if (!mediaTitle.isNullOrBlank()) {
      lastValidTitle = mediaTitle
    }
  }

  val audioPreferences = koinInject<AudioPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val audioVisualizerStyle by audioPreferences.audioVisualizerStyle.collectAsState()
  val appTheme by appearancePreferences.appTheme.collectAsState()
  val darkMode by appearancePreferences.darkMode.collectAsState()
  val amoledMode by appearancePreferences.amoledMode.collectAsState()
  val useDarkTheme = when (darkMode) {
    DarkMode.Dark -> true
    DarkMode.Light -> false
    DarkMode.System -> isSystemInDarkTheme()
  }
  val palette = remember(appTheme, useDarkTheme) {
    appTheme.toVisualizerPalette(useDarkTheme = true, amoledMode = true)
  }

  val isPlaying = paused == false
  val currentPosSec = if (precisePosition > 0f) precisePosition else position?.toFloat() ?: 0f
  val currentDurSec = if (preciseDuration > 0f) preciseDuration else duration?.toFloat() ?: 0f

  val repeatMode by viewModel.repeatMode.collectAsState()
  val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
  val playlistModeEnabled = viewModel.hasPlaylistSupport()
  val showVisualizer by viewModel.showVisualizerInAudioPlayer.collectAsState()

  val abLoop by viewModel.abLoopState.collectAsState()
  val abLoopA = abLoop.a
  val abLoopB = abLoop.b

  val playerPreferences = koinInject<PlayerPreferences>()
  val seekbarStyle by appearancePreferences.seekbarStyle.collectAsState()
  val invertDuration by playerPreferences.invertDuration.collectAsState()
  val showChapterIndicators by playerPreferences.showChapterIndicators.collectAsState()
  val chapters by viewModel.chapters.collectAsState()
  val seekbarChapters = remember(chapters, showChapterIndicators) {
    if (showChapterIndicators) chapters.toImmutableList() else persistentListOf()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surface)
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(horizontal = 24.dp, vertical = 16.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // 1. Top Header Bar (Close on left, NOW PLAYING center, Info on top-right)
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp)
      ) {
        IconButton(
          onClick = onBackPress,
          modifier = Modifier.align(Alignment.CenterStart)
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.ExpandMore,
            contentDescription = stringResource(R.string.ui_close),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(32.dp)
          )
        }

        Text(
          text = stringResource(R.string.ui_now_playing),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          letterSpacing = 2.sp,
          modifier = Modifier.align(Alignment.Center)
        )

        Row(
          modifier = Modifier.align(Alignment.CenterEnd),
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(
            onClick = { onOpenSheet(Sheets.AudioProperties) }
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Info,
              contentDescription = stringResource(R.string.player_sheets_more_title),
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(28.dp)
            )
          }
        }
      }

      if (isLosslessCodecOrExt) {
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
          shape = RoundedCornerShape(4.dp),
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
          border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
          Text(
            text = if (isHiRes) "HI-RES LOSSLESS" else "LOSSLESS",
            style = MaterialTheme.typography.labelSmall.copy(
              fontWeight = FontWeight.Bold,
              fontSize = 8.5.sp,
              letterSpacing = 0.8.sp
            ),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // 2. Center View (Album Art Box OR Dynamic Visualizer Overlay)
      BoxWithConstraints(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth(),
        contentAlignment = Alignment.Center
      ) {
        val maxW = maxWidth
        val maxH = maxHeight
        val visualizerScale = remember(maxW, maxH) {
          (maxH / maxW.coerceAtLeast(1.dp)).coerceIn(1.20f, 1.50f)
        }

        AnimatedContent(
          targetState = showVisualizer,
          transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
          label = "visualizer_toggle",
          modifier = Modifier.fillMaxSize(),
        ) { isVisualizerActive ->
          if (isVisualizerActive) {
            // Themed background layer — adapts to any theme, sits behind the GL surface
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            )
            // Dynamic, larger visualizer scaling dynamically according to screen size
            Box(
              modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                  scaleX = visualizerScale
                  scaleY = visualizerScale
                },
              contentAlignment = Alignment.Center
            ) {
              when (audioVisualizerStyle) {
                AudioVisualizerStyle.Galaxy -> GalaxyOverlay(
                  isPlaying = isPlaying,
                  palette = palette,
                  modifier = Modifier.fillMaxSize()
                )
                AudioVisualizerStyle.Blob -> BlobOverlay(
                  isPlaying = isPlaying,
                  palette = palette,
                  modifier = Modifier.fillMaxSize()
                )
              }
            }
          } else {
            // Album Art Box Container
            val coverShape = RoundedCornerShape(32.dp)
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center
            ) {
              Surface(
                modifier = Modifier
                  .aspectRatio(1f)
                  .shadow(
                    elevation = 24.dp,
                    shape = coverShape,
                    spotColor = MaterialTheme.colorScheme.scrim
                  )
                  .clip(coverShape),
                shape = coverShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
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
                      modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                          scaleX = 1.05f
                          scaleY = 1.05f
                        }
                    )
                  } else {
                    Box(
                      modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        imageVector = Icons.RoundedFilled.Audiotrack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(64.dp)
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Track Title & Metadata
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
      ) {
        val titleText = lastValidTitle
        Text(
          text = titleText,
          style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
        )
        Spacer(modifier = Modifier.height(4.dp))
        val playlistInfo = viewModel.getPlaylistInfo()
        val trackText = if (playlistInfo != null) "Track $playlistInfo" else "Audio Media"

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = trackText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )

          Text(
            text = "|",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
          )

          // A-B Loop Control from video player
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
                  color = if (abLoopA != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                  modifier = Modifier
                    .height(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { viewModel.setLoopA() }),
                ) {
                  Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                    Text(
                      text = if (abLoopA != null) formatSec(abLoopA.toLong()) else "A",
                      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                      color = if (abLoopA != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }

                Surface(
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.surfaceVariant,
                  modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = {
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
                  color = if (abLoopB != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                  modifier = Modifier
                    .height(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { viewModel.setLoopB() }),
                ) {
                  Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                    Text(
                      text = if (abLoopB != null) formatSec(abLoopB.toLong()) else "B",
                      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                      color = if (abLoopB != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            } else {
              Surface(
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier
                  .clip(CircleShape)
                  .clickable(onClick = viewModel::toggleABLoopExpanded)
              ) {
                AbLoopIcon(
                  modifier = Modifier.size(30.dp),
                  tint = if (abLoopA != null || abLoopB != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  isASet = abLoopA != null,
                  isBSet = abLoopB != null,
                )
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // 3. Audio Transport Controls (Video player seekbar component & style)
      SeekbarWithTimers(
        position = currentPosSec,
        committedPosition = currentPosSec,
        duration = currentDurSec.coerceAtLeast(1f),
        onValueChange = { value ->
          viewModel.seekTo(value.toInt(), fast = true)
        },
        onValueChangeFinished = { targetPosition ->
          viewModel.seekTo(targetPosition.toInt(), fast = false)
        },
        timersInverted = Pair(false, invertDuration),
        durationTimerOnCLick = {
          playerPreferences.invertDuration.set(!invertDuration)
        },
        positionTimerOnClick = {},
        chapters = seekbarChapters,
        skipSegments = persistentListOf(),
        paused = paused ?: false,
        seekbarStyle = seekbarStyle,
        loopStart = abLoopA?.toFloat(),
        loopEnd = abLoopB?.toFloat(),
        isPortrait = true,
        applyHorizontalPadding = false,
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(modifier = Modifier.height(16.dp))

      // Main Playback Controls Row (Skip Previous, Seek -30s, Play/Pause, Seek +30s, Skip Next)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(
          onClick = { viewModel.playPrevious() },
          enabled = playlistModeEnabled
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.SkipPrevious,
            contentDescription = null,
            tint = if (playlistModeEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.size(28.dp)
          )
        }

        IconButton(onClick = { viewModel.seekBy(-30) }) {
          Icon(
            imageVector = Icons.RoundedFilled.FastRewind,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(34.dp)
          )
        }

        Surface(
          onClick = { viewModel.pauseUnpause() },
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(76.dp),
          shadowElevation = 8.dp,
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = if (isPlaying) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.size(38.dp)
            )
          }
        }

        IconButton(onClick = { viewModel.seekBy(30) }) {
          Icon(
            imageVector = Icons.RoundedFilled.FastForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(34.dp)
          )
        }

        IconButton(
          onClick = { viewModel.playNext() },
          enabled = playlistModeEnabled
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.SkipNext,
            contentDescription = null,
            tint = if (playlistModeEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.size(28.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(24.dp))

      // 4. Bottom Action Row (Equalizer on Left, Center Pill Bar, Playlist on Right)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        // Bottom Left: Equalizer Button
        IconButton(
          onClick = { onOpenSheet(Sheets.Equalizer) },
          modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .size(48.dp)
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = Icons.RoundedFilled.Equalizer,
              contentDescription = "Equalizer",
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(24.dp)
            )
          }
        }

        // Center Pill Bar (Shuffle, Repeat, Visualizer)
        Row(
          modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          IconButton(
            onClick = viewModel::toggleShuffle,
            enabled = playlistModeEnabled
          ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
              Icon(
                imageVector = if (shuffleEnabled) Icons.RoundedFilled.ShuffleOn else Icons.RoundedFilled.Shuffle,
                contentDescription = null,
                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          IconButton(onClick = viewModel::cycleRepeatMode) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
              Icon(
                imageVector = when (repeatMode) {
                  RepeatMode.OFF -> Icons.RoundedFilled.Repeat
                  RepeatMode.ONE -> Icons.RoundedFilled.RepeatOne
                  RepeatMode.ALL -> Icons.RoundedFilled.RepeatOn
                },
                contentDescription = null,
                tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          IconButton(onClick = viewModel::toggleAudioVisualizer) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
              Icon(
                imageVector = if (showVisualizer) Icons.RoundedFilled.AutoAwesome else Icons.RoundedFilled.Audiotrack,
                contentDescription = null,
                tint = if (showVisualizer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }

        // Bottom Right: Playlist Button
        IconButton(
          onClick = { onOpenSheet(Sheets.Playlist) },
          modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .size(48.dp)
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = Icons.RoundedFilled.QueueMusic,
              contentDescription = "Playlist",
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(24.dp)
            )
          }
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
