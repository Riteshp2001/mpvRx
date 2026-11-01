/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import android.content.res.Configuration.ORIENTATION_PORTRAIT
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.drawCapsuleTint
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassEffects
import app.gyrolet.mpvrx.ui.liquidglass.outsideShadow
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidGlassSettings
import app.gyrolet.mpvrx.ui.theme.AppMotion
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.roundedfilled.Replay
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import `is`.xyz.mpv.Utils
import kotlin.math.roundToInt
import java.util.Locale
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerViewModel

private val compactSpeedIndicatorWidth = 32.dp

/** Equal-width digits where the font supports them. */
fun TextStyle.withTabularDigits(): TextStyle = copy(fontFeatureSettings = "tnum")

// Signs and separators that locale-formatted numbers may use: ASCII/Unicode minus, plus, Arabic separators.
private const val NUMERIC_PUNCTUATION = "+-\u2212.,\u066B\u066C"

/** Wording without numbers: "Zoom: 95%" and "Zoom: −100%" share a key, a different message does not. */
private fun numericWidthKey(text: String): String =
  text.filterNot {
    it.isDigit() || it in NUMERIC_PUNCTUATION || Character.getType(it) == Character.FORMAT.toInt()
  }

/**
 * Shared anti-jitter sizing for every live-number indicator on the player. While the wording of [text]
 * stays the same the element can grow but never shrink, so digits with different glyph widths cannot
 * make it jitter; new wording lets it fit its content again.
 */
@Composable
fun Modifier.stableNumericWidth(text: String): Modifier {
  // Layout-only memory: not snapshot state, so widening never triggers recomposition.
  val widestPx = remember(numericWidthKey(text)) { intArrayOf(0) }
  return layout { measurable, constraints ->
    val minWidth = maxOf(constraints.minWidth, widestPx[0]).coerceAtMost(constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = minWidth))
    widestPx[0] = maxOf(widestPx[0], placeable.width)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
  }
}

@Composable
private fun rememberPlayerUpdateOffset(): State<androidx.compose.ui.unit.Dp> {
  val activity = LocalActivity.current as? PlayerActivity
  val playerViewModel =
    remember(activity) {
      activity?.let { ViewModelProvider(it)[PlayerViewModel::class.java] }
    }

  val controlsShown = playerViewModel?.controlsShown?.collectAsState()?.value ?: false
  val controlsLocked = playerViewModel?.areControlsLocked?.collectAsState()?.value ?: false
  val brightnessSliderShown = playerViewModel?.isBrightnessSliderShown?.collectAsState()?.value ?: false
  val volumeSliderShown = playerViewModel?.isVolumeSliderShown?.collectAsState()?.value ?: false
  val areButtonsVisible = controlsShown && !controlsLocked && !brightnessSliderShown && !volumeSliderShown
  val isPortrait = LocalConfiguration.current.orientation == ORIENTATION_PORTRAIT

  // Keep mpvRx's current visible-control placement intact (104dp portrait / 64dp landscape).
  // mpvRex moves its OSD toward the top when controls disappear. Applying that movement as an
  // internal offset avoids touching the carefully tuned PlayerControls constraints and padding.
  val targetOffset =
    if (areButtonsVisible) {
      0.dp
    } else if (isPortrait) {
      (-40).dp
    } else {
      (-32).dp
    }

  return animateDpAsState(
    targetValue = targetOffset,
    animationSpec = spring(),
    label = "player_update_controls_offset",
  )
}

/** Shared container for every player pill; [stableWidthText] keeps it from jittering as numbers change. */
@Composable
fun PlayerUpdate(
  modifier: Modifier = Modifier,
  stableWidthText: String = "",
  content: @Composable () -> Unit = {},
) {
  val controlsOffset = rememberPlayerUpdateOffset()
  val backdrop = LocalKyantPlayerBackdrop.current
  val glassSettings = rememberLiquidGlassSettings()
  val glassEnabled = liquidGlassActive()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val renderGlass = glassEnabled && backdrop != null
  // Capture composable-context colors before entering DrawScope lambdas
  val pillColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
  val contentColor = MaterialTheme.colorScheme.onSurface

  val glassModifier =
    if (renderGlass) {
      Modifier
        .graphicsLayer { clip = false }
        .drawBackdrop(
          backdrop = checkNotNull(backdrop),
          shape = { Capsule() },
          effects = {
            liquidGlassEffects(
              glassSettings,
              blurRadius = 8.dp.toPx(),
              refractionHeight = 14.dp.toPx(),
              refractionAmount = 22.dp.toPx(),
              vibrant = true,
              refractionEnabled = !reducedMotion,
            )
          },
          highlight = {
            glassSettings.highlight(
              Highlight.Ambient.copy(alpha = if (reducedMotion) 0.28f else 0.52f),
            )
          },
          shadow = {
            Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.16f))
          },
          innerShadow = {
            InnerShadow(radius = 2.dp, color = Color.White.copy(alpha = 0.14f))
          },
          onDrawSurface = {
            drawCapsuleTint(glassSettings.surfaceColor(pillColor))
          },
        )
    } else {
      Modifier
        .outsideShadow(Capsule(), radius = 8.dp)
        .clip(Capsule())
        .background(pillColor)
    }

  CompositionLocalProvider(LocalContentColor provides contentColor) {
    Box(
      modifier =
        modifier
          .offset { IntOffset(0, controlsOffset.value.roundToPx()) }
          // animateContentSize() clips to its bounds, so it must sit inside the glass; otherwise the
          // drop shadow gets cut into a rectangular box around the capsule.
          .then(glassModifier)
          .animateContentSize(),
    ) {
      Box(
        modifier =
          Modifier
            .stableNumericWidth(stableWidthText)
            .padding(vertical = 4.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
      ) {
        content()
      }
    }
  }
}

@Composable
fun TextPlayerUpdate(
  text: String,
  modifier: Modifier = Modifier,
) {
  PlayerUpdate(modifier, stableWidthText = text) {
    Text(
      text = text,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.bodyMedium.withTabularDigits(),
    )
  }
}

@Composable
fun MultipleSpeedPlayerUpdate(
  currentSpeed: Float,
  modifier: Modifier = Modifier,
) {
  CompactSpeedIndicator(currentSpeed = currentSpeed, modifier = modifier)
}

@Composable
fun CompactSpeedIndicator(
  currentSpeed: Float,
  modifier: Modifier = Modifier,
) {
  val speedString = remember(currentSpeed) { currentSpeed.formatSpeed() }

  PlayerUpdate(modifier, stableWidthText = "${speedString}x") {
    AnimatedContent(
      targetState = speedString,
      modifier = Modifier.widthIn(min = compactSpeedIndicatorWidth),
      contentAlignment = Alignment.Center,
      transitionSpec = {
        (fadeIn(animationSpec = tween(100)) +
          scaleIn(
            initialScale = 0.85f,
            animationSpec =
              spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
              ),
          ) +
          slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec =
              spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
              ),
          )).togetherWith(
          fadeOut(animationSpec = tween(100)) +
            scaleOut(targetScale = 1.1f, animationSpec = tween(100)) +
            slideOutVertically(targetOffsetY = { -it / 3 }, animationSpec = tween(100)),
        ).using(SizeTransform(clip = false))
      },
      label = "SpeedJumpAnimation",
    ) { targetSpeed ->
      Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = targetSpeed,
          fontWeight = FontWeight.ExtraBold,
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.bodyMedium.withTabularDigits(),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "x",
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(start = 1.dp),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
      }
    }
  }
}

@Composable
fun ResumeAvailablePlayerUpdate(
  position: Int,
  onResume: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PlayerUpdate(modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.player_resume_from_pill, Utils.prettyTime(position)),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
      )
      Surface(
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier =
          Modifier
            .clip(RoundedCornerShape(100.dp))
            .clickable(onClick = onResume),
      ) {
        Text(
          text = stringResource(R.string.player_resume_action),
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
fun ResumedFromPlayerUpdate(
  position: Int,
  onRestart: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PlayerUpdate(modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.player_resumed_from_pill, Utils.prettyTime(position)),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
      )
      Surface(
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier =
          Modifier
            .clip(RoundedCornerShape(100.dp))
            .clickable(onClick = onRestart),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = MaterialSymbols.RoundedFilled.Replay,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp),
          )
          Text(
            text = stringResource(R.string.player_restart_action),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
  }
}

@Composable
@Preview
private fun PreviewMultipleSpeedPlayerUpdate() {
  MultipleSpeedPlayerUpdate(currentSpeed = 2f)
}

internal fun Float.formatSpeed(): String {
  val rounded = (this * 100f).roundToInt() / 100f
  return if (rounded % 1f == 0f) {
    rounded.toInt().toString()
  } else {
    String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0')
  }
}

@Composable
fun SeekPlayerUpdate(
  currentTime: String,
  seekDelta: String,
  modifier: Modifier = Modifier,
) {
  val stableTextStyle = MaterialTheme.typography.bodyMedium.withTabularDigits()
  PlayerUpdate(modifier, stableWidthText = "$currentTime $seekDelta") {
    Row(
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = currentTime,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        style = stableTextStyle,
      )

      Text(
        text = " $seekDelta",
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
        style = stableTextStyle,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
    }
  }
}
