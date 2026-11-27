// SPDX-License-Identifier: AGPL-3.0-or-later

package app.gyrolet.mpvrx.presentation.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.utils.logging.Log
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.ceil

private val logoPictures = LruCache<String, Picture>(48)
internal val GrayscaleLogoFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
internal val MediaBadgeLogoHeight = 20.dp

@Composable
internal fun rememberAssetLogoPainter(path: String): Painter? =
  key(path) {
    val assets = LocalContext.current.applicationContext.assets
    val picture by produceState<Picture?>(logoPictures.get(path), assets, path) {
      if (value == null) {
        value =
          withContext(Dispatchers.IO) {
            try {
              logoPictures.get(path) ?: assets
                .open(path)
                .use { input ->
                  renderLogo(SVG.getFromInputStream(input))
                }.also { logoPictures.put(path, it) }
            } catch (error: IOException) {
              Log.w("AssetLogoPainter", "Unable to read $path", error)
              null
            } catch (error: SVGParseException) {
              Log.w("AssetLogoPainter", "Invalid SVG: $path", error)
              null
            } catch (error: IllegalArgumentException) {
              Log.w("AssetLogoPainter", "Invalid logo bounds: $path", error)
              null
            }
          }
      }
    }
    remember(picture) { picture?.let(::LogoPainter) }
  }

private fun renderLogo(svg: SVG): Picture {
  val bounds = svg.documentViewBox ?: RectF(0f, 0f, svg.documentWidth, svg.documentHeight)
  val width = bounds.width()
  val height = bounds.height()
  require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
  // Leave room for strokes along the edge of the artwork.
  val padding = maxOf(width, height) * 0.03f
  val paddedWidth = width + padding * 2f
  val paddedHeight = height + padding * 2f
  svg.setDocumentViewBox(bounds.left - padding, bounds.top - padding, paddedWidth, paddedHeight)
  val scale = 1024f / maxOf(paddedWidth, paddedHeight)
  val pictureWidth = ceil(paddedWidth * scale).toInt().coerceAtLeast(1)
  val pictureHeight = ceil(paddedHeight * scale).toInt().coerceAtLeast(1)
  svg.setDocumentWidth(pictureWidth.toFloat())
  svg.setDocumentHeight(pictureHeight.toFloat())
  val picture = svg.renderToPicture(pictureWidth, pictureHeight)
  val bitmap = Bitmap.createBitmap(pictureWidth, pictureHeight, Bitmap.Config.ARGB_8888)
  try {
    Canvas(bitmap).drawPicture(picture)
    val pixels = IntArray(pictureWidth * pictureHeight)
    bitmap.getPixels(pixels, 0, pictureWidth, 0, 0, pictureWidth, pictureHeight)
    var left = pictureWidth
    var top = pictureHeight
    var right = -1
    var bottom = -1
    for (y in 0 until pictureHeight) {
      for (x in 0 until pictureWidth) {
        if (pixels[y * pictureWidth + x] ushr 24 != 0) {
          left = minOf(left, x)
          top = minOf(top, y)
          right = maxOf(right, x)
          bottom = maxOf(bottom, y)
        }
      }
    }
    require(right >= left && bottom >= top) { "Logo has no visible artwork" }
    // Measure ink, not transparent SVG margins; keep the cropped result as vector drawing commands.
    return Picture().apply {
      val canvas = beginRecording(right - left + 1, bottom - top + 1)
      canvas.translate(-left.toFloat(), -top.toFloat())
      canvas.drawPicture(picture)
      endRecording()
    }
  } finally {
    bitmap.recycle()
  }
}

private class LogoPainter(
  private val picture: Picture,
) : Painter() {
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
