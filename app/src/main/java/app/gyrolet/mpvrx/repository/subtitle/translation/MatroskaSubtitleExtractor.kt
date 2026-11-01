/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.zip.Inflater

/** Random-access bytes; the video file is only ever read. */
interface SeekableSource {
  val size: Long

  /** Reads up to [length] bytes at [position]; returns the count read, or -1 at the end. */
  fun read(
    position: Long,
    into: ByteArray,
    offset: Int,
    length: Int,
  ): Int
}

class FileChannelSource(
  private val channel: FileChannel,
) : SeekableSource {
  override val size: Long = channel.size()

  override fun read(
    position: Long,
    into: ByteArray,
    offset: Int,
    length: Int,
  ): Int = channel.read(ByteBuffer.wrap(into, offset, length), position)
}

class ByteArraySource(
  private val bytes: ByteArray,
) : SeekableSource {
  override val size: Long = bytes.size.toLong()

  override fun read(
    position: Long,
    into: ByteArray,
    offset: Int,
    length: Int,
  ): Int {
    if (position >= bytes.size) return -1
    val count = minOf(length.toLong(), bytes.size - position).toInt()
    System.arraycopy(bytes, position.toInt(), into, offset, count)
    return count
  }
}

/**
 * Pulls one text subtitle track out of a Matroska (MKV/WebM) file without touching the file.
 * Only element headers are read while scanning clusters; video and audio payloads are skipped,
 * so memory use is proportional to the subtitle, not the video.
 */
object MatroskaSubtitleExtractor {
  data class Track(
    val number: Long,
    val codecId: String,
    val language: String?,
    val name: String?,
    internal val codecPrivate: ByteArray?,
    internal val compression: Compression?,
  ) {
    val isText: Boolean get() = codecId.startsWith("S_TEXT/")
  }

  data class Compression(
    val algorithm: Int,
    val settings: ByteArray?,
  )

  data class Result(
    val content: String,
    val format: SubtitleFormat,
    val track: Track,
    val cueCount: Int,
  )

  private class Block(
    val startMs: Long,
    var durationMs: Long?,
    val payload: ByteArray,
  )

  private const val EBML = 0x1A45DFA3L
  private const val SEGMENT = 0x18538067L
  private const val INFO = 0x1549A966L
  private const val TIMESTAMP_SCALE = 0x2AD7B1L
  private const val TRACKS = 0x1654AE6BL
  private const val TRACK_ENTRY = 0xAEL
  private const val TRACK_NUMBER = 0xD7L
  private const val TRACK_TYPE = 0x83L
  private const val CODEC_ID = 0x86L
  private const val CODEC_PRIVATE = 0x63A2L
  private const val LANGUAGE = 0x22B59CL
  private const val LANGUAGE_BCP47 = 0x22B59DL
  private const val NAME = 0x536EL
  private const val CONTENT_ENCODINGS = 0x6D80L
  private const val CONTENT_ENCODING = 0x6240L
  private const val CONTENT_COMPRESSION = 0x5034L
  private const val CONTENT_COMP_ALGO = 0x4254L
  private const val CONTENT_COMP_SETTINGS = 0x4255L
  private const val CONTENT_ENCRYPTION = 0x5035L
  private const val CLUSTER = 0x1F43B675L
  private const val CLUSTER_TIMESTAMP = 0xE7L
  private const val SIMPLE_BLOCK = 0xA3L
  private const val BLOCK_GROUP = 0xA0L
  private const val BLOCK = 0xA1L
  private const val BLOCK_DURATION = 0x9BL
  private const val TRACK_TYPE_SUBTITLE = 17L
  private val TOP_LEVEL_IDS = setOf(CLUSTER, 0x1C53BB6BL, 0x1254C367L, 0x1941A469L, 0x1043A770L, 0x114D9B74L, INFO, TRACKS)

  private const val UNKNOWN_SIZE = -1L

  fun isMatroska(source: SeekableSource): Boolean =
    runCatching { Reader(source).readId() == EBML }.getOrDefault(false)

  /** Lists subtitle tracks, reading only the file's header. */
  fun listSubtitleTracks(source: SeekableSource): List<Track> = readHeader(Reader(source)).tracks

  /**
   * @param select picks the track to extract from the file's subtitle tracks (in file order).
   * @throws SubtitleTranslationException for image subtitles, encrypted or unreadable files.
   */
  suspend fun extract(
    source: SeekableSource,
    select: (List<Track>) -> Track?,
    onProgress: (Float) -> Unit = {},
  ): Result {
    val reader = Reader(source)
    val header = readHeader(reader)
    val track = select(header.tracks) ?: throw SubtitleTranslationException.Configuration("Couldn't find that subtitle track in the video file.")
    if (!track.isText) {
      throw SubtitleTranslationException.UnsupportedLanguage(
        "This is an image-based subtitle (${track.codecId.removePrefix("S_")}). It contains pictures, not text, so it can't be translated.",
      )
    }
    val format =
      when (track.codecId) {
        "S_TEXT/UTF8", "S_TEXT/ASCII" -> SubtitleFormat.SRT
        "S_TEXT/ASS", "S_TEXT/SSA" -> SubtitleFormat.ASS
        "S_TEXT/WEBVTT" -> SubtitleFormat.VTT
        else -> throw SubtitleTranslationException.Configuration("Subtitle codec ${track.codecId} isn't supported for translation.")
      }

    val blocks = mutableListOf<Block>()
    var lastReported = -1
    reader.position = header.firstClusterPosition
    while (reader.position < header.segmentEnd) {
      currentCoroutineContext().ensureActive()
      val id = reader.readIdOrNull() ?: break
      val size = reader.readSize()
      if (id != CLUSTER) {
        if (size == UNKNOWN_SIZE) break
        reader.position += size
        continue
      }
      val clusterEnd = if (size == UNKNOWN_SIZE) header.segmentEnd else reader.position + size
      try {
        readCluster(reader, clusterEnd, track, header.timestampScale, blocks)
      } catch (e: SubtitleTranslationException.Configuration) {
        // A torrent still downloading ends mid-cluster: keep the subtitles read so far.
        if (clusterEnd <= source.size) throw e
        break
      }
      val percent = ((reader.position * 100) / source.size).toInt()
      if (percent != lastReported) {
        lastReported = percent
        onProgress(percent / 100f)
      }
    }
    onProgress(1f)

    blocks.sortBy { it.startMs }
    blocks.forEachIndexed { i, block ->
      if (block.durationMs == null || block.durationMs!! <= 0) {
        val next = blocks.getOrNull(i + 1)?.startMs
        block.durationMs = next?.let { (it - block.startMs).coerceIn(500, 7_000) } ?: 4_000
      }
    }
    val texts = blocks.map { decode(it.payload, track.compression) }
    val content =
      when (format) {
        SubtitleFormat.SRT -> buildSrt(blocks, texts)
        SubtitleFormat.VTT -> buildVtt(blocks, texts)
        SubtitleFormat.ASS -> buildAss(track, blocks, texts)
      }
    return Result(content, format, track, blocks.size)
  }

  private class Header(
    val tracks: List<Track>,
    val timestampScale: Long,
    val firstClusterPosition: Long,
    val segmentEnd: Long,
  )

  private fun readHeader(reader: Reader): Header {
    if (reader.readId() != EBML) throw SubtitleTranslationException.Configuration("This video isn't a Matroska (MKV) file.")
    // Read the size first: `position += readSize()` would add it to the pre-read position.
    var skip = reader.readSize()
    reader.position += skip
    var segmentId = reader.readIdOrNull()
    while (segmentId != null && segmentId != SEGMENT) {
      skip = reader.readSize()
      reader.position += skip
      segmentId = reader.readIdOrNull()
    }
    if (segmentId != SEGMENT) throw SubtitleTranslationException.Configuration("The MKV file has no segment.")
    val segmentSize = reader.readSize()
    val segmentStart = reader.position
    val segmentEnd = if (segmentSize == UNKNOWN_SIZE) reader.source.size else minOf(segmentStart + segmentSize, reader.source.size)

    var scale = 1_000_000L
    var tracks: List<Track>? = null
    var firstCluster = -1L
    while (reader.position < segmentEnd) {
      val elementStart = reader.position
      val id = reader.readIdOrNull() ?: break
      val size = reader.readSize()
      val dataStart = reader.position
      when (id) {
        INFO -> scale = readInfo(reader, dataStart + size) ?: scale
        TRACKS -> tracks = readTracks(reader, dataStart + size)
        CLUSTER -> if (firstCluster < 0) firstCluster = elementStart
      }
      if (tracks != null && firstCluster >= 0) break
      if (size == UNKNOWN_SIZE) break
      reader.position = dataStart + size
    }
    return Header(
      tracks = tracks.orEmpty(),
      timestampScale = scale,
      firstClusterPosition = if (firstCluster >= 0) firstCluster else segmentStart,
      segmentEnd = segmentEnd,
    )
  }

  private fun readInfo(
    reader: Reader,
    end: Long,
  ): Long? {
    var scale: Long? = null
    reader.forEachChild(end) { id, size ->
      if (id == TIMESTAMP_SCALE) scale = reader.readUInt(size) else reader.position += size
    }
    return scale
  }

  private fun readTracks(
    reader: Reader,
    end: Long,
  ): List<Track> {
    val tracks = mutableListOf<Track>()
    reader.forEachChild(end) { id, size ->
      if (id == TRACK_ENTRY) readTrackEntry(reader, reader.position + size)?.let(tracks::add) else reader.position += size
    }
    return tracks
  }

  private fun readTrackEntry(
    reader: Reader,
    end: Long,
  ): Track? {
    var number = 0L
    var type = 0L
    var codec = ""
    var language: String? = null
    var languageBcp47: String? = null
    var name: String? = null
    var codecPrivate: ByteArray? = null
    var compression: Compression? = null
    var encrypted = false
    reader.forEachChild(end) { id, size ->
      when (id) {
        TRACK_NUMBER -> number = reader.readUInt(size)
        TRACK_TYPE -> type = reader.readUInt(size)
        CODEC_ID -> codec = reader.readString(size)
        LANGUAGE -> language = reader.readString(size)
        LANGUAGE_BCP47 -> languageBcp47 = reader.readString(size)
        NAME -> name = reader.readUtf8(size)
        CODEC_PRIVATE -> codecPrivate = reader.readBytes(size.toInt())
        CONTENT_ENCODINGS ->
          reader.forEachChild(reader.position + size) { encId, encSize ->
            if (encId != CONTENT_ENCODING) {
              reader.position += encSize
              return@forEachChild
            }
            reader.forEachChild(reader.position + encSize) { childId, childSize ->
              when (childId) {
                CONTENT_COMPRESSION -> {
                  var algorithm = 0
                  var settings: ByteArray? = null
                  reader.forEachChild(reader.position + childSize) { compId, compSize ->
                    when (compId) {
                      CONTENT_COMP_ALGO -> algorithm = reader.readUInt(compSize).toInt()
                      CONTENT_COMP_SETTINGS -> settings = reader.readBytes(compSize.toInt())
                      else -> reader.position += compSize
                    }
                  }
                  compression = Compression(algorithm, settings)
                }
                CONTENT_ENCRYPTION -> {
                  encrypted = true
                  reader.position += childSize
                }
                else -> reader.position += childSize
              }
            }
          }
        else -> reader.position += size
      }
    }
    if (type != TRACK_TYPE_SUBTITLE) return null
    if (encrypted) return Track(number, "ENCRYPTED", language, name, null, null)
    val lang = (languageBcp47 ?: language)?.takeUnless { it.isBlank() || it == "und" }
    return Track(number, codec, lang, name, codecPrivate, compression)
  }

  private fun readCluster(
    reader: Reader,
    end: Long,
    track: Track,
    scale: Long,
    blocks: MutableList<Block>,
  ) {
    var clusterTimestamp = 0L
    while (reader.position < end) {
      val childStart = reader.position
      val id = reader.readIdOrNull() ?: return
      if (id in TOP_LEVEL_IDS) {
        // Unknown-size cluster: the next top-level element ends it.
        reader.position = childStart
        return
      }
      val size = reader.readSize()
      if (size == UNKNOWN_SIZE) return
      val dataEnd = reader.position + size
      when (id) {
        CLUSTER_TIMESTAMP -> clusterTimestamp = reader.readUInt(size)
        SIMPLE_BLOCK -> readBlock(reader, dataEnd, track, clusterTimestamp, scale, null)?.let(blocks::add)
        BLOCK_GROUP -> {
          var block: Block? = null
          var duration: Long? = null
          reader.forEachChild(dataEnd) { childId, childSize ->
            when (childId) {
              BLOCK -> block = readBlock(reader, reader.position + childSize, track, clusterTimestamp, scale, null)
              BLOCK_DURATION -> duration = reader.readUInt(childSize) * scale / 1_000_000
              else -> reader.position += childSize
            }
          }
          block?.let {
            it.durationMs = duration ?: it.durationMs
            blocks += it
          }
        }
      }
      reader.position = dataEnd
    }
  }

  /** Reads a (Simple)Block if it belongs to [track]; otherwise only its track number is read. */
  private fun readBlock(
    reader: Reader,
    end: Long,
    track: Track,
    clusterTimestamp: Long,
    scale: Long,
    durationMs: Long?,
  ): Block? {
    val trackNumber = reader.readSize()
    if (trackNumber != track.number) {
      reader.position = end
      return null
    }
    val relative = ((reader.readByte() shl 8) or reader.readByte()).toShort().toLong()
    reader.readByte() // flags; subtitle blocks are never laced
    val payload = reader.readBytes((end - reader.position).toInt())
    return Block((clusterTimestamp + relative) * scale / 1_000_000, durationMs, payload)
  }

  private fun decode(
    payload: ByteArray,
    compression: Compression?,
  ): String {
    val bytes =
      when (compression?.algorithm) {
        null -> payload
        0 -> inflate(payload)
        3 -> (compression.settings ?: ByteArray(0)) + payload
        else -> throw SubtitleTranslationException.Configuration("This MKV uses an unsupported subtitle compression.")
      }
    return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
  }

  private fun inflate(data: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(data)
    val out = ByteArrayOutputStream(data.size * 3)
    val buffer = ByteArray(8192)
    try {
      while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        out.write(buffer, 0, count)
      }
    } finally {
      inflater.end()
    }
    return out.toByteArray()
  }

  private fun buildSrt(
    blocks: List<Block>,
    texts: List<String>,
  ): String =
    buildString {
      var index = 1
      blocks.forEachIndexed { i, block ->
        val text = texts[i].replace("\r\n", "\n").trim('\n')
        if (text.isBlank()) return@forEachIndexed
        append(index++).append('\n')
        append(srtTime(block.startMs)).append(" --> ").append(srtTime(block.startMs + block.durationMs!!)).append('\n')
        append(text).append("\n\n")
      }
    }

  private fun buildVtt(
    blocks: List<Block>,
    texts: List<String>,
  ): String =
    buildString {
      append("WEBVTT\n\n")
      blocks.forEachIndexed { i, block ->
        val text = texts[i].replace("\r\n", "\n").trim('\n')
        if (text.isBlank()) return@forEachIndexed
        append(srtTime(block.startMs).replace(',', '.')).append(" --> ")
        append(srtTime(block.startMs + block.durationMs!!).replace(',', '.')).append('\n')
        append(text).append("\n\n")
      }
    }

  /** Matroska ASS blocks are `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`. */
  private fun buildAss(
    track: Track,
    blocks: List<Block>,
    texts: List<String>,
  ): String {
    var header = track.codecPrivate?.let { String(it, Charsets.UTF_8) }?.replace("\r\n", "\n")?.trimEnd('\u0000', '\n', ' ').orEmpty()
    if (!header.contains("[Events]", ignoreCase = true)) {
      header += "\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
    }
    data class Event(
      val readOrder: Long,
      val line: String,
    )
    val events =
      blocks.mapIndexedNotNull { i, block ->
        val fields = texts[i].split(',', limit = 9)
        if (fields.size < 9) return@mapIndexedNotNull null
        val start = assTime(block.startMs)
        val end = assTime(block.startMs + block.durationMs!!)
        Event(
          fields[0].trim().toLongOrNull() ?: i.toLong(),
          "Dialogue: ${fields[1]},$start,$end,${fields.subList(2, 9).joinToString(",")}",
        )
      }.sortedWith(compareBy({ it.readOrder }))
    return header + "\n" + events.joinToString("\n") { it.line } + "\n"
  }

  private fun srtTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", clamped / 3_600_000, clamped / 60_000 % 60, clamped / 1000 % 60, clamped % 1000)
  }

  private fun assTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", clamped / 3_600_000, clamped / 60_000 % 60, clamped / 1000 % 60, clamped % 1000 / 10)
  }

  /** Buffered EBML reader over a [SeekableSource]. */
  private class Reader(
    val source: SeekableSource,
  ) {
    private val buffer = ByteArray(64 * 1024)
    private var bufferStart = -1L
    private var bufferLength = 0
    var position = 0L

    fun readByte(): Int {
      if (bufferStart < 0 || position < bufferStart || position >= bufferStart + bufferLength) fill()
      if (position >= bufferStart + bufferLength) throw SubtitleTranslationException.Configuration("The MKV file ended unexpectedly.")
      return buffer[(position++ - bufferStart).toInt()].toInt() and 0xFF
    }

    private fun fill() {
      bufferStart = position
      bufferLength = 0
      while (bufferLength < buffer.size) {
        val read = source.read(bufferStart + bufferLength, buffer, bufferLength, buffer.size - bufferLength)
        if (read <= 0) break
        bufferLength += read
      }
    }

    fun readBytes(count: Int): ByteArray {
      require(count in 0..(64 * 1024 * 1024)) { "Element too large" }
      val out = ByteArray(count)
      for (i in 0 until count) out[i] = readByte().toByte()
      return out
    }

    fun readIdOrNull(): Long? = if (position >= source.size) null else readId()

    /** Element IDs keep their length marker bits. */
    fun readId(): Long {
      val first = readByte()
      val length = Integer.numberOfLeadingZeros(first) - 23
      if (length !in 1..4) throw SubtitleTranslationException.Configuration("The MKV file is damaged (bad element ID).")
      var value = first.toLong()
      repeat(length - 1) { value = (value shl 8) or readByte().toLong() }
      return value
    }

    /** Sizes and track numbers drop the marker; an all-ones size means "unknown". */
    fun readSize(): Long {
      val first = readByte()
      val length = Integer.numberOfLeadingZeros(first) - 23
      if (length !in 1..8) throw SubtitleTranslationException.Configuration("The MKV file is damaged (bad element size).")
      var value = (first and (0xFF shr length)).toLong()
      var allOnes = value == (0xFF shr length).toLong()
      repeat(length - 1) {
        val next = readByte()
        if (next != 0xFF) allOnes = false
        value = (value shl 8) or next.toLong()
      }
      return if (allOnes) UNKNOWN_SIZE else value
    }

    fun readUInt(size: Long): Long {
      var value = 0L
      repeat(size.toInt()) { value = (value shl 8) or readByte().toLong() }
      return value
    }

    fun readString(size: Long): String = String(readBytes(size.toInt()), Charsets.US_ASCII).trimEnd('\u0000')

    fun readUtf8(size: Long): String = String(readBytes(size.toInt()), Charsets.UTF_8).trimEnd('\u0000')

    /** Visits children until [end]; [visit] must consume exactly the child's data. */
    inline fun forEachChild(
      end: Long,
      visit: (id: Long, size: Long) -> Unit,
    ) {
      while (position < end) {
        val id = readIdOrNull() ?: return
        val size = readSize()
        if (size == UNKNOWN_SIZE) return
        val dataEnd = position + size
        visit(id, size)
        position = dataEnd
      }
    }
  }
}
