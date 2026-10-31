/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Minimal, always-visible video HUD. Independent of Android's real status bar.
 */
package app.gyrolet.mpvrx.ui.player.controls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PersistentStatusPosition
import app.gyrolet.mpvrx.preferences.PersistentStatusSlot
import app.gyrolet.mpvrx.preferences.PlayerClockFormat
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.tv.isTelevision
import app.gyrolet.mpvrx.ui.tv.tvOverscanPadding
import app.gyrolet.mpvrx.ui.player.controls.components.formatSpeed
import kotlinx.coroutines.delay

/**
 * Reads the player's existing adaptive position flow. Do not add an independent mpv/JNI polling
 * loop: playback already updates [PlayerViewModel.precisePosition] with the controls hidden.
 */
@Composable
internal fun PersistentStatusHud(
  viewModel: PlayerViewModel,
  preferences: PlayerPreferences,
  durationSeconds: Float,
  playbackSpeed: Float,
  modifier: Modifier = Modifier,
) {
  val enabled by preferences.persistentStatusEnabled.collectAsState()
  if (!enabled) return

  val position by preferences.persistentStatusPosition.collectAsState()
  val left by preferences.persistentStatusLeftSlot.collectAsState()
  val center by preferences.persistentStatusCenterSlot.collectAsState()
  val right by preferences.persistentStatusRightSlot.collectAsState()
  val preferFinishTime by preferences.persistentStatusPreferFinishTime.collectAsState()
  val showSpeed by preferences.persistentStatusShowSpeed.collectAsState()
  val microBattery by preferences.persistentStatusMicroBattery.collectAsState()
  val showProgressLine by preferences.persistentStatusProgressLine.collectAsState()
  val clockFormat by preferences.clockFormat.collectAsState()
  val television = isTelevision()
  val slots = listOf(left, center, right)
  val needsProgress = showProgressLine || !television || PersistentStatusSlot.PlaybackProgress in slots
  val playbackPosition = if (needsProgress) viewModel.precisePosition.collectAsState().value else 0f
  val battery = if (television) 0 else rememberHudBatteryLevel()
  val needsClock = !television || PersistentStatusSlot.CurrentTime in slots || (needsProgress && preferFinishTime)
  val now = if (needsClock) rememberHudClock() else 0L

  Box(modifier.fillMaxSize()) {
    val isTop = position == PersistentStatusPosition.Top
    PersistentStatusHudRow(
      leftSlot = left,
      centerSlot = center,
      rightSlot = right,
      preferFinishTime = preferFinishTime,
      showSpeed = showSpeed,
      microBattery = microBattery,
      positionSeconds = playbackPosition,
      durationSeconds = durationSeconds,
      playbackSpeed = playbackSpeed,
      batteryLevel = battery,
      nowMillis = now,
      clockFormat = clockFormat,
      // Only the cutout is avoided: system-bar insets change when the controls show their bars.
      modifier = Modifier
        .align(if (isTop) Alignment.TopCenter else Alignment.BottomCenter)
        .tvOverscanPadding(top = isTop, bottom = !isTop)
        .windowInsetsPadding(
          WindowInsets.displayCutout.only(
            WindowInsetsSides.Horizontal +
              if (isTop) WindowInsetsSides.Top else WindowInsetsSides.Bottom,
          ),
        )
        .padding(vertical = 8.dp),
    )
    if (showProgressLine) {
      PersistentPlaybackProgressLine(
        positionSeconds = playbackPosition,
        durationSeconds = durationSeconds,
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }
}

@Composable
internal fun PersistentPlaybackProgressLine(
  positionSeconds: Float,
  durationSeconds: Float,
  modifier: Modifier = Modifier,
) {
  val validDuration = durationSeconds.isFinite() && durationSeconds > 0f
  val progress =
    if (validDuration && positionSeconds.isFinite()) (positionSeconds / durationSeconds).coerceIn(0f, 1f)
    else 0f
  val color = MaterialTheme.colorScheme.primary
  val label = stringResource(R.string.pref_persistent_status_progress)
  Canvas(
    modifier.fillMaxWidth().height(2.dp).semantics {
      contentDescription = label
      progressBarRangeInfo =
        if (validDuration) ProgressBarRangeInfo(progress, 0f..1f) else ProgressBarRangeInfo.Indeterminate
    },
  ) {
    drawRect(Color.White.copy(alpha = 0.2f))
    drawRect(color, size = Size(size.width * progress, size.height))
  }
}

/** Wall-clock time that ticks on each minute boundary, the finest unit the HUD shows. */
@Composable
internal fun rememberHudClock(): Long {
  val now by produceState(System.currentTimeMillis()) {
    while (true) {
      delay(60_000L - System.currentTimeMillis() % 60_000L)
      value = System.currentTimeMillis()
    }
  }
  return now
}

@Composable
private fun rememberHudBatteryLevel(): Int {
  val context = LocalContext.current
  var level by remember { mutableIntStateOf(100) }
  DisposableEffect(context) {
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (raw >= 0 && scale > 0) level = (raw * 100 / scale).coerceIn(0, 100)
      }
    }
    // BATTERY_CHANGED is a protected system broadcast and supplies the initial sticky value.
    context.registerReceiver(receiver, filter)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }
  return level
}

/** Shared by player overlay and the settings page's real-time layout preview. */
@Composable
internal fun PersistentStatusHudRow(
  leftSlot: PersistentStatusSlot,
  centerSlot: PersistentStatusSlot,
  rightSlot: PersistentStatusSlot,
  preferFinishTime: Boolean,
  showSpeed: Boolean,
  microBattery: Boolean,
  positionSeconds: Float,
  durationSeconds: Float,
  playbackSpeed: Float,
  batteryLevel: Int,
  nowMillis: Long,
  clockFormat: PlayerClockFormat,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val wallTime = remember(nowMillis, clockFormat) { formatClock(context, clockFormat, nowMillis) }
  val validDuration = durationSeconds.isFinite() && durationSeconds > 0f
  val remainingSeconds =
    if (validDuration && positionSeconds.isFinite()) {
      (durationSeconds - positionSeconds).coerceAtLeast(0f)
    } else 0f
  val validSpeed = playbackSpeed.isFinite() && playbackSpeed > 0.01f
  val progressText =
    if (!validDuration) {
      context.getString(R.string.pref_persistent_status_time_unknown)
    } else if (preferFinishTime && validSpeed) {
      val extraMillis = ((remainingSeconds / playbackSpeed) * 1000.0).toLong()
        .coerceIn(0L, 30L * 24 * 60 * 60 * 1000)
      context.getString(R.string.pref_persistent_status_est_prefix) +
        " " + formatClock(context, clockFormat, nowMillis + extraMillis)
    } else {
      DateUtils.formatElapsedTime(positionSeconds.coerceAtLeast(0f).toLong()) +
        " / " + DateUtils.formatElapsedTime(durationSeconds.toLong())
    }
  val speedTag = if (showSpeed && validSpeed && kotlin.math.abs(playbackSpeed - 1f) > 0.005f) {
    " ${playbackSpeed.formatSpeed()}x"
  } else ""
  val statusText = progressText + speedTag
  val style = TextStyle(
    color = Color.White,
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    shadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 4f),
  )

  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    listOf(
      Triple(leftSlot, Alignment.CenterStart, TextAlign.Start),
      Triple(centerSlot, Alignment.Center, TextAlign.Center),
      Triple(rightSlot, Alignment.CenterEnd, TextAlign.End),
    ).forEach { (slot, alignment, textAlign) ->
      StatusSlotContent(slot, wallTime, statusText, batteryLevel, microBattery, style, alignment, textAlign, Modifier.weight(1f))
    }
  }
}

@Composable
private fun StatusSlotContent(
  slot: PersistentStatusSlot,
  wallTime: String,
  progressText: String,
  batteryLevel: Int,
  microBattery: Boolean,
  style: TextStyle,
  alignment: Alignment,
  textAlignment: TextAlign,
  modifier: Modifier = Modifier,
) {
  val displayedSlot =
    if (slot == PersistentStatusSlot.BatteryStatus && isTelevision()) PersistentStatusSlot.None else slot
  Box(modifier = modifier, contentAlignment = alignment) {
    when (displayedSlot) {
      PersistentStatusSlot.None -> Unit
      PersistentStatusSlot.CurrentTime -> Text(wallTime, style = style, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = textAlignment)
      PersistentStatusSlot.PlaybackProgress -> Text(progressText, style = style, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = textAlignment)
      PersistentStatusSlot.BatteryStatus -> {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
          if (microBattery) {
            Canvas(Modifier.size(width = 13.dp, height = 10.dp)) {
              val capWidth = size.width * 0.10f
              val bodyWidth = size.width - capWidth - 1.dp.toPx()
              drawRoundRect(
                color = Color.White,
                size = Size(bodyWidth, size.height),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(1.dp.toPx()),
              )
              val inner = 2.dp.toPx()
              drawRoundRect(
                color = Color.White,
                topLeft = Offset(inner, inner),
                size = Size((bodyWidth - inner * 2) * (batteryLevel.coerceIn(0, 100) / 100f),
                  (size.height - inner * 2).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(0.75.dp.toPx()),
              )
              drawRoundRect(
                color = Color.White,
                topLeft = Offset(bodyWidth + 1.dp.toPx(), size.height * 0.25f),
                size = Size(capWidth, size.height * 0.5f),
                cornerRadius = CornerRadius(1.dp.toPx()),
              )
            }
          }
          Text("$batteryLevel%", style = style, maxLines = 1)
        }
      }
    }
  }
}
