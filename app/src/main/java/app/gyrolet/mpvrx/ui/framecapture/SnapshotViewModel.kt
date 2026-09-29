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
import app.gyrolet.mpvrx.domain.framecapture.FolderWriteResult
import app.gyrolet.mpvrx.domain.framecapture.FrameCapture
import app.gyrolet.mpvrx.domain.framecapture.FrameCaptureRepository
import app.gyrolet.mpvrx.domain.framecapture.ImageDimensions
import app.gyrolet.mpvrx.domain.framecapture.SnapshotFolder
import app.gyrolet.mpvrx.domain.framecapture.SnapshotImageLoader
import app.gyrolet.mpvrx.domain.framecapture.SnapshotThumbnailStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Header reads in flight at once while the size backfill runs. */
private const val IMAGE_SIZE_BACKFILL_CONCURRENCY = 16

/** One folder's contents, plus the folder list the move dialog needs as targets. */
data class SnapshotFolderContent(
  val folder: SnapshotFolder?,
  val snapshots: List<FrameCapture>,
  val moveTargets: List<SnapshotFolderRow>,
)

class SnapshotViewModel(
  application: Application,
  private val folderId: Long,
) : AndroidViewModel(application), KoinComponent {

  private val repository: FrameCaptureRepository by inject()

  // The folder page is the one that can show a mosaic, and a mosaic cell is twice as wide as a grid
  // cell — so this store samples for the widest thing it will ever draw.
  private val thumbnails =
    SnapshotThumbnailStore(
      context = application,
      scope = viewModelScope,
      targetMaxPx = SnapshotImageLoader.MOSAIC_THUMBNAIL_MAX_PX,
    )

  /** Guards the size backfill: it is a one-shot per folder, not per recomposition. */
  private val imageSizeBackfillStarted = AtomicBoolean(false)

  val content: StateFlow<SnapshotFolderContent> =
    combine(repository.observeFolders(), repository.observeAll()) { folders, captures ->
        // Counts come from the shared derivation so the number shown against a folder in the move
        // dialog matches the row count here.
        val library = SnapshotLibraryData.of(folders, captures)
        SnapshotFolderContent(
          folder = folders.firstOrNull { it.id == folderId },
          snapshots = captures.filter { it.folderId == folderId },
          moveTargets = library.folders,
        )
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        SnapshotFolderContent(folder = null, snapshots = emptyList(), moveTargets = emptyList()),
      )

  val thumbnailCache: StateFlow<Map<Long, Bitmap>> = thumbnails.thumbnails

  fun loadThumbnail(capture: FrameCapture) = thumbnails.load(capture)

  /**
   * Reads the image size of the snapshots recorded before the mosaic existed, so the mosaic can tile
   * them by their real shape.
   *
   * Deferred until someone actually opens the folder as a mosaic rather than run on every folder
   * open: the sizes are only ever read here, and the read is a header pass over every file in the
   * folder. Every size lands in one write, because each write invalidates the capture query — which
   * means a whole-folder re-query, re-sort and re-layout — and thirty small writes would cost thirty
   * of those to save a fraction of a second of settling.
   */
  fun backfillImageSizes(captures: List<FrameCapture>) {
    val unresolved = captures.filterNot { it.hasResolvedImageSize }
    if (unresolved.isEmpty() || !imageSizeBackfillStarted.compareAndSet(false, true)) return
    viewModelScope.launch {
      val context = getApplication<Application>()
      val sizes =
        unresolved
          .chunked(IMAGE_SIZE_BACKFILL_CONCURRENCY)
          .flatMap { chunk ->
            chunk
              .map { capture ->
                async(Dispatchers.IO) {
                  capture.id to
                    SnapshotImageLoader.readDimensions(
                      context = context,
                      imageUri = capture.imageUri,
                      imagePath = capture.imagePath,
                    )
                }
              }
              .awaitAll()
          }
          // A snapshot whose gallery file has since been deleted is stamped unreadable rather than
          // left blank, so this is the last time anything tries to open it.
          .associate { (id, size) -> id to (size ?: ImageDimensions.UNREADABLE) }
      repository.recordImageSizes(sizes)
    }
  }

  fun delete(ids: Collection<Long>) {
    if (ids.isEmpty()) return
    viewModelScope.launch {
      repository.deleteAll(ids)
      thumbnails.forget(ids)
    }
  }

  fun move(
    ids: Collection<Long>,
    folderId: Long?,
  ) {
    if (ids.isEmpty()) return
    viewModelScope.launch { repository.moveCaptures(ids, folderId) }
  }

  /**
   * Creating a folder from this page exists for the move dialog's "New folder" entry: the user is
   * filing snapshots, so making the destination on the spot beats backing out to the library page.
   */
  fun createFolder(
    name: String,
    onResult: (FolderWriteResult) -> Unit,
  ) {
    viewModelScope.launch { onResult(repository.createFolder(name)) }
  }

  companion object {
    fun factory(
      application: Application,
      folderId: Long,
    ): ViewModelProvider.Factory =
      viewModelFactory { initializer { SnapshotViewModel(application, folderId) } }
  }
}
