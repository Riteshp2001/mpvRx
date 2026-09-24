package app.gyrolet.mpvrx.utils.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import app.gyrolet.mpvrx.preferences.ReplayGainMode
import app.gyrolet.mpvrx.ui.player.AudioChannelMix
import app.gyrolet.mpvrx.ui.player.AudioProcessingSettings
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@UnstableApi
internal class ExoAudioProcessor(
  initialSettings: AudioProcessingSettings,
) : BaseAudioProcessor() {
  @Volatile var settings = initialSettings
  @Volatile var bypass = true
  @Volatile var preserveSource = false
  @Volatile var extraGain = 1f
  @Volatile var replayGain = 1f
  private var matrix: ChannelMixingMatrix? = null
  private var inputFrame = FloatArray(0)
  private var outputFrame = FloatArray(0)
  private var filters = emptyArray<Array<PeakFilter>>()
  private var appliedSettings: AudioProcessingSettings? = null
  private var rmsSquared = 0.0
  private var envelope = 0.0
  private var normalizationGain = 1.0
  private var limiterGain = 1.0
  private var outputGain = 1.0
  private var equalizerMix = 0.0
  private var compressionGain = 1.0
  private var sampleRate = 48_000

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    if (inputAudioFormat.encoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
      throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }
    val channels = inputAudioFormat.channelCount
    val targetChannels =
      if (bypass || preserveSource || channels > 2) {
        channels
      } else {
        when (settings.channelMix) {
          AudioChannelMix.Mono -> 1
          AudioChannelMix.Stereo, AudioChannelMix.ReverseStereo -> 2
          AudioChannelMix.Auto -> channels
        }
      }
    return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, targetChannels, inputAudioFormat.encoding)
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) return
    sampleRate = inputAudioFormat.sampleRate
    val channels = inputAudioFormat.channelCount
    val targetChannels = outputAudioFormat.channelCount
    matrix = if (channels == targetChannels) null else ChannelMixingMatrix.createForConstantGain(channels, targetChannels)
    inputFrame = FloatArray(channels)
    outputFrame = FloatArray(targetChannels)
    filters = Array(targetChannels) { Array(5) { band -> PeakFilter(FREQUENCIES[band], sampleRate) } }
    appliedSettings = null
    rmsSquared = 0.0
    envelope = 0.0
    normalizationGain = 1.0
    limiterGain = 1.0
    outputGain = 1.0
    equalizerMix = 0.0
    compressionGain = 1.0
    filters.forEach { channel -> channel.forEach(PeakFilter::reset) }
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val current = settings
    val processing = !bypass && !preserveSource && inputAudioFormat.channelCount <= 2
    val output = replaceOutputBuffer(inputBuffer.remaining() / inputAudioFormat.bytesPerFrame * outputAudioFormat.bytesPerFrame)
    if (!processing && inputAudioFormat.channelCount == outputAudioFormat.channelCount) {
      output.put(inputBuffer)
      output.flip()
      return
    }
    if (appliedSettings != current) {
      filters.forEach { channel ->
        channel.forEachIndexed { band, filter ->
          filter.setGain(if (current.equalizerEnabled) current.bandGains.getOrElse(band) { 0 }.coerceIn(-15, 15) else 0)
        }
      }
      appliedSettings = current
    }
    val rmsStep = smoothing(0.5)
    val gainStep = smoothing(0.02)
    val compressorAttack = smoothing(0.005)
    val compressorRelease = smoothing(0.05)
    val limiterRelease = smoothing(0.05)
    val normalizationAttack = smoothing(0.5)
    val normalizationRelease = smoothing(3.0)
    val eqHeadroom = if (current.equalizerEnabled) current.bandGains.maxOrNull()?.coerceAtLeast(0) ?: 0 else 0
    val boostDb = if (current.equalizerEnabled) current.boostDb.coerceIn(0, 10) else 0
    val taggedGain = replayGain.takeIf { it.isFinite() }?.coerceIn(0.03f, 10f) ?: 1f
    val targetGain = dbGain(boostDb - eqHeadroom.toDouble()) * extraGain.coerceIn(1f, 3f) * taggedGain
    val mixing = matrix

    while (inputBuffer.remaining() >= inputAudioFormat.bytesPerFrame) {
      for (channel in inputFrame.indices) {
        val sample = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputBuffer.float else inputBuffer.short / 32768f
        inputFrame[channel] = if (sample.isFinite()) sample.coerceIn(-8f, 8f) else 0f
      }
      for (channel in outputFrame.indices) {
        var sample = if (mixing == null) inputFrame[channel] else 0f
        if (mixing != null) {
          for (inputChannel in inputFrame.indices) {
            sample += inputFrame[inputChannel] * mixing.getMixingCoefficient(inputChannel, channel)
          }
        }
        outputFrame[channel] = sample
      }
      if (processing && current.channelMix == AudioChannelMix.ReverseStereo && outputFrame.size == 2) {
        val left = outputFrame[0]
        outputFrame[0] = outputFrame[1]
        outputFrame[1] = left
      }
      var power = 0.0
      outputFrame.forEach { power += it * it }
      rmsSquared += rmsStep * (power / outputFrame.size - rmsSquared)
      val rms = sqrt(rmsSquared.coerceAtLeast(0.0))
      val desiredNormalization = if (processing && current.normalize && current.replayGain == ReplayGainMode.Off && rms > 0.00316) {
        (0.1259 / rms).coerceIn(0.25, 2.0)
      } else 1.0
      normalizationGain += (if (desiredNormalization < normalizationGain) normalizationAttack else normalizationRelease) *
        (desiredNormalization - normalizationGain)
      outputGain += gainStep * (targetGain - outputGain)
      equalizerMix += gainStep * ((if (processing && current.equalizerEnabled) 1.0 else 0.0) - equalizerMix)
      var peak = 0.0
      for (channel in outputFrame.indices) {
        var sample = outputFrame[channel].toDouble() * normalizationGain
        if (equalizerMix > 0.000001) {
          var filtered = sample
          filters[channel].forEach { filter -> filtered = filter.process(filtered) }
          sample += (filtered - sample) * equalizerMix
        }
        outputFrame[channel] = (sample * outputGain).toFloat()
        peak = max(peak, abs(outputFrame[channel].toDouble()))
      }
      envelope += (if (peak > envelope) compressorAttack else compressorRelease) * (peak - envelope)
      val targetCompressionGain =
        if (processing && current.compress) {
          val aboveThreshold = (20 * log10(envelope.coerceAtLeast(0.000001)) + 20).coerceAtLeast(0.0)
          dbGain(-aboveThreshold * 0.75) * 2.0
        } else {
          1.0
        }
      compressionGain += gainStep * (targetCompressionGain - compressionGain)
      val requiredLimit = min(1.0, 0.98 / (peak * compressionGain).coerceAtLeast(0.000001))
      limiterGain = if (requiredLimit < limiterGain) requiredLimit else limiterGain + limiterRelease * (requiredLimit - limiterGain)
      for (sample in outputFrame) {
        val result = (sample * compressionGain * limiterGain).let { if (it.isFinite()) it.coerceIn(-0.98, 0.98) else 0.0 }
        if (outputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) output.putFloat(result.toFloat())
        else output.putShort((result * 32767).toInt().toShort())
      }
    }
    output.flip()
  }

  private fun smoothing(seconds: Double): Double = 1.0 - exp(-1.0 / (sampleRate * seconds))

  private class PeakFilter(private val frequency: Double, private val sampleRate: Int) {
    private var firstNumerator = 1.0
    private var secondNumerator = 0.0
    private var thirdNumerator = 0.0
    private var firstDenominator = 0.0
    private var secondDenominator = 0.0
    private var targetFirstNumerator = 1.0
    private var targetSecondNumerator = 0.0
    private var targetThirdNumerator = 0.0
    private var targetFirstDenominator = 0.0
    private var targetSecondDenominator = 0.0
    private val coefficientStep = 1.0 - exp(-1.0 / (sampleRate * 0.02))
    private var firstState = 0.0
    private var secondState = 0.0

    fun setGain(gainDb: Int) {
      if (gainDb == 0 || frequency >= sampleRate / 2.0) {
        targetFirstNumerator = 1.0
        targetSecondNumerator = 0.0
        targetThirdNumerator = 0.0
        targetFirstDenominator = 0.0
        targetSecondDenominator = 0.0
        return
      }
      val amplitude = 10.0.pow(gainDb / 40.0)
      val omega = 2.0 * PI * frequency / sampleRate
      val alpha = sin(omega) / (2.0 * 0.9)
      val denominator = 1.0 + alpha / amplitude
      targetFirstNumerator = (1.0 + alpha * amplitude) / denominator
      targetSecondNumerator = -2.0 * cos(omega) / denominator
      targetThirdNumerator = (1.0 - alpha * amplitude) / denominator
      targetFirstDenominator = targetSecondNumerator
      targetSecondDenominator = (1.0 - alpha / amplitude) / denominator
    }

    fun process(sample: Double): Double {
      firstNumerator += coefficientStep * (targetFirstNumerator - firstNumerator)
      secondNumerator += coefficientStep * (targetSecondNumerator - secondNumerator)
      thirdNumerator += coefficientStep * (targetThirdNumerator - thirdNumerator)
      firstDenominator += coefficientStep * (targetFirstDenominator - firstDenominator)
      secondDenominator += coefficientStep * (targetSecondDenominator - secondDenominator)
      val result = firstNumerator * sample + firstState
      firstState = secondNumerator * sample - firstDenominator * result + secondState
      secondState = thirdNumerator * sample - secondDenominator * result
      return result
    }

    fun reset() {
      firstState = 0.0
      secondState = 0.0
      firstNumerator = targetFirstNumerator
      secondNumerator = targetSecondNumerator
      thirdNumerator = targetThirdNumerator
      firstDenominator = targetFirstDenominator
      secondDenominator = targetSecondDenominator
    }
  }

  companion object {
    private val FREQUENCIES = doubleArrayOf(60.0, 230.0, 910.0, 3_600.0, 14_000.0)
    private fun dbGain(db: Double): Double = 10.0.pow(db / 20.0)
  }
}