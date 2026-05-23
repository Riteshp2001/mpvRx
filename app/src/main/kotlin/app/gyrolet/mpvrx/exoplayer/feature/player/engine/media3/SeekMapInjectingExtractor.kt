package app.gyrolet.mpvrx.exoplayer.feature.player.engine.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput

// 包装 Extractor，拦截 seekMap() 回调
// 当原始 extractor 提供 Unseekable SeekMap 时，替换为预解析的 IndexSeekMap
@OptIn(UnstableApi::class)
class SeekMapInjectingExtractor(
    private val delegate: Extractor,
    private val precomputedSeekMap: SeekMap?,
) : Extractor {

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        if (precomputedSeekMap != null) {
            delegate.init(SeekMapInterceptingOutput(output, precomputedSeekMap))
        } else {
            delegate.init(output)
        }
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

@OptIn(UnstableApi::class)
private class SeekMapInterceptingOutput(
    private val delegate: ExtractorOutput,
    private val precomputedSeekMap: SeekMap,
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput = delegate.track(id, type)

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) {
        // 原始 extractor（seekForCuesEnabled=false）提供 Unseekable SeekMap
        // 替换为预解析的 seekable SeekMap
        if (!seekMap.isSeekable) {
            delegate.seekMap(precomputedSeekMap)
        } else {
            delegate.seekMap(seekMap)
        }
    }
}

// 从预解析的 Cues 构建 IndexSeekMap
@OptIn(UnstableApi::class)
fun buildSeekMapFromCues(cuePoints: List<MkvCuePoint>, durationUs: Long): IndexSeekMap {
    val timesUs = LongArray(cuePoints.size) { cuePoints[it].timeUs }
    val positions = LongArray(cuePoints.size) { cuePoints[it].clusterPosition }
    return IndexSeekMap(positions, timesUs, durationUs)
}
