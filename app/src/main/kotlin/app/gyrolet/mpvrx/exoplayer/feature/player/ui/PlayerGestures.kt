package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.detectCustomHorizontalDragGestures
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.detectCustomTransformGestures
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.detectCustomVerticalDragGestures
import app.gyrolet.mpvrx.exoplayer.feature.player.state.ControlsVisibilityState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.PictureInPictureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SeekGestureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.TapGestureState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.VideoZoomAndContentScaleState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.VolumeAndBrightnessGestureState

@Composable
fun PlayerGestures(
    modifier: Modifier = Modifier,
    controlsVisibilityState: ControlsVisibilityState,
    tapGestureState: TapGestureState,
    pictureInPictureState: PictureInPictureState,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    volumeAndBrightnessGestureState: VolumeAndBrightnessGestureState,
    isEnabled: Boolean = true,
) {
    BoxWithConstraints {
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("player_gesture_surface")
                .pointerInput(
                    isEnabled,
                    controlsVisibilityState.isControlsLocked,
                    pictureInPictureState.isInPictureInPictureMode,
                ) {
                    if (!isEnabled) return@pointerInput
                    if (controlsVisibilityState.isControlsLocked) return@pointerInput
                    if (pictureInPictureState.isInPictureInPictureMode) return@pointerInput

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

                        tapGestureState.handleLongPress()
                        if (!tapGestureState.isLongPressGestureInAction) return@awaitEachGesture

                        try {
                            longPress.consume()
                            var pointerId = longPress.id
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull()
                                    ?: break

                                pointerId = change.id
                                if (!change.pressed) break

                                val dragAmount = change.position.x - change.previousPosition.x
                                if (dragAmount != 0f) {
                                    change.consume()
                                    tapGestureState.handleLongPressHorizontalDrag(dragAmount)
                                }
                            }
                        } finally {
                            tapGestureState.handleOnLongPressRelease()
                        }
                    }
                }
                .pointerInput(isEnabled, pictureInPictureState.isInPictureInPictureMode) {
                    if (!isEnabled) return@pointerInput
                    if (pictureInPictureState.isInPictureInPictureMode) return@pointerInput

                    detectTapGestures(
                        onTap = {
                            if (tapGestureState.seekMillis != 0L) return@detectTapGestures
                            controlsVisibilityState.toggleControlsVisibility()
                        },
                        onDoubleTap = {
                            if (controlsVisibilityState.isControlsLocked) return@detectTapGestures
                            tapGestureState.handleDoubleTap(offset = it, size = size)
                        },
                    )
                }
                .pointerInput(
                    isEnabled,
                    controlsVisibilityState.isControlsLocked,
                    pictureInPictureState.isInPictureInPictureMode,
                ) {
                    if (!isEnabled) return@pointerInput
                    if (controlsVisibilityState.isControlsLocked) return@pointerInput
                    if (pictureInPictureState.isInPictureInPictureMode) return@pointerInput

                    detectCustomHorizontalDragGestures(
                        onDragStart = {
                            if (tapGestureState.isLongPressGestureCaptured) return@detectCustomHorizontalDragGestures
                            seekGestureState.onDragStart(it)
                            if (seekGestureState.isSeeking) {
                                controlsVisibilityState.hideControls()
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (tapGestureState.isLongPressGestureCaptured) {
                                change.consume()
                                return@detectCustomHorizontalDragGestures
                            }
                            seekGestureState.onDrag(change, dragAmount)
                        },
                        onDragCancel = {
                            if (tapGestureState.isLongPressGestureCaptured) return@detectCustomHorizontalDragGestures
                            seekGestureState.onDragEnd()
                        },
                        onDragEnd = {
                            if (tapGestureState.isLongPressGestureCaptured) return@detectCustomHorizontalDragGestures
                            seekGestureState.onDragEnd()
                        },
                    )
                }
                .pointerInput(
                    isEnabled,
                    controlsVisibilityState.isControlsLocked,
                    pictureInPictureState.isInPictureInPictureMode,
                    tapGestureState.isLongPressGestureInAction,
                ) {
                    if (!isEnabled) return@pointerInput
                    if (controlsVisibilityState.isControlsLocked) return@pointerInput
                    if (pictureInPictureState.isInPictureInPictureMode) return@pointerInput
                    if (tapGestureState.isLongPressGestureInAction) return@pointerInput

                    detectCustomVerticalDragGestures(
                        onDragStart = { volumeAndBrightnessGestureState.onDragStart(it, size) },
                        onVerticalDrag = volumeAndBrightnessGestureState::onDrag,
                        onDragCancel = volumeAndBrightnessGestureState::onDragEnd,
                        onDragEnd = volumeAndBrightnessGestureState::onDragEnd,
                    )
                }
                .pointerInput(
                    isEnabled,
                    controlsVisibilityState.isControlsLocked,
                    pictureInPictureState.isInPictureInPictureMode,
                ) {
                    if (!isEnabled) return@pointerInput
                    if (controlsVisibilityState.isControlsLocked) return@pointerInput
                    if (pictureInPictureState.isInPictureInPictureMode) return@pointerInput

                    detectCustomTransformGestures(
                        onGesture = { _, panChange, zoomChange, _ ->
                            if (tapGestureState.isLongPressGestureInAction) return@detectCustomTransformGestures
                            videoZoomAndContentScaleState.onZoomPanGesture(
                                constraints = this@BoxWithConstraints.constraints,
                                panChange = panChange,
                                zoomChange = zoomChange,
                            )
                        },
                        onGestureEnd = {
                            videoZoomAndContentScaleState.onZoomPanGestureEnd()
                        },
                    )
                },
        )
    }
}
