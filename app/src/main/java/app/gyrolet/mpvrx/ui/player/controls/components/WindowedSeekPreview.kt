package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.seekpreview.SeekPreviewPolicy
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import `is`.xyz.mpv.Utils
import kotlin.math.abs

@Composable
internal fun WindowedSeekPreview(
  viewModel: PlayerViewModel,
  durationSeconds: Float,
  isPortrait: Boolean,
  modifier: Modifier = Modifier,
  onCancel: () -> Unit = {},
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentOnCancel by rememberUpdatedState(onCancel)
  DisposableEffect(viewModel, lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP && viewModel.seekThumbnailFrame.value.targetMs != null) {
        viewModel.cancelWindowedSeekPreview(resumePlayback = false)
        currentOnCancel()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      if (viewModel.seekThumbnailFrame.value.targetMs != null) {
        viewModel.cancelWindowedSeekPreview(resumePlayback = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        currentOnCancel()
      }
    }
  }
  val frame by viewModel.seekThumbnailFrame.collectAsStateWithLifecycle()
  BackHandler(enabled = frame.targetMs != null) {
    viewModel.cancelWindowedSeekPreview()
    onCancel()
  }
  val targetMs = frame.targetMs ?: return
  if (!durationSeconds.isFinite() || durationSeconds <= 0f) return
  val cursorPosition by viewModel.precisePosition.collectAsStateWithLifecycle()
  val density = LocalDensity.current
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val targetLabel = Utils.prettyTime((targetMs / 1_000L).toInt(), false)
  val cachedTime = frame.frameMs?.takeIf { abs(it - targetMs) > SeekPreviewPolicy.EXACT_TOLERANCE_MS }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val windowWidth = minOf(maxWidth, if (isPortrait) 152.dp else 132.dp)
    val windowHeight = windowWidth * 9f / 16f
    val widthPx = with(density) { windowWidth.roundToPx() }
    val durationMs = (durationSeconds.toDouble() * 1_000.0).toLong()
    val cursorMs = (cursorPosition.toDouble() * 1_000.0).toLong()
    val offsetPx = SeekPreviewPolicy.windowOffsetPx(cursorMs, durationMs, constraints.maxWidth, widthPx)
    val logicalOffsetPx = if (rtl) constraints.maxWidth - widthPx - offsetPx else offsetPx
    Column(
      modifier = Modifier.offset { IntOffset(logicalOffsetPx, 0) }.width(windowWidth),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(windowHeight)
          .clip(RoundedCornerShape(6.dp))
          .background(Color.Black.copy(alpha = 0.94f))
          .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
      ) {
        val bitmap = frame.bitmap
        if (bitmap != null) {
          Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
        }
        if (frame.loading) {
          CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp),
          )
        } else if (bitmap == null) {
          Text(
            text = stringResource(R.string.seek_preview_unavailable),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp),
          )
        }
      }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 34.dp)
          .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
          .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = targetLabel,
          color = Color.White,
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (cachedTime != null) {
          Text(
            text = stringResource(R.string.seek_preview_cached_time, Utils.prettyTime((cachedTime / 1_000L).toInt(), false)),
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}