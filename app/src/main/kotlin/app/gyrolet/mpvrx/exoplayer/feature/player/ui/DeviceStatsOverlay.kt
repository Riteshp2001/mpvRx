package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import app.gyrolet.mpvrx.ui.icons.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.FileReader

data class DeviceStats(
    val cpuPercent: Int = 0,
    val tempCelsius: Float = 0f,
    val usedMemMb: Long = 0,
    val totalMemMb: Long = 0,
    val batteryPercent: Int = 0,
    val batteryCharging: Boolean = false,
    val videoFps: Float = 0f
)

data class PlaybackStats(
    val videoResolution: String = "N/A",
    val videoCodec: String = "N/A",
    val videoBitrate: String = "N/A",
    val audioCodec: String = "N/A",
    val audioSampleRate: String = "N/A",
    val audioChannels: String = "N/A",
    val bufferHealth: String = "0 / 0 ms",
    val playbackSpeed: String = "1.0x",
    val displayRefreshRate: String = "N/A",
    val screenResolution: String = "N/A",
    val reportedFps: Float = 0f,
    val formatFps: Float = 0f
)

@Composable
fun DeviceStatsOverlay(
    visible: Boolean,
    player: Player?,
    modifier: Modifier = Modifier,
    videoFps: Float = 0f,
    videoDecoderName: String? = null,
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    var deviceStats by remember { mutableStateOf(DeviceStats()) }
    var playbackStats by remember { mutableStateOf(PlaybackStats()) }

    LaunchedEffect(visible) {
        if (visible) {
            while (true) {
                deviceStats = collectStats(context, videoFps)
                playbackStats = collectPlaybackStats(player, context, videoFps)
                delay(1000)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header with Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STATS FOR NERDS",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.padding(0.dp)) {
                        Icon(
                            imageVector = NextIcons.Close,
                            contentDescription = "Close Stats",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))

                // Video Details
                StatsSectionHeader("VIDEO")
                StatItem("Resolution", playbackStats.videoResolution)
                StatItem("Codec", playbackStats.videoCodec)
                
                val decoderLabel = when {
                    videoDecoderName == null -> "-"
                    videoDecoderName.contains("c2.android", ignoreCase = true) -> "[SW] $videoDecoderName"
                    videoDecoderName.contains("omx.google", ignoreCase = true) -> "[SW] $videoDecoderName"
                    videoDecoderName.contains("ffmpeg", ignoreCase = true) -> "[SW] $videoDecoderName"
                    else -> "[HW] $videoDecoderName"
                }
                StatItem("Decoder", decoderLabel, if (decoderLabel.contains("[SW]")) Color(0xFFFF9800) else Color(0xFF4CAF50))
                
                val fpsToDisplay = if (playbackStats.reportedFps > 0f) {
                    "%.2f fps".format(playbackStats.reportedFps)
                } else if (playbackStats.formatFps > 0f) {
                    "%.2f fps".format(playbackStats.formatFps)
                } else {
                    "N/A"
                }
                StatItem("Framerate", fpsToDisplay)
                StatItem("Bitrate", playbackStats.videoBitrate)

                // Audio Details
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                StatsSectionHeader("AUDIO")
                StatItem("Codec", playbackStats.audioCodec)
                StatItem("Sample Rate", playbackStats.audioSampleRate)
                StatItem("Channels", playbackStats.audioChannels)

                // Playback Health
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                StatsSectionHeader("PLAYBACK")
                StatItem("Buffer (Buf/Cur)", playbackStats.bufferHealth)
                StatItem("Speed", playbackStats.playbackSpeed)

                // Device Display
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                StatsSectionHeader("DISPLAY")
                StatItem("Refresh Rate", playbackStats.displayRefreshRate)
                StatItem("Resolution", playbackStats.screenResolution)

                // System Specs
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                StatsSectionHeader("SYSTEM")
                StatItem("CPU", "${deviceStats.cpuPercent}%", levelColor(deviceStats.cpuPercent.toFloat() / 100f))
                StatItem("Memory", "${deviceStats.usedMemMb} / ${deviceStats.totalMemMb} MB", Color.Cyan.copy(alpha = 0.85f))
                StatItem(
                    "Battery",
                    "${deviceStats.batteryPercent}% ${if (deviceStats.batteryCharging) "⚡" else ""}",
                    batteryColor(deviceStats.batteryPercent)
                )
                StatItem(
                    "Temp",
                    if (deviceStats.tempCelsius > 0f) "%.1f°C".format(deviceStats.tempCelsius) else "N/A",
                    tempColor(deviceStats.tempCelsius)
                )
            }
        }
    }
}

@Composable
private fun StatsSectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.0.sp
    )
}

@Composable
private fun StatItem(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@OptIn(UnstableApi::class)
private fun collectPlaybackStats(player: Player?, context: Context, reportedFps: Float): PlaybackStats {
    var vRes = "N/A"
    var vCodec = "N/A"
    var vBitrate = "N/A"
    var aCodec = "N/A"
    var aSampleRate = "N/A"
    var aChannels = "N/A"
    var formatFps = 0f

    val groups = player?.currentTracks?.groups ?: emptyList()
    for (group in groups) {
        if (group.isSelected) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                // Get the first selected track format
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val format = group.getTrackFormat(i)
                        vRes = if (format.width != Format.NO_VALUE && format.height != Format.NO_VALUE) {
                            "${format.width}x${format.height}"
                        } else "N/A"
                        vCodec = format.sampleMimeType ?: "N/A"
                        vBitrate = if (format.bitrate != Format.NO_VALUE) "${format.bitrate / 1000} kbps" else "N/A"
                        formatFps = if (format.frameRate != Format.NO_VALUE.toFloat() && format.frameRate > 0f) format.frameRate else 0f
                        break
                    }
                }
            } else if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val format = group.getTrackFormat(i)
                        aCodec = format.sampleMimeType ?: "N/A"
                        aSampleRate = if (format.sampleRate != Format.NO_VALUE) "${format.sampleRate} Hz" else "N/A"
                        aChannels = when (format.channelCount) {
                            1 -> "Mono (1)"
                            2 -> "Stereo (2)"
                            6 -> "5.1 (6)"
                            8 -> "7.1 (8)"
                            Format.NO_VALUE -> "N/A"
                            else -> "${format.channelCount} ch"
                        }
                        break
                    }
                }
            }
        }
    }

    val bufferPos = player?.bufferedPosition ?: 0L
    val currentPos = player?.currentPosition ?: 0L
    val bufferHealth = "%.1f / %.1f s".format(bufferPos / 1000f, currentPos / 1000f)
    val speed = player?.playbackParameters?.speed ?: 1f
    
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }
    val refreshRate = display?.refreshRate ?: 0f

    val screenRes = context.resources.displayMetrics.run { "${widthPixels}x${heightPixels}" }

    return PlaybackStats(
        videoResolution = vRes,
        videoCodec = vCodec,
        videoBitrate = vBitrate,
        audioCodec = aCodec,
        audioSampleRate = aSampleRate,
        audioChannels = aChannels,
        bufferHealth = bufferHealth,
        playbackSpeed = "%.2fx".format(speed),
        displayRefreshRate = if (refreshRate > 0f) "%.1f Hz".format(refreshRate) else "N/A",
        screenResolution = screenRes,
        reportedFps = reportedFps,
        formatFps = formatFps
    )
}

private fun levelColor(level: Float): Color = when {
    level < 0.5f -> Color(0xFF4CAF50)   // green
    level < 0.75f -> Color(0xFFFF9800)  // orange
    else -> Color(0xFFF44336)           // red
}

private fun tempColor(temp: Float): Color = when {
    temp <= 0f -> Color.White
    temp < 40f -> Color(0xFF4CAF50)
    temp < 50f -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}

private fun batteryColor(percent: Int): Color = when {
    percent > 50 -> Color(0xFF4CAF50)
    percent > 20 -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}

private fun collectStats(context: Context, videoFps: Float): DeviceStats {
    // CPU
    val cpu = readCpuUsage()

    // Memory
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am.getMemoryInfo(memInfo)
    val totalMb = memInfo.totalMem / 1_048_576L
    val availMb = memInfo.availMem / 1_048_576L
    val usedMb = totalMb - availMb

    // Battery
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: 0
    val charging = plugged != 0

    // Temperature (from battery broadcast - most reliable cross-device)
    val rawTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    val tempC = rawTemp / 10f

    return DeviceStats(
        cpuPercent = cpu,
        tempCelsius = tempC,
        usedMemMb = usedMb,
        totalMemMb = totalMb,
        batteryPercent = batteryPct,
        batteryCharging = charging,
        videoFps = videoFps
    )
}

/** Reads /proc/stat twice 100ms apart for a real CPU usage percentage. */
private fun readCpuUsage(): Int {
    return try {
        fun readStat(): LongArray {
            val line = BufferedReader(FileReader("/proc/stat")).use { it.readLine() } ?: return longArrayOf()
            val parts = line.trim().split("\\s+".toRegex()).drop(1)
            return parts.take(8).map { it.toLongOrNull() ?: 0L }.toLongArray()
        }

        val stat1 = readStat()
        Thread.sleep(100)
        val stat2 = readStat()
        if (stat1.isEmpty() || stat2.isEmpty()) return 0

        val idle1 = stat1.getOrElse(3) { 0L }
        val idle2 = stat2.getOrElse(3) { 0L }
        val total1 = stat1.sum()
        val total2 = stat2.sum()

        val totalDiff = total2 - total1
        val idleDiff = idle2 - idle1
        if (totalDiff <= 0) 0
        else ((totalDiff - idleDiff) * 100L / totalDiff).toInt().coerceIn(0, 100)
    } catch (_: Exception) {
        0
    }
}
