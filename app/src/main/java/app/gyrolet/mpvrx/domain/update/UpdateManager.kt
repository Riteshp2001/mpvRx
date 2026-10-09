/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.update

import android.content.Context
import android.os.Build
import app.gyrolet.mpvrx.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Signals that the resuming attempt must be abandoned and retried without a Range header. */
private object RetryWithoutRangeSignal : IOException("Stored partial is not resumable")

class UpdateManager(
  private val context: Context,
) {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun checkForUpdate(
    channel: AppUpdateChannel,
    forceShow: Boolean = false,
  ): Release? {
    // Return null immediately if update checks are disabled for this build.
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return null
    }

    val release =
      getLatestRelease(
        when (channel) {
          AppUpdateChannel.STABLE -> STABLE_RELEASE_URL
          AppUpdateChannel.PREVIEW -> PREVIEW_RELEASE_URL
        },
      )
    if (selectBestApkAsset(release.assets) == null) {
      return null
    }
    val prefs = context.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
    val ignoredVersion =
      prefs.getString(ignoredVersionKey(channel), null)
        ?: if (channel == AppUpdateChannel.STABLE) prefs.getString(LEGACY_IGNORED_VERSION_KEY, null) else null

    // If this version was ignored, don't show it unless forced (manual check)
    val isIgnored =
      ignoredVersion == release.tagName ||
        (channel == AppUpdateChannel.STABLE && ignoredVersion == release.tagName.removePrefix("v"))
    if (!forceShow && isIgnored) {
      return null
    }

    val isNewer =
      when (channel) {
        AppUpdateChannel.STABLE -> {
          val currentVersion = BuildConfig.VERSION_NAME.substringBefore('-')
          isNewerVersion(release.tagName.removePrefix("v"), currentVersion)
        }
        AppUpdateChannel.PREVIEW -> {
          val remoteCommitCount = release.commitCount ?: parsePreviewCommitCount(release.tagName)
          remoteCommitCount != null && remoteCommitCount > BuildConfig.GIT_COUNT
        }
      }

    return if (isNewer) {
      release
    } else {
      null
    }
  }

  fun ignoreVersion(
    version: String,
    channel: AppUpdateChannel,
  ) {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    val prefs = context.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
    prefs
      .edit()
      .putString(ignoredVersionKey(channel), version)
      .apply()
  }

  private suspend fun getLatestRelease(url: String): Release =
    withContext(Dispatchers.IO) {
      val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val responseBody = response.body.string()
        json.decodeFromString<Release>(responseBody)
      }
    }

  private fun isNewerVersion(
    remote: String,
    current: String,
  ): Boolean {
    val rParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
    val cParts = current.split(".").map { it.toIntOrNull() ?: 0 }

    for (i in 0 until maxOf(rParts.size, cParts.size)) {
      val r = rParts.getOrElse(i) { 0 }
      val c = cParts.getOrElse(i) { 0 }
      if (r > c) return true
      if (r < c) return false
    }
    return false
  }

  private fun parsePreviewCommitCount(tagName: String): Int? =
    PREVIEW_TAG_REGEX.find(tagName)?.groupValues?.getOrNull(1)?.toIntOrNull()

  private fun ignoredVersionKey(channel: AppUpdateChannel): String =
    "ignored_version_${channel.name.lowercase()}"

  fun downloadUpdate(release: Release): Flow<Float> {
    // Return completed flow immediately if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return flowOf(100f)
    }

    val asset =
      selectBestApkAsset(release.assets)
        ?: throw Exception("No compatible APK asset found")

    val destination = File(context.externalCacheDir, asset.name)
    return downloadApk(asset.downloadUrl, destination)
  }

  private fun selectBestApkAsset(assets: List<Asset>): Asset? {
    val deviceArch = getDeviceArchitecture()
    val compatibleAssets =
      assets.filter { asset ->
        asset.name.startsWith("mpvRx-", ignoreCase = true) &&
          asset.name.endsWith(".apk", ignoreCase = true) &&
          asset.name.matchesApkVariant(BuildConfig.UPDATE_APK_VARIANT)
      }

    // First, try to find architecture-specific APK
    val archSpecificApk =
      compatibleAssets.firstOrNull { asset ->
        asset.name.hasAssetToken(deviceArch)
      }

    if (archSpecificApk != null) {
      return archSpecificApk
    }

    // Fallback to universal APK
    val universalApk =
      compatibleAssets.firstOrNull { asset ->
        asset.name.hasAssetToken("universal")
      }

    if (universalApk != null) {
      return universalApk
    }

    // FongMi and No-Vulkan universal assets use the flavor marker instead of "universal".
    return compatibleAssets.firstOrNull { asset ->
      SUPPORTED_ARCHITECTURES.none { architecture -> asset.name.hasAssetToken(architecture) }
    }
  }

  private fun String.matchesApkVariant(variant: String): Boolean {
    val isFongMi = hasAssetToken("fongmi")
    val isNoVulkan = hasAssetToken("no-vulkan")
    return when (variant) {
      "fongmi" -> isFongMi
      "no-vulkan" -> isNoVulkan
      "standard" -> !isFongMi && !isNoVulkan
      else -> false
    }
  }

  private fun String.hasAssetToken(token: String): Boolean =
    Regex(
      pattern = "(?:^|-)${Regex.escape(token)}(?:-|\\.apk$)",
      option = RegexOption.IGNORE_CASE,
    ).containsMatchIn(this)

  private fun getDeviceArchitecture(): String {
    // Get the primary ABI (Application Binary Interface)
    val primaryAbi =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        Build.SUPPORTED_ABIS[0]
      } else {
        @Suppress("DEPRECATION")
        Build.CPU_ABI
      }

    // Map Android ABI names to your APK naming convention
    return when (primaryAbi) {
      "arm64-v8a" -> "arm64-v8a"
      "armeabi-v7a" -> "armeabi-v7a"
      "x86" -> "x86"
      "x86_64" -> "x86_64"
      else -> "universal" // Fallback for unknown architectures
    }
  }

  /**
   * Downloads to a sidecar `.part` file and promotes it on success, so an interrupted download
   * never leaves a truncated APK that [getApkFile] would happily offer to install. When a `.part`
   * already exists the request asks for the remaining byte range, so closing and reopening the app
   * continues where it stopped instead of starting over. Servers that ignore Range answer 200 with
   * the whole body, which is detected and treated as a fresh start.
   */
  private fun downloadApk(
    url: String,
    destination: File,
  ): Flow<Float> =
    flow {
      val partial = File(destination.absolutePath + PART_SUFFIX)
      try {
        emitAll(downloadAttempt(url, destination, partial, allowResume = true))
        return@flow
      } catch (_: RetryWithoutRangeSignal) {
        // Stored partial is unusable; the attempt already discarded it, so start clean.
      }
      emitAll(downloadAttempt(url, destination, partial, allowResume = false))
    }.flowOn(Dispatchers.IO)

  private fun downloadAttempt(
    url: String,
    destination: File,
    partial: File,
    allowResume: Boolean,
  ): Flow<Float> =
    flow {
      val alreadyDownloaded = if (allowResume) partial.takeIf { it.isFile }?.length() ?: 0L else 0L

      val request =
        Request.Builder()
          .url(url)
          .apply { if (alreadyDownloaded > 0L) header("Range", "bytes=$alreadyDownloaded-") }
          .build()

      client.newCall(request).execute().use { response ->
        if (response.code == HTTP_RANGE_NOT_SATISFIABLE && allowResume) {
          partial.delete()
          throw RetryWithoutRangeSignal
        }
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        val body = response.body
        // 206 means the server honoured our Range. A plain 200 means it ignored it and is sending
        // the whole asset, so the stored partial must be discarded rather than appended to.
        val resuming = response.code == HTTP_PARTIAL_CONTENT
        val appending = resuming && alreadyDownloaded > 0L
        if (!appending) partial.delete()

        val totalBytes =
          body.contentLength().takeIf { it > 0L }?.let { if (resuming) alreadyDownloaded + it else it }
        val inputStream = body.byteStream()
        val outputStream = FileOutputStream(partial, appending)

        try {
          val buffer = ByteArray(8 * 1024)
          var bytesRead: Int
          var totalBytesRead: Long = if (appending) alreadyDownloaded else 0L

          if (totalBytesRead > 0L) emit(progressOf(totalBytesRead, totalBytes))

          while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            emit(progressOf(totalBytesRead, totalBytes))
          }
          outputStream.flush()

          if (totalBytes != null && totalBytesRead < totalBytes) {
            throw IOException("Incomplete download: $totalBytesRead of $totalBytes bytes")
          }
          if (destination.exists()) destination.delete()
          if (!partial.renameTo(destination)) {
            throw IOException("Could not move the downloaded APK into place")
          }
          emit(100f)
        } finally {
          runCatching { inputStream.close() }
          runCatching { outputStream.close() }
        }
      }
    }

  private fun progressOf(downloaded: Long, total: Long?): Float =
    if (total != null && total > 0L) (downloaded.toFloat() / total.toFloat()) * 100f else -1f

  /**
   * Progress already banked from a previous session, so the UI can show a resumed download starting
   * from where it stopped instead of snapping back to 0%.
   */
  suspend fun getResumableProgress(release: Release): Float? {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return null
    val asset = selectBestApkAsset(release.assets) ?: return null
    val partial = File(File(context.externalCacheDir, asset.name).absolutePath + PART_SUFFIX)
    val downloaded = partial.takeIf { it.isFile }?.takeIf { it.length() > 0L }?.length() ?: return null
    val total = resolveAssetLength(asset) ?: return null
    if (total <= downloaded) return null
    return progressOf(downloaded, total)
  }

  private suspend fun resolveAssetLength(asset: Asset): Long? =
    withContext(Dispatchers.IO) {
      runCatching {
        val request = Request.Builder().url(asset.downloadUrl).method("HEAD", null).build()
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) return@use null
          response.header("Content-Length")?.toLongOrNull()
            ?: response.body.contentLength().takeIf { it > 0L }
        }
      }.getOrNull()
    }

  fun getApkFile(release: Release): File? {
    // Return null if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return null
    }

    val asset = selectBestApkAsset(release.assets) ?: return null
    val file = File(context.externalCacheDir, asset.name)
    return if (file.exists()) file else null
  }

  fun clearCache() {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    context.externalCacheDir?.listFiles()?.forEach {
      if (it.name.endsWith(".apk") || it.name.endsWith(PART_SUFFIX)) it.delete()
    }
  }

  private companion object {
    const val STABLE_RELEASE_URL = "https://api.github.com/repos/Riteshp2001/mpvRx/releases/latest"
    const val PREVIEW_RELEASE_URL = "https://riteshp2001.github.io/mpvRx/latest.json"
    const val LEGACY_IGNORED_VERSION_KEY = "ignored_version"
    const val PART_SUFFIX = ".part"
    const val HTTP_PARTIAL_CONTENT = 206
    const val HTTP_RANGE_NOT_SATISFIABLE = 416
    val PREVIEW_TAG_REGEX = Regex("""(?:preview-)?r(\d+)""", RegexOption.IGNORE_CASE)
    val SUPPORTED_ARCHITECTURES = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
  }
}
