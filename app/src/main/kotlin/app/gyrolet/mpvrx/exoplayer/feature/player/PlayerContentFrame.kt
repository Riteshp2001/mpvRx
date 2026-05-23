package app.gyrolet.mpvrx.exoplayer.feature.player

import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority
import app.gyrolet.mpvrx.exoplayer.core.model.VideoContentScale
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.hasRenderedFirstFrame
import app.gyrolet.mpvrx.exoplayer.feature.player.state.ControlsVisibilityState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.PictureInPictureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SeekGestureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.TapGestureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.VideoZoomAndContentScaleState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.VolumeAndBrightnessGestureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberTracksState
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.PlayerGestures
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.ShutterView
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.SubtitleConfiguration
import app.gyrolet.mpvrx.exoplayer.feature.player.ui.SubtitleView

@OptIn(UnstableApi::class)
@Composable
fun PlayerContentFrame(
    modifier: Modifier = Modifier,
    player: Player,
    pictureInPictureState: PictureInPictureState,
    controlsVisibilityState: ControlsVisibilityState,
    tapGestureState: TapGestureState,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    volumeAndBrightnessGestureState: VolumeAndBrightnessGestureState,
    subtitleConfiguration: SubtitleConfiguration,
    decoderPriority: DecoderPriority,
    isGesturesEnabled: Boolean = true,
) {
    // decoder 切换重建 SurfaceView，重新绑定视频输出
    var surfaceRefreshKey by remember { mutableIntStateOf(0) }
    var previousDecoderPriority by remember { mutableStateOf(decoderPriority) }
    LaunchedEffect(decoderPriority) {
        if (previousDecoderPriority == decoderPriority) return@LaunchedEffect
        previousDecoderPriority = decoderPriority
        surfaceRefreshKey++
        delay(120)
        surfaceRefreshKey++
    }

    val presentationState = rememberPresentationState(player)
    val density = LocalDensity.current
    val textTracksState = rememberTracksState(player = player, trackType = C.TRACK_TYPE_TEXT)
    val isAssSubtitleSelected = textTracksState.tracks.any { track ->
        track.isSelected &&
            (0 until track.mediaTrackGroup.length).any { index ->
                val format = track.mediaTrackGroup.getFormat(index)
                format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA
            }
    }
    var lastLoggedSurfaceLayout by remember { mutableStateOf("") }

    // Media3 1.10.1 的 videoSizeDp 名带 Dp 但实际存视频原始 px；ASS wrapper 不触发 onVideoSizeChanged，回退 metadata
    val videoSizePx = presentationState.videoSizeDp ?: run {
        val w = videoZoomAndContentScaleState.metadataVideoWidth.toFloat()
        val h = videoZoomAndContentScaleState.metadataVideoHeight.toFloat()
        if (w <= 0f || h <= 0f) return@run null
        val rotation = videoZoomAndContentScaleState.metadataVideoRotation
        if (rotation == 90 || rotation == 270) Size(h, w) else Size(w, h)
    }

    key(surfaceRefreshKey) {
        BoxWithConstraints(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val containerWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val containerHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val videoWidth = videoSizePx?.width ?: containerWidth
            val videoHeight = (videoSizePx?.height ?: containerHeight).coerceAtLeast(1f)
            val fillX = containerWidth / videoWidth
            val fillY = containerHeight / videoHeight

            // SurfaceView 锁视频原始 px，避开 holder resize 异步竞态；graphicsLayer 缩放使 HUNDRED_PERCENT 1:1 无插值
            val (baseScaleX, baseScaleY) = when (videoZoomAndContentScaleState.videoContentScale) {
                VideoContentScale.STRETCH -> fillX to fillY
                VideoContentScale.BEST_FIT -> min(fillX, fillY).let { it to it }
                VideoContentScale.CROP -> max(fillX, fillY).let { it to it }
                VideoContentScale.HUNDRED_PERCENT -> 1f to 1f
            }
            val surfaceWidthDp = with(density) { videoWidth.toDp() }
            val surfaceHeightDp = with(density) { videoHeight.toDp() }

            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier
                    .requiredSize(surfaceWidthDp, surfaceHeightDp)
                    .graphicsLayer {
                        scaleX = baseScaleX * videoZoomAndContentScaleState.zoom
                        scaleY = baseScaleY * videoZoomAndContentScaleState.zoom
                        translationX = videoZoomAndContentScaleState.offset.x
                        translationY = videoZoomAndContentScaleState.offset.y
                    }
                    .onGloballyPositioned {
                        val bounds = it.boundsInWindow()
                        val rect = Rect(
                            bounds.left.toInt(),
                            bounds.top.toInt(),
                            bounds.right.toInt(),
                            bounds.bottom.toInt(),
                        )
                        val key = "${rect.width()}x${rect.height()}@${rect.left},${rect.top}:${videoZoomAndContentScaleState.videoContentScale}:${videoSizePx?.width}x${videoSizePx?.height}:$surfaceRefreshKey"
                        if (key != lastLoggedSurfaceLayout) {
                            lastLoggedSurfaceLayout = key
                            Logger.info(
                                TAG,
                                "Player surface layout size=${rect.width()}x${rect.height()} left=${rect.left} top=${rect.top} contentScale=${videoZoomAndContentScaleState.videoContentScale} videoPx=${videoSizePx?.width}x${videoSizePx?.height} coverSurface=${presentationState.coverSurface} refresh=$surfaceRefreshKey",
                            )
                        }
                        pictureInPictureState.updateVideoViewRect(rect)
                    },
            )

            if (!presentationState.coverSurface) {
                val subtitleModifier = if (isAssSubtitleSelected) {
                    val subtitleScale = min(fillX, fillY)
                    Modifier
                        .requiredSize(surfaceWidthDp, surfaceHeightDp)
                        .graphicsLayer {
                            scaleX = subtitleScale
                            scaleY = subtitleScale
                        }
                } else {
                    val subtitleWidthDp = with(density) { min(containerWidth, videoWidth * baseScaleX).toDp() }
                    val subtitleHeightDp = with(density) { min(containerHeight, videoHeight * baseScaleY).toDp() }
                    Modifier.requiredSize(subtitleWidthDp, subtitleHeightDp)
                }
                SubtitleView(
                    modifier = subtitleModifier,
                    player = player,
                    isInPictureInPictureMode = pictureInPictureState.isInPictureInPictureMode,
                    configuration = subtitleConfiguration,
                )
            }
        }
    }

    PlayerGestures(
        controlsVisibilityState = controlsVisibilityState,
        tapGestureState = tapGestureState,
        pictureInPictureState = pictureInPictureState,
        seekGestureState = seekGestureState,
        videoZoomAndContentScaleState = videoZoomAndContentScaleState,
        volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
        isEnabled = isGesturesEnabled,
    )

    if (presentationState.coverSurface) {
        ShutterView()
    }
}

private const val TAG = "PlayerContentFrame"
