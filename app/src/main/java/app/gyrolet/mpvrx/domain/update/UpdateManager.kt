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
import androidx.core.content.pm.PackageInfoCompat
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.network.awaitResponse
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Signals that the resuming attempt must be abandoned and retried without a Range header. */
private object RetryWithoutRangeSignal : IOException("Stored partial is not resumable")

class UpdateManager(
  private val context: Context,
) {
  private val client = OkHttpClient()
  private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
  }

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
          AppUpdateChannel.EXPERIMENTAL -> EXPERIMENTAL_RELEASE_URL
        },
        allowMissing = channel == AppUpdateChannel.EXPERIMENTAL,
      ) ?: return null
    if (selectUpdateAsset(release) == null) {
      return null
    }
    val prefs = context.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
    val ignoredVersion =
      prefs.getString(ignoredVersionKey(channel), null)
        ?: if (channel == AppUpdateChannel.STABLE) prefs.getString(LEGACY_IGNORED_VERSION_KEY, null) else null

    // If this version was ignored, don't show it unless forced (manual check)
    val isIgnored =
      ignoredVersion == release.updateKey ||
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
        AppUpdateChannel.PREVIEW, AppUpdateChannel.EXPERIMENTAL -> isNewerCiBuild(release, channel)
      }

    return if (isNewer) {
      release
    } else {
      null
    }
  }

  /**
   * Preview and experimental feeds both publish the newest CI build of master. Commit counts order
   * builds, but an amended or force-pushed master publishes a different commit at the same count,
   * which must still reach users who already run that count.
   */
  private fun isNewerCiBuild(
    release: Release,
    channel: AppUpdateChannel,
  ): Boolean {
    val isPreview = channel == AppUpdateChannel.PREVIEW
    val remoteBuild = (if (isPreview) release.previewBuildNumber else release.experimentalBuildNumber) ?: return false
    if (remoteBuild != BuildConfig.GIT_COUNT) return remoteBuild > BuildConfig.GIT_COUNT

    val alreadyOnChannel = if (isPreview) BuildConfig.IS_PREVIEW_BUILD else BuildConfig.IS_EXPERIMENTAL_BUILD
    return !alreadyOnChannel || isDifferentCommit(release.commitSha)
  }

  private fun isDifferentCommit(remoteSha: String?): Boolean {
    val local = BuildConfig.GIT_SHA.trim()
    val remote = remoteSha?.trim().orEmpty()
    if (!GIT_SHA_REGEX.matches(local) || !GIT_SHA_REGEX.matches(remote)) return false
    return !remote.startsWith(local, ignoreCase = true) && !local.startsWith(remote, ignoreCase = true)
  }

  internal fun isInstalledRelease(release: Release): Boolean =
    when {
      release.isExperimental -> BuildConfig.IS_EXPERIMENTAL_BUILD &&
        release.experimentalBuildNumber == BuildConfig.GIT_COUNT && !isDifferentCommit(release.commitSha)
      release.isPreview -> BuildConfig.IS_PREVIEW_BUILD &&
        release.previewBuildNumber == BuildConfig.GIT_COUNT && !isDifferentCommit(release.commitSha)
      else -> !BuildConfig.IS_EXPERIMENTAL_BUILD && !BuildConfig.IS_PREVIEW_BUILD &&
        release.tagName.removePrefix("v") == BuildConfig.VERSION_NAME.substringBefore('-')
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

  private suspend fun getLatestRelease(url: String, allowMissing: Boolean = false): Release? =
    withContext(Dispatchers.IO) {
      val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
      client.newCall(request).awaitResponse().use { response ->
        // The channel becomes available after the first successful signed CI publication.
        if (allowMissing && response.code == 404) return@withContext null
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

  private fun ignoredVersionKey(channel: AppUpdateChannel): String =
    "ignored_version_${channel.name.lowercase()}"

  /** The exact APK this install would download: same distribution, and the same ABI package when known. */
  fun selectUpdateAsset(release: Release): Asset? {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return null
    return selectBestApkAsset(release.updateAssets)
  }

  fun downloadUpdate(release: Release): Flow<Float> {
    // Return completed flow immediately if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return flowOf(100f)
    }

    val asset =
      selectUpdateAsset(release)
        ?: throw Exception("No compatible APK asset found")

    return downloadApk(asset, localApkFile(asset))
  }

  private fun selectBestApkAsset(assets: List<Asset>): Asset? {
    val compatibleAssets =
      assets.filter { asset ->
        asset.name.startsWith("mpvRx-", ignoreCase = true) &&
          asset.name.endsWith(".apk", ignoreCase = true) &&
          asset.name.matchesApkVariant(BuildConfig.UPDATE_APK_VARIANT)
      }
    if (compatibleAssets.isEmpty()) return null

    // Keep the package the user installed: an ABI split stays on its ABI and a universal APK stays
    // universal. Moving from an ABI split to universal at the same build is also a versionCode downgrade.
    val preferredAbis = listOfNotNull(installedApkAbi(), primaryDeviceAbi(), APK_UNIVERSAL).distinct()
    for (abi in preferredAbis) {
      val match =
        if (abi == APK_UNIVERSAL) {
          universalApkAsset(compatibleAssets)
        } else {
          compatibleAssets.firstOrNull { it.name.hasApkAssetToken(abi) }
        }
      if (match != null) return match
    }
    return null
  }

  private fun universalApkAsset(assets: List<Asset>): Asset? =
    assets.firstOrNull { it.name.hasApkAssetToken(APK_UNIVERSAL) }
      // FongMi and No-Vulkan universal assets use the flavor marker instead of "universal".
      ?: assets.firstOrNull { asset -> APK_ABIS.none { abi -> asset.name.hasApkAssetToken(abi) } }

  private fun String.matchesApkVariant(variant: String): Boolean {
    val isFongMi = hasApkAssetToken(APK_VARIANT_FONGMI)
    val isNoVulkan = hasApkAssetToken(APK_VARIANT_NO_VULKAN)
    val isPlayStore = hasApkAssetToken(APK_VARIANT_PLAYSTORE)
    return when (variant) {
      APK_VARIANT_FONGMI -> isFongMi
      APK_VARIANT_NO_VULKAN -> isNoVulkan
      "standard" -> !isFongMi && !isNoVulkan && !isPlayStore
      else -> false
    }
  }

  /** build.gradle.kts encodes the installed APK's ABI package in the last digit of its versionCode. */
  private fun installedApkAbi(): String? {
    val versionCode =
      runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
      }.getOrNull() ?: return null
    val abi =
      when ((versionCode % 10).toInt()) {
        0 -> APK_UNIVERSAL
        1 -> "armeabi-v7a"
        2 -> "arm64-v8a"
        3 -> "x86"
        4 -> "x86_64"
        else -> null
      }
    return abi?.takeIf { it == APK_UNIVERSAL || it in Build.SUPPORTED_ABIS }
  }

  private fun primaryDeviceAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull()?.takeIf { it in APK_ABIS }

  /**
   * CI rebuilds at the same commit count reuse asset names, so the published content hash keeps
   * their downloads (and resumable partials) from being mistaken for one another.
   */
  private fun localApkFile(asset: Asset): File {
    val name =
      asset.expectedSha256?.let { sha -> "${asset.name.removeSuffix(".apk")}-${sha.take(LOCAL_HASH_LENGTH)}.apk" }
        ?: asset.name
    return File(File(context.externalCacheDir ?: context.cacheDir, "updates"), File(name).name)
  }

  /**
   * Downloads to a sidecar `.part` file and promotes it on success, so an interrupted download
   * never leaves a truncated APK that [getApkFile] would happily offer to install. When a `.part`
   * already exists the request asks for the remaining byte range, so closing and reopening the app
   * continues where it stopped instead of starting over. Servers that ignore Range answer 200 with
   * the whole body, which is detected and treated as a fresh start. The finished file must match the
   * published size and SHA-256 before it can be installed.
   */
  private fun downloadApk(
    asset: Asset,
    destination: File,
  ): Flow<Float> =
    flow {
      if (!destination.parentFile!!.isDirectory && !destination.parentFile!!.mkdirs()) {
        throw IOException("Could not create the update download directory")
      }
      val partial = File(destination.absolutePath + PART_SUFFIX)
      pruneOtherDownloads(keep = setOf(destination.name, partial.name))
      try {
        emitAll(downloadAttempt(asset, destination, partial, allowResume = true))
        return@flow
      } catch (_: RetryWithoutRangeSignal) {
        // Stored partial is unusable; the attempt already discarded it, so start clean.
      }
      emitAll(downloadAttempt(asset, destination, partial, allowResume = false))
    }.flowOn(Dispatchers.IO)

  private fun downloadAttempt(
    asset: Asset,
    destination: File,
    partial: File,
    allowResume: Boolean,
  ): Flow<Float> =
    flow {
      val alreadyDownloaded = if (allowResume) partial.takeIf { it.isFile }?.length() ?: 0L else 0L

      val request =
        Request.Builder()
          .url(asset.downloadUrl)
          .apply { if (alreadyDownloaded > 0L) header("Range", "bytes=$alreadyDownloaded-") }
          .build()

      client.newCall(request).awaitResponse().use { response ->
        if (response.code == HTTP_RANGE_NOT_SATISFIABLE && allowResume) {
          partial.delete()
          throw RetryWithoutRangeSignal
        }
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        val body = response.body
        // 206 means the server honoured our Range. A plain 200 means it ignored it and is sending
        // the whole asset, so the stored partial must be discarded rather than appended to.
        val resuming = response.code == HTTP_PARTIAL_CONTENT
        if (resuming) {
          val range = CONTENT_RANGE_REGEX.matchEntire(response.header("Content-Range").orEmpty())
          if (range == null || range.groupValues[1].toLongOrNull() != alreadyDownloaded) {
            partial.delete()
            if (allowResume) throw RetryWithoutRangeSignal
            throw IOException("Server returned an invalid download range")
          }
        } else if (response.code != 200) {
          throw IOException("Unexpected download response: HTTP ${response.code}")
        }
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
            currentCoroutineContext().ensureActive()
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            emit(progressOf(totalBytesRead, totalBytes))
          }
          outputStream.flush()
          outputStream.close()

          if (totalBytes != null && totalBytesRead < totalBytes) {
            throw IOException("Incomplete download: $totalBytesRead of $totalBytes bytes")
          }
          verifyDownload(partial, asset, resumed = appending)
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

  /** Rejects truncated, stale-CDN, or mis-resumed files before they can be offered for install. */
  private fun verifyDownload(
    file: File,
    asset: Asset,
    resumed: Boolean,
  ) {
    val expectedSha256 = asset.expectedSha256
    val sizeMatches = asset.size <= 0L || file.length() == asset.size
    if (sizeMatches && (expectedSha256 == null || sha256Of(file) == expectedSha256)) return

    file.delete()
    // A partial left by an earlier session may belong to different bytes; start over once.
    if (resumed) throw RetryWithoutRangeSignal
    throw IOException("Downloaded ${asset.name} does not match the published checksum")
  }

  private fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        digest.update(buffer, 0, read)
      }
    }
    val bytes = digest.digest()
    val hex = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
      val value = byte.toInt() and 0xFF
      hex[index * 2] = HEX_DIGITS[value ushr 4]
      hex[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(hex)
  }

  private fun pruneOtherDownloads(keep: Set<String>) {
    File(context.externalCacheDir ?: context.cacheDir, "updates").listFiles()?.forEach { file ->
      val isUpdateDownload =
        file.name.startsWith("mpvRx-", ignoreCase = true) &&
          (file.name.endsWith(".apk") || file.name.endsWith(PART_SUFFIX))
      if (isUpdateDownload && file.name !in keep) file.delete()
    }
  }

  private fun progressOf(downloaded: Long, total: Long?): Float =
    if (total != null && total > 0L) ((downloaded.toDouble() / total) * 100).toFloat().coerceIn(0f, 99f) else -1f

  suspend fun getApkFile(release: Release): File? = withContext(Dispatchers.IO) {
    // Return null if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return@withContext null
    }

    val asset = selectUpdateAsset(release) ?: return@withContext null
    val file = localApkFile(asset).takeIf { it.isFile } ?: return@withContext null
    try {
      verifyDownload(file, asset, resumed = false)
      file
    } catch (_: IOException) {
      null
    }
  }

  fun clearCache() {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    pruneOtherDownloads(emptySet())
  }

  private companion object {
    const val STABLE_RELEASE_URL = "https://api.github.com/repos/Riteshp2001/mpvRx/releases/latest"
    const val PREVIEW_RELEASE_URL = "https://riteshp2001.github.io/mpvRx/latest.json"
    const val EXPERIMENTAL_RELEASE_URL = "https://riteshp2001.github.io/mpvRx/experimental.json"
    const val LEGACY_IGNORED_VERSION_KEY = "ignored_version"
    const val PART_SUFFIX = ".part"
    const val LOCAL_HASH_LENGTH = 12
    const val HTTP_PARTIAL_CONTENT = 206
    const val HTTP_RANGE_NOT_SATISFIABLE = 416
    val GIT_SHA_REGEX = Regex("[0-9a-fA-F]{7,40}")
    val CONTENT_RANGE_REGEX = Regex("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)")
    val HEX_DIGITS = "0123456789abcdef".toCharArray()
  }
}
