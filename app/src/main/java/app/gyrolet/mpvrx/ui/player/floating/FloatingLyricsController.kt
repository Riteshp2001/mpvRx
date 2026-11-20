/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.floating

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import app.gyrolet.mpvrx.App
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.lyrics.LyricsTranslationService
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsTiming
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.domain.thumbnail.getArtwork
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.repository.lyrics.LyricsRepository
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.components.AppToastType
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackQueueReducer
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.utils.media.LyricsUtils
import com.google.android.material.color.utilities.Score
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class FloatingLyricsModel(
  val generation: Long = 0L,
  val title: String = "",
  val artist: String = "",
  val artwork: Bitmap? = null,
  val artworkAccent: Int? = null,
  val lines: List<SyncedLine> = emptyList(),
  val plain: List<String> = emptyList(),
  val lineIndex: Int = -1,
  val offsetMs: Int = 0,
  val positionMs: Long = 0L,
  val positionUpdatedAt: Long = 0L,
  val speed: Float = 1f,
  val seekTick: Long = 0L,
  val durationMs: Long = 0L,
  val paused: Boolean = true,
  val canControl: Boolean = false,
  val canPrevious: Boolean = false,
  val canNext: Boolean = false,
  val canSeek: Boolean = false,
  val loading: Boolean = true,
  val errorMessage: String? = null,
)

internal class FloatingLyricsController(
  private val context: Context,
  private val preferences: AudioPreferences,
  private val playerPreferences: PlayerPreferences,
  private val lyricsRepository: LyricsRepository,
  private val translations: LyricsTranslationService,
  private val thumbnails: ThumbnailRepository,
  private val scope: CoroutineScope,
  private val isAudio: (PlaybackItem) -> Boolean,
  private val openPlayer: () -> Unit,
  private val togglePlay: () -> Unit,
  private val previous: () -> Unit,
  private val next: () -> Unit,
  private val seek: (Long) -> Unit,
) {
  private data class Track(val generation: Long, val item: PlaybackItem)
  private data class Clock(val positionMs: Long?, val durationMs: Long?, val seekable: Boolean, val speed: Float)
  private data class Metadata(val title: String, val artist: String, val duration: Int, val album: String?, val isrc: String?)

  private val dismissed = MutableStateFlow(false)
  private val screenAvailable = MutableStateFlow(false)
  private val playbackKindChanges = MutableStateFlow(0L)
  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile private var job: Job? = null
  @Volatile private var stopped = false
  private var window: FloatingLyricsWindow? = null
  private var receiverRegistered = false
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (!stopped) {
        val available = intent.action != Intent.ACTION_SCREEN_OFF && canShowOnScreen()
        screenAvailable.value = available
        if (!available) closeWindow()
      }
    }
  }

  fun onPlaybackKindChanged() {
    playbackKindChanges.update { it + 1L }
  }

  fun start() {
    if (stopped || job != null) return
    screenAvailable.value = canShowOnScreen()
    try {
      ContextCompat.registerReceiver(
        context,
        screenReceiver,
        IntentFilter().apply {
          addAction(Intent.ACTION_SCREEN_ON)
          addAction(Intent.ACTION_SCREEN_OFF)
          addAction(Intent.ACTION_USER_PRESENT)
        },
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
      receiverRegistered = true
    } catch (error: SecurityException) {
      unavailable(error)
      return
    } catch (error: IllegalArgumentException) {
      unavailable(error)
      return
    }
    val observerJob = scope.launch(start = CoroutineStart.LAZY) {
      try {
        coroutineScope {
          launch {
            App.foregroundState.collect { visible ->
              if (visible) {
                dismissed.value = false
                closeWindow()
              }
            }
          }
          val enabled = combine(
            preferences.floatingLyricsEnabled.changes(),
            preferences.audioBackgroundPlayback.changes(),
          ) { floating, background -> floating && background }
            .onEach { if (!it) closeWindow() }
          val playback = combine(PlaybackSession.state, playbackKindChanges) { state, _ -> state }
          combine(enabled, App.foregroundState, screenAvailable, dismissed, playback) { on, visible, screenOn, hidden, state ->
            val item = state.currentItem
            if (on && !visible && screenOn && !hidden && item != null && item.audiobook == null &&
              state.generation > 0L && isAudio(item) &&
              state.phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
            ) Track(state.generation, item) else null
          }.distinctUntilChanged().collectLatest { track ->
            if (track == null || App.isInForeground || !canShowOnScreen() ||
              !preferences.floatingLyricsEnabled.get() || !preferences.audioBackgroundPlayback.get() || !isCurrent(track)
            ) {
              closeWindow()
            } else if (!Settings.canDrawOverlays(context)) {
              permissionLost()
            } else {
              val pill = window ?: createWindow()?.also { window = it } ?: return@collectLatest
              pill.update { current ->
                FloatingLyricsModel(
                  generation = track.generation,
                  title = track.item.title.orEmpty().ifBlank { context.getString(R.string.app_name) },
                  artist = track.item.artist.orEmpty(),
                  artworkAccent = current.artworkAccent,
                  paused = PlaybackSession.state.value.paused,
                  durationMs = (track.item.durationSeconds?.toLong() ?: 0L).coerceAtLeast(0L) * 1_000,
                )
              }
              if (pill.show()) followTrack(track, pill)
            }
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: SecurityException) {
        unavailable(error)
      } catch (error: IllegalStateException) {
        unavailable(error)
      } catch (error: IllegalArgumentException) {
        unavailable(error)
      } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) { cleanup() }
      }
    }
    job = observerJob
    if (stopped || !observerJob.start()) {
      observerJob.cancel()
      cleanup()
    }
  }

  fun stop() {
    stopped = true
    job?.cancel()
    job = null
    if (Looper.myLooper() == Looper.getMainLooper()) cleanup() else mainHandler.post { cleanup() }
  }

  private fun cleanup() {
    closeWindow()
    if (receiverRegistered) {
      receiverRegistered = false
      try {
        context.unregisterReceiver(screenReceiver)
      } catch (error: IllegalArgumentException) {
        Log.w(TAG, "Floating lyrics screen receiver was already removed", error)
      } catch (error: SecurityException) {
        Log.w(TAG, "Could not unregister floating lyrics screen receiver", error)
      }
    }
  }

  private fun canShowOnScreen(): Boolean =
    context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
      context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false

  private fun createWindow(): FloatingLyricsWindow? =
    try {
      FloatingLyricsWindow(
        context = context,
        preferences = preferences,
        playerPreferences = playerPreferences,
        onDismiss = { if (!stopped) { dismissed.value = true; closeWindow() } },
        onDisable = { if (!stopped) { preferences.floatingLyricsEnabled.set(false); closeWindow() } },
        onOpenPlayer = { if (!stopped) openPlayer() },
        onTogglePlay = togglePlay,
        onPrevious = previous,
        onNext = next,
        onSeek = seek,
        onUnavailable = ::unavailable,
      )
    } catch (error: SecurityException) {
      unavailable(error)
      null
    } catch (error: WindowManager.InvalidDisplayException) {
      unavailable(error)
      null
    } catch (error: IllegalStateException) {
      unavailable(error)
      null
    } catch (error: IllegalArgumentException) {
      unavailable(error)
      null
    } catch (error: UnsupportedOperationException) {
      unavailable(error)
      null
    }

  private fun closeWindow() {
    val old = window
    window = null
    old?.remove()
  }

  private fun unavailable(error: RuntimeException) {
    Log.e(TAG, "Floating lyrics are unavailable; background playback is unaffected", error)
    val permissionDenied = !Settings.canDrawOverlays(context)
    preferences.floatingLyricsEnabled.set(false)
    stop()
    AppToast.makeText(
      context,
      if (permissionDenied) R.string.floating_lyrics_permission else R.string.floating_lyrics_unavailable,
      AppToast.LENGTH_LONG,
    ).withType(AppToastType.Error).show()
  }

  private fun permissionLost() {
    preferences.floatingLyricsEnabled.set(false)
    stop()
    AppToast.makeText(context, R.string.floating_lyrics_permission, AppToast.LENGTH_LONG)
      .withType(AppToastType.Warning).show()
  }

  private fun isCurrent(track: Track): Boolean =
    !stopped && PlaybackSession.isCurrentGeneration(track.generation) &&
      PlaybackSession.state.value.currentItem?.originalUri == track.item.originalUri

  private suspend fun followTrack(track: Track, pill: FloatingLyricsWindow): Nothing = coroutineScope {
    launch {
      try {
        val artwork = thumbnails.getArtwork(
          source = track.item.originalUri,
          artworkUri = track.item.artworkUri,
          isAudio = true,
          widthPx = 192,
        )
        val copy = withContext(Dispatchers.IO) {
          artwork?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
        }
        val accent = withContext(Dispatchers.Default) {
          copy?.let { bitmap ->
            val colors = Palette.from(bitmap).maximumColorCount(8).generate().swatches.associate { it.rgb to it.population }
            Score.score(colors).firstOrNull()
          }
        }
        if (isCurrent(track)) pill.update { it.copy(artwork = copy, artworkAccent = accent ?: it.artworkAccent) }
      } catch (error: IOException) {
        Log.w(TAG, "Could not load floating lyrics artwork", error)
      } catch (error: SecurityException) {
        Log.w(TAG, "Floating lyrics artwork is no longer accessible", error)
      } catch (error: IllegalStateException) {
        Log.w(TAG, "Floating lyrics artwork was released while loading", error)
      } catch (error: IllegalArgumentException) {
        Log.w(TAG, "Floating lyrics artwork could not be copied", error)
      }
    }
    launch {
      lyricsRepository.playbackLyrics.collectLatest { shared ->
        if (!isCurrent(track)) return@collectLatest
        val snapshot = shared?.takeIf { it.generation == track.generation && it.mediaPath == track.item.originalUri }
        if (snapshot != null && (snapshot.publisherActive || !snapshot.isLoading)) {
          applyLyrics(pill, snapshot.lyrics, snapshot.offsetMs, snapshot.isLoading, snapshot.errorMessage)
        } else {
          val offset = snapshot?.offsetMs ?: 0
          try {
            PlaybackSession.state.first {
              it.generation != track.generation || it.phase == PlaybackPhase.READY || it.phase == PlaybackPhase.BACKGROUND
            }
            if (!isCurrent(track)) return@collectLatest
            val metadata = withContext(Dispatchers.IO) {
              PlaybackSession.readLoadedMedia(track.generation) { properties ->
                Metadata(
                  title = track.item.title?.takeIf(String::isNotBlank)
                    ?: properties.string("metadata/by-key/Title")?.takeIf(String::isNotBlank)
                    ?: properties.string("metadata/by-key/title")?.takeIf(String::isNotBlank)
                    ?: properties.string("media-title").orEmpty(),
                  artist = track.item.artist?.takeIf(String::isNotBlank)
                    ?: properties.string("metadata/by-key/Artist")
                    ?: properties.string("metadata/by-key/artist")
                    ?: properties.string("metadata/by-key/ARTIST")
                    ?: properties.string("metadata/by-key/album_artist").orEmpty(),
                  duration = track.item.durationSeconds?.takeIf { it > 0 }
                    ?: properties.double("duration")?.takeIf { it.isFinite() && it > 0.0 }?.toInt() ?: 0,
                  album = properties.string("metadata/by-key/Album") ?: properties.string("metadata/by-key/album"),
                  isrc = properties.string("metadata/by-key/isrc"),
                )
              }
            } ?: return@collectLatest
            if (!isCurrent(track)) return@collectLatest
            pill.update {
              it.copy(title = metadata.title.ifBlank { it.title }, artist = metadata.artist, durationMs = metadata.duration.toLong() * 1_000)
            }
            val result = lyricsRepository.loadLyricsForTrack(
              mediaPath = track.item.originalUri,
              title = metadata.title,
              artist = metadata.artist,
              durationSeconds = metadata.duration,
              album = metadata.album,
              isrc = metadata.isrc,
            )
            if (!isCurrent(track)) return@collectLatest
            val lyrics = result.activeLyrics
            applyLyrics(pill, lyrics, offset)
            if (!BuildConfig.IS_PLAY_STORE_BUILD && preferences.lyricsAutoTranslate.get() &&
              lyrics != null && lyrics.synced?.isNotEmpty() == true
            ) {
              val translated = translations.translateLyrics(
                lyrics,
                preferences.lyricsTargetLanguage.get().ifBlank { "en" },
                cacheKey = track.item.originalUri,
              )
              if (!translated.isSuccessful) {
                Log.w(TAG, "No floating lyrics lines could be translated")
              } else if (isCurrent(track)) {
                applyLyrics(pill, translated.lyrics, offset)
              }
            }
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (error: IOException) {
            Log.e(TAG, "Could not load floating lyrics", error)
            if (isCurrent(track)) applyLyrics(pill, null, offset, error = context.getString(R.string.lyrics_editor_load_failed))
          } catch (error: SecurityException) {
            Log.e(TAG, "Floating lyrics source is no longer accessible", error)
            if (isCurrent(track)) applyLyrics(pill, null, offset, error = context.getString(R.string.lyrics_editor_load_failed))
          }
        }
      }
    }
    launch {
      var permissionTicks = 0
      while (isActive && isCurrent(track)) {
        if (++permissionTicks >= 12) {
          permissionTicks = 0
          if (!Settings.canDrawOverlays(context)) {
            permissionLost()
            return@launch
          }
          if (!canShowOnScreen()) {
            screenAvailable.value = false
            closeWindow()
            return@launch
          }
        }
        val clock = withContext(Dispatchers.IO) {
          PlaybackSession.readLoadedMedia(track.generation) { properties ->
            Clock(
              properties.double("time-pos")?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1_000).toLong() },
              properties.double("duration")?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1_000).toLong() },
              properties.string("seekable") == "yes",
              properties.double("speed")?.takeIf { it.isFinite() && it > 0.0 }?.toFloat() ?: 1f,
            )
          }
        }
        if (isCurrent(track)) {
          val session = PlaybackSession.state.value
          val ready = session.phase == PlaybackPhase.READY || session.phase == PlaybackPhase.BACKGROUND
          val queue = PlaybackSession.queue.value
          pill.update { current ->
            val position = clock?.positionMs ?: current.positionMs
            val now = SystemClock.uptimeMillis()
            val predicted = current.positionMs + if (!current.paused && current.positionUpdatedAt > 0L) {
              ((now - current.positionUpdatedAt).coerceAtLeast(0L) * current.speed).toLong()
            } else 0L
            val jumped = clock?.positionMs != null && current.positionUpdatedAt > 0L && kotlin.math.abs(position - predicted) > 500L
            current.copy(
              positionMs = position,
              positionUpdatedAt = if (clock?.positionMs != null) now else current.positionUpdatedAt,
              speed = clock?.speed ?: current.speed,
              seekTick = current.seekTick + if (jumped) 1L else 0L,
              durationMs = clock?.durationMs ?: current.durationMs,
              canControl = ready,
              canSeek = ready && clock?.seekable == true,
              paused = session.paused,
              canPrevious = ready && PlaybackQueueReducer.peekPrevious(queue) != null,
              canNext = ready && PlaybackQueueReducer.peekNext(queue) != null,
              lineIndex = LyricsUtils.getActiveLineIndex(current.lines, position, current.offsetMs),
            )
          }
        }
        delay(if (PlaybackSession.state.value.paused) 250L else 80L)
      }
    }
    awaitCancellation()
  }

  private fun applyLyrics(
    pill: FloatingLyricsWindow,
    lyrics: Lyrics?,
    offsetMs: Int,
    loading: Boolean = false,
    error: String? = null,
  ) {
    val lines = lyrics?.synced.orEmpty().takeIf { lyrics?.timing() != LyricsTiming.PLAIN }.orEmpty()
    val plain = lyrics?.plain.orEmpty().ifEmpty {
      if (lines.isEmpty()) lyrics?.synced.orEmpty().map { it.line } else emptyList()
    }
    pill.update {
      it.copy(
        lines = lines,
        plain = plain,
        offsetMs = offsetMs,
        loading = loading,
        errorMessage = error,
        lineIndex = LyricsUtils.getActiveLineIndex(lines, it.positionMs, offsetMs),
      )
    }
  }

  private companion object {
    const val TAG = "FloatingLyrics"
  }
}
