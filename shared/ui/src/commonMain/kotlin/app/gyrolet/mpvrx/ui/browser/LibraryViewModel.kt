package app.gyrolet.mpvrx.ui.browser

import app.gyrolet.mpvrx.core.media.MediaFile
import app.gyrolet.mpvrx.core.media.MediaFolder
import app.gyrolet.mpvrx.core.preferences.FolderSortOrder
import app.gyrolet.mpvrx.core.preferences.VideoSortOrder
import app.gyrolet.mpvrx.ui.platform.AppDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Shared library state behind the Home and Music tabs (Android: FolderListViewModel + music scan). */
class LibraryViewModel(
  private val deps: AppDependencies,
  private val scope: CoroutineScope,
) {
  private val mutableFolders = MutableStateFlow<List<MediaFolder>>(emptyList())
  val folders: StateFlow<List<MediaFolder>> = mutableFolders.asStateFlow()

  private val mutableAudio = MutableStateFlow<List<MediaFile>>(emptyList())
  val audio: StateFlow<List<MediaFile>> = mutableAudio.asStateFlow()

  private val mutableScanning = MutableStateFlow(false)
  val isScanning: StateFlow<Boolean> = mutableScanning.asStateFlow()

  private var scanJob: Job? = null

  fun roots(): List<String> = deps.settings.browser.libraryFolders.get().toList().ifEmpty { deps.scanner.defaultLibraryRoots() }

  fun refresh() {
    scanJob?.cancel()
    scanJob =
      scope.launch {
        mutableScanning.value = true
        try {
          val hidden = deps.settings.browser.showHiddenFiles.get()
          val roots = roots()
          mutableFolders.value = deps.scanner.scanFolders(roots, hidden).filter { it.videoCount > 0 }
          mutableAudio.value = deps.scanner.scanAudio(roots, hidden).sortedBy { it.name.lowercase() }
        } finally {
          mutableScanning.value = false
        }
      }
  }

  fun addFolder(path: String) {
    val pref = deps.settings.browser.libraryFolders
    val base = pref.get().ifEmpty { deps.scanner.defaultLibraryRoots().toSet() }
    pref.set(base + path)
    refresh()
  }

  fun removeFolder(path: String) {
    val pref = deps.settings.browser.libraryFolders
    pref.set(pref.get() - path)
    refresh()
  }
}

fun List<MediaFolder>.sortedFor(order: FolderSortOrder, ascending: Boolean): List<MediaFolder> {
  val sorted =
    when (order) {
      FolderSortOrder.Name -> sortedBy { it.name.lowercase() }
      FolderSortOrder.RecentlyModified -> sortedBy { it.lastModifiedMillis }
      FolderSortOrder.Size -> sortedBy { it.totalSizeBytes }
      FolderSortOrder.Count -> sortedBy { it.mediaCount }
    }
  return if (ascending) sorted else sorted.reversed()
}

fun List<MediaFile>.sortedFor(
  order: VideoSortOrder,
  ascending: Boolean,
  durationOf: (MediaFile) -> Double,
): List<MediaFile> {
  val sorted =
    when (order) {
      VideoSortOrder.Name -> sortedWith(compareBy(NaturalOrder) { it.name })
      VideoSortOrder.RecentlyModified -> sortedBy { it.lastModifiedMillis }
      VideoSortOrder.Size -> sortedBy { it.sizeBytes }
      VideoSortOrder.Duration -> sortedBy(durationOf)
    }
  return if (ascending) sorted else sorted.reversed()
}

/** Natural ordering so "Episode 2" sorts before "Episode 10", as the Android browser does. */
object NaturalOrder : Comparator<String> {
  private val chunk = Regex("(\\d+)|(\\D+)")

  override fun compare(a: String, b: String): Int {
    val left = chunk.findAll(a.lowercase()).map { it.value }.toList()
    val right = chunk.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
      val x = left[i]
      val y = right[i]
      val result =
        if (x[0].isDigit() && y[0].isDigit()) {
          x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
        } else {
          x.compareTo(y)
        }
      if (result != 0) return result
    }
    return left.size.compareTo(right.size)
  }
}
