/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.download

import android.content.Context
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Empty adapter for shared download screens; no extractor service or process is compiled here. */
@Suppress("UNUSED_PARAMETER")
class YtdlpDownloadEngine(
  context: Context,
  preferences: YtdlPreferences,
  outputReservations: DownloadOutputReservations,
) {
  enum class JobState { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

  data class Job(
    val id: Int,
    val url: String,
    val title: String,
    val directory: String,
    val formatSelector: String? = null,
    val mergeSeparateStreams: Boolean = false,
    val posterUrl: String? = null,
    val state: JobState = JobState.QUEUED,
    val progressPercent: Float = 0f,
    val detail: String = "",
    val error: String? = null,
    val outputFile: String? = null,
    val artifactFiles: Set<String> = emptySet(),
  ) {
    val isActive: Boolean get() = state == JobState.QUEUED || state == JobState.RUNNING
  }

  val jobs: StateFlow<List<Job>> = MutableStateFlow<List<Job>>(emptyList()).asStateFlow()

  fun enqueue(
    url: String,
    title: String,
    directory: File,
    formatSelector: String? = null,
    mergeSeparateStreams: Boolean = false,
    posterUrl: String? = null,
  ): Int = -1

  fun cancel(id: Int) = Unit

  fun retry(id: Int) = Unit

  fun remove(
    id: Int,
    deleteFiles: Boolean = false,
  ) = Unit

  suspend fun drainQueue(onJobUpdate: (Job) -> Unit) = Unit
}
