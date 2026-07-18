package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.ui.unit.Dp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import app.gyrolet.mpvrx.ui.icons.Icon as AppSymbolIcon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.AbLoopIcon

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.PlayerClockFormat
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.cast.CastPlayerButton
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.VideoAspect
import app.gyrolet.mpvrx.ui.player.controls.components.ControlsButton
import app.gyrolet.mpvrx.ui.player.controls.components.CurrentChapter
import app.gyrolet.mpvrx.ui.theme.controlColor
import app.gyrolet.mpvrx.ui.theme.spacing
import dev.vivvvek.seeker.Segment
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun RenderPlayerButton(
  button: PlayerButton,
  chapters: List<Segment>,
  currentChapter: Int?,
  isPortrait: Boolean,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.gyrolet.mpvrx.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
  buttonSize: Dp = 40.dp,
) {
  val clickEvent = LocalPlayerButtonsClickEvent.current
  val advancedPreferences = koinInject<AdvancedPreferences>()
  val playerPreferences = koinInject<PlayerPreferences>()
  val statisticsPage by advancedPreferences.enabledStatisticsPage.collectAsState()
  when (button) {
    PlayerButton.BACK_ARROW -> {
      ControlsButton(
        icon = Icons.RoundedFilled.ArrowBack,
        onClick = onBackPress,
        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.VIDEO_TITLE -> {
      val playlistModeEnabled = viewModel.hasPlaylistSupport()

      val titleInteractionSource = remember { MutableInteractionSource() }

      Surface(
        shape = CircleShape,
        color =
          if (hideBackground) {
            Color.Transparent
          } else {
            MaterialTheme.colorScheme.surfaceContainer.copy(
              alpha = 0.55f,
            )
          },
        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border =
          if (hideBackground) {
            null
          } else {
            BorderStroke(
              1.dp,
              MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
          },
        modifier =
          Modifier
            .height(buttonSize)
            .clip(CircleShape)
            .clickable(
              interactionSource = titleInteractionSource,
              indication = ripple(
                bounded = true,
              ),
              enabled = playlistModeEnabled,
              onClick = {
                clickEvent()
                onOpenSheet(Sheets.Playlist)
              },
            ),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier
              .padding(
                horizontal = MaterialTheme.spacing.extraSmall,
                vertical = MaterialTheme.spacing.small,
              ),
        ) {
          Text(
            mediaTitle ?: "",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
          )
          viewModel.getPlaylistInfo()?.let { playlistInfo ->
            Text(
              " • $playlistInfo",
              maxLines = 1,
              overflow = TextOverflow.Visible,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    }

    PlayerButton.BOOKMARKS_CHAPTERS -> {
      if (chapters.isNotEmpty()) {
        ControlsButton(
          Icons.RoundedFilled.Bookmarks,
          onClick = { onOpenSheet(Sheets.Chapters) },
          color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(buttonSize),
        )
      }
    }

    PlayerButton.PLAYBACK_SPEED -> {
      if (isSpeedNonOne) {
        Surface(
          shape = CircleShape,
          color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
          contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
          border = if (hideBackground) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
          ),
          modifier = Modifier
            .height(buttonSize)
            .clip(CircleShape)
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = ripple(bounded = true),
              onClick = {
                clickEvent()
                onOpenSheet(Sheets.PlaybackSpeed)
              },
            ),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            modifier = Modifier.padding(
              horizontal = MaterialTheme.spacing.small,
              vertical = MaterialTheme.spacing.small,
            ),
          ) {
            AppSymbolIcon(
              imageVector = Icons.RoundedFilled.Speed,
              contentDescription = "Playback Speed",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = String.format("%.2fx", playbackSpeed),
              maxLines = 1,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      } else {
        ControlsButton(
          icon = Icons.RoundedFilled.Speed,
          onClick = { onOpenSheet(Sheets.PlaybackSpeed) },
          color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(buttonSize),
        )
      }
    }

    PlayerButton.DECODER -> {
      Surface(
        shape = CircleShape,
        color =
          if (hideBackground) {
            Color.Transparent
          } else {
            MaterialTheme.colorScheme.surfaceContainer.copy(
              alpha = 0.55f,
            )
          },
        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border =
          if (hideBackground) {
            null
          } else {
            BorderStroke(
              1.dp,
              MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
          },
        modifier = Modifier
          .height(buttonSize)
          .clip(CircleShape)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = true),
            onClick = {
              clickEvent()
              onOpenSheet(Sheets.Decoders)
            },
          ),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier
              .padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.small,
              ),
        ) {
          Text(
            text = decoder.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }

    PlayerButton.HDR_MODE -> {
      val isHdrEnabled by viewModel.isHdrScreenOutputEnabled.collectAsState()
      ControlsButton(
        icon = if (isHdrEnabled) Icons.RoundedFilled.HdrOn else Icons.RoundedFilled.HdrOff,
        onClick = viewModel::toggleHdrScreenOutput,
        onLongClick = { onOpenPanel(Panels.HdrScreenOutput) },
        color = if (hideBackground) {
          if (isHdrEnabled) MaterialTheme.colorScheme.primary else controlColor
        } else {
          if (isHdrEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.SCREEN_ROTATION -> {
      ControlsButton(
        icon = Icons.RoundedFilled.ScreenRotation,
        onClick = viewModel::cycleScreenRotations,
        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.FRAME_NAVIGATION -> {
      val isExpanded by viewModel.isFrameNavigationExpanded.collectAsState()
      val isSnapshotLoading by viewModel.isSnapshotLoading.collectAsState()
      val context = LocalContext.current

      AnimatedContent(
        targetState = isExpanded,
        transitionSpec = {
          (fadeIn(animationSpec = tween(200)) + expandHorizontally(animationSpec = tween(250)))
            .togetherWith(fadeOut(animationSpec = tween(200)) + shrinkHorizontally(animationSpec = tween(250)))
            .using(SizeTransform(clip = false))
        },
        label = "FrameNavExpandCollapse",
      ) { expanded ->
        if (expanded) {
          Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.height(buttonSize),
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(2.dp),
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 4.dp),
            ) {
              // Previous frame button
              Surface(
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier
                  .size(buttonSize - 4.dp)
                  .clip(CircleShape)
                  .clickable(onClick = {
                    viewModel.frameStepBackward()
                    viewModel.resetFrameNavigationTimer()
                  }),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  AppSymbolIcon(
                    imageVector = Icons.RoundedFilled.FastRewind,
                    contentDescription = "Previous Frame",
                    tint = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }

              // Camera / Loading button
              if (isSnapshotLoading) {
                Surface(
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                  modifier = Modifier.size(buttonSize - 4.dp),
                ) {
                  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(16.dp),
                      strokeWidth = 2.dp,
                      color = if (hideBackground) controlColor else MaterialTheme.colorScheme.primary,
                    )
                  }
                }
              } else {
                @OptIn(ExperimentalFoundationApi::class)
                Surface(
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                  modifier = Modifier
                    .size(buttonSize - 4.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                      onClick = {
                        viewModel.takeSnapshot(context)
                        viewModel.resetFrameNavigationTimer()
                      },
                      onLongClick = { onOpenSheet(Sheets.FrameNavigation) },
                    ),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    AppSymbolIcon(
                      imageVector = Icons.RoundedFilled.Aperture,
                      contentDescription = "Take Screenshot",
                      tint = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                      modifier = Modifier.size(20.dp),
                    )
                  }
                }
              }

              // Next frame button
              Surface(
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier
                  .size(buttonSize - 4.dp)
                  .clip(CircleShape)
                  .clickable(onClick = {
                    viewModel.frameStepForward()
                    viewModel.resetFrameNavigationTimer()
                  }),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  AppSymbolIcon(
                    imageVector = Icons.RoundedFilled.FastForward,
                    contentDescription = "Next Frame",
                    tint = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }
            }
          }
        } else {
          // Collapsed: Show camera icon button
          ControlsButton(
            icon = Icons.RoundedFilled.CameraAlt,
            onClick = viewModel::toggleFrameNavigationExpanded,
            onLongClick = { onOpenSheet(Sheets.FrameNavigation) },
            color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(buttonSize),
          )
        }
      }
    }

    PlayerButton.VIDEO_ZOOM -> {
      if (kotlin.math.abs(currentZoom) >= 0.005f) {
        @OptIn(ExperimentalFoundationApi::class)
        Surface(
          shape = CircleShape,
          color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
          contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
          border = if (hideBackground) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
          ),
          modifier = Modifier
            .height(buttonSize)
            .clip(CircleShape)
            .combinedClickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = ripple(bounded = true),
              onClick = {
                clickEvent()
                onOpenSheet(Sheets.VideoZoom)
              },
              onLongClick = {
                clickEvent()
                viewModel.resetVideoZoom()
              },
            ),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            modifier = Modifier.padding(
              horizontal = MaterialTheme.spacing.small,
              vertical = MaterialTheme.spacing.small,
            ),
          ) {
            AppSymbolIcon(
              imageVector = Icons.RoundedFilled.ZoomIn,
              contentDescription = "Video Zoom",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = String.format("%.0f%%", currentZoom * 100),
              maxLines = 1,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      } else {
        ControlsButton(
          Icons.RoundedFilled.ZoomIn,
          onClick = {
            clickEvent()
            onOpenSheet(Sheets.VideoZoom)
          },
          onLongClick = { viewModel.resetVideoZoom() },
          color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(buttonSize),
        )
      }
    }

    PlayerButton.PICTURE_IN_PICTURE -> {
      ControlsButton(
        Icons.RoundedFilled.PictureInPictureAlt,
        onClick = { activity.enterPipModeHidingOverlay() },
        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.CAST -> {
      CastPlayerButton(
        hideBackground = hideBackground,
        buttonSize = buttonSize,
      )
    }

    PlayerButton.ASPECT_RATIO -> {
      ControlsButton(
        icon =
          when (aspect) {
            VideoAspect.Fit -> Icons.RoundedFilled.AspectRatio
            VideoAspect.Stretch -> Icons.RoundedFilled.ZoomOutMap
            VideoAspect.Crop -> Icons.RoundedFilled.FitScreen
          },
        onClick = {
          when (aspect) {
            VideoAspect.Fit -> viewModel.changeVideoAspect(VideoAspect.Stretch)
            VideoAspect.Stretch -> viewModel.changeVideoAspect(VideoAspect.Crop)
            VideoAspect.Crop -> viewModel.changeVideoAspect(VideoAspect.Fit)
          }
        },
        onLongClick = { onOpenSheet(Sheets.AspectRatios) },
        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.LOCK_CONTROLS -> {
      ControlsButton(
        Icons.RoundedFilled.LockOpen,
        onClick = viewModel::lockControls,
        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.AUDIO_TRACK -> {
      ControlsButton(
        Icons.RoundedFilled.Audiotrack,
        onClick = { onOpenSheet(Sheets.AudioTracks) },
        onLongClick = { onOpenPanel(Panels.AudioDelay) },
        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.SUBTITLES -> {
      ControlsButton(
        Icons.RoundedFilled.Subtitles,
        onClick = { onOpenSheet(Sheets.SubtitleTracks) },
        onLongClick = { onOpenPanel(Panels.SubtitleDelay) },
        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.MORE_OPTIONS -> {
      ControlsButton(
        Icons.RoundedFilled.MoreVert,
        onClick = { onOpenSheet(Sheets.More) },
        onLongClick = { onOpenPanel(Panels.VideoFilters) },
        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.CURRENT_CHAPTER -> {
      if (isPortrait) {
      } else {
        AnimatedVisibility(
          chapters.getOrNull(currentChapter ?: 0) != null,
          enter = fadeIn(),
          exit = fadeOut(),
        ) {
          chapters.getOrNull(currentChapter ?: 0)?.let { chapter ->
            CurrentChapter(
              chapter = chapter,
              onClick = { onOpenSheet(Sheets.Chapters) },
            )
          }
        }
      }
    }

    PlayerButton.REPEAT_MODE -> {
      val repeatMode by viewModel.repeatMode.collectAsState()
      val icon = when (repeatMode) {
        app.gyrolet.mpvrx.ui.player.RepeatMode.OFF -> Icons.RoundedFilled.Repeat
        app.gyrolet.mpvrx.ui.player.RepeatMode.ONE -> Icons.RoundedFilled.RepeatOne
        app.gyrolet.mpvrx.ui.player.RepeatMode.ALL -> Icons.RoundedFilled.RepeatOn
      }
      ControlsButton(
        icon = icon,
        onClick = viewModel::cycleRepeatMode,
        color = if (hideBackground) {
          when (repeatMode) {
            app.gyrolet.mpvrx.ui.player.RepeatMode.OFF -> controlColor
            else -> MaterialTheme.colorScheme.primary
          }
        } else {
          when (repeatMode) {
            app.gyrolet.mpvrx.ui.player.RepeatMode.OFF -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.primary
          }
        },
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.CUSTOM_SKIP -> {
      val playerPreferences = org.koin.compose.koinInject<app.gyrolet.mpvrx.preferences.PlayerPreferences>()
      ControlsButton(
        icon = Icons.RoundedFilled.FastForward,
        onClick = { viewModel.seekBy(playerPreferences.customSkipDuration.get()) },
        color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.SHUFFLE -> {
      // Only show shuffle button if there's a playlist (more than one video)
      if (viewModel.hasPlaylistSupport()) {
        val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
        ControlsButton(
          icon = if (shuffleEnabled) Icons.RoundedFilled.ShuffleOn else Icons.RoundedFilled.Shuffle,
          onClick = viewModel::toggleShuffle,
          color = if (hideBackground) {
            if (shuffleEnabled) MaterialTheme.colorScheme.primary else controlColor
          } else {
            if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
          },
          modifier = Modifier.size(buttonSize),
        )
      }
    }

    PlayerButton.MIRROR -> {
      val transform by viewModel.transformState.collectAsState()
      ControlsButton(
        icon = Icons.RoundedFilled.Flip,
        onClick = viewModel::toggleMirroring,
        color = if (hideBackground) {
          if (transform.isMirrored) MaterialTheme.colorScheme.primary else controlColor
        } else {
          if (transform.isMirrored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.VERTICAL_FLIP -> {
      val transform by viewModel.transformState.collectAsState()
      val isVerticalFlipped = transform.isVerticalFlipped
      val vFlipColor = if (hideBackground) {
        if (isVerticalFlipped) MaterialTheme.colorScheme.primary else controlColor
      } else {
        if (isVerticalFlipped) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
      }
      Surface(
        shape = CircleShape,
        color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = vFlipColor,
        border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
          .size(buttonSize)
          .clip(CircleShape)
          .clickable(onClick = viewModel::toggleVerticalFlip),
      ) {
        Box(contentAlignment = Alignment.Center) {
          AppSymbolIcon(
            imageVector = Icons.RoundedFilled.Flip,
            contentDescription = "Vertical Flip",
            tint = vFlipColor,
            modifier = Modifier
              .padding(MaterialTheme.spacing.small)
              .size(20.dp)
              .rotate(90f),
          )
        }
      }
    }

    PlayerButton.AB_LOOP -> {
      val abLoop by viewModel.abLoopState.collectAsState()
      val isExpanded = abLoop.isExpanded
      val loopA = abLoop.a
      val loopB = abLoop.b

      AnimatedContent(
        targetState = isExpanded,
        transitionSpec = {
          (fadeIn(animationSpec = tween(200)) + expandHorizontally(animationSpec = tween(250)))
            .togetherWith(fadeOut(animationSpec = tween(200)) + shrinkHorizontally(animationSpec = tween(250)))
            .using(SizeTransform(clip = false))
        },
        label = "ABLoopExpandCollapse",
      ) { expanded ->
        if (expanded) {
          Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.height(buttonSize),
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(2.dp),
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 4.dp),
            ) {
              // Point A Button - always transparent background
              Surface(
                shape = CircleShape,
                color = if (loopA != null) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
                modifier = Modifier
                  .height(buttonSize - 4.dp)
                  .widthIn(min = buttonSize - 4.dp)
                  .clip(CircleShape)
                  .clickable(onClick = { viewModel.setLoopA() }),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Text(
                    text = if (loopA != null) viewModel.formatTimestamp(loopA) else "A",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (loopA != null) {
                      MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                      if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = if (loopA != null) 8.dp else 0.dp),
                  )
                }
              }

              // Clear/Close Button - always has background
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                  .size(buttonSize - 4.dp)
                  .clip(CircleShape)
                  .clickable(onClick = {
                    viewModel.clearABLoop()
                    viewModel.toggleABLoopExpanded()
                  }),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  AppSymbolIcon(
                    imageVector = Icons.RoundedFilled.Close,
                    contentDescription = "Clear Loop",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                  )
                }
              }

              // Point B Button - always transparent background
              Surface(
                shape = CircleShape,
                color = if (loopB != null) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
                modifier = Modifier
                  .height(buttonSize - 4.dp)
                  .widthIn(min = buttonSize - 4.dp)
                  .clip(CircleShape)
                  .clickable(onClick = { viewModel.setLoopB() }),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Text(
                    text = if (loopB != null) viewModel.formatTimestamp(loopB) else "B",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (loopB != null) {
                      MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                      if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = if (loopB != null) 8.dp else 0.dp),
                  )
                }
              }
            }
          }
        } else {
          // Collapsed: Show the custom A-B loop icon
          Surface(
            shape = CircleShape,
            color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier
              .size(buttonSize)
              .clip(CircleShape)
              .clickable(onClick = viewModel::toggleABLoopExpanded),
          ) {
            Box(contentAlignment = Alignment.Center) {
              AbLoopIcon(
                modifier = Modifier.size(30.dp),
                tint = if (loopA != null && loopB != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                isASet = loopA != null,
                isBSet = loopB != null,
              )
            }
          }
        }
      }
    }

    PlayerButton.BACKGROUND_PLAYBACK -> {
      val audioPreferences = koinInject<AudioPreferences>()
      val backgroundPlaybackEnabled by audioPreferences.backgroundPlayback.collectAsState()
      ControlsButton(
        icon = Icons.RoundedFilled.Headset,
        onClick = { activity.toggleBackgroundPlayback() },
        color =
          if (backgroundPlaybackEnabled) {
            MaterialTheme.colorScheme.primary
          } else if (hideBackground) {
            controlColor
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        modifier = Modifier.size(buttonSize),
      )
    }

    PlayerButton.AMBIENT_MODE -> {
        val isAmbientEnabled by viewModel.isAmbientEnabled.collectAsState()
        @OptIn(ExperimentalFoundationApi::class)
        Surface(
          shape = CircleShape,
          color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
          contentColor = if (isAmbientEnabled) {
               MaterialTheme.colorScheme.primary
            } else {
               if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface
            },
          border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
          modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .combinedClickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = ripple(bounded = true),
              onClick = { 
                clickEvent()
                viewModel.toggleAmbientMode() 
              },
              onLongClick = {
                clickEvent()
                onOpenSheet(Sheets.AmbientConfig)
              }
            ),
        ) {
          Box(contentAlignment = Alignment.Center) {
            AppSymbolIcon(
              imageVector = if (isAmbientEnabled) Icons.RoundedFilled.BlurOn else Icons.RoundedFilled.BlurOff,
              contentDescription = "Ambience Mode",
              tint = if (isAmbientEnabled) MaterialTheme.colorScheme.primary else (if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface),
              modifier = Modifier.size(24.dp)
            )
          }
        }
    }

    PlayerButton.TIME_NETWORK -> {
      val clockFormat by playerPreferences.clockFormat.collectAsState()
      val stat by rememberTimeAndNetworkStat(clockFormat)
      Surface(
        shape = CircleShape,
        color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
          .height(buttonSize)
          .clip(CircleShape)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = true),
            onClick = {
              clickEvent()
              if (MPVLib.getPropertyBoolean("user-data/mpv/console/open") == true) {
                MPVLib.command("script-message-to", "console", "disable")
              }
              if (statisticsPage == 6) {
                advancedPreferences.enabledStatisticsPage.set(0)
              } else {
                if (statisticsPage in 1..5) {
                  MPVLib.command("script-binding", "stats/display-stats-toggle")
                }
                advancedPreferences.enabledStatisticsPage.set(6)
              }
              onOpenSheet(Sheets.None)
            },
          ),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
          modifier = Modifier
            .widthIn(min = 176.dp)
            .padding(horizontal = MaterialTheme.spacing.small),
        ) {
          AppSymbolIcon(
            imageVector = Icons.RoundedFilled.AccessTime,
            contentDescription = "Time and Network",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "${stat.time} • ${stat.network}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    PlayerButton.NONE -> { /* Do nothing */
    }
  }
}

private data class TimeAndNetworkStat(
  val time: String,
  val network: String,
  val battery: String,
)

internal data class BatterySnapshot(
  val percentageText: String,
  val rateText: String,
  val wattsText: String,
  val tempText: String,
)

internal fun readBatterySnapshot(context: Context): BatterySnapshot {
  val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
  val percentage =
    if (level >= 0 && scale > 0) {
      ((level.toFloat() / scale.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    } else {
      null
    }

  val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
  val currentMicroAmps =
    listOf(
      batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
      batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
    ).firstOrNull { value ->
      value != null && value != Long.MIN_VALUE && value != 0L
    }

  val voltageMilliVolts = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }

  val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    ?: BatteryManager.BATTERY_STATUS_UNKNOWN
  val statusText =
    when (status) {
      BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
      BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
      BatteryManager.BATTERY_STATUS_FULL -> "Full"
      BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
      else ->
        when {
          (currentMicroAmps ?: 0L) > 0L -> "Charging"
          (currentMicroAmps ?: 0L) < 0L -> "Discharging"
          else -> "Unknown"
        }
    }

  val currentMilliAmps = currentMicroAmps?.let { abs(it).toFloat() / 1000f }?.takeIf { it > 0f }
  val rateText =
    if (currentMilliAmps != null && statusText != "Full" && statusText != "Unknown") {
      val formattedCurrent =
        if (currentMilliAmps >= 100f) {
          String.format("%.0f mA", currentMilliAmps)
        } else {
          String.format("%.1f mA", currentMilliAmps)
        }
      "$statusText $formattedCurrent"
    } else {
      statusText
    }

  val wattsText =
    if (currentMilliAmps != null && voltageMilliVolts != null && voltageMilliVolts > 0) {
      val watts = (currentMilliAmps / 1000f) * (voltageMilliVolts / 1000f)
      String.format("%.2f W", watts)
    } else {
      "-- W"
    }

  val tempCelsius = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }
  val tempText =
    if (tempCelsius != null) {
      String.format("%.1f°C", tempCelsius / 10f)
    } else {
      "--°C"
    }

  return BatterySnapshot(
    percentageText = percentage?.let { "$it%" } ?: "--%",
    rateText = rateText,
    wattsText = wattsText,
    tempText = tempText,
  )
}

@Composable
private fun rememberTimeAndNetworkStat(
  clockFormat: PlayerClockFormat,
): androidx.compose.runtime.State<TimeAndNetworkStat> {
  val context = LocalContext.current.applicationContext
  return produceState(
    initialValue = TimeAndNetworkStat("--:--", "0 KB/s | --%", "--%"),
    key1 = clockFormat,
  ) {
    while (true) {
      val now = formatClock(context, clockFormat)
      val bps = readNetworkBytesPerSecond()
      val network =
        when {
          bps >= 1024 * 1024 -> String.format("%.1f MB/s", bps / (1024 * 1024))
          bps >= 1024 -> String.format("%.0f KB/s", bps / 1024)
          else -> "${bps.toInt()} B/s"
        }
      val battery = readBatterySnapshot(context).percentageText
      value = TimeAndNetworkStat(now, "$network | $battery", battery)
      delay(1000)
    }
  }
}

private fun formatClock(
  context: Context,
  clockFormat: PlayerClockFormat,
): String {
  val use24Hour =
    when (clockFormat) {
      PlayerClockFormat.SYSTEM -> DateFormat.is24HourFormat(context)
      PlayerClockFormat.TWELVE_HOUR -> false
      PlayerClockFormat.TWENTY_FOUR_HOUR -> true
    }
  return DateFormat.format(if (use24Hour) "HH:mm" else "h:mm a", System.currentTimeMillis()).toString()
}

private fun readNetworkBytesPerSecond(): Double {
  val directBytesPerSecond = listOf("demuxer-cache-speed", "cache-speed", "demuxer-speed")
    .asSequence()
    .mapNotNull { name -> runCatching { MPVLib.getPropertyDouble(name) }.getOrNull() }
    .firstOrNull { it > 0.0 }

  if (directBytesPerSecond != null) return directBytesPerSecond

  val bitratesBitsPerSecond = listOf("packet-video-bitrate", "video-bitrate", "audio-bitrate")
    .asSequence()
    .mapNotNull { name -> runCatching { MPVLib.getPropertyDouble(name) }.getOrNull() }
    .filter { it > 0.0 }
    .sum()

  return if (bitratesBitsPerSecond > 0.0) bitratesBitsPerSecond / 8.0 else 0.0
}
