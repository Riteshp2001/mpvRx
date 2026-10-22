package app.gyrolet.mpvrx.core.media

import kotlinx.serialization.Serializable

enum class MediaKind { Video, Audio }

@Serializable
data class MediaFile(
  /** Absolute path for local files, full URL for streams. */
  val path: String,
  val name: String,
  val folderPath: String,
  val sizeBytes: Long,
  val lastModifiedMillis: Long,
  val isAudio: Boolean,
) {
  val kind: MediaKind get() = if (isAudio) MediaKind.Audio else MediaKind.Video
  val extension: String get() = name.substringAfterLast('.', "").lowercase()
  val displayName: String get() = name.substringBeforeLast('.', name)
}

@Serializable
data class MediaFolder(
  val path: String,
  val name: String,
  val videoCount: Int,
  val audioCount: Int,
  val totalSizeBytes: Long,
  val lastModifiedMillis: Long,
) {
  val mediaCount: Int get() = videoCount + audioCount
}

/** Probed once per file and cached; filled lazily because probing needs the native player. */
@Serializable
data class MediaMetadata(
  val durationSeconds: Double = 0.0,
  val width: Int = 0,
  val height: Int = 0,
  val videoCodec: String? = null,
  val audioCodec: String? = null,
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
) {
  val resolutionLabel: String?
    get() = if (width > 0 && height > 0) "${width}x$height" else null
}

object MediaExtensions {
  val video =
    setOf(
      "3g2", "3gp", "asf", "avi", "divx", "f4v", "flv", "h264", "h265", "hevc", "m2ts", "m2v", "m4v", "mk3d",
      "mkv", "mov", "mp4", "mpeg", "mpg", "mts", "mxf", "ogm", "ogv", "rm", "rmvb", "ts", "vob", "webm", "wmv",
      "y4m",
    )
  val audio =
    setOf(
      "aac", "ac3", "aif", "aiff", "alac", "amr", "ape", "au", "dsf", "dff", "dts", "eac3", "flac", "m4a", "m4b",
      "mka", "mp2", "mp3", "mpc", "oga", "ogg", "opus", "tak", "tta", "wav", "weba", "wma", "wv",
    )
  val subtitle = setOf("ass", "idx", "lrc", "smi", "srt", "ssa", "sub", "sup", "ttml", "vtt")
  val playlist = setOf("m3u", "m3u8", "pls", "cue")

  fun isMedia(name: String) = name.substringAfterLast('.', "").lowercase().let { it in video || it in audio }

  fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in audio

  fun isSubtitle(name: String) = name.substringAfterLast('.', "").lowercase() in subtitle
}

fun isNetworkUrl(path: String): Boolean {
  val scheme = path.substringBefore("://", "").lowercase()
  return scheme.isNotEmpty() && scheme.length > 1 && scheme != "file"
}

/** Platform media discovery (desktop walks the file system, iOS will query the Photos/Files APIs). */
interface MediaScanner {
  /** Folders under [roots] that directly contain media. */
  suspend fun scanFolders(roots: List<String>, includeHidden: Boolean): List<MediaFolder>

  /** Media directly inside [folderPath]. */
  suspend fun listMedia(folderPath: String, includeHidden: Boolean): List<MediaFile>

  /** All audio files below [roots]. */
  suspend fun scanAudio(roots: List<String>, includeHidden: Boolean): List<MediaFile>

  /** External subtitle files matching [media] in the same folder. */
  suspend fun findSidecarSubtitles(media: MediaFile): List<String>

  fun defaultLibraryRoots(): List<String>
}
