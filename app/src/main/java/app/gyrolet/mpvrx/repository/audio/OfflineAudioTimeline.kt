package app.gyrolet.mpvrx.repository.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

internal data class AudioTimeRange(val startMs: Long, val endMs: Long)

internal data class SubtitleAlignmentSample(val centerMs: Long, val offsetMs: Long, val confidence: Double)

internal data class SubtitleAlignmentResult(val delaySeconds: Double, val speed: Double, val confidence: Double)

internal fun matchSubtitleTiming(
  timeline: OfflineAudioTimeline,
  cues: List<AudioTimeRange>,
): SubtitleAlignmentSample? {
  if (timeline.levelsDb.size < 100 || cues.isEmpty()) return null
  val sortedLevels = timeline.levelsDb.sorted()
  val threshold = (sortedLevels[sortedLevels.size / 5] + 12f).coerceIn(-55f, -25f)
  val stepFrames = (100 / timeline.frameMs).coerceAtLeast(1)
  val samples = (timeline.levelsDb.indices step stepFrames).toList()
  val active = samples.map { timeline.levelsDb[it] > threshold }
  val activeCount = active.count { it }
  if (activeCount.toDouble() / active.size !in 0.05..0.9) return null
  val orderedCues = cues.sortedBy { it.startMs }
  val scores = (-10000L..10000L step 100L).map { offset ->
    var both = 0.0
    var predicted = 0.0
    var cueIndex = 0
    for (sampleIndex in samples.indices) {
      val subtitleTime = timeline.startMs + samples[sampleIndex] * timeline.frameMs.toLong() - offset
      while (cueIndex < orderedCues.size && orderedCues[cueIndex].endMs <= subtitleTime) cueIndex++
      val present = cueIndex < orderedCues.size && subtitleTime >= orderedCues[cueIndex].startMs
      if (present) {
        predicted++
        if (active[sampleIndex]) both++
      }
    }
    val count = samples.size.toDouble()
    val denominator = sqrt(predicted * activeCount * (count - predicted) * (count - activeCount))
    val score = if (denominator > 0) (count * both - predicted * activeCount) / denominator else -1.0
    offset to score
  }
  val best = scores.maxBy { it.second }
  val competitor = scores.filter { abs(it.first - best.first) >= 1500 }.maxOfOrNull { it.second } ?: -1.0
  if (best.second < 0.3 || best.second - competitor < 0.06 || abs(best.first) == 10000L) return null
  return SubtitleAlignmentSample((timeline.startMs + timeline.endMs) / 2, best.first, best.second)
}

internal fun fitSubtitleAlignment(samples: List<SubtitleAlignmentSample>): SubtitleAlignmentResult? {
  if (samples.size < 3) return null
  val meanTime = samples.map { (it.centerMs - it.offsetMs).toDouble() }.average()
  val meanOffset = samples.map { it.offsetMs.toDouble() }.average()
  val timeSpread = samples.maxOf { it.centerMs } - samples.minOf { it.centerMs }
  val denominator = samples.sumOf { (it.centerMs - it.offsetMs - meanTime) * (it.centerMs - it.offsetMs - meanTime) }
  val drift = if (timeSpread >= 60000 && denominator > 0) {
    samples.sumOf { (it.centerMs - it.offsetMs - meanTime) * (it.offsetMs - meanOffset) } / denominator
  } else {
    0.0
  }
  val offset = meanOffset - drift * meanTime
  val residual = samples.maxOf { abs(it.offsetMs - (offset + drift * (it.centerMs - it.offsetMs))) }
  if (residual > 300 || abs(offset) > 15000 || 1.0 + drift !in 0.97..1.03) return null
  return SubtitleAlignmentResult(offset / 1000, 1.0 + drift, samples.minOf { it.confidence })
}

internal data class OfflineAudioTimeline(
  val startMs: Long,
  val levelsDb: FloatArray,
  val frameMs: Int = 20,
) {
  val endMs: Long get() = startMs + levelsDb.size * frameMs.toLong()

  fun silenceRanges(
    thresholdDb: Float,
    minimumMs: Long,
    paddingMs: Long,
  ): List<AudioTimeRange> {
    require(thresholdDb.isFinite() && thresholdDb in -80f..-10f)
    require(minimumMs >= frameMs && paddingMs >= 0)
    val ranges = mutableListOf<AudioTimeRange>()
    var quietStart = -1
    for (index in 0..levelsDb.size) {
      val quiet = index < levelsDb.size && levelsDb[index] <= thresholdDb
      if (quiet && quietStart < 0) quietStart = index
      if (!quiet && quietStart >= 0) {
        val start = startMs + quietStart * frameMs.toLong()
        val end = startMs + index * frameMs.toLong()
        if (end - start >= minimumMs && end - start > paddingMs * 2) {
          ranges += AudioTimeRange(start + paddingMs, end - paddingMs)
        }
        quietStart = -1
      }
    }
    return ranges
  }

  companion object {
    fun fromMonoPcmWav(file: File, startMs: Long): OfflineAudioTimeline {
      val bytes = file.readBytes()
      require(bytes.size >= 44) { "Audio file is incomplete" }
      val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      require(buffer.getInt(0) == 0x46464952 && buffer.getInt(8) == 0x45564157) { "Expected WAV audio" }
      var position = 12
      var sampleRate = 0
      var dataOffset = -1
      var dataSize = 0
      while (position <= bytes.size - 8) {
        val chunkId = buffer.getInt(position)
        val chunkSize = buffer.getInt(position + 4)
        require(chunkSize >= 0 && chunkSize <= bytes.size - position - 8) { "Invalid WAV chunk" }
        val chunkStart = position + 8
        if (chunkId == 0x20746d66) {
          require(chunkSize >= 16)
          require(buffer.getShort(chunkStart).toInt() == 1 && buffer.getShort(chunkStart + 2).toInt() == 1)
          require(buffer.getShort(chunkStart + 14).toInt() == 16) { "Expected mono PCM16 audio" }
          sampleRate = buffer.getInt(chunkStart + 4)
        } else if (chunkId == 0x61746164) {
          dataOffset = chunkStart
          dataSize = chunkSize
        }
        position = chunkStart + chunkSize + (chunkSize and 1)
      }
      require(sampleRate > 0 && dataOffset >= 0 && dataSize >= 2 && dataSize % 2 == 0)
      val samplesPerFrame = (sampleRate / 50).coerceAtLeast(1)
      val sampleCount = dataSize / 2
      val frameCount = (sampleCount + samplesPerFrame - 1) / samplesPerFrame
      val levels = FloatArray(frameCount) { frame ->
        val first = frame * samplesPerFrame
        val last = (first + samplesPerFrame).coerceAtMost(sampleCount)
        var energy = 0.0
        for (sampleIndex in first until last) {
          val sample = buffer.getShort(dataOffset + sampleIndex * 2) / 32768.0
          energy += sample * sample
        }
        (20 * log10(sqrt(energy / (last - first)).coerceAtLeast(0.00001))).toFloat()
      }
      return OfflineAudioTimeline(startMs, levels)
    }
  }
}