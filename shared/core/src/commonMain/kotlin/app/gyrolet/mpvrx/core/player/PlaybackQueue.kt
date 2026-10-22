package app.gyrolet.mpvrx.core.player

import app.gyrolet.mpvrx.core.preferences.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class QueueItem(
  val path: String,
  val title: String,
  val isAudio: Boolean,
)

data class QueueState(
  val items: List<QueueItem> = emptyList(),
  /** Index into [items] of the playing entry. */
  val currentIndex: Int = -1,
  /** Playback order as indices into [items]; differs from natural order when shuffled. */
  val order: List<Int> = emptyList(),
  val shuffle: Boolean = false,
  val repeatMode: RepeatMode = RepeatMode.OFF,
) {
  val current: QueueItem? get() = items.getOrNull(currentIndex)
  private val position: Int get() = order.indexOf(currentIndex)
  val hasNext: Boolean get() = repeatMode == RepeatMode.ALL && items.isNotEmpty() || position in 0 until order.lastIndex
  val hasPrevious: Boolean get() = repeatMode == RepeatMode.ALL && items.isNotEmpty() || position > 0

  internal fun indexAfter(step: Int): Int? {
    if (order.isEmpty()) return null
    val target = position + step
    return when {
      target in order.indices -> order[target]
      repeatMode == RepeatMode.ALL -> order[(target % order.size + order.size) % order.size]
      else -> null
    }
  }
}

/** Platform-neutral queue: next/previous, shuffle and repeat semantics shared by every UI. */
class PlaybackQueue(private val random: kotlin.random.Random = kotlin.random.Random.Default) {
  private val mutableState = MutableStateFlow(QueueState())
  val state: StateFlow<QueueState> = mutableState

  fun set(items: List<QueueItem>, startIndex: Int) {
    mutableState.update { current ->
      val start = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
      current.copy(
        items = items,
        currentIndex = if (items.isEmpty()) -1 else start,
        order = buildOrder(items.size, start, current.shuffle),
      )
    }
  }

  fun jumpTo(index: Int): QueueItem? {
    val state = mutableState.value
    if (index !in state.items.indices) return null
    mutableState.value = state.copy(currentIndex = index)
    return state.items[index]
  }

  fun next(): QueueItem? = move(1)

  fun previous(): QueueItem? = move(-1)

  /** Item to auto-play after natural end of file, honoring repeat-one. */
  fun nextAfterCompletion(): QueueItem? {
    val state = mutableState.value
    return if (state.repeatMode == RepeatMode.ONE) state.current else next()
  }

  fun setShuffle(enabled: Boolean) {
    mutableState.update { it.copy(shuffle = enabled, order = buildOrder(it.items.size, it.currentIndex, enabled)) }
  }

  fun setRepeatMode(mode: RepeatMode) {
    mutableState.update { it.copy(repeatMode = mode) }
  }

  fun remove(index: Int) {
    mutableState.update { state ->
      if (index !in state.items.indices || index == state.currentIndex) return@update state
      val items = state.items.toMutableList().apply { removeAt(index) }
      val current = if (index < state.currentIndex) state.currentIndex - 1 else state.currentIndex
      state.copy(items = items, currentIndex = current, order = buildOrder(items.size, current, state.shuffle))
    }
  }

  fun move(from: Int, to: Int) {
    mutableState.update { state ->
      if (from !in state.items.indices || to !in state.items.indices) return@update state
      val playing = state.current
      val items = state.items.toMutableList().apply { add(to, removeAt(from)) }
      val current = items.indexOf(playing)
      state.copy(items = items, currentIndex = current, order = buildOrder(items.size, current, state.shuffle))
    }
  }

  fun append(items: List<QueueItem>) {
    mutableState.update { state ->
      val all = state.items + items
      val current = if (state.currentIndex < 0 && all.isNotEmpty()) 0 else state.currentIndex
      state.copy(items = all, currentIndex = current, order = buildOrder(all.size, current, state.shuffle))
    }
  }

  private fun move(step: Int): QueueItem? {
    val state = mutableState.value
    val index = state.indexAfter(step) ?: return null
    mutableState.value = state.copy(currentIndex = index)
    return state.items[index]
  }

  private fun buildOrder(size: Int, current: Int, shuffle: Boolean): List<Int> {
    val natural = List(size) { it }
    if (!shuffle || size < 2) return natural
    // Keep the playing item first so shuffling never interrupts it.
    val rest = natural.filter { it != current }.shuffled(random)
    return if (current in natural) listOf(current) + rest else rest
  }
}
