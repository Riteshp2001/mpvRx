/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/**
 * Executes FFT processing, Hann windowing, magnitude extraction, and EMA smoothing
 * on a background thread every 16ms (~60 FPS).
 *
 * UI threads NEVER call FFT or windowing math directly.
 */
class FFTProcessor(
  private val pcmRepository: PCMRepository,
  val audioFeatures: AudioFeatures = AudioFeatures(),
) {
  companion object {
    const val FFT_SIZE = 2048
    const val NUM_BARS = 64
    const val NUM_WAVE_POINTS = 128
    const val TIMER_INTERVAL_MS = 16L // 16ms = ~60 FPS
  }

  private val _visualizationData = MutableStateFlow(VisualizationData())
  val visualizationData: StateFlow<VisualizationData> = _visualizationData.asStateFlow()

  private var fftJob: Job? = null

  // Precomputed Hann window weights
  private val hannWindow = FloatArray(FFT_SIZE) { i ->
    (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
  }

  // Precomputed bit-reversal indices for Cooley-Tukey Radix-2 FFT
  private val bitReversedIndices = IntArray(FFT_SIZE) { i ->
    Integer.reverse(i) ushr (32 - Integer.numberOfTrailingZeros(FFT_SIZE))
  }

  // Buffers for FFT execution
  private val realBuffer = FloatArray(FFT_SIZE)
  private val imagBuffer = FloatArray(FFT_SIZE)
  private val rawMagnitudes = FloatArray(FFT_SIZE / 2)
  private val smoothedBars = FloatArray(NUM_BARS)

  // Smoothing states
  private var smoothBass = 0f
  private var smoothMid = 0f
  private var smoothTreble = 0f
  private var smoothEnergy = 0f
  private var previousBass = 0f
  private var beatEnvelope = 0f
  private var beatCooldownFrames = 0

  /**
   * Starts background FFT processing on [Dispatchers.Default].
   */
  fun start(scope: CoroutineScope) {
    if (fftJob?.isActive == true) return

    fftJob = scope.launch(Dispatchers.Default) {
      while (isActive) {
        val startTime = System.currentTimeMillis()

        processFrame()

        val elapsed = System.currentTimeMillis() - startTime
        val delayTime = (TIMER_INTERVAL_MS - elapsed).coerceAtLeast(1L)
        delay(delayTime)
      }
    }
  }

  /**
   * Stops background FFT processing.
   */
  fun stop() {
    fftJob?.cancel()
    fftJob = null
    _visualizationData.value = VisualizationData()
    audioFeatures.reset()
  }

  /**
   * Single frame processing step (runs every 16ms on background thread).
   */
  private fun processFrame() {
    val pcmSamples = pcmRepository.getLatestSamples(FFT_SIZE)

    // 1. Apply Hann Window
    for (i in 0 until FFT_SIZE) {
      realBuffer[i] = pcmSamples[i] * hannWindow[i]
      imagBuffer[i] = 0f
    }

    // 2. Perform Radix-2 Cooley-Tukey FFT
    runCooleyTukeyFFT(realBuffer, imagBuffer)

    // 3. Compute FFT magnitudes: sqrt(real^2 + imag^2)
    val halfSize = FFT_SIZE / 2
    val sampleRate = pcmRepository.currentSampleRate.toFloat()
    val binHz = sampleRate / FFT_SIZE.toFloat()

    var bassSum = 0f
    var midSum = 0f
    var trebleSum = 0f
    var bassCount = 0
    var midCount = 0
    var trebleCount = 0
    var weightedFrequency = 0f
    var magnitudeSum = 0f

    for (k in 0 until halfSize) {
      val mag = hypot(realBuffer[k], imagBuffer[k]) / (FFT_SIZE / 2f)
      val normalizedMag = (ln(1f + mag * 10f) / ln(11f)).coerceIn(0f, 1f)
      rawMagnitudes[k] = normalizedMag

      val freq = k * binHz
      when {
        freq < 250f -> {
          bassSum += normalizedMag
          bassCount++
        }
        freq < 4000f -> {
          midSum += normalizedMag
          midCount++
        }
        freq < 16000f -> {
          trebleSum += normalizedMag
          trebleCount++
        }
      }

      weightedFrequency += normalizedMag * freq
      magnitudeSum += normalizedMag
    }

    // 4. Logarithmic frequency binning for spectrum bar chart
    val newBars = FloatArray(NUM_BARS)
    val minFreq = 20.0
    val maxFreq = 20000.0
    val logStep = (Math.log10(maxFreq) - Math.log10(minFreq)) / NUM_BARS

    for (b in 0 until NUM_BARS) {
      val fLow = Math.pow(10.0, Math.log10(minFreq) + b * logStep)
      val fHigh = Math.pow(10.0, Math.log10(minFreq) + (b + 1) * logStep)

      val kStart = (fLow / binHz).toInt().coerceIn(0, halfSize - 1)
      val kEnd = (fHigh / binHz).toInt().coerceIn(kStart + 1, halfSize)

      var sum = 0f
      var count = 0
      for (k in kStart until kEnd) {
        sum += rawMagnitudes[k]
        count++
      }

      val targetHeight = if (count > 0) (sum / count) * 1.6f else 0f
      val clampedTarget = targetHeight.coerceIn(0f, 1f)

      // Exponential Moving Average (EMA) bar smoothing
      val attack = 0.45f
      val release = 0.18f
      val current = smoothedBars[b]
      val factor = if (clampedTarget > current) attack else release
      smoothedBars[b] = current + (clampedTarget - current) * factor
      newBars[b] = smoothedBars[b]
    }

    // 5. Calculate Band Energies & Features
    val rawBass = if (bassCount > 0) (bassSum / bassCount) * 1.5f else 0f
    val rawMid = if (midCount > 0) (midSum / midCount) * 1.8f else 0f
    val rawTreble = if (trebleCount > 0) (trebleSum / trebleCount) * 2.2f else 0f
    val energy = (rawBass * 0.50f + rawMid * 0.34f + rawTreble * 0.16f).coerceIn(0f, 1f)
    val centroidHz = if (magnitudeSum > 0.0001f) weightedFrequency / magnitudeSum else 1000f
    val centroid = ((centroidHz - 120f) / 9000f).coerceIn(0f, 1f)

    // EMA attack/release feature smoothing
    smoothBass = envelope(smoothBass, rawBass.coerceIn(0f, 1f), 0.46f, 0.14f)
    smoothMid = envelope(smoothMid, rawMid.coerceIn(0f, 1f), 0.38f, 0.12f)
    smoothTreble = envelope(smoothTreble, rawTreble.coerceIn(0f, 1f), 0.34f, 0.11f)
    smoothEnergy = envelope(smoothEnergy, energy, 0.42f, 0.13f)

    // Beat Detection
    if (beatCooldownFrames > 0) beatCooldownFrames--
    val bassRise = smoothBass - previousBass
    val beatDetected = beatCooldownFrames == 0 && smoothBass > 0.16f && bassRise > 0.028f
    if (beatDetected) beatCooldownFrames = 5
    beatEnvelope = if (beatDetected) 1f else beatEnvelope * 0.72f
    previousBass = smoothBass

    // 6. Update AudioFeatures for GL renderers
    audioFeatures.bass = smoothBass
    audioFeatures.mid = smoothMid
    audioFeatures.treble = smoothTreble
    audioFeatures.energy = smoothEnergy
    audioFeatures.centroid = centroid
    audioFeatures.beat = beatEnvelope
    audioFeatures.markCaptureReceived()

    // 7. Downsample wave samples for waveform view
    val wavePoints = FloatArray(NUM_WAVE_POINTS)
    val step = FFT_SIZE / NUM_WAVE_POINTS
    for (i in 0 until NUM_WAVE_POINTS) {
      wavePoints[i] = pcmSamples[i * step].coerceIn(-1f, 1f)
    }

    // 8. Emit StateFlow VisualizationData
    _visualizationData.value = VisualizationData(
      magnitudes = newBars,
      waveform = wavePoints,
      bass = smoothBass,
      mid = smoothMid,
      treble = smoothTreble,
      energy = smoothEnergy,
      beat = beatEnvelope,
      isPlaying = true,
    )
  }

  /**
   * Fast in-place Cooley-Tukey Radix-2 FFT.
   */
  private fun runCooleyTukeyFFT(real: FloatArray, imag: FloatArray) {
    val n = FFT_SIZE

    // Bit-reversal permutation
    for (i in 0 until n) {
      val j = bitReversedIndices[i]
      if (j > i) {
        val tempR = real[i]
        real[i] = real[j]
        real[j] = tempR

        val tempI = imag[i]
        imag[i] = imag[j]
        imag[j] = tempI
      }
    }

    // Cooley-Tukey butterfly computation
    var len = 2
    while (len <= n) {
      val halfLen = len / 2
      val angle = -2.0 * PI / len
      val wStepR = cos(angle).toFloat()
      val wStepI = Math.sin(angle).toFloat()

      var i = 0
      while (i < n) {
        var wR = 1f
        var wI = 0f

        for (j in 0 until halfLen) {
          val pos = i + j
          val matchPos = pos + halfLen

          val uR = real[pos]
          val uI = imag[pos]

          val tR = real[matchPos] * wR - imag[matchPos] * wI
          val tI = real[matchPos] * wI + imag[matchPos] * wR

          real[pos] = uR + tR
          imag[pos] = uI + tI

          real[matchPos] = uR - tR
          imag[matchPos] = uI - tI

          val nextWR = wR * wStepR - wI * wStepI
          val nextWI = wR * wStepI + wI * wStepR
          wR = nextWR
          wI = nextWI
        }
        i += len
      }
      len = len shl 1
    }
  }

  private fun envelope(current: Float, target: Float, attack: Float, release: Float): Float {
    val factor = if (target > current) attack else release
    return current + (target - current) * factor
  }
}
