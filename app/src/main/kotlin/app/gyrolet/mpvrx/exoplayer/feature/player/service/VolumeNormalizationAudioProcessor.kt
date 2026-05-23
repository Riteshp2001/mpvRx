package app.gyrolet.mpvrx.exoplayer.feature.player.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import app.gyrolet.mpvrx.exoplayer.core.common.Logger

@OptIn(UnstableApi::class)
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var isEnabled: Boolean = false

    private var currentGain = 1f
    private var hasLoggedProcessing = false
    private var lastLoggedGain = 1f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
        inputAudioFormat
    } else {
        AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        val outputBuffer = replaceOutputBuffer(size)
        val shouldNormalize = isEnabled
        if (!shouldNormalize || size < BYTES_PER_SAMPLE) {
            currentGain = 1f
            copyInput(inputBuffer, outputBuffer, position, limit)
            inputBuffer.position(limit)
            outputBuffer.flip()
            return
        }

        if (!hasLoggedProcessing) {
            hasLoggedProcessing = true
            Logger.debug(TAG, "Volume normalization processing: encoding=${inputAudioFormat.encoding}, bytes=$size")
        }
        val gain = calculateGain(inputBuffer, position, limit)
        logGainChange(gain)
        applyGain(inputBuffer, outputBuffer, position, limit, gain)
        inputBuffer.position(limit)
        outputBuffer.flip()
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        currentGain = 1f
        hasLoggedProcessing = false
        lastLoggedGain = 1f
    }

    override fun onReset() {
        currentGain = 1f
        hasLoggedProcessing = false
        lastLoggedGain = 1f
    }

    private fun calculateGain(
        inputBuffer: ByteBuffer,
        position: Int,
        limit: Int,
    ): Float {
        var sumSquares = 0.0
        var peak = 0
        var sampleCount = 0
        var index = position
        while (index + 1 < limit) {
            val sample = inputBuffer.readLittleEndianShort(index)
            sumSquares += (sample / MAX_SAMPLE_VALUE) * (sample / MAX_SAMPLE_VALUE)
            peak = maxOf(peak, abs(sample))
            sampleCount++
            index += BYTES_PER_SAMPLE
        }
        if (sampleCount == 0) return currentGain

        val rms = sqrt(sumSquares / sampleCount).toFloat()
        if (rms <= SILENCE_RMS || peak == 0) return currentGain

        // 用短时 RMS 估计增益，峰值只负责防止削波。
        val rmsGain = TARGET_RMS / rms
        val peakGain = MAX_PEAK_SAMPLE / peak.toFloat()
        val targetGain = min(rmsGain, peakGain).coerceIn(MIN_GAIN, MAX_GAIN)
        val smoothing = if (targetGain < currentGain) GAIN_REDUCTION_SMOOTHING else GAIN_INCREASE_SMOOTHING
        val smoothedGain = currentGain + (targetGain - currentGain) * smoothing
        currentGain = min(smoothedGain, peakGain).coerceIn(MIN_GAIN, MAX_GAIN)
        return currentGain
    }

    private fun logGainChange(gain: Float) {
        if ((gain - lastLoggedGain).absoluteValue < LOG_GAIN_DELTA) return
        lastLoggedGain = gain
        Logger.debug(TAG, "Volume normalization gain=${gain.formatGainForLog()}")
    }

    private fun copyInput(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        position: Int,
        limit: Int,
    ) {
        for (index in position until limit) {
            outputBuffer.put(inputBuffer.get(index))
        }
    }

    private fun applyGain(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        position: Int,
        limit: Int,
        gain: Float,
    ) {
        var index = position
        while (index + 1 < limit) {
            val sample = inputBuffer.readLittleEndianShort(index)
            val scaledSample = (sample * gain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputBuffer.writeLittleEndianShort(scaledSample)
            index += BYTES_PER_SAMPLE
        }
        if (index < limit) {
            outputBuffer.put(inputBuffer.get(index))
        }
    }

    private fun Float.formatGainForLog(): String = ((this * 100).roundToInt() / 100f).toString()

    private fun ByteBuffer.readLittleEndianShort(index: Int): Int {
        val low = get(index).toInt() and 0xFF
        val high = get(index + 1).toInt()
        return ((high shl 8) or low).toShort().toInt()
    }

    private fun ByteBuffer.writeLittleEndianShort(value: Int) {
        put((value and 0xFF).toByte())
        put(((value shr 8) and 0xFF).toByte())
    }

    private companion object {
        private const val TAG = "VolumeNormalization"
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_SAMPLE_VALUE = 32768.0
        private const val TARGET_RMS = 0.14f
        private const val SILENCE_RMS = 0.001f
        private const val MIN_GAIN = 0.25f
        private const val MAX_GAIN = 3f
        private const val MAX_PEAK_SAMPLE = 30_000f
        private const val GAIN_REDUCTION_SMOOTHING = 0.55f
        private const val GAIN_INCREASE_SMOOTHING = 0.08f
        private const val LOG_GAIN_DELTA = 0.5f
    }
}
