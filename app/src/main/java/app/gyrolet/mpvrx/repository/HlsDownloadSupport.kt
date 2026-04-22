package app.gyrolet.mpvrx.repository

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object HlsDownloadSupport {

    fun download(
        client: OkHttpClient,
        playlistUrl: String,
        headers: Map<String, String>,
        resumeFromBytes: Long = 0L,
        onChunk: (ByteArray) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Long {
        val playlist = loadPlaylist(client, playlistUrl, headers)
        val keyCache = mutableMapOf<String, ByteArray>()
        // Avoid per-segment HEAD preflight because it is slow and unreliable on many CDNs.
        val totalBytes: Long? = null
        var downloadedBytes = 0L
        var bytesToSkip = resumeFromBytes.coerceAtLeast(0L)

        fun emitPayload(payload: ByteArray) {
            val payloadSize = payload.size.toLong()
            when {
                bytesToSkip >= payloadSize -> {
                    bytesToSkip -= payloadSize
                }

                bytesToSkip > 0L -> {
                    val startIndex = bytesToSkip.toInt().coerceAtMost(payload.size)
                    bytesToSkip = 0L
                    if (startIndex < payload.size) {
                        onChunk(payload.copyOfRange(startIndex, payload.size))
                    }
                }

                else -> onChunk(payload)
            }
            downloadedBytes += payloadSize
            onProgress(downloadedBytes, totalBytes)
        }

        playlist.initializationSegment?.let { initSegment ->
            val bytes = fetchBytes(client, initSegment.url, headers)
            val payload = initSegment.key?.let { decrypt(bytes, resolveKey(client, it.uri, headers, keyCache), keyIv(it, initSegment.sequence)) }
                ?: bytes
            emitPayload(payload)
        }

        playlist.segments.forEach { segment ->
            val bytes = fetchBytes(client, segment.url, headers)
            val payload = segment.key?.let { decrypt(bytes, resolveKey(client, it.uri, headers, keyCache), keyIv(it, segment.sequence)) }
                ?: bytes
            emitPayload(payload)
        }

        return downloadedBytes
    }

    private fun loadPlaylist(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
    ): ParsedPlaylist {
        val content = fetchText(client, url, headers)
        if (content.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val variantUrl = selectVariantUrl(url, content)
                ?: throw IllegalStateException("No playable HLS variant found")
            return loadPlaylist(client, variantUrl, headers)
        }

        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val segments = mutableListOf<PlaylistSegment>()
        var currentKey: SegmentKey? = null
        var initSegment: PlaylistSegment? = null
        var mediaSequence = 0L
        var sequence = mediaSequence

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    sequence = mediaSequence
                }

                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    currentKey = parseKey(line, url)
                }

                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val mapUrl = attrs["URI"]?.let { resolveUrl(url, it) } ?: continue
                    initSegment = PlaylistSegment(
                        url = mapUrl,
                        sequence = sequence,
                        key = currentKey
                    )
                }

                line.startsWith("#") -> Unit

                else -> {
                    segments += PlaylistSegment(
                        url = resolveUrl(url, line),
                        sequence = sequence,
                        key = currentKey
                    )
                    sequence += 1
                }
            }
        }

        if (segments.isEmpty()) {
            throw IllegalStateException("No HLS segments found")
        }

        return ParsedPlaylist(
            initializationSegment = initSegment,
            segments = segments
        )
    }

    private fun selectVariantUrl(baseUrl: String, content: String): String? {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val variants = mutableListOf<Pair<Int, String>>()

        for (index in lines.indices) {
            val line = lines[index]
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) continue
            val attrs = parseAttributes(line.substringAfter(':'))
            val bandwidth = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0
            val nextLine = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: continue
            variants += bandwidth to resolveUrl(baseUrl, nextLine)
        }

        return variants.maxByOrNull { it.first }?.second
    }

    private fun parseKey(line: String, baseUrl: String): SegmentKey? {
        val attrs = parseAttributes(line.substringAfter(':'))
        val method = attrs["METHOD"] ?: return null
        if (!method.equals("AES-128", ignoreCase = true)) return null
        val uri = attrs["URI"]?.let { resolveUrl(baseUrl, it) } ?: return null
        val iv = attrs["IV"]?.let(::parseIv)
        return SegmentKey(uri = uri, iv = iv)
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val regex = Regex("""([A-Z0-9\-]+)=("[^"]*"|[^,]+)""")
        return regex.findAll(raw).associate { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim().removePrefix("\"").removeSuffix("\"")
            key to value
        }
    }

    private fun parseIv(raw: String): ByteArray {
        val normalized = raw.removePrefix("0x").removePrefix("0X")
        val bytes = ByteArray(normalized.length / 2)
        for (index in bytes.indices) {
            val offset = index * 2
            bytes[index] = normalized.substring(offset, offset + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun keyIv(key: SegmentKey, sequence: Long): ByteArray =
        key.iv ?: ByteArray(16).also { iv ->
            var value = sequence
            for (index in 15 downTo 0) {
                iv[index] = (value and 0xFF).toByte()
                value = value ushr 8
            }
        }

    private fun resolveKey(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        cache: MutableMap<String, ByteArray>,
    ): ByteArray = cache.getOrPut(url) {
        fetchBytes(client, url, headers)
    }

    private fun decrypt(
        payload: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(payload)
    }

    private fun fetchText(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
    ): String = fetchWithRetry(client, url, headers).decodeToString()

    private fun fetchBytes(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
    ): ByteArray = fetchWithRetry(client, url, headers)

    private fun fetchWithRetry(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
    ): ByteArray {
        var lastError: Throwable? = null
        repeat(MAX_FETCH_ATTEMPTS) { attempt ->
            runCatching { fetch(client, url, headers) }
                .onSuccess { return it }
                .onFailure { error ->
                    lastError = error
                    if (attempt < MAX_FETCH_ATTEMPTS - 1) {
                        Thread.sleep(RETRY_BASE_DELAY_MS * (attempt + 1).toLong())
                    }
                }
        }
        throw IOException("Failed to fetch $url after $MAX_FETCH_ATTEMPTS attempts", lastError)
    }

    private fun fetch(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
    ): ByteArray {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} for $url")
            }
            return response.body.bytes()
        }
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String =
        URL(URL(baseUrl), relativeOrAbsolute).toString()

    private const val MAX_FETCH_ATTEMPTS = 3
    private const val RETRY_BASE_DELAY_MS = 350L
}

private data class ParsedPlaylist(
    val initializationSegment: PlaylistSegment? = null,
    val segments: List<PlaylistSegment>
)

private data class PlaylistSegment(
    val url: String,
    val sequence: Long,
    val key: SegmentKey? = null
)

private data class SegmentKey(
    val uri: String,
    val iv: ByteArray? = null
)
