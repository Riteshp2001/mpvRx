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
import app.gyrolet.mpvrx.domain.framecapture.SnapshotThumbnailStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SnapshotLibraryViewModel(
  application: Application,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: FrameCaptureRepository by inject()

  private val thumbnails =
    SnapshotThumbnailStore(context = application, scope = viewModelScope)

  val library: StateFlow<SnapshotLibraryData> =
    combine(repository.observeFolders(), repository.observeAll()) { folders, captures ->
      SnapshotLibraryData.of(folders, captures)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), EMPTY_LIBRARY)

  val thumbnailCache: StateFlow<Map<Long, Bitmap>> = thumbnails.thumbnails

  fun loadThumbnail(capture: FrameCapture) = thumbnails.load(capture)

  /** [onResult] carries the rejection reason so the dialog can stay open with a message. */
  fun createFolder(
    name: String,
    onResult: (FolderWriteResult) -> Unit,
  ) {
    viewModelScope.launch { onResult(repository.createFolder(name)) }
  }

  fun renameFolder(
    id: Long,
    name: String,
    onResult: (FolderWriteResult) -> Unit,
  ) {
    viewModelScope.launch { onResult(repository.renameFolder(id, name)) }
  }

  fun deleteFolders(ids: Collection<Long>) {
    if (ids.isEmpty()) return
    viewModelScope.launch {
      ids.forEach { repository.deleteFolderWithCaptures(it) }
      // The library flow is driven by the DAOs, so the removed rows and folders disappear on their own.
      thumbnails.forget(ids)
    }
  }

  fun deleteCaptures(ids: Collection<Long>) {
    if (ids.isEmpty()) return
    viewModelScope.launch {
      repository.deleteAll(ids)
      thumbnails.forget(ids)
    }
  }

  fun moveCaptures(
    ids: Collection<Long>,
    folderId: Long?,
  ) {
    if (ids.isEmpty()) return
    viewModelScope.launch { repository.moveCaptures(ids, folderId) }
  }

  companion object {
    private val EMPTY_LIBRARY = SnapshotLibraryData(emptyList(), emptyList(), emptyList())

    fun factory(application: Application): ViewModelProvider.Factory =
      viewModelFactory { initializer { SnapshotLibraryViewModel(application) } }
  }
}
