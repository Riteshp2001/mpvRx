package app.gyrolet.mpvrx.exoplayer.feature.player.engine.media3

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.RandomAccessFile
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.getPath

// MKV Cues 预解析结果
data class MkvCuePoint(
    val timeUs: Long,
    val clusterPosition: Long,
)

// 轻量级 EBML 解析器，仅读取 SeekHead + Cues
// 使用 RandomAccessFile + 256KB 读取缓冲，大文件耗时 < 1 秒
object MkvCuesParser {

    private const val TAG = "MkvCuesParser"
    internal const val BUFFER_SIZE = 256 * 1024
    private const val MAX_CUE_POINTS = 10_000

    // EBML 元素 ID
    private const val ID_EBML: Long = 0x1A45DFA3
    private const val ID_SEGMENT: Long = 0x18538067
    private const val ID_SEEK_HEAD: Long = 0x114D9B74
    private const val ID_SEEK: Long = 0x4DBB
    private const val ID_SEEK_ID: Long = 0x53AB
    private const val ID_SEEK_POSITION: Long = 0x53AC
    private const val ID_INFO: Long = 0x1549A966
    private const val ID_TIMESTAMP_SCALE: Long = 0x2AD7B1
    private const val ID_CUES: Long = 0x1C53BB6B
    private const val ID_CUE_POINT: Long = 0xBB
    private const val ID_CUE_TIME: Long = 0xB3
    private const val ID_CUE_TRACK_POSITIONS: Long = 0xB7
    private const val ID_CUE_CLUSTER_POSITION: Long = 0xF1

    fun parse(context: Context, uri: Uri): List<MkvCuePoint>? = try {
        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> parseContent(context, uri)
            ContentResolver.SCHEME_FILE -> parseFile(uri.path!!)
            else -> getPath(context, uri)?.let(::parseFile)
        }
    } catch (e: Exception) {
        Logger.error(TAG, "Failed to parse MKV Cues", e)
        null
    }

    private fun parseFile(path: String): List<MkvCuePoint>? {
        RandomAccessFile(path, "r").use { raf ->
            return parseWithReader(BufferedRandomReader(raf))
        }
    }

    private fun parseContent(context: Context, uri: Uri): List<MkvCuePoint>? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return pfd.use {
            java.io.FileInputStream(it.fileDescriptor).use { fis ->
                val channel = fis.channel
                return parseWithReader(BufferedChannelReader(channel))
            }
        }
    }

    private fun parseWithReader(reader: BufferedReader): List<MkvCuePoint>? {
        // 跳过 EBML 头
        val ebmlHeader = reader.readElementHeader() ?: return null
        if (ebmlHeader.id != ID_EBML) return null
        reader.skip(ebmlHeader.dataSize)

        // 读取 Segment 头
        val segmentHeader = reader.readElementHeader() ?: return null
        if (segmentHeader.id != ID_SEGMENT) return null
        val segmentDataStart = reader.position()

        // 扫描 Segment 子元素：SeekHead + Info
        var cuesPosition: Long? = null
        var timestampScale = 1_000_000L // 默认 1ms = 1_000_000ns
        val scanEnd = minOf(segmentDataStart + segmentHeader.dataSize, reader.fileSize())

        while (reader.position() < scanEnd) {
            val header = reader.readElementHeader() ?: break

            when (header.id) {
                ID_SEEK_HEAD -> {
                    val seekHeadEnd = reader.position() + header.dataSize
                    val parsedCuesPos = parseSeekHead(reader, seekHeadEnd, segmentDataStart)
                    if (parsedCuesPos != null) cuesPosition = parsedCuesPos
                }
                ID_INFO -> {
                    val infoEnd = reader.position() + header.dataSize
                    while (reader.position() < infoEnd) {
                        val infoChild = reader.readElementHeader() ?: break
                        when (infoChild.id) {
                            ID_TIMESTAMP_SCALE -> timestampScale = reader.readUint(infoChild.dataSize)
                            else -> reader.skip(infoChild.dataSize)
                        }
                    }
                }
                else -> {
                    // 遇到 Cluster 等大元素时停止扫描头部
                    if (cuesPosition != null) break
                    reader.skip(header.dataSize)
                }
            }
        }

        if (cuesPosition == null) {
            Logger.debug(TAG, "No Cues position found in SeekHead")
            return null
        }

        // 直接跳到 Cues 位置
        reader.seek(cuesPosition)
        val cuesHeader = reader.readElementHeader() ?: return null
        if (cuesHeader.id != ID_CUES) {
            Logger.error(TAG, "Expected Cues at $cuesPosition, got 0x${cuesHeader.id.toString(16)}")
            return null
        }

        val cues = parseCues(reader, reader.position() + cuesHeader.dataSize, timestampScale, segmentDataStart)
        if (cues.isEmpty()) return null

        Logger.debug(TAG, "Parsed ${cues.size} cue points (max=$MAX_CUE_POINTS), timestampScale=$timestampScale")
        return cues
    }

    private fun parseSeekHead(
        reader: BufferedReader,
        seekHeadEnd: Long,
        segmentDataStart: Long,
    ): Long? {
        var cuesPosition: Long? = null
        while (reader.position() < seekHeadEnd) {
            val header = reader.readElementHeader() ?: break
            if (header.id != ID_SEEK) {
                reader.skip(header.dataSize)
                continue
            }

            val seekEnd = reader.position() + header.dataSize
            var seekId: Long? = null
            var seekPosition: Long? = null

            while (reader.position() < seekEnd) {
                val child = reader.readElementHeader() ?: break
                when (child.id) {
                    ID_SEEK_ID -> seekId = reader.readUint(child.dataSize)
                    ID_SEEK_POSITION -> seekPosition = reader.readUint(child.dataSize)
                    else -> reader.skip(child.dataSize)
                }
            }

            if (seekId == ID_CUES && seekPosition != null) {
                cuesPosition = segmentDataStart + seekPosition
            }
        }
        return cuesPosition
    }

    // 两阶段解析：先计数再按采样步长收集，避免分配 80 万个临时对象导致 OOM
    private fun parseCues(
        reader: BufferedReader,
        cuesEnd: Long,
        timestampScale: Long,
        segmentDataStart: Long,
    ): List<MkvCuePoint> {
        // 第一阶段，快速扫描计数，只读元素头并跳过数据
        val cuesStart = reader.position()
        var totalCount = 0
        while (reader.position() < cuesEnd) {
            val header = reader.readElementHeader() ?: break
            if (header.id == ID_CUE_POINT) totalCount++
            reader.skip(header.dataSize)
        }
        if (totalCount == 0) return emptyList()

        // 计算采样步长：超过上限时均匀跳过
        val step = if (totalCount > MAX_CUE_POINTS) {
            totalCount.toDouble() / MAX_CUE_POINTS
        } else {
            1.0
        }
        val expectedSize = minOf(totalCount, MAX_CUE_POINTS)

        // 第二阶段：回到起点，按步长只解析需要保留的 CuePoint
        reader.seek(cuesStart)
        val cuePoints = ArrayList<MkvCuePoint>(expectedSize)
        var index = 0
        var nextKeepIndex = 0.0

        while (reader.position() < cuesEnd) {
            val header = reader.readElementHeader() ?: break
            if (header.id != ID_CUE_POINT) {
                reader.skip(header.dataSize)
                continue
            }

            val shouldKeep = index >= nextKeepIndex.toInt()
            if (!shouldKeep) {
                reader.skip(header.dataSize)
                index++
                continue
            }

            val cueEnd = reader.position() + header.dataSize
            var cueTime: Long? = null
            var clusterPosition: Long? = null

            while (reader.position() < cueEnd) {
                val child = reader.readElementHeader() ?: break
                when (child.id) {
                    ID_CUE_TIME -> cueTime = reader.readUint(child.dataSize)
                    ID_CUE_TRACK_POSITIONS -> {
                        val posEnd = reader.position() + child.dataSize
                        while (reader.position() < posEnd) {
                            val posChild = reader.readElementHeader() ?: break
                            when (posChild.id) {
                                ID_CUE_CLUSTER_POSITION -> {
                                    if (clusterPosition == null) {
                                        clusterPosition = reader.readUint(posChild.dataSize)
                                    } else {
                                        reader.skip(posChild.dataSize)
                                    }
                                }
                                else -> reader.skip(posChild.dataSize)
                            }
                        }
                    }
                    else -> reader.skip(child.dataSize)
                }
            }

            if (cueTime != null && clusterPosition != null) {
                val timeUs = cueTime * timestampScale / 1_000L
                val absolutePosition = segmentDataStart + clusterPosition
                cuePoints.add(MkvCuePoint(timeUs = timeUs, clusterPosition = absolutePosition))
                nextKeepIndex += step
            }
            index++
        }
        return cuePoints
    }

    // 从 content:// URI 解析文件路径
    private fun getPath(context: Context, uri: Uri): String? = try {
        context.getPath(uri)
    } catch (_: Exception) {
        null
    }
}

// 带缓冲的读取器接口
private interface BufferedReader {
    fun position(): Long
    fun fileSize(): Long
    fun seek(position: Long)
    fun skip(size: Long)
    fun readByte(): Int
    fun readBytes(count: Int): ByteArray

    fun readElementHeader(): ElementHeader? {
        val id = readVint(withLengthBits = true) ?: return null
        val dataSize = readVint(withLengthBits = false) ?: return null
        return ElementHeader(id, dataSize)
    }

    fun readUint(size: Long): Long {
        if (size > 8) throw IOException("uint too large: $size")
        var value = 0L
        for (i in 0 until size.toInt()) {
            val b = readByte()
            if (b == -1) throw IOException("Unexpected EOF")
            value = (value shl 8) or b.toLong()
        }
        return value
    }

    // 读取 EBML 变长整数
    // withLengthBits=true 保留长度位，false 清除长度位
    fun readVint(withLengthBits: Boolean): Long? {
        val firstByte = readByte()
        if (firstByte == -1 || firstByte == 0) return null

        val length = Integer.numberOfLeadingZeros(firstByte) - 23
        if (length < 1 || length > 8) return null

        var value = if (withLengthBits) {
            firstByte.toLong()
        } else {
            (firstByte.toLong() and ((1L shl (8 - length)) - 1))
        }

        for (i in 1 until length) {
            val b = readByte()
            if (b == -1) return null
            value = (value shl 8) or b.toLong()
        }

        return value
    }
}

// 基于 RandomAccessFile 的带缓冲读取器
// 256KB 缓冲减少系统调用，seek 操作清空缓冲区重新填充
private class BufferedRandomReader(private val raf: RandomAccessFile) : BufferedReader {

    private val buffer = ByteArray(MkvCuesParser.BUFFER_SIZE)
    private var bufferOffset = 0L // buffer[0] 对应的文件偏移
    private var bufferLength = 0 // buffer 中有效数据长度
    private var bufferPos = 0 // buffer 中当前读取位置

    override fun position(): Long = bufferOffset + bufferPos
    override fun fileSize(): Long = raf.length()

    override fun seek(position: Long) {
        // 如果目标位置在当前缓冲区内，直接移动指针
        val relativePos = position - bufferOffset
        if (relativePos in 0 until bufferLength) {
            bufferPos = relativePos.toInt()
            return
        }
        raf.seek(position)
        bufferOffset = position
        bufferLength = 0
        bufferPos = 0
    }

    override fun skip(size: Long) {
        seek(position() + size)
    }

    override fun readByte(): Int {
        if (bufferPos >= bufferLength) {
            fillBuffer()
            if (bufferLength == 0) return -1
        }
        return buffer[bufferPos++].toInt() and 0xFF
    }

    override fun readBytes(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            if (bufferPos >= bufferLength) {
                fillBuffer()
                if (bufferLength == 0) throw IOException("Unexpected EOF")
            }
            val available = bufferLength - bufferPos
            val toCopy = minOf(available, count - offset)
            System.arraycopy(buffer, bufferPos, result, offset, toCopy)
            bufferPos += toCopy
            offset += toCopy
        }
        return result
    }

    private fun fillBuffer() {
        bufferOffset += bufferPos
        raf.seek(bufferOffset)
        bufferLength = raf.read(buffer)
        if (bufferLength == -1) bufferLength = 0
        bufferPos = 0
    }
}

// 基于 FileChannel 的带缓冲读取器（用于 content:// URI 回退路径）
private class BufferedChannelReader(
    private val channel: java.nio.channels.FileChannel,
) : BufferedReader {

    private val buffer = ByteArray(MkvCuesParser.BUFFER_SIZE)
    private var bufferOffset = 0L
    private var bufferLength = 0
    private var bufferPos = 0

    override fun position(): Long = bufferOffset + bufferPos
    override fun fileSize(): Long = channel.size()

    override fun seek(position: Long) {
        val relativePos = position - bufferOffset
        if (relativePos in 0 until bufferLength) {
            bufferPos = relativePos.toInt()
            return
        }
        channel.position(position)
        bufferOffset = position
        bufferLength = 0
        bufferPos = 0
    }

    override fun skip(size: Long) {
        seek(position() + size)
    }

    override fun readByte(): Int {
        if (bufferPos >= bufferLength) {
            fillBuffer()
            if (bufferLength == 0) return -1
        }
        return buffer[bufferPos++].toInt() and 0xFF
    }

    override fun readBytes(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            if (bufferPos >= bufferLength) {
                fillBuffer()
                if (bufferLength == 0) throw IOException("Unexpected EOF")
            }
            val available = bufferLength - bufferPos
            val toCopy = minOf(available, count - offset)
            System.arraycopy(buffer, bufferPos, result, offset, toCopy)
            bufferPos += toCopy
            offset += toCopy
        }
        return result
    }

    private fun fillBuffer() {
        bufferOffset += bufferPos
        channel.position(bufferOffset)
        val bb = java.nio.ByteBuffer.wrap(buffer)
        bufferLength = channel.read(bb)
        if (bufferLength == -1) bufferLength = 0
        bufferPos = 0
    }
}

private data class ElementHeader(val id: Long, val dataSize: Long)
