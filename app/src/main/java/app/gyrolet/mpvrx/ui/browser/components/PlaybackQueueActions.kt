/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.components

import android.content.Context
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackSession

enum class QueueInsertion {
  PlayNext,
  AddToEnd,
}

fun addVideosToPlaybackQueue(
  context: Context,
  videos: List<Video>,
  insertion: QueueInsertion,
): Boolean {
  if (videos.isEmpty()) return false
  if (!PlaybackSession.queue.value.hasItems) {
    Toast.makeText(context, R.string.queue_requires_playback, Toast.LENGTH_SHORT).show()
    return false
  }

  val items =
    videos.map { video ->
      val uri = video.uri.toString()
      PlaybackItem.fromUri(
        uri = uri,
        stableId =
          video.path
            .takeIf { path -> path.isNotBlank() && !path.contains("://") }
            ?.let(PlaybackIdentity::forLocalPath),
        title = video.displayName.ifBlank { video.title },
        mimeType = video.mimeType,
        durationSeconds = (video.duration / 1000L).toInt().takeIf { it > 0 },
        videoWidth = video.width,
        videoHeight = video.height,
      )
    }
  val added =
    when (insertion) {
      QueueInsertion.PlayNext -> PlaybackSession.insertQueueItemsNext(items)
      QueueInsertion.AddToEnd -> PlaybackSession.appendQueueItems(items)
    }
  if (added) {
    val message =
      when (insertion) {
        QueueInsertion.PlayNext -> R.string.queue_added_next
        QueueInsertion.AddToEnd -> R.string.queue_added_end
      }
    Toast.makeText(context, context.getString(message, items.size), Toast.LENGTH_SHORT).show()
  }
  return added
}