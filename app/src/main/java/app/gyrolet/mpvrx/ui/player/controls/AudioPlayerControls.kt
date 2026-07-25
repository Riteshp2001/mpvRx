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
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.RepeatMode
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.visualizer.BlobOverlay
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

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colorStops = arrayOf(
            0.0f to Color.Black.copy(alpha = 0.85f),
            0.3f to Color.Black.copy(alpha = 0.40f),
            0.7f to Color.Black.copy(alpha = 0.60f),
            1.0f to Color.Black.copy(alpha = 0.95f),
          )
        )
      )
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
            tint = Color.White,
            modifier = Modifier.size(32.dp)
          )
        }

        Text(
          text = stringResource(R.string.ui_now_playing),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.7f),
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
              tint = Color.White,
              modifier = Modifier.size(28.dp)
            )
          }
        }
      }

      if (isLosslessCodecOrExt) {
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
          shape = RoundedCornerShape(4.dp),
          color = Color.White.copy(alpha = 0.15f),
          border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
        ) {
          Text(
            text = if (isHiRes) "HI-RES LOSSLESS" else "LOSSLESS",
            style = MaterialTheme.typography.labelSmall.copy(
              fontWeight = FontWeight.Bold,
              fontSize = 8.5.sp,
              letterSpacing = 0.8.sp
            ),
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // 2. Center View (Album Art Box OR Visualizer Overlay)
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth(),
        contentAlignment = Alignment.Center
      ) {
        AnimatedContent(
          targetState = showVisualizer,
          transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
          label = "visualizer_toggle",
          modifier = Modifier.fillMaxSize(),
        ) { isVisualizerActive ->
          if (isVisualizerActive) {
            // ONLY the visualizer shows, NO album art box, border, or container!
            Box(
              modifier = Modifier.fillMaxSize(),
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
                    spotColor = Color.Black
                  )
                  .clip(coverShape),
                shape = coverShape,
                color = Color.Black,
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
      // Track Title & Metadata
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
      ) {
        val titleText = lastValidTitle
        Text(
          text = titleText,
          style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
        )
        Spacer(modifier = Modifier.height(4.dp))
        val playlistInfo = viewModel.getPlaylistInfo()
        Text(
          text = if (playlistInfo != null) "Track $playlistInfo" else "Audio Media",
          style = MaterialTheme.typography.titleMedium,
          color = Color.White.copy(alpha = 0.7f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      // 3. Audio Transport Controls (Seekbar + Progress Timers)
      var sliderValue by remember(currentPosSec) { mutableFloatStateOf(currentPosSec) }
      var isDragging by remember { mutableStateOf(false) }

      Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
          value = if (isDragging) sliderValue else currentPosSec,
          onValueChange = { value ->
            sliderValue = value
            isDragging = true
          },
          onValueChangeFinished = {
            viewModel.seekTo(sliderValue.toInt())
            isDragging = false
          },
          valueRange = 0f..currentDurSec.coerceAtLeast(1f),
          modifier = Modifier.fillMaxWidth(),
          colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
          ),
          thumb = {
            Box(modifier = Modifier.size(16.dp).background(Color.White, CircleShape))
          },
          track = { sliderState ->
            Canvas(modifier = Modifier.fillMaxWidth().height(3.dp)) {
              val thumbRadiusPx = 8.dp.toPx()
              val trackStart = thumbRadiusPx
              val trackEnd = size.width - thumbRadiusPx
              val trackRange = trackEnd - trackStart
              val h = size.height
              val cornerR = CornerRadius(h / 2f, h / 2f)
              val rangeSpan = (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(1f)
              val progress = (sliderState.value - sliderState.valueRange.start) / rangeSpan
              val thumbCenterX = trackStart + progress * trackRange

              drawRoundRect(
                color = Color.White.copy(alpha = 0.2f),
                topLeft = Offset(trackStart, 0f),
                size = Size(trackRange, h),
                cornerRadius = cornerR,
              )

              drawRoundRect(
                color = Color.White,
                topLeft = Offset(trackStart, 0f),
                size = Size((thumbCenterX - trackStart).coerceAtLeast(0f), h),
                cornerRadius = cornerR,
              )
            }
          }
        )

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = formatSec(currentPosSec.toLong()),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
          )
          Text(
            text = formatSec(currentDurSec.toLong()),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
          )
        }
      }

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
            tint = if (playlistModeEnabled) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(28.dp)
          )
        }

        IconButton(onClick = { viewModel.seekBy(-30) }) {
          Icon(
            imageVector = Icons.RoundedFilled.FastRewind,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(34.dp)
          )
        }

        Surface(
          onClick = { viewModel.pauseUnpause() },
          shape = CircleShape,
          color = Color.White,
          modifier = Modifier.size(76.dp),
          shadowElevation = 8.dp,
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = if (isPlaying) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
              contentDescription = null,
              tint = Color.Black,
              modifier = Modifier.size(38.dp)
            )
          }
        }

        IconButton(onClick = { viewModel.seekBy(30) }) {
          Icon(
            imageVector = Icons.RoundedFilled.FastForward,
            contentDescription = null,
            tint = Color.White,
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
            tint = if (playlistModeEnabled) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.35f),
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
            .background(Color.White.copy(alpha = 0.12f))
            .size(48.dp)
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = Icons.RoundedFilled.Equalizer,
              contentDescription = "Equalizer",
              tint = Color.White.copy(alpha = 0.85f),
              modifier = Modifier.size(24.dp)
            )
          }
        }

        // Center Pill Bar (Shuffle, Repeat, Visualizer)
        Row(
          modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
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
                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f)
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
                tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f)
              )
            }
          }

          IconButton(onClick = viewModel::toggleAudioVisualizer) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
              Icon(
                imageVector = if (showVisualizer) Icons.RoundedFilled.AutoAwesome else Icons.RoundedFilled.Audiotrack,
                contentDescription = null,
                tint = if (showVisualizer) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f)
              )
            }
          }
        }

        // Bottom Right: Playlist Button
        IconButton(
          onClick = { onOpenSheet(Sheets.Playlist) },
          modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .size(48.dp)
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = Icons.RoundedFilled.QueueMusic,
              contentDescription = "Playlist",
              tint = Color.White.copy(alpha = 0.85f),
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
