package app.gyrolet.mpvrx.utils.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rational polyphase windowed-sinc sample-rate converter for 16-bit or float PCM.
 *
 * Output at the device mix rate lets AudioFlinger pass the stream through without its own
 * resampler. The Kaiser design keeps the stopband below the lower Nyquist frequency, so
 * conversion in either direction does not alias.
 */
@UnstableApi
internal class ExoResampleProcessor(private val targetSampleRateHz: Int) : BaseAudioProcessor() {
  private var upFactor = 1
  private var downFactor = 1
  private var tapsPerPhase = 0
  private var coefficients = FloatArray(0)
  private var history = emptyArray<FloatArray>()
  private var historyIndex = 0
  private var nextOutputTime = 0L
  private var inputFrames = 0L

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
      throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }
    if (targetSampleRateHz <= 0 || inputAudioFormat.sampleRate <= 0 || targetSampleRateHz == inputAudioFormat.sampleRate) {
      return AudioProcessor.AudioFormat.NOT_SET
    }
    return AudioProcessor.AudioFormat(targetSampleRateHz, inputAudioFormat.channelCount, inputAudioFormat.encoding)
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    // A flush into an inactive configuration must not leave the previous stream's kernel and ring
    // buffers addressable; they are sized for that stream's channel count and tap count.
    tapsPerPhase = 0
    coefficients = FloatArray(0)
    history = emptyArray()
    historyIndex = 0
    nextOutputTime = 0L
    inputFrames = 0L
    if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET || outputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) return
    val divisor = gcd(inputAudioFormat.sampleRate, outputAudioFormat.sampleRate)
    upFactor = outputAudioFormat.sampleRate / divisor
    downFactor = inputAudioFormat.sampleRate / divisor
    designFilter(inputAudioFormat.sampleRate, outputAudioFormat.sampleRate)
    history = Array(inputAudioFormat.channelCount) { FloatArray(tapsPerPhase) }
  }

  override fun onReset() {
    coefficients = FloatArray(0)
    history = emptyArray()
    tapsPerPhase = 0
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val bytesPerFrame = inputAudioFormat.bytesPerFrame
    if (tapsPerPhase == 0 || history.isEmpty() || bytesPerFrame <= 0) {
      inputBuffer.position(inputBuffer.limit())
      replaceOutputBuffer(0).flip()
      return
    }
    val frames = inputBuffer.remaining() / bytesPerFrame
    val output = replaceOutputBuffer(maxOutputFrames(frames) * outputAudioFormat.bytesPerFrame)
    resample(inputBuffer, frames, output)
    // A trailing sub-frame remainder would otherwise be handed back unchanged forever and stall
    // the sink, which never retries with more data for the same buffer.
    inputBuffer.position(inputBuffer.limit())
    output.flip()
  }

  override fun onQueueEndOfStream() {
    if (tapsPerPhase == 0 || history.isEmpty()) return
    // Zero input equal to the filter delay pushes the last real samples through the kernel.
    val tailFrames = tapsPerPhase / 2 + 1
    val silence = ByteBuffer.allocate(tailFrames * inputAudioFormat.bytesPerFrame).order(ByteOrder.nativeOrder())
    val output = replaceOutputBuffer(maxOutputFrames(tailFrames) * outputAudioFormat.bytesPerFrame)
    resample(silence, tailFrames, output)
    output.flip()
  }

  private fun maxOutputFrames(inputFrameCount: Int): Int =
    ((inputFrameCount.toLong() * upFactor + downFactor - 1) / downFactor + 1).toInt()

  private fun resample(input: ByteBuffer, frames: Int, output: ByteBuffer) {
    val channels = history.size
    val floatInput = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
    val floatOutput = outputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
    repeat(frames) {
      historyIndex = if (historyIndex + 1 == tapsPerPhase) 0 else historyIndex + 1
      for (channel in 0 until channels) {
        val sample = if (floatInput) input.float else input.short / 32768f
        history[channel][historyIndex] = if (sample.isFinite()) sample else 0f
      }
      val newest = inputFrames
      inputFrames++
      // Every output whose newest contributing input sample is the one just queued.
      while (nextOutputTime / upFactor <= newest) {
        val phaseOffset = (nextOutputTime % upFactor).toInt() * tapsPerPhase
        for (channel in 0 until channels) {
          val ring = history[channel]
          var index = historyIndex
          var accumulator = 0f
          for (tap in 0 until tapsPerPhase) {
            accumulator += coefficients[phaseOffset + tap] * ring[index]
            index = if (index == 0) tapsPerPhase - 1 else index - 1
          }
          if (floatOutput) {
            output.putFloat(accumulator)
          } else {
            output.putShort((accumulator * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
          }
        }
        nextOutputTime += downFactor
      }
    }
  }

  private fun designFilter(inputRate: Int, outputRate: Int) {
    val upsampledRate = inputRate.toDouble() * upFactor
    val nyquist = min(inputRate, outputRate) / 2.0
    val transition = nyquist * TRANSITION_FRACTION
    val totalTaps = ceil((STOPBAND_DB - 7.95) * upsampledRate / (14.357 * transition)).toInt()
    tapsPerPhase = ((totalTaps + upFactor - 1) / upFactor).coerceIn(MIN_TAPS_PER_PHASE, MAX_TAPS_PER_PHASE)
    val length = tapsPerPhase * upFactor
    val cutoff = (nyquist - transition / 2.0) / upsampledRate
    val beta = 0.1102 * (STOPBAND_DB - 8.7)
    val center = (length - 1) / 2.0
    val windowScale = 1.0 / besselI0(beta)
    val prototype = DoubleArray(length) { n ->
      val offset = n - center
      val sinc = if (offset == 0.0) 2.0 * cutoff else sin(2.0 * PI * cutoff * offset) / (PI * offset)
      val ratio = offset / center
      sinc * besselI0(beta * sqrt((1.0 - ratio * ratio).coerceAtLeast(0.0))) * windowScale
    }
    coefficients = FloatArray(length)
    for (phase in 0 until upFactor) {
      var sum = 0.0
      for (tap in 0 until tapsPerPhase) sum += prototype[tap * upFactor + phase]
      val scale = if (abs(sum) > 1e-9) 1.0 / sum else 1.0
      for (tap in 0 until tapsPerPhase) {
        coefficients[phase * tapsPerPhase + tap] = (prototype[tap * upFactor + phase] * scale).toFloat()
      }
    }
  }

  private fun besselI0(x: Double): Double {
    var sum = 1.0
    var term = 1.0
    val quarterSquare = x * x / 4.0
    var k = 1
    while (term > sum * 1e-12 && k < 1000) {
      term *= quarterSquare / (k.toDouble() * k)
      sum += term
      k++
    }
    return sum
  }

  private fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
      val remainder = x % y
      x = y
      y = remainder
    }
    return x
  }

  companion object {
    private const val STOPBAND_DB = 96.0
    private const val TRANSITION_FRACTION = 0.12
    private const val MIN_TAPS_PER_PHASE = 16
    private const val MAX_TAPS_PER_PHASE = 4096
  }
}
