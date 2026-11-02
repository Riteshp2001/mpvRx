/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.media

import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale
import java.util.PriorityQueue

/** Name-only discovery: never open media, probe metadata, or leave the video's directory tree. */
internal object SubtitleDiscovery {
  const val MAX_DEPTH = 2
  const val MAX_DIRECTORIES = 32
  const val MAX_ENTRIES = 4096
  private const val MAX_TRACKS = 64
  private const val MAX_SCAN_NANOS = 2_000_000_000L

  data class Entry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = -1L,
  )

  data class Listing(val entries: List<Entry>, val complete: Boolean = true)

  private data class Directory(
    val path: String,
    val depth: Int = 0,
    val mediaMatched: Boolean = false,
    val subtitleFolder: Boolean = false,
  )

  // Keep the permissive legacy formats for explicit filename matches only. A README.txt or
  // unrelated JSON file in a subtitle folder must never become a subtitle track.
  private val extensions = setOf(
    "srt", "vtt", "ass", "ssa", "sub", "idx", "sup", "xml", "ttml", "dfxp", "itt",
    "ebu", "imsc", "usf", "sbv", "srv1", "srv2", "srv3", "json", "sami", "smi",
    "mpl", "pjs", "stl", "rt", "psb", "cap", "scc", "vttx", "lrc", "krc", "txt", "pgs",
  )
  private val genericExtensions = extensions - setOf("txt", "json", "xml")
  private val separators = Regex("[^\\p{L}\\p{N}]+")
  private val episodePattern = Regex(
    "(?:^|[^a-z0-9])(?:s(\\d{1,3})[ ._-]*e(\\d{1,4})|(\\d{1,3})x(\\d{1,4}))(?=$|[^0-9])",
    RegexOption.IGNORE_CASE,
  )
  private val seasonPattern = Regex("^(?:season|s)[ ._-]*(\\d+)$", RegexOption.IGNORE_CASE)
  private val subtitleFolderNames = setOf("sub", "subs", "subtitle", "subtitles", "caption", "captions")
  private val trackLabels = setOf(
    "sub", "subs", "subtitle", "subtitles", "full", "forced", "sdh", "cc", "default",
    "simplified", "traditional", "hans", "hant", "brazilian", "latin", "american",
  )
  private val languageNames by lazy {
    buildSet {
      for (code in Locale.getISOLanguages()) {
        val locale = Locale.forLanguageTag(code)
        add(normalize(code))
        runCatching { locale.isO3Language }.getOrNull()?.let { add(normalize(it)) }
        add(normalize(locale.getDisplayLanguage(Locale.ENGLISH)))
        add(normalize(locale.getDisplayLanguage(locale)))
      }
    }
  }
  private val countryCodes by lazy { Locale.getISOCountries().mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) } }

  fun isSubtitle(name: String): Boolean = extension(name) in extensions

  /** [listDirectory] must return at most limit entries, marking truncated listings incomplete. */
  suspend fun find(
    directoryPath: String,
    videoFileName: String,
    videoPath: String? = null,
    isCurrent: () -> Boolean,
    listDirectory: suspend (path: String, limit: Int, shouldContinue: () -> Boolean) -> Listing,
  ): List<Entry> {
    var currentVideoName = videoFileName
    var videoBase = currentVideoName.substringBeforeLast('.')
    if (videoBase.isBlank()) return emptyList()
    val started = System.nanoTime()
    val shouldContinue = { isCurrent() && System.nanoTime() - started < MAX_SCAN_NANOS }
    val pending = PriorityQueue(compareBy<Directory> {
      when {
        it.mediaMatched -> 0
        it.subtitleFolder -> 1
        else -> 2
      }
    }.thenBy { it.depth }.thenBy { it.path }).apply { add(Directory(directoryPath)) }
    val visited = hashSetOf<String>()
    val matched = mutableListOf<Entry>()
    val generic = mutableListOf<Entry>()
    var remainingEntries = MAX_ENTRIES
    var singleVideo = false
    var videoEpisode = episode(videoBase)

    while (pending.isNotEmpty() && visited.size < MAX_DIRECTORIES && remainingEntries > 0 && shouldContinue()) {
      currentCoroutineContext().ensureActive()
      val directory = pending.remove()
      if (!visited.add(directory.path)) continue
      val listing = listDirectory(directory.path, remainingEntries, shouldContinue)
      if (!isCurrent()) return emptyList()
      val entries = listing.entries.take(remainingEntries)
      remainingEntries -= entries.size
      val videos = entries.filter { !it.isDirectory && extension(it.name) in FileTypeUtils.VIDEO_EXTENSIONS }
      if (directory.depth == 0) {
        // Network providers may use opaque IDs in paths, while the UI may have a custom title.
        entries.firstOrNull { !it.isDirectory && it.path == videoPath }?.let {
          currentVideoName = it.name
          videoBase = it.name.substringBeforeLast('.')
          videoEpisode = episode(videoBase)
        }
        singleVideo = listing.complete && videos.size == 1 &&
          videos.single().name.equals(currentVideoName, ignoreCase = true)
      }
      // Generic language tracks only belong to this media when their directory is unambiguous.
      val containsOtherVideo = videos.any { !matchesMedia(it.name.substringBeforeLast('.'), videoBase) }
      val allowGeneric = directory.depth > 0 && !containsOtherVideo &&
        (directory.mediaMatched || singleVideo)
      val indexes = entries.filter { !it.isDirectory && extension(it.name) == "idx" }
        .mapTo(hashSetOf()) { it.name.substringBeforeLast('.').lowercase(Locale.ROOT) }

      for (entry in entries) {
        currentCoroutineContext().ensureActive()
        // A slow network listing may consume the time budget by itself. Still process its
        // bounded result so existing same-folder subtitles keep working; skip further I/O.
        if (!isCurrent()) return emptyList()
        if (entry.isDirectory || !isSubtitle(entry.name)) continue
        val base = entry.name.substringBeforeLast('.')
        if (hasConflictingEpisode(base, videoEpisode)) continue
        // VobSub's .idx opens its .sub companion itself; adding both creates duplicate tracks.
        if (extension(entry.name) == "sub" && base.lowercase(Locale.ROOT) in indexes) continue
        if (matchesMedia(base, videoBase)) {
          matched.add(entry)
        } else if (allowGeneric && extension(entry.name) in genericExtensions && isLanguageLabel(base)) {
          generic.add(entry)
        }
        if (matched.size + generic.size >= MAX_TRACKS) break
      }
      if (matched.size + generic.size >= MAX_TRACKS) break
      if (directory.depth >= MAX_DEPTH || (directory.depth > 0 && containsOtherVideo)) continue

      val children = entries.filter { entry ->
        entry.isDirectory && !entry.name.startsWith('.') &&
          !hasConflictingEpisode(entry.name, videoEpisode) &&
          !hasConflictingSeason(entry.name, videoEpisode)
      }.sortedWith(compareBy<Entry> {
        when {
          matchesMedia(it.name, videoBase) -> 0
          normalize(it.name) in subtitleFolderNames -> 1
          else -> 2
        }
      }.thenBy { it.name.lowercase(Locale.ROOT) })
      for (child in children) {
        pending.add(Directory(
          path = child.path,
          depth = directory.depth + 1,
          mediaMatched = directory.mediaMatched || matchesMedia(child.name, videoBase),
          subtitleFolder = directory.subtitleFolder || normalize(child.name) in subtitleFolderNames,
        ))
      }
    }
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return emptyList()
    val order = compareBy<Entry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.path }
    return (matched.sortedWith(order) + generic.sortedWith(order)).distinctBy { it.path }.take(MAX_TRACKS)
  }

  private fun matchesMedia(candidate: String, videoBase: String): Boolean {
    val name = normalize(candidate)
    val video = normalize(videoBase)
    if (name == video) return true
    // A numbered sequel/episode is not a language suffix (Movie.2 versus Movie).
    if (name.startsWith("$video ") && name.removePrefix("$video ").firstOrNull()?.isDigit() == false) return true
    val videoEpisode = episode(videoBase) ?: return false
    if (episode(candidate) != videoEpisode) return false
    val candidateTitle = normalize(candidate.substringBeforeEpisode())
    val videoTitle = normalize(videoBase.substringBeforeEpisode())
    return candidateTitle.isEmpty() || candidateTitle == videoTitle
  }

  private fun isLanguageLabel(name: String): Boolean {
    val label = normalize(name).split(' ').filter {
      it.isNotBlank() && it !in trackLabels && it.toIntOrNull() == null
    }.filterIndexed { index, token -> index == 0 || token !in countryCodes }
    return label.isNotEmpty() && (label.joinToString(" ") in languageNames || label.all { it in languageNames })
  }

  private fun episode(name: String): Pair<Int, Int>? {
    val match = episodePattern.find(name) ?: return null
    return (match.groups[1]?.value ?: match.groupValues[3]).toInt() to
      (match.groups[2]?.value ?: match.groupValues[4]).toInt()
  }

  private fun String.substringBeforeEpisode(): String =
    episodePattern.find(this)?.let { take(it.range.first) }.orEmpty()

  private fun hasConflictingEpisode(name: String, videoEpisode: Pair<Int, Int>?): Boolean =
    episode(name)?.let { it != videoEpisode } ?: false

  private fun hasConflictingSeason(name: String, videoEpisode: Pair<Int, Int>?): Boolean =
    seasonPattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.let { it != videoEpisode?.first } ?: false

  private fun normalize(name: String): String = separators.replace(name.lowercase(Locale.ROOT), " ").trim()

  private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

/** Small, short-lived listing cache shared by playlist episodes. No subtitle contents are cached. */
internal class SubtitleDirectoryCache {
  private data class Cached(val listing: SubtitleDiscovery.Listing, val stamp: Long?, val time: Long)

  private val listings = LinkedHashMap<String, Cached>(16, 0.75f, true)
  private var entries = 0

  @Synchronized
  fun get(key: String, stamp: Long?, limit: Int): SubtitleDiscovery.Listing? {
    val cached = listings[key] ?: return null
    if (cached.stamp != stamp || System.nanoTime() - cached.time > 10_000_000_000L) {
      entries -= cached.listing.entries.size
      listings.remove(key)
      return null
    }
    return cached.listing.copy(
      entries = cached.listing.entries.take(limit),
      complete = cached.listing.entries.size <= limit,
    )
  }

  @Synchronized
  fun put(key: String, stamp: Long?, listing: SubtitleDiscovery.Listing) {
    if (!listing.complete || listing.entries.size > SubtitleDiscovery.MAX_ENTRIES) return
    listings.remove(key)?.let { entries -= it.listing.entries.size }
    listings[key] = Cached(listing, stamp, System.nanoTime())
    entries += listing.entries.size
    while (listings.size > SubtitleDiscovery.MAX_DIRECTORIES || entries > SubtitleDiscovery.MAX_ENTRIES) {
      val oldest = listings.entries.iterator().next()
      entries -= oldest.value.listing.entries.size
      listings.remove(oldest.key)
    }
  }
}
