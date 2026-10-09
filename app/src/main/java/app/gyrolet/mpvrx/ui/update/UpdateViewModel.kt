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
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.update.AppUpdateChannel
import app.gyrolet.mpvrx.domain.update.Release
import app.gyrolet.mpvrx.domain.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
  private var updateCheckJob: Job? = null

  private val prefs = application.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
  private val _isAutoUpdateEnabled =
    MutableStateFlow(
      if (BuildConfig.ENABLE_UPDATE_FEATURE) prefs.getBoolean("auto_update", false) else false,
    )
  val isAutoUpdateEnabled: StateFlow<Boolean> = _isAutoUpdateEnabled.asStateFlow()

  private val _updateChannel =
    MutableStateFlow(
      prefs.getString(UPDATE_CHANNEL_KEY, null)?.let(AppUpdateChannel::fromStoredValue)
        ?: if (BuildConfig.IS_PREVIEW_BUILD) AppUpdateChannel.PREVIEW else AppUpdateChannel.STABLE,
    )
  val updateChannel: StateFlow<AppUpdateChannel> = _updateChannel.asStateFlow()

  fun setUpdateChannel(channel: AppUpdateChannel) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE || channel == _updateChannel.value) return

    prefs.edit().putString(UPDATE_CHANNEL_KEY, channel.name).apply()
    _updateChannel.value = channel
    updateManager.clearCache()
    _updateState.value = UpdateState.Idle
    checkForUpdate(manual = true)
  }

  fun toggleAutoUpdate(enabled: Boolean) {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    prefs.edit().putBoolean("auto_update", enabled).apply()
    _isAutoUpdateEnabled.value = enabled
    if (enabled) {
      checkForUpdate(manual = false)
    }
  }

  init {
    // Only initialize auto-update if feature is enabled.
    // The network check is deferred by a short delay so that it does not
    // race with first-frame composition of the main UI. Previously the
    // check fired synchronously inside the init block of the ViewModel,
    // which is itself constructed during the very first Compose
    // composition in MainActivity.Navigator() -- meaning the OkHttp
    // client + GitHub API call competed with cold-start rendering.
    // See issue 1.5 in the startup audit.
    if (BuildConfig.ENABLE_UPDATE_FEATURE && isAutoUpdateEnabled.value) {
      viewModelScope.launch {
        delay(UPDATE_CHECK_STARTUP_DELAY_MS)
        checkForUpdate(manual = false)
      }
    }
  }

  private companion object {
    /**
     * Delay before the auto-update network check fires after the
     * UpdateViewModel is constructed. Long enough to let the first frame
     * draw and the main navigation settle, short enough that the update
     * dialog (if any) still appears within a few seconds of cold start.
     */
    private const val UPDATE_CHECK_STARTUP_DELAY_MS = 1500L
    private const val UPDATE_CHANNEL_KEY = "app_update_channel"
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
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    updateCheckJob?.cancel()
    updateCheckJob = viewModelScope.launch {
      _updateState.value = UpdateState.Loading
      try {
        val release = updateManager.checkForUpdate(channel = _updateChannel.value, forceShow = manual)
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
      } catch (cancellation: CancellationException) {
        throw cancellation
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
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    viewModelScope.launch {
      _isDownloading.value = true
      try {
        // Surface what a previous session already banked, so a resumed download picks up its
        // progress bar where it stopped instead of snapping back to 0%.
        _downloadProgress.value = updateManager.getResumableProgress(release) ?: 0f
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
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

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
    // Clean up downloaded APK when user dismisses the dialog
    updateManager.clearCache()
    _updateState.value = UpdateState.Idle
  }

  fun ignoreVersion(version: String) {
    updateManager.ignoreVersion(version, _updateChannel.value)
    _updateState.value = UpdateState.Idle
  }
}
