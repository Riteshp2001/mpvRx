package app.gyrolet.mpvrx.utils.media

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ProgressiveResultsPublisher<T>(
  private val onSnapshot: (suspend (List<T>) -> Unit)?,
  private val snapshot: () -> List<T>,
) {
  private var published = false
  private var pendingItems = 0
  private var lastPublicationNanos = System.nanoTime()

  suspend fun publishIfNeeded(force: Boolean = false) {
    currentCoroutineContext().ensureActive()
    val publish = onSnapshot ?: return
    pendingItems++
    val now = System.nanoTime()
    val batchSize = if (published) 128 else 16
    if (!force && pendingItems < batchSize && now - lastPublicationNanos < 150_000_000L) return
    publish(snapshot().toList())
    published = true
    pendingItems = 0
    lastPublicationNanos = System.nanoTime()
  }
}