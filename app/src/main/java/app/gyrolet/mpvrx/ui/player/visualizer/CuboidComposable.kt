/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 *
 * Cuboid Warptunnel Audio Visualizer
 * Original by Niklas Knaack — https://codepen.io/NiklasKnaack/pen/WyWqja
 * Ported to native Android Compose Canvas for mpvRx
 */
package app.gyrolet.mpvrx.ui.player.visualizer

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.hypot
import kotlin.math.min
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun CuboidOverlay(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    palette: VisualizerPalette,
    isSheetOpen: Boolean = false,
) {
    val context = LocalContext.current
    val engine = remember { CuboidWarptunnelEngine() }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val analyzerActive = remember { AtomicBoolean(false) }
    val frequencyData = remember { ByteArray(2048) }

    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val recordPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasRecordPermission = granted
        }

    LaunchedEffect(hasRecordPermission) {
        if (!hasRecordPermission) recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(palette) {
        engine.palette = palette
    }

    DisposableEffect(hasRecordPermission) {
        var visualizer: Visualizer? = null
        if (hasRecordPermission) {
            try {
                val v = Visualizer(0)
                val range = Visualizer.getCaptureSizeRange()
                v.captureSize = min(range[1], 2048)
                v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                v.setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, sr: Int) {}
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, sr: Int) {
                            if (fft != null && fft.size >= 8) {
                                synchronized(frequencyData) {
                                    val len = min(fft.size / 2, frequencyData.size)
                                    for (k in 0 until len) {
                                        val real = fft[k * 2].toInt().toFloat()
                                        val imag = fft[k * 2 + 1].toInt().toFloat()
                                        val m = (hypot(real.toDouble(), imag.toDouble()) / 32f).coerceIn(0.0, 255.0)
                                        frequencyData[k] = m.toInt().coerceIn(0, 255).toByte()
                                    }
                                }
                                engine.frequencyData = frequencyData
                                analyzerActive.set(true)
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false,
                    true,
                )
                v.enabled = true
                visualizer = v
            } catch (_: Exception) {}
        }

        onDispose {
            analyzerActive.set(false)
            engine.frequencyData = null
            visualizer?.release()
        }
    }

    var engineW by remember { mutableStateOf(1) }
    var engineH by remember { mutableStateOf(1) }

    LaunchedEffect(engineW, engineH) {
        if (engineW < 2 || engineH < 2) return@LaunchedEffect
        engine.init(engineW, engineH)
        while (isActive) {
            val bmp = withContext(Dispatchers.Default) { engine.render() }
            bitmap = bmp
            delay(16)
        }
    }

    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var longPressJob: kotlinx.coroutines.Job? = null
                    var touchStartPos: Offset? = null
                    var pointerId: androidx.compose.ui.input.pointer.PointerId? = null
                    var pointerCount = 0

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val changes = event.changes

                            when (event.type) {
                                PointerEventType.Press -> {
                                    val first = changes.firstOrNull() ?: continue
                                    if (pointerCount == 1 && changes.size == 2) {
                                        engine.mouseDown = true
                                        engine.touchActive = true
                                    }
                                    if (pointerCount == 0 && changes.size == 1) {
                                        pointerCount = 1
                                        pointerId = first.id
                                        touchStartPos = Offset(first.position.x, first.position.y)
                                        val sx = engineW.toFloat() / size.width
                                        val sy = engineH.toFloat() / size.height
                                        engine.mousePos.x = first.position.x * sx
                                        engine.mousePos.y = first.position.y * sy
                                        engine.touchActive = true

                                        if (longPressJob?.isActive != true) {
                                            longPressJob = scope.launch {
                                                delay(400)
                                                if (pointerCount == 1) {
                                                    engine.mouseDown = true
                                                }
                                            }
                                        }
                                    }
                                    changes.forEach { it.consume() }
                                }

                                PointerEventType.Move -> {
                                    val primary = changes.firstOrNull { pointerId == null || it.id == pointerId } ?: continue
                                    val sx = engineW.toFloat() / size.width
                                    val sy = engineH.toFloat() / size.height
                                    engine.mousePos.x = primary.position.x * sx
                                    engine.mousePos.y = primary.position.y * sy
                                    engine.touchActive = true

                                    if (touchStartPos != null) {
                                        val dx = primary.position.x - touchStartPos!!.x
                                        val dy = primary.position.y - touchStartPos!!.y
                                        if (dx * dx + dy * dy > 100f) {
                                            longPressJob?.cancel()
                                            longPressJob = null
                                            engine.mouseDown = false
                                        }
                                    }
                                    changes.forEach { it.consume() }
                                }

                                PointerEventType.Release -> {
                                    if (changes.size >= 1) {
                                        val releasedIds = changes.map { it.id }.toSet()
                                        if (pointerId != null && pointerId in releasedIds) {
                                            pointerCount = 0
                                            pointerId = null
                                            longPressJob?.cancel()
                                            longPressJob = null
                                            engine.mouseDown = false
                                            engine.touchActive = false
                                        }
                                        if (changes.size >= 2) {
                                            pointerCount = if (pointerCount > 0) pointerCount - 1 else 0
                                            if (pointerCount == 1) {
                                                engine.mouseDown = false
                                            }
                                        }
                                    }
                                    changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
        ) {
            engineW = size.width.toInt()
            engineH = size.height.toInt()
            val bmp = bitmap
            if (bmp != null) {
                scale(size.width / bmp.width.toFloat(), size.height / bmp.height.toFloat()) {
                    drawImage(bmp.asImageBitmap())
                }
            }
        }
    }
}
