/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.clip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClipEditorRangeState(
  val startSeconds: Float,
  val endSeconds: Float?,
)

/** Visible Clip selection shared with the player seekbar. */
object ClipEditorUiState {
  private val _state = MutableStateFlow<ClipEditorRangeState?>(null)
  val state: StateFlow<ClipEditorRangeState?> = _state.asStateFlow()

  internal fun publish(
    startSeconds: Double,
    endSeconds: Double?,
  ) {
    _state.value =
      ClipEditorRangeState(
        startSeconds = startSeconds.toFloat().coerceAtLeast(0f),
        endSeconds = endSeconds?.toFloat()?.coerceAtLeast(0f),
      )
  }

  internal fun clear() {
    _state.value = null
  }
}
