/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.update.AppUpdateChannel
import app.gyrolet.mpvrx.domain.update.Asset
import app.gyrolet.mpvrx.domain.update.Release
import app.gyrolet.mpvrx.domain.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Visibility is independent of loading, the selected version and installation readiness. */
data class UpdateUiState(
  val visible: Boolean = false,
  val channel: AppUpdateChannel = AppUpdateChannel.STABLE,
  val releases: List<Release> = emptyList(),
  val selectedKey: String? = null,
  val asset: Asset? = null,
  val loadingHistory: Boolean = false,
  val historyError: Boolean = false,
  val nextPage: Int? = 1,
  val selecting: Boolean = false,
  val isDownloading: Boolean = false,
  val progress: Float = 0f,
  val readyToInstall: Boolean = false,
  val downloadError: Boolean = false,
  val installError: Boolean = false,
  val installPermissionRequired: Boolean = false,
  val olderThanInstalled: Boolean = false,
) {
  val selectedRelease: Release?
    get() = releases.firstOrNull { it.historyKey == selectedKey }
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
  private val updateManager = UpdateManager(application)
  private val prefs = application.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
  private val _isAutoUpdateEnabled = MutableStateFlow(
    BuildConfig.ENABLE_UPDATE_FEATURE && prefs.getBoolean("auto_update", false),
  )
  val isAutoUpdateEnabled = _isAutoUpdateEnabled.asStateFlow()

  private val _updateChannel = MutableStateFlow(
    prefs.getString(UPDATE_CHANNEL_KEY, null)?.let(AppUpdateChannel::fromStoredValue)
      ?: when {
        BuildConfig.IS_EXPERIMENTAL_BUILD -> AppUpdateChannel.EXPERIMENTAL
        BuildConfig.IS_PREVIEW_BUILD -> AppUpdateChannel.PREVIEW
        else -> AppUpdateChannel.STABLE
      },
  )
  val updateChannel = _updateChannel.asStateFlow()
  private val _uiState = MutableStateFlow(UpdateUiState(channel = _updateChannel.value))
  val uiState = _uiState.asStateFlow()

  private var checkJob: Job? = null
  private var historyJob: Job? = null
  private var selectionJob: Job? = null
  private val commitJobs = mutableMapOf<String, Job>()
  private val commitCache = mutableMapOf<String, Release>()

  init {
    if (BuildConfig.ENABLE_UPDATE_FEATURE && _isAutoUpdateEnabled.value) {
      viewModelScope.launch {
        delay(UPDATE_CHECK_STARTUP_DELAY_MS)
        if (_isAutoUpdateEnabled.value) checkForUpdate()
      }
    }
  }

  fun setUpdateChannel(channel: AppUpdateChannel) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE || _uiState.value.isDownloading || channel == _updateChannel.value) return
    stopBrowsing()
    prefs.edit().putString(UPDATE_CHANNEL_KEY, channel.name).apply()
    _updateChannel.value = channel
    _uiState.value = UpdateUiState(visible = true, channel = channel)
    loadHistory(refresh = true)
  }

  fun toggleAutoUpdate(enabled: Boolean) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return
    prefs.edit().putBoolean("auto_update", enabled).apply()
    _isAutoUpdateEnabled.value = enabled
    if (enabled) checkForUpdate() else checkJob?.cancel()
  }

  fun checkForUpdate(manual: Boolean = false) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE || _uiState.value.isDownloading) return
    if (manual) {
      checkJob?.cancel()
      _uiState.update { it.copy(visible = true) }
      if (!_uiState.value.loadingHistory) loadHistory(refresh = true)
      return
    }
    // Background checks cannot replace an open browser or its selected download.
    if (_uiState.value.visible) return
    checkJob?.cancel()
    val channel = _updateChannel.value
    checkJob = viewModelScope.launch {
      try {
        val release = updateManager.checkForUpdate(channel) ?: return@launch
        ensureActive()
        if (_uiState.value.visible || _uiState.value.isDownloading || channel != _updateChannel.value) return@launch
        _uiState.value = UpdateUiState(visible = true, channel = channel, releases = listOf(release))
        selectRelease(release.historyKey)
        loadHistory()
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        // Automatic checks stay silent; manual browsing has an inline retry.
      }
    }
  }

  fun loadMoreHistory() = loadHistory()

  fun retryHistory() = loadHistory(refresh = _uiState.value.nextPage == 1)

  private fun loadHistory(refresh: Boolean = false) {
    val state = _uiState.value
    if (!state.visible || state.isDownloading || state.loadingHistory) return
    val firstPage = if (refresh) 1 else state.nextPage ?: return
    val channel = state.channel
    _uiState.update { it.copy(loadingHistory = true, historyError = false) }
    historyJob?.cancel()
    historyJob = viewModelScope.launch {
      try {
        if (firstPage == 1) {
          try {
            updateManager.getChannelRelease(channel)?.let { mergeReleases(listOf(it)) }
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (_: Exception) {
            // The archive is still usable if a rolling Pages feed is unavailable.
          }
        }
        var page = firstPage
        var scannedPages = 0
        do {
          val result = updateManager.getReleaseHistory(channel, page)
          ensureActive()
          mergeReleases(result.releases)
          _uiState.update { it.copy(nextPage = result.nextPage) }
          scannedPages++
          // Skip sparse pages without allowing a single batch to exhaust the API quota.
          val next = result.nextPage ?: break
          if (result.releases.isNotEmpty() || scannedPages >= MAX_HISTORY_PAGES_PER_BATCH) break
          page = next
        } while (true)
        _uiState.update { it.copy(loadingHistory = false) }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        _uiState.update { it.copy(loadingHistory = false, historyError = true) }
      }
    }
  }

  private fun mergeReleases(releases: List<Release>) {
    _uiState.update { state ->
      state.copy(
        releases = (state.releases + releases).distinctBy { it.historyKey }
          .sortedWith(
            compareByDescending<Release> { it.experimentalBuildNumber ?: it.previewBuildNumber ?: 0 }
              .thenByDescending { if (it.isExperimental) it.updatedAt ?: it.publishedAt else it.publishedAt },
          ),
      )
    }
    if (_uiState.value.selectedKey == null) {
      _uiState.value.releases.firstOrNull()?.let { selectRelease(it.historyKey) }
    }
  }

  fun selectRelease(key: String) {
    val state = _uiState.value
    if (state.isDownloading || !state.visible) return
    val release = state.releases.firstOrNull { it.historyKey == key } ?: return
    val asset = updateManager.selectUpdateAsset(release) ?: return
    selectionJob?.cancel()
    _uiState.update {
      it.copy(
        selectedKey = key, asset = asset, selecting = true, progress = 0f,
        readyToInstall = false, downloadError = false, installError = false,
        installPermissionRequired = false, olderThanInstalled = updateManager.isOlderThanInstalled(release),
      )
    }
    loadCommitDetails(key)
    selectionJob = viewModelScope.launch {
      try {
        val file = updateManager.getApkFile(release)
        ensureActive()
        _uiState.update { it.copy(selecting = false, readyToInstall = file != null) }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        _uiState.update { it.copy(selecting = false, readyToInstall = false) }
      }
    }
  }

  fun loadCommitDetails(key: String) {
    val release = _uiState.value.releases.firstOrNull { it.historyKey == key } ?: return
    if (commitJobs.containsKey(key) || release.commitMessage != null) return
    commitCache[key]?.let { cached ->
      _uiState.update { state ->
        state.copy(releases = state.releases.map { if (it.historyKey == key) cached else it })
      }
      return
    }
    commitJobs[key] = viewModelScope.launch {
      try {
        val detailed = updateManager.getCommitDetails(release)
        ensureActive()
        commitCache[key] = detailed
        _uiState.update { state ->
          state.copy(releases = state.releases.map { if (it.historyKey == key) detailed else it })
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        // Missing metadata never hides a downloadable APK.
      } finally {
        if (commitJobs[key] === currentCoroutineContext()[Job]) commitJobs.remove(key)
      }
    }
  }

  fun downloadUpdate() {
    val state = _uiState.value
    if (!BuildConfig.ENABLE_UPDATE_FEATURE || state.isDownloading || state.selecting) return
    val release = state.selectedRelease ?: return
    if (state.asset == null) return
    historyJob?.cancel()
    // Guard synchronously, before launching: repeated taps must never start parallel writers.
    _uiState.update { it.copy(isDownloading = true, loadingHistory = false, progress = 0f, downloadError = false) }
    viewModelScope.launch {
      try {
        val resumedProgress = updateManager.getResumableProgress(release) ?: 0f
        _uiState.update { it.copy(progress = resumedProgress) }
        updateManager.downloadUpdate(release).collect { progress ->
          _uiState.update { it.copy(progress = progress) }
        }
        _uiState.update { it.copy(isDownloading = false, readyToInstall = true, progress = 100f) }
      } catch (cancellation: CancellationException) {
        _uiState.update { it.copy(isDownloading = false) }
        throw cancellation
      } catch (_: Exception) {
        _uiState.update { it.copy(isDownloading = false, readyToInstall = false, downloadError = true) }
      }
    }
  }

  fun installUpdate() {
    val state = _uiState.value
    if (!BuildConfig.ENABLE_UPDATE_FEATURE || state.isDownloading || state.selecting || !state.readyToInstall) return
    val release = state.selectedRelease ?: return
    _uiState.update { it.copy(selecting = true, installError = false, installPermissionRequired = false) }
    selectionJob = viewModelScope.launch {
      try {
        val file = updateManager.getApkFile(release)
        ensureActive()
        if (file == null) {
          _uiState.update { it.copy(readyToInstall = false, downloadError = true) }
          return@launch
        }
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
          _uiState.update { it.copy(installPermissionRequired = true) }
          context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          )
          return@launch
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(
          Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
          },
        )
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        _uiState.update { it.copy(installError = true) }
      } finally {
        if (currentCoroutineContext().isActive) _uiState.update { it.copy(selecting = false) }
      }
    }
  }

  fun dismiss() {
    if (_uiState.value.isDownloading) return
    stopBrowsing()
    // Retain verified APKs and partials when closing; the next download prunes other versions.
    _uiState.update { it.copy(visible = false, loadingHistory = false, selecting = false) }
  }

  fun ignoreVersion() {
    if (_uiState.value.isDownloading) return
    _uiState.value.selectedRelease?.let { updateManager.ignoreVersion(it.updateKey, _updateChannel.value) }
    dismiss()
  }

  private fun stopBrowsing() {
    checkJob?.cancel()
    historyJob?.cancel()
    selectionJob?.cancel()
    commitJobs.values.toList().forEach { it.cancel() }
    commitJobs.clear()
  }

  private companion object {
    const val UPDATE_CHECK_STARTUP_DELAY_MS = 1500L
    const val UPDATE_CHANNEL_KEY = "app_update_channel"
    const val MAX_HISTORY_PAGES_PER_BATCH = 5
  }
}

