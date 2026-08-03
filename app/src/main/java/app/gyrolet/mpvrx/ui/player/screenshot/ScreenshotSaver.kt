/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.screenshot

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ScreenshotFormat(
  val title: String,
  val mpvValue: String,
  val extension: String,
  val mimeType: String,
  val androidFallback: Boolean,
) {
  PNG("PNG", "png", "png", "image/png", true),
  JPG("JPG", "jpg", "jpg", "image/jpeg", true),
  JPEG("JPEG", "jpeg", "jpeg", "image/jpeg", true),
  WEBP("WebP", "webp", "webp", "image/webp", true),
}

data class ScreenshotSettings(
  val format: ScreenshotFormat = ScreenshotFormat.PNG,
  val template: String = "mpv_snapshot_%Y%m%d_%H%M%S",
  val quality: Int = 90,
  val pngCompression: Int = 7,
  val webpLossless: Boolean = false,
) {
  companion object {
    fun fromPreferences(preferences: PlayerPreferences): ScreenshotSettings =
      ScreenshotSettings(
        format = preferences.screenshotFormat.get(),
        template = preferences.screenshotTemplate.get(),
        quality = preferences.screenshotQuality.get(),
        pngCompression = preferences.screenshotPngCompression.get(),
        webpLossless = preferences.screenshotWebpLossless.get(),
      )
  }
}

data class ScreenshotSaveResult(
  val displayName: String,
  val uri: Uri?,
  val file: File?,
)

object ScreenshotSaver {
  private const val SNAPSHOT_FOLDER = "mpvSnaps"

  suspend fun save(
    context: Context,
    settings: ScreenshotSettings,
    includeSubtitles: Boolean,
  ): Result<ScreenshotSaveResult> =
    withContext(Dispatchers.IO) {
      runCatching {
        applyMpvScreenshotOptions(settings)
        val displayName =
          ScreenshotTemplate.buildFileName(
            template = settings.template,
            extension = settings.format.extension,
            filename = MPVLib.getPropertyString("filename"),
            filenameNoExt = MPVLib.getPropertyString("filename/no-ext"),
            mediaTitle = MPVLib.getPropertyString("media-title"),
            path = MPVLib.getPropertyString("path"),
            positionSeconds = MPVLib.getPropertyDouble("time-pos") ?: 0.0,
          )
        val tempFile =
          captureNative(context, settings, includeSubtitles)
            ?: captureWithAndroidFallback(context, settings, includeSubtitles)
            ?: error("mpv does not support ${settings.format.title} format on this device")

        saveToPictures(context, tempFile, displayName, settings.format).also {
          tempFile.delete()
        }
      }
    }

  fun applyMpvScreenshotOptions(settings: ScreenshotSettings) {
    MPVLib.setOptionString("screenshot-format", settings.format.mpvValue)
    MPVLib.setOptionString("screenshot-template", settings.template)
    MPVLib.setOptionString("screenshot-jpeg-quality", settings.quality.coerceIn(0, 100).toString())
    MPVLib.setOptionString("screenshot-webp-quality", settings.quality.coerceIn(0, 100).toString())
    MPVLib.setOptionString("screenshot-png-compression", settings.pngCompression.coerceIn(0, 9).toString())
    MPVLib.setOptionString("screenshot-webp-lossless", if (settings.webpLossless) "yes" else "no")
  }

  private suspend fun captureNative(
    context: Context,
    settings: ScreenshotSettings,
    includeSubtitles: Boolean,
  ): File? {
    val tempFile = File(context.cacheDir, "mpvrx_snapshot_native.${settings.format.extension}")
    tempFile.delete()
    MPVLib.command("screenshot-to-file", tempFile.absolutePath, if (includeSubtitles) "subtitles" else "video")
    delay(250)
    return tempFile.takeIf { it.exists() && it.length() > 0L }
  }

  private suspend fun captureWithAndroidFallback(
    context: Context,
    settings: ScreenshotSettings,
    includeSubtitles: Boolean,
  ): File? {
    if (!settings.format.androidFallback) return null

    val sourcePng = File(context.cacheDir, "mpvrx_snapshot_fallback_source.png")
    sourcePng.delete()
    MPVLib.setOptionString("screenshot-format", "png")
    MPVLib.command("screenshot-to-file", sourcePng.absolutePath, if (includeSubtitles) "subtitles" else "video")
    delay(250)
    if (!sourcePng.exists() || sourcePng.length() == 0L) return null

    if (settings.format == ScreenshotFormat.PNG) {
      return sourcePng
    }

    val bitmap = BitmapFactory.decodeFile(sourcePng.absolutePath) ?: return null
    val output = File(context.cacheDir, "mpvrx_snapshot_fallback.${settings.format.extension}")
    output.delete()
    output.outputStream().use { stream ->
      if (!bitmap.compress(settings.compressFormat(), settings.compressQuality(), stream)) {
        error("Android encoder failed for ${settings.format.title}")
      }
    }
    bitmap.recycle()
    sourcePng.delete()
    applyMpvScreenshotOptions(settings)
    return output.takeIf { it.exists() && it.length() > 0L }
  }

  @Suppress("DEPRECATION")
  private fun ScreenshotSettings.compressFormat(): Bitmap.CompressFormat =
    when (format) {
      ScreenshotFormat.JPG,
      ScreenshotFormat.JPEG,
      -> Bitmap.CompressFormat.JPEG
      ScreenshotFormat.WEBP ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && webpLossless) {
          Bitmap.CompressFormat.WEBP_LOSSLESS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          Bitmap.CompressFormat.WEBP_LOSSY
        } else {
          Bitmap.CompressFormat.WEBP
        }
      ScreenshotFormat.PNG -> Bitmap.CompressFormat.PNG
    }

  private fun ScreenshotSettings.compressQuality(): Int =
    when (format) {
      ScreenshotFormat.PNG -> (100 - pngCompression.coerceIn(0, 9) * 10).coerceIn(0, 100)
      ScreenshotFormat.WEBP ->
        if (webpLossless &&
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
          100
        } else {
          quality.coerceIn(0, 100)
        }
      else -> quality.coerceIn(0, 100)
    }

  private fun saveToPictures(
    context: Context,
    tempFile: File,
    displayName: String,
    format: ScreenshotFormat,
  ): ScreenshotSaveResult {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values =
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
          put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
          put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SNAPSHOT_FOLDER")
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
      val resolver = context.contentResolver
      val uri =
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
          ?: error("Failed to create MediaStore entry")
      resolver.openOutputStream(uri)?.use { output ->
        tempFile.inputStream().use { input -> input.copyTo(output) }
      } ?: error("Failed to open MediaStore output")
      values.clear()
      values.put(MediaStore.Images.Media.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
      return ScreenshotSaveResult(displayName = displayName, uri = uri, file = null)
    }

    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val snapshotsDir =
      File(picturesDir, SNAPSHOT_FOLDER).apply {
        if (!exists()) mkdirs()
      }
    val destFile = uniqueFile(snapshotsDir, displayName)
    tempFile.copyTo(destFile, overwrite = false)
    MediaScannerConnection.scanFile(
      context,
      arrayOf(destFile.absolutePath),
      arrayOf(format.mimeType),
      null,
    )
    return ScreenshotSaveResult(displayName = destFile.name, uri = null, file = destFile)
  }

  private fun uniqueFile(
    directory: File,
    displayName: String,
  ): File {
    val baseName = displayName.substringBeforeLast('.', displayName)
    val extension = displayName.substringAfterLast('.', "")
    var candidate = File(directory, displayName)
    var index = 1
    while (candidate.exists()) {
      val suffix = if (extension.isBlank()) "_$index" else "_$index.$extension"
      candidate = File(directory, "$baseName$suffix")
      index++
    }
    return candidate
  }
}

object ScreenshotTemplate {
  private val invalidFileChars = Regex("""[\u0000-\u001F\\/:*?"<>|]""")
  private val whitespace = Regex("""\s+""")

  fun buildFileName(
    template: String,
    extension: String,
    now: LocalDateTime = LocalDateTime.now(),
    filename: String? = null,
    filenameNoExt: String? = null,
    mediaTitle: String? = null,
    path: String? = null,
    positionSeconds: Double = 0.0,
  ): String {
    val resolvedFilename =
      filename?.ifBlank { null }
        ?: path?.substringAfterLast('/')?.ifBlank { null }
        ?: mediaTitle?.ifBlank { null }
        ?: "video"

    val resolvedFilenameNoExt =
      filenameNoExt?.ifBlank { null }
        ?: resolvedFilename.substringBeforeLast('.')

    val posSec = positionSeconds.coerceAtLeast(0.0)
    val totalMs = (posSec * 1000.0).toLong()
    val hours = totalMs / 3600000
    val minutes = (totalMs % 3600000) / 60000
    val seconds = (totalMs % 60000) / 1000
    val milliseconds = totalMs % 1000

    val wHStr = String.format(Locale.US, "%02d", hours)
    val wMStr = String.format(Locale.US, "%02d", minutes)
    val wSStr = String.format(Locale.US, "%02d", seconds)
    val wTStr = String.format(Locale.US, "%03d", milliseconds)

    val rendered =
      template
        .ifBlank { "mpv_snapshot_%Y%m%d_%H%M%S" }
        .replace("%wH", wHStr)
        .replace("%wM", wMStr)
        .replace("%wS", wSStr)
        .replace("%wT", wTStr)
        .replace("%Y", now.format(DateTimeFormatter.ofPattern("yyyy")))
        .replace("%m", now.format(DateTimeFormatter.ofPattern("MM")))
        .replace("%d", now.format(DateTimeFormatter.ofPattern("dd")))
        .replace("%H", now.format(DateTimeFormatter.ofPattern("HH")))
        .replace("%M", now.format(DateTimeFormatter.ofPattern("mm")))
        .replace("%S", now.format(DateTimeFormatter.ofPattern("ss")))
        .replace("%f", resolvedFilename)
        .replace("%F", resolvedFilenameNoExt)
        .replace("%P", "$wHStr:$wMStr:$wSStr.$wTStr")
        .replace("%p", "$wHStr:$wMStr:$wSStr")

    val sanitized =
      rendered
        .replace(invalidFileChars, "_")
        .replace(whitespace, " ")
        .trim()
        .trim('.', ' ')
        .ifBlank { "mpv_snapshot" }
        .take(120)
        .trimEnd('.', ' ', '_')
        .ifBlank { "mpv_snapshot" }
    return "${sanitized.lowercaseExtension(extension)}.${extension.lowercase(Locale.US)}"
  }

  private fun String.lowercaseExtension(extension: String): String =
    removeSuffix(".${extension.lowercase(Locale.US)}")
      .removeSuffix(".${extension.uppercase(Locale.US)}")
}
