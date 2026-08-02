/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.utils.security.PinHasher

/**
 * Preferences backing the Secure Folder feature: PIN gate, forgot-PIN security question,
 * whether the entry point itself is hidden from Preferences, and per-action "don't ask again"
 * flags for the confirm dialogs (Step 4).
 *
 * Only salted hashes are ever persisted — never the raw PIN or the raw answer text.
 */
class SecureFolderPreferences(
  preferenceStore: PreferenceStore,
) {
  // ============================================================================
  // PIN
  // ============================================================================
  private val pinHash = preferenceStore.getString("secure_folder_pin_hash", "")
  private val pinSalt = preferenceStore.getString("secure_folder_pin_salt", "")

  fun isPinSet(): Boolean = pinHash.get().isNotBlank()

  /** Hashes and stores a new PIN, replacing any existing one. */
  fun setPin(pin: String) {
    val salt = PinHasher.generateSalt()
    pinSalt.set(salt)
    pinHash.set(PinHasher.hash(pin, salt))
  }

  fun verifyPin(pin: String): Boolean = PinHasher.verify(pin, pinSalt.get(), pinHash.get())

  fun clearPin() {
    pinHash.delete()
    pinSalt.delete()
  }

  // ============================================================================
  // Forgot-PIN security question
  // ============================================================================
  val securityQuestion = preferenceStore.getString("secure_folder_security_question", "")
  private val securityAnswerHash = preferenceStore.getString("secure_folder_security_answer_hash", "")
  private val securityAnswerSalt = preferenceStore.getString("secure_folder_security_answer_salt", "")

  fun isSecurityQuestionSet(): Boolean = securityQuestion.get().isNotBlank() && securityAnswerHash.get().isNotBlank()

  fun setSecurityQuestion(
    question: String,
    answer: String,
  ) {
    val salt = PinHasher.generateSalt()
    securityQuestion.set(question)
    securityAnswerSalt.set(salt)
    securityAnswerHash.set(PinHasher.hash(normalizeAnswer(answer), salt))
  }

  fun verifySecurityAnswer(answer: String): Boolean =
    PinHasher.verify(normalizeAnswer(answer), securityAnswerSalt.get(), securityAnswerHash.get())

  private fun normalizeAnswer(answer: String): String = answer.trim().lowercase()

  /** Call after a successful forgot-PIN flow: wipes the PIN so setup runs again, keeps the Q&A. */
  fun resetPinAfterRecovery() {
    clearPin()
  }

  // ============================================================================
  // Visibility of the Secure Folder entry point
  // ============================================================================

  /** When true, the "Secure Folder" row is hidden from the Preferences screen (still reachable via title double-tap etc., wired in Step 5). */
  val isEntryPointHidden = preferenceStore.getBoolean("secure_folder_entry_hidden", false)

  // ============================================================================
  // Biometric authentication
  // ============================================================================
  val isBiometricEnabled = preferenceStore.getBoolean("secure_folder_biometric_enabled", false)

  // ============================================================================
  // Don't-ask-again flags for SecureConfirmDialog (Step 4)
  // ============================================================================
  val dontAskBeforeMove = preferenceStore.getBoolean("secure_folder_dont_ask_move", false)
  val dontAskBeforeRestore = preferenceStore.getBoolean("secure_folder_dont_ask_restore", false)
  val dontAskBeforeDelete = preferenceStore.getBoolean("secure_folder_dont_ask_delete", false)
  val dontAskBeforeHideEntryPoint = preferenceStore.getBoolean("secure_folder_dont_ask_hide_entry_point", false)

  /** Full reset — used for testing/debug or a future "reset Secure Folder" action. */
  fun resetAll() {
    clearPin()
    securityQuestion.delete()
    securityAnswerHash.delete()
    securityAnswerSalt.delete()
    isEntryPointHidden.delete()
    isBiometricEnabled.delete()
    dontAskBeforeMove.delete()
    dontAskBeforeRestore.delete()
    dontAskBeforeDelete.delete()
    dontAskBeforeHideEntryPoint.delete()
  }
}
