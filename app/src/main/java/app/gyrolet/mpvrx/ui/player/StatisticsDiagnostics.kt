/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingEngine
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingState
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.domain.torrent.formatTorrentSpeed
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.ui.player.controls.formatClock
import app.gyrolet.mpvrx.ui.player.controls.BatterySnapshot
import app.gyrolet.mpvrx.ui.player.controls.readBatterySnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.java.KoinJavaComponent
import java.util.Locale
import kotlin.math.roundToInt

internal data class SessionBatteryReading(
  val chargeMicroAh: Long?,
  val energyNanoWh: Long?,
  val levelPercent: Double?,
)

internal fun readSessionBatteryReading(
  context: Context,
  battery: BatterySnapshot = readBatterySnapshot(context),
): SessionBatteryReading {
  val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
  fun counter(property: Int): Long? =
    manager?.getLongProperty(property)?.takeIf {
      it > 0L || (it == 0L && battery.levelPercent == 0.0)
    }
  return SessionBatteryReading(
    chargeMicroAh = counter(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
    energyNanoWh = counter(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
    levelPercent = battery.levelPercent,
  )
}

/** Supplies Android-only data; stats.lua owns Page 7's layout, native properties and scrolling. */
internal class StatisticsDiagnostics(
  private val context: Context,
  private val playerPreferences: PlayerPreferences,
  private val decoderPreferences: DecoderPreferences,
) {
  private val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
  private var lastTimeMs = SystemClock.elapsedRealtime()
  private var lastCpuMs = Process.getElapsedCpuTime()
  private var cpuSamples = 0
  private var cpuPercent = 0f
  private var activeTimeMs = 0L
  private var lastMemoryTimeMs = 0L
  private var memoryText: String? = null
  private var initialTemperature: Float? = null
  private var peakTemperature: Float? = null
  private val torrentEngine by lazy {
    KoinJavaComponent.get<TorrentStreamingEngine>(TorrentStreamingEngine::class.java)
  }

  fun sample(state: PlaybackSessionState): JsonObject {
    val now = SystemClock.elapsedRealtime()
    val cpu = Process.getElapsedCpuTime()
    val elapsed = (now - lastTimeMs).coerceAtLeast(1L)
    if (cpuSamples > 0) {
      val raw = ((cpu - lastCpuMs).coerceAtLeast(0L) * 100f / elapsed / processors).coerceIn(0f, 100f)
      cpuPercent = if (cpuSamples == 1) raw else cpuPercent * 0.65f + raw * 0.35f
    }
    cpuSamples++
    if (!state.paused && state.activeGeneration != 0L) activeTimeMs += elapsed
    lastCpuMs = cpu
    lastTimeMs = now

    if (memoryText == null || now - lastMemoryTimeMs >= 5_000L) {
      val memory = Debug.MemoryInfo()
      Debug.getMemoryInfo(memory)
      val runtime = Runtime.getRuntime()
      memoryText =
        context.getString(
          R.string.diagnostics_process_memory_value,
          formatTorrentBytes(memory.totalPss.toLong() * 1024L),
          formatTorrentBytes(runtime.totalMemory() - runtime.freeMemory()),
          formatTorrentBytes(runtime.maxMemory()),
          formatTorrentBytes(Debug.getNativeHeapAllocatedSize()),
        )
      lastMemoryTimeMs = now
    }
    val battery = readBatterySnapshot(context)
    battery.temperatureCelsius?.let { temperature ->
      if (initialTemperature == null) initialTemperature = temperature
      peakTemperature = maxOf(peakTemperature ?: temperature, temperature)
    }
    val peakText = peakTemperature?.let { String.format(Locale.getDefault(), "%.1f\u00B0C", it) } ?: "--"
    val riseText =
      battery.temperatureCelsius?.let { temperature ->
        initialTemperature?.let { String.format(Locale.getDefault(), "%+.1f\u00B0C", temperature - it) }
      } ?: "--"
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val thermal =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        when (powerManager?.currentThermalStatus) {
          PowerManager.THERMAL_STATUS_NONE -> "Normal"
          PowerManager.THERMAL_STATUS_LIGHT -> "Light Throttling"
          PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Throttling"
          PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttling"
          PowerManager.THERMAL_STATUS_CRITICAL -> "Critical Throttling"
          PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency!"
          PowerManager.THERMAL_STATUS_SHUTDOWN -> "Overheating Shutdown!"
          else -> "Unavailable"
        }
      } else {
        "Unavailable"
      }
    val activeSeconds = activeTimeMs / 1000L
    val cacheText =
      PlaybackSession.readLoadedMedia(state.generation) { properties ->
        val cache = properties.node("demuxer-cache-state")?.asMap() ?: return@readLoadedMedia null
        Triple(
          cache["cache-duration"]?.asDouble() ?: properties.double("demuxer-cache-duration"),
          cache["fw-bytes"]?.asInt(),
          cache["file-cache-bytes"]?.asInt(),
        )
      }?.let { (duration, packets, file) ->
        duration?.takeIf { it.isFinite() && it >= 0.0 }?.let {
          context.getString(
            R.string.diagnostics_playback_cache_value,
            it,
            formatCacheBytes(packets),
            file?.let(::formatCacheBytes) ?: context.getString(R.string.generic_disabled),
          )
        }
      }
    val torrent =
      if (state.currentItem?.torrentFileIndex != null) torrentEngine.state.value else TorrentStreamingState.Idle

    return buildJsonObject {
      put("playback_header", context.getString(R.string.diagnostics_playback_decoder_header))
      put("clock", formatClock(context, playerPreferences.clockFormat.get()))
      put(
        "hdr_mode",
        if (decoderPreferences.hdrScreenOutput.get()) {
          context.getString(
            R.string.hdr_mode_output_diagnostic,
            context.getString(decoderPreferences.hdrScreenMode.get().shortTitleRes),
          )
        } else {
          "SDR Output"
        },
      )
      put(
        "sections",
        buildJsonArray {
          when (torrent) {
            is TorrentStreamingState.Connecting ->
              add(torrentSection(torrent.peers, torrent.seeds, torrent.downloadSpeed, torrent.uploadSpeed))
            is TorrentStreamingState.Streaming -> {
              val progress = torrent.bufferProgress.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
              add(
                torrentSection(
                  torrent.peers,
                  torrent.seeds,
                  torrent.downloadSpeed,
                  torrent.uploadSpeed,
                  context.getString(
                    R.string.diagnostics_torrent_loaded_value,
                    formatTorrentBytes(torrent.downloadedBytes.coerceIn(0L, torrent.fileSize.coerceAtLeast(0L))),
                    formatTorrentBytes(torrent.fileSize.coerceAtLeast(0L)),
                    ((progress ?: 0f) * 100).roundToInt(),
                  ),
                ),
              )
            }
            is TorrentStreamingState.Error,
            TorrentStreamingState.Idle,
            -> Unit
          }
          add(
            section(
              context.getString(R.string.diagnostics_power_thermals_header),
              listOf(
                context.getString(R.string.diagnostics_battery_live) to
                  "${battery.percentageText} | ${battery.wattsText} | ${battery.rateText}",
                context.getString(R.string.diagnostics_session_battery_used) to
                  sessionBatteryUse(state.generation, battery),
                "Temp" to "${battery.tempText} (Peak: $peakText | Rise: $riseText)",
                "Thermal" to thermal,
              ),
            ),
          )
          add(
            section(
              context.getString(R.string.diagnostics_session_header),
              listOf(
                "Active" to
                  String.format(
                    Locale.getDefault(),
                    "%02d:%02d:%02d",
                    activeSeconds / 3600,
                    activeSeconds / 60 % 60,
                    activeSeconds % 60,
                  ),
                context.getString(R.string.diagnostics_app_cpu) to "${cpuPercent.toInt()}%",
              ),
            ),
          )
          add(
            section(
              context.getString(R.string.diagnostics_memory_cache_header),
              buildList {
                add(context.getString(R.string.diagnostics_process_memory) to checkNotNull(memoryText))
                cacheText?.let { add(context.getString(R.string.diagnostics_playback_cache) to it) }
              },
            ),
          )
        },
      )
    }
  }

  private fun sessionBatteryUse(generation: Long, battery: BatterySnapshot): String {
    val baseline =
      PlaybackSession.readSessionBatteryBaseline(generation)
        ?: return "--"
    val current = readSessionBatteryReading(context, battery)
    val values =
      buildList {
        val initialCharge = baseline.chargeMicroAh
        val currentCharge = current.chargeMicroAh
        if (initialCharge != null && currentCharge != null) {
          add(
            context.getString(
              R.string.diagnostics_session_battery_charge_value,
              (initialCharge.toDouble() - currentCharge.toDouble()) / 1_000.0,
            ),
          )
        }
        val initialEnergy = baseline.energyNanoWh
        val currentEnergy = current.energyNanoWh
        if (initialEnergy != null && currentEnergy != null) {
          add(
            context.getString(
              R.string.diagnostics_session_battery_energy_value,
              (initialEnergy.toDouble() - currentEnergy.toDouble()) / 1_000_000.0,
            ),
          )
        }
      }
    if (values.isNotEmpty()) return values.joinToString(" | ")
    val initialLevel = baseline.levelPercent
    val currentLevel = current.levelPercent
    return if (initialLevel != null && currentLevel != null) {
      context.getString(R.string.diagnostics_session_battery_percent_value, initialLevel - currentLevel)
    } else {
      context.getString(R.string.diagnostics_session_battery_unavailable)
    }
  }

  private fun torrentSection(
    peers: Int,
    seeds: Int,
    down: Long,
    up: Long,
    loaded: String? = null,
  ): JsonObject =
    section(
      context.getString(R.string.diagnostics_torrent_header),
      buildList {
        add(
          context.getString(R.string.diagnostics_torrent_peer_status) to
            context.getString(R.string.diagnostics_torrent_peers_value, peers, seeds),
        )
        loaded?.let { add(context.getString(R.string.diagnostics_torrent_loaded) to it) }
        add(
          context.getString(R.string.diagnostics_torrent_transfer) to
            context.getString(
              R.string.diagnostics_torrent_transfer_value,
              formatTorrentSpeed(down),
              formatTorrentSpeed(up),
            ),
        )
      },
    )

  private fun section(title: String, rows: List<Pair<String, String>>): JsonObject =
    buildJsonObject {
      put("title", title)
      put(
        "rows",
        buildJsonArray {
          rows.forEach { (label, value) ->
            add(
              buildJsonObject {
                put("label", label)
                put("value", value)
              },
            )
          }
        },
      )
    }

  private fun formatCacheBytes(value: Long?): String =
    value?.takeIf { it >= 0L }?.let(::formatTorrentBytes) ?: "--"
}
