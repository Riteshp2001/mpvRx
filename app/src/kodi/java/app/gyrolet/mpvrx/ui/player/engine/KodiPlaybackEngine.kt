/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent
import org.xbmc.kodi.engine.KodiLib
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kodi as the playback engine (mpvlib-kodi's KodiLib). Kodi runs headless in this app's `:kodi`
 * process and renders into the player's Surface; KodiLib speaks mpv's commands, properties and
 * events, so PlaybackSession drives it like libmpv. mpv features Kodi has no equivalent for
 * (scripts, filters, shaders, statistics) are accepted and ignored.
 */
internal object KodiPlaybackEngine : PlaybackEngine {
  private const val TAG = "KodiPlaybackEngine"
  private const val STARTUP_ALLOWANCE_MS = 90_000L
  private const val SURFACE_SIZE = "android-surface-size"
  private const val OSD_SURFACE_SIZE = "android-osd-surface-size"

  private val observers = CopyOnWriteArrayList<MPVLib.EventObserver>()
  private val logObservers = CopyOnWriteArrayList<MPVLib.LogObserver>()
  private val decoderPreferences by lazy {
    KoinJavaComponent.get<DecoderPreferences>(DecoderPreferences::class.java)
  }

  @Volatile private var surfaceAttached = false

  // With MediaCodec embed, Kodi decodes straight to the player's Surface (zero-copy, HDR and
  // Dolby Vision passthrough) and draws subtitles on the transparent overlay. Otherwise Kodi
  // draws everything, tone-mapped, on its GL layer in the player's Surface.
  @Volatile private var videoLayer = KodiLib.LAYER_GUI

  // mpvRx may create the overlay before the player Surface.
  @Volatile private var overlaySurface: Surface? = null
  private var overlayWidth = 0
  private var overlayHeight = 0

  private var preferencesJob: Job? = null

  // First start extracts Kodi's assets; every start boots Kodi before the first file can load.
  override val startupAllowanceMs: Long
    get() = if (KodiLib.getPropertyString("kodi-engine-state") == "ready") 0L else STARTUP_ALLOWANCE_MS

  private val events =
    object : KodiLib.EventObserver {
      override fun eventProperty(property: String) = observers.forEach { it.eventProperty(property) }

      override fun eventProperty(
        property: String,
        value: Long,
      ) = observers.forEach { it.eventProperty(property, value) }

      override fun eventProperty(
        property: String,
        value: Boolean,
      ) = observers.forEach { it.eventProperty(property, value) }

      override fun eventProperty(
        property: String,
        value: String,
      ) = observers.forEach { it.eventProperty(property, value) }

      override fun eventProperty(
        property: String,
        value: Double,
      ) = observers.forEach { it.eventProperty(property, value) }

      override fun eventPropertyNode(
        property: String,
        value: Any?,
      ) {
        val node = value.toNode()
        observers.forEach { it.eventProperty(property, node) }
      }

      override fun event(
        eventId: Int,
        data: Map<String, Any?>,
      ) {
        val node = data.toNode()
        observers.forEach { it.event(eventId, node) }
      }
    }

  private val logs =
    KodiLib.LogObserver { prefix, level, text ->
      logObservers.forEach { it.logMessage(prefix, level, text) }
    }

  override fun create(context: Context) {
    KodiLib.addObserver(events)
    KodiLib.addLogObserver(logs)
    KodiLib.create(context)
  }

  override fun init() {
    KodiLib.init()
    // Kodi keeps these until they change; KodiLib replays them to a restarted engine.
    preferencesJob?.cancel()
    preferencesJob =
      CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        combine(
          decoderPreferences.hdrScreenOutput.changes(),
          decoderPreferences.kodiToneMapping.changes(),
          decoderPreferences.kodiDolbyVisionCompatibility.changes(),
          decoderPreferences.kodiAllowDolbyVision.changes(),
          decoderPreferences.kodiAllowHdr10Plus.changes(),
        ) { hdrOutput, toneMapping, doviCompatibility, allowDolbyVision, allowHdr10Plus ->
          KodiLib.setOptionString("kodi-setting/winsystem.ishdrdisplay", hdrOutput.toString())
          KodiLib.setOptionString("kodi-setting/videoplayer.convertdovi", doviCompatibility.toString())
          val formats = listOfNotNull(0.takeIf { allowDolbyVision }, 1.takeIf { allowHdr10Plus })
          KodiLib.setOptionString("kodi-setting/videoplayer.allowedhdrformats", formats.joinToString(",", "[", "]"))
          KodiLib.setPropertyString("kodi-video/tonemapmethod", toneMapping.kodiValue.toString())
        }.collect()
      }
  }

  override fun destroy() {
    preferencesJob?.cancel()
    preferencesJob = null
    surfaceAttached = false
    overlaySurface = null
    KodiLib.destroy()
    KodiLib.removeObserver(events)
    KodiLib.removeLogObserver(logs)
  }

  override fun attachSurface(surface: Surface) {
    val layer = if (decoderPreferences.mediaCodecEmbed.get()) KodiLib.LAYER_VIDEO else KodiLib.LAYER_GUI
    if (surfaceAttached && layer != videoLayer) KodiLib.detachSurface(videoLayer)
    videoLayer = layer
    surfaceAttached = true
    Log.d(TAG, "player Surface is Kodi's ${if (layer == KodiLib.LAYER_VIDEO) "video" else "GUI"} layer")
    KodiLib.attachSurface(layer, surface, 0, 0)
    val overlay = overlaySurface
    if (layer == KodiLib.LAYER_VIDEO && overlay != null && overlay.isValid) {
      KodiLib.attachSurface(KodiLib.LAYER_GUI, overlay, overlayWidth, overlayHeight)
    }
  }

  override fun detachSurface() {
    surfaceAttached = false
    KodiLib.detachSurface(videoLayer)
  }

  override val supportsOsdOverlay = true

  override fun wantsOsdOverlay(
    dolbyVisionDirect: Boolean,
    mediaCodecEmbed: Boolean,
  ): Boolean = mediaCodecEmbed

  // With the video layer, the overlay is Kodi's GUI layer (subtitles); otherwise the player's own
  // Surface is.
  override fun attachOsdSurface(
    surface: Surface,
    width: Int,
    height: Int,
  ) {
    overlaySurface = surface
    overlayWidth = width
    overlayHeight = height
    val used = surfaceAttached && videoLayer == KodiLib.LAYER_VIDEO
    Log.d(TAG, "overlay Surface ${if (used) "is Kodi's GUI layer" else "kept for the video layer"}")
    if (used) KodiLib.attachSurface(KodiLib.LAYER_GUI, surface, width, height)
  }

  override fun detachOsdSurface() {
    overlaySurface = null
    if (surfaceAttached && videoLayer == KodiLib.LAYER_VIDEO) KodiLib.detachSurface(KodiLib.LAYER_GUI)
  }

  override fun command(vararg cmd: String) = KodiLib.command(*cmd)

  // No mpv command returns a node from Kodi.
  override fun commandNode(vararg cmd: String): MPVNode? {
    KodiLib.command(*cmd)
    return null
  }

  override fun setOptionString(
    name: String,
    value: String,
  ): Int = KodiLib.setOptionString(name, value)

  override fun getPropertyInt(property: String): Int? = KodiLib.getPropertyInt(property)

  override fun setPropertyInt(
    property: String,
    value: Int,
  ) = KodiLib.setPropertyInt(property, value)

  override fun getPropertyDouble(property: String): Double? = KodiLib.getPropertyDouble(property)

  override fun setPropertyDouble(
    property: String,
    value: Double,
  ) = KodiLib.setPropertyDouble(property, value)

  override fun getPropertyBoolean(property: String): Boolean? = KodiLib.getPropertyBoolean(property)

  override fun setPropertyBoolean(
    property: String,
    value: Boolean,
  ) = KodiLib.setPropertyBoolean(property, value)

  // mpv reports the attached window as wid; PlaybackSession waits for it before video output.
  override fun getPropertyString(property: String): String? =
    if (property == "wid") {
      if (surfaceAttached) "1" else "0"
    } else {
      KodiLib.getPropertyString(property)
    }

  override fun setPropertyString(
    property: String,
    value: String,
  ) {
    if (property == SURFACE_SIZE || property == OSD_SURFACE_SIZE) {
      val width = value.substringBefore('x').toIntOrNull() ?: return
      val height = value.substringAfter('x').toIntOrNull() ?: return
      val layer = if (property == OSD_SURFACE_SIZE) KodiLib.LAYER_GUI else videoLayer
      KodiLib.setSurfaceSize(layer, width, height)
      return
    }
    KodiLib.setPropertyString(property, value)
  }

  override fun getPropertyNode(property: String): MPVNode? = KodiLib.getPropertyNode(property)?.toNode()

  override fun observeProperty(
    property: String,
    format: Int,
  ) = KodiLib.observeProperty(property, format)

  override fun addObserver(observer: MPVLib.EventObserver) {
    observers.addIfAbsent(observer)
  }

  override fun removeObserver(observer: MPVLib.EventObserver) {
    observers.remove(observer)
  }

  override fun addLogObserver(observer: MPVLib.LogObserver) {
    logObservers.addIfAbsent(observer)
  }

  override fun removeLogObserver(observer: MPVLib.LogObserver) {
    logObservers.remove(observer)
  }

  override fun grabThumbnail(dimension: Int): Bitmap? = null

  private fun Any?.toNode(): MPVNode =
    when (this) {
      null -> MPVNode.None
      is String -> MPVNode.StringNode(this)
      is Boolean -> MPVNode.BooleanNode(this)
      is Long -> MPVNode.IntNode(this)
      is Int -> MPVNode.IntNode(toLong())
      is Number -> MPVNode.DoubleNode(toDouble())
      is Map<*, *> -> MPVNode.MapNode(entries.associate { (key, value) -> key.toString() to value.toNode() })
      is List<*> -> MPVNode.ArrayNode(map { it.toNode() }.toTypedArray())
      else -> MPVNode.StringNode(toString())
    }
}
