/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Cuboid Warptunnel Audio Visualizer
 * Original by Niklas Knaack — https://codepen.io/NiklasKnaack/pen/WyWqja
 * Ported to native Android Compose Canvas for mpvRx
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class CuboidWarptunnelEngine {
  companion object {
    private const val FOV = 250f
    private const val SPEED = 0.75f
    private const val Z_STEP = 5f
    private const val SEGMENTS = 64
    private const val RADIUS = 75f
    private const val AUDIO_BIN_MIN = 8
    private const val AUDIO_BIN_MAX = 1024
  }

  data class SubSegment(
    var x: Float = 0f,
    var y: Float = 0f,
    var x2d: Float = 0f,
    var y2d: Float = 0f,
    var index: Int = 0,
  )

  data class Segment(
    var active: Boolean = false,
    var x: Float = 0f,
    var y: Float = 0f,
    var x2d: Float = 0f,
    var y2d: Float = 0f,
    var index: Int = 0,
    var radius: Float = RADIUS,
    var radiusAudio: Float = RADIUS,
    var segments: Int = SEGMENTS,
    var audioBufferIndex: Int = 0,
    var subs: Array<SubSegment> = Array(7) { SubSegment() },
  )

  data class CircleObj(
    var z: Float,
    var center: Offset = Offset(0f, 0f),
    var circleCenter: Offset = Offset(0f, 0f),
    var mp: Offset = Offset(0f, 0f),
    var radius: Float = RADIUS,
    var color: Color3 = Color3(0f, 0f, 0f),
    var segmentsOutside: Array<Segment>,
    var index: Int,
  )

  data class Offset(
    var x: Float,
    var y: Float,
  )

  data class Color3(
    var r: Float,
    var g: Float,
    var b: Float,
  )

  data class Rgb(
    var r: Float,
    var g: Float,
    var b: Float,
  )

  private val renderLock = Any()

  @Volatile
  var renderWidth = 0
    private set

  @Volatile
  var renderHeight = 0
    private set

  var mousePos = Offset(0f, 0f)
    get() = synchronized(renderLock) { field }
    set(value) = synchronized(renderLock) { field = value }

  var mouseDown = false
    get() = synchronized(renderLock) { field }
    set(value) = synchronized(renderLock) { field = value }

  var touchActive = false
    get() = synchronized(renderLock) { field }
    set(value) = synchronized(renderLock) { field = value }

  var isLightTheme = false
    get() = synchronized(renderLock) { field }
    set(value) = synchronized(renderLock) { field = value }

  internal var palette: VisualizerPalette? = null
    get() = synchronized(renderLock) { field }
    set(value) = synchronized(renderLock) { field = value }

  private val audioSmoother = AudioReactiveSmoother()

  @Volatile
  private var frequencyData: ByteArray? = null

  @Volatile
  var volumeScale: Float = 1f

  private var pixelBuffer: IntArray = IntArray(0)
  private var bitmap: Bitmap? = null
  private var circleHolder = mutableListOf<CircleObj>()
  private var time = 0f
  private var colorInvertValue = 0f

  private val rgb =
    Rgb(
      Random.nextFloat() * Math.PI.toFloat() * 2,
      Random.nextFloat() * Math.PI.toFloat() * 2,
      Random.nextFloat() * Math.PI.toFloat() * 2,
    )
  private val rgb2 =
    Rgb(
      Random.nextFloat() * Math.PI.toFloat() * 2,
      Random.nextFloat() * Math.PI.toFloat() * 2,
      Random.nextFloat() * Math.PI.toFloat() * 2,
    )

  private fun getColor(
    color: Rgb,
    dr: Float,
    dg: Float,
    db: Float,
  ): Color3 {
    color.r += dr
    color.g += dg
    color.b += db
    return Color3(
      (sin(color.r) * 1f + 1f),
      (sin(color.g) * 1f + 1f),
      (sin(color.b) * 1f + 1f),
    )
  }

  private fun limit(
    c: Color3,
    p: Float,
  ) {
    if (c.r < p) c.r = p
    if (c.g < p) c.g = p
    if (c.b < p) c.b = p
  }

  private fun paletteColor3(color: Int): Color3 =
    Color3(
      Color.red(color) / 255f,
      Color.green(color) / 255f,
      Color.blue(color) / 255f,
    )

  fun init(
    w: Int,
    h: Int,
  ): Unit = synchronized(renderLock) {
    if (w <= 0 || h <= 0) return@synchronized
    renderWidth = w
    renderHeight = h
    val size = w * h
    if (size != pixelBuffer.size) {
      pixelBuffer = IntArray(size)
    }

    bitmap?.recycle()
    bitmap = try {
      Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    } catch (_: Throwable) {
      null
    }

    addCirclesLocked()
  }

  fun resize(
    w: Int,
    h: Int,
  ): Unit = synchronized(renderLock) {
    if (w <= 0 || h <= 0) return@synchronized
    val ow = renderWidth
    val oh = renderHeight
    renderWidth = w
    renderHeight = h
    val size = w * h
    if (size != pixelBuffer.size) {
      pixelBuffer = IntArray(size)
    }

    bitmap?.recycle()
    bitmap = try {
      Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    } catch (_: Throwable) {
      null
    }

    val sx = if (ow > 0) w.toFloat() / ow else 1f
    val sy = if (oh > 0) h.toFloat() / oh else 1f
    for (obj in circleHolder) {
      obj.mp.x *= sx
      obj.mp.y *= sy
      obj.center.x *= sx
      obj.center.y *= sy
    }
  }

  private fun addCirclesLocked() {
    circleHolder.clear()
    val mp = Offset(Random.nextFloat() * renderWidth, Random.nextFloat() * renderHeight)
    var toggle: Int
    var index = 0

    val dynamicRadius = max(min(renderWidth, renderHeight) * 0.35f, 140f)

    var z = -FOV
    while (z < FOV) {
      val coords = buildCircle(dynamicRadius, SEGMENTS)
      val initialRadius = coords.firstOrNull()?.radius ?: dynamicRadius
      val segmentsArray = Array(coords.size) {
        Segment(
          active = false,
          radius = initialRadius,
          radiusAudio = initialRadius,
          segments = SEGMENTS,
          subs = Array(7) { SubSegment() },
        )
      }

      val obj =
        CircleObj(
          z = z,
          center = Offset(renderWidth / 2f, renderHeight / 2f),
          circleCenter = Offset(0f, 0f),
          mp = Offset(mp.x, mp.y),
          radius = initialRadius,
          color = Color3(0f, 0f, 0f),
          segmentsOutside = segmentsArray,
          index = index,
        )

      toggle = index % 2
      index++

      for (i in coords.indices) {
        val c = coords[i]
        if (i % 2 == toggle) {
          val prev = if (i > 0) coords[i - 1] else coords.last()
          val bufIdx = Random.nextInt(AUDIO_BIN_MIN, max(AUDIO_BIN_MIN + 1, AUDIO_BIN_MAX))
          val seg =
            Segment(
              active = true,
              x = c.x,
              y = c.y,
              x2d = 0f,
              y2d = 0f,
              index = c.index,
              radius = c.radius,
              radiusAudio = c.radius,
              segments = c.segments,
              audioBufferIndex = bufIdx,
              subs =
                arrayOf(
                  SubSegment(prev.x, prev.y, 0f, 0f, prev.index),
                  SubSegment(c.x, c.y, 0f, 0f, c.index),
                  SubSegment(prev.x, prev.y, 0f, 0f, prev.index),
                  SubSegment(c.x, c.y, 0f, 0f, c.index),
                  SubSegment(prev.x, prev.y, 0f, 0f, prev.index),
                  SubSegment(c.x, c.y, 0f, 0f, c.index),
                  SubSegment(prev.x, prev.y, 0f, 0f, prev.index),
                ),
            )
          obj.segmentsOutside[i] = seg
        }
      }
      circleHolder.add(obj)
      z += Z_STEP
    }
  }

  data class Coord(
    val x: Float,
    val y: Float,
    val index: Int,
    val radius: Float,
    val segments: Int,
  )

  private fun buildCircle(
    radius: Float,
    segments: Int,
  ): List<Coord> {
    val list = mutableListOf<Coord>()
    val pi2 = Math.PI.toFloat() * 2
    var rs = radius
    for (i in 0..segments) {
      val r =
        when {
          i == 0 -> {
            rs = radius
            radius
          }
          i == segments -> rs
          else -> radius
        }
      val a = i * pi2 / segments + time
      list.add(Coord(cos(a) * r, sin(a) * r, i, r, segments))
    }
    return list
  }

  fun updateFrequencyData(data: ByteArray?) = synchronized(renderLock) {
    frequencyData = data?.copyOf()
  }

  fun clearAudioData() = synchronized(renderLock) {
    frequencyData = null
  }

  private fun clearLocked() {
    // Completely transparent background - no opaque background color!
    pixelBuffer.fill(0x00000000)
  }

  private fun blendPixel(
    px: Int,
    py: Int,
    r: Int,
    g: Int,
    b: Int,
    alpha: Float,
  ) {
    val w = renderWidth
    val h = renderHeight
    if (px !in 0 until w || py !in 0 until h || pixelBuffer.isEmpty()) return
    val idx = py * w + px
    val existing = pixelBuffer[idx]
    val exA = (existing ushr 24) and 0xFF
    val exR = (existing ushr 16) and 0xFF
    val exG = (existing ushr 8) and 0xFF
    val exB = existing and 0xFF

    val aFactor = alpha.coerceIn(0f, 1f)
    val newR = min(255, exR + (r * aFactor).toInt())
    val newG = min(255, exG + (g * aFactor).toInt())
    val newB = min(255, exB + (b * aFactor).toInt())
    val newA = min(255, exA + (255 * aFactor).toInt())

    pixelBuffer[idx] = (newA shl 24) or (newR shl 16) or (newG shl 8) or newB
  }

  private fun lineLocked(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    r: Int,
    g: Int,
    b: Int,
  ) {
    val w = renderWidth
    val h = renderHeight
    if (w <= 0 || h <= 0 || pixelBuffer.isEmpty()) return

    val maxVal = max(w, h) * 3
    val lx1 = x1.coerceIn(-maxVal, maxVal)
    val ly1 = y1.coerceIn(-maxVal, maxVal)
    val lx2 = x2.coerceIn(-maxVal, maxVal)
    val ly2 = y2.coerceIn(-maxVal, maxVal)

    var dx = abs(lx2 - lx1)
    var dy = abs(ly2 - ly1)
    val sx = if (lx1 < lx2) 1 else -1
    val sy = if (ly1 < ly2) 1 else -1
    var err = dx - dy
    var lx = lx1
    var ly = ly1

    val cr = r.coerceIn(0, 255)
    val cg = g.coerceIn(0, 255)
    val cb = b.coerceIn(0, 255)

    var iterations = 0
    val maxIter = max(w, h) * 4

    while (iterations < maxIter) {
      iterations++
      blendPixel(lx, ly, cr, cg, cb, 0.92f)
      if (lx == lx2 && ly == ly2) break
      val e2 = 2 * err
      if (e2 > -dx) {
        err -= dy
        lx += sx
      }
      if (e2 < dy) {
        err += dx
        ly += sy
      }
    }
  }

  private fun buildAudioFrame(data: ByteArray?): AudioFeatureFrame {
    if (data == null || data.isEmpty()) return AudioFeatureFrame.Silence
    val safeData = data.copyOf(min(data.size, 256))
    val len = safeData.size.coerceAtLeast(1)
    var subBass = 0f; var bass = 0f; var lowMid = 0f
    var mid = 0f; var highMid = 0f; var treble = 0f; var energy = 0f

    val subBassLimit = max(2, len / 16)
    val bassLimit = max(4, len / 8)
    val lowMidLimit = max(6, len / 5)
    val midLimit = max(8, len / 3)
    val highMidLimit = max(12, (len * 2) / 3)

    for (index in safeData.indices) {
      val magnitude = ((safeData[index].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
      val weighted = magnitude * magnitude
      when {
        index < subBassLimit -> subBass += weighted
        index < bassLimit -> bass += weighted
        index < lowMidLimit -> lowMid += weighted
        index < midLimit -> mid += weighted
        index < highMidLimit -> highMid += weighted
        else -> treble += weighted
      }
      energy += weighted
    }
    val subBassNorm = (subBass / subBassLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val bassNorm = (bass / bassLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val lowMidNorm = (lowMid / lowMidLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val midNorm = (mid / midLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val highMidNorm = (highMid / highMidLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val trebleNorm = (treble / (len / 2).coerceAtLeast(1)).coerceIn(0f, 1f)
    val energyNorm = (energy / len.toFloat()).coerceIn(0f, 1f)
    val flux = (bassNorm * 0.5f + midNorm * 0.5f)

    return AudioFeatureFrame(
      energy = energyNorm * volumeScale,
      subBass = subBassNorm * volumeScale,
      bass = bassNorm * volumeScale,
      lowMid = lowMidNorm * volumeScale,
      mid = midNorm * volumeScale,
      highMid = highMidNorm * volumeScale,
      treble = trebleNorm * volumeScale,
      centroid = 0.35f + bassNorm * 0.15f + trebleNorm * 0.1f,
      beat = if (energyNorm > 0.22f && bassNorm > 0.35f) 1f else 0f,
      spectralFlux = flux * volumeScale,
    )
  }

  fun render(): Bitmap? = synchronized(renderLock) {
    if (renderWidth <= 0 || renderHeight <= 0) return null
    val targetBmp = bitmap ?: return null
    clearLocked()

    val p = palette
    val defaultPrimary = if (isLightTheme) Color3(0.12f, 0.15f, 0.85f) else Color3(0.86f, 0.03f, 1f)
    val defaultSecondary = if (isLightTheme) Color3(0.85f, 0.1f, 0.4f) else Color3(1f, 0.05f, 0.5f)
    val pc = if (p != null) paletteColor3(p.primary) else defaultPrimary
    val scPalette = if (p != null) paletteColor3(p.secondary) else defaultSecondary
    val audioTarget = buildAudioFrame(frequencyData)
    val audioState = audioSmoother.update(audioTarget, 1f / 60f)
    val bass = audioState.bass
    val mid = audioState.mid
    val treble = audioState.treble
    val beat = audioState.beat
    val energy = audioState.energy
    val wave = getColor(rgb, 0.040f, 0.028f, 0.052f)
    val wave2 = getColor(rgb2, 0.010f, 0.007f, 0.013f)
    limit(wave, 0.85f)
    limit(wave2, 0.65f)
    val col =
      Color3(
        pc.r * (0.55f + bass * 0.55f) * wave.r,
        pc.g * (0.55f + mid * 0.55f) * wave.g,
        pc.b * (0.65f + treble * 0.45f) * wave.b,
      )
    val col2 =
      Color3(
        scPalette.r * (0.35f + energy * 0.65f) * wave2.r,
        scPalette.g * (0.22f + beat * 0.55f) * wave2.g,
        scPalette.b * (0.45f + bass * 0.55f) * wave2.b,
      )
    limit(col, 0.42f)
    limit(col2, 0.28f)

    val pressed = mouseDown
    var sort = false
    val fd = frequencyData
    val hasAudio = fd != null && fd.isNotEmpty()
    val pi2 = Math.PI.toFloat() * 2
    val l = circleHolder.size

    for (i in 0 until l) {
      val obj = circleHolder[i]
      obj.color.r = col.r - (obj.z + FOV) / FOV
      obj.color.g = col.g - (obj.z + FOV) / FOV
      obj.color.b = col.b - (obj.z + FOV) / FOV
      if (obj.color.r < col2.r) obj.color.r = col2.r
      if (obj.color.g < col2.g) obj.color.g = col2.g
      if (obj.color.b < col2.b) obj.color.b = col2.b

      val back = if (i > 0) circleHolder[i - 1] else null

      val targetX = if (touchActive) mousePos.x else (renderWidth / 2f)
      val targetY = if (touchActive) mousePos.y else (renderHeight / 2f)
      val lerpFactor = if (touchActive) 0.04f else 0.0025f
      obj.mp.x += (targetX - obj.mp.x) * lerpFactor
      obj.mp.y += (targetY - obj.mp.y) * lerpFactor

      val depthFactor = ((obj.z - FOV) / 500f).coerceIn(-5f, 5f)
      obj.center.x = ((renderWidth / 2f) - obj.mp.x) * depthFactor + renderWidth / 2f
      obj.center.y = ((renderHeight / 2f) - obj.mp.y) * depthFactor + renderHeight / 2f

      for (j in obj.segmentsOutside.indices) {
        val seg = obj.segmentsOutside[j]
        if (!seg.active) continue

        val zDepth = (FOV + obj.z).coerceAtLeast(1.0f)
        val scScale = FOV / zDepth

        val backZDepth = if (back != null) (FOV + back.z).coerceAtLeast(1.0f) else 1.0f
        val scBScale = if (i > 0) FOV / backZDepth else 0f

        seg.x2d = (seg.x * scScale) + obj.center.x
        seg.y2d = (seg.y * scScale) + obj.center.y

        var freq = 0
        var freqAdd = 0f
        if (hasAudio && seg.audioBufferIndex < fd.size) {
          freq = fd[seg.audioBufferIndex].toInt() and 0xFF
          freqAdd = freq / 20f
          val audioLift = 1f + (bass * 0.85f) + (mid * 0.4f) + (treble * 0.25f) + (beat * 0.35f)
          seg.radiusAudio = (seg.radius - freqAdd * 0.75f) * audioLift
        }

        var lv = 0f
        if (j > 0) {
          lv =
            if (hasAudio) {
              min(42f + i.toFloat() / l * (105f + freq + energy * 190f + bass * 145f + beat * 85f), 255f)
            } else {
              36f + i.toFloat() / l * 200f
            }
        }

        if (i > 0 && i < l - 1 && back != null) {
          val sub1 = seg.subs[0]
          sub1.x = obj.circleCenter.x + cos(sub1.index * pi2 / seg.segments + time) * seg.radiusAudio
          sub1.y = obj.circleCenter.y + sin(sub1.index * pi2 / seg.segments + time) * seg.radiusAudio
          sub1.x2d = (sub1.x * scScale) + obj.center.x
          sub1.y2d = (sub1.y * scScale) + obj.center.y

          val sub2 = seg.subs[1]
          sub2.x = obj.circleCenter.x + cos(sub2.index * pi2 / seg.segments + time) * seg.radiusAudio
          sub2.y = obj.circleCenter.y + sin(sub2.index * pi2 / seg.segments + time) * seg.radiusAudio
          sub2.x2d = (sub2.x * scBScale) + back.center.x
          sub2.y2d = (sub2.y * scBScale) + back.center.y

          val sub3 = seg.subs[2]
          sub3.x = obj.circleCenter.x + cos(sub3.index * pi2 / seg.segments + time) * seg.radiusAudio
          sub3.y = obj.circleCenter.y + sin(sub3.index * pi2 / seg.segments + time) * seg.radiusAudio
          sub3.x2d = (sub3.x * scBScale) + back.center.x
          sub3.y2d = (sub3.y * scBScale) + back.center.y

          val sub4 = seg.subs[3]
          sub4.x = obj.circleCenter.x + cos(sub4.index * pi2 / seg.segments + time) * seg.radius
          sub4.y = obj.circleCenter.y + sin(sub4.index * pi2 / seg.segments + time) * seg.radius
          sub4.x2d = (sub4.x * scScale) + obj.center.x
          sub4.y2d = (sub4.y * scScale) + obj.center.y

          val sub5 = seg.subs[4]
          sub5.x = back.circleCenter.x + cos(sub5.index * pi2 / seg.segments + time) * seg.radius
          sub5.y = back.circleCenter.y + sin(sub5.index * pi2 / seg.segments + time) * seg.radius
          sub5.x2d = (sub5.x * scScale) + obj.center.x
          sub5.y2d = (sub5.y * scScale) + obj.center.y

          val sub6 = seg.subs[5]
          sub6.x = obj.circleCenter.x + cos(sub6.index * pi2 / seg.segments + time) * seg.radius
          sub6.y = obj.circleCenter.y + sin(sub6.index * pi2 / seg.segments + time) * seg.radius
          sub6.x2d = (sub6.x * scBScale) + back.center.x
          sub6.y2d = (sub6.y * scBScale) + back.center.y

          val sub7 = seg.subs[6]
          sub7.x = back.circleCenter.x + cos(sub7.index * pi2 / seg.segments + time) * seg.radius
          sub7.y = back.circleCenter.y + sin(sub7.index * pi2 / seg.segments + time) * seg.radius
          sub7.x2d = (sub7.x * scBScale) + back.center.x
          sub7.y2d = (sub7.y * scBScale) + back.center.y

          val cr = (obj.color.r * lv).roundToInt().coerceIn(0, 255)
          val cg = (obj.color.g * lv).roundToInt().coerceIn(0, 255)
          val cb = (obj.color.b * lv).roundToInt().coerceIn(0, 255)

          if (freqAdd > 0) {
            val p1 = seg
            val p2 = seg.subs[1]
            val p3 = seg.subs[2]
            val p4 = seg.subs[0]
            lineLocked(p1.x2d.roundToInt(), p1.y2d.roundToInt(), p2.x2d.roundToInt(), p2.y2d.roundToInt(), cr, cg, cb)
            lineLocked(p2.x2d.roundToInt(), p2.y2d.roundToInt(), p3.x2d.roundToInt(), p3.y2d.roundToInt(), cr, cg, cb)
            lineLocked(p3.x2d.roundToInt(), p3.y2d.roundToInt(), p4.x2d.roundToInt(), p4.y2d.roundToInt(), cr, cg, cb)
            lineLocked(p4.x2d.roundToInt(), p4.y2d.roundToInt(), p1.x2d.roundToInt(), p1.y2d.roundToInt(), cr, cg, cb)
            lineLocked(seg.subs[3].x2d.roundToInt(), seg.subs[3].y2d.roundToInt(), p1.x2d.roundToInt(), p1.y2d.roundToInt(), cr, cg, cb)
            lineLocked(seg.subs[4].x2d.roundToInt(), seg.subs[4].y2d.roundToInt(), p4.x2d.roundToInt(), p4.y2d.roundToInt(), cr, cg, cb)
            lineLocked(sub7.x2d.roundToInt(), sub7.y2d.roundToInt(), p3.x2d.roundToInt(), p3.y2d.roundToInt(), cr, cg, cb)
            lineLocked(sub6.x2d.roundToInt(), sub6.y2d.roundToInt(), p2.x2d.roundToInt(), p2.y2d.roundToInt(), cr, cg, cb)
          }

          if (obj.z < FOV / 2f) {
            lineLocked(seg.subs[3].x2d.roundToInt(), seg.subs[3].y2d.roundToInt(), seg.subs[4].x2d.roundToInt(), seg.subs[4].y2d.roundToInt(), cr, cg, cb)
            lineLocked(seg.subs[4].x2d.roundToInt(), seg.subs[4].y2d.roundToInt(), sub7.x2d.roundToInt(), sub7.y2d.roundToInt(), cr, cg, cb)
            lineLocked(sub7.x2d.roundToInt(), sub7.y2d.roundToInt(), sub6.x2d.roundToInt(), sub6.y2d.roundToInt(), cr, cg, cb)
            lineLocked(sub6.x2d.roundToInt(), sub6.y2d.roundToInt(), seg.subs[3].x2d.roundToInt(), seg.subs[3].y2d.roundToInt(), cr, cg, cb)
          }
        }

        val a = seg.index * pi2 / seg.segments + time
        seg.x = obj.circleCenter.x + cos(a) * seg.radiusAudio
        seg.y = obj.circleCenter.y + sin(a) * seg.radiusAudio
      }

      val motionBoost = 0.35f + bass * 0.55f + beat * 0.3f + energy * 0.22f
      if (pressed) {
        obj.z += SPEED + motionBoost
        if (obj.z > FOV) {
          obj.z -= FOV * 2
          sort = true
        }
      } else {
        obj.z -= SPEED + motionBoost * 0.7f
        if (obj.z < -FOV) {
          obj.z += FOV * 2
          sort = true
        }
      }
    }

    if (sort) circleHolder.sortByDescending { it.z }

    time = if (pressed) time - 0.005f else time + 0.005f

    if (pressed) {
      colorInvertValue = min(colorInvertValue + 4f, 255f)
      softInvLocked(colorInvertValue.roundToInt())
    } else if (colorInvertValue > 0f) {
      colorInvertValue = max(colorInvertValue - 4f, 0f)
      if (colorInvertValue > 0f) softInvLocked(colorInvertValue.roundToInt())
    }

    return try {
      targetBmp.setPixels(pixelBuffer, 0, renderWidth, 0, 0, renderWidth, renderHeight)
      Bitmap.createBitmap(targetBmp)
    } catch (_: Throwable) {
      null
    }
  }

  private fun softInvLocked(v: Int) {
    for (i in pixelBuffer.indices) {
      val c = pixelBuffer[i]
      val a = (c ushr 24) and 0xFF
      if (a == 0) continue // Keep transparent background transparent!
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val nr = abs(v - r).coerceIn(0, 255)
      val ng = abs(v - g).coerceIn(0, 255)
      val nb = abs(v - b).coerceIn(0, 255)
      pixelBuffer[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
  }

  fun release() = synchronized(renderLock) {
    bitmap?.recycle()
    bitmap = null
    pixelBuffer = IntArray(0)
    circleHolder.clear()
  }
}
