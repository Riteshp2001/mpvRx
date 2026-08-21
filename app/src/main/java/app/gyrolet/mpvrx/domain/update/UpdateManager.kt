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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class UpdateManager(
  private val context: Context,
) {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun checkForUpdate(forceShow: Boolean = false): Release? {
    // Return null immediately if update feature is disabled (F-Droid flavor)
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return null
    }

    val release = getLatestRelease("https://api.github.com/repos/Riteshp2001/mpvRx/releases/latest")
    val currentVersion = BuildConfig.VERSION_NAME.replace("-dev", "")
    val remoteVersion = release.tagName.removePrefix("v")
    val prefs = context.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
    val ignoredVersion = prefs.getString("ignored_version", null)

    // If this version was ignored, don't show it unless forced (manual check)
    if (!forceShow && ignoredVersion == remoteVersion) {
      return null
    }

    return if (isNewerVersion(remoteVersion, currentVersion)) {
      release
    } else {
      null
    }
  }

  fun ignoreVersion(version: String) {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    val prefs = context.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
    prefs
      .edit()
      .putString("ignored_version", version)
      .apply()
  }

  private suspend fun getLatestRelease(url: String): Release =
    withContext(Dispatchers.IO) {
      val request = Request.Builder().url(url).build()
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

    // First, try to find architecture-specific APK
    val archSpecificApk =
      assets.firstOrNull { asset ->
        asset.name.endsWith(".apk") && asset.name.contains(deviceArch, ignoreCase = true)
      }

    if (archSpecificApk != null) {
      return archSpecificApk
    }

    // Fallback to universal APK
    val universalApk =
      assets.firstOrNull { asset ->
        asset.name.endsWith(".apk") && asset.name.contains("universal", ignoreCase = true)
      }

    if (universalApk != null) {
      return universalApk
    }

    // Last resort: any APK
    return assets.firstOrNull { it.name.endsWith(".apk") }
  }

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

  private fun downloadApk(
    url: String,
    destination: File,
  ): Flow<Float> =
    flow {
      val request = Request.Builder().url(url).build()
      val response = client.newCall(request).execute()
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      val body = response.body
      val contentLength = body.contentLength()
      val inputStream = body.byteStream()
      val outputStream = FileOutputStream(destination)

      try {
        val buffer = ByteArray(8 * 1024)
        var bytesRead: Int
        var totalBytesRead: Long = 0

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
          outputStream.write(buffer, 0, bytesRead)
          totalBytesRead += bytesRead
          val progress =
            if (contentLength > 0) {
              (totalBytesRead.toFloat() / contentLength.toFloat()) * 100
            } else {
              -1f
            }
          emit(progress)
        }
        outputStream.flush()
        emit(100f)
      } finally {
        inputStream.close()
        outputStream.close()
      }
    }.flowOn(Dispatchers.IO)

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
      if (it.name.endsWith(".apk")) it.delete()
    }
  }
}
