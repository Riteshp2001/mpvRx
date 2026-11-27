package app.gyrolet.mpvrx.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.AudioFormatInfo
import app.gyrolet.mpvrx.ui.player.AudioQuality
import app.gyrolet.mpvrx.ui.theme.AppMotion
import org.koin.compose.koinInject

private val audioLogoGold = Color(0xFFFFD166)
private val audioLogoSilver = Color(0xFFE9EEF4)

@Composable
internal fun AudioFormatArtworkBadge(
  format: AudioFormatInfo?,
  modifier: Modifier = Modifier,
) {
  val logo = format?.artworkLogo ?: return
  val colorize by koinInject<AppearancePreferences>().colorizeMediaFormatBadges.collectAsState()
  val logoPainter = rememberAssetLogoPainter("audio_format_logos/${logo.asset}") ?: return
  val aspectRatio = logoPainter.intrinsicSize.width / logoPainter.intrinsicSize.height
  val width = MediaBadgeLogoHeight * aspectRatio
  val height = MediaBadgeLogoHeight
  val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
  val animateSheen = colorize && !AppMotion.shouldReduceMotion() && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
  val sheenProgress: State<Float>? =
    if (animateSheen) {
      val transition = rememberInfiniteTransition(label = "audioFormatSheen")
      transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
          infiniteRepeatable(
            animation = tween(durationMillis = 2_000, delayMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
          ),
        label = "audioFormatSilverSweep",
      )
    } else {
      null
    }
  val ink = if (colorize) audioLogoGold else MaterialTheme.colorScheme.onSurface
  val inkFilter = remember(ink) { ColorFilter.tint(ink) }
  val qualityLabel =
    stringResource(
      when (format.quality) {
        AudioQuality.HiRes -> R.string.audio_quality_hi_res
        AudioQuality.Lossless -> R.string.audio_quality_lossless
        AudioQuality.Lossy -> R.string.audio_quality_lossy
        AudioQuality.Dsd -> R.string.audio_quality_dsd
        AudioQuality.Unknown -> R.string.audio_quality_audio
      },
    )
  val sheenModifier =
    if (sheenProgress != null) {
      Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
          drawContent()
          val bandWidth = maxOf(4.dp.toPx(), this.size.width * 0.22f)
          val bandStart = sheenProgress.value * (this.size.width + bandWidth) - bandWidth
          // SrcAtop keeps the silver sweep inside the SVG's painted pixels.
          drawRect(
            brush =
              Brush.horizontalGradient(
                0f to Color.Transparent,
                0.35f to audioLogoSilver.copy(alpha = 0.2f),
                0.5f to audioLogoSilver.copy(alpha = 0.9f),
                0.65f to audioLogoSilver.copy(alpha = 0.2f),
                1f to Color.Transparent,
                startX = bandStart,
                endX = bandStart + bandWidth,
              ),
            blendMode = BlendMode.SrcAtop,
          )
        }
    } else {
      Modifier
    }
  Image(
    painter = logoPainter,
    contentDescription = format.details(qualityLabel),
    contentScale = ContentScale.Fit,
    colorFilter =
      when {
        logo.isMonochrome -> inkFilter
        !colorize -> GrayscaleLogoFilter
        else -> null
      },
    modifier = modifier.size(width, height).then(sheenModifier),
  )
}
