package app.gyrolet.mpvrx.exoplayer.core.common.extensions

import android.content.res.Configuration

val Configuration.isPortrait: Boolean
    get() = orientation == Configuration.ORIENTATION_PORTRAIT
