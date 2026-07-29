/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.cos
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
        var x: Float, var y: Float,
        var x2d: Float, var y2d: Float,
        var index: Int,
    )

    data class Segment(
        var active: Boolean = false,
        var x: Float, var y: Float,
        var x2d: Float, var y2d: Float,
        var index: Int,
        var radius: Float,
        var radiusAudio: Float,
        var segments: Int,
        var audioBufferIndex: Int,
        var subs: Array<SubSegment> = emptyArray(),
    )

    data class CircleObj(
        var z: Float,
        var center: Offset,
        var circleCenter: Offset,
        var mp: Offset,
        var radius: Float,
        var color: Color3,
        var segmentsOutside: Array<Segment>,
        var index: Int,
    )

    data class Offset(var x: Float, var y: Float)
    data class Color3(var r: Float, var g: Float, var b: Float)
    data class Rgb(var r: Float, var g: Float, var b: Float)

    var renderWidth = 0
    var renderHeight = 0

    var mousePos = Offset(0f, 0f)
    var mouseDown = false
    var touchActive = false
    var isLightTheme = false

    internal var palette: VisualizerPalette? = null

    @Volatile
    var frequencyData: ByteArray? = null

    private var pixelBuffer: IntArray = IntArray(0)
    private var bitmap: Bitmap? = null
    private var circleHolder = mutableListOf<CircleObj>()
    private var time = 0f
    private var colorInvertValue = 0f

    private val rgb = Rgb(
        Random.nextFloat() * Math.PI.toFloat() * 2,
        Random.nextFloat() * Math.PI.toFloat() * 2,
        Random.nextFloat() * Math.PI.toFloat() * 2,
    )
    private val rgb2 = Rgb(
        Random.nextFloat() * Math.PI.toFloat() * 2,
        Random.nextFloat() * Math.PI.toFloat() * 2,
        Random.nextFloat() * Math.PI.toFloat() * 2,
    )

    private fun getColor(color: Rgb, dr: Float, dg: Float, db: Float): Color3 {
        color.r += dr; color.g += dg; color.b += db
        return Color3(
            (sin(color.r) * 1f + 1f),
            (sin(color.g) * 1f + 1f),
            (sin(color.b) * 1f + 1f),
        )
    }

    private fun limit(c: Color3, p: Float) {
        if (c.r < p) c.r = p
        if (c.g < p) c.g = p
        if (c.b < p) c.b = p
    }

    private fun paletteColor3(color: Int): Color3 = Color3(
        Color.red(color) / 255f,
        Color.green(color) / 255f,
        Color.blue(color) / 255f,
    )

    fun init(w: Int, h: Int) {
        renderWidth = w; renderHeight = h
        val size = w * h
        if (size != pixelBuffer.size) pixelBuffer = IntArray(size)
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        addCircles()
    }

    fun resize(w: Int, h: Int) {
        val ow = renderWidth; val oh = renderHeight
        renderWidth = w; renderHeight = h
        val size = w * h
        if (size != pixelBuffer.size) pixelBuffer = IntArray(size)
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sx = if (ow > 0) w.toFloat() / ow else 1f
        val sy = if (oh > 0) h.toFloat() / oh else 1f
        for (obj in circleHolder) {
            obj.mp.x *= sx; obj.mp.y *= sy
            obj.center.x *= sx; obj.center.y *= sy
        }
    }

    private fun addCircles() {
        circleHolder.clear()
        val mp = Offset(Random.nextFloat() * renderWidth, Random.nextFloat() * renderHeight)
        var toggle = 1; var index = 0; var audioIndex = AUDIO_BIN_MIN

        var z = -FOV
        while (z < FOV) {
            val coords = buildCircle(RADIUS, SEGMENTS)
            val obj = CircleObj(
                z = z,
                center = Offset(0f, 0f), circleCenter = Offset(0f, 0f),
                mp = Offset(mp.x, mp.y),
                radius = coords.first().radius,
                color = Color3(0f, 0f, 0f),
                segmentsOutside = Array(coords.size) {
                    Segment(active = false, x = 0f, y = 0f, x2d = 0f, y2d = 0f, index = 0, radius = 0f, radiusAudio = 0f, segments = 0, audioBufferIndex = 0)
                },
                index = index,
            )
            toggle = index % 2
            index++
            if (z < 0) audioIndex++ else audioIndex--

            var bufIdx = Random.nextInt(AUDIO_BIN_MIN, AUDIO_BIN_MAX)

            for (i in coords.indices) {
                val c = coords[i]
                if (i % 2 == toggle) {
                    val prev = if (i > 0) coords[i - 1] else coords.last()
                    val seg = Segment(
                        active = true,
                        x = c.x, y = c.y, x2d = 0f, y2d = 0f,
                        index = c.index, radius = c.radius, radiusAudio = c.radius,
                        segments = c.segments, audioBufferIndex = bufIdx,
                        subs = arrayOf(
                            SubSegment(prev.x, prev.y, 0f, 0f, prev.index),
                            SubSegment(c.x, c.y, 0f, 0f, c.index),
                            SubSegment(prev.x, prev.y, 0f, 0f, prev.index),
                            SubSegment(c.x, c.y, 0f, 0f, c.index),
                            SubSegment(prev.x, prev.y, 0f, 0f, prev.index),
                            SubSegment(c.x, c.y, 0f, 0f, c.index),
                            SubSegment(prev.x, prev.y, 0f, 0f, prev.index),
                        ),
                    )
                    bufIdx = if (i < coords.size - 1) Random.nextInt(AUDIO_BIN_MIN, AUDIO_BIN_MAX)
                    else obj.segmentsOutside[0].audioBufferIndex
                    obj.segmentsOutside[i] = seg
                }
            }
            circleHolder.add(obj)
            z += Z_STEP
        }
    }

    data class Coord(val x: Float, val y: Float, val index: Int, val radius: Float, val segments: Int)

    private fun buildCircle(radius: Float, segments: Int): List<Coord> {
        val list = mutableListOf<Coord>()
        val pi2 = Math.PI.toFloat() * 2
        var rs = radius
        for (i in 0..segments) {
            val r = when { i == 0 -> { rs = radius; radius }; i == segments -> rs; else -> radius }
            val a = i * pi2 / segments + time
            list.add(Coord(cos(a) * r, sin(a) * r, i, r, segments))
        }
        return list
    }

    private fun clear() {
        pixelBuffer.fill(palette?.background ?: 0xFF000000.toInt())
    }

    private fun line(x1: Int, y1: Int, x2: Int, y2: Int, r: Int, g: Int, b: Int) {
        var dx = abs(x2 - x1); var dy = abs(y2 - y1)
        val sx = if (x1 < x2) 1 else -1; val sy = if (y1 < y2) 1 else -1
        var err = dx - dy
        var lx = x1; var ly = y1
        while (true) {
            if (lx in 0 until renderWidth && ly in 0 until renderHeight)
                pixelBuffer[ly * renderWidth + lx] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            if (lx == x2 && ly == y2) break
            val e2 = 2 * err
            if (e2 > -dx) { err -= dy; lx += sx }
            if (e2 < dy) { err += dx; ly += sy }
        }
    }

    fun render(): Bitmap? {
        clear()

        val p = palette
        val pc = if (p != null) paletteColor3(p.primary) else Color3(1f, 1f, 1f)
        val sc = if (p != null) paletteColor3(p.secondary) else Color3(0.5f, 0.5f, 0.5f)
        val wave = getColor(rgb, 0.040f, 0.028f, 0.052f)
        val wave2 = getColor(rgb2, 0.010f, 0.007f, 0.013f)
        limit(wave, 0.85f); limit(wave2, 0.65f)
        val col = Color3(pc.r * wave.r, pc.g * wave.g, pc.b * wave.b)
        val col2 = Color3(sc.r * wave2.r, sc.g * wave2.g, sc.b * wave2.b)
        limit(col, 0.12f); limit(col2, 0.04f)

        val pressed = mouseDown
        var sort = false
        val fd = frequencyData
        val hasAudio = fd != null
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

            if (!touchActive) {
                obj.mp.x += ((renderWidth / 2f) - obj.mp.x) * 0.00025f
                obj.mp.y += ((renderHeight / 2f) - obj.mp.y) * 0.00025f
            }

            obj.center.x = ((renderWidth / 2f) - obj.mp.x) * ((obj.z - FOV) / 500f) + renderWidth / 2f
            obj.center.y = ((renderHeight / 2f) - obj.mp.y) * ((obj.z - FOV) / 500f) + renderHeight / 2f

            for (j in obj.segmentsOutside.indices) {
                val seg = obj.segmentsOutside[j]
                if (!seg.active) continue

                val sc = FOV / (FOV + obj.z)
                val scB = if (i > 0) FOV / (FOV + back!!.z) else 0f

                seg.x2d = (seg.x * sc) + obj.center.x
                seg.y2d = (seg.y * sc) + obj.center.y

                var freq = 0; var freqAdd = 0f
                if (hasAudio && seg.audioBufferIndex < fd!!.size) {
                    freq = fd[seg.audioBufferIndex].toInt() and 0xFF
                    freqAdd = freq / 20f
                    seg.radiusAudio = seg.radius - freqAdd
                }

                var lv = 0f
                if (j > 0) {
                    lv = if (hasAudio) min(i.toFloat() / l * (55f + freq), 255f)
                    else i.toFloat() / l * 200f
                }

                if (i > 0 && i < l - 1 && back != null) {
                    val sub1 = seg.subs[0]; sub1.x = obj.circleCenter.x + cos(sub1.index * pi2 / seg.segments + time) * seg.radiusAudio; sub1.y = obj.circleCenter.y + sin(sub1.index * pi2 / seg.segments + time) * seg.radiusAudio
                    sub1.x2d = (sub1.x * sc) + obj.center.x; sub1.y2d = (sub1.y * sc) + obj.center.y

                    val sub2 = seg.subs[1]; sub2.x = obj.circleCenter.x + cos(sub2.index * pi2 / seg.segments + time) * seg.radiusAudio; sub2.y = obj.circleCenter.y + sin(sub2.index * pi2 / seg.segments + time) * seg.radiusAudio
                    sub2.x2d = (sub2.x * scB) + back.center.x; sub2.y2d = (sub2.y * scB) + back.center.y

                    val sub3 = seg.subs[2]; sub3.x = obj.circleCenter.x + cos(sub3.index * pi2 / seg.segments + time) * seg.radiusAudio; sub3.y = obj.circleCenter.y + sin(sub3.index * pi2 / seg.segments + time) * seg.radiusAudio
                    sub3.x2d = (sub3.x * scB) + back.center.x; sub3.y2d = (sub3.y * scB) + back.center.y

                    val sub4 = seg.subs[3]; sub4.x = obj.circleCenter.x + cos(sub4.index * pi2 / seg.segments + time) * seg.radius; sub4.y = obj.circleCenter.y + sin(sub4.index * pi2 / seg.segments + time) * seg.radius
                    sub4.x2d = (sub4.x * sc) + obj.center.x; sub4.y2d = (sub4.y * sc) + obj.center.y

                    val sub5 = seg.subs[4]; sub5.x = back.circleCenter.x + cos(sub5.index * pi2 / seg.segments + time) * seg.radius; sub5.y = back.circleCenter.y + sin(sub5.index * pi2 / seg.segments + time) * seg.radius
                    sub5.x2d = (sub5.x * sc) + obj.center.x; sub5.y2d = (sub5.y * sc) + obj.center.y

                    val sub6 = seg.subs[5]; sub6.x = obj.circleCenter.x + cos(sub6.index * pi2 / seg.segments + time) * seg.radius; sub6.y = obj.circleCenter.y + sin(sub6.index * pi2 / seg.segments + time) * seg.radius
                    sub6.x2d = (sub6.x * scB) + back.center.x; sub6.y2d = (sub6.y * scB) + back.center.y

                    val sub7 = seg.subs[6]; sub7.x = back.circleCenter.x + cos(sub7.index * pi2 / seg.segments + time) * seg.radius; sub7.y = back.circleCenter.y + sin(sub7.index * pi2 / seg.segments + time) * seg.radius
                    sub7.x2d = (sub7.x * scB) + back.center.x; sub7.y2d = (sub7.y * scB) + back.center.y

                    var cr = (obj.color.r * lv).roundToInt()
                    var cg = (obj.color.g * lv).roundToInt()
                    var cb = (obj.color.b * lv).roundToInt()

                    if (freqAdd > 0) {
                        val p1 = seg; val p2 = seg.subs[1]; val p3 = seg.subs[2]; val p4 = seg.subs[0]
                        line(p1.x2d.roundToInt(), p1.y2d.roundToInt(), p2.x2d.roundToInt(), p2.y2d.roundToInt(), cr, cg, cb)
                        line(p2.x2d.roundToInt(), p2.y2d.roundToInt(), p3.x2d.roundToInt(), p3.y2d.roundToInt(), cr, cg, cb)
                        line(p3.x2d.roundToInt(), p3.y2d.roundToInt(), p4.x2d.roundToInt(), p4.y2d.roundToInt(), cr, cg, cb)
                        line(p4.x2d.roundToInt(), p4.y2d.roundToInt(), p1.x2d.roundToInt(), p1.y2d.roundToInt(), cr, cg, cb)
                        line(seg.subs[3].x2d.roundToInt(), seg.subs[3].y2d.roundToInt(), p1.x2d.roundToInt(), p1.y2d.roundToInt(), cr, cg, cb)
                        line(seg.subs[4].x2d.roundToInt(), seg.subs[4].y2d.roundToInt(), p4.x2d.roundToInt(), p4.y2d.roundToInt(), cr, cg, cb)
                        line(sub7.x2d.roundToInt(), sub7.y2d.roundToInt(), p3.x2d.roundToInt(), p3.y2d.roundToInt(), cr, cg, cb)
                        line(sub6.x2d.roundToInt(), sub6.y2d.roundToInt(), p2.x2d.roundToInt(), p2.y2d.roundToInt(), cr, cg, cb)
                    }
                    if (obj.z < FOV / 2f) {
                        line(seg.subs[3].x2d.roundToInt(), seg.subs[3].y2d.roundToInt(), seg.subs[4].x2d.roundToInt(), seg.subs[4].y2d.roundToInt(), cr, cg, cb)
                        line(seg.subs[4].x2d.roundToInt(), seg.subs[4].y2d.roundToInt(), sub7.x2d.roundToInt(), sub7.y2d.roundToInt(), cr, cg, cb)
                        line(sub7.x2d.roundToInt(), sub7.y2d.roundToInt(), sub6.x2d.roundToInt(), sub6.y2d.roundToInt(), cr, cg, cb)
                        line(sub6.x2d.roundToInt(), sub6.y2d.roundToInt(), seg.subs[3].x2d.roundToInt(), seg.subs[3].y2d.roundToInt(), cr, cg, cb)
                    }
                }

                val a = seg.index * pi2 / seg.segments + time
                seg.x = obj.circleCenter.x + cos(a) * seg.radiusAudio
                seg.y = obj.circleCenter.y + sin(a) * seg.radiusAudio
            }

            if (pressed) { obj.z += SPEED; if (obj.z > FOV) { obj.z -= FOV * 2; sort = true } }
            else { obj.z -= SPEED; if (obj.z < -FOV) { obj.z += FOV * 2; sort = true } }
        }

        if (sort) circleHolder.sortByDescending { it.z }

        time = if (pressed) time - 0.005f else time + 0.005f

        if (pressed) {
            colorInvertValue = min(colorInvertValue + 5f, 255f)
            softInv(colorInvertValue.roundToInt())
        } else if (colorInvertValue > 0f) {
            colorInvertValue = maxOf(colorInvertValue - 5f, 0f)
            if (colorInvertValue > 0f) softInv(colorInvertValue.roundToInt())
        }

        val bmp = bitmap ?: return null
        bmp.setPixels(pixelBuffer, 0, renderWidth, 0, 0, renderWidth, renderHeight)
        return bmp
    }

    private fun softInv(v: Int) {
        for (i in pixelBuffer.indices) {
            val c = pixelBuffer[i]
            pixelBuffer[i] = 0xFF000000.toInt() or
                (abs(v - Color.red(c)) shl 16) or
                (abs(v - Color.green(c)) shl 8) or
                abs(v - Color.blue(c))
        }
    }

    fun release() {
        bitmap?.recycle(); bitmap = null; circleHolder.clear()
    }
}
