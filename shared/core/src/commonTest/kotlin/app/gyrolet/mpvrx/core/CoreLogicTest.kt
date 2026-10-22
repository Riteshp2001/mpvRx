package app.gyrolet.mpvrx.core

import app.gyrolet.mpvrx.core.player.PlaybackQueue
import app.gyrolet.mpvrx.core.player.QueueItem
import app.gyrolet.mpvrx.core.preferences.DarkMode
import app.gyrolet.mpvrx.core.preferences.KeyValueBackend
import app.gyrolet.mpvrx.core.preferences.KeyValuePreferenceStore
import app.gyrolet.mpvrx.core.preferences.RepeatMode
import app.gyrolet.mpvrx.core.preferences.getEnum
import app.gyrolet.mpvrx.core.util.formatDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreLogicTest {
  private val items = List(3) { QueueItem("/m/$it.mkv", "$it", isAudio = false) }

  @Test
  fun queueAdvancesAndStopsAtEnd() {
    val queue = PlaybackQueue()
    queue.set(items, 1)
    assertEquals("2", queue.next()?.title)
    assertNull(queue.next())
    assertEquals("1", queue.previous()?.title)
  }

  @Test
  fun repeatAllWrapsAndRepeatOneReplays() {
    val queue = PlaybackQueue()
    queue.set(items, 2)
    queue.setRepeatMode(RepeatMode.ALL)
    assertEquals("0", queue.next()?.title)
    queue.setRepeatMode(RepeatMode.ONE)
    assertEquals("0", queue.nextAfterCompletion()?.title)
  }

  @Test
  fun shuffleKeepsCurrentItemPlaying() {
    val queue = PlaybackQueue(kotlin.random.Random(7))
    queue.set(items, 1)
    queue.setShuffle(true)
    assertEquals("1", queue.state.value.current?.title)
    assertEquals(1, queue.state.value.order.first())
  }

  @Test
  fun preferencesRoundTripThroughBackend() {
    val backend =
      object : KeyValueBackend {
        var saved: Map<String, String> = emptyMap()

        override fun load() = saved

        override fun save(values: Map<String, String>) {
          saved = values
        }
      }
    val store = KeyValuePreferenceStore(backend)
    store.getEnum("dark_mode", DarkMode.System).set(DarkMode.Dark)
    store.getStringSet("folders").set(setOf("C:\\Videos", "D:\\Anime"))
    val reloaded = KeyValuePreferenceStore(backend)
    assertEquals(DarkMode.Dark, reloaded.getEnum("dark_mode", DarkMode.System).get())
    assertEquals(setOf("C:\\Videos", "D:\\Anime"), reloaded.getStringSet("folders").get())
  }

  @Test
  fun formatsDurations() {
    assertEquals("01:05", formatDuration(65.0))
    assertEquals("1:01:01", formatDuration(3661.0))
    assertEquals("-00:10", formatDuration(-10.0))
  }
}
