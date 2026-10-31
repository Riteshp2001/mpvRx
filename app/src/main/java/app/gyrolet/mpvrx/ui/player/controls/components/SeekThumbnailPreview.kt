/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.player.preview.SeekPreviewLoader
import `is`.xyz.mpv.Utils

/** Composition lives only while thumbnail mode is selected; opening a video does no preview work. */
@Composable
internal fun rememberSeekThumbnailContent(generation: Long): @Composable (Float, Boolean) -> Unit {
  val context = LocalContext.current
  val isTelevision = app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(context)
  val loader = remember(generation) { SeekPreviewLoader(context, generation) }
  DisposableEffect(loader) { onDispose { loader.close() } }
  return { position, expanded ->
    val frame by loader.frame.collectAsState()
    LaunchedEffect(loader, position.toInt()) { loader.request(position) }
    DisposableEffect(loader) { onDispose { loader.hide() } }
    val configuration = LocalConfiguration.current
    val width = (if (expanded) 264 else 168).coerceAtMost((configuration.screenWidthDp - 24).coerceAtLeast(80)).dp
    val height = (if (expanded) 148 else 94).coerceAtMost((configuration.screenHeightDp - 140).coerceAtLeast(48)).dp
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
      Column(Modifier.width(width).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
          val bitmap = frame.bitmap.takeIf { frame.second == position.toInt() }
          if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit)
          } else if (frame.loading || frame.second != position.toInt()) {
            CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
          } else {
            Text(stringResource(R.string.seek_preview_unavailable), style = MaterialTheme.typography.labelSmall)
          }
        }
        Text(Utils.prettyTime(position.toInt()), style = MaterialTheme.typography.labelLarge)
        if (!isTelevision) Text(
          stringResource(if (expanded) R.string.seek_preview_precise else R.string.seek_preview_drag_up),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
internal fun SeekThumbnailPopup(fraction: Float, content: @Composable () -> Unit) {
  val provider = remember(fraction) {
    object : PopupPositionProvider {
      override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val center = anchorBounds.left + (anchorBounds.width * fraction.coerceIn(0f, 1f)).toInt()
        return IntOffset(
          (center - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
          (anchorBounds.top - popupContentSize.height).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
      }
    }
  }
  Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false), content = content)
}
