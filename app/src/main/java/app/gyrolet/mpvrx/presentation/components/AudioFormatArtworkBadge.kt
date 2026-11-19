package app.gyrolet.mpvrx.presentation.components

import android.graphics.Picture
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.player.AudioFormatInfo
import app.gyrolet.mpvrx.ui.player.AudioQuality
import app.gyrolet.mpvrx.ui.theme.AppMotion
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

private val audioLogoPictures = LruCache<String, Picture>(10)
private val audioLogoGold = Color(0xFFFFD166)
private val audioLogoSilver = Color(0xFFE9EEF4)

private fun renderAudioLogo(svg: SVG): Picture {
  val bounds = svg.documentViewBox ?: RectF(0f, 0f, svg.documentWidth, svg.documentHeight)
  val width = bounds.width()
  val height = bounds.height()
  require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
    "Audio format logo has invalid SVG bounds"
  }
  // Keep edge strokes and paths just outside the original viewBox inside the compositing layer.
  val padding = maxOf(width, height) * 0.03f
  val paddedWidth = width + padding * 2f
  val paddedHeight = height + padding * 2f
  svg.setDocumentViewBox(bounds.left - padding, bounds.top - padding, paddedWidth, paddedHeight)
  val scale = 512f / maxOf(paddedWidth, paddedHeight)
  val pictureWidth = ceil(paddedWidth * scale).toInt().coerceAtLeast(1)
  val pictureHeight = ceil(paddedHeight * scale).toInt().coerceAtLeast(1)
  svg.setDocumentWidth(pictureWidth.toFloat())
  svg.setDocumentHeight(pictureHeight.toFloat())
  return svg.renderToPicture(pictureWidth, pictureHeight)
}

private class AudioLogoPainter(private val picture: Picture) : Painter() {
  override val intrinsicSize = Size(picture.width.toFloat(), picture.height.toFloat())

  override fun DrawScope.onDraw() {
    drawIntoCanvas { canvas ->
      val native = canvas.nativeCanvas
      val saveCount = native.save()
      native.scale(size.width / picture.width, size.height / picture.height)
      native.drawPicture(picture)
      native.restoreToCount(saveCount)
    }
  }
}

@Composable
internal fun AudioFormatArtworkBadge(
  format: AudioFormatInfo?,
  modifier: Modifier = Modifier,
  size: Dp = 18.dp,
) {
  val logo = format?.artworkLogo ?: return
  val context = LocalContext.current.applicationContext
  val painter by produceState<AudioLogoPainter?>(null, logo.asset, context) {
    value = try {
      val picture = withContext(Dispatchers.IO) {
        audioLogoPictures.get(logo.asset) ?: context.assets.open("audio_format_logos/${logo.asset}").use { input ->
          renderAudioLogo(SVG.getFromInputStream(input))
        }.also { audioLogoPictures.put(logo.asset, it) }
      }
      if (picture.width > 0 && picture.height > 0) AudioLogoPainter(picture) else null
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Exception) {
      Log.w("AudioFormatArtworkBadge", "Unable to load audio logo ${logo.asset}", error)
      null
    }
  }
  val logoPainter = painter ?: return
  val aspectRatio = logoPainter.intrinsicSize.width / logoPainter.intrinsicSize.height
  val width = minOf(size * aspectRatio, size * 2.4f)
  val height = width / aspectRatio
  val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
  val animateSheen = !AppMotion.shouldReduceMotion() && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
  val sheenProgress: State<Float>? =
    if (animateSheen) {
      val transition = rememberInfiniteTransition(label = "audioFormatSheen")
      transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
          animation = tween(durationMillis = 2_000, delayMillis = 1_200, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
        label = "audioFormatSilverSweep",
      )
    } else {
      null
    }
  val goldFilter = remember { ColorFilter.tint(audioLogoGold) }
  val qualityLabel = stringResource(
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
            brush = Brush.horizontalGradient(
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
    colorFilter = if (logo.isMonochrome) goldFilter else null,
    modifier = modifier.size(width, height).then(sheenModifier),
  )
}