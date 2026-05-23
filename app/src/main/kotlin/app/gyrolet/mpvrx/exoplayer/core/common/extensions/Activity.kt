package app.gyrolet.mpvrx.exoplayer.core.common.extensions

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.WindowManager
import androidx.annotation.ColorInt
import androidx.core.view.WindowCompat

fun Activity.applyNavigationBarStyle(
    @ColorInt color: Int,
    shouldUseDarkIcons: Boolean,
) {
    if (window.navigationBarColor != color) {
        window.navigationBarColor = color
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        if (window.isNavigationBarContrastEnforced) {
            window.isNavigationBarContrastEnforced = false
        }
    }


    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    if (insetsController.isAppearanceLightNavigationBars != shouldUseDarkIcons) {
        insetsController.isAppearanceLightNavigationBars = shouldUseDarkIcons
    }
}

fun Activity.applyPrivacyProtection(
    shouldPreventScreenshots: Boolean,
    shouldHideInRecents: Boolean,
) {
    if (shouldPreventScreenshots) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(!shouldHideInRecents)
    }

    applyPrivacyTaskDescription(shouldHideInRecents = shouldHideInRecents)
}

@ColorInt
fun Activity.resolvePrivacyPreviewColor(): Int {
    val typedValue = TypedValue()
    val isResolved = theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
    if (!isResolved) return Color.BLACK

    return when {
        typedValue.resourceId != 0 -> runCatching { getColor(typedValue.resourceId) }.getOrDefault(Color.BLACK)
        typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> typedValue.data
        else -> Color.BLACK
    }
}

@ColorInt
fun Activity.resolvePrivacyPreviewScrim(shouldHideInRecents: Boolean): Int = if (shouldHideInRecents) {
    resolvePrivacyPreviewColor()
} else {
    Color.TRANSPARENT
}

private fun Activity.applyPrivacyTaskDescription(shouldHideInRecents: Boolean) {
    val taskColor = if (shouldHideInRecents) {
        resolvePrivacyPreviewColor()
    } else {
        null
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val builder = ActivityManager.TaskDescription.Builder()
        taskColor?.let { color ->
            builder.setPrimaryColor(color)
            builder.setBackgroundColor(color)
            builder.setStatusBarColor(color)
            builder.setNavigationBarColor(color)
        }
        setTaskDescription(builder.build())
        return
    }

    @Suppress("DEPRECATION")
    setTaskDescription(
        ActivityManager.TaskDescription(
            title?.toString(),
            null,
            taskColor ?: Color.BLACK,
        ),
    )
}
