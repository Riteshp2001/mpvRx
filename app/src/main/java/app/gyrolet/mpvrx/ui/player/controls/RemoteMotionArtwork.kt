/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.gyrolet.mpvrx.data.artwork.MotionArtworkQuery
import app.gyrolet.mpvrx.data.artwork.MotionArtworkRendition
import app.gyrolet.mpvrx.data.artwork.MotionArtworkResolver
import app.gyrolet.mpvrx.data.artwork.MotionArtworkSource
import app.gyrolet.mpvrx.data.artwork.RemoteMotionArtwork
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * Online motion artwork over the still cover. Providers are tried in order; when every
 * rendition of one fails to play, the next provider is asked, and the still cover remains
 * if none succeeds.
 */
@Composable
internal fun RemoteMotionArtwork(
  query: MotionArtworkQuery,
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
) {
  val resolver = koinInject<MotionArtworkResolver>()
  val preferences = koinInject<AudioPreferences>()
  val appleMusic by preferences.animatedArtworkAppleMusic.collectAsState()
  val tidal by preferences.animatedArtworkTidal.collectAsState()
  val tidalClientId by preferences.tidalClientId.collectAsState()
  val tidalClientSecret by preferences.tidalClientSecret.collectAsState()
  val sources = remember(appleMusic, tidal, tidalClientId, tidalClientSecret) {
    MotionArtworkResolver.sources(
      appleMusic = appleMusic,
      tidal = tidal && tidalClientId.isNotBlank() && tidalClientSecret.isNotBlank(),
    )
  }
  val lifecycleOwner = LocalLifecycleOwner.current
  var exhausted by remember(query, sources) { mutableStateOf(emptySet<MotionArtworkSource>()) }
  var artwork by remember(query, sources) { mutableStateOf<RemoteMotionArtwork?>(null) }
  LaunchedEffect(query, sources, exhausted, lifecycleOwner, resolver) {
    val remaining = sources - exhausted
    if (remaining.isEmpty()) return@LaunchedEffect
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      // Metadata can arrive in several updates; skip transient combinations during track changes.
      delay(600)
      artwork = resolver.find(query, remaining)
    }
  }
  val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
  if (lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
    artwork?.let { result ->
      key(query, result) {
        MotionArtworkVideo(
          artwork = result,
          isPlaying = isPlaying,
          modifier = modifier,
          onExhausted = {
            if (artwork === result) {
              artwork = null
              exhausted = exhausted + result.source
            }
          },
        )
      }
    }
  }
}

@Composable
private fun MotionArtworkVideo(
  artwork: RemoteMotionArtwork,
  isPlaying: Boolean,
  modifier: Modifier,
  onExhausted: () -> Unit,
) {
  var sourceIndex by remember { mutableIntStateOf(0) }
  var failed by remember { mutableStateOf(false) }
  if (failed) return

  key(sourceIndex) {
    var firstFrame by remember { mutableStateOf(false) }
    val attempt = sourceIndex
    val retryOrFallBack = {
      if (sourceIndex == attempt && !failed) {
        if (sourceIndex < artwork.renditions.lastIndex) {
          sourceIndex++
        } else {
          failed = true
          onExhausted()
        }
      }
    }
    LaunchedEffect(firstFrame) {
      if (!firstFrame) {
        delay(20_000)
        Log.w("MotionArtworkVideo", "Artwork first frame timed out; trying another rendition or provider")
        retryOrFallBack()
      }
    }
    AndroidView(
      modifier = modifier,
      factory = { context ->
        MotionArtworkTextureView(
          context = context,
          rendition = artwork.renditions[sourceIndex],
          source = artwork.source,
          onFrameReady = { firstFrame = it },
          onFailure = retryOrFallBack,
        )
      },
      update = { view ->
        // A Compose alpha=0 layer can suppress TextureView drawing and its first-frame
        // callback. Blend on the View itself, leaving the still cover underneath.
        view.alpha = if (firstFrame) 1f else 0f
        view.setPlaying(isPlaying)
      },
      onRelease = { view -> view.releasePlayer() },
    )
  }
}

/** Local folder cover.mp4, looped silently and scoped to the visible player lifecycle. */
@Composable
internal fun LocalFolderMotionArtworkVideo(
  uri: String,
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
  if (lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
    key(uri) {
      var firstFrame by remember { mutableStateOf(false) }
      AndroidView(
        modifier = modifier,
        factory = { context ->
          MotionArtworkTextureView(
            context = context,
            rendition = MotionArtworkRendition(uri, hls = false),
            source = null,
            onFrameReady = { firstFrame = it },
            onFailure = { Log.w("MotionArtworkVideo", "Local folder cover.mp4 could not be played") },
          )
        },
        update = { view ->
          view.alpha = if (firstFrame) 1f else 0f
          view.setPlaying(isPlaying)
        },
        onRelease = { view -> view.releasePlayer() },
      )
    }
  }
}

/** HLS/MP4 artwork with audio tracks disabled; no audio focus, media session or video disk cache. */
@OptIn(UnstableApi::class)
private class MotionArtworkTextureView(
  context: Context,
  private val rendition: MotionArtworkRendition,
  private val source: MotionArtworkSource?,
  private val onFrameReady: (Boolean) -> Unit,
  private val onFailure: () -> Unit,
) : TextureView(context) {
  private var player: ExoPlayer? = null
  private var released = false
  private var failed = false
  private var frameReady = false
  private var videoAspect = 1f

  init {
    isOpaque = false
    isClickable = false
    isFocusable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    alpha = 0f
    preparePlayer()
  }

  fun setPlaying(value: Boolean) {
    player?.playWhenReady = value
  }

  private fun preparePlayer() {
    try {
      val http = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(10_000)
      // Apple's CDN expects requests from its web player; other CDNs get no borrowed origin.
      if (source == MotionArtworkSource.APPLE_MUSIC) {
        http.setDefaultRequestProperties(mapOf("Referer" to "https://music.apple.com/", "Origin" to "https://music.apple.com"))
      }
      val current = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context.applicationContext, http)))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 8_000, 500, 1_000).build())
        .setAudioAttributes(AudioAttributes.DEFAULT, false)
        .setHandleAudioBecomingNoisy(false)
        .build()
      player = current
      current.volume = 0f
      current.repeatMode = Player.REPEAT_MODE_ONE
      current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
        .setMaxVideoSize(720, 720)
        .setMaxVideoFrameRate(30)
        .setMaxVideoBitrate(1_500_000)
        .build()
      current.addListener(object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
          if (videoSize.width > 0 && videoSize.height > 0 && player === current) {
            videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            updateCrop()
          }
        }

        override fun onPlayerError(error: PlaybackException) {
          if (player === current) fail("Artwork decoder error (${error.errorCodeName})")
        }
      })
      current.setVideoTextureView(this)
      // ExoPlayer owns the surface listener. Forward every callback so its renderer stays
      // attached, and reveal only after the *current* texture has received a real buffer.
      val delegate = surfaceTextureListener
      surfaceTextureListener = object : SurfaceTextureListener {
        private var replacing = false

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
          delegate?.onSurfaceTextureAvailable(texture, width, height)
          updateCrop()
          if (replacing && player === current) {
            replacing = false
            // Repaint even if the song is paused when the surface returns.
            current.seekTo(current.currentPosition)
          }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
          delegate?.onSurfaceTextureSizeChanged(texture, width, height)
          updateCrop()
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
          replacing = true
          alpha = 0f
          if (frameReady && !released) {
            frameReady = false
            onFrameReady(false)
          }
          return delegate?.onSurfaceTextureDestroyed(texture) ?: true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
          delegate?.onSurfaceTextureUpdated(texture)
          if (!frameReady && !released && !failed) {
            frameReady = true
            onFrameReady(true)
          }
        }
      }
      val item = MediaItem.Builder().setUri(rendition.url)
      // Progressive files are sniffed, since not every CDN URL ends in a container extension.
      if (rendition.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
      current.setMediaItem(item.build())
      current.prepare()
    } catch (e: IllegalArgumentException) {
      fail("Unsupported artwork stream", e)
    } catch (e: IllegalStateException) {
      fail("Artwork decoder could not prepare", e)
    } catch (e: SecurityException) {
      fail("Artwork stream access denied", e)
    }
  }

  private fun updateCrop() {
    if (width <= 0 || height <= 0) return
    val viewAspect = width.toFloat() / height
    val scaleX = if (videoAspect > viewAspect) videoAspect / viewAspect else 1f
    val scaleY = if (videoAspect < viewAspect) viewAspect / videoAspect else 1f
    setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
  }

  private fun fail(message: String, cause: Exception? = null) {
    if (failed || released) return
    failed = true
    Log.w("MotionArtworkVideo", "$message; trying another rendition or retaining the cover", cause)
    releasePlayer()
    alpha = 0f
    onFrameReady(false)
    onFailure()
  }

  fun releasePlayer() {
    if (released) return
    released = true
    val current = player
    player = null
    current?.clearVideoTextureView(this)
    current?.release()
    surfaceTextureListener = null
  }
}
