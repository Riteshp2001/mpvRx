/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences.preference

import android.util.Log
import app.gyrolet.mpvrx.data.network.credentials.AndroidNetworkCredentialKey
import app.gyrolet.mpvrx.data.network.credentials.NetworkCredentialCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Stores a secret (API key) encrypted with the app's Android Keystore key. Plaintext values saved
 * by older versions are read as-is and re-saved encrypted. If the key is gone (restored on another
 * device), the value reads as empty and the user enters it again.
 */
class EncryptedStringPreference(
  private val delegate: Preference<String>,
) : Preference<String> {
  @Volatile private var decrypted: Pair<String, String>? = null

  override fun key(): String = delegate.key()

  override fun get(): String = reveal(delegate.get())

  override fun set(value: String) {
    if (value.isEmpty()) {
      delegate.set("")
      return
    }
    val stored =
      runCatching { cipher.encrypt(value) }
        .onFailure { Log.w(TAG, "Keystore unavailable; storing ${key()} unencrypted", it) }
        .getOrDefault(value)
    decrypted = stored to value
    delegate.set(stored)
  }

  override fun isSet(): Boolean = delegate.isSet()

  override fun delete() = delegate.delete()

  override fun defaultValue(): String = ""

  override fun changes(): Flow<String> = delegate.changes().map(::reveal).distinctUntilChanged()

  override fun stateIn(scope: CoroutineScope): StateFlow<String> =
    changes().stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), get())

  private fun reveal(stored: String): String {
    if (stored.isEmpty()) return ""
    decrypted?.let { (cipherText, plain) -> if (cipherText == stored) return plain }
    val c = runCatching { cipher }.getOrNull() ?: return stored
    if (!c.isEncrypted(stored)) {
      // Legacy plaintext: migrate in place.
      runCatching { c.encrypt(stored) }.onSuccess { encrypted ->
        decrypted = encrypted to stored
        delegate.set(encrypted)
      }
      return stored
    }
    return runCatching { c.decrypt(stored) }
      .onFailure { Log.w(TAG, "Couldn't decrypt ${key()}", it) }
      .getOrDefault("")
      .also { decrypted = stored to it }
  }

  private companion object {
    const val TAG = "EncryptedPreference"
    val cipher: NetworkCredentialCipher by lazy { NetworkCredentialCipher(AndroidNetworkCredentialKey::getOrCreate) }
  }
}

fun PreferenceStore.getEncryptedString(key: String): Preference<String> = EncryptedStringPreference(getString(key, ""))
