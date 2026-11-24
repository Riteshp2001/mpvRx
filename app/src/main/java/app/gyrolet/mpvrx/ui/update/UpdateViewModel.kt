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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.update.Asset
import app.gyrolet.mpvrx.domain.update.Release
import app.gyrolet.mpvrx.domain.update.UpdateManager
import app.gyrolet.mpvrx.domain.update.UpdateInstaller
import app.gyrolet.mpvrx.domain.update.UpdateStage
import app.gyrolet.mpvrx.domain.update.UpdateTaskState
import app.gyrolet.mpvrx.domain.update.UpdateWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One state keeps the download sheet mounted while checking, downloading and installing. */
data class UpdateUiState(
  val visible: Boolean = false,
  val release: Release? = null,
  val asset: Asset? = null,
  val checking: Boolean = false,
  val checkError: Boolean = false,
  val noUpdate: Boolean = false,
  val selecting: Boolean = false,
  val isDownloading: Boolean = false,
  val progress: Float = 0f,
  val readyToInstall: Boolean = false,
  val downloadError: Boolean = false,
  val installError: Boolean = false,
  val installPermissionRequired: Boolean = false,
  val isInstalling: Boolean = false,
  val awaitingApproval: Boolean = false,
  val queued: Boolean = false,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
  private val updateManager = UpdateManager(application)
  private val prefs = application.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE)
  private val _isAutoUpdateEnabled = MutableStateFlow(
    BuildConfig.ENABLE_UPDATE_FEATURE && prefs.getBoolean("auto_update", false),
  )
  val isAutoUpdateEnabled = _isAutoUpdateEnabled.asStateFlow()
  private val _isAutoInstallEnabled = MutableStateFlow(UpdateWork.automaticEnabled(application))
  val isAutoInstallEnabled = _isAutoInstallEnabled.asStateFlow()

  private val _uiState = MutableStateFlow(UpdateUiState())
  val uiState = _uiState.asStateFlow()
  private var checkJob: Job? = null
  private var selectionJob: Job? = null

  init {
    if (BuildConfig.ENABLE_UPDATE_FEATURE) {
      viewModelScope.launch {
        UpdateWork.state(application).collect { task -> applyTask(task) }
      }
    }
    if (BuildConfig.ENABLE_UPDATE_FEATURE && _isAutoUpdateEnabled.value) {
      viewModelScope.launch {
        delay(UPDATE_CHECK_STARTUP_DELAY_MS)
        if (_isAutoUpdateEnabled.value) checkForUpdate()
      }
    }
  }

  fun toggleAutoInstall(enabled: Boolean) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return
    _isAutoInstallEnabled.value = enabled
    viewModelScope.launch { UpdateWork.setAutomatic(getApplication(), enabled) }
  }

  fun toggleAutoUpdate(enabled: Boolean) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return
    prefs.edit().putBoolean("auto_update", enabled).apply()
    _isAutoUpdateEnabled.value = enabled
    if (enabled) checkForUpdate() else checkJob?.cancel()
  }

  fun retryCheck() = checkForUpdate(manual = true)

  fun showPendingUpdate() {
    applyTask(UpdateWork.state(getApplication()).value)
    if (_uiState.value.release != null) _uiState.update { it.copy(visible = true) }
    else checkForUpdate(manual = true)
  }

  fun checkForUpdate(manual: Boolean = false) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return
    if (busy() || _uiState.value.awaitingApproval || _uiState.value.selecting) {
      if (manual) _uiState.update { it.copy(visible = true) }
      return
    }
    if (!manual && _uiState.value.visible) return
    checkJob?.cancel()
    if (manual) _uiState.update { it.copy(visible = true, checking = true, checkError = false, noUpdate = false) }
    checkJob = viewModelScope.launch {
      try {
        val release = updateManager.checkForUpdate(forceShow = manual)
        ensureActive()
        if (busy()) return@launch
        val asset = release?.let(updateManager::selectUpdateAsset)
        if (release != null && asset != null) {
          _uiState.value = UpdateUiState(visible = true, release = release, asset = asset, selecting = true)
          val file = updateManager.getApkFile(release)
          ensureActive()
          _uiState.update { it.copy(readyToInstall = file != null, selecting = false) }
        } else if (manual) {
          _uiState.update { it.copy(checking = false, noUpdate = true) }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        if (manual) _uiState.update { it.copy(checking = false, selecting = false, checkError = true) }
        else _uiState.update { it.copy(selecting = false) }
      }
    }
  }

  fun downloadUpdate() {
    val state = _uiState.value
    if (!BuildConfig.ENABLE_UPDATE_FEATURE || busy() || state.selecting || state.checking) return
    val release = state.release ?: return
    if (state.asset == null) return
    checkJob?.cancel()
    // Guard synchronously, before launching: repeated taps must never start parallel writers.
    _uiState.update { it.copy(isDownloading = true, checking = false, progress = 0f, downloadError = false) }
    viewModelScope.launch {
      try {
        if (!UpdateWork.enqueue(getApplication(), release)) {
          applyTask(UpdateWork.state(getApplication()).value)
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        _uiState.update { it.copy(isDownloading = false, readyToInstall = false, downloadError = true) }
      }
    }
  }

  fun cancelDownload() {
    if (!_uiState.value.isDownloading) return
    viewModelScope.launch { UpdateWork.cancelDownload(getApplication()) }
  }

  fun installUpdate() {
    val state = _uiState.value
    if (!BuildConfig.ENABLE_UPDATE_FEATURE || busy() || state.selecting || !state.readyToInstall) return
    val release = state.release ?: return
    _uiState.update { it.copy(selecting = true, installError = false, installPermissionRequired = false) }
    selectionJob = viewModelScope.launch {
      try {
        val file = updateManager.getApkFile(release)
        ensureActive()
        if (file == null) {
          _uiState.update { it.copy(readyToInstall = false, downloadError = true) }
          return@launch
        }
        withContext(Dispatchers.IO) {
          if (UpdateWork.state(getApplication()).value.release?.tagName != release.tagName) {
            UpdateWork.update(getApplication()) { UpdateTaskState(release = release, stage = UpdateStage.READY) }
          }
          UpdateInstaller.requestUserInstall(getApplication(), release)
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        _uiState.update { it.copy(installError = true) }
      } finally {
        _uiState.update { it.copy(selecting = false) }
      }
    }
  }

  fun dismiss() {
    if (busy()) return
    stopChecking()
    // Retain verified APKs and partials when closing; the next download prunes other versions.
    _uiState.update { it.copy(visible = false, checking = false, selecting = false) }
  }

  fun ignoreVersion() {
    if (busy() || _uiState.value.awaitingApproval) return
    _uiState.value.release?.let { updateManager.ignoreVersion(it.tagName) }
    dismiss()
  }

  private fun stopChecking() {
    checkJob?.cancel()
    selectionJob?.cancel()
  }

  private fun busy() = _uiState.value.isDownloading || _uiState.value.isInstalling

  private fun applyTask(task: UpdateTaskState) {
    if (task.stage == UpdateStage.COMPLETE) {
      _uiState.value = UpdateUiState()
      return
    }
    val release = task.release ?: return
    _uiState.update { current ->
      current.copy(
        visible = current.visible || !task.automatic,
        release = release,
        asset = updateManager.selectUpdateAsset(release),
        checking = false,
        checkError = false,
        noUpdate = false,
        isDownloading = task.stage == UpdateStage.DOWNLOADING || task.stage == UpdateStage.QUEUED,
        isInstalling = task.stage == UpdateStage.INSTALLING,
        awaitingApproval = task.stage == UpdateStage.APPROVAL,
        queued = task.stage == UpdateStage.QUEUED,
        progress = task.progress,
        readyToInstall = task.stage in setOf(UpdateStage.READY, UpdateStage.APPROVAL, UpdateStage.PERMISSION) ||
          (task.stage == UpdateStage.FAILED && !task.downloadError),
        downloadError = task.stage == UpdateStage.FAILED && task.downloadError,
        installError = task.stage == UpdateStage.FAILED && !task.downloadError,
        installPermissionRequired = task.stage == UpdateStage.PERMISSION,
      )
    }
  }

  private companion object {
    const val UPDATE_CHECK_STARTUP_DELAY_MS = 1500L
  }
}
