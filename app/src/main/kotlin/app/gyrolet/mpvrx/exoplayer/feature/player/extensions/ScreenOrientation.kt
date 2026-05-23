package app.gyrolet.mpvrx.exoplayer.feature.player.extensions

import android.content.pm.ActivityInfo
import app.gyrolet.mpvrx.exoplayer.core.model.LastPlayerScreenOrientation
import app.gyrolet.mpvrx.exoplayer.core.model.ScreenOrientation

fun LastPlayerScreenOrientation.toActivityOrientation(): Int = when (this) {
    LastPlayerScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    LastPlayerScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

fun ScreenOrientation.toActivityOrientation(videoOrientation: Int? = null): Int = when (this) {
    ScreenOrientation.AUTOMATIC -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
    ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    ScreenOrientation.LANDSCAPE_REVERSE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    ScreenOrientation.LANDSCAPE_AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    ScreenOrientation.VIDEO_ORIENTATION -> videoOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
