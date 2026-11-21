/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.download

import app.gyrolet.mpvrx.database.entities.DownloadItemEntity
import java.io.File

enum class AppDownloadStatus {
  QUEUED,
  RUNNING,
  SUCCESS,
  FAILED,
  CANCELLED,
  ;

  companion object {
    fun from(raw: String): AppDownloadStatus = entries.firstOrNull { it.name == raw } ?: QUEUED
  }
}

/** Where a queued download originated from. */
object DownloadSources {
  const val LINK = "link"
  const val JELLYFIN = "jellyfin"
  const val TORRENT = "torrent"
}

/** Metadata captured when queueing, persisted onto the registry row. */
data class DownloadMetadata(
  val source: String = DownloadSources.LINK,
  val title: String = "",
  val posterUrl: String? = null,
  /** Canonical URL the user downloaded from (stream page or direct link). */
  val sourceUrl: String? = null,
  val jellyfinServerId: String? = null,
  val jellyfinItemId: String? = null,
  val jellyfinSeriesName: String? = null,
  val seasonNumber: Int? = null,
  val episodeNumber: Int? = null,
  val isAudio: Boolean = false,
)

/** A registry row with typed status and file helpers, consumed by the UI. */
data class AppDownload(
  val entity: DownloadItemEntity,
) {
  val id: Long get() = entity.id
  val status: AppDownloadStatus get() = AppDownloadStatus.from(entity.status)

  val displayTitle: String
    get() = entity.title.ifBlank { entity.fileName }

  val file: File
    get() = File(entity.dirPath, entity.fileName)

  val isCompleted: Boolean
    get() = status == AppDownloadStatus.SUCCESS

  val isActive: Boolean
    get() = status == AppDownloadStatus.QUEUED || status == AppDownloadStatus.RUNNING

  /** Completed and the file is still present on disk. */
  val isPlayable: Boolean
    get() = isCompleted && file.isFile
}
