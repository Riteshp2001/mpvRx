package app.gyrolet.mpvrx.desktop.mpv

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

/**
 * Draws mpv frames as a normal Compose node, so player controls, panels and tooltips
 * layer above video without heavyweight-window workarounds.
 */
@Composable
fun MpvVideoSurface(
  engine: MpvPlayerEngine,
  modifier: Modifier = Modifier,
) {
  var frame by remember { mutableStateOf<Image?>(null) }
  var generation by remember { mutableLongStateOf(0L) }

  LaunchedEffect(engine) {
    engine.frameVersion.collect {
      val next = engine.takeFrame() ?: return@collect
      val previous = frame
      frame = next
      generation++
      // The previous frame finished drawing before this callback ran on the UI thread.
      previous?.close()
    }
  }
  DisposableEffect(engine) {
    onDispose {
      frame?.close()
      frame = null
    }
  }

  Canvas(modifier.onSizeChanged { engine.setSurfaceSize(it.width, it.height) }) {
    generation
    val image = frame ?: return@Canvas
    drawIntoCanvas { canvas ->
      canvas.skiaCanvas.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(size.width, size.height),
      )
    }
  }
}
