package app.gyrolet.mpvrx.core.platform

import app.gyrolet.mpvrx.core.media.MediaExtensions
import app.gyrolet.mpvrx.core.media.MediaFile
import app.gyrolet.mpvrx.core.media.MediaFolder
import app.gyrolet.mpvrx.core.media.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

class FileSystemMediaScanner(
  private val maxDepth: Int = 12,
) : MediaScanner {
  override suspend fun scanFolders(roots: List<String>, includeHidden: Boolean): List<MediaFolder> =
    withContext(Dispatchers.IO) {
      val byFolder = HashMap<String, MutableList<MediaFile>>()
      walk(roots, includeHidden) { file -> byFolder.getOrPut(file.folderPath) { mutableListOf() } += file }
      byFolder.map { (path, files) ->
        MediaFolder(
          path = path,
          name = File(path).name.ifEmpty { path },
          videoCount = files.count { !it.isAudio },
          audioCount = files.count { it.isAudio },
          totalSizeBytes = files.sumOf { it.sizeBytes },
          lastModifiedMillis = files.maxOf { it.lastModifiedMillis },
        )
      }
    }

  override suspend fun listMedia(folderPath: String, includeHidden: Boolean): List<MediaFile> =
    withContext(Dispatchers.IO) {
      File(folderPath)
        .listFiles()
        .orEmpty()
        .filter { it.isFile && it.isLikelyMedia(it.length()) && (includeHidden || !it.isHiddenEntry()) }
        .map { it.toMediaFile() }
    }

  override suspend fun scanAudio(roots: List<String>, includeHidden: Boolean): List<MediaFile> =
    withContext(Dispatchers.IO) {
      val result = mutableListOf<MediaFile>()
      walk(roots, includeHidden) { if (it.isAudio) result += it }
      result
    }

  override suspend fun findSidecarSubtitles(media: MediaFile): List<String> =
    withContext(Dispatchers.IO) {
      val base = media.displayName.lowercase()
      File(media.folderPath)
        .listFiles()
        .orEmpty()
        .filter { it.isFile && MediaExtensions.isSubtitle(it.name) && it.name.lowercase().startsWith(base) }
        .map { it.absolutePath }
    }

  override fun defaultLibraryRoots(): List<String> {
    val home = File(System.getProperty("user.home"))
    val candidates =
      when (currentDesktopOs) {
        DesktopOs.Windows, DesktopOs.MacOs -> listOf("Videos", "Movies", "Music", "Downloads", "Desktop")
        DesktopOs.Linux -> listOf("Videos", "Music", "Downloads", "Desktop")
      }
    return candidates.map { File(home, it) }.filter(File::isDirectory).map(File::getAbsolutePath)
  }

  private suspend fun walk(roots: List<String>, includeHidden: Boolean, onMedia: (MediaFile) -> Unit) {
    val context = coroutineContext
    val visited = HashSet<String>()
    for (root in roots.distinct()) {
      val start = File(root)
      if (!start.isDirectory) continue
      Files.walkFileTree(
        start.toPath(),
        emptySet(),
        maxDepth,
        object : SimpleFileVisitor<Path>() {
          override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            context.ensureActive()
            val file = dir.toFile()
            if (!visited.add(file.absolutePath)) return FileVisitResult.SKIP_SUBTREE
            if (file != start && (file.name.lowercase() in SKIPPED_DIRECTORIES || (!includeHidden && file.isHiddenEntry()))) {
              return FileVisitResult.SKIP_SUBTREE
            }
            return FileVisitResult.CONTINUE
          }

          override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
            val file = path.toFile()
            if (attrs.isRegularFile && file.isLikelyMedia(attrs.size()) && (includeHidden || !file.isHiddenEntry())) {
              onMedia(file.toMediaFile(attrs))
            }
            return FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        },
      )
    }
  }

  private fun File.isHiddenEntry() = name.startsWith('.') || isHidden

  // ".ts" is also TypeScript and ".sub"/".rm" collide with text formats; real media is never this small.
  private fun File.isLikelyMedia(size: Long) = MediaExtensions.isMedia(name) && size >= MIN_MEDIA_BYTES

  private fun File.toMediaFile(attrs: BasicFileAttributes? = null) =
    MediaFile(
      path = absolutePath,
      name = name,
      folderPath = parentFile?.absolutePath.orEmpty(),
      sizeBytes = attrs?.size() ?: length(),
      lastModifiedMillis = attrs?.lastModifiedTime()?.toMillis() ?: lastModified(),
      isAudio = MediaExtensions.isAudio(name),
    )

  private companion object {
    const val MIN_MEDIA_BYTES = 16 * 1024L
    val SKIPPED_DIRECTORIES =
      setOf("\$recycle.bin", "system volume information", "appdata", "node_modules", ".git", "windows", "program files")
  }
}
