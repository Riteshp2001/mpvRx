package app.gyrolet.mpvrx.exoplayer.feature.player.extensions

import android.graphics.Typeface
import app.gyrolet.mpvrx.exoplayer.core.model.Font

fun Font.toTypeface(): Typeface = when (this) {
    Font.DEFAULT -> Typeface.DEFAULT
    Font.MONOSPACE -> Typeface.MONOSPACE
    Font.SANS_SERIF -> Typeface.SANS_SERIF
    Font.SERIF -> Typeface.SERIF
}
