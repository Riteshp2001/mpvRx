/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

val LocalAppWallpaperActive = compositionLocalOf { false }

/** True when the light theme is showing a dark wallpaper, so text placed directly on it must be light. */
val LocalLightTextOnWallpaper = compositionLocalOf { false }

/** Use for text/icons drawn directly on the wallpaper (not inside cards, chips or bars). */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun Color.onWallpaper(): Color =
  if (LocalLightTextOnWallpaper.current) lerp(this, Color.White, 0.9f).copy(alpha = this.alpha) else this

private const val LIGHT_TEXT_LUMINANCE_THRESHOLD = 0.55f

enum class WallpaperScaleMode {
  Fit,
  Fill,
}

@Composable
fun AppWallpaperHost(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val preferences = koinInject<AppearancePreferences>()
  val wallpaperUri by preferences.customWallpaperUri.collectAsState()
  val wallpaperZoom by preferences.customWallpaperZoom.collectAsState()
  val wallpaperOffsetX by preferences.customWallpaperOffsetX.collectAsState()
  val wallpaperOffsetY by preferences.customWallpaperOffsetY.collectAsState()
  val wallpaperScaleMode by preferences.customWallpaperScaleMode.collectAsState()
  val wallpaperBlur by preferences.customWallpaperBlur.collectAsState()
  val wallpaperAlpha by preferences.customWallpaperAlpha.collectAsState()
  // Hold the UI back until the first wallpaper load finishes, so the app doesn't flash a plain
  // background and then pop the wallpaper in on launch.
  var initialWallpaperResolved by remember { mutableStateOf(wallpaperUri.isBlank()) }
  val wallpaper =
    produceState<Bitmap?>(initialValue = null, wallpaperUri) {
      val loadedWallpaper =
        if (wallpaperUri.isBlank()) {
          null
        } else {
          try {
            withContext(Dispatchers.IO) { loadWallpaperBitmap(context, wallpaperUri) }
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
          }
        }
      value = loadedWallpaper
      initialWallpaperResolved = true
    }.value
  val contentAlpha by animateFloatAsState(
    targetValue = if (initialWallpaperResolved) 1f else 0f,
    animationSpec = tween(durationMillis = 180),
    label = "wallpaperContentAlpha",
  )
  DisposableEffect(wallpaper) {
    val displayedWallpaper = wallpaper
    onDispose {
      if (displayedWallpaper != null) {
        Handler(Looper.getMainLooper()).postDelayed(
          { if (!displayedWallpaper.isRecycled) displayedWallpaper.recycle() },
          WALLPAPER_RECYCLE_DELAY_MS,
        )
      }
    }
  }
  val wallpaperActive = wallpaper != null
  val isLightTheme = MaterialTheme.colorScheme.background.luminance() >= 0.5f
  val wallpaperLuminance =
    produceState(initialValue = 0.4f, wallpaper) {
      value = if (wallpaper == null) 0.4f else withContext(Dispatchers.Default) { wallpaper.estimateLuminance() }
    }.value
  // Light theme + dark wallpaper: show the wallpaper untouched and turn text drawn on it light.
  val lightTextOnWallpaper = wallpaperActive && isLightTheme && wallpaperLuminance < LIGHT_TEXT_LUMINANCE_THRESHOLD

  Box(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    wallpaper?.let { bitmap ->
      WallpaperImage(
        bitmap = bitmap,
        zoom = wallpaperZoom,
        offsetX = wallpaperOffsetX,
        offsetY = wallpaperOffsetY,
        scaleMode = wallpaperScaleMode,
        blurRadius = wallpaperBlur,
        imageAlpha = wallpaperAlpha,
        modifier = Modifier.fillMaxSize(),
      )
      Box(
        modifier = Modifier.fillMaxSize().background(rememberWallpaperScrimColor(bitmap)),
      )
    }
    CompositionLocalProvider(
      LocalAppWallpaperActive provides wallpaperActive,
      LocalLightTextOnWallpaper provides lightTextOnWallpaper,
    ) {
      Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha }) {
        content()
      }
    }
  }
}

@Composable
fun wallpaperAwareBackgroundColor(): Color =
  if (LocalAppWallpaperActive.current) Color.Transparent else MaterialTheme.colorScheme.background

@Composable
fun WallpaperImage(
  bitmap: Bitmap,
  zoom: Float,
  offsetX: Float,
  offsetY: Float,
  scaleMode: WallpaperScaleMode,
  blurRadius: Float = 0f,
  imageAlpha: Float = 1f,
  modifier: Modifier = Modifier,
) {
  val safeBlur = blurRadius.coerceIn(0f, MAX_WALLPAPER_BLUR_DP)
  val safeAlpha = imageAlpha.coerceIn(0f, 1f)
  Box(modifier = modifier.clipToBounds()) {
    if (scaleMode == WallpaperScaleMode.Fit) {
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier
            .fillMaxSize()
            .graphicsLayer {
              scaleX = 1.12f
              scaleY = 1.12f
              alpha = 0.72f * safeAlpha
            }.blur(28.dp),
      )
    }
    Image(
      bitmap = bitmap.asImageBitmap(),
      contentDescription = null,
      contentScale = if (scaleMode == WallpaperScaleMode.Fit) ContentScale.Fit else ContentScale.Crop,
      modifier =
        Modifier
          .fillMaxSize()
          .graphicsLayer {
            val safeZoom = zoom.coerceIn(1f, 3f)
            scaleX = safeZoom
            scaleY = safeZoom
            translationX = offsetX.coerceIn(-1f, 1f) * size.width * 0.35f
            translationY = offsetY.coerceIn(-1f, 1f) * size.height * 0.35f
            alpha = safeAlpha
          }.then(if (safeBlur > 0f) Modifier.blur(safeBlur.dp) else Modifier),
    )
  }
}

private const val MAX_WALLPAPER_BLUR_DP = 40f

/**
 * Scrim laid over the wallpaper so text stays readable. It is tinted with the theme background
 * (not flat white/black, which looked milky/grey) and its strength follows how bright the
 * wallpaper actually is: dark wallpapers get a stronger wash in light mode, bright wallpapers a
 * stronger dim in dark mode.
 */
fun wallpaperScrimColor(
  background: Color,
  wallpaperLuminance: Float,
): Color {
  val lum = wallpaperLuminance.coerceIn(0f, 1f)
  return if (background.luminance() < 0.5f) {
    Color.Black.copy(alpha = 0.16f + 0.30f * lum)
  } else {
    if (lum < LIGHT_TEXT_LUMINANCE_THRESHOLD) {
      // Dark wallpaper in light theme: keep it as it really is (no milky wash); text turns light instead.
      Color.Transparent
    } else {
      // Bright wallpaper: only a gentle wash so dark text stays readable.
      lerp(background, Color.White, 0.35f).copy(alpha = 0.10f + 0.20f * lum)
    }
  }
}

@Composable
fun rememberWallpaperScrimColor(bitmap: Bitmap?): Color {
  val background = MaterialTheme.colorScheme.background
  val luminance =
    produceState(initialValue = 0.4f, bitmap) {
      value =
        if (bitmap == null) {
          0.4f
        } else {
          withContext(Dispatchers.Default) { bitmap.estimateLuminance() }
        }
    }.value
  return remember(background, luminance) { wallpaperScrimColor(background, luminance) }
}

private fun Bitmap.estimateLuminance(): Float =
  runCatching {
    if (isRecycled) return@runCatching 0.4f
    val small = Bitmap.createScaledBitmap(this, 16, 16, true)
    val pixels = IntArray(16 * 16)
    small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
    if (small !== this) small.recycle()
    var sum = 0f
    for (px in pixels) {
      val r = ((px shr 16) and 0xFF) / 255f
      val g = ((px shr 8) and 0xFF) / 255f
      val b = (px and 0xFF) / 255f
      sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
    sum / pixels.size
  }.getOrDefault(0.4f)

/**
 * Real window size in portrait orientation, so code-drawn presets are rendered at the device's
 * own aspect ratio and fill it top to bottom with no cropping. Capped for memory.
 */
private fun presetTargetSize(context: android.content.Context): Pair<Int, Int> {
  val (rawW, rawH) =
    runCatching {
      val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
      if (android.os.Build.VERSION.SDK_INT >= 30) {
        val b = wm.maximumWindowMetrics.bounds
        b.width() to b.height()
      } else {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        m.widthPixels to m.heightPixels
      }
    }.getOrElse {
      val m = context.resources.displayMetrics
      m.widthPixels to m.heightPixels
    }
  val w = minOf(rawW, rawH).coerceAtLeast(1)
  val h = maxOf(rawW, rawH).coerceAtLeast(1)
  val scale = minOf(1f, PRESET_MAX_WIDTH_PX / w.toFloat(), PRESET_MAX_HEIGHT_PX / h.toFloat())
  return (w * scale).toInt().coerceAtLeast(2) to (h * scale).toInt().coerceAtLeast(2)
}

private const val PRESET_MAX_WIDTH_PX = 1080f
private const val PRESET_MAX_HEIGHT_PX = 2600f

suspend fun saveWallpaperCopy(
  context: android.content.Context,
  sourceUri: String,
): String = withContext(Dispatchers.IO) {
  // Presets are drawn in code, so only the tiny "preset:<id>" reference is stored.
  if (WallpaperPreset.isPresetUri(sourceUri)) return@withContext sourceUri
  val bitmap = requireNotNull(loadWallpaperBitmap(context, sourceUri)) {
    context.getString(app.gyrolet.mpvrx.R.string.wallpaper_save_failed)
  }
  try {
    if (sourceUri.startsWith(WALLPAPER_DATA_PREFIX)) return@withContext sourceUri
    val output = java.io.ByteArrayOutputStream()
    android.util.Base64OutputStream(output, android.util.Base64.NO_WRAP).use { encodedStream ->
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encodedStream)) {
        context.getString(app.gyrolet.mpvrx.R.string.wallpaper_save_failed)
      }
    }
    require(output.size() <= MAX_WALLPAPER_ENCODED_LENGTH) {
      context.getString(app.gyrolet.mpvrx.R.string.wallpaper_save_failed)
    }
    WALLPAPER_DATA_PREFIX + output.toString(Charsets.US_ASCII.name())
  } finally {
    bitmap.recycle()
  }
}

fun loadWallpaperBitmap(
  context: android.content.Context,
  wallpaperUri: String,
): Bitmap? =
  runCatching {
    WallpaperPreset.fromUri(wallpaperUri)?.let {
      val (presetW, presetH) = presetTargetSize(context)
      return@runCatching createWallpaperPresetBitmap(it, presetW, presetH)
    }
    val uri = Uri.parse(wallpaperUri)
    if (uri.scheme.equals("file", ignoreCase = true)) {
      val file = uri.path?.let { java.io.File(it) }?.takeIf { it.isFile && it.canRead() } ?: return@runCatching null
      if (file.length() == 0L) return@runCatching null
    }
    val imageBytes = if (uri.scheme.equals("data", ignoreCase = true)) {
      val header = wallpaperUri.substringBefore(',')
      if (!header.startsWith("data:image/", ignoreCase = true) || !header.endsWith(";base64", ignoreCase = true)) {
        return@runCatching null
      }
      val encoded = wallpaperUri.substringAfter(',', "")
      if (encoded.isEmpty() || encoded.length > MAX_WALLPAPER_ENCODED_LENGTH) return@runCatching null
      android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
    } else null
    fun decode(options: BitmapFactory.Options): Bitmap? =
      if (imageBytes != null) {
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
      } else if (uri.scheme.equals("file", ignoreCase = true)) {
        BitmapFactory.decodeFile(uri.path, options)
      } else {
        context.contentResolver.openInputStream(uri)?.use { stream ->
          BitmapFactory.decodeStream(stream, null, options)
        }
      }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_WALLPAPER_DIMENSION_PX) {
      sampleSize *= 2
    }
    decode(BitmapFactory.Options().apply { inSampleSize = sampleSize })
  }.getOrNull()

private const val WALLPAPER_DATA_PREFIX = "data:image/png;base64,"
private const val MAX_WALLPAPER_ENCODED_LENGTH = 40 * 1024 * 1024
private const val MAX_WALLPAPER_DIMENSION_PX = 2560
private const val WALLPAPER_RECYCLE_DELAY_MS = 120L

/** Same rule the host uses; lets the settings preview match the real screen. */
@Composable
fun rememberLightTextOnWallpaper(bitmap: Bitmap?): Boolean {
  val isLightTheme = MaterialTheme.colorScheme.background.luminance() >= 0.5f
  val luminance =
    produceState(initialValue = 0.4f, bitmap) {
      value =
        if (bitmap == null || bitmap.isRecycled) 0.4f else withContext(Dispatchers.Default) { bitmap.estimateLuminance() }
    }.value
  return bitmap != null && isLightTheme && luminance < LIGHT_TEXT_LUMINANCE_THRESHOLD
}
