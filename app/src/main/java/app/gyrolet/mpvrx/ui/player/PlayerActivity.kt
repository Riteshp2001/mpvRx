package app.gyrolet.mpvrx.ui.player

import android.Manifest
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.entities.PlaylistItemEntity
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.databinding.PlayerLayoutBinding
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.VideoSortType
import app.gyrolet.mpvrx.ui.browser.playlist.ALL_VIDEOS_PLAYLIST_ID
import app.gyrolet.mpvrx.ui.browser.playlist.buildAllVideosPlaylistEntity
import app.gyrolet.mpvrx.ui.browser.playlist.isAllVideosPlaylist
import app.gyrolet.mpvrx.ui.cast.CastMediaSnapshot
import app.gyrolet.mpvrx.ui.cast.CastPlaybackController
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.controls.PlayerControls
import app.gyrolet.mpvrx.ui.player.visualizer.BlobOverlay
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.HttpUtils
import app.gyrolet.mpvrx.utils.media.JellyfinSessionReporter
import app.gyrolet.mpvrx.utils.media.listTreeFilesSafely
import app.gyrolet.mpvrx.utils.media.openPersistedTreeDocument
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.SubtitleOps
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import com.github.k1rakishou.fsaf.FileManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.media.M3UParseResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

private enum class BackgroundPlaybackStartResult {
  Started,
  PendingPermission,
  Blocked,
}

private enum class PlaybackCompatibilityFallback {
  LEGACY_GPU,
  MEDIACODEC_COPY,
  SOFTWARE_DECODING,
}

private data class PlaybackWatchdogSnapshot(
  val position: Double?,
  val paused: Boolean,
  val buffering: Boolean,
  val seeking: Boolean,
)

/**
 * Main player activity that handles video playback using the MPV library.
 *
 * This activity manages:
 * - Video playback using MPV library
 * - System UI visibility (immersive mode)
 * - Audio focus management
 * - Picture-in-Picture (PiP) mode
 * - Background playback service
 * - MediaSession for external controls (Android Auto, Bluetooth, etc.)
 * - Playback state persistence and restoration
 * - Subtitle and audio track management
 * - Hardware key event handling
 *
 * @see PlayerViewModel for UI state management
 * @see MediaPlaybackService for background playback functionality
 */
@Suppress("TooManyFunctions", "LargeClass")
class PlayerActivity :
  AppCompatActivity(),
  PlayerHost {
  // ==================== ViewModels and Bindings ====================

  /**
   * View model for managing player UI state.
   */
  private val viewModel: PlayerViewModel by viewModels<PlayerViewModel> {
    PlayerViewModelProviderFactory(this)
  }

  /**
   * Binding for the player layout.
   */
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }

  /**
   * Observer for MPV events.
   */
  private lateinit var playerObserver: PlayerObserver
  private val activityInstanceId = nextActivityInstanceId.incrementAndGet()
  @Volatile private var playerSessionId = 0L

  // ==================== Dependency Injection ====================

  /**
   * Repository for managing playback state.
   */
  private val playbackStateRepository: PlaybackStateRepository by inject()

  /**
   * Repository for managing playlists.
   */
  private val playlistRepository: app.gyrolet.mpvrx.database.repository.PlaylistRepository by inject()

  /**
   * Preferences for player settings.
   */
  private val playerPreferences: PlayerPreferences by inject()

  /**
   * Preferences for audio settings.
   */
  private val audioPreferences: AudioPreferences by inject()

  /**
   * Preferences for subtitle settings.
   */
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  /**
   * Preferences for decoder and renderer settings.
   */
  private val decoderPreferences: DecoderPreferences by inject()

  /**
   * Preferences for advanced settings.
   */
  private val advancedPreferences: AdvancedPreferences by inject()

  /**
   * Preferences for browser settings.
   */
  private val browserPreferences: BrowserPreferences by inject()

  /**
   * Preferences for appearance settings.
   */
  private val appearancePreferences: AppearancePreferences by inject()

  /**
   * Manager for file operations.
   */
  private val fileManager: FileManager by inject()

  /**
   * Track selector for automatic audio/subtitle selection
   */
  private val trackSelector: TrackSelector by lazy {
    TrackSelector(audioPreferences, subtitlesPreferences)
  }

  // ==================== Views ====================

  /**
   * The MPV player view.
   */
  val player by lazy { binding.player }

  override fun currentThumbnailSource(): String? = currentPlayableUri

  override fun isCurrentMediaKnownAudio(): Boolean {
    val extension =
      sequenceOf(fileName, currentPlayableUri)
        .filterNotNull()
        .map { value -> value.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase() }
        .firstOrNull { it in FileTypeUtils.AUDIO_EXTENSIONS || it in FileTypeUtils.VIDEO_EXTENSIONS }
    if (extension != null) return extension in FileTypeUtils.AUDIO_EXTENSIONS
    return isKnownAudioLaunch(intent)
  }

  // ==================== State Management ====================

  /**
   * Current video file name being played.
   */
  private var fileName by mutableStateOf("")

  /**
   * Unique identifier for the current media, used for saving/loading playback state.
   * For network streams, this includes a hash of the URI to ensure uniqueness.
   */
  private var mediaIdentifier = ""
  private var pendingBackgroundPlaybackStart = false

  /**
   * Playlist of URIs for sequential playback
   */
  internal var playlist: List<Uri> = emptyList()

  /**
   * Database metadata for playlist items, if the current playlist was loaded from Room.
   */
  private var playlistItems: List<PlaylistItemEntity> = emptyList()

  /**
   * Original network metadata for intent-backed WebDAV/SMB/FTP playlists.
   */
  private var networkPlaylistPaths: List<String> = emptyList()
  private var networkPlaylistTitles: List<String> = emptyList()
  private var networkPlaylistConnectionId: Long = -1L

  /**
   * Playlist metadata for the current Room-backed playlist.
   */
  private var playlistEntity: PlaylistEntity? = null

  /**
   * Current index in the playlist
   */
  internal var playlistIndex: Int = 0

  /**
   * Shuffled order of playlist indices (when shuffle is enabled)
   */
  private var shuffledIndices: List<Int> = emptyList()

  /**
   * Current position in shuffled playlist (when shuffle is enabled)
   */
  private var shuffledPosition: Int = 0

  /**
   * Playlist ID for tracking play history (optional, only for custom playlists)
   */
  private var playlistId: Int? = null

  /**
   * Tracks the starting offset of the loaded playlist window in the full playlist.
   * Used for windowed loading to prevent ANR with large playlists.
   */
  private var playlistWindowOffset: Int = 0

  /**
   * Total count of items in the full playlist (when using windowed loading).
   * -1 means unknown or not using windowed loading.
   */
  var playlistTotalCount: Int = -1
    private set

  /**
   * Indicates whether the current playlist is an M3U playlist sourced from database.
   * Used to skip thumbnail/metadata extraction for network streams.
   */
  private var isM3uPlaylist: Boolean = false

  /**
   * Helper for managing Picture-in-Picture mode.
   */
  private lateinit var pipHelper: MPVPipHelper
  private lateinit var castPlaybackController: CastPlaybackController

  private var isReady = false // Single flag: true when video loaded and ready
  private var isUserFinishing = false
  private var isBackgroundPlaybackSessionActive = false
  private var wasInPipMode = false
  private var handledPipDismissal = false
  private var pendingBackgroundTransition = false
  private var pendingBackNavigationBackgroundTransition = false
  private var noisyReceiverRegistered = false
  private var lastVid = -1 // Track video track for background playback optimization
  private var isInBackgroundPlayback = false // Track if we are currently in background playback mode
  private var screenStateReceiverRegistered = false
  @Volatile private var mpvInitialized = false // Track MPV initialization state
  private var compatibilityFallbackIndex = 0
  private var lastVideoAspect: Double? = null
  @Volatile private var pendingConfigWarnings: List<String> = emptyList()
  private var savePlaybackStateJob: Job? = null // Track ongoing save job
  private var wasPlayingBeforePause = false // Track if video was playing before pause
  private var resumeAfterUnlockJob: Job? = null
  private var jellyfinSessionReporter: JellyfinSessionReporter? = null
  private var jellyfinProgressJob: Job? = null
  private val screenUnlockPlaybackController = ScreenUnlockPlaybackController()
  private var backgroundServiceSyncJob: Job? = null
  private var deferredFontSyncJob: Job? = null
  private var systemBarsAutoHideJob: Job? = null
  private var videoParamRefreshJob: Job? = null
  private var intentSubtitleJob: Job? = null
  private var mediaLoadJob: Job? = null
  private var eofAdvanceJob: Job? = null
  private var playbackWatchdogJob: Job? = null
  @Volatile private var isAdvancingAtEof = false
  @Volatile private var playWhenFileLoaded = false
  private var pendingVideoParamRefreshRequiresShaderReload = false
  private var lastBackgroundThumbnailKey: String? = null
  private var lastBackgroundThumbnail: Bitmap? = null
  private var currentPlayableUri: String? = null // Store current URI for notification re-entry
  private val playbackRenderDispatcher = Dispatchers.Main
  private val mediaLoadDispatcher = Dispatchers.Default.limitedParallelism(1)

  // ==================== Background Playback ====================

  /**
   * Reference to the background playback service.
   */
  private var mediaPlaybackService: MediaPlaybackService? = null

  /**
   * Tracks whether we're currently bound to the background playback service.
   */
  private var serviceBound = false

  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      val wasEnablingFromPlayerControls = pendingBackgroundTransition
      if (granted) {
        pendingBackgroundPlaybackStart = false
        val started = startBackgroundPlaybackInternal(bindToActivity = false)
        if (pendingBackgroundTransition && started) {
          pendingBackgroundTransition = false
          isBackgroundPlaybackSessionActive = true
          viewModel.showToast("Background playback on")
        } else if (pendingBackNavigationBackgroundTransition && started) {
          pendingBackNavigationBackgroundTransition = false
          finishIntoBackgroundPlayback()
        } else if (!started) {
          if (wasEnablingFromPlayerControls) {
            audioPreferences.backgroundPlayback.set(false)
          }
          pendingBackgroundTransition = false
          pendingBackNavigationBackgroundTransition = false
          isBackgroundPlaybackSessionActive = false
        }
      } else {
        if (wasEnablingFromPlayerControls) {
          audioPreferences.backgroundPlayback.set(false)
        }
        pendingBackgroundPlaybackStart = false
        pendingBackgroundTransition = false
        pendingBackNavigationBackgroundTransition = false
        isBackgroundPlaybackSessionActive = false
        Toast.makeText(
          this,
          getString(R.string.notification_permission_denied),
          Toast.LENGTH_LONG,
        ).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
          openNotificationSettings()
        }
      }
    }

  // ==================== MediaSession ====================

  /**
   * MediaSession for integration with system media controls, Android Auto, and Wear OS.
   */
  private lateinit var mediaSession: MediaSession

  /**
   * Tracks whether MediaSession has been successfully initialized.
   */
  private var mediaSessionInitialized = false

  /**
   * Builder for MediaSession playback states.
   */
  private lateinit var playbackStateBuilder: PlaybackState.Builder

  // ==================== Audio Focus ====================

  /**
   * Audio focus request for API 26+.
   */
  private var audioFocusRequest: AudioFocusRequest? = null

  /**
   * Callback to restore audio focus after it's been lost and regained.
   */
  private var restoreAudioFocus: () -> Unit = {}

  // ==================== Broadcast Receivers ====================

  /**
   * Receiver for handling noisy audio events.
   */
  private val noisyReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
          viewModel.pause()
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      }
    }

  private val screenStateReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        when (intent?.action) {
          Intent.ACTION_SCREEN_OFF -> {
            screenUnlockPlaybackController.onScreenTurnedOff(
              autoplayAfterScreenUnlockEnabled = playerPreferences.autoplayAfterScreenUnlock.get(),
              wasPlayingBeforePause = wasPlayingBeforePause,
              isCurrentlyPaused = viewModel.paused,
              backgroundPlaybackActive = isBackgroundPlaybackEnabled(),
              isUserFinishing = isUserFinishing,
              isFinishing = isFinishing,
            )
          }
          Intent.ACTION_USER_PRESENT -> {
            resumePlaybackAfterScreenUnlockIfNeeded()
          }
          Intent.ACTION_SCREEN_ON -> resumePlaybackAfterScreenUnlockIfNeeded()
        }
      }
    }

  /**
   * Listener for audio focus changes.
   */
  private val audioFocusChangeListener =
    AudioManager.OnAudioFocusChangeListener { focusChange ->
      when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
          -> {
          // Save current state to restore later
          val oldRestore = restoreAudioFocus
          val wasPlayerPaused = viewModel.paused ?: false
          viewModel.pause()
          restoreAudioFocus = {
            oldRestore()
            if (!wasPlayerPaused) viewModel.unpause()
          }
        }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          // Lower volume temporarily
          MpvSessionCoordinator.execute(playerSessionId, "audio_duck") {
            MPVLib.commandResult("multiply", "volume", "0.5")
          }
          restoreAudioFocus = {
            MpvSessionCoordinator.execute(playerSessionId, "audio_unduck") {
              MPVLib.commandResult("multiply", "volume", "2")
            }
          }
        }

        AudioManager.AUDIOFOCUS_GAIN -> {
          // Restore previous audio state
          restoreAudioFocus()
          restoreAudioFocus = {}
        }

        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
          Log.d(TAG, "Audio focus request failed")
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContentView(binding.root)
    setupSystemBarsAutoHide()

    val isNotificationReentry = isNotificationReentryIntent(intent)
    if (!isNotificationReentry) {
      releaseDetachedBackgroundPlaybackBeforeFreshLaunch()
    }
    setupMPV(reuseDetachedSession = isNotificationReentry)
    MediaPlaybackService.createNotificationChannel(this)
    setupBackPressHandler()
    setupPipHelper()
    setupMediaSession()
    // Note: screenStateReceiver is now registered in onStart() and
    // unregistered in onStop(), matching the noisyReceiver pattern.
    // Previously it was registered here in onCreate and stayed registered
    // for the entire Activity lifetime — including while paused/stopped —
    // which wasted battery (every ACTION_SCREEN_OFF / ACTION_USER_PRESENT
    // woke the Activity) and risked leaking the receiver if onDestroy was
    // skipped. See issue 2.3 in the leak audit.

    playlistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
    playlistIndex = intent.getIntExtra("playlist_index", 0)
    loadNetworkPlaylistMetadata(intent)

    // Load playlist from intent extras first (fast path - backward compatibility)
    playlist = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    if (playlist.isNotEmpty()) {
      playlistIndex = playlistIndex.coerceIn(0, playlist.lastIndex)
      playlistWindowOffset = 0
      playlistTotalCount = playlist.size
      viewModel.refreshPlaylistItems()
    }

    // If playlist is empty but playlist_id is provided, load asynchronously from database
    // Load all items - LazyColumn handles pagination/virtualization efficiently
    if (playlist.isEmpty() && playlistId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          loadPlaylistById(
            pid = pid,
            sourceIntent = intent,
            logPrefix = "Loaded",
            reapplyShuffle = true,
          )
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load playlist from database", e)
        }
      }
    }

    // Only auto-generate playlist from folder if playlist mode is enabled and no playlist_id
    if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
      val path = parsePathFromIntent(intent)
      if (path != null) {
        generatePlaylistFromFolder(path)
      }
    }

    // Extract fileName early so it's available when video loads
    fileName = getFileName(intent)
    if (fileName.isBlank()) {
      fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    }
    mediaIdentifier = getMediaIdentifier(intent, fileName)

    // Set HTTP headers (including referer) BEFORE playing the file
    setHttpHeadersFromExtras(intent.extras)

    getPlayableUri(intent)?.let { playableUri ->
      // Remind user if they forgot to set up yt-dlp
      if (playableUri.startsWith("http") && !playableUri.substringAfterLast('/').contains('.')) {
        val ytdlDir = YtdlpManager.getYtdlDir(this)
        if (!File(ytdlDir, "yt-dlp").exists()) {
          viewModel.showToast(getString(R.string.toast_need_ytdl))
        }
      }

      currentPlayableUri = playableUri
      if (isNotificationReentry) {
        isReady = true
        viewModel.onVideoLoadCompleted()
        endBackgroundPlayback()
      } else {
        isReady = false
        viewModel.onVideoLoadStarted()
        val originalUri = extractUriFromIntent(intent)
        val shouldExpandM3u = M3uPlaybackPolicy.shouldExpandInApp(
          playableUri = playableUri,
          originalUri = originalUri?.toString(),
          fileName = fileName,
          mimeType = intent.type,
          hasExistingPlaylist = playlist.isNotEmpty(),
          hasPlaylistId = playlistId != null,
        )
        if (shouldExpandM3u) {
          startMediaLoad(playableUri, originalUri?.toString(), expandM3u = true)
        } else {
          startMediaLoad(playableUri)
        }
      }
    }
    setupCastPlayback()

    // Only set orientation immediately if NOT in Video mode
    // For Video mode, wait for video-params/aspect to become available
    if (isKnownAudioLaunch(intent) || playerPreferences.orientation.get() != PlayerOrientation.Video) {
      setOrientation()
    }

    // Apply persisted shuffle state after playlist is loaded
    viewModel.applyPersistedShuffleState()

    // Observe selected Lua scripts for runtime loading
    lifecycleScope.launch {
      var previousScripts = advancedPreferences.selectedLuaScripts.get()
      advancedPreferences.selectedLuaScripts.changes().collect { newScripts ->
        if (!advancedPreferences.enableLuaScripts.get()) {
          previousScripts = newScripts
          return@collect
        }
        val addedScripts = newScripts - previousScripts
        addedScripts.forEach { scriptName ->
          loadScriptAtRuntime(scriptName)
        }
        previousScripts = newScripts
      }
    }

    lifecycleScope.launch {
      advancedPreferences.enableLuaScripts.changes().drop(1).collect { enabled ->
        if (enabled) {
          advancedPreferences.selectedLuaScripts.get().forEach { scriptName ->
            loadScriptAtRuntime(scriptName)
          }
          if (advancedPreferences.selectedLuaScripts.get().isEmpty()) {
            viewModel.showToast("Scripts enabled")
          }
        } else {
          viewModel.showToast("Scripts disabled. Reopen the video if a script stays active.")
        }
      }
    }

    lifecycleScope.launch {
      viewModel.chapters
        .map { chapters -> chapters.map { ChapterNode(time = it.start, title = it.name) } }
        .distinctUntilChanged()
        .collect { chapterNodes ->
        mediaPlaybackService?.setChapters(
          chapterNodes,
        )
      }
    }

    setLayoutInDisplayCutoutModeIfSupported(shortEdges = true)
  }

  override fun attachBaseContext(newBase: Context?) {
    if (newBase == null) {
      super.attachBaseContext(null)
      return
    }

    val originalConfiguration = newBase.resources.configuration
    val contextToUse =
      if (originalConfiguration.fontScale == 1f) {
        newBase
      } else {
        val updatedConfiguration = Configuration(originalConfiguration).apply { fontScale = 1f }
        val configurationContext = newBase.createConfigurationContext(updatedConfiguration)
        configurationContext
      }

    super.attachBaseContext(contextToUse)
  }

  private fun setupBackPressHandler() {
    val callback =
      object : OnBackPressedCallback(shouldInterceptBackPress()) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
          applyPredictiveBackProgress(backEvent)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
          applyPredictiveBackProgress(backEvent)
        }

        override fun handleOnBackCancelled() {
          resetPredictiveBackProgress()
        }

        override fun handleOnBackPressed() {
          // Do not let the predictive-back transform compete with Android's
          // full-screen-to-PiP surface morph.
          resetPredictiveBackProgress(animate = false)
          handleBackPress()
        }
      }

    onBackPressedDispatcher.addCallback(
      this,
      callback,
    )

    lifecycleScope.launch {
      combine(
        viewModel.sheetShown,
        viewModel.panelShown,
        playerPreferences.autoPiPOnNavigation.changes(),
        audioPreferences.backgroundPlayback.changes(),
      ) { sheetShown, panelShown, autoPipOnNavigation, backgroundPlaybackEnabled ->
        sheetShown != Sheets.None ||
          panelShown != Panels.None ||
          autoPipOnNavigation ||
          backgroundPlaybackEnabled
      }
        .distinctUntilChanged()
        .collect { callback.isEnabled = it }
    }
  }

  private fun shouldInterceptBackPress(): Boolean =
    viewModel.sheetShown.value != Sheets.None ||
      viewModel.panelShown.value != Panels.None ||
      playerPreferences.autoPiPOnNavigation.get() ||
      isBackgroundPlaybackEnabled()

  private fun applyPredictiveBackProgress(backEvent: BackEventCompat) {
    val root = binding.root
    val width = root.width
    val height = root.height
    if (width == 0 || height == 0) return

    val progress = backEvent.progress.coerceIn(0f, 1f)
    val fromRightEdge = backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT
    val direction = if (fromRightEdge) -1f else 1f
    val scale = 1f - (0.045f * progress)

    root.animate().cancel()
    binding.controls.animate().cancel()
    root.pivotX = if (fromRightEdge) width.toFloat() else 0f
    root.pivotY = backEvent.touchY.coerceIn(0f, height.toFloat())
    root.scaleX = scale
    root.scaleY = scale
    root.translationX = direction * width * 0.04f * progress
    binding.controls.alpha = 1f - (0.2f * progress)
  }

  private fun resetPredictiveBackProgress(animate: Boolean = true) {
    binding.root.animate().cancel()
    binding.controls.animate().cancel()
    if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
      binding.root.scaleX = 1f
      binding.root.scaleY = 1f
      binding.root.translationX = 0f
      binding.controls.alpha = 1f
      return
    }

    binding.root.animate()
      .scaleX(1f)
      .scaleY(1f)
      .translationX(0f)
      .setDuration(140L)
      .start()
    binding.controls.animate()
      .alpha(1f)
      .setDuration(140L)
      .start()
  }

  private fun handleBackPress() {
    // Dismiss overlays first
    if (viewModel.sheetShown.value != Sheets.None) {
      viewModel.sheetShown.update { Sheets.None }
      viewModel.showControls()
      return
    }

    if (viewModel.panelShown.value != Panels.None) {
      viewModel.panelShown.update { Panels.None }
      viewModel.showControls()
      return
    }

    // Background playback takes precedence on Back: return to the browser while
    // handing the live MPV session to the foreground service.
    if (
      PlayerLifecyclePolicy.shouldStartBackgroundPlaybackOnBack(
        backgroundPlaybackEnabled = isBackgroundPlaybackEnabled(),
        mediaReady = isReady,
      )
    ) {
      when (startBackgroundPlayback()) {
        BackgroundPlaybackStartResult.Started -> finishIntoBackgroundPlayback()
        BackgroundPlaybackStartResult.PendingPermission -> {
          pendingBackNavigationBackgroundTransition = true
        }
        BackgroundPlaybackStartResult.Blocked -> {
          isUserFinishing = true
          finish()
        }
      }
      return
    }

    // Check if auto PIP is enabled - enter PIP mode instead of finishing
    if (playerPreferences.autoPiPOnNavigation.get() && isReady) {
      enterPipModeSmoothly()
      return
    }

    isUserFinishing = true
    finish()
  }

  private fun setupPlayerControls() {
    binding.controls.setContent {
      MpvrxTheme {
        val isAudioOnly by viewModel.isAudioOnly.collectAsState()
        val audioBlobEnabled by audioPreferences.audioBlobEnabled.collectAsState()
        val paused by MPVLib.propBoolean["pause"].collectAsState()
        Box(modifier = Modifier.fillMaxSize()) {
          if (isAudioOnly && isCurrentMediaKnownAudio() && audioBlobEnabled) {
            BlobOverlay(isPlaying = paused == false)
          }
            PlayerControls(
              viewModel = viewModel,
            onBackPress = ::handleBackPress,
            modifier = Modifier,
          )
        }
      }
    }
  }

  /**
   * Initializes the Picture-in-Picture helper.
   */
  private fun setupPipHelper() {
    pipHelper = MPVPipHelper(activity = this, mpvView = player)
  }

  private fun setupCastPlayback() {
    castPlaybackController =
      CastPlaybackController(
        activity = this,
        currentMedia = ::currentCastMediaSnapshot,
        pauseLocal = viewModel::pause,
        restoreLocal = { positionMs, play ->
          if (!isFinishing && !isDestroyed) {
            viewModel.seekTo((positionMs / 1000L).toInt().coerceAtLeast(0))
            if (play) viewModel.unpause() else viewModel.pause()
          }
        },
        notifyUser = viewModel::showToast,
      )
    castPlaybackController.start()
  }

  private fun currentCastMediaSnapshot(): CastMediaSnapshot? {
    if (!isReady || fileName.isBlank()) return null
    val source =
      sequenceOf(
        currentPlayableUri,
        runCatching { MPVLib.getPropertyString("path") }.getOrNull(),
        intent?.dataString,
      ).filterNotNull()
        .filter { it.isNotBlank() }
        .map { sourceText ->
          val parsed = Uri.parse(sourceText)
          if (parsed.scheme.isNullOrBlank()) Uri.fromFile(File(sourceText)) else parsed
        }.firstOrNull { uri ->
          when (uri.scheme?.lowercase()) {
            "content", "file" -> true
            "http", "https" -> uri.host !in setOf("127.0.0.1", "localhost", "0.0.0.0")
            else -> false
          }
        } ?: return null
    return CastMediaSnapshot(
      source = source,
      title = getPreferredCurrentTitle().ifBlank { fileName },
      mimeType = intent?.type ?: runCatching { contentResolver.getType(source) }.getOrNull(),
      durationMs = ((MPVLib.getPropertyDouble("duration") ?: 0.0) * 1000.0).toLong(),
      positionMs = ((MPVLib.getPropertyDouble("time-pos") ?: 0.0) * 1000.0).toLong(),
      isPlaying = MPVLib.getPropertyBoolean("pause") == false,
    )
  }

  private fun setupAudio() {
    audioPreferences.audioChannels.get().let {
      runCatching {
        MPVLib.setPropertyString(it.property, it.value)
      }.onFailure { e ->
        Log.e(TAG, "Error setting audio channels: ${it.property}=${it.value}", e)
      }
    }

    if (!serviceBound) {
      audioFocusRequest =
        AudioFocusRequest
          .Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(
            AudioAttributes
              .Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
              .build(),
          ).setOnAudioFocusChangeListener(audioFocusChangeListener)
          .setAcceptsDelayedFocusGain(true)
          .setWillPauseWhenDucked(true)
          .build()
      requestAudioFocus()
    }
  }

  /**
   * @return true if audio focus was granted immediately, false otherwise
   */
  override fun requestAudioFocus(): Boolean {
    val req = audioFocusRequest ?: return false
    val result = audioManager.requestAudioFocus(req)
    return when (result) {
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
        restoreAudioFocus = {}
        true
      }

      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
        restoreAudioFocus = { requestAudioFocus() }
        false
      }

      else -> {
        restoreAudioFocus = {}
        false
      }
    }
  }

  override fun currentMediaLookupHint(): String? = currentPlayableUri ?: intent?.dataString

  override fun currentPlayerLookupHints(): PlayerLookupHints =
    PlayerLookupHints(
      canonicalTitle = intent?.getStringExtra("introdb_title"),
      imdbId = intent?.getStringExtra("introdb_imdb_id"),
      tmdbId =
        intent
          ?.getIntExtra("introdb_tmdb_id", -1)
          ?.takeIf { it > 0 },
      mediaType = intent?.getStringExtra("introdb_media_type"),
      season =
        intent
          ?.getIntExtra("introdb_season", -1)
          ?.takeIf { it > 0 },
      episode =
        intent
          ?.getIntExtra("introdb_episode", -1)
          ?.takeIf { it > 0 },
    )

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    // Enter PIP mode when user presses home button if auto PIP is enabled
    if (playerPreferences.autoPiPOnNavigation.get() && isReady && !isFinishing) {
      enterPipModeSmoothly()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus) {
      cancelSystemBarsAutoHide()
      return
    }

    if (shouldAutoHideSystemBars()) {
      scheduleSystemBarsAutoHide(delayMs = 250L)
    }
  }

  override fun onDestroy() {
    Log.d(TAG, "PlayerActivity onDestroy")
    val keepBackgroundPlaybackAlive =
      PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(
        backgroundPlaybackEnabled = isBackgroundPlaybackEnabled(),
        backgroundPlaybackSessionActive = isBackgroundPlaybackSessionActive,
      )

    runCatching {
      if (::castPlaybackController.isInitialized) castPlaybackController.release()
      cancelSystemBarsAutoHide()
      saveVideoPlaybackState(fileName, immediate = true)
      if (!keepBackgroundPlaybackAlive) {
        reportJellyfinStop()
      }

      if ((isUserFinishing || isFinishing) && !keepBackgroundPlaybackAlive) {
        if (serviceBound) {
          runCatching { unbindService(serviceConnection) }
          serviceBound = false
        }
        stopService(Intent(this, MediaPlaybackService::class.java))
        mediaPlaybackService = null
      } else if (keepBackgroundPlaybackAlive && serviceBound) {
        // Unbind but keep the service running for background audio
        runCatching { unbindService(serviceConnection) }
        serviceBound = false
        mediaPlaybackService = null
      }

      closePlayerAsync("activity_destroyed", keepBackgroundPlaybackAlive)
      if (!keepBackgroundPlaybackAlive) {
        cleanupAudio()
      }
      cleanupReceivers()
      releaseMediaSession()
    }.onFailure { e ->
      Log.e(TAG, "Error during onDestroy", e)
    }

    super.onDestroy()
  }

  private fun closePlayerAsync(reason: String, keepBackgroundPlaybackAlive: Boolean = false) {
    val sessionId = playerSessionId
    if (sessionId == 0L) return
    player.isExiting = true
    mpvInitialized = false
    player.onSurfaceReady = null
    // Prevent SurfaceView destruction from invoking libmpv on the main thread.
    runCatching { player.holder.removeCallback(player) }
    intentSubtitleJob?.cancel()
    videoParamRefreshJob?.cancel()
    backgroundServiceSyncJob?.cancel()
    deferredFontSyncJob?.cancel()
    mediaLoadJob?.cancel()
    eofAdvanceJob?.cancel()
    playbackWatchdogJob?.cancel()
    viewModel.onMpvCoreStopping()

    if (::playerObserver.isInitialized) {
      runCatching { MPVLib.removeObserver(playerObserver) }
        .onFailure { e -> Log.e(TAG, "Error removing MPV observer", e) }
      runCatching { MPVLib.removeLogObserver(playerObserver) }
        .onFailure { e -> Log.e(TAG, "Error removing MPV log observer", e) }
    }

    if (!keepBackgroundPlaybackAlive) {
      endBackgroundPlayback()
    }

    MpvSessionCoordinator.closeAsync(
      sessionId = sessionId,
      reason = reason,
      keepNativeSession = keepBackgroundPlaybackAlive,
      operation = {
        if (!keepBackgroundPlaybackAlive) {
          MPVLib.detachSurface()
          if (isReady) {
            MPVLib.setPropertyBooleanResult("pause", true)
            MPVLib.commandResult("quit")
          }
          player.destroy()
        } else {
          MPVLib.setPropertyStringResult("vo", "null")
          MPVLib.detachSurface()
        }
      },
      onClosed = {
        PlayerDiagnostics.logMemory(applicationContext, sessionId, "teardown_complete")
      },
    )
  }

  override fun abandonAudioFocus() {
    if (restoreAudioFocus != {}) {
      audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
      restoreAudioFocus = {}
    }
  }

  private fun cleanupAudio() {
    abandonAudioFocus()
  }

  private fun cleanupReceivers() {
    if (noisyReceiverRegistered) {
      runCatching {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }
    }

    if (screenStateReceiverRegistered) {
      runCatching {
        unregisterReceiver(screenStateReceiver)
        screenStateReceiverRegistered = false
      }
    }
  }

  private fun registerScreenStateReceiver() {
    if (screenStateReceiverRegistered) return

    runCatching {
      val filter =
        IntentFilter().apply {
          addAction(Intent.ACTION_SCREEN_OFF)
          addAction(Intent.ACTION_SCREEN_ON)
          addAction(Intent.ACTION_USER_PRESENT)
        }
      registerReceiver(screenStateReceiver, filter)
      screenStateReceiverRegistered = true
    }.onFailure { e ->
      Log.e(TAG, "Error registering screen state receiver", e)
    }
  }

  override fun onPause() {
    runCatching {
      val isInPip = isInPictureInPictureMode
      val shouldPause =
        PlayerLifecyclePolicy.shouldPauseOnPause(
          backgroundPlaybackEnabled = isBackgroundPlaybackEnabled(),
          backgroundPlaybackSessionActive = isBackgroundPlaybackSessionActive,
          isUserFinishing = isUserFinishing,
          isInPictureInPictureMode = isInPip,
          isScreenOffOrLocked = isDeviceScreenOffOrLocked(),
        )

      if (!isInPip && shouldPause) {
        wasPlayingBeforePause = !(viewModel.paused ?: true)
        viewModel.pause()
      } else if (!isInPip && !shouldPause) {
        // Background playback is active - disable video decoding to save battery
        disableVideoForBackground()
      }

      // Restore UI immediately when user is finishing for instant feedback
      if (isUserFinishing && !isInPip && !isBackgroundPlaybackSessionActive) {
        restoreSystemUI()
      }

      saveVideoPlaybackState(fileName, immediate = true)
    }.onFailure { e ->
      Log.e(TAG, "Error during onPause", e)
    }

    super.onPause()
  }

  override fun finish() {
    runCatching {
      saveVideoPlaybackState(fileName, immediate = true)
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      
      // Clean up service when finishing
      if (!isBackgroundPlaybackSessionActive) {
        endBackgroundPlayback()
      }
      
      if (!isBackgroundPlaybackSessionActive) {
        reportJellyfinStop()
      }
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finish", e)
    }

    super.finish()
  }

  override fun finishAndRemoveTask() {
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      isUserFinishing = true
      
      // Clean up service when finishing
      if (!isBackgroundPlaybackSessionActive) {
        endBackgroundPlayback()
      }
      
      reportJellyfinStop()
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finishAndRemoveTask", e)
    }

    super.finishAndRemoveTask()
  }

  override fun onStop() {
    runCatching {
      pipHelper.onStop()

      if (noisyReceiverRegistered) {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }

      // Unregister the screen-state receiver while stopped so the Activity
      // is not woken by ACTION_SCREEN_OFF / ACTION_USER_PRESENT while in
      // the background. It is re-registered in onStart(). See issue 2.3.
      if (screenStateReceiverRegistered) {
        unregisterReceiver(screenStateReceiver)
        screenStateReceiverRegistered = false
      }

      if (
        PlayerLifecyclePolicy.shouldTreatStopAsPipDismissal(
          wasInPictureInPictureMode = wasInPipMode,
          isInPictureInPictureMode = isInPictureInPictureMode,
          isChangingConfigurations = isChangingConfigurations,
          backgroundPlaybackEnabled = isBackgroundPlaybackEnabled(),
          isScreenOffOrLocked = isDeviceScreenOffOrLocked(),
          alreadyHandled = handledPipDismissal,
        )
      ) {
        handlePipDismissed()
        return@runCatching
      }

      if (
        PlayerLifecyclePolicy.shouldStartBackgroundPlaybackOnStop(
          backgroundPlaybackEnabled = isBackgroundPlaybackEnabled(),
          backgroundPlaybackSessionActive = isBackgroundPlaybackSessionActive,
          isUserFinishing = isUserFinishing,
          isFinishing = isFinishing,
          isInPictureInPictureMode = isInPictureInPictureMode,
          isScreenOffOrLocked = isDeviceScreenOffOrLocked(),
        )
      ) {
        if (startBackgroundPlayback(allowUserPrompt = false) == BackgroundPlaybackStartResult.Started) {
          isBackgroundPlaybackSessionActive = true
          disableVideoForBackground()
        } else {
          rememberResumeAfterUnlockBeforeForcedPause()
          viewModel.pause()
        }
        return@runCatching
      }

      if (isDeviceScreenOffOrLocked() && !isBackgroundPlaybackEnabled()) {
        rememberResumeAfterUnlockBeforeForcedPause()
        viewModel.pause()
      } else if (!isBackgroundPlaybackSessionActive && (isUserFinishing || isFinishing)) {
        viewModel.pause()
      } else if (isBackgroundPlaybackSessionActive && !isInBackgroundPlayback) {
        disableVideoForBackground()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onStop", e)
    }

    super.onStop()
  }

  private fun handlePipDismissed() {
    Log.d(TAG, "PiP dismissed; closing playback instead of continuing in background")
    handledPipDismissal = true
    isUserFinishing = true
    isBackgroundPlaybackSessionActive = false
    pendingBackgroundTransition = false
    viewModel.pause()
    endBackgroundPlayback()
    if (!isFinishing && !isDestroyed) {
      finish()
    }
  }

  fun getCurrentPlayableUriForLookup(): String? = currentPlayableUri ?: intent?.dataString

  override fun onStart() {
    super.onStart()

    runCatching {
      setupWindowFlags()
      setupSystemUI()
      val deviceScreenOffOrLocked = isDeviceScreenOffOrLocked()

      if (!deviceScreenOffOrLocked) {
        // Foreground playback owns the session again after unlock or app return.
        enableVideoAfterBackground()
        if (MediaPlaybackService.isRunning()) endBackgroundPlayback()
        isBackgroundPlaybackSessionActive = false
      }

      if (!noisyReceiverRegistered) {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
        noisyReceiverRegistered = true
      }

      // Re-register the screen-state receiver when returning to the
      // foreground. It is unregistered in onStop(). See issue 2.3.
      registerScreenStateReceiver()

      if (playerPreferences.rememberBrightness.get()) {
        val brightness = playerPreferences.defaultBrightness.get()
        if (brightness != BRIGHTNESS_NOT_SET) {
          viewModel.changeBrightnessTo(brightness)
        }
      }
      
      if (!isInPictureInPictureMode) {
        wasInPipMode = false
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onStart", e)
    }
  }

  private fun setupWindowFlags() {
    pipHelper.updatePictureInPictureParams()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setFlags(
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
  }

  private fun setLayoutInDisplayCutoutModeIfSupported(shortEdges: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    val mode =
      if (shortEdges) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      } else {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
      }
    val attributes = window.attributes
    attributes.layoutInDisplayCutoutMode = mode
    window.attributes = attributes
  }

  private fun setupSystemBarsAutoHide() {
    ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
      handleSystemBarsVisibility(insets)
      binding.player.applyOsdSafeAreaMargins(insets)
      insets
    }
    lifecycleScope.launch {
      playerPreferences.safeAreaWindow.changes().drop(1).collect {
        binding.player.applyOsdSafeAreaMargins(ViewCompat.getRootWindowInsets(binding.root))
      }
    }
    binding.root.post { ViewCompat.requestApplyInsets(binding.root) }
  }

  private fun handleSystemBarsVisibility(insets: WindowInsetsCompat) {
    val systemBarsVisible =
      insets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
        insets.isVisible(WindowInsetsCompat.Type.navigationBars())

    if (systemBarsVisible) {
      scheduleSystemBarsAutoHide()
    } else {
      cancelSystemBarsAutoHide()
    }
  }

  private fun shouldAutoHideSystemBars(): Boolean =
    !isInPictureInPictureMode &&
      !viewModel.controlsShown.value &&
      viewModel.sheetShown.value == Sheets.None &&
      viewModel.panelShown.value == Panels.None

  private fun scheduleSystemBarsAutoHide(delayMs: Long = 1500L) {
    if (!shouldAutoHideSystemBars()) {
      cancelSystemBarsAutoHide()
      return
    }

    systemBarsAutoHideJob?.cancel()
    systemBarsAutoHideJob =
      lifecycleScope.launch {
        delay(delayMs)
        if (shouldAutoHideSystemBars()) {
          hideSystemBarsForPlayback()
        }
      }
  }

  private fun cancelSystemBarsAutoHide() {
    systemBarsAutoHideJob?.cancel()
    systemBarsAutoHideJob = null
  }

  @Suppress("DEPRECATION")
  private fun hideSystemBarsForPlayback() {
    cancelSystemBarsAutoHide()
    try {
      windowInsetsController.apply {
        hide(WindowInsetsCompat.Type.statusBars())
        hide(WindowInsetsCompat.Type.navigationBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to hide system bars for playback", e)
    }

    binding.root.systemUiVisibility =
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        if (playerPreferences.showSystemStatusBar.get()) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
  }

  private fun setupSystemUI() {
    setLayoutInDisplayCutoutModeIfSupported(shortEdges = true)

    // Set status bar color for when it will be shown (with controls)
    applyStatusBarColorIfNeeded()

    // Always start with status bar hidden - it will show when controls are shown
    hideSystemBarsForPlayback()
  }

  @Suppress("DEPRECATION")
  private fun applyStatusBarColorIfNeeded() {
    if (playerPreferences.showSystemStatusBar.get()) {
      window.statusBarColor = android.graphics.Color.parseColor("#80000000")
    }
  }

  private fun restoreSystemUI() {
    cancelSystemBarsAutoHide()

    // Clear flags first for immediate effect
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Set cutout mode before showing bars for smoother transition
    setLayoutInDisplayCutoutModeIfSupported(shortEdges = false)

    // Update window insets configuration
    WindowCompat.setDecorFitsSystemWindows(window, true)

    // Restore default behavior and show bars in one go
    try {
      windowInsetsController.apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to restore system UI insets", e)
    }
  }

  private fun releaseDetachedBackgroundPlaybackBeforeFreshLaunch() {
    if (!MediaPlaybackService.isRunning()) return

    Log.d(TAG, "Stopping detached background playback before fresh player launch")
    runCatching {
      stopService(Intent(this, MediaPlaybackService::class.java))
    }.onFailure { e ->
      Log.e(TAG, "Error stopping detached playback service", e)
    }
    MpvSessionCoordinator.releaseDetachedSession {
      MPVLib.setPropertyBooleanResult("pause", true)
      MPVLib.commandResult("quit")
      player.destroy()
    }
  }

  private fun isNotificationReentryIntent(intent: Intent?): Boolean =
    intent?.action == MediaPlaybackService.ACTION_OPEN_PLAYER && MediaPlaybackService.isRunning()

  /**
   * Initializes the MPV player with the necessary paths and observers.
   * CRITICAL: Must copy config and scripts BEFORE initializing MPV, as MPV loads scripts during init.
   */
  private fun setupMPV(reuseDetachedSession: Boolean) {
    val sessionId = MpvSessionCoordinator.begin(activityInstanceId)
    playerSessionId = sessionId
    player.sessionId = sessionId
    playerObserver = PlayerObserver(this, sessionId)
    player.onSurfaceReady = {
      if (acceptsPlayerCallback(sessionId) &&
        !isDeviceScreenOffOrLocked() &&
        (isInBackgroundPlayback || lastVid > 0)
      ) {
        enableVideoAfterBackground()
      }
    }
    MpvSessionCoordinator.initialize(
      sessionId = sessionId,
      operation = {
        if (reuseDetachedSession) {
          player.holder.addCallback(player)
          Log.i(TAG, "session=$sessionId adopted detached background MPV core")
        } else {
          syncBundledAssetsIfNeeded()
          syncFromUserMpvDirectory()
          sanitizeInternalMpvConfig()
          sanitizeInternalFontsDirectory()
          Log.d(TAG, "MPV config and assets prepared successfully")
          initializePlayerWithRendererFallback()
          runCatching { MPVLib.setThumbnailJavaVM(applicationContext) }
        }
        MPVLib.addObserver(playerObserver)
        MPVLib.addLogObserver(playerObserver)
      },
      onReady = onReady@{
        if (!MpvSessionCoordinator.isCurrent(sessionId) || isDestroyed) return@onReady
        mpvInitialized = true
        Log.i(TAG, "session=$sessionId activity=$activityInstanceId state=READY thread=${Thread.currentThread().name}")
        viewModel.onMpvCoreInitialized()
        setupAudio()
        setupPlayerControls()
        scheduleDeferredSubtitleFontsSync()
        startPlaybackWatchdog()
        PlayerDiagnostics.logMemory(applicationContext, sessionId, "startup")
        if (pendingConfigWarnings.isNotEmpty()) {
          viewModel.showToast("Incompatible mpv.conf options were disabled. Review the config editor.")
        }
      },
      onFailure = { error ->
        Log.e(TAG, "session=$sessionId activity=$activityInstanceId MPV initialization failed", error)
        viewModel.showToast(getString(R.string.toast_playback_load_failed))
        if (!isFinishing) finish()
      },
    )
  }

  private fun initializePlayerWithRendererFallback() {
    player.forceOpenGlFallback = false

    runCatching {
      player.initialize(filesDir.path, cacheDir.path)
    }.recoverCatching { error ->
      if (!decoderPreferences.useVulkan.get()) {
        throw error
      }

      Log.w(TAG, "MPV Vulkan init failed, retrying with OpenGL fallback for this session", error)
      player.forceOpenGlFallback = true
      runCatching { MPVLib.destroy() }
      player.initialize(filesDir.path, cacheDir.path)
    }.getOrElse { error ->
      Log.e(TAG, "Failed to initialize MPV", error)
      throw error
    }
  }

  /**
   * Syncs MPV assets from the user's configured MPV directory to internal storage.
   * Handles: mpv.conf, input.conf, selected scripts/, script helper folders, script-opts/,
   * shaders/, and fonts/.
   */
  private fun syncFromUserMpvDirectory() {
    val mpvConfStorageUri = advancedPreferences.mpvConfStorageUri.get()

    // Try to open the user's MPV directory
    val tree =
      if (mpvConfStorageUri.isNotBlank()) {
        openPersistedTreeDocument(this, mpvConfStorageUri)
      } else {
        null
      }

    if (tree != null) {
      Log.d(TAG, "Syncing from user MPV directory: ${tree.uri}")
      val rootChildren = listTreeFilesSafely(tree)
      syncConfigFiles(tree, rootChildren)
      syncScripts(tree, rootChildren)
      syncScriptOpts(tree, rootChildren)
      syncShaders(tree, rootChildren)
      syncFonts(tree, rootChildren)
      Log.d(TAG, "Full MPV directory sync completed")
    } else {
      // Fallback: use preferences-based config (no user directory set)
      Log.d(TAG, "No MPV directory configured, using preferences fallback")
      copyMPVConfigFromPreferences()
    }
  }

  // ==================== Config Files Sync ====================

  /**
   * Syncs mpv.conf and input.conf from the user's MPV directory.
   * Also caches the content in preferences for the config editor.
   */
  private fun syncConfigFiles(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    for (configName in listOf("mpv.conf", "input.conf")) {
      runCatching {
        val configFile = findFileCaseInsensitive(tree, configName, rootChildren)
        if (configFile != null && configFile.exists() && configFile.canRead()) {
          contentResolver.openInputStream(configFile.uri)?.use { input ->
            val content = input.bufferedReader().readText()
            writeTextFileIfChanged(File(filesDir, configName), content)
            // Cache in preferences for the config editor
            when (configName) {
              "mpv.conf" -> advancedPreferences.mpvConf.set(content)
              "input.conf" -> advancedPreferences.inputConf.set(content)
            }
            Log.d(TAG, "Synced config: $configName (${content.length} chars)")
          }
        } else {
          // Config not in directory, fall back to preferences
          val prefContent = when (configName) {
            "mpv.conf" -> advancedPreferences.mpvConf.get()
            "input.conf" -> advancedPreferences.inputConf.get()
            else -> ""
          }
          File(filesDir, configName).apply {
            if (!exists()) createNewFile()
            if (prefContent.isNotBlank()) writeText(prefContent)
          }
          Log.d(TAG, "Config not found in directory, used preferences: $configName")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing config: $configName", e)
      }
    }
  }

  // ==================== Scripts Sync ====================

  /**
   * Syncs all script files (.lua, .js) from the user's MPV directory.
   * Looks in scripts/ subfolder first (case-insensitive), falls back to root.
   */
  private fun syncScripts(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val internalScriptsDir = File(filesDir, "scripts")
    internalScriptsDir.mkdirs()

    if (!advancedPreferences.enableLuaScripts.get()) {
      clearDirectoryContents(internalScriptsDir)
      Log.d(TAG, "Scripts disabled, skipping")
      return
    }

    val scriptsSubdir = findSubdirCaseInsensitive(tree, "scripts", rootChildren)
    val sourceDir = scriptsSubdir ?: tree
    val scriptExtensions = setOf("lua", "js")
    val selectedScripts = advancedPreferences.selectedLuaScripts.get()
    val count =
      syncFlatDocumentDirectory(
        sourceDir = sourceDir,
        destinationDir = internalScriptsDir,
        includeFile = { name -> name.substringAfterLast('.', "").lowercase() in scriptExtensions },
        allowedNames = selectedScripts,
        deleteMissing = true,
      )
    val supportCount = syncScriptSupportDirectories(scriptsSubdir)

    Log.d(
      TAG,
      "Scripts sync: $count file(s), $supportCount helper file(s) from ${if (scriptsSubdir != null) "scripts/" else "root"}",
    )
  }

  /**
   * Syncs helper folders from scripts/ and mirrors Lua modules into mpv's internal
   * script-modules path so require() works without exposing a separate user folder.
   */
  private fun syncScriptSupportDirectories(
    scriptsSubdir: DocumentFile?,
  ): Int {
    val internalScriptsDir = File(filesDir, "scripts")
    val internalModulesDir = File(filesDir, "script-modules")
    internalModulesDir.mkdirs()

    if (!advancedPreferences.enableLuaScripts.get()) {
      clearDirectoryContents(internalModulesDir)
      return 0
    }

    clearDirectoryContents(internalModulesDir)

    var copiedCount = 0

    if (scriptsSubdir != null) {
      listTreeFilesSafely(scriptsSubdir).forEach { document ->
        val name = document.name?.takeIf { isSafeDocumentFileName(it) } ?: return@forEach
        if (!document.isDirectory) return@forEach

        copiedCount += syncRecursiveDocumentDirectory(
          sourceDir = document,
          destinationDir = File(internalScriptsDir, name),
          includeFile = { true },
          deleteMissing = true,
        )

        copiedCount += syncRecursiveDocumentDirectory(
          sourceDir = document,
          destinationDir = File(internalModulesDir, name),
          includeFile = { fileName -> fileName.endsWith(".lua", ignoreCase = true) },
          deleteMissing = true,
        )
      }
    }

    return copiedCount
  }

  // ==================== Script Options Sync ====================

  /**
   * Syncs all files from script-opts/ subfolder (case-insensitive).
   */
  private fun syncScriptOpts(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val internalScriptOptsDir = File(filesDir, "script-opts")
    internalScriptOptsDir.mkdirs()

    val scriptOptsSubdir = findSubdirCaseInsensitive(tree, "script-opts", rootChildren)
    if (scriptOptsSubdir == null) {
      Log.d(TAG, "No script-opts/ subfolder found, skipping")
      return
    }

    val count =
      syncFlatDocumentDirectory(
        sourceDir = scriptOptsSubdir,
        destinationDir = internalScriptOptsDir,
        includeFile = { true },
        deleteMissing = true,
      )

    Log.d(TAG, "Script-opts sync: $count file(s)")
  }

  // ==================== Shaders Sync ====================

  /**
   * Syncs shader files (.glsl, .hook, .comp) from the user's MPV directory.
   * Looks in shaders/ subfolder first (case-insensitive), falls back to root.
   */
  private fun syncShaders(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val shadersDir = File(filesDir, "shaders")
    shadersDir.mkdirs()

    val shadersSubdir = findSubdirCaseInsensitive(tree, "shaders", rootChildren)
    val sourceDir = shadersSubdir ?: tree
    val shaderExtensions = setOf("glsl", "hook", "comp")
    val count =
      syncFlatDocumentDirectory(
        sourceDir = sourceDir,
        destinationDir = shadersDir,
        includeFile = { name -> name.substringAfterLast('.', "").lowercase() in shaderExtensions },
        protectedNames = Anime4KManager.BUILT_IN_SHADER_FILES,
        deleteMissing = true,
      )

    Log.d(TAG, "Shaders sync: $count file(s)")
  }

  // ==================== Fonts Sync ====================

  /**
   * Syncs font files (.ttf, .otf, .ttc, .woff, .woff2) from the user's MPV directory.
   * Looks in fonts/ subfolder first (case-insensitive), falls back to root.
   */
  private fun syncFonts(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val internalFontsDir = File(filesDir, "fonts")
    internalFontsDir.mkdirs()
    internalFontsDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }

    val fontsSubdir = findSubdirCaseInsensitive(tree, "fonts", rootChildren)
    val sourceDir = fontsSubdir ?: tree
    val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")
    val count =
      syncFlatDocumentDirectory(
        sourceDir = sourceDir,
        destinationDir = internalFontsDir,
        includeFile = { name -> name.substringAfterLast('.', "").lowercase() in fontExtensions },
        deleteMissing = false,
      )

    Log.d(TAG, "Fonts sync: $count file(s) from MPV directory")
  }

  private fun syncBundledAssetsIfNeeded() {
    val syncPrefs = getSharedPreferences("mpv_asset_sync", MODE_PRIVATE)
    val currentVersion =
      runCatching {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
      }.getOrDefault(-1L)

    val assetsAlreadyPrepared =
      File(filesDir, "mpv.conf").exists() &&
        File(filesDir, "input.conf").exists() &&
        File(filesDir, "scripts").exists()

    if (assetsAlreadyPrepared && syncPrefs.getLong("bundled_assets_version", -1L) == currentVersion) {
      return
    }

    Utils.copyAssets(this@PlayerActivity)
    syncPrefs.edit().putLong("bundled_assets_version", currentVersion).apply()
  }

  private fun scheduleDeferredSubtitleFontsSync() {
    deferredFontSyncJob?.cancel()
    deferredFontSyncJob =
      lifecycleScope.launch(Dispatchers.IO) {
        delay(750)
        runCatching { syncSubtitleFontsFromPreferenceFolder() }
          .onFailure { e -> Log.e(TAG, "Deferred subtitle font sync failed", e) }
      }
  }

  private fun syncSubtitleFontsFromPreferenceFolder() {
    val sourceDir = resolveSubtitleFontSourceDirectory() ?: return

    val destinationDir = File(filesDir, "fonts")
    destinationDir.mkdirs()
    destinationDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
    syncFontDirectory(sourceDir, destinationDir)
  }

  private fun resolveSubtitleFontSourceDirectory(): DocumentFile? {
    val fontsFolderUri = subtitlesPreferences.fontsFolder.get()
    if (fontsFolderUri.isBlank()) return null

    val sourceDir = openPersistedTreeDocument(this, fontsFolderUri) ?: return null

    // Older builds auto-pointed the subtitle font folder at the whole storage/config root.
    // Use its fonts/ child instead so playback never recursively scans a large media folder.
    if (fontsFolderUri == advancedPreferences.mpvConfStorageUri.get()) {
      return findSubdirCaseInsensitive(sourceDir, "fonts")
    }

    return sourceDir
  }

  private fun syncFontDirectory(
    sourceDir: DocumentFile,
    destinationDir: File,
  ): Int {
    destinationDir.mkdirs()
    var copiedCount = 0

    listTreeFilesSafely(sourceDir).forEach { document ->
      val name = document.name ?: return@forEach
      when {
        document.isDirectory -> {
          copiedCount += syncFontDirectory(document, destinationDir)
        }
        document.isFile -> {
          val extension = name.substringAfterLast('.', "").lowercase()
          if (extension !in setOf("ttf", "otf", "ttc", "woff", "woff2")) {
            return@forEach
          }

          if (copyDocumentToFileIfNeeded(document, File(destinationDir, name))) {
            copiedCount++
          }
        }
      }
    }

    return copiedCount
  }

  private fun syncRecursiveDocumentDirectory(
    sourceDir: DocumentFile,
    destinationDir: File,
    includeFile: (name: String) -> Boolean,
    deleteMissing: Boolean,
  ): Int {
    destinationDir.mkdirs()
    val expectedFiles = mutableSetOf<String>()
    val expectedDirs = mutableSetOf<String>()
    var copiedCount = 0

    fun syncDirectory(
      currentSourceDir: DocumentFile,
      currentDestinationDir: File,
      relativeDir: String,
    ) {
      currentDestinationDir.mkdirs()
      listTreeFilesSafely(currentSourceDir).forEach { document ->
        val name = document.name?.takeIf { isSafeDocumentFileName(it) } ?: return@forEach
        val relativePath = if (relativeDir.isBlank()) name else "$relativeDir/$name"

        when {
          document.isDirectory -> {
            expectedDirs += relativePath
            syncDirectory(
              currentSourceDir = document,
              currentDestinationDir = File(currentDestinationDir, name),
              relativeDir = relativePath,
            )
          }
          document.isFile && includeFile(name) -> {
            expectedFiles += relativePath
            if (copyDocumentToFileIfNeeded(document, File(currentDestinationDir, name))) {
              copiedCount++
            }
          }
        }
      }
    }

    syncDirectory(sourceDir, destinationDir, relativeDir = "")

    if (deleteMissing) {
      pruneDirectoryToExpected(destinationDir, expectedFiles, expectedDirs, relativeDir = "")
    }

    return copiedCount
  }

  private fun pruneDirectoryToExpected(
    directory: File,
    expectedFiles: Set<String>,
    expectedDirs: Set<String>,
    relativeDir: String,
  ) {
    directory.listFiles()?.forEach { existingFile ->
      val relativePath =
        if (relativeDir.isBlank()) {
          existingFile.name
        } else {
          "$relativeDir/${existingFile.name}"
        }

      when {
        existingFile.isDirectory -> {
          pruneDirectoryToExpected(existingFile, expectedFiles, expectedDirs, relativePath)
          val isExpected = relativePath in expectedDirs
          val isEmpty = existingFile.listFiles()?.isEmpty() != false
          if (!isExpected || isEmpty) {
            existingFile.deleteRecursively()
          }
        }
        existingFile.isFile && relativePath !in expectedFiles -> existingFile.delete()
      }
    }
  }

  private fun syncFlatDocumentDirectory(
    sourceDir: DocumentFile,
    destinationDir: File,
    includeFile: (name: String) -> Boolean,
    allowedNames: Set<String>? = null,
    protectedNames: Set<String> = emptySet(),
    deleteMissing: Boolean,
  ): Int {
    destinationDir.mkdirs()
    val expectedNames = mutableSetOf<String>()
    var copiedCount = 0

    listTreeFilesSafely(sourceDir).forEach { document ->
      if (!document.isFile) return@forEach
      val name = document.name ?: return@forEach
      if (!includeFile(name)) return@forEach
      if (allowedNames != null && name !in allowedNames) return@forEach

      expectedNames += name
      if (copyDocumentToFileIfNeeded(document, File(destinationDir, name))) {
        copiedCount++
      }
    }

    if (deleteMissing) {
      destinationDir.listFiles()?.forEach { existingFile ->
        if (existingFile.isFile &&
          existingFile.name !in expectedNames &&
          existingFile.name !in protectedNames
        ) {
          existingFile.delete()
        }
      }
    }

    return copiedCount
  }

  private fun copyDocumentToFileIfNeeded(
    source: DocumentFile,
    target: File,
  ): Boolean {
    val sourceLength = source.length()
    val sourceLastModified = source.lastModified()

    if (target.exists() &&
      sourceLength >= 0L &&
      target.length() == sourceLength &&
      sourceLastModified > 0L &&
      target.lastModified() == sourceLastModified
    ) {
      return false
    }

    target.parentFile?.mkdirs()
    contentResolver.openInputStream(source.uri)?.use { input ->
      target.outputStream().use { output ->
        input.copyTo(output)
      }
    } ?: return false

    if (sourceLastModified > 0L) {
      target.setLastModified(sourceLastModified)
    }
    return true
  }

  private fun writeTextFileIfChanged(
    target: File,
    content: String,
  ) {
    if (target.exists() && runCatching { target.readText() }.getOrNull() == content) {
      return
    }

    target.parentFile?.mkdirs()
    target.writeText(content)
  }

  /**
   * Loads a specific Lua script at runtime without restarting the player.
   * Finds the script in the user's MPV directory, copies it to internal storage,
   * and commands MPV to load it.
   */
  private fun loadScriptAtRuntime(scriptName: String) {
    if (!mpvInitialized || isFinishing) return

    val mpvConfStorageUri = advancedPreferences.mpvConfStorageUri.get()
    if (mpvConfStorageUri.isBlank()) return

    lifecycleScope.launch(Dispatchers.IO) {
      runCatching {
        val tree = openPersistedTreeDocument(this@PlayerActivity, mpvConfStorageUri)
        if (tree != null) {
          val rootChildren = listTreeFilesSafely(tree)
          // Look for scripts/ subfolder first (case-insensitive), fall back to root
          val scriptsSubdir = findSubdirCaseInsensitive(tree, "scripts", rootChildren)
          val scriptsDir = scriptsSubdir ?: tree
          syncScriptSupportDirectories(scriptsSubdir)
          syncScriptOpts(tree, rootChildren)
          
          val scriptFile = listTreeFilesSafely(scriptsDir).firstOrNull {
            it.name == scriptName 
          }

          if (scriptFile != null) {
            val internalScriptsDir = File(filesDir, "scripts")
            if (!internalScriptsDir.exists()) internalScriptsDir.mkdirs()
            
            val targetFile = File(internalScriptsDir, scriptName)
            
            contentResolver.openInputStream(scriptFile.uri)?.use { input ->
              targetFile.outputStream().use { output ->
                input.copyTo(output)
              }
            }
            
            withContext(Dispatchers.Main) {
              if (!canIssueMpvCommands()) return@withContext
              MPVLib.command("load-script", targetFile.absolutePath)
              viewModel.showToast("Loaded script: $scriptName")
            }
          }
        }
      }.onFailure { e ->
        Log.e(TAG, "Error loading script at runtime: $scriptName", e)
        withContext(Dispatchers.Main) {
          android.widget.Toast.makeText(
            this@PlayerActivity,
            "Failed to load script: ${e.message}",
            android.widget.Toast.LENGTH_LONG
          ).show()
        }
      }
    }
  }

  // ==================== Helpers ====================

  /**
   * Fallback: copies config from preferences when no user MPV directory is set.
   */
  private fun copyMPVConfigFromPreferences() {
    runCatching {
      File(filesDir, "mpv.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.mpvConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      File(filesDir, "input.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.inputConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      // Ensure scripts directory exists even without user dir
      File(filesDir, "scripts").mkdirs()
      File(filesDir, "script-modules").mkdirs()
      File(filesDir, "fonts").mkdirs()
      File(filesDir, "shaders").mkdirs()
    }.onFailure { e ->
      Log.e(TAG, "Error creating fallback config files", e)
    }
  }

  private fun sanitizeInternalFontsDirectory() {
    val fontsDir = File(filesDir, "fonts")
    if (!fontsDir.exists()) {
      return
    }

    fontsDir.listFiles()?.filter { it.isDirectory }?.forEach { nestedDir ->
      nestedDir.deleteRecursively()
    }
  }

  private fun clearDirectoryContents(directory: File) {
    directory.listFiles()?.forEach { child ->
      if (child.isDirectory) {
        child.deleteRecursively()
      } else {
        child.delete()
      }
    }
  }

  private fun isSafeDocumentFileName(name: String): Boolean =
    name.isNotBlank() && !name.contains('/') && !name.contains('\\')

  /**
   * Finds a subdirectory by name (case-insensitive) within a DocumentFile.
   */
  private fun findSubdirCaseInsensitive(
    parent: DocumentFile,
    name: String,
    children: Array<DocumentFile> = listTreeFilesSafely(parent),
  ): DocumentFile? =
    children.firstOrNull {
      it.isDirectory && it.name?.equals(name, ignoreCase = true) == true
    }

  /**
   * Finds a file by name (case-insensitive) within a DocumentFile.
   */
  private fun findFileCaseInsensitive(
    parent: DocumentFile,
    name: String,
    children: Array<DocumentFile> = listTreeFilesSafely(parent),
  ): DocumentFile? =
    children.firstOrNull {
      it.isFile && it.name?.equals(name, ignoreCase = true) == true
    }

  override fun onResume() {
    super.onResume()
    if (!isDeviceScreenOffOrLocked()) enableVideoAfterBackground()
    updateVolume()
    resumePlaybackAfterScreenUnlockIfNeeded()
    if (!screenUnlockPlaybackController.hasPendingResume()) wasPlayingBeforePause = false
  }

  /**
   * Updates the volume level to match the system volume.
   *
   * This method updates the current volume level by getting the current system volume
   * and adjusting the MPV volume accordingly. It ensures that the MPV volume is set
   * to the maximum allowed value if the system volume is lower than the maximum.
   */
  private fun updateVolume() {
    val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    viewModel.syncCurrentVolumeState()
    if (volume < viewModel.maxVolume) {
      viewModel.changeMPVVolumeTo(MAX_MPV_VOLUME)
    }
  }

  private fun isBackgroundPlaybackEnabled(): Boolean = audioPreferences.backgroundPlayback.get()

  private fun isDeviceScreenOffOrLocked(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    return keyguardManager.isDeviceLocked || !powerManager.isInteractive
  }

  private fun rememberResumeAfterUnlockBeforeForcedPause() {
    screenUnlockPlaybackController.onScreenTurnedOff(
      autoplayAfterScreenUnlockEnabled = playerPreferences.autoplayAfterScreenUnlock.get(),
      wasPlayingBeforePause = wasPlayingBeforePause,
      isCurrentlyPaused = viewModel.paused,
      backgroundPlaybackActive = false,
      isUserFinishing = isUserFinishing,
      isFinishing = isFinishing,
    )
    wasPlayingBeforePause = viewModel.paused == false || wasPlayingBeforePause
  }

  private fun resumePlaybackAfterScreenUnlockIfNeeded() {
    resumeAfterUnlockJob?.cancel()
    if (!screenUnlockPlaybackController.hasPendingResume()) return

    resumeAfterUnlockJob = lifecycleScope.launch {
      repeat(50) {
        val deviceLocked = isDeviceScreenOffOrLocked()
        if (screenUnlockPlaybackController.consumeResumeAfterUnlockIfReady(deviceLocked)) {
          wasPlayingBeforePause = false
          if (viewModel.paused == true && !isFinishing && !isUserFinishing) {
            viewModel.unpause()
          }
          return@launch
        }
        if (!screenUnlockPlaybackController.hasPendingResume()) return@launch
        delay(100)
      }
    }
  }

  /**
   * Processes intent extras to set initial playback position, subtitles, and HTTP headers.
   *
   * This method checks the intent extras for the following keys:
   * - "position": The initial playback position in seconds.
   * - "subs": A list of subtitle URIs to add.
   * - "subs.enable": A list of subtitle URIs to enable.
   * - "headers": A list of HTTP headers to set for network playback.
   *
   * @param extras Bundle containing intent extras
   */
  private fun setIntentExtras(extras: Bundle?) {
    if (extras == null) return

    extras.getInt("position", POSITION_NOT_SET).takeIf { it != POSITION_NOT_SET }?.let {
      MPVLib.setPropertyInt("time-pos", it / MILLISECONDS_TO_SECONDS)
    }

    addSubtitlesFromExtras(extras)
    setHttpHeadersFromExtras(extras)
  }

  /**
   * Adds subtitle tracks from intent extras.
   *
   * This method checks the intent extras for the "subs" key, which contains a list
   * of subtitle URIs to add. It also checks for the "subs.enable" key, which contains
   * a list of subtitle URIs to enable.
   *
   * @param extras Bundle containing subtitle URIs
   */
  private fun addSubtitlesFromExtras(extras: Bundle) {
    if (!extras.containsKey("subs")) return

    val subList = Utils.getParcelableArray<Uri>(extras, "subs").toList().orEmpty()
    val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable").toList().orEmpty()
    val hasSubsToEnable = extras.containsKey("subs.enable")
    val subtitleTitles = extras.getStringArray("subs.titles").orEmpty()
    val subtitleLanguages = extras.getStringArray("subs.langs").orEmpty()
    val subtitleEntries =
      IntentSubtitleLoadPolicy.entriesToLoad(
        subtitles = subList,
        enabledSubtitles = subsToEnable,
        hasEnabledSubtitleExtra = hasSubsToEnable,
      )

    intentSubtitleJob?.cancel()
    intentSubtitleJob = lifecycleScope.launch(Dispatchers.IO) {
      for (entry in subtitleEntries) {
        if (!isActive || !canIssueMpvCommands()) break
        val suburi = entry.value
        val subfile = suburi.resolveUri(this@PlayerActivity) ?: continue
        val flag = if (entry.select) "select" else "auto"
        val title = subtitleTitles.getOrNull(entry.metadataIndex)?.trim().orEmpty().ifBlank { null }
        val language = subtitleLanguages.getOrNull(entry.metadataIndex)?.trim().orEmpty().ifBlank { null }
        val displayTitle = title ?: language

        withContext(Dispatchers.Main.immediate) {
          if (!canIssueMpvCommands()) return@withContext

          Log.v(TAG, "Adding subtitles from intent extras: $subfile")
          val trackCountBefore = MPVLib.getPropertyInt("track-list/count") ?: 0
          runCatching {
            when {
              displayTitle != null -> MPVLib.command("sub-add", subfile, flag, displayTitle)
              else -> MPVLib.command("sub-add", subfile, flag)
            }
          }
            .onSuccess {
              val trackCountAfter = MPVLib.getPropertyInt("track-list/count") ?: 0
              if (displayTitle != null && trackCountAfter > trackCountBefore) {
                val newTrackIndex = trackCountAfter - 1
                runCatching {
                  MPVLib.setPropertyString("track-list/$newTrackIndex/title", displayTitle)
                }
              }
            }
            .onFailure { error ->
              Log.w(TAG, "Failed to add subtitle from intent extras: $subfile", error)
            }
        }
      }
    }
  }

  /**
   * Sets HTTP headers from intent extras for network playback.
   *
   * This method checks the intent extras for the "headers" key, which contains a list
   * of HTTP headers to set. It sets the User-Agent header and any additional headers
   * specified in the list.
   *
   * Also automatically adds Referer header based on the URL origin if not already provided.
   *
   * @param extras Bundle containing HTTP headers
   */
  private fun setHttpHeadersFromExtras(extras: Bundle?) {
    // Build header map starting with auto-detected referer
    val headerMap = mutableMapOf<String, String>()
    var userAgent: String? = null

    // Automatically extract and set referer domain from the URL
    val uri = extractUriFromIntent(intent)
    if (uri != null && HttpUtils.isNetworkStream(uri)) {
      HttpUtils.extractRefererDomain(uri)?.let { referer ->
        headerMap["Referer"] = referer
        Log.d(TAG, "Auto-detected Referer: $referer")
      }
    }

    // Process headers from extras (these can override the auto-detected referer)
    extras?.getStringArray("headers")?.let { headers ->
      headers
        .asList()
        .chunked(2)
        .filter { it.size == 2 }
        .forEach { (key, value) ->
          if (key.equals("User-Agent", ignoreCase = true)) {
            userAgent = value
          } else {
            headerMap[key] = value
          }
        }
    }

    applyHttpHeaders(userAgent, headerMap)
  }

  /**
   * Sets HTTP headers for a specific URI (used for playlist items).
   * Automatically extracts and sets the Referer header based on the URI origin.
   *
   * @param uri The URI to extract referer from and set headers for
   */
  private fun setHttpHeadersForUri(uri: Uri) {
    if (!HttpUtils.isNetworkStream(uri)) {
      applyHttpHeaders(userAgent = null, headers = emptyMap())
      return
    }

    val headerMap = mutableMapOf<String, String>()
    val playlistItem = getPlaylistItemByUri(uri)

    // Automatically extract and set referer domain from the URI
    HttpUtils.extractRefererDomain(uri)?.let { referer ->
      headerMap["Referer"] = referer
      Log.d(TAG, "Auto-detected Referer for playlist item: $referer")
    }

    applyHttpHeaders(getEffectiveUserAgent(playlistItem), headerMap)
  }

  /**
   * Parses the file path from the intent.
   *
   * This method checks the intent action and data to determine the file path.
   * It supports the following actions:
   * - ACTION_VIEW: The file path is contained in the intent data.
   * - ACTION_SEND: The file path is contained in the intent extras.
   *
   * @param intent The intent containing the file URI
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromIntent(intent: Intent): String? =
    intent.getStringExtra("local_media_path")
      ?.takeIf { path -> File(path).isFile }
      ?: when (intent.action) {
        Intent.ACTION_VIEW -> intent.data?.resolveUri(this)
        Intent.ACTION_SEND -> parsePathFromSendIntent(intent)
        else -> intent.getStringExtra("uri")
      }

  /**
   * Parses the file path from a SEND intent.
   *
   * This method checks the intent extras for the file path.
   *
   * @param intent The SEND intent
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromSendIntent(intent: Intent): String? =
    if (intent.hasExtra(Intent.EXTRA_STREAM)) {
      val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
      }
      uri?.resolveUri(this@PlayerActivity)
    } else {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        val uri = text.trim().toUri()
        if (uri.isHierarchical && !uri.isRelative) {
          uri.resolveUri(this)
        } else {
          null
        }
      }
    }

  /**
   * Extracts and resolves the file name from the intent.
   *
   * @param intent The intent containing the file URI
   * @return The display name of the file, or empty string if not found
   */
  private fun getFileName(intent: Intent): String {
    // First check if a custom title/filename was provided via intent extras
    intent.getStringExtra("title")?.let { return it }
    intent.getStringExtra("filename")?.let { return it }

    val uri = extractUriFromIntent(intent) ?: return ""

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Extracts filename from URI, handling URL encoding and network URLs properly.
   * For network streams, returns a temporary name that will be updated async via HTTP headers.
   *
   * @param uri The URI to extract filename from
   * @return The extracted filename
   */
  private fun extractFileNameFromUri(uri: Uri): String {
    // For HTTP/HTTPS URLs, extract from path (will be updated async via HTTP headers)
    if (HttpUtils.isNetworkStream(uri)) {
      // Get the last path segment and decode URL encoding
      val path = uri.path ?: return uri.host ?: "Network Stream"
      val lastSegment = path.substringAfterLast("/")

      if (lastSegment.isNotBlank()) {
        // Decode URL encoding (e.g., %20 -> space)
        return try {
          java.net.URLDecoder.decode(lastSegment, "UTF-8")
            .substringBefore("?") // Remove query parameters
            .substringBefore("#") // Remove fragments (only for network streams)
            .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
        } catch (e: Exception) {
          lastSegment
            .substringBefore("?")
            .substringBefore("#")
        }
      }

      // If no filename in path, use hostname
      return uri.host ?: "Network Stream"
    }

    // For file:// and content:// URIs - preserve # characters as they're part of the filename
    val lastSegment = uri.lastPathSegment?.substringAfterLast("/") ?: uri.path ?: "Unknown Video"
    
    // For local files, only decode URL encoding but preserve # characters
    return try {
      java.net.URLDecoder.decode(lastSegment, "UTF-8")
    } catch (e: Exception) {
      lastSegment
    }
  }

  /**
   * Gets the display title for a playlist item URI.
   * If Room metadata exists for the current playlist, the stored playlist item title wins.
   *
   * @param uri The URI to get the title for
   * @return The display name/title of the file
   */
  internal fun getPlaylistItemTitle(uri: Uri): String {
    getPlaylistItemByUri(uri)?.fileName?.takeIf { it.isNotBlank() }?.let { return it }

    val idx = playlist.indexOf(uri)
    if (idx != -1 && idx < networkPlaylistTitles.size) {
      networkPlaylistTitles[idx].takeIf { it.isNotBlank() }?.let { return it }
    }

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  private fun getPlaylistItemByIndex(index: Int): PlaylistItemEntity? = playlistItems.getOrNull(index)

  private fun getPlaylistItemByUri(uri: Uri): PlaylistItemEntity? {
    val currentItem = getPlaylistItemByIndex(playlistIndex)
    if (currentItem != null && isSameUriOrLocalPath(currentItem.filePath, uri)) {
      return currentItem
    }
    return playlistItems.firstOrNull { isSameUriOrLocalPath(it.filePath, uri) }
  }

  private fun isSameUriOrLocalPath(filePath: String, uri: Uri): Boolean {
    if (filePath == uri.toString()) return true
    val path1 = if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
      Uri.parse(filePath).extractLocalPath()
    } else {
      filePath
    }
    val path2 = if (uri.scheme == "content" || uri.scheme == "file") {
      uri.extractLocalPath()
    } else {
      uri.toString()
    }
    return path1 != null && path2 != null && path1 == path2
  }

  private fun getEffectiveUserAgent(item: PlaylistItemEntity?): String? =
    item?.userAgent?.takeIf { it.isNotBlank() }
      ?: playlistEntity?.userAgent?.takeIf { it.isNotBlank() }

  private fun applyHttpHeaders(userAgent: String?, headers: Map<String, String>) {
    val headersString =
      headers.entries.joinToString(",") { (key, value) ->
        "${key}: ${value.replace(",", "\\,")}"
      }
    MpvSessionCoordinator.execute(playerSessionId, "http_headers") {
      MPVLib.setPropertyStringResult("user-agent", userAgent.orEmpty())
      MPVLib.setPropertyStringResult("http-header-fields", headersString)
    }

    if (userAgent != null || headers.isNotEmpty()) {
      Log.d(TAG, "Applied HTTP headers (ua=${userAgent != null}, count=${headers.size})")
    }
  }

  private fun getPreferredCurrentTitle(): String =
    getPlaylistItemByIndex(playlistIndex)?.fileName?.takeIf { it.isNotBlank() } ?: fileName

  private fun shouldForceCurrentMediaTitle(): Boolean =
    getPlaylistItemByIndex(playlistIndex)?.fileName?.isNotBlank() == true ||
      getExplicitIntentTitle() != null ||
      (!isCurrentStreamM3U() && !HttpUtils.shouldPreferResolvedMediaTitle(extractUriFromIntent(intent), fileName))

  private fun getExplicitIntentTitle(): String? =
    intent.getStringExtra("title")?.takeIf { !HttpUtils.isLikelyJunkTitle(it) }
      ?: intent.getStringExtra("filename")?.takeIf { !HttpUtils.isLikelyJunkTitle(it) }

  /**
   * Plays a playlist item by index.
   *
   * @param index The index of the playlist item to play
   */
  internal fun playPlaylistItem(index: Int) {
    if (index in playlist.indices) {
      loadPlaylistItem(index)
    }
  }

  /**
   * Extracts the URI from the intent based on intent type.
   *
   * @param intent The intent to extract URI from
   * @return The extracted URI, or null if not found
   */
  private fun extractUriFromIntent(intent: Intent): Uri? {
    if (intent.type == "text/plain") {
      return intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
    }

    val streamUri =
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
      }

    return intent.data ?: streamUri ?: intent.getStringExtra("uri")?.toUri()
  }

  /**
   * Queries the content resolver to get the display name for a URI.
   *
   * @param uri The URI to query
   * @return The display name, or null if not found
   */
  private fun getDisplayNameFromUri(uri: Uri): String? =
    runCatching {
      contentResolver
        .query(
          uri,
          arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.onFailure { e ->
      Log.e(TAG, "Error getting display name from URI", e)
    }.getOrNull()

  /**
   * Converts the intent URI to a playable URI string for MPV.
   *
   * @param intent The intent containing the file URI
   * @return A playable URI string, or null if unable to resolve
   */
  private fun getPlayableUri(intent: Intent): String? {
    val uri = parsePathFromIntent(intent)
    if (uri == null) {
      Log.e(TAG, "Unable to resolve playable media URI: ${extractUriFromIntent(intent)}")
      viewModel.onVideoLoadCompleted()
      viewModel.showToast(getString(R.string.toast_playback_load_failed))
      return null
    }
    return if (uri.startsWith("content://")) {
      uri.toUri().openContentFd(this)
    } else {
      uri
    }
  }

  /**
   * Handles device configuration changes.
   *
   * @param newConfig The new configuration
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    val isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
    viewModel.onOrientationChanged(isPortrait)
    if (isReady) {
      handleConfigurationChange()
    }
  }

  /**
   * Handles configuration changes by updating video aspect ratio.
   */
  private fun handleConfigurationChange() {
    if (!isInPictureInPictureMode) {
      // Configuration changes don't affect aspect ratio
    } else {
      viewModel.hideControls()
    }
  }

  // ==================== MPV Event Observers ====================

  /**
   * Observer callback for MPV property changes (Long values).
   * Handles video width and height changes.
   *
   * @param property The property name that changed
   * @param value The new Long value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: Long,
  ) {
    when (property) {
      "video-params/w",
      "video-params/h" -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return
        scheduleVideoParamRefresh(reloadShaders = true)
      }
    }
  }

  /**
   * Observer callback for MPV property changes (Boolean values).
   * Handles pause state and end-of-file events.
   *
   * @param property The property name that changed
   * @param value The new Boolean value
   */
  internal fun onObserverEvent(
    property: String,
    value: Boolean,
  ) {
    when (property) {
      "pause" -> {
        handlePauseStateChange(value)
        // Ensure isReady is set when playback starts
        if (!value && !isReady) {
          isReady = true
        }
      }
      "eof-reached" -> handleEndOfFile(value)
      "user-data/mpv/console/open" -> {
        if (!value) {
          if (advancedPreferences.enabledStatisticsPage.get() == 7) {
            advancedPreferences.enabledStatisticsPage.set(0)
          }
        } else {
          if (advancedPreferences.enabledStatisticsPage.get() != 7) {
            advancedPreferences.enabledStatisticsPage.set(7)
          }
        }
      }
    }
  }

  /**
   * Handles pause state changes by managing screen-on flag and MediaSession state.
   *
   * @param isPaused true if playback is paused, false if playing
   */
  private fun handlePauseStateChange(isPaused: Boolean) {
    MpvSessionCoordinator.markPlaying(playerSessionId, isPaused)
    if (isPaused) {
      // Only clear keep-screen-on if the preference is NOT enabled
      if (!playerPreferences.keepScreenOnWhenPaused.get()) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    } else {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    updateMediaSessionPlaybackState(!isPaused)
    runCatching {
      if (isInPictureInPictureMode) {
        pipHelper.updatePictureInPictureParams()
      }
    }.onFailure { /* Silently ignore PiP update failures */ }

    jellyfinSessionReporter?.let { reporter ->
      val currentPosMs = (viewModel.pos ?: 0).toLong() * 1000L
      reporter.reportPlaybackProgress(currentPosMs, isPaused)
    }
  }

  /**
   * Handles end-of-file event by playing next in playlist if available, otherwise finishing activity if configured.
   *
   * @param isEof true if end of file reached
   */
  private fun handleEndOfFile(isEof: Boolean) {
    if (!isEof) {
      eofAdvanceJob?.cancel()
      eofAdvanceJob = null
      isAdvancingAtEof = false
      return
    }
    if (isAdvancingAtEof) return

    val repeatMode = viewModel.repeatMode.value
    if (repeatMode == RepeatMode.ONE) {
      restartCurrentAtEof()
      return
    }

    val autoplay = playerPreferences.autoplayNextVideo.get()
    val repeatAll = repeatMode == RepeatMode.ALL

    if (playlist.isNotEmpty()) {
      ensureShuffleOrder()
      val hasNext =
        if (viewModel.shuffleEnabled.value) {
          shuffledPosition < shuffledIndices.lastIndex
        } else {
          playlistIndex < playlist.lastIndex
        }
      if ((autoplay && hasNext) || repeatAll) {
        isAdvancingAtEof = true
        playNext()
      } else {
        finishAtEofIfRequested()
      }
      return
    }

    if (playerPreferences.playlistMode.get() && (autoplay || repeatAll)) {
      val path = parsePathFromIntent(intent)
      if (path != null) {
        isAdvancingAtEof = true
        eofAdvanceJob = lifecycleScope.launch(Dispatchers.IO) {
          generatePlaylistFromFolderInternal(path)
          withContext(Dispatchers.Main) {
            ensureShuffleOrder()
            val hasNext =
              if (viewModel.shuffleEnabled.value) {
                shuffledPosition < shuffledIndices.lastIndex
              } else {
                playlistIndex < playlist.lastIndex
              }
            when {
              (autoplay && hasNext) || (repeatAll && playlist.isNotEmpty()) -> playNext()
              repeatAll -> restartCurrentAtEof()
              else -> finishAtEofIfRequested()
            }
          }
        }
        return
      }
    }

    if (repeatAll) restartCurrentAtEof() else finishAtEofIfRequested()
  }

  private fun restartCurrentAtEof() {
    isAdvancingAtEof = false
    MPVLib.command("seek", "0", "absolute")
    viewModel.unpause()
  }

  private fun finishAtEofIfRequested() {
    isAdvancingAtEof = false
    if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
      finishAndRemoveTask()
    }
  }

  /**
   * Observer callback for MPV property changes (String values).
   * Handles Lua script invocations.
   *
   * @param property The property name that changed
   * @param value The new String value
   */
  internal fun onObserverEvent(
    property: String,
    value: String,
  ) {
    when (property) {
      "sub-text" -> {
        if (isSecondarySubtitleActive()) {
          val primaryPosition = subtitlesPreferences.subPos.get()
          val width = player.width.takeIf { it > 0 }?.toFloat()
          val height = player.height.takeIf { it > 0 }?.toFloat()
          applySubtitlePositions(primaryPosition, width, height)
        }
      }
      else -> {
        when (property.substringBeforeLast("/")) {
          "user-data/mpvrx" -> viewModel.handleLuaInvocation(property, value)
        }
      }
    }
  }

  /**
   * Observer callback for MPV property changes (MPVNode values).
   *
   * This method is called when an MPV property (with MPVNode value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new MPVNode value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: MPVNode,
  ) {
    // Currently no MPVNode properties are handled
  }

  /**
   * Observer callback for MPV property changes (Double values).
   *
   * This method is called when an MPV property (with Double value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new Double value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: Double,
  ) {
    // Handle Double properties
    when (property) {
      "video-params/aspect" -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return
        lastVideoAspect = value.takeIf { it.isFinite() && it > 0.0 }
        scheduleVideoParamRefresh(reloadShaders = false)
      }
      "container-fps" -> {
        if (!mpvInitialized || player.isExiting || isFinishing) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
          value.isFinite() &&
          value > 0.0
        ) {
          try {
            player.applyFrameRate(value)
          } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set frame rate", e)
          }
        }
      }
      "sub-scale" -> {
        if (isSecondarySubtitleActive()) {
          val primaryPosition = subtitlesPreferences.subPos.get()
          val width = player.width.takeIf { it > 0 }?.toFloat()
          val height = player.height.takeIf { it > 0 }?.toFloat()
          applySubtitlePositions(primaryPosition, width, height)
        }
      }
    }
  }

  @Synchronized
  private fun scheduleVideoParamRefresh(reloadShaders: Boolean) {
    pendingVideoParamRefreshRequiresShaderReload =
      pendingVideoParamRefreshRequiresShaderReload || reloadShaders

    videoParamRefreshJob?.cancel()
    videoParamRefreshJob =
      lifecycleScope.launch {
        delay(100)
        if (!mpvInitialized || player.isExiting || isFinishing) return@launch

        val aspect = MpvSessionCoordinator.query(playerSessionId, "video_aspect") {
          player.getVideoOutAspect()
        }
        lastVideoAspect = aspect
        Log.d(TAG, "Coalesced video params refresh, aspect: $aspect")
        pipHelper.updatePictureInPictureParams()

        val aspectOverride = MpvSessionCoordinator.query(playerSessionId, "video_aspect_override") {
          MPVLib.getPropertyDouble("video-aspect-override") ?: -1.0
        } ?: -1.0
        if (playerPreferences.orientation.get() == PlayerOrientation.Video &&
          aspect != null &&
          aspectOverride <= 0.0
        ) {
          setOrientation()
        }

        if (pendingVideoParamRefreshRequiresShaderReload) {
          pendingVideoParamRefreshRequiresShaderReload = false
          withContext(playbackRenderDispatcher) {
            player.applyAnime4KShaders()
            viewModel.restartHdrScreenOutputAndAmbientIfActive()
          }
        }
      }
  }

  /**
   * Observer callback for MPV property changes (no value parameter).
   * Handles properties with no value parameter.
   *
   * @param property The property name that changed
   */
  internal fun onObserverEvent(property: String) {
    // Currently no properties use this signature
  }

  /**
   * Handles MPV core events such as file loaded and playback restart.
   *
   * Called by the player when critical playback events occur.
   *
   * @param eventId The MPV event ID
   */
  internal fun event(eventId: Int) {
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
        eofAdvanceJob?.cancel()
        eofAdvanceJob = null
        isAdvancingAtEof = false
        isReady = true
        MpvSessionCoordinator.markPlaying(playerSessionId, paused = false)
        if (playWhenFileLoaded) {
          playWhenFileLoaded = false
          requestAudioFocus()
          MPVLib.setPropertyBoolean("pause", false)
        }
        viewModel.onVideoLoadCompleted()
        handleFileLoaded()
        logPlaybackPipelineAsync()
      }

      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
        isAdvancingAtEof = false
        if (!isReady) {
          isReady = true
        }
        viewModel.onVideoLoadCompleted()
      }
    }
  }

  /**
   * Handles the file loaded event from MPV.
   * Initializes playback state, loads saved playback data, restores custom settings,
   * applies user preferences, and sets up metadata and media session.
   */
  private fun handleFileLoaded() {
    // Extract fileName from intent only if not already set
    // This preserves fileName set in onNewIntent or onCreate
    if (fileName.isBlank()) {
      fileName = getFileName(intent)
      // Ensure fileName is not blank - use a fallback if necessary
      if (fileName.isBlank()) {
        fileName = intent.data?.lastPathSegment ?: "Unknown Video"
      }
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    } else if (mediaIdentifier.isBlank()) {
      // If fileName was already set, but mediaIdentifier is missing, set it for safety
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    }

    if (serviceBound || mediaPlaybackService != null) {
      syncBackgroundPlaybackService(updateThumbnail = true)
    }

    val currentUri =
      if (playlist.isNotEmpty() && playlistIndex in playlist.indices) {
        playlist[playlistIndex]
      } else {
        extractUriFromIntent(intent)
      }
    currentUri?.let { viewModel.calculateVideoHash(it) }

    reportJellyfinStop()
    currentUri?.toString()?.let { url ->
      jellyfinSessionReporter = JellyfinSessionReporter.create(url, lifecycleScope)
      jellyfinSessionReporter?.reportPlaybackStart((viewModel.pos ?: 0).toLong() * 1000L)
      startJellyfinProgressLoop()
    }

    // Reset AB loop values when video changes
    viewModel.clearABLoop()

    // Drop the old ambient shader file, but keep the user's ambient preference/style.
    viewModel.prepareAmbientForNewVideo()

    setIntentExtras(intent.extras)

    lifecycleScope.launch(Dispatchers.IO) {
      // Load playback state (will skip track restoration if preferred language configured)
      val hasState = loadVideoPlaybackState(fileName)

      // Apply track selection logic (defaults only apply when no saved state)
      trackSelector.onFileLoaded(hasState)

      // Apply default zoom only if there's no saved state
      if (!hasState) {
        withContext(Dispatchers.Main) {
          val zoomPreference = playerPreferences.defaultVideoZoom.get()
          MPVLib.setPropertyDouble("video-zoom", zoomPreference.toDouble())
          viewModel.setVideoZoom(zoomPreference)
        }
      }
    }

    // Save to recently played when video actually loads and plays
    lifecycleScope.launch(Dispatchers.IO) {
      if (playlist.isNotEmpty()) {
        // For playlist items, save using the current URI
        // All items are loaded, so playlistIndex is the direct index
        if (playlistIndex >= 0 && playlistIndex < playlist.size) {
          saveRecentlyPlayedForUri(playlist[playlistIndex], fileName)
        } else {
          Log.w(TAG, "Cannot save recently played: invalid playlist index $playlistIndex (playlist size: ${playlist.size})")
        }
      } else {
        // For non-playlist videos, use the original saveRecentlyPlayed
        saveRecentlyPlayed()
      }
    }

    // Only set orientation immediately if NOT in Video mode
    // For Video mode, wait for video-params/aspect to become available
    if (playerPreferences.orientation.get() != PlayerOrientation.Video) {
      setOrientation()
    } else {
      // For Video mode, try to set orientation after a short delay to ensure
      // video dimensions are available
      lifecycleScope.launch {
        kotlinx.coroutines.delay(100)
        if (mpvInitialized && !player.isExiting && !isFinishing) {
          val aspect = MpvSessionCoordinator.query(playerSessionId, "delayed_video_aspect") {
            player.getVideoOutAspect()
          }
          lastVideoAspect = aspect
          Log.d(TAG, "handleFileLoaded - Video mode, aspect after delay: $aspect")
          if (aspect != null && aspect > 0) {
            setOrientation()
          }
        }
      }
    }

    // Audio track information becomes available only after FILE_LOADED. Re-apply
    // orientation once the track list settles so album art is not treated as video.
    lifecycleScope.launch {
      delay(100)
      if (mpvInitialized && !player.isExiting && !isFinishing) setOrientation()
    }

    applySubtitlePreferences()
    applyVideoFilterPreferences()
    viewModel.restoreSavedVideoAspect(showUpdate = false)

    if (shouldForceCurrentMediaTitle()) {
      val preferredTitle = getPreferredCurrentTitle()
      MPVLib.setPropertyString("force-media-title", preferredTitle)
      viewModel.setMediaTitle(preferredTitle)
    }

    viewModel.unpause()

    lifecycleScope.launch {
      withContext(playbackRenderDispatcher) {
        player.applyAnime4KShaders()
        viewModel.restartHdrScreenOutputAndAmbientIfActive()
      }
    }

    if (subtitlesPreferences.autoEnableSubtitles.get() && subtitlesPreferences.autoloadMatchingSubtitles.get()) {
      lifecycleScope.launch {
        // For network files played via proxy (SMB/WebDAV/FTP), use the original network file path
        val networkFilePath = intent.getStringExtra("network_file_path")
        val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

        if (networkFilePath != null && networkConnectionId != -1L) {
          // Pass network file path and connection ID for subtitle discovery
          SubtitleOps.autoloadSubtitles(
            videoFilePath = networkFilePath,
            videoFileName = fileName,
            networkConnectionId = networkConnectionId,
          )
        } else {
          // Regular file or direct network stream
          val filePath = parsePathFromIntent(intent)
          if (filePath != null) {
            SubtitleOps.autoloadSubtitles(
              videoFilePath = filePath,
              videoFileName = fileName,
            )
          }
        }
      }
    }

    updateMediaSessionMetadata(
      title = fileName,
      durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L,
    )
    updateMediaSessionPlaybackState(isPlaying = true)
    syncBackgroundPlaybackService(updateThumbnail = true)

    // Asynchronously fetch better filename from HTTP headers for network streams
    fetchNetworkStreamTitle()
  }

  /**
   * Fetches a better title from HTTP headers for network streams asynchronously.
   * Updates the title in UI, MPV, and media session if a better name is found.
   */
  private fun fetchNetworkStreamTitle() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val uri = extractUriFromIntent(intent)
        if (uri == null || !HttpUtils.isNetworkStream(uri)) {
          return@launch
        }

        // Skip fetching for m3u/m3u8 streams - let MPV provide the title
        if (isCurrentStreamM3U()) {
          Log.d(TAG, "Skipping title fetch for m3u/m3u8 stream: $uri")
          return@launch
        }

        // Skip fetching if title was provided in intent extras (e.g. from Jellyfin or other external launchers)
        // This prevents overwriting the correct title with a generic filename from the URL (like "stream")
        if (intent.hasExtra("title") || intent.hasExtra("filename")) {
          Log.d(TAG, "Skipping title fetch because title was explicitly provided in intent: $fileName")
          return@launch
        }

        // Skip fetching for local proxy URLs (SMB/WebDAV/FTP files)
        // These already have correct filename from intent extras
        val host = uri.host?.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
          Log.d(TAG, "Skipping title fetch for local proxy URL: $uri")
          return@launch
        }

        val url = uri.toString()
        Log.d(TAG, "Fetching title from network stream: $url")

        val betterFilename = HttpUtils.extractFilenameFromUrl(url)
        if (betterFilename != null && betterFilename.isNotBlank() &&
          betterFilename != fileName &&
          betterFilename != uri.host &&
          betterFilename != "Network Stream" &&
          !HttpUtils.isLikelyJunkTitle(betterFilename)
        ) {

          Log.d(TAG, "Found better filename from HTTP headers: $betterFilename")

          // Update fileName
          fileName = betterFilename

          // DO NOT update mediaIdentifier - keep the original identifier for playback state consistency
          // The URI hash in mediaIdentifier ensures position is saved/loaded correctly even if filename changes

          // Update MPV title
          withContext(Dispatchers.Main) {
            MPVLib.setPropertyString("force-media-title", fileName)
            viewModel.setMediaTitle(fileName)

            // Update media session
            val durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
            updateMediaSessionMetadata(
              title = fileName,
              durationMs = durationMs,
            )

            syncBackgroundPlaybackService(updateThumbnail = true)
          }

          // Update recently played with the parsed video title, duration, and file size
          val filePath = when (uri.scheme) {
            "file" -> uri.path ?: uri.toString()
            "content" -> {
              contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else null
              } ?: uri.toString()
            }

            else -> uri.toString()
          }

          // Get duration and file size from MPV on Main thread
          var updatedDuration = 0L
          var updatedFileSize = 0L
          var updatedWidth = 0
          var updatedHeight = 0
          withContext(Dispatchers.Main) {
            updatedDuration = runCatching {
              (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
            }.getOrDefault(0L)

            updatedFileSize = runCatching {
              MPVLib.getPropertyDouble("file-size")?.toLong()
                ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
                ?: 0L
            }.getOrDefault(0L)

            updatedWidth = runCatching {
              MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
            }.getOrDefault(0)

            updatedHeight = runCatching {
              MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
            }.getOrDefault(0)
          }

          // Update metadata without thumbnail
          runCatching {
            RecentlyPlayedOps.updateVideoMetadata(
              filePath,
              fileName,
              updatedDuration,
              updatedFileSize,
              updatedWidth,
              updatedHeight,
            )
            Log.d(
              TAG,
              "Updated recently played metadata: $fileName (duration: ${updatedDuration}ms, size: ${updatedFileSize}B, resolution: ${updatedWidth}x${updatedHeight}) for $filePath",
            )
          }.onFailure { e ->
            Log.e(TAG, "Error updating video metadata in recently played", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error fetching network stream title", e)
      }
    }
  }

  /**
   * Applies all saved subtitle preferences when a file is loaded.
   * This ensures subtitle customizations (font, colors, position, etc.) persist across videos.
   */
  private fun applySubtitlePreferences() {
    val font = subtitlesPreferences.font.get()
    val fontSize = subtitlesPreferences.fontSize.get()
    val bold = subtitlesPreferences.bold.get()
    val italic = subtitlesPreferences.italic.get()
    val justify = subtitlesPreferences.justification.get().value
    val borderStyle = subtitlesPreferences.borderStyle.get().value
    val borderSize = subtitlesPreferences.borderSize.get()
    val shadowOffset = subtitlesPreferences.shadowOffset.get()

    // Color settings
    val textColor = subtitlesPreferences.textColor.get().toColorHexString()
    val borderColor = subtitlesPreferences.borderColor.get().toColorHexString()
    val backgroundColor = subtitlesPreferences.backgroundColor.get().toColorHexString()
    val shadowColor = subtitlesPreferences.shadowColor.get().toColorHexString()

    // Miscellaneous settings
    val scaleByWindow = subtitlesPreferences.scaleByWindow.get()
    val scaleValue = if (scaleByWindow) "yes" else "no"
    val subScale = subtitlesPreferences.subScale.get()
    val blendMode = if (subtitlesPreferences.blendSubtitlesWithVideo.get() && playerPreferences.isAmbientEnabled.get()) "video" else "no"

    MPVLib.setPropertyString("blend-subtitles", blendMode)

    for (prefix in listOf("sub-", "secondary-sub-")) {
      MPVLib.setPropertyString("${prefix}font", font)
      MPVLib.setPropertyInt("${prefix}font-size", fontSize)
      MPVLib.setPropertyBoolean("${prefix}bold", bold)
      MPVLib.setPropertyBoolean("${prefix}italic", italic)
      MPVLib.setPropertyString("${prefix}justify", justify)
      MPVLib.setPropertyString("${prefix}border-style", borderStyle)
      MPVLib.setPropertyInt("${prefix}border-size", borderSize)
      MPVLib.setPropertyInt("${prefix}outline-size", borderSize)
      MPVLib.setPropertyInt("${prefix}shadow-offset", shadowOffset)
      MPVLib.setPropertyString("${prefix}color", textColor)
      MPVLib.setPropertyString("${prefix}border-color", borderColor)
      MPVLib.setPropertyString("${prefix}back-color", backgroundColor)
      MPVLib.setPropertyString("${prefix}shadow-color", shadowColor)
      MPVLib.setPropertyString("${prefix}scale-by-window", scaleValue)
      MPVLib.setPropertyString("${prefix}use-margins", scaleValue)
      MPVLib.setPropertyFloat("${prefix}scale", subScale)
    }

    applySubtitleLayout(
      primaryPosition = subtitlesPreferences.subPos.get(),
      forceAssOverride = subtitlesPreferences.overrideAssSubs.get(),
      screenWidth = player.width.takeIf { it > 0 }?.toFloat(),
      screenHeight = player.height.takeIf { it > 0 }?.toFloat(),
    )

    Log.d(TAG, "Applied subtitle preferences")
  }

  /**
   * Applies saved video filter preferences (brightness, contrast, etc.) when a file is loaded.
   */
  private fun applyVideoFilterPreferences() {
    VideoFilters.entries.forEach {
      MPVLib.setPropertyInt(it.mpvProperty, it.preference(decoderPreferences).get())
    }
    Log.d(TAG, "Applied video filter preferences")
  }

  /**
   * Helper extension function to convert Int color to hex string for MPV
   */
  private fun Int.toColorHexString(): String {
    val a = (this shr 24 and 0xFF).toString(16).padStart(2, '0')
    val r = (this shr 16 and 0xFF).toString(16).padStart(2, '0')
    val g = (this shr 8 and 0xFF).toString(16).padStart(2, '0')
    val b = (this and 0xFF).toString(16).padStart(2, '0')
    return "#$a$r$g$b".uppercase()
  }

  internal fun acceptsPlayerCallback(sessionId: Long): Boolean =
    sessionId == playerSessionId &&
      mpvInitialized &&
      !player.isExiting &&
      !isDestroyed &&
      MpvSessionCoordinator.acceptsCallbacks(sessionId)

  private fun canIssueMpvCommands(): Boolean = acceptsPlayerCallback(playerSessionId)

  /**
   * Saves the current playback state to the database.
   *
   * Captures MPV state synchronously, then persists on a background dispatcher.
   * This avoids shutdown races with MPV destruction and collapses duplicate writes.
   *
   * @param mediaTitle The title of the media being played
   */
  @OptIn(DelicateCoroutinesApi::class)
  private fun saveVideoPlaybackState(
    mediaTitle: String,
    immediate: Boolean = false,
  ) {
    if (mediaIdentifier.isBlank() || !canIssueMpvCommands() || !isReady) return
    savePlaybackStateJob?.cancel()
    savePlaybackStateJob =
      MpvSessionCoordinator.execute(
        playerSessionId,
        "capture_playback_state",
        drainDuringStop = true,
      ) {
        val snapshot = capturePlaybackStateSnapshot(mediaTitle) ?: return@execute
        val saveBlock: suspend kotlinx.coroutines.CoroutineScope.() -> Unit = {
          runCatching {
            if (!immediate) delay(250)
            val oldState = playbackStateRepository.getVideoDataByTitle(snapshot.mediaIdentifier)
            Log.d(TAG, "Saving playback state for: ${snapshot.mediaTitle} (identifier: ${snapshot.mediaIdentifier})")
            val playbackState =
              PlaybackStatePersistence.buildEntity(
                oldState = oldState,
                snapshot = snapshot,
                savePositionOnQuit = playerPreferences.savePositionOnQuit.get(),
                watchedThreshold = browserPreferences.watchedThreshold.get(),
              )
            playbackStateRepository.upsert(playbackState)
            PlaybackStateEvents.notifyChanged(snapshot.mediaIdentifier)
          }.onFailure { error ->
            Log.e(TAG, "Error saving playback state", error)
          }
        }
        if (immediate) {
          kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable, block = saveBlock)
        } else {
          lifecycleScope.launch(Dispatchers.IO, block = saveBlock)
        }
      }
  }

  private fun startJellyfinProgressLoop() {
    jellyfinProgressJob?.cancel()
    jellyfinProgressJob = lifecycleScope.launch {
      while (isActive) {
        delay(10000) // Report progress every 10 seconds
        val reporter = jellyfinSessionReporter ?: continue
        val currentPosMs = (viewModel.pos ?: 0).toLong() * 1000L
        val isPaused = viewModel.paused ?: false
        reporter.reportPlaybackProgress(currentPosMs, isPaused)
      }
    }
  }

  private fun reportJellyfinStop() {
    jellyfinProgressJob?.cancel()
    jellyfinProgressJob = null
    jellyfinSessionReporter?.let { reporter ->
      val currentPosMs = (viewModel.pos ?: 0).toLong() * 1000L
      reporter.reportPlaybackStop(currentPosMs)
      jellyfinSessionReporter = null
    }
  }

  private fun capturePlaybackStateSnapshot(mediaTitle: String): PlaybackStateSnapshot? {
    if (mediaIdentifier.isBlank() ||
      MpvSessionCoordinator.state(playerSessionId) in setOf(MpvSessionState.RELEASED, MpvSessionState.FAILED)
    ) return null

    return PlaybackStateSnapshot(
      mediaIdentifier = mediaIdentifier,
      mediaTitle = mediaTitle,
      currentPosition = readMpvIntSeconds("time-pos", viewModel.pos ?: 0),
      duration = readMpvIntSeconds("duration", viewModel.duration ?: 0),
      playbackSpeed = MPVLib.getPropertyDouble("speed") ?: DEFAULT_PLAYBACK_SPEED,
      videoZoom = MPVLib.getPropertyDouble("video-zoom")?.toFloat() ?: 0f,
      sid = player.sid,
      secondarySid = player.secondarySid,
      subDelayMs = ((MPVLib.getPropertyDouble("sub-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt(),
      subSpeed = MPVLib.getPropertyDouble("sub-speed") ?: DEFAULT_SUB_SPEED,
      aid = player.aid,
      audioDelayMs = ((MPVLib.getPropertyDouble("audio-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt(),
      externalSubtitles = viewModel.externalSubtitles.joinToString("|"),
    )
  }

  private fun readMpvIntSeconds(
    property: String,
    fallback: Int,
  ): Int =
    runCatching {
      MPVLib.getPropertyDouble(property)?.toInt()
        ?: MPVLib.getPropertyInt(property)
        ?: fallback
    }.getOrDefault(fallback)

  /**
   * Loads and applies saved playback state from the database.
   *
   * @param mediaTitle The title of the media being played
   * @return true if saved state was found and applied, false otherwise
   */
  private suspend fun loadVideoPlaybackState(mediaTitle: String): Boolean {
    if (mediaIdentifier.isBlank()) return false

    return runCatching {
      val state = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)

      applyPlaybackState(state)
      
      withContext(Dispatchers.Main) {
        applyDefaultSettings(state)
      }

      state != null
    }.onFailure { e ->
      Log.e(TAG, "Error loading playback state", e)
    }.getOrDefault(false)
  }

  /**
   * Applies saved playback state to MPV.
   *
   * Restores subtitle delay, audio delay, audio and track selections, and playback speed.
   * Also restores saved time position if enabled.
   *
   * @param state The saved playback state entity
   */
  private suspend fun applyPlaybackState(state: PlaybackStateEntity?) {
    if (state == null) return

    val subDelay = state.subDelay / DELAY_DIVISOR
    val audioDelay = state.audioDelay / DELAY_DIVISOR

    // Restore external subtitles first
    if (state.externalSubtitles.isNotBlank()) {
      val externalSubUris = state.externalSubtitles.split("|").filter { it.isNotBlank() }
      Log.d(TAG, "Restoring ${externalSubUris.size} external subtitle(s)")

      for (subUri in externalSubUris) {
        viewModel.addSubtitleSuspend(Uri.parse(subUri), select = false, silent = true)
      }
    }

    // Always restore subtitle and audio tracks from saved state
    // User's manual selection has highest priority
    if (state.sid > 0) {
      player.sid = state.sid
      Log.d(TAG, "Restored primary subtitle track: ${state.sid} (user selection)")
    }

    if (state.secondarySid > 0) {
      player.secondarySid = state.secondarySid
      Log.d(TAG, "Restored secondary subtitle track: ${state.secondarySid} (user selection)")
    }

    applySubtitleLayout(
      primaryPosition = subtitlesPreferences.subPos.get(),
      forceAssOverride = subtitlesPreferences.overrideAssSubs.get(),
      screenWidth = player.width.takeIf { it > 0 }?.toFloat(),
      screenHeight = player.height.takeIf { it > 0 }?.toFloat(),
    )

    if (state.aid > 0) {
      player.aid = state.aid
      Log.d(TAG, "Restored audio track: ${state.aid} (user selection)")
    }

    MPVLib.setPropertyDouble("sub-delay", subDelay)
    MPVLib.setPropertyDouble("speed", state.playbackSpeed)
    // Re-apply audio-pitch-correction after speed change, as mpv resets it to default
    MPVLib.setPropertyBoolean("audio-pitch-correction", audioPreferences.audioPitchCorrection.get())
    MPVLib.setPropertyDouble("audio-delay", audioDelay)
    MPVLib.setPropertyDouble("sub-speed", state.subSpeed)

    // Restore video zoom from saved state
    MPVLib.setPropertyDouble("video-zoom", state.videoZoom.toDouble())
    viewModel.setVideoZoom(state.videoZoom)

    if (playerPreferences.savePositionOnQuit.get() && state.lastPosition != 0) {
      MPVLib.setPropertyInt("time-pos", state.lastPosition)
    }
  }

  /**
   * Applies default settings when no saved state exists.
   *
   * Sets subtitle speed to user default if not present in saved state.
   *
   * @param state The saved playback state entity (null if no saved state)
   */
  private fun applyDefaultSettings(state: PlaybackStateEntity?) {
    if (state == null) {
      val defaultSubSpeed = subtitlesPreferences.defaultSubSpeed.get().toDouble()
      MPVLib.setPropertyDouble("sub-speed", defaultSubSpeed)
    }
  }

  /**
   * Saves the currently playing file to recently played history.
   *
   * Handles various URI schemes and infers launch source.
   */
  private suspend fun saveRecentlyPlayed() {
    runCatching {
      val uri = extractUriFromIntent(intent)

      if (uri == null) {
        Log.w(TAG, "Cannot save recently played: URI is null")
        return@runCatching
      }

      if (uri.scheme == null) {
        Log.w(TAG, "Cannot save recently played: URI has null scheme: $uri")
        return@runCatching
      }

      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      val launchSource =
        when {
          intent.getStringExtra("launch_source") != null -> intent.getStringExtra("launch_source")
          intent.action == Intent.ACTION_SEND -> "share"
          else -> "normal"
        }

      // Prioritize intent title first if provided and valid
      val intentTitle = intent.getStringExtra("title")
      
      // Get parsed video title from MPV
      val mpvTitle = runCatching {
        MPVLib.getPropertyString("media-title")
      }.getOrNull()

      val videoTitle = when {
        !HttpUtils.isLikelyJunkTitle(intentTitle) -> intentTitle
        !HttpUtils.isLikelyJunkTitle(mpvTitle) && mpvTitle != fileName -> mpvTitle
        else -> null
      }

      // Get duration and file size from MPV
      val duration = runCatching {
        (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      val fileSize = runCatching {
        // Try multiple properties to get file size
        MPVLib.getPropertyDouble("file-size")?.toLong()
          ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = fileName,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = launchSource,
      )

      Log.d(TAG, "Saved recently played: $filePath")
      Log.d(TAG, "  - fileName: $fileName")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - source: $launchSource")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played", e)
    }
  }

  // ==================== Intent and Result Management ====================

  /**
   * Sets the result intent with current playback position and duration.
   * Called when activity is finishing to return data to caller.
   */
  private fun setReturnIntent() {
    Log.d(TAG, "Setting return intent")

    val action = if ((callingPackage != null && callingPackage != packageName) ||
      intent.getBooleanExtra("return_result", false)) {
      "is.xyz.mpv.MPVActivity.result"
    } else {
      RESULT_INTENT
    }

    val resultIntent =
      Intent(action).apply {
        viewModel.pos?.let { putExtra("position", it * MILLISECONDS_TO_SECONDS) }
        viewModel.duration?.let { putExtra("duration", it * MILLISECONDS_TO_SECONDS) }
      }

    setResult(RESULT_OK, resultIntent)
  }

  /**
   * Handles new intents to load a different file without recreating the activity.
   *
   * @param intent The new intent
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)

    // Update the intent first so getFileName uses the new intent data
    setIntent(intent)
    if (isKnownAudioLaunch(intent)) setOrientation()

    when (intent.action) {
      MediaPlaybackService.ACTION_NOTIFICATION_PREVIOUS -> {
        playPrevious()
        return
      }
      MediaPlaybackService.ACTION_NOTIFICATION_NEXT -> {
        playNext()
        return
      }
      MediaPlaybackService.ACTION_OPEN_PLAYER -> {
        isBackgroundPlaybackSessionActive = false
        pendingBackgroundTransition = false
        isReady = true
        viewModel.onVideoLoadCompleted()
        endBackgroundPlayback()
        return
      }
    }

    isBackgroundPlaybackSessionActive = false
    pendingBackgroundTransition = false
    handledPipDismissal = false
    if (serviceBound || mediaPlaybackService != null || MediaPlaybackService.isRunning()) {
      endBackgroundPlayback()
    }

    // Check if this intent has playlist information
    val hasPlaylistExtras = intent.hasExtra("playlist_id") ||
      intent.hasExtra("playlist")

    // Load playlist from intent extras first (fast path)
    val playlistFromIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    // Only update playlist state if we have new playlist information
    // This prevents losing the playlist when coming back from notification/PiP
    if (hasPlaylistExtras || playlistFromIntent.isNotEmpty()) {
      val newPlaylistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
      playlistId = newPlaylistId
      playlistIndex = intent.getIntExtra("playlist_index", 0)
      playlistWindowOffset = 0
      playlistTotalCount = playlistFromIntent.size.takeIf { it > 0 } ?: -1
      playlist = playlistFromIntent
      playlistItems = emptyList()
      playlistEntity = null
      isM3uPlaylist = false
      loadNetworkPlaylistMetadata(intent)
      if (playlist.isNotEmpty()) {
        playlistIndex = playlistIndex.coerceIn(0, playlist.lastIndex)
        viewModel.refreshPlaylistItems()
      }
    }

    // If playlist is empty but playlist_id is provided, load from database
    if (playlist.isEmpty() && playlistId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          loadPlaylistById(
            pid = pid,
            sourceIntent = intent,
            logPrefix = "onNewIntent: Loaded",
          )
        } catch (e: Exception) {
          Log.e(TAG, "onNewIntent: Failed to load playlist from database", e)
        }
      }
    }

    // Auto-generate playlist from folder if playlist mode is enabled and no playlist_id
    if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
      val path = parsePathFromIntent(intent)
      if (path != null) {
        generatePlaylistFromFolder(path)
      }
    }

    // Extract the new fileName before loading the file
    fileName = getFileName(intent)
    if (fileName.isBlank()) {
      fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    }
    mediaIdentifier = getMediaIdentifier(intent, fileName)

    // Set HTTP headers (including referer) BEFORE loading the new file
    setHttpHeadersFromExtras(intent.extras)

    // Load the new file
    getPlayableUri(intent)?.let { uri ->
      // Remind user if they forgot to set up yt-dlp
      if (uri.startsWith("http") && !uri.substringAfterLast('/').contains('.')) {
        val ytdlDir = YtdlpManager.getYtdlDir(this)
        if (!File(ytdlDir, "yt-dlp").exists()) {
          viewModel.showToast(getString(R.string.toast_need_ytdl))
        }
      }

      currentPlayableUri = uri
      isReady = false
      viewModel.onVideoLoadStarted()
      val originalUri = extractUriFromIntent(intent)
      val shouldExpandM3u = M3uPlaybackPolicy.shouldExpandInApp(
        playableUri = uri,
        originalUri = originalUri?.toString(),
        fileName = fileName,
        mimeType = intent.type,
        hasExistingPlaylist = playlist.isNotEmpty(),
        hasPlaylistId = playlistId != null,
      )
      if (shouldExpandM3u) {
        startMediaLoad(
          playableUri = uri,
          originalUri = originalUri?.toString(),
          expandM3u = true,
          disableVideoOnFallback = true,
        )
      } else {
        startMediaLoad(uri)
      }
    }

  }

  private fun startMediaLoad(
    playableUri: String,
    originalUri: String? = null,
    expandM3u: Boolean = false,
    disableVideoOnFallback: Boolean = false,
  ) {
    mediaLoadJob?.cancel()
    compatibilityFallbackIndex = 0
    lastVideoAspect = null
    playWhenFileLoaded = true
    mediaLoadJob = lifecycleScope.launch(mediaLoadDispatcher) {
      try {
        if (expandM3u && loadDynamicM3uPlaylist(originalUri ?: playableUri)) {
          val targetIndex = playlistIndex.coerceIn(0, playlist.lastIndex)
          loadPlaylistItem(targetIndex)
          return@launch
        }

        withContext(Dispatchers.Main) { requestAudioFocus() }
        val videoMode = if (disableVideoOnFallback) "no" else "auto"
        val queued =
          MpvSessionCoordinator.execute(playerSessionId, "loadfile") {
            MpvSessionCoordinator.markLoading(playerSessionId)
            val loadResult = MPVLib.commandResult("loadfile", playableUri, "replace", "-1", "vid=$videoMode,pause=no")
            val pauseResult = MPVLib.setPropertyBooleanResult("pause", false)
            if (loadResult < 0 || pauseResult < 0) {
              Log.e(TAG, "session=$playerSessionId loadfileResult=$loadResult pauseResult=$pauseResult")
            }
          }
        if (queued == null) {
          throw IllegalStateException("MPV session is not accepting media loads")
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        playWhenFileLoaded = false
        isAdvancingAtEof = false
        Log.e(TAG, "Failed to load media URL", error)
        viewModel.onVideoLoadCompleted()
        viewModel.showToast(getString(R.string.toast_playback_load_failed))
      }
    }
  }

  private fun sanitizeInternalMpvConfig() {
    val config = File(filesDir, "mpv.conf")
    if (!config.isFile) return
    val original = config.readText()
    val result = MpvConfigSanitizer.sanitize(original)
    pendingConfigWarnings = result.warnings
    if (result.content != original) {
      config.writeText(result.content)
    }
    result.warnings.forEach { warning ->
      Log.w(TAG, "mpv.conf $warning")
    }
  }

  internal fun onImageReaderFailure(sessionId: Long, failures: Int, detail: String) {
    if (!acceptsPlayerCallback(sessionId)) return
    val fallback = PlaybackCompatibilityFallback.entries.getOrNull(compatibilityFallbackIndex)
    if (fallback == null) {
      Log.e(
        TAG,
        "session=$sessionId imageReaderFailures=$failures recovery=exhausted detail=$detail " +
          "device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT}",
      )
      return
    }

    val playableUri = currentPlayableUri ?: return
    val positionSeconds = (viewModel.pos ?: 0).coerceAtLeast(0)
    val surface = player.holder?.surface?.takeIf { it.isValid }
    compatibilityFallbackIndex++
    isReady = false
    viewModel.onVideoLoadStarted()
    val fallbackLabel =
      when (fallback) {
        PlaybackCompatibilityFallback.LEGACY_GPU -> "legacy gpu renderer"
        PlaybackCompatibilityFallback.MEDIACODEC_COPY -> "MediaCodec copy decoding"
        PlaybackCompatibilityFallback.SOFTWARE_DECODING -> "software decoding"
      }
    if (fallback == PlaybackCompatibilityFallback.LEGACY_GPU) {
      player.useLegacyGpuForSession()
    }
    Log.e(
      TAG,
      "session=$sessionId imageReaderFailures=$failures recovery=$fallback " +
        "device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT} " +
        "${PlayerDiagnostics.surfaceSummary(surface)} detail=$detail",
    )
    PlayerDiagnostics.logMemory(applicationContext, sessionId, "fallback_$fallback")
    viewModel.showToast("Playback compatibility fallback applied: $fallbackLabel")

    MpvSessionCoordinator.execute(sessionId, "compatibility_fallback:$fallback", timeoutMs = 10_000L) {
      MPVLib.commandResult("stop")
      when (fallback) {
        PlaybackCompatibilityFallback.LEGACY_GPU -> {
          MPVLib.setPropertyStringResult("vo", "gpu")
          MPVLib.setPropertyStringResult("gpu-api", "opengl")
        }
        PlaybackCompatibilityFallback.MEDIACODEC_COPY -> {
          MPVLib.setPropertyStringResult("hwdec", "mediacodec-copy")
        }
        PlaybackCompatibilityFallback.SOFTWARE_DECODING -> {
          MPVLib.setPropertyStringResult("hwdec", "no")
        }
      }
      if (surface?.isValid == true) {
        MPVLib.replaceSurface(surface)
      }
      MpvSessionCoordinator.markLoading(sessionId)
      val options = "start=$positionSeconds,pause=no"
      val result = MPVLib.commandResult("loadfile", playableUri, "replace", "-1", options)
      if (result < 0) {
        Log.e(TAG, "session=$sessionId recovery=$fallback loadfileResult=$result")
      }
    }
  }

  private fun startPlaybackWatchdog() {
    playbackWatchdogJob?.cancel()
    playbackWatchdogJob = lifecycleScope.launch {
      var lastPosition = Double.NaN
      var lastProgressAt = android.os.SystemClock.elapsedRealtime()
      var reported = false
      while (isActive) {
        delay(2_000L)
        val snapshot = MpvSessionCoordinator.query(playerSessionId, "playback_watchdog") {
          PlaybackWatchdogSnapshot(
            position = MPVLib.getPropertyDouble("time-pos"),
            paused = MPVLib.getPropertyBoolean("pause") == true,
            buffering = MPVLib.getPropertyBoolean("paused-for-cache") == true,
            seeking = MPVLib.getPropertyBoolean("seeking") == true,
          )
        } ?: continue
        val position = snapshot.position
        if (!isReady || snapshot.paused || snapshot.buffering || snapshot.seeking || position == null) {
          lastPosition = position ?: Double.NaN
          lastProgressAt = android.os.SystemClock.elapsedRealtime()
          reported = false
          continue
        }
        if (!lastPosition.isFinite() || kotlin.math.abs(position - lastPosition) >= 0.05) {
          lastPosition = position
          lastProgressAt = android.os.SystemClock.elapsedRealtime()
          reported = false
          continue
        }
        val stalledMs = android.os.SystemClock.elapsedRealtime() - lastProgressAt
        if (!reported && stalledMs >= FRAME_STALL_WARNING_MS) {
          reported = true
          Log.e(
            TAG,
            "watchdog=no_playback_progress session=$playerSessionId activity=$activityInstanceId " +
              "state=${MpvSessionCoordinator.state(playerSessionId)} stalledMs=$stalledMs " +
              PlayerDiagnostics.surfaceSummary(player.holder?.surface),
          )
          PlayerDiagnostics.logMemory(applicationContext, playerSessionId, "frame_stall")
        }
      }
    }
  }

  private fun logPlaybackPipelineAsync() {
    val sessionId = playerSessionId
    lifecycleScope.launch {
      val details = MpvSessionCoordinator.query(sessionId, "pipeline_diagnostics") {
        "renderer=${MPVLib.getPropertyString("current-vo") ?: MPVLib.getPropertyString("vo")} " +
          "gpuApi=${MPVLib.getPropertyString("gpu-api")} " +
          "hwdec=${MPVLib.getPropertyString("hwdec-current")} " +
          "videoCodec=${MPVLib.getPropertyString("video-codec")} " +
          "decoder=${MPVLib.getPropertyString("video-dec-params/codec")} " +
          "size=${MPVLib.getPropertyInt("video-params/w")}x${MPVLib.getPropertyInt("video-params/h")} " +
          "bitDepth=${MPVLib.getPropertyInt("video-params/component-bits")} " +
          "hdr=${MPVLib.getPropertyString("video-params/color-trc")}"
      } ?: return@launch
      Log.i(
        TAG,
        "session=$sessionId activity=$activityInstanceId state=${MpvSessionCoordinator.state(sessionId)} " +
          "thread=${Thread.currentThread().name} nativeHandle=session-owned " +
          "device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT} " +
          "${PlayerDiagnostics.surfaceSummary(player.holder?.surface)} $details",
      )
    }
  }

  // ==================== Picture-in-Picture Management ====================

  /**
   * Called when Picture-in-Picture mode changes.
   * Updates UI visibility and window configuration.
   *
   * @param isInPictureInPictureMode true if entering PiP, false if exiting
   * @param newConfig The new configuration
   */
  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

    pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode)
    if (isInPictureInPictureMode) {
      wasInPipMode = true
      handledPipDismissal = false
    }

    binding.controls.animate().cancel()
    if (isInPictureInPictureMode) {
      binding.controls.alpha = 0f
    }

    runCatching {
      if (isInPictureInPictureMode) {
        enterPipUIMode()
      } else {
        exitPipUIMode()
        if (ValueAnimator.areAnimatorsEnabled()) {
          binding.controls.alpha = 0f
          binding.controls.animate()
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(PathInterpolator(0.25f, 1f, 0.5f, 1f))
            .start()
        } else {
          binding.controls.alpha = 1f
        }
      }
    }.onFailure { e ->
      Log.e(TAG, "Error handling PiP mode change", e)
    }
  }

  /**
   * Configures window for Picture-in-Picture mode.
   * Shows system UI and navigation bars.
   */
  private fun enterPipUIMode() {
    cancelSystemBarsAutoHide()
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    WindowCompat.setDecorFitsSystemWindows(window, true)
    try {
      windowInsetsController.apply {
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to show system bars for PiP mode", e)
    }
  }

  /**
   * Restores window configuration when exiting Picture-in-Picture mode.
   * Hides system UI for immersive playback.
   */
  private fun exitPipUIMode() {
    setupWindowFlags()
    setupSystemUI()
  }

  /**
   * Enters Picture-in-Picture mode and hides all overlay controls.
   */
  fun enterPipModeHidingOverlay() {
    runCatching {
      enterPipUIMode()
    }.onFailure { e ->
      Log.e(TAG, "Error entering PiP mode with hidden overlay", e)
    }

    enterPipModeSmoothly()
  }

  private fun enterPipModeSmoothly() {
    binding.root.animate().cancel()
    binding.controls.animate().cancel()
    binding.root.scaleX = 1f
    binding.root.scaleY = 1f
    binding.root.translationX = 0f
    binding.controls.alpha = 0f
    pipHelper.updatePictureInPictureParams()
    pipHelper.enterPipMode()
  }

  // ==================== Orientation Management ====================

  /**
   * Sets the screen orientation based on user preferences.
   *
   * IMPORTANT: Preferences are the single source of truth for orientation.
   * This method applies the preference value when videos load.
   * The rotation button temporarily overrides this without changing preferences.
   *
   * For "Video" orientation mode, this will wait for video-params/aspect to update
   * to the correct orientation, starting with landscape as fallback.
   */
  private fun setOrientation() {
    if (isKnownAudioLaunch(intent) || viewModel.isAudioOnly.value) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      return
    }
    val orientationPref = playerPreferences.orientation.get()

    requestedOrientation =
      when (orientationPref) {
        PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        PlayerOrientation.Video -> {
          // For video orientation, check if aspect is available
          val aspect = lastVideoAspect
          Log.d(TAG, "setOrientation - Video mode: aspect=$aspect")
          if (aspect == null || aspect <= 0.0) {
            // Aspect not available yet - wait for video-params/aspect update
            Log.d(TAG, "setOrientation - Aspect not available, defaulting to landscape")
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
          } else {
            // Aspect available - set correct orientation now
            val orientation = if (aspect > 1.0) {
              Log.d(TAG, "setOrientation - Aspect $aspect > 1.0, setting landscape")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
              Log.d(TAG, "setOrientation - Aspect $aspect <= 1.0, setting portrait")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            orientation
          }
        }
        PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
  }

  private fun isKnownAudioLaunch(sourceIntent: Intent): Boolean =
    sourceIntent.getBooleanExtra("is_audio", false) || sourceIntent.type?.startsWith("audio/") == true

  // ==================== Key Event Handling ====================

  /**
   * Handles hardware key down events for player control.
   * Supports D-pad navigation, media keys, and volume controls.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
  override fun onKeyDown(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    val isTrackSheetOpen =
      viewModel.sheetShown.value == Sheets.SubtitleTracks ||
        viewModel.sheetShown.value == Sheets.AudioTracks
    val isNoSheetOpen = viewModel.sheetShown.value == Sheets.None

    // If any modifier keys are pressed, delegate to MPVView for proper modifier handling
    val modifierEvent = event?.takeIf {
      it.isShiftPressed || it.isCtrlPressed || it.isAltPressed || it.isMetaPressed
    }
    val hasModifiers = modifierEvent != null

    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_UP -> {
        if (hasModifiers) {
          player.onKey(modifierEvent)
          return true
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_LEFT,
        -> {
        // If modifiers are pressed, delegate to MPVView for proper handling (e.g. sub-step)
        if (hasModifiers) {
          player.onKey(modifierEvent)
          return true
        }

        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }

        if (isNoSheetOpen) {
          when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
              viewModel.handleRightDoubleTap()
              return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
              viewModel.handleLeftDoubleTap()
              return true
            }
          }
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
        if (hasModifiers) {
          player.onKey(modifierEvent)
          return true
        }
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_SPACE -> {
        if (hasModifiers) {
          player.onKey(modifierEvent)
          return true
        }
        viewModel.pauseUnpause()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        viewModel.changeVolumeBy(1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        viewModel.changeVolumeBy(-1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_STOP -> {
        finishAndRemoveTask()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_REWIND -> {
        viewModel.handleLeftDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
        viewModel.handleRightDoubleTap()
        return true
      }

      else -> {
        event?.let { player.onKey(it) }
        return super.onKeyDown(keyCode, event)
      }
    }
  }

  /**
   * Handles hardware key up events for player control.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  override fun onKeyUp(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    event?.let {
      if (player.onKey(it)) return true
    }
    return super.onKeyUp(keyCode, event)
  }

  // ==================== System UI Management ====================

  /**
   * Restores system UI to normal state (shows status and navigation bars).
   * Called when finishing the activity to return to normal Android UI.
   */

  // ==================== MediaSession ====================

  /**
   * Initializes MediaSession for integration with system media controls.
   * Supports Android Auto, Wear OS, Bluetooth controls, and notification controls.
   */
  private fun setupMediaSession() {
    runCatching {
      mediaSession =
        MediaSession(this, TAG).apply {
          setCallback(
            object : MediaSession.Callback() {
              override fun onPlay() {
                viewModel.unpause()
                updateMediaSessionPlaybackState(isPlaying = true)
              }

              override fun onPause() {
                viewModel.pause()
                updateMediaSessionPlaybackState(isPlaying = false)
              }

              override fun onSeekTo(pos: Long) {
                viewModel.seekTo((pos / 1000).toInt())
                updateMediaSessionPlaybackState(isPlaying = viewModel.paused == false)
              }
            },
          )
          isActive = true
        }
      playbackStateBuilder =
        PlaybackState
          .Builder()
          .setActions(
            PlaybackState.ACTION_PLAY or
              PlaybackState.ACTION_PAUSE or
              PlaybackState.ACTION_PLAY_PAUSE or
              PlaybackState.ACTION_SEEK_TO,
          )
      mediaSessionInitialized = true
    }.onFailure { e ->
      Log.e(TAG, "Failed to initialize MediaSession", e)
      mediaSessionInitialized = false
    }
  }

  /**
   * Updates MediaSession playback state (playing/paused).
   *
   * @param isPlaying true if currently playing, false if paused
   */
  private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
    if (!mediaSessionInitialized) return
    runCatching {
      val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
      val positionMs = (viewModel.pos ?: 0) * 1000L
      mediaSession.setPlaybackState(
        playbackStateBuilder
          .setState(state, positionMs, if (isPlaying) 1.0f else 0f)
          .build(),
      )
    }.onFailure { e -> Log.e(TAG, "Error updating playback state", e) }
  }

  /**
   * Updates MediaSession metadata (title, duration, etc.).
   *
   * @param title The media title
   * @param durationMs The media duration in milliseconds
   */
  private fun updateMediaSessionMetadata(
    title: String,
    durationMs: Long,
  ) {
    if (!mediaSessionInitialized) return
    runCatching {
      val metadata =
        MediaMetadata
          .Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, title)
          .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
          .build()
      mediaSession.setMetadata(metadata)
    }.onFailure { e -> Log.e(TAG, "Error updating metadata", e) }
  }

  /**
   * Releases MediaSession resources.
   * Called during activity cleanup.
   */
  private fun releaseMediaSession() {
    if (!mediaSessionInitialized) return
    runCatching {
      mediaSession.isActive = false
      mediaSession.release()
    }.onFailure { e -> Log.e(TAG, "Error releasing MediaSession", e) }
    mediaSessionInitialized = false
  }

  // ==================== Background Playback Service ====================

  /**
   * Service connection for binding to background playback service.
   */
  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
      ) {
        val binder = service as? MediaPlaybackService.MediaPlaybackBinder ?: return
        mediaPlaybackService = binder.getService()
        serviceBound = true
        Log.d(TAG, "Service connected")
        syncBackgroundPlaybackService(updateThumbnail = false)
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "Service disconnected")
        mediaPlaybackService = null
        serviceBound = false
      }
    }

  /**
   * Starts the background playback service and binds to it.
   *
   * This should only be called if a video is loaded and playback is initialized.
   * Responsible for starting and binding to the MediaPlaybackService, which
   * handles background playback.
   */
  private fun startBackgroundPlayback(allowUserPrompt: Boolean = true): BackgroundPlaybackStartResult {
    pendingBackgroundPlaybackStart = true

    if (!shouldShowPlaybackNotification()) {
      pendingBackgroundPlaybackStart = false
      Log.d(TAG, "Playback notification disabled, skipping background playback service")
      return BackgroundPlaybackStartResult.Blocked
    }

    when (ensureNotificationAccessForPlayback(allowUserPrompt)) {
      BackgroundPlaybackStartResult.Started -> Unit
      BackgroundPlaybackStartResult.PendingPermission -> return BackgroundPlaybackStartResult.PendingPermission
      BackgroundPlaybackStartResult.Blocked -> {
        pendingBackgroundPlaybackStart = false
        return BackgroundPlaybackStartResult.Blocked
      }
    }

    pendingBackgroundPlaybackStart = false
    return if (startBackgroundPlaybackInternal(bindToActivity = false)) {
      BackgroundPlaybackStartResult.Started
    } else {
      BackgroundPlaybackStartResult.Blocked
    }
  }

  private fun startBackgroundPlaybackInternal(bindToActivity: Boolean): Boolean {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot start background playback: video not ready")
      return false
    }

    // Prevent starting service multiple times
    if (bindToActivity && serviceBound) {
      Log.d(TAG, "Service already bound, skipping start")
      return true
    }

    Log.d(TAG, "Starting background playback for: $fileName")
    
    // Ensure notification channel exists
    MediaPlaybackService.createNotificationChannel(this)
    
    // Get media info before starting service
    val artist = runCatching { MPVLib.getPropertyString("metadata/artist") }.getOrNull() ?: ""
    
    // Pass media info via intent extras
    val intent = Intent(this, MediaPlaybackService::class.java).apply {
      putExtra("media_title", fileName)
      putExtra("media_artist", artist)
      putExtra("media_uri", currentPlayableUri)
      putExtra("media_identifier", mediaIdentifier)
    }
    
    try {
      startForegroundService(intent)
      if (bindToActivity) {
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        Log.d(TAG, "Service start and bind initiated")
      } else {
        Log.d(TAG, "Service start initiated")
      }
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Error starting/binding service", e)
      return false
    }
  }

  private fun ensureNotificationAccessForPlayback(allowUserPrompt: Boolean): BackgroundPlaybackStartResult {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      if (!allowUserPrompt) return BackgroundPlaybackStartResult.Blocked
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      return BackgroundPlaybackStartResult.PendingPermission
    }

    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      if (!allowUserPrompt) return BackgroundPlaybackStartResult.Blocked
      Toast.makeText(
        this,
        getString(R.string.notification_permission_disabled),
        Toast.LENGTH_LONG,
      ).show()
      openNotificationSettings()
      return BackgroundPlaybackStartResult.Blocked
    }

    return BackgroundPlaybackStartResult.Started
  }

  private fun openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
      putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
    runCatching { startActivity(intent) }
      .onFailure { Log.e(TAG, "Failed to open notification settings", it) }
  }

  /**
   * Stops the background playback service and unbinds from it.
   *
   * Called when the activity is destroyed to remove the notification.
   */
  private fun endBackgroundPlayback() {
    Log.d(TAG, "Ending background playback service")
    isBackgroundPlaybackSessionActive = false
    pendingBackgroundTransition = false
    pendingBackNavigationBackgroundTransition = false
    
    if (serviceBound) {
      try {
        unbindService(serviceConnection)
        Log.d(TAG, "Service unbound successfully")
      } catch (e: Exception) {
        Log.e(TAG, "Error unbinding service", e)
      }
      serviceBound = false
    }
    
    // Stop the service which will trigger its onDestroy and cleanup
    try {
      stopService(Intent(this, MediaPlaybackService::class.java))
      Log.d(TAG, "Stop service command sent")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping service", e)
    }
    
    mediaPlaybackService = null
  }

  /** Uses the same persistent setting as Settings > Audio. */
  fun toggleBackgroundPlayback() {
    val enabled = !audioPreferences.backgroundPlayback.get()
    audioPreferences.backgroundPlayback.set(enabled)

    if (!enabled) {
      pendingBackgroundTransition = false
      isBackgroundPlaybackSessionActive = false
      endBackgroundPlayback()
      enableVideoAfterBackground()
      viewModel.showToast("Background playback off")
      return
    }

    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot start background playback: media not ready")
      viewModel.showToast("Background playback on")
      return
    }

    Log.d(TAG, "Background playback enabled from player controls")
    when (startBackgroundPlayback()) {
      BackgroundPlaybackStartResult.Started -> {
        isBackgroundPlaybackSessionActive = true
        viewModel.showToast("Background playback on")
      }
      BackgroundPlaybackStartResult.PendingPermission -> pendingBackgroundTransition = true
      BackgroundPlaybackStartResult.Blocked -> {
        audioPreferences.backgroundPlayback.set(false)
        isBackgroundPlaybackSessionActive = false
        pendingBackgroundTransition = false
      }
    }
  }

  private fun finishIntoBackgroundPlayback() {
    isBackgroundPlaybackSessionActive = true
    pendingBackNavigationBackgroundTransition = false
    disableVideoForBackground()
    isUserFinishing = true
    finish()
  }

  // ==================== PlayerHost ====================
  override val context: Context
    get() = this
  override val windowInsetsController: WindowInsetsControllerCompat
    get() = WindowCompat.getInsetsController(window, window.decorView)
  override val hostWindow: android.view.Window
    get() = window
  override val hostWindowManager: WindowManager
    get() = windowManager
  override val hostContentResolver: android.content.ContentResolver
    get() = contentResolver
  override val audioManager: AudioManager
    get() = getSystemService(AUDIO_SERVICE) as AudioManager
  private val keyguardManager: KeyguardManager
    get() = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
  override var hostRequestedOrientation: Int
    get() = requestedOrientation
    set(value) {
      requestedOrientation = value
    }

  // ==================== Playlist Management ====================

  /**
   * Check if there's a next video in the playlist
   */
  fun hasNext(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "next" (loops back to beginning)
    if (viewModel.shouldRepeatPlaylist()) return true

    ensureShuffleOrder()
    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition < shuffledIndices.size - 1
    } else {
      playlistIndex < playlist.lastIndex
    }
  }

  /**
   * Check if there's a previous video in the playlist
   */
  fun hasPrevious(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "previous" (loops back to end)
    if (viewModel.shouldRepeatPlaylist()) return true

    ensureShuffleOrder()
    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition > 0
    } else {
      playlistIndex > 0
    }
  }

  /**
   * Generate shuffled indices for the playlist
   */
  private fun generateShuffledIndices() {
    if (playlist.isEmpty()) return

    // Create a list of all indices except the current one
    val indices = playlist.indices.filter { it != playlistIndex }.toMutableList()
    indices.shuffle()

    // Put current index at the beginning
    shuffledIndices = listOf(playlistIndex) + indices
    shuffledPosition = 0
  }

  private fun ensureShuffleOrder() {
    if (viewModel.shuffleEnabled.value && playlist.isNotEmpty() && shuffledIndices.isEmpty()) {
      generateShuffledIndices()
    }
  }

  /**
   * Called when shuffle is toggled on/off
   */
  fun onShuffleToggled(enabled: Boolean) {
    if (enabled && playlist.isNotEmpty()) {
      generateShuffledIndices()
    } else {
      shuffledIndices = emptyList()
      shuffledPosition = 0
    }
  }

  /**
   * Play the next video in the playlist
   */
  fun playNext() {
    if (playlist.isEmpty()) return

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to next position
      if (shuffledPosition < shuffledIndices.size - 1) {
        shuffledPosition++
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of shuffled playlist with repeat ALL: regenerate and restart
        generateShuffledIndices()
        shuffledPosition = 0
        playlistIndex = shuffledIndices[0]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex < playlist.lastIndex) {
        playlistIndex++
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of playlist with repeat ALL: restart from beginning
        playlistIndex = 0
        loadPlaylistItem(0)
      }
    }
  }

  /**
   * Play the previous video in the playlist
   */
  fun playPrevious() {
    if (playlist.isEmpty()) return

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to previous position
      if (shuffledPosition > 0) {
        shuffledPosition--
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of shuffled playlist with repeat ALL: go to end
        shuffledPosition = shuffledIndices.size - 1
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex > 0) {
        playlistIndex--
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of playlist with repeat ALL: go to last item
        playlistIndex = playlist.lastIndex
        loadPlaylistItem(playlistIndex)
      }
    }
  }

  /**
   * Load a playlist item by index
   */
  private fun loadPlaylistItem(index: Int) {
    // All items are loaded - just validate index and load directly
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }
    loadPlaylistItemInternal(index)
  }

  /**
   * Internal method to load a playlist item
   */
  private fun loadPlaylistItemInternal(index: Int) {
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }

    // Save current video's playback state before switching
    if (fileName.isNotBlank()) {
      saveVideoPlaybackState(fileName)
      reportJellyfinStop()
    }

    val uri = playlist[index]
    val playableUri = uri.openContentFd(this) ?: uri.toString()
    currentPlayableUri = uri.toString()
    val networkFilePath = networkPlaylistPaths.getOrNull(index)?.takeIf { it.isNotBlank() }
    val networkTitle = networkPlaylistTitles.getOrNull(index)?.takeIf { it.isNotBlank() }

    // Update playlist index
    playlistIndex = index
    viewModel.calculateVideoHash(uri)

    // Extract and set the new file name
    fileName = getPlaylistItemByIndex(index)?.fileName?.takeIf { it.isNotBlank() }
      ?: networkTitle
      ?: getFileNameFromUri(uri)
    // Generate new media identifier for playback state
    mediaIdentifier = if (networkFilePath != null && networkPlaylistConnectionId != -1L) {
      buildNetworkMediaIdentifier(networkPlaylistConnectionId, networkFilePath)
    } else {
      getMediaIdentifierFromUri(uri, fileName)
    }

    // Set HTTP headers (including referer) for network streams
    setHttpHeadersForUri(uri)

    // Update playlist play history if this is a custom playlist
    playlistId?.takeUnless(::isAllVideosPlaylist)?.let { id ->
      lifecycleScope.launch(Dispatchers.IO) {
        val playlistItem = getPlaylistItemByUri(uri)
        val filePath = playlistItem?.filePath ?: when (uri.scheme) {
          "file" -> uri.path ?: uri.toString()
          "content" -> {
            contentResolver.query(
              uri,
              arrayOf(MediaStore.MediaColumns.DATA),
              null,
              null,
              null,
            )?.use { cursor ->
              if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (columnIndex != -1) cursor.getString(columnIndex) else null
              } else null
            } ?: uri.toString()
          }

          else -> uri.toString()
        }

        runCatching {
          playlistRepository.updatePlayHistory(id, filePath)
          Log.d(TAG, "Updated playlist history for: $filePath in playlist $id")
        }.onFailure { e ->
          Log.e(TAG, "Error updating playlist history", e)
        }
      }
    }

    // Load the new video
    // Avoid blocking UI thread while mpv opens network streams (e.g., HLS).
    isAdvancingAtEof = true
    isReady = false
    viewModel.onVideoLoadStarted()

    startMediaLoad(playableUri)

    // Update media title (this will trigger UI update)
    val shouldForceTitle =
      getPlaylistItemByIndex(index)?.fileName?.isNotBlank() == true ||
        !(uri.toString().lowercase().contains(".m3u8") || uri.toString().lowercase().contains(".m3u"))
    if (shouldForceTitle) {
      MPVLib.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    // Update media session metadata
    lifecycleScope.launch {
      kotlinx.coroutines.delay(100) // Wait for MPV to load the file
      val durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
      updateMediaSessionMetadata(
        title = fileName,
        durationMs = durationMs,
      )
      syncBackgroundPlaybackService(updateThumbnail = true)
      // Refresh playlist items to update the currently playing indicator
      viewModel.refreshPlaylistItems()
    }
  }

  private fun syncBackgroundPlaybackService(updateThumbnail: Boolean) {
    val service = mediaPlaybackService ?: return
    val title = getPreferredCurrentTitle().ifBlank { fileName.ifBlank { "Unknown Video" } }
    val artist = runCatching { MPVLib.getPropertyString("metadata/artist") }.getOrNull() ?: ""
    val thumbnailKey = buildBackgroundThumbnailKey()
    val cachedThumbnail =
      if (thumbnailKey == lastBackgroundThumbnailKey) {
        lastBackgroundThumbnail
      } else {
        null
      }

    service.setMediaInfo(
      title = title,
      artist = artist,
      thumbnail = cachedThumbnail,
      uri = currentPlayableUri,
      identifier = mediaIdentifier,
    )
    service.setChapters(viewModel.chapters.value.map { ChapterNode(time = it.start, title = it.name) })

    if (!updateThumbnail || thumbnailKey.isBlank()) return
    if (thumbnailKey == lastBackgroundThumbnailKey && cachedThumbnail != null) return

    backgroundServiceSyncJob?.cancel()
    backgroundServiceSyncJob =
      lifecycleScope.launch {
        delay(150)
        val generatedThumbnail =
          withContext(Dispatchers.Default) {
            runCatching { MPVLib.grabThumbnail(480) }.getOrNull()
          }

        if (!mpvInitialized || player.isExiting || isFinishing) return@launch
        if (thumbnailKey != buildBackgroundThumbnailKey()) return@launch

        lastBackgroundThumbnailKey = thumbnailKey
        lastBackgroundThumbnail = generatedThumbnail
        mediaPlaybackService?.setMediaInfo(
          title = title,
          artist = artist,
          thumbnail = generatedThumbnail,
          uri = currentPlayableUri,
          identifier = mediaIdentifier,
        )
      }
  }

  private fun buildBackgroundThumbnailKey(): String {
    if (mediaIdentifier.isBlank()) return ""
    return "$mediaIdentifier|$playlistIndex"
  }

  /**
   * Get file name from URI (used for playlist items)
   */
  private fun getFileNameFromUri(uri: Uri): String {
    getDisplayNameFromUri(uri)?.let { return it }
    return extractFileNameFromUri(uri)
  }

  /**
   * Get the current video title for controls display.
   * Prefer an explicit intent title when one was supplied by the launcher.
   * For m3u/m3u8 streams, only uses MPV's media-title when it looks valid.
   */
  fun getTitleForControls(): String {
    getExplicitIntentTitle()?.let { return it }

    if (HttpUtils.shouldPreferResolvedMediaTitle(extractUriFromIntent(intent), fileName)) {
      MPVLib.getPropertyString("media-title")
        ?.takeIf { !HttpUtils.isLikelyJunkTitle(it) }
        ?.let { return it }
    }

    // For m3u/m3u8 streams, only trust MPV if it produced a real title.
    if (isCurrentStreamM3U()) {
      MPVLib.getPropertyString("media-title")
        ?.takeIf { !HttpUtils.isLikelyJunkTitle(it) }
        ?.let { return it }
    }
    return fileName.ifBlank { "Unknown Video" }
  }

  /**
   * Check if the currently playing media is an m3u or m3u8 stream.
   * Checks both the intent URI and the current playlist item if playing from a playlist.
   */
  private fun isCurrentStreamM3U(): Boolean {
    // First check the intent URI
    val uri = extractUriFromIntent(intent)
    if (uri != null && isUriM3U(uri)) {
      return true
    }

    // Also check the current playlist item if playing from a playlist
    if (playlist.isNotEmpty() && playlistIndex >= 0 && playlistIndex < playlist.size) {
      return isUriM3U(playlist[playlistIndex])
    }

    return false
  }

  /**
   * Check if a specific URI is an m3u or m3u8 file/stream.
   */
  private fun isUriM3U(uri: Uri): Boolean {
    val lowerUrl = uri.toString().lowercase()
    return lowerUrl.contains(".m3u8") || lowerUrl.contains(".m3u") ||
      lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".m3u")
  }

  /**
   * Save recently played for a specific URI
   */
  private suspend fun saveRecentlyPlayedForUri(
    uri: Uri,
    name: String,
  ) {
    runCatching {
      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      // Get parsed video title from MPV
      val videoTitle = runCatching {
        MPVLib.getPropertyString("media-title")
      }.getOrNull()?.takeIf { it.isNotBlank() && it != name }

      // Get duration and file size from MPV
      val duration = runCatching {
        (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      val fileSize = runCatching {
        // Try multiple properties to get file size
        MPVLib.getPropertyDouble("file-size")?.toLong()
          ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      val historyPlaylistId = playlistId?.takeUnless(::isAllVideosPlaylist)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = name,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = "playlist",
        playlistId = historyPlaylistId,
      )

      Log.d(TAG, "Saved recently played (playlist): $filePath")
      Log.d(TAG, "  - fileName: $name")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - playlistId: $historyPlaylistId")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played for playlist item", e)
    }
  }

  /**
   * Generate a unique identifier for this media for playback state/history.
   *
  * For local/offline files, uses fileName (display name or path).
  * For network streams via proxy (SMB/WebDAV/FTP), uses the stable network file path from intent extras.
  * For other network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
  */
  private fun getMediaIdentifier(intent: Intent, fileName: String): String {
    intent.getStringExtra("media_identifier")?.takeIf { it.isNotBlank() }?.let { return it }

    // Check if this is a network file played via proxy (SMB/WebDAV/FTP)
    // Use the stable network file path instead of the temporary proxy URL
    val networkFilePath = intent.getStringExtra("network_file_path")
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

    if (networkFilePath != null && networkConnectionId != -1L) {
      // For network files via proxy: use connection ID + file path for stable identifier
      val identifier = buildNetworkMediaIdentifier(networkConnectionId, networkFilePath)
      Log.d(
        TAG,
        "Using network file identifier: $identifier (connection: $networkConnectionId, path: $networkFilePath)",
      )
      return identifier
    }

    val uri = extractUriFromIntent(intent)
    return if (uri != null && (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms")) {
      // For remote protocols: hash the URI so position is per-episode or per-stream.
      "${fileName}_${uri.toString().hashCode()}"
    } else {
      // For local/file uris and unknown: just use fileName.
      fileName
    }
  }

  private fun loadNetworkPlaylistMetadata(intent: Intent) {
    networkPlaylistPaths = intent.getStringArrayListExtra("network_playlist_paths") ?: emptyList()
    networkPlaylistTitles = intent.getStringArrayListExtra("network_playlist_titles") ?: emptyList()
    networkPlaylistConnectionId = intent.getLongExtra("network_playlist_connection_id", -1L)
  }

  private fun buildNetworkMediaIdentifier(connectionId: Long, filePath: String): String =
    "network_${connectionId}_${filePath.hashCode()}"

  /**
   * Generate a unique identifier for this media from a URI and name.
   *
   * For local/offline files, uses fileName (display name or path).
   * For network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifierFromUri(uri: Uri, fileName: String): String {
    return if (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms") {
      "${fileName}_${uri.toString().hashCode()}"
    } else {
      fileName
    }
  }

  private fun shouldShowPlaybackNotification(): Boolean =
    advancedPreferences.notificationStyle.get()
      .takeIf { it.isSupportedOn(Build.VERSION.SDK_INT) }
      ?.let { it != NotificationStyle.None }
      ?: true

  private fun isVideoListLaunchSource(launchSource: String): Boolean =
    launchSource == "video_list" ||
      launchSource == "recently_played_button" ||
      launchSource == "first_video_button"

  private fun normalizePlaylistFilePath(path: String): String = path.replace("\\", "/")

  private fun naturalSortFiles(files: List<File>): List<File> =
    files.sortedWith { first, second ->
      app.gyrolet.mpvrx.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT.compare(first.name, second.name)
    }

  private suspend fun sortSiblingFilesForVideoList(files: List<File>): List<File> {
    val sortType = browserPreferences.videoSortType.get()
    val sortOrder = browserPreferences.videoSortOrder.get()

    val sortedFiles =
      when (sortType) {
        VideoSortType.Title -> naturalSortFiles(files)
        VideoSortType.Date -> files.sortedBy { it.lastModified() }
        VideoSortType.Size -> files.sortedBy { it.length() }
        VideoSortType.Duration -> {
          val fileByPath = files.associateBy { normalizePlaylistFilePath(it.absolutePath) }
          val sortedVideos =
            app.gyrolet.mpvrx.repository.MediaFileRepository
              .getVideosFromFiles(this@PlayerActivity, files)
              .let { videos ->
                app.gyrolet.mpvrx.utils.sort.SortUtils.sortVideos(videos, sortType, sortOrder)
              }
          val resolvedFiles = sortedVideos.mapNotNull { video -> fileByPath[normalizePlaylistFilePath(video.path)] }
          if (resolvedFiles.isEmpty()) {
            naturalSortFiles(files)
          } else {
            val seenPaths = resolvedFiles.mapTo(mutableSetOf()) { normalizePlaylistFilePath(it.absolutePath) }
            resolvedFiles + naturalSortFiles(files.filter { normalizePlaylistFilePath(it.absolutePath) !in seenPaths })
          }
        }
      }

    return if (sortType == VideoSortType.Duration || sortOrder.isAscending) {
      sortedFiles
    } else {
      sortedFiles.reversed()
    }
  }

  private suspend fun resolveAutoPlaylistSiblingFiles(
    currentFile: File,
    launchSource: String,
  ): List<File> {
    val parentFolder = currentFile.parentFile ?: return emptyList()
    val includeAudio = browserPreferences.includeAudioBrowser.get()
    val minimumAudioDurationMs = browserPreferences.minimumAudioDurationSeconds.get() * 1000L
    val directMediaFiles =
      parentFolder.listFiles { file ->
        file.isFile &&
          (
            FileTypeUtils.isVideoFile(file) ||
              (includeAudio &&
                FileTypeUtils.isAudioFile(file) &&
                (minimumAudioDurationMs == 0L ||
                  FileTypeUtils.getDurationMs(file) >= minimumAudioDurationMs))
          ) &&
          !file.name.startsWith(".")
      }?.toList().orEmpty()

    if (!isVideoListLaunchSource(launchSource)) {
      return naturalSortFiles(directMediaFiles)
    }

    val currentFilePath = normalizePlaylistFilePath(currentFile.absolutePath)
    val fileByPath = directMediaFiles.associateBy { normalizePlaylistFilePath(it.absolutePath) }
    val sortedFromLibrary =
      app.gyrolet.mpvrx.repository.MediaFileRepository
        .getVideosInFolder(context, normalizePlaylistFilePath(parentFolder.absolutePath))
        .let { videos ->
          app.gyrolet.mpvrx.utils.sort.SortUtils.sortVideos(
            videos,
            browserPreferences.videoSortType.get(),
            browserPreferences.videoSortOrder.get(),
          )
        }.mapNotNull { video -> fileByPath[normalizePlaylistFilePath(video.path)] }

    return if (sortedFromLibrary.any { normalizePlaylistFilePath(it.absolutePath) == currentFilePath }) {
      sortedFromLibrary
    } else {
      sortSiblingFilesForVideoList(directMediaFiles)
    }
  }

  private suspend fun loadPlaylistById(
    pid: Int,
    sourceIntent: Intent,
    logPrefix: String,
    reapplyShuffle: Boolean = false,
  ) {
    if (isAllVideosPlaylist(pid)) {
      val mediaLibraryAudio = sourceIntent.getBooleanExtra("media_library_audio", false)
      val isMediaLibraryLaunch = sourceIntent.getStringExtra("launch_source") == "media_library"
      val allVideos =
        app.gyrolet.mpvrx.utils.sort.SortUtils.sortVideos(
          app.gyrolet.mpvrx.repository.MediaFileRepository
            .getAllVideos(
              context = this@PlayerActivity,
              includeAudioOverride = if (isMediaLibraryLaunch) true else null,
            )
            .let { media ->
              if (isMediaLibraryLaunch) {
                media.filter { it.isAudio == mediaLibraryAudio }
              } else {
                media
              }
            },
          browserPreferences.videoSortType.get(),
          browserPreferences.videoSortOrder.get(),
        )
      val playlistUris = allVideos.map { it.uri }
      val resolvedPath = parsePathFromIntent(sourceIntent)
      val resolvedUri = sourceIntent.dataString
      val derivedIndex =
        allVideos.indexOfFirst { video ->
          video.path == resolvedPath || video.uri.toString() == resolvedUri
        }
      val syntheticItems =
        allVideos.mapIndexed { index, video ->
          PlaylistItemEntity(
            id = index + 1,
            playlistId = ALL_VIDEOS_PLAYLIST_ID,
            filePath = video.path,
            fileName = video.displayName,
            position = index,
            addedAt = video.dateAdded * 1000L,
          )
        }
      val updatedAt =
        allVideos.maxOfOrNull { it.dateModified * 1000L } ?: System.currentTimeMillis()

      withContext(Dispatchers.Main) {
        playlistEntity = buildAllVideosPlaylistEntity(updatedAt = updatedAt)
        playlistItems = syntheticItems
        isM3uPlaylist = false
        playlist = playlistUris
        playlistWindowOffset = 0
        playlistTotalCount = playlistUris.size
        playlistIndex =
          derivedIndex.takeIf { it >= 0 }
            ?: playlistIndex.coerceIn(0, (playlistUris.lastIndex).coerceAtLeast(0))
        Log.d(TAG, "$logPrefix ${playlistUris.size} items from all-videos playlist")
        if (reapplyShuffle && viewModel.shuffleEnabled.value) {
          onShuffleToggled(true)
        }
        viewModel.refreshPlaylistItems()
      }
      return
    }

    val loadedPlaylist = playlistRepository.getPlaylistById(pid)
    val loadedItems = playlistRepository.getPlaylistItems(pid)
    val items = loadedItems.map { Uri.parse(it.filePath) }
    val totalCount = loadedItems.size

    withContext(Dispatchers.Main) {
      playlistEntity = loadedPlaylist
      playlistItems = loadedItems
      isM3uPlaylist = loadedPlaylist?.isM3uPlaylist == true
      playlist = items
      playlistWindowOffset = 0
      playlistTotalCount = totalCount
      Log.d(TAG, "$logPrefix all $totalCount items from playlist $pid (isM3U: $isM3uPlaylist)")
      if (reapplyShuffle && viewModel.shuffleEnabled.value) {
        onShuffleToggled(true)
      }
      viewModel.refreshPlaylistItems()
    }
  }

  private fun generatePlaylistFromFolder(currentPath: String) {
    lifecycleScope.launch(Dispatchers.IO) {
      generatePlaylistFromFolderInternal(currentPath)
    }
  }

  private suspend fun generatePlaylistFromFolderInternal(currentPath: String): Boolean =
    runCatching {
      val currentFile = File(currentPath)
      if (!currentFile.exists()) return@runCatching false

      val launchSource = intent.getStringExtra("launch_source") ?: ""
      val siblingFiles = resolveAutoPlaylistSiblingFiles(currentFile, launchSource)
      if (siblingFiles.size <= 1) return@runCatching false

      val currentFilePath = normalizePlaylistFilePath(currentFile.absolutePath)
      val newIndex = siblingFiles.indexOfFirst {
        normalizePlaylistFilePath(it.absolutePath) == currentFilePath
      }
      if (newIndex < 0) return@runCatching false

      withContext(Dispatchers.Main) {
        playlistEntity = null
        playlistItems = emptyList()
        isM3uPlaylist = false
        playlist = siblingFiles.map { it.toUri() }
        playlistIndex = newIndex
        if (viewModel.shuffleEnabled.value) onShuffleToggled(true)
        viewModel.refreshPlaylistItems()
        Log.d(TAG, "Auto-playlist generated: ${playlist.size} items")
      }
      true
    }.onFailure { error ->
      Log.e(TAG, "Failed to auto-generate playlist", error)
    }.getOrDefault(false)

  /**
   * Check if the current playlist is an M3U playlist (sourced from database).
   */
  fun isCurrentPlaylistM3U(): Boolean = isM3uPlaylist

  private suspend fun fetchM3uContent(uriString: String): String? = withContext(Dispatchers.IO) {
    try {
      if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
        val uri = Uri.parse(uriString)
        contentResolver.openInputStream(uri)?.use { inputStream ->
          BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
            reader.readText()
          }
        }
      } else if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
        val connection = URL(uriString).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "MpvRx/1.0")
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
          val text = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
            reader.readText()
          }
          connection.disconnect()
          text
        } else {
          connection.disconnect()
          null
        }
      } else if (uriString.startsWith("fd://")) {
        val fdNum = uriString.substring(5).toIntOrNull()
        if (fdNum != null) {
          runCatching {
            val fd = java.io.FileDescriptor().apply {
              val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
              field.isAccessible = true
              field.setInt(this, fdNum)
            }
            val dupFd = android.system.Os.dup(fd)
            java.io.FileInputStream(dupFd).use { inputStream ->
              BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                reader.readText()
              }
            }
          }.getOrNull()
        } else {
          null
        }
      } else {
        // It could be a local file path, e.g. /storage/...
        val file = File(uriString)
        if (file.exists()) {
          file.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
          null
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error fetching M3U content from $uriString", e)
      null
    }
  }

  private suspend fun loadDynamicM3uPlaylist(uriString: String): Boolean {
    val content = fetchM3uContent(uriString) ?: return false
    
    // Check if HLS manifest
    if (M3UParser.isLikelyHlsMediaManifest(content)) {
      Log.d(TAG, "M3U file is likely an HLS manifest, playing as single stream")
      return false
    }
    
    // Resolve content URI to a local path if possible, for better base URL resolution
    val resolvedSourceUrl = if (uriString.startsWith("content://")) {
      Uri.parse(uriString).extractLocalPath() ?: uriString
    } else {
      uriString
    }
    
    // Parse as M3U playlist
    val parseResult = M3UParser.parseContent(content, resolvedSourceUrl)
    if (parseResult is M3UParseResult.Success) {
      val items = parseResult.items
      if (items.isNotEmpty()) {
        withContext(Dispatchers.Main) {
          isM3uPlaylist = true
          playlist = items.map { Uri.parse(it.url) }
          networkPlaylistTitles = items.map { it.title ?: extractFileNameFromUri(Uri.parse(it.url)) }
          networkPlaylistPaths = items.map { it.url }
          playlistWindowOffset = 0
          playlistTotalCount = items.size
          
          // Re-generate shuffled indices if shuffle is active
          if (viewModel.shuffleEnabled.value) {
            generateShuffledIndices()
          }
          
          Log.d(TAG, "Dynamically loaded M3U playlist with ${items.size} items")
          viewModel.refreshPlaylistItems()
        }
        return true
      }
    }
    return false
  }

  /**
   * Disables video decoding to save battery when moving to background playback.
   */
  private fun disableVideoForBackground() {
    if (!mpvInitialized || !isReady || fileName.isBlank()) return

    val currentVid = MPVLib.getPropertyString("vid")?.toIntOrNull() ?: -1
    if (currentVid > 0) {
      lastVid = currentVid
      MPVLib.setPropertyString("vid", "no")
      isInBackgroundPlayback = true
      Log.d(TAG, "Video disabled for background playback (saved vid: $lastVid)")
    }
  }

  /**
   * Restores video decoding when returning from background playback.
   */
  private fun enableVideoAfterBackground() {
    if (!mpvInitialized) return
    if ((isInBackgroundPlayback || lastVid > 0) && !player.isSurfaceReady) {
      Log.d(TAG, "Deferring video restoration until the playback surface is ready")
      return
    }

    isInBackgroundPlayback = false
    if (lastVid > 0) {
      Log.d(TAG, "Restoring video after background playback (vid: $lastVid)")
      MPVLib.setPropertyInt("vid", lastVid)
      lastVid = -1
    } else if ((MPVLib.getPropertyString("vid")?.toIntOrNull() ?: -1) <= 0) {
      Log.d(TAG, "Restoring video after background playback with auto track selection")
      MPVLib.setPropertyString("vid", "auto")
    }
  }

  companion object {
    private val nextActivityInstanceId = AtomicLong()

    /**
     * Intent action used to return playback result data to the calling activity.
     */
    private const val RESULT_INTENT = "app.gyrolet.mpvrx.ui.player.PlayerActivity.result"

    /**
     * Constant for "brightness not set".
     */
    private const val BRIGHTNESS_NOT_SET = -1f

    /**
     * Constant used when playback position is not set.
     */
    private const val POSITION_NOT_SET = 0

    /**
     * Maximum volume for MPV in percent.
     */
    private const val MAX_MPV_VOLUME = 100

    /**
     * Milliseconds-to-seconds conversion factor.
     */
    private const val MILLISECONDS_TO_SECONDS = 1000

    private const val FRAME_STALL_WARNING_MS = 8_000L

    /**
     * Factor to divide subtitle and audio delays to convert from ms to seconds.
     */
    private const val DELAY_DIVISOR = 1000.0

    /**
     * Default playback speed (1.0 = normal).
     */
    private const val DEFAULT_PLAYBACK_SPEED = 1.0

    /**
     * Default subtitle speed (1.0 = normal).
     */
    private const val DEFAULT_SUB_SPEED = 1.0

    /**
     * General tag for logging from PlayerActivity.
     */
    const val TAG = "mpvrx"
  }
}
