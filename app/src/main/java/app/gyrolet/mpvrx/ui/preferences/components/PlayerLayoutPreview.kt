/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SeekbarStyle
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.captureLiquidGlassBackdrop
import app.gyrolet.mpvrx.presentation.components.rememberLiquidGlassBackdrop
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.AdaptiveControlsContainer
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.player.controls.BottomSlot
import app.gyrolet.mpvrx.ui.player.controls.PlaybackControlsGeometry
import app.gyrolet.mpvrx.ui.player.controls.PlayerButtonTheme
import app.gyrolet.mpvrx.ui.player.controls.PlayerControlsContentTheme
import app.gyrolet.mpvrx.ui.player.controls.VideoControlsLayoutPreset
import app.gyrolet.mpvrx.ui.player.controls.components.AbLoopIcon
import app.gyrolet.mpvrx.ui.player.controls.components.LocalForceDarkPlayerButtonsBackground
import app.gyrolet.mpvrx.ui.player.controls.components.LocalPlayerButtonsOpacity
import app.gyrolet.mpvrx.ui.player.controls.components.SeekbarStylePreview
import app.gyrolet.mpvrx.ui.player.controls.resolveBottomControlsLayout
import app.gyrolet.mpvrx.ui.player.controls.resolvePlaybackControlsGeometry
import app.gyrolet.mpvrx.ui.preferences.ControlRegion
import app.gyrolet.mpvrx.ui.theme.spacing
import org.koin.compose.koinInject

/** A scaled sample scene: editing never invokes native playback, Cast, PiP, or script actions. */
@Composable
internal fun PlayerLayoutPreview(
  region: ControlRegion,
  topRightButtons: List<PlayerButton>,
  bottomRightButtons: List<PlayerButton>,
  bottomLeftButtons: List<PlayerButton>,
  portraitBottomButtons: List<PlayerButton>,
  preset: VideoControlsLayoutPreset,
  playlist: Boolean,
  onPlaylistChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val appearance = koinInject<AppearancePreferences>()
  val forceDark by appearance.forceDarkPlayerButtonsBackground.collectAsState()
  val opacity by appearance.playerButtonsBackgroundOpacity.collectAsState()
  val seekbarStyle by appearance.seekbarStyle.collectAsState()
  val glassEnabled by appearance.liquidGlassEnabled.collectAsState()
  val wavy by koinInject<PlayerPreferences>().useWavySeekbar.collectAsState()
  val title = stringResource(region.titleRes)
  val configuration = LocalConfiguration.current
  val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1).dp
  val longSide = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1).dp
  val sceneWidth = if (region.isPortrait) shortSide else longSide
  val sceneHeight = if (region.isPortrait) longSide else shortSide
  Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(stringResource(R.string.pref_layout_preview), style = MaterialTheme.typography.titleSmall)
          Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilterChip(
          selected = playlist,
          onClick = { onPlaylistChange(!playlist) },
          label = { Text(stringResource(R.string.ui_playlist)) },
        )
      }
      BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ratio = sceneWidth / sceneHeight
        val frameWidth = minOf(maxWidth, maxHeight * ratio)
        val frameHeight = frameWidth / ratio
        val scale = (frameWidth / sceneWidth).coerceAtLeast(0f)
        Layout(
          modifier = Modifier.size(frameWidth, frameHeight).clip(MaterialTheme.shapes.large)
            .clearAndSetSemantics { contentDescription = title },
          content = {
            CompositionLocalProvider(
              LocalLayoutDirection provides LayoutDirection.Ltr,
              LocalForceDarkPlayerButtonsBackground provides forceDark,
              LocalPlayerButtonsOpacity provides opacity,
            ) {
              PlayerButtonTheme {
                PlayerControlsContentTheme {
                  PreviewScene(
                    region, topRightButtons, bottomRightButtons, bottomLeftButtons, portraitBottomButtons,
                    preset, playlist, glassEnabled, seekbarStyle, wavy, sceneWidth,
                  )
                }
              }
            }
          },
        ) { measurables, constraints ->
          val scene = measurables.single().measure(Constraints.fixed(sceneWidth.roundToPx(), sceneHeight.roundToPx()))
          layout(constraints.maxWidth, constraints.maxHeight) {
            scene.placeWithLayer(0, 0) {
              scaleX = scale
              scaleY = scale
              transformOrigin = TransformOrigin(0f, 0f)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PreviewScene(
  region: ControlRegion,
  topRightButtons: List<PlayerButton>,
  bottomRightButtons: List<PlayerButton>,
  bottomLeftButtons: List<PlayerButton>,
  portraitBottomButtons: List<PlayerButton>,
  preset: VideoControlsLayoutPreset,
  playlist: Boolean,
  glassEnabled: Boolean,
  seekbarStyle: SeekbarStyle,
  wavy: Boolean,
  sceneWidth: Dp,
) {
  val spacing = MaterialTheme.spacing
  val hasActions =
    if (region.isPortrait) portraitBottomButtons.isNotEmpty()
    else bottomRightButtons.isNotEmpty() || bottomLeftButtons.isNotEmpty()
  val geometry = resolvePlaybackControlsGeometry(
    sceneWidth, preset.playbackPosition, playlist, hasActions, spacing,
  )
  val layout = resolveBottomControlsLayout(
    preset.seekbarPlacement, preset.playbackPosition, hasActions, geometry.canShareRow,
  )
  val backdrop = rememberLiquidGlassBackdrop()
  val background = Brush.linearGradient(listOf(Color(0xFF112333), Color(0xFF27434A), Color(0xFF10131D)))
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().captureLiquidGlassBackdrop(backdrop, glassEnabled).background(background))
    CompositionLocalProvider(LocalKyantPlayerBackdrop provides backdrop.takeIf { glassEnabled }) {
      Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = spacing.large, vertical = spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
      ) {
        Row(
          Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
          PreviewIcon(Icons.RoundedFilled.ArrowBack, 48.dp)
          Text(
            stringResource(R.string.ui_video_title), color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
          )
        }
        if (!region.isPortrait) {
          PreviewButtons(
            topRightButtons, region == ControlRegion.TOP_RIGHT,
            Modifier.weight(1f), endAligned = true,
          )
        }
      }
      if (!layout.buttonsBesidePlayback) {
        PreviewPlayback(playlist, geometry, Modifier.align(Alignment.Center))
      }
      Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
          .padding(horizontal = spacing.large, vertical = if (region.isPortrait) spacing.large else spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
      ) {
        layout.bottomToTop.asReversed().forEach { slot ->
          when (slot) {
            BottomSlot.Seekbar -> SeekbarStylePreview(seekbarStyle, useWavySeekbar = wavy)
            BottomSlot.Buttons -> {
              val left = if (region.isPortrait) {
                if (layout.buttonsBesidePlayback) portraitBottomButtons.take(portraitBottomButtons.size / 2) else emptyList()
              } else bottomLeftButtons
              val right = if (region.isPortrait) {
                if (layout.buttonsBesidePlayback) portraitBottomButtons.drop(portraitBottomButtons.size / 2) else portraitBottomButtons
              } else bottomRightButtons
              val actionGap = when {
                region.isPortrait && layout.buttonsBesidePlayback -> spacing.extraSmall
                region.isPortrait -> spacing.medium
                else -> spacing.extraSmall
              }
              Row(
                Modifier.fillMaxWidth().heightIn(min = if (layout.buttonsBesidePlayback) geometry.playPauseSize else 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (layout.buttonsBesidePlayback) geometry.sideGap else spacing.smaller),
              ) {
                if (layout.buttonsBesidePlayback || left.isNotEmpty()) {
                  PreviewButtons(
                    left, region == ControlRegion.BOTTOM_LEFT || region == ControlRegion.PORTRAIT_BOTTOM,
                    Modifier.weight(1f), buttonGap = actionGap,
                  )
                }
                if (layout.buttonsBesidePlayback) PreviewPlayback(playlist, geometry)
                if (layout.buttonsBesidePlayback || right.isNotEmpty()) {
                  PreviewButtons(
                    right, region == ControlRegion.BOTTOM_RIGHT || region == ControlRegion.PORTRAIT_BOTTOM,
                    Modifier.weight(1f), endAligned = !region.isPortrait || layout.buttonsBesidePlayback,
                    centered = region.isPortrait && !layout.buttonsBesidePlayback,
                    buttonGap = actionGap,
                  )
                }
              }
            }
            BottomSlot.Playback -> Unit
          }
        }
      }
    }
  }
}

@Composable
private fun PreviewButtons(
  buttons: List<PlayerButton>,
  highlighted: Boolean,
  modifier: Modifier,
  endAligned: Boolean = false,
  centered: Boolean = false,
  buttonGap: Dp = MaterialTheme.spacing.extraSmall,
) {
  val shape = MaterialTheme.shapes.medium
  Box(
    modifier.heightIn(min = 48.dp)
      .then(
        if (highlighted) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape)
          .border(2.dp, MaterialTheme.colorScheme.primary, shape)
        else Modifier,
      ),
  ) {
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = endAligned),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(
        buttonGap,
        if (endAligned) Alignment.End else if (centered) Alignment.CenterHorizontally else Alignment.Start,
      ),
    ) {
      buttons.forEach { button ->
        AdaptiveControlsContainer(
          onClick = {}, isInteractive = false, buttonSize = 48.dp,
          color = Color.Unspecified,
          horizontalPadding = if (button == PlayerButton.VIDEO_TITLE || button == PlayerButton.CURRENT_CHAPTER) 12.dp else 0.dp,
          modifier = if (button == PlayerButton.VIDEO_TITLE || button == PlayerButton.CURRENT_CHAPTER) Modifier else Modifier.size(48.dp),
        ) {
          when (button) {
            PlayerButton.VIDEO_TITLE -> Text(stringResource(R.string.ui_video_title), maxLines = 1)
            PlayerButton.CURRENT_CHAPTER -> Text(stringResource(R.string.ui_1_06_chapter_1), maxLines = 1)
            PlayerButton.AB_LOOP -> AbLoopIcon(Modifier.size(30.dp))
            else -> Icon(
              button.icon, null,
              Modifier.size(24.dp).then(if (button == PlayerButton.VERTICAL_FLIP) Modifier.rotate(90f) else Modifier),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PreviewPlayback(
  playlist: Boolean,
  geometry: PlaybackControlsGeometry,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier, verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(geometry.buttonGap),
  ) {
    if (playlist) PreviewIcon(Icons.RoundedFilled.SkipPrevious, geometry.skipButtonSize)
    PreviewIcon(Icons.RoundedFilled.PlayArrow, geometry.playPauseSize)
    if (playlist) PreviewIcon(Icons.RoundedFilled.SkipNext, geometry.skipButtonSize)
  }
}

@Composable
private fun PreviewIcon(icon: AppIcon, size: Dp) {
  AdaptiveControlsContainer(
    onClick = {}, isInteractive = false, modifier = Modifier.size(size),
    color = Color.Unspecified,
    buttonSize = size, horizontalPadding = 0.dp,
  ) {
    Icon(icon, null, Modifier.size(size / 2))
  }
}
