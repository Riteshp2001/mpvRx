package app.gyrolet.mpvrx.exoplayer

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import org.koin.androidx.viewmodel.ext.android.viewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import androidx.core.util.Consumer
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.applyNavigationBarStyle
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.applyPrivacyProtection
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.canonicalPathOrSelf
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.getFilenameFromUri
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.getMediaContentUri
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.isSubtitleExtension
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.resolvePrivacyPreviewScrim
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.scanFileForContentUri
import app.gyrolet.mpvrx.exoplayer.core.model.ScreenOrientation
import app.gyrolet.mpvrx.exoplayer.core.model.Video
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.OpenDocumentWithInitialUri
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.copy
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.registerForSuspendActivityResult
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.setMetadataExtras
import app.gyrolet.mpvrx.exoplayer.core.data.repository.OnlineSubtitleRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.InvalidOnlineSubtitleSchemeException
import app.gyrolet.mpvrx.exoplayer.core.data.repository.InvalidOnlineSubtitleUrlException
import app.gyrolet.mpvrx.exoplayer.core.data.repository.InvalidOnlineSubtitleExtensionException
import app.gyrolet.mpvrx.exoplayer.core.data.repository.OnlineSubtitleTooLargeException
import app.gyrolet.mpvrx.exoplayer.core.data.repository.EmptyOnlineSubtitleException
import app.gyrolet.mpvrx.exoplayer.core.data.repository.OnlineSubtitleDownloadFailedException
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.toActivityOrientation
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.uriToSubtitleConfiguration
import app.gyrolet.mpvrx.exoplayer.feature.player.service.addSubtitleTrack
import app.gyrolet.mpvrx.exoplayer.feature.player.service.stopPlayerSession
import app.gyrolet.mpvrx.exoplayer.feature.player.MediaPlayerScreen
import app.gyrolet.mpvrx.exoplayer.feature.player.PlayerViewModel
import app.gyrolet.mpvrx.R
import org.koin.android.ext.android.inject

internal data class PlaybackPlaylist(
    val items: List<String>,
    val currentIndex: Int,
)

internal data class PlaybackTarget(
    val sourceUriString: String,
    val playbackUriString: String,
    val currentPath: String?,
)

internal fun buildPlaybackPlaylistFromItems(
    playlistItems: List<String>,
    playbackTarget: PlaybackTarget,
): PlaybackPlaylist {
    if (playlistItems.isEmpty()) {
        return PlaybackPlaylist(
            items = listOf(playbackTarget.playbackUriString),
            currentIndex = 0,
        )
    }

    val currentIndex = playlistItems.indexOfFirst { uriString ->
        uriString == playbackTarget.playbackUriString ||
            uriString == playbackTarget.sourceUriString
    }
    if (currentIndex >= 0) {
        return PlaybackPlaylist(
            items = playlistItems,
            currentIndex = currentIndex,
        )
    }

    return PlaybackPlaylist(
        items = listOf(playbackTarget.playbackUriString) + playlistItems,
        currentIndex = 0,
    )
}

internal fun buildPlaybackPlaylist(
    playlistVideos: List<Video>,
    playbackTarget: PlaybackTarget,
): PlaybackPlaylist {
    if (playlistVideos.isEmpty()) {
        return PlaybackPlaylist(
            items = listOf(playbackTarget.playbackUriString),
            currentIndex = 0,
        )
    }

    val currentIndex = playlistVideos.indexOfFirst { video ->
        video.uriString == playbackTarget.playbackUriString ||
            video.uriString == playbackTarget.sourceUriString ||
            (playbackTarget.currentPath != null && video.path == playbackTarget.currentPath)
    }
    if (currentIndex >= 0) {
        return PlaybackPlaylist(
            items = playlistVideos.map { video -> video.uriString },
            currentIndex = currentIndex,
        )
    }

    return buildPlaybackPlaylistFromItems(
        playlistItems = playlistVideos.map { video -> video.uriString },
        playbackTarget = playbackTarget,
    )
}

private val storagePermission = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> android.Manifest.permission.READ_MEDIA_VIDEO
    else -> android.Manifest.permission.READ_EXTERNAL_STORAGE
}

@SuppressLint("UnsafeOptInUsageError")
class ExoPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ExoPlayerActivity"

        private val SUBTITLE_DOCUMENT_MIME_TYPES = arrayOf(
            "application/octet-stream",
            "application/ttml+xml",
            "application/x-ass",
            "application/x-subrip",
            "application/x-ssa",
            "audio/aac",
            "text/*",
            "text/plain",
            "text/srt",
            "text/vtt",
            "text/x-ass",
            "text/x-ssa",
        )
    }

    val mediaSynchronizer: MediaSynchronizer by inject()
    val onlineSubtitleRepository: OnlineSubtitleRepository by inject()

    private val viewModel: PlayerViewModel by viewModel()
    val playerPreferences get() = viewModel.uiState.value.playerPreferences

    private val onWindowAttributesChangedListener = CopyOnWriteArrayList<Consumer<WindowManager.LayoutParams?>>()

    private var isPlaybackFinished = false
    private var shouldPlayInBackground: Boolean = false
    private var isIntentNew: Boolean = true
    private var keyboardEventHandler: ((KeyEvent) -> Boolean)? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var playerApi: PlayerApi? = null

    private val playbackStateListener: Player.Listener = playbackStateListener()

    private val subtitleFileSuspendLauncher = registerForSuspendActivityResult(OpenDocumentWithInitialUri())
    private val mediaPermissionLauncher = registerForActivityResult(RequestPermission()) { isGranted ->
        if (!isGranted) return@registerForActivityResult

        lifecycleScope.launch {
            maybeInitControllerFuture()
            mediaController = controllerFuture?.await()
            mediaController?.run {
                updateKeepScreenOnFlag()
                addListener(playbackStateListener)
                startPlayback()
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            val initialUiState = viewModel.uiState.value
            applyPrivacyProtection(
                shouldPreventScreenshots = initialUiState.shouldPreventScreenshots,
                shouldHideInRecents = initialUiState.shouldHideInRecents,
            )
            presetVideoOrientation()
            val systemBarScrim = resolvePrivacyPreviewScrim(
                shouldHideInRecents = initialUiState.shouldHideInRecents,
            )
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(systemBarScrim),
                navigationBarStyle = SystemBarStyle.dark(systemBarScrim),
            )
            applyNavigationBarStyle(color = Color.BLACK, shouldUseDarkIcons = false)

            setContent {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                var player by remember { mutableStateOf<MediaController?>(null) }
                var isTakingScreenshot by remember { mutableStateOf(false) }

                LifecycleStartEffect(
                    uiState.shouldPreventScreenshots,
                    uiState.shouldHideInRecents,
                ) {
                    this@ExoPlayerActivity.applyPrivacyProtection(
                        shouldPreventScreenshots = uiState.shouldPreventScreenshots,
                        shouldHideInRecents = uiState.shouldHideInRecents,
                    )
                    onStopOrDispose {}
                }

                LifecycleStartEffect(Unit) {
                    maybeInitControllerFuture()
                    lifecycleScope.launch {
                        player = controllerFuture?.await()
                    }

                    onStopOrDispose {
                        player = null
                    }
                }

                MpvrxTheme {
                    MediaPlayerScreen(
                        modifier = Modifier.semantics {
                            testTagsAsResourceId = true
                        },
                        player = player,
                        viewModel = viewModel,
                        playerPreferences = uiState.playerPreferences ?: return@MpvrxTheme,
                        externalSubtitleFontSource = uiState.externalSubtitleFontSource,
                        onSelectSubtitleClick = {
                            lifecycleScope.launch {
                                val uri = subtitleFileSuspendLauncher.launch(
                                    OpenDocumentWithInitialUri.Input(
                                        mimeTypes = SUBTITLE_DOCUMENT_MIME_TYPES,
                                        initialUri = IntentCompat.getParcelableExtra(
                                            intent,
                                            "initial_subtitle_directory_uri",
                                            Uri::class.java,
                                        ),
                                    ),
                                ) ?: return@launch
                                if (!isSupportedSubtitleDocument(uri)) {
                                    showToast(R.string.local_subtitle_unsupported)
                                    return@launch
                                }
                                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                maybeInitControllerFuture()
                                controllerFuture?.await()?.addSubtitleTrack(uri)
                            }
                        },
                        onAddOnlineSubtitleClick = ::addOnlineSubtitle,
                        onBackClick = { finishAndStopPlayerSession() },
                        onPlayInBackgroundClick = {
                            shouldPlayInBackground = true
                            finish()
                        },
                        isTakingScreenshot = isTakingScreenshot,
                        onScreenshotClick = screenshotClick@{
                            if (isTakingScreenshot) return@screenshotClick
                            lifecycleScope.launch {
                                isTakingScreenshot = true
                                try {
                                    val messageResId = runCatching {
                                        if (saveCurrentFrameScreenshot()) {
                                            R.string.screenshot_saved
                                        } else {
                                            R.string.screenshot_failed
                                        }
                                    }.getOrElse {
                                        Logger.error(TAG, "Failed to take screenshot", it)
                                        R.string.screenshot_failed
                                    }
                                    Toast.makeText(this@ExoPlayerActivity, messageResId, Toast.LENGTH_SHORT).show()
                                } finally {
                                    isTakingScreenshot = false
                                }
                            }
                        },
                        onKeyboardEventHandlerChanged = { handler ->
                            keyboardEventHandler = handler
                        },
                    )
                }
            }

            playerApi = PlayerApi(this)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ExoPlayerActivity onCreate failed!", e)
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(Dispatchers.IO) {
            onlineSubtitleRepository.deleteExpiredSubtitles()
        }
        lifecycleScope.launch {
            if (!ensureMediaPermission()) return@launch

            maybeInitControllerFuture()
            mediaController = controllerFuture?.await()

            mediaController?.run {
                updateKeepScreenOnFlag()
                addListener(playbackStateListener)
                startPlayback()
            }
        }
    }

    override fun onStop() {
        keyboardEventHandler = null
        mediaController?.run {
            viewModel.shouldPlayWhenReady = playWhenReady
            removeListener(playbackStateListener)
        }
        val shouldPlayInBackground = shouldPlayInBackground || playerPreferences?.shouldAutoPlayInBackground == true
        if (subtitleFileSuspendLauncher.isAwaitingResult || !shouldPlayInBackground) {
            mediaController?.pause()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            finish()
            if (!shouldPlayInBackground) {
                mediaController?.stopPlayerSession()
            }
        }

        controllerFuture?.run {
            MediaController.releaseFuture(this)
            controllerFuture = null
        }
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyboardEventHandler?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun maybeInitControllerFuture() {
        if (controllerFuture == null) {
            val sessionToken = SessionToken(applicationContext, ComponentName(applicationContext, ExoPlayerService::class.java))
            controllerFuture = MediaController.Builder(applicationContext, sessionToken).buildAsync()
        }
    }

    private fun addOnlineSubtitle(subtitleUrl: String) {
        if (subtitleUrl.isBlank()) {
            showToast(R.string.online_subtitle_empty)
            return
        }

        lifecycleScope.launch {
            Logger.debug(TAG, "Add online subtitle requested: ${subtitleUrl.toLogSummary()}")
            val messageResId = try {
                val downloadedSubtitle = withContext(Dispatchers.IO) {
                    onlineSubtitleRepository.downloadSubtitle(subtitleUrl)
                }
                maybeInitControllerFuture()
                val controller = controllerFuture?.await() ?: error("MediaController is unavailable")
                controller.addSubtitleTrack(downloadedSubtitle.uri)
                Logger.debug(TAG, "Add online subtitle command sent: uri=${downloadedSubtitle.uriString}")
                R.string.online_subtitle_added
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: InvalidOnlineSubtitleSchemeException) {
                R.string.online_subtitle_unsupported_url
            } catch (exception: InvalidOnlineSubtitleUrlException) {
                R.string.online_subtitle_unsupported_url
            } catch (exception: InvalidOnlineSubtitleExtensionException) {
                R.string.online_subtitle_unsupported_url
            } catch (exception: OnlineSubtitleTooLargeException) {
                R.string.online_subtitle_too_large
            } catch (exception: EmptyOnlineSubtitleException) {
                R.string.online_subtitle_empty
            } catch (exception: OnlineSubtitleDownloadFailedException) {
                R.string.online_subtitle_download_failed
            } catch (exception: Exception) {
                Logger.error(TAG, "Failed to add online subtitle", exception)
                R.string.online_subtitle_download_failed
            }
            showToast(messageResId)
        }
    }

    private fun showToast(messageResId: Int) {
        Toast.makeText(this@ExoPlayerActivity, messageResId, Toast.LENGTH_SHORT).show()
    }

    private fun isSupportedSubtitleDocument(uri: Uri): Boolean {
        val fileName = getFilenameFromUri(uri)
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return extension.isSubtitleExtension()
    }

    private fun String.toLogSummary(): String {
        val uri = Uri.parse(this)
        val extension = uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase().orEmpty()
        return "scheme=${uri.scheme.orEmpty()} host=${uri.host.orEmpty()} extension=$extension"
    }

    private fun startPlayback() {
        val uri = intent.data ?: return

        val isReturningFromBackground = !isIntentNew && mediaController?.currentMediaItem != null
        val isNewUriTheCurrentMediaItem = mediaController?.currentMediaItem?.localConfiguration?.uri.toString() == uri.toString()

        if (isReturningFromBackground || isNewUriTheCurrentMediaItem) {
            Logger.info(
                TAG,
                "startPlayback reused current item returning=$isReturningFromBackground same=$isNewUriTheCurrentMediaItem uri=$uri",
            )
            mediaController?.prepare()
            mediaController?.playWhenReady = viewModel.shouldPlayWhenReady
            return
        }

        isIntentNew = false

        lifecycleScope.launch {
            playVideo(uri)
        }
    }

    private suspend fun playVideo(uri: Uri) = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        Logger.info(TAG, "playVideo start uri=$uri")

        val playbackUri = resolvePlaybackUri(uri)
        val requestHeaders = buildRequestHeadersFromIntent()
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                mediaSynchronizer.registerManualVideoPath(path)
                Logger.info(TAG, "playVideo registeredManualPath=$path")
            }
        }
        val t1 = System.currentTimeMillis()
        Logger.info(TAG, "playVideo resolveUri=${t1 - t0}ms resolved=$playbackUri")

        val playbackTarget = PlaybackTarget(
            sourceUriString = uri.toString(),
            playbackUriString = playbackUri.toString(),
            currentPath = playbackUri.path,
        )
        val currentMediaItem = buildMediaItem(
            uriString = playbackUri.toString(),
            requestHeaders = requestHeaders,
            isCurrentItem = true,
            localParentPath = null,
        )

        withContext(Dispatchers.Main) {
            mediaController?.run {
                setMediaItem(currentMediaItem, (playerApi?.position?.times(1000))?.toLong() ?: C.TIME_UNSET)
                playWhenReady = viewModel.shouldPlayWhenReady
                prepare()
                Logger.info(TAG, "playVideo prepare total=${System.currentTimeMillis() - t0}ms")
            }
        }

        appendPlaylistAfterCurrent(
            currentMediaItem = currentMediaItem,
            playbackTarget = playbackTarget,
            requestHeaders = requestHeaders,
            startedAtMs = t0,
            playlistStartedAtMs = System.currentTimeMillis(),
        )
    }

    private suspend fun appendPlaylistAfterCurrent(
        currentMediaItem: MediaItem,
        playbackTarget: PlaybackTarget,
        requestHeaders: Map<String, String>,
        startedAtMs: Long,
        playlistStartedAtMs: Long,
    ) = coroutineScope {
        val apiPlaylist = playerApi?.getPlaylist().orEmpty()
        val folderPlaylist = if (apiPlaylist.isEmpty()) {
            viewModel.getPlaylistFromUri(Uri.parse(playbackTarget.playbackUriString))
        } else {
            emptyList()
        }
        val playbackPlaylist = if (apiPlaylist.isNotEmpty()) {
            buildPlaybackPlaylistFromItems(
                playlistItems = apiPlaylist,
                playbackTarget = playbackTarget,
            )
        } else {
            buildPlaybackPlaylist(
                playlistVideos = folderPlaylist,
                playbackTarget = playbackTarget,
            )
        }
        val playlist = playbackPlaylist.items
        Logger.info(TAG, "playVideo playlist=${System.currentTimeMillis() - playlistStartedAtMs}ms size=${playlist.size}")
        if (playlist.size <= 1) return@coroutineScope

        val currentIndex = playbackPlaylist.currentIndex
        val currentLocalParentPath = findLocalParentPath(folderPlaylist, playbackTarget.playbackUriString)
            ?: findLocalParentPath(folderPlaylist, playbackTarget.sourceUriString)
        val beforeItems = playlist.take(currentIndex).map { uriString ->
            async {
                buildMediaItem(
                    uriString = uriString,
                    requestHeaders = requestHeaders,
                    isCurrentItem = false,
                    localParentPath = findLocalParentPath(folderPlaylist, uriString),
                )
            }
        }.awaitAll()
        val afterItems = playlist.drop(currentIndex + 1).map { uriString ->
            async {
                buildMediaItem(
                    uriString = uriString,
                    requestHeaders = requestHeaders,
                    isCurrentItem = false,
                    localParentPath = findLocalParentPath(folderPlaylist, uriString),
                )
            }
        }.awaitAll()

        withContext(Dispatchers.Main) {
            mediaController?.run {
                val currentItem = currentMediaItem
                if (currentItem.localConfiguration?.uri.toString() != playbackTarget.playbackUriString) return@run
                if (mediaItemCount != 1) return@run

                if (currentLocalParentPath != null) {
                    replaceMediaItem(
                        currentMediaItemIndex,
                        currentItem.copy(localParentPath = currentLocalParentPath),
                    )
                }
                if (beforeItems.isNotEmpty()) addMediaItems(0, beforeItems)
                if (afterItems.isNotEmpty()) addMediaItems(currentMediaItemIndex + 1, afterItems)
                Logger.info(TAG, "playVideo appendPlaylist total=${System.currentTimeMillis() - startedAtMs}ms")
            }
        }
    }

    private suspend fun buildMediaItem(
        uriString: String,
        requestHeaders: Map<String, String>,
        isCurrentItem: Boolean,
        localParentPath: String?,
    ): MediaItem = coroutineScope {
        val builder = MediaItem.Builder()
        builder.setUri(uriString)
        builder.setMediaId(uriString)
        val remoteServerId = requestHeaders["_remote_server_id"]?.toLongOrNull()
        val remoteProtocol = requestHeaders["_remote_protocol"]
        val hasRemoteMetadata = remoteServerId != null && remoteProtocol != null
        if (isCurrentItem || hasRemoteMetadata) {
            val filePath = if (isCurrentItem) {
                requestHeaders["_remote_file_path"]
            } else {
                Uri.parse(uriString).path
            }
            builder.setMediaMetadata(
                MediaMetadata.Builder().apply {
                    if (isCurrentItem) setTitle(playerApi?.title)
                    val remoteDirectoryPath = filePath
                        ?.substringBeforeLast('/', missingDelimiterValue = "")
                        ?.ifBlank { "/" }
                    setMetadataExtras(
                        positionMs = if (isCurrentItem) (playerApi?.position?.times(1000))?.toLong() else null,
                        requestHeaders = requestHeaders,
                        remoteServerId = remoteServerId,
                        remoteFilePath = filePath,
                        remoteProtocol = remoteProtocol,
                        localParentPath = localParentPath,
                        remoteDirectoryPath = remoteDirectoryPath,
                    )
                }.build(),
            )
        }
        if (isCurrentItem) {
            val apiSubs = playerApi?.getSubs().orEmpty().map { subtitle ->
                async {
                    uriToSubtitleConfiguration(
                        uri = subtitle.uri,
                        subtitleEncoding = playerPreferences?.subtitleTextEncoding ?: "",
                        isSelected = subtitle.isSelected,
                    )
                }
            }.awaitAll()
            builder.setSubtitleConfigurations(apiSubs)
        }
        builder.build()
    }

    private fun findLocalParentPath(
        folderPlaylist: List<Video>,
        uriString: String,
    ): String? {
        val itemPath = Uri.parse(uriString).path
        return folderPlaylist
            .find { video -> video.uriString == uriString || video.path == itemPath }
            ?.parentPath
            ?.takeIf { it.isNotBlank() }
    }

    private fun ensureMediaPermission(): Boolean {
        if (hasMediaReadPermission()) return true

        mediaPermissionLauncher.launch(storagePermission)
        return false
    }

    private suspend fun resolvePlaybackUri(uri: Uri): Uri {
        val t0 = System.currentTimeMillis()

        if (uri.scheme == "file") {
            val rawPath = uri.path ?: return uri
            val canonicalPath = rawPath.canonicalPathOrSelf()
            Logger.info(TAG, "resolveUri canonical=${System.currentTimeMillis() - t0}ms path=$canonicalPath")

            if (File(canonicalPath).exists()) {
                Logger.info(TAG, "resolveUri fileFallback=${System.currentTimeMillis() - t0}ms")
                return Uri.fromFile(File(canonicalPath))
            }

            if (hasMediaReadPermission()) {
                scanFileForContentUri(path = canonicalPath, timeoutMs = 800L)?.let {
                    Logger.info(TAG, "resolveUri scanFile=${System.currentTimeMillis() - t0}ms result=$it")
                    return it
                }
                Logger.info(TAG, "resolveUri scanFileMiss=${System.currentTimeMillis() - t0}ms")
            } else {
                Logger.info(TAG, "resolveUri skipMediaStore noReadPermission=true")
            }
            return uri
        }

        if (hasMediaReadPermission()) {
            getMediaContentUri(uri)?.let {
                Logger.info(TAG, "resolveUri contentUri=${System.currentTimeMillis() - t0}ms")
                return it
            }
        }

        return uri
    }

    private fun buildRequestHeadersFromIntent(): Map<String, String> {
        val headerBundle = intent.getBundleExtra("headers") ?: return emptyMap()
        return buildMap {
            for (key in headerBundle.keySet()) {
                val value = headerBundle.getString(key).orEmpty()
                if (value.isNotEmpty()) put(key, value)
            }
        }
    }

    private fun hasMediaReadPermission(): Boolean = ContextCompat.checkSelfPermission(this, storagePermission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun playbackStateListener() = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            intent.data = mediaItem?.localConfiguration?.uri
            updateKeepScreenOnFlag()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            updateKeepScreenOnFlag()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            when (playbackState) {
                Player.STATE_ENDED -> {
                    isPlaybackFinished = mediaController?.playbackState == Player.STATE_ENDED
                    updateKeepScreenOnFlag()
                    finishAndStopPlayerSession()
                }

                else -> {}
            }
        }

        override fun onPlayWhenReadyChanged(shouldPlayWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(shouldPlayWhenReady, reason)

            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (mediaController?.repeatMode != Player.REPEAT_MODE_OFF) return
                isPlaybackFinished = true
                finishAndStopPlayerSession()
            }
        }
    }

    override fun finish() {
        if (playerApi?.shouldReturnResult == true) {
            val result = playerApi?.getResult(
                isPlaybackFinished = isPlaybackFinished,
                duration = mediaController?.duration ?: C.TIME_UNSET,
                position = mediaController?.currentPosition ?: C.TIME_UNSET,
            )
            setResult(RESULT_OK, result)
        }
        super.finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) {
            setIntent(intent)
            isIntentNew = true
            if (mediaController != null) {
                startPlayback()
            }
        }
    }

    private fun updateKeepScreenOnFlag() {
        if (mediaController?.currentMediaItem != null && !isPlaybackFinished) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private suspend fun saveCurrentFrameScreenshot(): Boolean = withContext(Dispatchers.Main) {
        val surfaceView = findPlayerSurfaceView() ?: return@withContext false
        if (surfaceView.width <= 0 || surfaceView.height <= 0) return@withContext false

        val bitmap = createBitmap(surfaceView.width, surfaceView.height)
        val copyResult = suspendCancellableCoroutine<Int> { continuation ->
            PixelCopy.request(surfaceView, bitmap, { result ->
                continuation.resume(result)
            }, Handler(mainLooper))
        }
        if (copyResult != PixelCopy.SUCCESS) return@withContext false

        withContext(Dispatchers.IO) {
            saveScreenshotBitmap(bitmap)
        }
    }

    private fun findPlayerSurfaceView(): SurfaceView? {
        val rootView = window.decorView.rootView ?: return null
        return findSurfaceView(rootView)
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        val group = view as? android.view.ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findSurfaceView(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun saveScreenshotBitmap(bitmap: android.graphics.Bitmap): Boolean {
        val fileName = buildScreenshotFileName()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val contentValues = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(collection, contentValues) ?: return false
        return try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("Failed to compress screenshot")
                }
            } ?: throw IOException("Failed to open screenshot output stream")

            contentResolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                },
                null,
                null,
            ) > 0
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            Logger.error(TAG, "Failed to save screenshot", e)
            false
        }
    }

    private fun buildScreenshotFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "Screenshot-$timestamp.png"
    }

    private fun finishAndStopPlayerSession() {
        finish()
        mediaController?.stopPlayerSession()
    }

    override fun onWindowAttributesChanged(params: WindowManager.LayoutParams?) {
        super.onWindowAttributesChanged(params)
        for (listener in onWindowAttributesChangedListener) {
            listener.accept(params)
        }
    }

    fun addOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.add(listener)
    }

    fun removeOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.remove(listener)
    }

    private fun presetVideoOrientation() {
        val prefs = playerPreferences ?: return
        val rememberedOrientation = prefs.lastPlayerScreenOrientation
            ?.takeIf { prefs.shouldRememberPlayerScreenOrientation }
            ?.toActivityOrientation()
        if (rememberedOrientation != null) {
            requestedOrientation = rememberedOrientation
            return
        }
        if (prefs.playerScreenOrientation != ScreenOrientation.VIDEO_ORIENTATION) return
        val uri = intent.data ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val video = viewModel.getVideoByUri(uri.toString()) ?: return@launch
            if (video.width <= 0 || video.height <= 0) return@launch
            val orientation = if (video.height >= video.width) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            withContext(Dispatchers.Main) {
                if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    requestedOrientation = orientation
                }
            }
        }
    }
}
