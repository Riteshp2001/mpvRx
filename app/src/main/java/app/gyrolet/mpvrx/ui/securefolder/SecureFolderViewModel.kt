/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.securefolder

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.database.entities.SecureMediaEntity
import app.gyrolet.mpvrx.database.repository.SecureFolderRepository
import app.gyrolet.mpvrx.preferences.SecureFolderPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Backs both [SecureFolderGateScreen] (PIN setup/entry/forgot-PIN) and [SecureFolderScreen]
 * (grid + selection + restore/delete).
 *
 * Kept as a single ViewModel since the gate and the grid share the same lifecycle scope
 * (entering a correct PIN just swaps which composable is shown), following how
 * FolderListViewModel/VideoListViewModel are already scoped per-screen-group in this repo.
 */
class SecureFolderViewModel(
  application: Application,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: SecureFolderRepository by inject()
  val preferences: SecureFolderPreferences by inject()

  companion object {
    private const val TAG = "SecureFolderViewModel"

    fun factory(application: Application) =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SecureFolderViewModel(application) as T
      }
  }

  // ============================================================================
  // Gate state (PIN setup / entry / forgot-PIN)
  // ============================================================================

  enum class GateStep {
    ENTER_PIN, // existing user, asking for PIN
    SETUP, // first-time: PIN + confirm PIN + security question + answer, all in one step
    FORGOT_PIN_QUESTION, // forgot-PIN flow: re-answer the security question
    FORGOT_PIN_NEW_PIN, // forgot-PIN flow: security answer verified, choose a new PIN
  }

  private val _gateStep =
    MutableStateFlow(if (preferences.isPinSet()) GateStep.ENTER_PIN else GateStep.SETUP)
  val gateStep: StateFlow<GateStep> = _gateStep.asStateFlow()

  private val _gateError = MutableStateFlow<String?>(null)
  val gateError: StateFlow<String?> = _gateError.asStateFlow()

  fun clearGateError() {
    _gateError.value = null
  }

  /**
   * Re-syncs [gateStep] with the current persisted PIN state.
   *
   * [_gateStep] is only seeded once, when the ViewModel is first constructed. If this same
   * ViewModel instance survives across multiple visits to [SecureFolderGateScreen] (e.g. the
   * ViewModelStoreOwner isn't recreated per visit), a stale SETUP step could keep being shown
   * even after a PIN has already been saved — asking the user to "set up" the Secure Folder
   * again every time they open it. The gate screen calls this on every entry so the step
   * always reflects [SecureFolderPreferences.isPinSet] rather than a cached value. Mid-flow
   * states (forgot-PIN) are left alone since those aren't driven by isPinSet().
   */
  fun refreshGateStep() {
    if (_gateStep.value == GateStep.FORGOT_PIN_QUESTION || _gateStep.value == GateStep.FORGOT_PIN_NEW_PIN) return
    _gateStep.value = if (preferences.isPinSet()) GateStep.ENTER_PIN else GateStep.SETUP
  }

  /** Called from ENTER_PIN. On success the caller (Gate screen) navigates to the grid. */
  fun verifyPin(pin: String): Boolean {
    val ok = preferences.verifyPin(pin)
    _gateError.value = if (ok) null else "Incorrect PIN"
    return ok
  }

  /**
   * First-time setup: PIN and security question/answer are validated and persisted together in
   * one atomic call — there's no intermediate "pending PIN" state to lose between steps.
   */
  fun submitSetup(
    pin: String,
    question: String,
    answer: String,
  ): Boolean {
    if (pin.length < 4) {
      _gateError.value = "PIN must be at least 4 digits"
      return false
    }
    if (question.isBlank() || answer.isBlank()) {
      _gateError.value = "Please answer the security question"
      return false
    }
    preferences.setPin(pin)
    preferences.setSecurityQuestion(question, answer)
    _gateError.value = null
    // Keep gateStep consistent with isPinSet() now that a PIN exists, in case this ViewModel
    // instance is revisited later (see refreshGateStep()) instead of being recreated.
    _gateStep.value = GateStep.ENTER_PIN
    return true
  }

  fun startForgotPinFlow() {
    _gateError.value = null
    _gateStep.value = GateStep.FORGOT_PIN_QUESTION
  }

  fun cancelForgotPinFlow() {
    _gateError.value = null
    _gateStep.value = GateStep.ENTER_PIN
  }

  /** Forgot-PIN flow's last step: persists the new PIN directly, no security question re-ask needed. */
  fun finishForgotPinFlow(pin: String): Boolean {
    if (pin.length < 4) {
      _gateError.value = "PIN must be at least 4 digits"
      return false
    }
    preferences.setPin(pin)
    _gateError.value = null
    _gateStep.value = GateStep.ENTER_PIN
    return true
  }

  fun verifySecurityAnswerForRecovery(answer: String): Boolean {
    val ok = preferences.verifySecurityAnswer(answer)
    if (ok) {
      preferences.resetPinAfterRecovery()
      _gateError.value = null
      _gateStep.value = GateStep.FORGOT_PIN_NEW_PIN
    } else {
      _gateError.value = "That doesn't match our records"
    }
    return ok
  }

  // ============================================================================
  // Grid state (hidden media)
  // ============================================================================

  val secureMedia: StateFlow<List<SecureMediaEntity>> =
    repository
      .observeAll()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val operationProgress = repository.progress

  private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
  val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

  val isInSelectionMode: StateFlow<Boolean> =
    _selectedIds
      .map { it.isNotEmpty() }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  private val _operationResult = MutableStateFlow<String?>(null)
  val operationResult: StateFlow<String?> = _operationResult.asStateFlow()

  private var currentOperationJob: Job? = null

  fun clearOperationResult() {
    _operationResult.value = null
  }

  fun toggleSelection(id: Long) {
    _selectedIds.value =
      if (_selectedIds.value.contains(id)) {
        _selectedIds.value - id
      } else {
        _selectedIds.value + id
      }
  }

  fun handleLongClick(id: Long) {
    toggleSelection(id)
  }

  fun selectAll() {
    _selectedIds.value = secureMedia.value.map { it.id }.toSet()
  }

  fun clearSelection() {
    _selectedIds.value = emptySet()
  }

  fun invertSelection() {
    val all = secureMedia.value.map { it.id }.toSet()
    _selectedIds.value = all - _selectedIds.value
  }

  /** Toggles whether the "Secure Folder" entry point is hidden from the Preferences screen. */
  fun toggleEntryPointHidden() {
    preferences.isEntryPointHidden.set(!preferences.isEntryPointHidden.get())
  }

  fun restoreSelected() {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty() || _isBusy.value) return

    currentOperationJob =
      viewModelScope.launch {
        _isBusy.value = true
        runCatching {
          val entities = repository.getByIds(ids)
          repository.restore(getApplication(), entities)
        }.onSuccess { result ->
          result
            .onSuccess { batch ->
              _operationResult.value =
                if (batch.failedIds.isEmpty()) {
                  "Restored ${batch.succeededIds.size} file(s)"
                } else {
                  "Restored ${batch.succeededIds.size}, failed ${batch.failedIds.size}"
                }
            }.onFailure { e ->
              Log.e(TAG, "Restore failed", e)
              _operationResult.value = "Restore failed: ${e.message}"
            }
        }.onFailure { e ->
          Log.e(TAG, "Restore threw", e)
          _operationResult.value = "Restore failed: ${e.message}"
        }
        _selectedIds.value = emptySet()
        _isBusy.value = false
      }
  }

  fun deleteSelectedForever() {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty() || _isBusy.value) return

    currentOperationJob =
      viewModelScope.launch {
        _isBusy.value = true
        runCatching {
          val entities = repository.getByIds(ids)
          repository.deleteForever(entities)
        }.onSuccess { result ->
          result
            .onSuccess { batch ->
              _operationResult.value =
                if (batch.failedIds.isEmpty()) {
                  "Deleted ${batch.succeededIds.size} file(s)"
                } else {
                  "Deleted ${batch.succeededIds.size}, failed ${batch.failedIds.size}"
                }
            }.onFailure { e ->
              Log.e(TAG, "Delete failed", e)
              _operationResult.value = "Delete failed: ${e.message}"
            }
        }.onFailure { e ->
          Log.e(TAG, "Delete threw", e)
          _operationResult.value = "Delete failed: ${e.message}"
        }
        _selectedIds.value = emptySet()
        _isBusy.value = false
      }
  }

  fun cancelCurrentOperation() {
    repository.cancelOperation()
  }
}
