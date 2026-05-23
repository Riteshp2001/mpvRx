package app.gyrolet.mpvrx.exoplayer.core.data.repository

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import app.gyrolet.mpvrx.exoplayer.core.common.Logger

class OnlineSubtitleRepository(
    private val cacheRoot: File,
    private val downloader: suspend (String) -> DownloadStream,
    private val nowMillis: () -> Long,
) {

    constructor(
        context: Context,
    ) : this(
        cacheRoot = context.cacheDir,
        downloader = { url -> downloadWithOkHttp(url) },
        nowMillis = System::currentTimeMillis,
    )

    private val subtitleCacheDir = File(cacheRoot, ONLINE_SUBTITLE_DIR_NAME)

    suspend fun downloadSubtitle(url: String): DownloadedOnlineSubtitle {
        val parsedUrl = ParsedSubtitleUrl.from(url)
        subtitleCacheDir.mkdirs()

        val baseName = url.hashCode().toUInt().toString(16)
        val targetFile = File(subtitleCacheDir, "$baseName.${parsedUrl.extension}")
        val tempFile = File.createTempFile(baseName, ".${parsedUrl.extension}.part", subtitleCacheDir)
        Logger.debug(TAG, "Download online subtitle start: extension=${parsedUrl.extension}, target=${targetFile.name}")

        try {
            downloader(url).inputStream.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L

                    while (true) {
                        val readCount = inputStream.read(buffer)
                        if (readCount == -1) break

                        totalBytes += readCount
                        // 超限立即终止，避免落盘超出上限的字幕。
                        if (totalBytes > MAX_SUBTITLE_BYTES) {
                            throw OnlineSubtitleTooLargeException()
                        }
                        outputStream.write(buffer, 0, readCount)
                    }

                    if (totalBytes == 0L) {
                        throw EmptyOnlineSubtitleException()
                    }
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                throw IOException("Unable to move subtitle file")
            }
            targetFile.setLastModified(nowMillis())
            Logger.debug(TAG, "Download online subtitle cached: file=${targetFile.name}, bytes=${targetFile.length()}")
            return DownloadedOnlineSubtitle(file = targetFile)
        } catch (exception: EmptyOnlineSubtitleException) {
            tempFile.delete()
            throw exception
        } catch (exception: OnlineSubtitleTooLargeException) {
            tempFile.delete()
            throw exception
        } catch (exception: IOException) {
            tempFile.delete()
            throw OnlineSubtitleDownloadFailedException(exception)
        }
    }

    fun deleteExpiredSubtitles() {
        val expireBefore = nowMillis() - SUBTITLE_TTL_MILLIS
        val files = subtitleCacheDir.listFiles().orEmpty()

        files.forEach { file ->
            if (file.isFile && file.lastModified() < expireBefore) {
                file.delete()
            }
        }
    }

    fun touchSubtitle(uri: Uri) {
        if (uri.scheme != "file") return
        val file = uri.path?.let(::File) ?: return
        touchSubtitleFile(file)
    }

    internal fun touchSubtitleFile(file: File) {
        if (!file.isFile) return
        if (file.parentFile?.canonicalFile != subtitleCacheDir.canonicalFile) return

        file.setLastModified(nowMillis())
    }

    private data class ParsedSubtitleUrl(
        val extension: String,
    ) {
        companion object {
            fun from(url: String): ParsedSubtitleUrl {
                val uri = runCatching { URI(url.trim()) }
                    .getOrElse { throw InvalidOnlineSubtitleUrlException() }
                val scheme = uri.scheme?.lowercase().orEmpty()
                if (scheme !in SUPPORTED_SCHEMES) {
                    throw InvalidOnlineSubtitleSchemeException(scheme)
                }
                if (uri.host.isNullOrBlank()) {
                    throw InvalidOnlineSubtitleUrlException()
                }

                val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
                if (extension !in SUPPORTED_EXTENSIONS) {
                    throw InvalidOnlineSubtitleExtensionException(extension)
                }

                return ParsedSubtitleUrl(extension = extension)
            }
        }
    }

    private companion object {
        const val TAG = "OnlineSubtitleRepository"
        const val ONLINE_SUBTITLE_DIR_NAME = "online_subtitles"
        const val MAX_SUBTITLE_BYTES = 10L * 1024 * 1024
        const val SUBTITLE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        val SUPPORTED_SCHEMES = setOf("http", "https")
        val SUPPORTED_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

        fun downloadWithOkHttp(url: String): DownloadStream {
            val request = Request.Builder()
                .url(url)
                .build()
            val response = OkHttpClient.Builder().build().newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("Subtitle download failed with code ${response.code}")
            }

            val body = response.body
            if (body == null) {
                response.close()
                throw IOException("Subtitle response body is empty")
            }

            return DownloadStream(ResponseInputStream(body.byteStream(), response::close))
        }
    }
}

data class DownloadedOnlineSubtitle(
    val file: File,
) {
    val uriString: String = file.toURI().toString()
    val uri: Uri get() = Uri.parse(uriString)
}

class DownloadStream(
    val inputStream: InputStream,
)

class InvalidOnlineSubtitleSchemeException(
    val scheme: String,
) : IllegalArgumentException("Unsupported subtitle scheme: $scheme")

class InvalidOnlineSubtitleUrlException : IllegalArgumentException("Unsupported subtitle URL")

class InvalidOnlineSubtitleExtensionException(
    val extension: String,
) : IllegalArgumentException("Unsupported subtitle extension: $extension")

class EmptyOnlineSubtitleException : IllegalStateException("Online subtitle is empty")

class OnlineSubtitleTooLargeException : IllegalStateException("Online subtitle exceeds 10 MB")

class OnlineSubtitleDownloadFailedException(
    cause: IOException,
) : IOException("Online subtitle download failed", cause)

private class ResponseInputStream(
    inputStream: InputStream,
    private val onClose: () -> Unit,
) : FilterInputStream(inputStream) {

    override fun close() {
        try {
            super.close()
        } finally {
            onClose()
        }
    }
}
