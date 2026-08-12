/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// --- Data Models ---

@Serializable
data class Release(
  @SerialName("tag_name") val tagName: String,
  @SerialName("name") val name: String,
  @SerialName("body") val body: String,
  @SerialName("published_at") val publishedAt: String,
  @SerialName("assets") val assets: List<Asset>,
  @SerialName("channel") val channel: String? = null,
  @SerialName("commit_sha") val commitSha: String? = null,
  @SerialName("commit_count") val commitCount: Int? = null,
  @SerialName("run_number") val runNumber: Long? = null,
  @SerialName("run_url") val runUrl: String? = null,
)

@Serializable
data class Asset(
  @SerialName("browser_download_url") val downloadUrl: String,
  @SerialName("name") val name: String,
  @SerialName("size") val size: Long,
  @SerialName("content_type") val contentType: String,
  @SerialName("sha256") val sha256: String? = null,
)

// --- Domain Manager ---

class UpdateManager(
  private val context: Context,
) {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun checkForUpdate(
    forceShow: Boolean = false,
    includeBeta: Boolean = false,
  ): Release? {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return null

    val currentVersion = BuildConfig.VERSION_NAME.substringBefore('-')
    val prefs = context.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
    val ignoredVersion = prefs.getString("ignored_version", null)

    // Stable releases always have priority. Automatic checks intentionally stop here so beta
    // builds are opt-in and never installed silently.
    val stableResult = runCatching { getLatestRelease(STABLE_RELEASE_URL) }
    val stableRelease = stableResult.getOrNull()
    if (stableRelease != null) {
      val remoteVersion = stableRelease.tagName.removePrefix("v")
      val ignored = !forceShow && ignoredVersion == remoteVersion
      if (!ignored && isNewerVersion(remoteVersion, currentVersion)) {
        return stableRelease
      }
    }

    if (includeBeta) {
      val betaRelease = runCatching { getLatestRelease(BETA_RELEASE_URL) }.getOrNull()
      if (betaRelease != null &&
        isValidBetaManifest(betaRelease) &&
        isBetaNewerThanCurrent(betaRelease) &&
        (forceShow || ignoredVersion != betaRelease.tagName)
      ) {
        return betaRelease
      }
    }

    // Preserve the previous error behavior when GitHub itself is unavailable. A missing beta
    // page is harmless before the first successful preview deployment and must not break stable
    // update checks.
    if (stableRelease == null) stableResult.getOrThrow()
    return null
  }

  fun ignoreVersion(version: String) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return

    val prefs = context.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
    prefs
      .edit()
      .putString("ignored_version", version)
      .apply()
  }

  private suspend fun getLatestRelease(url: String): Release =
    withContext(Dispatchers.IO) {
      val request =
        Request
          .Builder()
          .url(url)
          .header("Cache-Control", "no-cache")
          .build()
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

  private fun isValidBetaManifest(release: Release): Boolean {
    if (release.channel != "beta") return false
    if (!release.tagName.startsWith("beta-r")) return false
    if ((release.commitCount ?: 0) <= 0) return false
    if (release.assets.isEmpty()) return false

    return release.assets.all { asset ->
      asset.name.endsWith(".apk") &&
        asset.downloadUrl.startsWith(BETA_DOWNLOAD_PREFIX) &&
        asset.sha256?.matches(SHA256_REGEX) == true
    }
  }

  private fun isBetaNewerThanCurrent(release: Release): Boolean {
    val betaCommitCount = release.commitCount ?: return false
    return betaCommitCount > BuildConfig.GIT_COUNT
  }

  fun downloadUpdate(release: Release): Flow<Float> {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return flowOf(100f)

    val asset =
      selectBestApkAsset(release.assets)
        ?: throw Exception("No compatible APK asset found")

    val destination = File(context.externalCacheDir, asset.name)
    return downloadApk(asset.downloadUrl, destination, asset.sha256)
  }

  private fun selectBestApkAsset(assets: List<Asset>): Asset? {
    val deviceArch = getDeviceArchitecture()

    val archSpecificApk =
      assets.firstOrNull { asset ->
        asset.name.endsWith(".apk") && asset.name.contains(deviceArch, ignoreCase = true)
      }

    if (archSpecificApk != null) return archSpecificApk

    val universalApk =
      assets.firstOrNull { asset ->
        asset.name.endsWith(".apk") && asset.name.contains("universal", ignoreCase = true)
      }

    if (universalApk != null) return universalApk

    return assets.firstOrNull { it.name.endsWith(".apk") }
  }

  private fun getDeviceArchitecture(): String {
    val primaryAbi =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        Build.SUPPORTED_ABIS[0]
      } else {
        @Suppress("DEPRECATION")
        Build.CPU_ABI
      }

    return when (primaryAbi) {
      "arm64-v8a" -> "arm64-v8a"
      "armeabi-v7a" -> "armeabi-v7a"
      "x86" -> "x86"
      "x86_64" -> "x86_64"
      else -> "universal"
    }
  }

  private fun downloadApk(
    url: String,
    destination: File,
    expectedSha256: String?,
  ): Flow<Float> =
    flow {
      val partialFile = File("${destination.absolutePath}.part")
      partialFile.delete()

      try {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          val body = response.body
          val contentLength = body.contentLength()
          body.byteStream().use { inputStream ->
            FileOutputStream(partialFile).use { outputStream ->
              val buffer = ByteArray(8 * 1024)
              var bytesRead: Int
              var totalBytesRead = 0L

              while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                val progress =
                  if (contentLength > 0) {
                    ((totalBytesRead.toFloat() / contentLength.toFloat()) * 99f).coerceAtMost(99f)
                  } else {
                    -1f
                  }
                emit(progress)
              }
              outputStream.flush()
            }
          }
        }

        if (!expectedSha256.isNullOrBlank()) {
          val actualSha256 = calculateSha256(partialFile)
          if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("Downloaded APK checksum does not match the published beta manifest")
          }
        }

        if (destination.exists() && !destination.delete()) {
          throw IOException("Could not replace cached APK")
        }
        if (!partialFile.renameTo(destination)) {
          partialFile.copyTo(destination, overwrite = true)
          partialFile.delete()
        }
        emit(100f)
      } catch (error: Throwable) {
        partialFile.delete()
        destination.delete()
        throw error
      }
    }.flowOn(Dispatchers.IO)

  private fun calculateSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(16 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
  }

  fun getApkFile(release: Release): File? {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return null

    val asset = selectBestApkAsset(release.assets) ?: return null
    val file = File(context.externalCacheDir, asset.name)
    return if (file.exists()) file else null
  }

  fun clearCache() {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return

    context.externalCacheDir?.listFiles()?.forEach {
      if (it.name.endsWith(".apk") || it.name.endsWith(".apk.part")) it.delete()
    }
  }

  private companion object {
    const val STABLE_RELEASE_URL = "https://api.github.com/repos/Riteshp2001/mpvRx/releases/latest"
    const val BETA_RELEASE_URL = "https://riteshp2001.github.io/mpvRx/latest.json"
    const val BETA_DOWNLOAD_PREFIX = "https://riteshp2001.github.io/mpvRx/downloads/"
    val SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")
  }
}

// --- ViewModel ---

class UpdateViewModel(
  application: Application,
) : AndroidViewModel(application) {
  private val updateManager = UpdateManager(application)

  private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
  val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

  private val _downloadProgress = MutableStateFlow<Float>(0f)
  val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

  private val _isDownloading = MutableStateFlow(false)
  val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

  private val prefs = application.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
  private val _isAutoUpdateEnabled =
    MutableStateFlow(
      if (BuildConfig.ENABLE_UPDATE_FEATURE) prefs.getBoolean("auto_update", false) else false,
    )
  val isAutoUpdateEnabled: StateFlow<Boolean> = _isAutoUpdateEnabled.asStateFlow()

  fun toggleAutoUpdate(enabled: Boolean) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return

    prefs.edit().putBoolean("auto_update", enabled).apply()
    _isAutoUpdateEnabled.value = enabled
    if (enabled) {
      checkForUpdate(manual = false)
    }
  }

  init {
    // Automatic startup checks intentionally remain stable-only. Beta is only considered after a
    // manual check from About so users never opt into preview builds by accident.
    if (BuildConfig.ENABLE_UPDATE_FEATURE && isAutoUpdateEnabled.value) {
      viewModelScope.launch {
        kotlinx.coroutines.delay(UPDATE_CHECK_STARTUP_DELAY_MS)
        checkForUpdate(manual = false)
      }
    }
  }

  private companion object {
    private const val UPDATE_CHECK_STARTUP_DELAY_MS = 1500L
  }

  sealed class UpdateState {
    object Idle : UpdateState()

    object Loading : UpdateState()

    data class Available(
      val release: Release,
    ) : UpdateState()

    object NoUpdate : UpdateState()

    object Error : UpdateState()

    data class ReadyToInstall(
      val release: Release,
    ) : UpdateState()
  }

  fun dismissNoUpdate() {
    _updateState.value = UpdateState.Idle
  }

  fun checkForUpdate(manual: Boolean = false) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return

    viewModelScope.launch {
      _updateState.value = UpdateState.Loading
      try {
        val release =
          updateManager.checkForUpdate(
            forceShow = manual,
            includeBeta = manual,
          )
        if (release != null) {
          val existingFile = updateManager.getApkFile(release)
          if (existingFile != null) {
            _updateState.value = UpdateState.ReadyToInstall(release)
          } else {
            _updateState.value = UpdateState.Available(release)
          }
        } else {
          if (manual) {
            _updateState.value = UpdateState.NoUpdate
          } else {
            _updateState.value = UpdateState.Idle
          }
        }
      } catch (e: Exception) {
        e.printStackTrace()
        if (manual) {
          _updateState.value = UpdateState.Error
        } else {
          _updateState.value = UpdateState.Idle
        }
      }
    }
  }

  fun downloadUpdate(release: Release) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return

    viewModelScope.launch {
      _isDownloading.value = true
      try {
        updateManager.downloadUpdate(release).collect { progress ->
          _downloadProgress.value = progress
        }
        _isDownloading.value = false
        _updateState.value = UpdateState.ReadyToInstall(release)
      } catch (e: Exception) {
        e.printStackTrace()
        _isDownloading.value = false
        _updateState.value = UpdateState.Error
      }
    }
  }

  fun installUpdate(release: Release) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return

    val file = updateManager.getApkFile(release) ?: return
    val context = getApplication<Application>()
    val uri =
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file,
      )
    val intent =
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    context.startActivity(intent)
  }

  fun dismiss() {
    updateManager.clearCache()
    _updateState.value = UpdateState.Idle
  }

  fun ignoreVersion(version: String) {
    updateManager.ignoreVersion(version)
    _updateState.value = UpdateState.Idle
  }
}

// --- UI Components ---

@Composable
fun UpdateDialog(
  release: Release,
  isDownloading: Boolean,
  progress: Float,
  actionLabel: String,
  currentVersion: String,
  onDismiss: () -> Unit,
  onAction: () -> Unit,
  onIgnore: () -> Unit,
) {
  val downloadSize = release.assets.find { it.name.endsWith(".apk") }?.size ?: 0L
  val formattedDate = formatDate(release.publishedAt)
  val isBeta = release.channel == "beta" || release.tagName.startsWith("beta-r")

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector =
          if (actionLabel == "Install") {
            Icons.RoundedFilled.SystemUpdate
          } else {
            Icons.RoundedFilled.CloudDownload
          },
        contentDescription = null,
        modifier = Modifier.size(24.dp),
      )
    },
    title = {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text =
            when {
              actionLabel == "Install" && isBeta -> "Beta Ready to Install"
              actionLabel == "Install" -> "Ready to Install"
              isBeta -> "Beta Build Available"
              else -> "Update Available"
            },
          style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = if (isBeta) release.name else release.tagName.removePrefix("v"),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    },
    text = {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
      ) {
        if (actionLabel != "Install") {
          InfoRow(label = "Current Version", value = currentVersion)
          if (isBeta) {
            InfoRow(label = "Channel", value = "GitHub Actions Beta")
            release.commitCount?.let { InfoRow(label = "Build", value = "r$it") }
            release.commitSha?.let { InfoRow(label = "Commit", value = it.take(7)) }
            release.runNumber?.let { InfoRow(label = "Actions Run", value = "#$it") }
          } else {
            InfoRow(label = "Latest Version", value = release.tagName.removePrefix("v"))
          }
          InfoRow(label = "Build Date", value = formattedDate)
          InfoRow(label = "Size", value = formatFileSize(downloadSize))
        }

        if (isDownloading) {
          Spacer(modifier = Modifier.height(16.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_downloading),
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              text = stringResource(R.string.update_progress_percent, progress.toInt()),
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
          LinearProgressIndicator(
            progress = { if (progress >= 0) progress / 100f else 0f },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      if (!isDownloading) {
        Button(onClick = onAction) {
          Text(
            if (actionLabel == "Install") {
              stringResource(R.string.ui_install)
            } else {
              stringResource(R.string.ui_download)
            },
          )
        }
      }
    },
    dismissButton = {
      if (!isDownloading) {
        Row {
          if (actionLabel != "Install") {
            TextButton(onClick = onIgnore) {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_ignore),
              )
            }
          }
          TextButton(onClick = onDismiss) {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
            )
          }
        }
      }
    },
  )
}

@Composable
private fun InfoRow(
  label: String,
  value: String,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

private fun formatFileSize(size: Long): String {
  if (size <= 0) return "Unknown size"
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
  return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatDate(dateString: String): String {
  return try {
    val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    inputFormat.timeZone = TimeZone.getTimeZone("UTC")
    val date = inputFormat.parse(dateString) ?: return dateString

    val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    outputFormat.format(date)
  } catch (e: Exception) {
    dateString
  }
}
