package app.gyrolet.mpvrx.exoplayer.core.common.extensions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.gyrolet.mpvrx.exoplayer.core.common.Logger

suspend fun File.getSubtitles(): List<File> = withContext(Dispatchers.IO) {
    val mediaName = this@getSubtitles.nameWithoutExtension
    val parentDir = this@getSubtitles.parentFile ?: return@withContext emptyList()

    parentDir.listFiles()
        ?.filter { it.isFile }
        ?.filter { it.isSubtitle() }
        ?.filter { it.name.matchesSubtitleBase(mediaName) }
        .orEmpty()
}

suspend fun File.getLocalSubtitles(
    context: Context,
    excludeSubsList: List<Uri> = emptyList(),
): List<Uri> = withContext(Dispatchers.IO) {
    val excludeSubsPathSet = excludeSubsList.mapNotNull { subtitleUri ->
        subtitleUri.toCanonicalLocalPath(context)
    }.toSet()

    getSubtitles().mapNotNull { file ->
        val canonicalPath = file.path.canonicalPathOrSelf()
        if (canonicalPath !in excludeSubsPathSet) {
            File(canonicalPath).toUri()
        } else {
            null
        }
    }
}

fun String.getThumbnail(): File? {
    val filePathWithoutExtension = this.substringBeforeLast(".")
    val imageExtensions = listOf("png", "jpg", "jpeg")
    for (imageExtension in imageExtensions) {
        val file = File("$filePathWithoutExtension.$imageExtension")
        if (file.exists()) return file
    }
    return null
}

fun File.isSubtitle(): Boolean = extension.isSubtitleExtension()

fun String.isSubtitleExtension(): Boolean = lowercase() in SUBTITLE_EXTENSIONS

fun String.matchesSubtitleBase(videoName: String): Boolean {
    val subtitleBase = substringBeforeLast('.', missingDelimiterValue = this)
    return subtitleBase.equals(videoName, ignoreCase = true) || subtitleBase.startsWith("$videoName.", ignoreCase = true)
}

fun File.deleteFiles() {
    try {
        listFiles()?.onEach {
            it.delete()
        }
    } catch (e: Exception) {
        Logger.error("File", "Failed to delete files", e)
    }
}

fun String.canonicalPathOrSelf(): String = runCatching {
    File(this).canonicalPath
}.getOrDefault(this)

fun Uri.toCanonicalLocalPath(context: Context): String? {
    val rawPath = when (scheme) {
        ContentResolver.SCHEME_FILE -> path
        else -> context.getPath(this)
    } ?: return null
    return rawPath.canonicalPathOrSelf()
}

fun Uri.toCanonicalFilePathOrNull(): String? {
    if (scheme != ContentResolver.SCHEME_FILE) return null
    return path?.canonicalPathOrSelf()
}

fun String.isInsideNoMediaDirectory(): Boolean = File(this).isInsideNoMediaDirectory()

fun File.isInsideNoMediaDirectory(): Boolean {
    var currentDirectory = parentFile
    while (currentDirectory != null && currentDirectory.exists()) {
        if (File(currentDirectory, ".nomedia").exists()) {
            return true
        }
        currentDirectory = currentDirectory.parentFile
    }
    return false
}

fun Iterable<String>.excludeNoMediaPaths(): List<String> = filterNot { path ->
    path.isInsideNoMediaDirectory()
}

val File.prettyName: String
    get() = this.name.takeIf { this.path != Environment.getExternalStorageDirectory()?.path } ?: "Internal Storage"

private val SUBTITLE_EXTENSIONS = setOf("srt", "ssa", "ass", "vtt", "ttml")
