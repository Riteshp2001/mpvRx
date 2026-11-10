package app.gyrolet.mpvrx.presentation.components

import android.graphics.Picture
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.player.AudioFormatInfo
import app.gyrolet.mpvrx.ui.player.AudioQuality
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val audioLogoPictures = LruCache<String, Picture>(10)

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
          SVG.getFromInputStream(input).renderToPicture()
        }.also { audioLogoPictures.put(logo.asset, it) }
      }
      if (picture.width > 0 && picture.height > 0) AudioLogoPainter(picture) else null
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      null
    }
  }
  val logoPainter = painter ?: return
  val width = minOf(size * logo.aspectRatio, size * 2.4f)
  val height = width / logo.aspectRatio
  val qualityLabel = stringResource(
    when (format.quality) {
      AudioQuality.HiRes -> R.string.audio_quality_hi_res
      AudioQuality.Lossless -> R.string.audio_quality_lossless
      AudioQuality.Lossy -> R.string.audio_quality_lossy
      AudioQuality.Dsd -> R.string.audio_quality_dsd
      AudioQuality.Unknown -> R.string.audio_quality_audio
    },
  )
  Image(
    painter = logoPainter,
    contentDescription = format.details(qualityLabel),
    contentScale = ContentScale.Fit,
    modifier = modifier.size(width, height),
  )
}