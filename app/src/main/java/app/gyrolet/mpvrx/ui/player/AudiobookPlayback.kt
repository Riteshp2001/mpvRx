package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.gyrolet.mpvrx.database.dao.AudiobookDao
import app.gyrolet.mpvrx.database.entities.Audiobook
import app.gyrolet.mpvrx.database.entities.AudiobookChapter
import app.gyrolet.mpvrx.database.entities.AudiobookChapterEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext

internal data class AudiobookProgress(
  val item: AudiobookPlaybackInfo,
  val positionMs: Long,
  val reachedEnd: Boolean,
  val capturedAtNanos: Long,
  val playedAt: Long = System.currentTimeMillis(),
)

internal object AudiobookPlayback {
  const val EXTRA_BOOK_ID = "app.gyrolet.mpvrx.AUDIOBOOK_ID"
  const val EXTRA_TRACK_ID = "app.gyrolet.mpvrx.AUDIOBOOK_TRACK_ID"
  const val EXTRA_POSITION_MS = "app.gyrolet.mpvrx.AUDIOBOOK_POSITION_MS"

  private val dao by lazy { GlobalContext.get().get<AudiobookDao>() }
  private val json by lazy { GlobalContext.get().get<Json>() }
  private val absRepo by lazy { runCatching { GlobalContext.get().get<app.gyrolet.mpvrx.repository.AudiobookshelfRepository>() }.getOrNull() }
  private val started = AtomicBoolean(false)
  private val handler = CoroutineExceptionHandler { _, error ->
    Log.e("AudiobookPlayback", "Audiobook operation failed", error)
    _saveFailed.value = true
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
  private data class SaveWork(val progress: AudiobookProgress? = null, val completion: CompletableDeferred<Unit>? = null)
  private val saves = Channel<SaveWork>(Channel.UNLIMITED)
  private val _book = MutableStateFlow<Audiobook?>(null)
  val book = _book.asStateFlow()
  private val _chapters = MutableStateFlow<List<AudiobookChapter>>(emptyList())
  val chapters = _chapters.asStateFlow()
  private val _saveFailed = MutableStateFlow(false)
  val saveFailed = _saveFailed.asStateFlow()
  private val _timer = MutableStateFlow<SleepTimer?>(null)
  val timer = _timer.asStateFlow()
  @Volatile private var pausedAt = 0L
  @Volatile private var pausedTrack: Long? = null
  @Volatile private var sleepStoppedTrack: Long? = null

  data class SleepTimer(val bookId: Long, val deadline: Long? = null, val trackId: Long? = null, val chapterEndMs: Long? = null,
    val remainingSeconds: Int = 0, val durationMinutes: Int? = null)

  fun ensureStarted() {
    if (!started.compareAndSet(false, true)) return
    scope.launch(Dispatchers.IO) {
      val savedAt = mutableMapOf<Long, Long>()
      for (work in saves) {
        try {
          work.progress?.let { progress ->
            if (progress.capturedAtNanos > (savedAt[progress.item.bookId] ?: 0L)) {
              dao.saveProgress(progress.item.bookId, progress.item.trackId, progress.positionMs, progress.playedAt, progress.reachedEnd)
              savedAt[progress.item.bookId] = progress.capturedAtNanos
              _saveFailed.value = false

              val currentBook = dao.getBook(progress.item.bookId)
              val sourceKey = currentBook?.book?.sourceKey
              if (sourceKey?.startsWith("abs:") == true) {
                val parts = sourceKey.split(":")
                if (parts.size >= 3) {
                  val serverId = parts[1].toLongOrNull()
                  val itemId = parts[2]
                  if (serverId != null) {
                    val posMs = currentBook.positionInBook(progress.item.trackId, progress.positionMs)
                    val durMs = currentBook.durationMs
                    val isFinished = progress.reachedEnd || (durMs > 0 && posMs >= durMs - 5000)
                    absRepo?.syncProgress(
                      serverId = serverId,
                      itemId = itemId,
                      currentTimeSeconds = posMs / 1000.0,
                      durationSeconds = durMs / 1000.0,
                      isFinished = isFinished,
                    )
                  }
                }
              }
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.e("AudiobookPlayback", "Could not save progress", error)
          _saveFailed.value = true
        } finally {
          work.completion?.complete(Unit)
        }
      }
    }
    scope.launch {
      PlaybackSession.state.map { it.currentItem?.audiobook?.bookId }.distinctUntilChanged().collectLatest { id ->
        if (_timer.value?.bookId != id) _timer.value = null
        _book.value = null
        _chapters.value = emptyList()
        if (id != null) combine(dao.observeBook(id), dao.observeChapters(id)) { book, chapters -> book to chapters }
          .collect { (book, chapters) ->
            _book.value = book
            _chapters.value = book?.chapterTimeline(chapters).orEmpty()
          }
      }
    }
    scope.launch {
      var lastSaved: Triple<AudiobookPlaybackInfo, Long, Boolean>? = null
      var ticks = 0
      while (isActive) {
        val progress = PlaybackSession.audiobookProgress()
        if (progress != null && ++ticks % 4 == 0) {
          val key = Triple(progress.item, progress.positionMs / 1000, progress.reachedEnd)
          if (key != lastSaved) {
            saves.trySend(SaveWork(progress))
            lastSaved = key
          }
        }
        val timer = _timer.value
        if (timer != null) {
          val currentBook = PlaybackSession.state.value.currentItem?.audiobook?.bookId
          if (currentBook != timer.bookId || PlaybackSession.state.value.phase in setOf(PlaybackPhase.STOPPING, PlaybackPhase.ERROR)) {
            _timer.value = null
          } else if (timer.deadline != null) {
            val remaining = ((timer.deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
            if (remaining == 0) {
              sleepStoppedTrack = progress?.item?.trackId
              _timer.value = null
              PlaybackSession.setPropertyBoolean("pause", true)
            } else if (remaining != timer.remainingSeconds) {
              _timer.value = timer.copy(remainingSeconds = remaining)
            }
          } else if (progress != null && progress.item.trackId == timer.trackId && progress.positionMs >= (timer.chapterEndMs ?: Long.MAX_VALUE)) {
            sleepStoppedTrack = progress.item.trackId
            _timer.value = null
            PlaybackSession.setPropertyBoolean("pause", true)
          }
        }
        delay(250)
      }
    }
  }

  fun capture(reachedEnd: Boolean = false) {
    if (!started.get()) return
    PlaybackSession.audiobookProgress(reachedEnd)?.let { saves.trySend(SaveWork(it)) }
  }

  suspend fun flush() {
    if (!started.get()) return
    val completion = CompletableDeferred<Unit>()
    saves.send(SaveWork(completion = completion))
    completion.await()
  }

  fun onPauseRequested(paused: Boolean) {
    if (!started.get()) return
    val progress = PlaybackSession.audiobookProgress() ?: return
    if (paused) {
      if (PlaybackSession.state.value.paused.not()) {
        pausedAt = SystemClock.elapsedRealtime()
        pausedTrack = progress.item.trackId
      }
      capture()
    } else if (PlaybackSession.state.value.paused) {
      sleepStoppedTrack = null
      val seconds = _book.value?.book?.takeIf { it.id == progress.item.bookId }?.rewindSeconds ?: 0
      if (pausedAt > 0 && pausedTrack == progress.item.trackId && SystemClock.elapsedRealtime() - pausedAt >= 5000 && seconds > 0) {
        seek((progress.positionMs - seconds * 1000L).coerceAtLeast(0))
      }
      pausedAt = 0
    }
  }

  fun seek(positionMs: Long) {
    pausedAt = 0
    PlaybackSession.command("seek", (positionMs.coerceAtLeast(0) / 1000.0).toString(), "absolute+exact")
  }

  fun seekBy(seconds: Int) {
    val progress = PlaybackSession.audiobookProgress() ?: return
    val book = _book.value?.takeIf { it.book.id == progress.item.bookId } ?: return
    seekInBook(book.positionInBook(progress.item.trackId, progress.positionMs) + seconds * 1000L)
  }

  fun seekInBook(positionMs: Long, resumePlayback: Boolean = false) {
    val state = PlaybackSession.state.value
    val info = state.currentItem?.audiobook ?: return
    val book = _book.value?.takeIf { it.book.id == info.bookId } ?: return
    val (track, localPosition) = book.resolvePosition(positionMs) ?: return
    pausedAt = 0
    sleepStoppedTrack = null
    if (track.id == info.trackId) {
      seek(localPosition)
      if (resumePlayback) PlaybackSession.setPropertyBoolean("pause", false)
    } else {
      scope.launch(Dispatchers.IO) {
        PlaybackSession.seekAudiobookTrack(info.bookId, track.id, localPosition, state.generation, resumePlayback)
      }
    }
  }

  fun seekToBookmark(trackId: Long, positionMs: Long) {
    val current = _book.value ?: return
    if (current.tracks.none { it.id == trackId }) return
    seekInBook(current.positionInBook(trackId, positionMs))
  }

  fun currentChapter(): AudiobookChapter? {
    val progress = PlaybackSession.audiobookProgress() ?: return null
    return chapters.value.lastOrNull { it.trackId == progress.item.trackId && it.startMs <= progress.positionMs }
  }

  fun resumeAtEnd() {
    val info = PlaybackSession.state.value.currentItem?.audiobook ?: return
    val book = _book.value?.takeIf { it.book.id == info.bookId } ?: return
    val track = book.tracks.firstOrNull { it.id == info.trackId } ?: return
    val nextPosition = book.positionInBook(track.id, track.durationMs)
    seekInBook(if (nextPosition >= book.durationMs) 0 else nextPosition, resumePlayback = true)
  }

  fun onFileLoaded(item: PlaybackItem, generation: Long) {
    val info = item.audiobook ?: return
    ensureStarted()
    pausedAt = 0
    sleepStoppedTrack = null
    scope.launch {
      val book = dao.getBook(info.bookId) ?: return@launch
      PlaybackSession.state.first {
        it.generation != generation || it.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND, PlaybackPhase.ERROR, PlaybackPhase.IDLE)
      }
      if (!PlaybackSession.isCurrentGeneration(generation)) return@launch
      val track = book.tracks.firstOrNull { it.id == info.trackId } ?: return@launch
      val audio = PlaybackSession.audioState.value.takeIf { PlaybackSession.usingExoPlayer && it.generation == generation }
      val tags = if (audio != null) audio.metadata else
        runCatching { PlaybackSession.getPropertyNode("metadata")?.toObject<Map<String, String>>(json) }
          .getOrNull().orEmpty().mapKeys { it.key.lowercase(java.util.Locale.ROOT) }
      fun tag(vararg keys: String) = keys.firstNotNullOfOrNull { tags[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()
      val duration = PlaybackSession.getPropertyDouble("duration")?.takeIf { it.isFinite() && it > 0 }?.times(1000)?.toLong() ?: track.durationMs
      val nodes = (audio?.chapters?.map { ChapterNode(it.positionMs / 1000f, it.title) }
        ?: runCatching { PlaybackSession.getPropertyNode("chapter-list")?.toObject<List<ChapterNode>>(json) }.getOrNull().orEmpty())
        .filter { it.time.isFinite() && it.time >= 0 && it.time * 1000 < duration }
        .distinctBy { it.time }.sortedBy { it.time }
      if (!PlaybackSession.isCurrentGeneration(generation)) return@launch
      dao.updateDuration(track.id, duration)
      dao.fillMetadata(info.bookId, tag("artist", "album_artist", "author"), tag("narrator", "composer"),
        tag("series", "grouping"), tag("series-part", "series_part"), tag("description", "comment"),
        tag("language"), tag("publisher"), tag("date", "year"), tag("genre"), tag("isbn"), tag("asin"))
      if (nodes.isNotEmpty() && dao.getChapters(track.id).isEmpty()) {
        val completeNodes = if (nodes.first().time > 0) listOf(ChapterNode(0f, track.title)) + nodes else nodes
        val chapters = completeNodes.mapIndexedNotNull { index, node ->
          val start = (node.time * 1000).toLong()
          val end = completeNodes.getOrNull(index + 1)?.time?.times(1000)?.toLong() ?: duration
          if (end > start) AudiobookChapterEntity(track.id, start, end, node.title.ifBlank { track.title }) else null
        }
        dao.replaceChapters(track.id, chapters)
      }
    }
  }

  fun handleEndOfFile(): Boolean {
    val info = PlaybackSession.state.value.currentItem?.audiobook ?: return false
    capture(reachedEnd = true)
    val lastTrack = PlaybackSession.queue.value.items.lastOrNull()?.audiobook?.trackId == info.trackId
    val timer = _timer.value
    if (lastTrack || timer?.trackId == info.trackId || sleepStoppedTrack == info.trackId) {
      _timer.value = null
      PlaybackSession.setPropertyBoolean("pause", true)
      return true
    }
    return false
  }

  fun setTimer(minutes: Int?, chapterEndMs: Long? = null) {
    val progress = PlaybackSession.audiobookProgress() ?: return
    sleepStoppedTrack = null
    _timer.value = when {
      chapterEndMs != null && chapterEndMs > progress.positionMs -> SleepTimer(progress.item.bookId, trackId = progress.item.trackId,
        chapterEndMs = chapterEndMs)
      minutes != null && minutes > 0 -> SleepTimer(progress.item.bookId,
        deadline = SystemClock.elapsedRealtime() + minutes * 60_000L, remainingSeconds = minutes * 60, durationMinutes = minutes)
      else -> null
    }
  }

  fun clearTimer() {
    _timer.value = null
    sleepStoppedTrack = null
  }

  fun setSpeed(speed: Float) {
    if (!speed.isFinite()) return
    val id = PlaybackSession.state.value.currentItem?.audiobook?.bookId ?: return
    val safeSpeed = speed.coerceIn(0.1f, 4f)
    scope.launch {
      dao.setSpeed(id, safeSpeed)
      if (PlaybackSession.state.value.currentItem?.audiobook?.bookId == id) PlaybackSession.setPropertyFloat("speed", safeSpeed)
    }
  }

  fun setRewind(seconds: Int) {
    val id = PlaybackSession.state.value.currentItem?.audiobook?.bookId ?: return
    scope.launch { dao.setRewind(id, seconds.coerceIn(0, 30)) }
  }

  suspend fun prepareQueue(bookId: Long, trackId: Long? = null): PreparedPlaybackLaunch {
    ensureStarted()
    val book = dao.getBook(bookId) ?: error("Audiobook not found")
    val ordered = book.orderedTracks
    require(ordered.isNotEmpty())
    val selected = trackId ?: book.book.currentTrackId?.takeUnless { book.book.finished }
    val index = ordered.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val items = ordered.map { track ->
      PlaybackItem.fromUri(track.uri, title = book.book.title, artist = book.book.author,
        mimeType = "audio/*", artworkUri = book.book.coverUri, durationSeconds = (track.durationMs / 1000).toInt())
        .copy(audiobook = AudiobookPlaybackInfo(bookId, track.id))
    }
    return PreparedPlaybackLaunch(token = 0, items = items, currentIndex = index, isExplicitQueue = true, isM3u = false)
  }

  suspend fun launch(context: Context, bookId: Long, trackId: Long? = null, positionMs: Long? = null, fromBeginning: Boolean = false) {
    ensureStarted()
    capture()
    flush()
    val firstTrack = if (fromBeginning) dao.getBook(bookId)?.orderedTracks?.firstOrNull()?.id else null
    val launch = prepareQueue(bookId, firstTrack ?: trackId)
    val current = launch.items[launch.currentIndex]
    withContext(Dispatchers.Main) {
      val token = PreparedPlaybackLaunchStore.stage(launch.items, launch.currentIndex)
      context.startActivity(Intent(context, PlayerActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(current.originalUri))
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra("internal_launch", true).putExtra("is_audio", true).putExtra("media_library_audio", true)
        .putExtra("title", launch.items[launch.currentIndex].title)
        .putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true).putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, token)
        .putExtra("playlist_index", launch.currentIndex).putExtra(EXTRA_BOOK_ID, bookId)
        .putExtra(EXTRA_TRACK_ID, current.audiobook?.trackId ?: -1L)
        .putExtra(EXTRA_POSITION_MS, if (fromBeginning) 0L else positionMs ?: -1L))
    }
  }

  suspend fun positionForLoad(item: PlaybackItem, intent: Intent): PlaybackPositionRestoreOverride? {
    val info = item.audiobook ?: return null
    val stored = dao.getBook(info.bookId) ?: return PlaybackPositionRestoreOverride(0.0, false)
    val book = stored.book
    val explicit = intent.getLongExtra(EXTRA_POSITION_MS, -1L).takeIf {
      it >= 0 && intent.getLongExtra(EXTRA_BOOK_ID, -1L) == info.bookId && intent.getLongExtra(EXTRA_TRACK_ID, -1L) == info.trackId
    }
    val saved = if (book.currentTrackId == info.trackId && !book.finished) book.positionMs else 0L
    val rewind = if (explicit == null && System.currentTimeMillis() - book.lastPlayedAt >= 5000) book.rewindSeconds * 1000L else 0L
    val maximum = stored.tracks.firstOrNull { it.id == info.trackId }?.durationMs?.minus(1)?.coerceAtLeast(0) ?: Long.MAX_VALUE
    return PlaybackPositionRestoreOverride(((explicit ?: saved) - rewind).coerceIn(0, maximum) / 1000.0, false)
  }

  suspend fun applyBookSettings(item: PlaybackItem, generation: Long) {
    val info = item.audiobook ?: return
    val book = dao.getBook(info.bookId)?.book ?: return
    if (PlaybackSession.isCurrentGeneration(generation)) {
      PlaybackSession.applyAudiobookSpeed(generation, book.playbackSpeed)
    }
  }
}