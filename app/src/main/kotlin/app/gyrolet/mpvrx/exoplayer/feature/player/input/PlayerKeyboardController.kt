package app.gyrolet.mpvrx.exoplayer.feature.player.input

import android.view.KeyEvent

class PlayerKeyboardController(
    private val onSeekBackward: () -> Unit,
    private val onSeekForward: () -> Unit,
    private val onIncreaseVolume: () -> Unit,
    private val onDecreaseVolume: () -> Unit,
    private val onTogglePlayPause: () -> Unit,
    private val onStartTemporarySpeed: () -> Boolean,
    private val onStopTemporarySpeed: () -> Unit,
) {

    private var isLeftKeyPressed = false
    private var isRightKeyPressed = false
    private var isSpaceKeyPressed = false
    private var hasSpaceLongPressStarted = false
    private var isSpaceTemporarySpeedActive = false

    fun handleKeyEvent(event: KeyEvent): Boolean = handleKeyEvent(
        action = event.action,
        keyCode = event.keyCode,
        repeatCount = event.repeatCount,
        isCanceled = event.isCanceled,
    )

    internal fun handleKeyEvent(
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        isCanceled: Boolean = false,
    ): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> handleLeftKey(action = action, isCanceled = isCanceled)
        KeyEvent.KEYCODE_DPAD_RIGHT -> handleRightKey(
            action = action,
            isCanceled = isCanceled,
        )

        KeyEvent.KEYCODE_DPAD_UP -> handleVolumeKey(action = action, onActionDown = onIncreaseVolume)
        KeyEvent.KEYCODE_DPAD_DOWN -> handleVolumeKey(action = action, onActionDown = onDecreaseVolume)
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_DPAD_CENTER,
        -> handleTogglePlaybackKey(action = action, isCanceled = isCanceled)
        KeyEvent.KEYCODE_SPACE -> handleSpaceKey(
            action = action,
            repeatCount = repeatCount,
            isCanceled = isCanceled,
        )

        else -> false
    }

    private fun handleLeftKey(action: Int, isCanceled: Boolean): Boolean = when (action) {
        KeyEvent.ACTION_DOWN -> {
            isLeftKeyPressed = true
            true
        }

        KeyEvent.ACTION_UP -> {
            val shouldSeekBackward = isLeftKeyPressed && !isCanceled
            isLeftKeyPressed = false
            if (shouldSeekBackward) {
                onSeekBackward()
            }
            true
        }

        else -> false
    }

    private fun handleRightKey(action: Int, isCanceled: Boolean): Boolean = when (action) {
        KeyEvent.ACTION_DOWN -> {
            isRightKeyPressed = true
            true
        }

        KeyEvent.ACTION_UP -> {
            val shouldSeekForward = isRightKeyPressed && !isCanceled
            isRightKeyPressed = false
            if (shouldSeekForward) {
                onSeekForward()
            }
            true
        }

        else -> false
    }

    private fun handleVolumeKey(action: Int, onActionDown: () -> Unit): Boolean = when (action) {
        KeyEvent.ACTION_DOWN -> {
            onActionDown()
            true
        }

        KeyEvent.ACTION_UP -> true
        else -> false
    }

    private fun handleSpaceKey(action: Int, repeatCount: Int, isCanceled: Boolean): Boolean = when (action) {
        KeyEvent.ACTION_DOWN -> {
            isSpaceKeyPressed = true
            if (repeatCount > 0 && !hasSpaceLongPressStarted) {
                val didStartTemporarySpeed = onStartTemporarySpeed()
                hasSpaceLongPressStarted = didStartTemporarySpeed
                isSpaceTemporarySpeedActive = didStartTemporarySpeed
            }
            true
        }

        KeyEvent.ACTION_UP -> {
            val shouldTogglePlayback = isSpaceKeyPressed && !hasSpaceLongPressStarted && !isCanceled
            if (isSpaceTemporarySpeedActive) {
                onStopTemporarySpeed()
            }
            resetSpaceKeyState()
            if (shouldTogglePlayback) {
                onTogglePlayPause()
            }
            true
        }

        else -> false
    }

    private fun handleTogglePlaybackKey(action: Int, isCanceled: Boolean): Boolean = when (action) {
        KeyEvent.ACTION_DOWN -> true
        KeyEvent.ACTION_UP -> {
            if (!isCanceled) {
                onTogglePlayPause()
            }
            true
        }

        else -> false
    }

    private fun resetSpaceKeyState() {
        isSpaceKeyPressed = false
        hasSpaceLongPressStarted = false
        isSpaceTemporarySpeedActive = false
    }
}
