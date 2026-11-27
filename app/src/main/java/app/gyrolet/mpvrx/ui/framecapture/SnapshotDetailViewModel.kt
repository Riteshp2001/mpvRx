/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.framecapture

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.domain.framecapture.FrameCapture
import app.gyrolet.mpvrx.domain.framecapture.FrameCaptureRepository
import app.gyrolet.mpvrx.domain.framecapture.SnapshotImageLoader
import app.gyrolet.mpvrx.domain.framecapture.VideoAvailability
import app.gyrolet.mpvrx.domain.framecapture.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** One entry in the viewer's pager, carrying only what the UI needs. */
@Serializable
data class SnapshotDetailItem(
  val id: Long,
  val videoTitle: String,
  val positionLabel: String,
  val imageUri: String?,
  val imagePath: String?,
  val videoUri: String,
  val videoPath: String?,
  val positionMs: Long,
)

fun FrameCapture.toDetailItem(): SnapshotDetailItem =
  SnapshotDetailItem(
    id = id,
    videoTitle = videoTitle,
    positionLabel = formattedPosition,
    imageUri = imageUri,
    imagePath = imagePath,
    videoUri = videoUri,
    videoPath = videoPath,
    positionMs = positionMs,
  )

class SnapshotDetailViewModel(
  application: Application,
  private val captures: List<SnapshotDetailItem>,
  private val initialIndex: Int,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: FrameCaptureRepository by inject()

  private val _currentIndex = MutableStateFlow(initialIndex.coerceIn(0, captures.lastIndex.coerceAtLeast(0)))
  val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

  private val _images = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
  val images: StateFlow<Map<Long, Bitmap>> = _images.asStateFlow()

  private val _deletedIds = MutableStateFlow<Set<Long>>(emptySet())
  val deletedIds: StateFlow<Set<Long>> = _deletedIds.asStateFlow()

  private val _rotations = MutableStateFlow<Map<Long, Float>>(emptyMap())
  val rotations: StateFlow<Map<Long, Float>> = _rotations.asStateFlow()

  /**
   * Captions are resolved per capture rather than carried on the route payload, which keeps a
   * renamed connection reflected and the saved state small (see SnapshotDetailScreen's list key).
   */
  private val _sources = MutableStateFlow<Map<Long, VideoSource>>(emptyMap())
  val sources: StateFlow<Map<Long, VideoSource>> = _sources.asStateFlow()

  private val inFlight = mutableSetOf<Long>()
  private val sourceInFlight = mutableSetOf<Long>()

  fun setCurrentIndex(index: Int) {
    _currentIndex.value = index.coerceIn(0, captures.lastIndex.coerceAtLeast(0))
    preloadNeighbors()
  }

  fun rotate(item: SnapshotDetailItem) {
    _rotations.value = _rotations.value + (item.id to ((_rotations.value[item.id] ?: 0f) + 90f) % 360f)
  }

  fun loadSource(item: SnapshotDetailItem) {
    if (_sources.value.containsKey(item.id) || !sourceInFlight.add(item.id)) return
    viewModelScope.launch {
      try {
        val source = repository.videoSource(item.toDomain())
        _sources.value = _sources.value + (item.id to source)
      } finally {
        sourceInFlight.remove(item.id)
      }
    }
  }

  fun loadImage(item: SnapshotDetailItem) {
    if (_images.value.containsKey(item.id) || !inFlight.add(item.id)) return
    viewModelScope.launch {
      try {
        val bitmap =
          withContext(Dispatchers.IO) {
            SnapshotImageLoader.load(
              context = getApplication(),
              imageUri = item.imageUri,
              imagePath = item.imagePath,
              targetMaxPx = SnapshotImageLoader.VIEWER_MAX_PX,
            )
          }
        if (bitmap != null) _images.value = _images.value + (item.id to bitmap)
      } finally {
        inFlight.remove(item.id)
      }
    }
  }

  private fun preloadNeighbors() {
    val index = _currentIndex.value
    listOfNotNull(captures.getOrNull(index - 1), captures.getOrNull(index + 1)).forEach(::loadImage)
  }

  suspend fun isImageAvailable(item: SnapshotDetailItem): Boolean = repository.isImageAvailable(item.toDomain())

  suspend fun videoAvailability(item: SnapshotDetailItem): VideoAvailability =
    repository.videoAvailability(item.toDomain())

  suspend fun delete(item: SnapshotDetailItem) {
    repository.delete(item.id)
    _deletedIds.value = _deletedIds.value + item.id
    _images.value = _images.value - item.id
  }

  companion object {
    fun factory(
      application: Application,
      captures: List<SnapshotDetailItem>,
      initialIndex: Int,
    ): ViewModelProvider.Factory =
      viewModelFactory {
        initializer { SnapshotDetailViewModel(application, captures, initialIndex) }
      }
  }
}

/**
 * The availability checks take a domain [FrameCapture], but the viewer only carries a
 * [SnapshotDetailItem]; this bridges the two without the pager payload holding DB-shaped fields it
 * otherwise has no use for.
 */
private fun SnapshotDetailItem.toDomain(): FrameCapture =
  FrameCapture(
    id = id,
    imageUri = imageUri,
    imagePath = imagePath,
    videoUri = videoUri,
    videoPath = videoPath,
    videoTitle = videoTitle,
    positionMs = positionMs,
    capturedAt = 0L,
  )
