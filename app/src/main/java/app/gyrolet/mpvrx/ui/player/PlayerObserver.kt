package app.gyrolet.mpvrx.ui.player

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import java.util.concurrent.atomic.AtomicInteger

class PlayerObserver(
  private val activity: PlayerActivity,
  private val sessionId: Long,
) : MPVLib.EventObserver,
  MPVLib.LogObserver {
  private val consecutiveImageReaderFailures = AtomicInteger()

  private fun dispatch(block: () -> Unit) {
    if (!activity.acceptsPlayerCallback(sessionId)) return
    activity.runOnUiThread {
      if (activity.acceptsPlayerCallback(sessionId)) block()
    }
  }

  override fun eventProperty(property: String) {
    dispatch { activity.onObserverEvent(property) }
  }

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    dispatch { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    dispatch { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    dispatch { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    dispatch { activity.onObserverEvent(property, value) }
  }

  @Suppress("EmptyFunctionBlock")
  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {
    dispatch { activity.onObserverEvent(property, value) }
  }

  override fun event(eventId: Int, data: MPVNode) {
    dispatch {
      if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED ||
        eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART
      ) {
        consecutiveImageReaderFailures.set(0)
      }
      activity.event(eventId)
    }
  }

  override fun logMessage(prefix: String, level: Int, text: String) {
    if (!activity.acceptsPlayerCallback(sessionId)) return
    if (!prefix.contains("aimagereader", ignoreCase = true) ||
      !text.contains("acquireLatestImage failed", ignoreCase = true)
    ) {
      return
    }
    val failures = consecutiveImageReaderFailures.incrementAndGet()
    if (failures == IMAGE_READER_FAILURE_THRESHOLD) {
      dispatch { activity.onImageReaderFailure(sessionId, failures, text.trim()) }
    }
  }

  companion object {
    private const val IMAGE_READER_FAILURE_THRESHOLD = 3
  }
}

