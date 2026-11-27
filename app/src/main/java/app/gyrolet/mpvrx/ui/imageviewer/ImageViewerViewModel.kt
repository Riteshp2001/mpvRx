/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.imageviewer

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkImageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ImageViewerViewModel(
  application: Application,
  private val connectionId: Long,
  val images: List<NetworkFile>,
  val initialIndex: Int,
) : AndroidViewModel(application),
  KoinComponent {
  private val imageRepository: NetworkImageRepository by inject()

  private val _currentIndex = MutableStateFlow(initialIndex.coerceIn(0, images.lastIndex.coerceAtLeast(0)))
  val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

  /** Per-page state map keyed by file path. */
  private val _imageStates = MutableStateFlow<Map<String, ImageViewerItemState>>(emptyMap())
  val imageStates: StateFlow<Map<String, ImageViewerItemState>> = _imageStates.asStateFlow()

  /** Per-page rotation angles, not persisted across sessions. */
  private val _rotations = MutableStateFlow<Map<String, Float>>(emptyMap())
  val rotations: StateFlow<Map<String, Float>> = _rotations.asStateFlow()

  private val preloadJobs = mutableMapOf<String, Job>()

  private var thumbnailPreloadJob: Job? = null

  /**
   * True between [onViewerClosed] and the next [onViewerOpened]; in-flight loads must not write into
   * a released state map. Re-armed per session, not latched for the life of the ViewModel.
   */
  @Volatile private var closed = false

  init {
    startThumbnailPreload()
  }

  /**
   * Starts a viewing session.
   *
   * This ViewModel is scoped to the Activity — Navigation3 gives back-stack entries no
   * ViewModelStore of their own — so re-opening the same image after [onViewerClosed] hands back
   * this very instance. Re-arming here is what makes the second visit work; without it the instance
   * stays closed forever and every page renders as "no image".
   */
  fun onViewerOpened() {
    closed = false
    // Every other piece of per-session state is reset on close; the page number has to be too, or the
    // first frame shows the previous session's image and the pre-load window is centred on it.
    _currentIndex.value = initialIndex.coerceIn(0, images.lastIndex.coerceAtLeast(0))
    preloadNeighbors()
    if (thumbnailPreloadJob?.isActive != true) startThumbnailPreload()
  }

  private fun startThumbnailPreload() {
    thumbnailPreloadJob?.cancel()
    val window = thumbnailPreloadWindow()
    if (window.isEmpty()) return
    thumbnailPreloadJob =
      viewModelScope.launch {
        // Batched to avoid overwhelming the network / disk IO. Each batch is isolated: one unreadable
        // file must not abort the pre-load for the rest of the window.
        window.chunked(THUMBNAIL_BATCH_SIZE).forEach { batch ->
          val thumbnails =
            batch
              .map { file -> async { runCatching { loadThumbnail(file) }.getOrNull() } }
              .awaitAll()
          thumbnails.forEachIndexed { index, thumbnail ->
            if (thumbnail != null) updateState(batch[index].path) { it.copy(thumbnail = thumbnail) }
          }
        }
      }
  }

  /**
   * Pages either side of the current one whose thumbnail still needs fetching.
   *
   * The whole folder used to be queued here, and a missing thumbnail costs a **full download of the
   * original** before it can be sampled — so opening one image in an eight-hundred image folder
   * downloaded every image in it.
   */
  private fun thumbnailPreloadWindow(): List<NetworkFile> {
    val index = _currentIndex.value
    val state = _imageStates.value
    return (index - PRELOAD_WINDOW..index + PRELOAD_WINDOW)
      .mapNotNull { images.getOrNull(it) }
      .filter { state[it.path]?.thumbnail == null }
  }

  fun setCurrentIndex(index: Int) {
    _currentIndex.value = index.coerceIn(0, images.lastIndex.coerceAtLeast(0))
    pruneStateWindow()
    preloadNeighbors()
    // Re-centre the thumbnail window as the user swipes; pages already fetched are skipped, so this
    // is cheap on every page change.
    startThumbnailPreload()
  }

  fun rotateCurrentImage() {
    val current = images.getOrNull(_currentIndex.value) ?: return
    val path = current.path
    val currentRotation = _rotations.value[path] ?: 0f
    _rotations.value = _rotations.value + (path to ((currentRotation + 90f) % 360f))
  }

  /**
   * Load the full image for [file]. If already loaded or loading, this is a no-op.
   *
   * [force] re-attempts a file whose bytes previously failed to decode; only a manual retry sets it.
   */
  fun loadImage(
    file: NetworkFile,
    force: Boolean = false,
  ) {
    val current = _imageStates.value[file.path]
    if (current?.bitmap != null || current?.isLoading == true) return

    updateState(file.path) { it.copy(isLoading = true, error = null) }

    viewModelScope.launch {
      try {
        val bitmap = fetchFullImage(file, force)
        if (bitmap != null) {
          updateState(file.path) { it.copy(bitmap = bitmap, isLoading = false) }
        } else {
          updateState(file.path) { it.copy(isLoading = false, error = Exception("Failed to load image")) }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        updateState(file.path) { it.copy(isLoading = false, error = e) }
      }
    }
  }

  /**
   * Pre-load the full image for [file] without changing loading flags.
   */
  fun preloadImage(file: NetworkFile) {
    if (file.path in preloadJobs) return
    if (_imageStates.value[file.path]?.bitmap != null) return

    preloadJobs[file.path] =
      viewModelScope.launch {
        try {
          val bitmap = fetchFullImage(file, force = false)
          if (bitmap != null) {
            updateState(file.path) { it.copy(bitmap = bitmap) }
          }
        } catch (_: CancellationException) {
          // Routine: closing the viewer cancels pre-loads. The finally block still runs.
        } catch (_: Exception) {
          // ignore pre-load failures
        } finally {
          preloadJobs.remove(file.path)
        }
      }
  }

  fun retryImage(file: NetworkFile) {
    updateState(file.path) { it.copy(error = null) }
    loadImage(file, force = true)
  }

  /**
   * Releases everything tied to one viewing session.
   *
   * Navigation3 gives each back-stack entry no ViewModelStore of its own here, so this ViewModel is
   * scoped to the Activity and [onCleared] only runs when the Activity is destroyed. The viewer
   * therefore calls this explicitly when it leaves composition.
   */
  fun onViewerClosed() {
    closed = true
    thumbnailPreloadJob?.cancel()
    preloadJobs.values.forEach { it.cancel() }
    preloadJobs.clear()
    _imageStates.value = emptyMap()
    _rotations.value = emptyMap()
    imageRepository.clearViewerMemoryCache()
  }

  override fun onCleared() {
    super.onCleared()
    onViewerClosed()
  }

  private fun preloadNeighbors() {
    val idx = _currentIndex.value
    listOfNotNull(
      images.getOrNull(idx - 1),
      images.getOrNull(idx + 1),
    ).forEach { preloadImage(it) }
  }

  /**
   * Keeps full-resolution bitmaps only for the current page and its immediate neighbours, and
   * thumbnails for a wider window around them.
   *
   * The repository's LRU cannot bound this map on its own: an entry here holds a strong reference,
   * so every page the user has swiped past would otherwise pin a multi-megabyte bitmap for the
   * whole session. Dropping a bitmap is cheap to undo — swiping back re-reads it from the
   * repository's viewer memory cache.
   */
  private fun pruneStateWindow() {
    val index = _currentIndex.value
    val bitmapWindow =
      setOfNotNull(
        images.getOrNull(index - 1)?.path,
        images.getOrNull(index)?.path,
        images.getOrNull(index + 1)?.path,
      )
    val thumbnailWindow =
      (index - THUMBNAIL_WINDOW..index + THUMBNAIL_WINDOW)
        .mapNotNull { images.getOrNull(it)?.path }
        .toSet()

    val current = _imageStates.value
    val pruned =
      current
        .mapNotNull { (path, state) ->
          when {
            path in bitmapWindow -> path to state
            path in thumbnailWindow -> path to state.copy(bitmap = null)
            else -> null
          }
        }.toMap()
    if (pruned != current) _imageStates.value = pruned
  }

  // The repository already switches to Dispatchers.IO internally; wrapping again only added a
  // redundant context hop.
  private suspend fun fetchFullImage(
    file: NetworkFile,
    force: Boolean,
  ): Bitmap? = imageRepository.getFullImage(connectionId, file, force = force)

  private suspend fun loadThumbnail(file: NetworkFile): Bitmap? =
    imageRepository.getThumbnail(connectionId, file, widthPx = 256, heightPx = 256)

  private fun updateState(
    path: String,
    transform: (ImageViewerItemState) -> ImageViewerItemState,
  ) {
    if (closed) return
    _imageStates.value =
      _imageStates.value.toMutableMap().apply {
        this[path] = transform(this[path] ?: ImageViewerItemState())
      }
  }

  companion object {
    private const val THUMBNAIL_BATCH_SIZE = 5

    /**
     * Pages either side of the current one whose thumbnail is fetched.
     *
     * Kept well below [THUMBNAIL_WINDOW] because fetching one costs a full download of the original,
     * while retaining one costs a few hundred kilobytes.
     */
    private const val PRELOAD_WINDOW = 10

    /** Pages either side of the current one whose thumbnail is kept in memory. */
    private const val THUMBNAIL_WINDOW = 30

    fun factory(
      application: Application,
      connectionId: Long,
      images: List<NetworkFile>,
      initialIndex: Int,
    ): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          ImageViewerViewModel(application, connectionId, images, initialIndex)
        }
      }
  }
}
