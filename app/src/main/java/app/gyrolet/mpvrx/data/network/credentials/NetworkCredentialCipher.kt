/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts saved network passwords without exposing the Android Keystore to repository code. */
class NetworkCredentialCipher(
  private val keyProvider: () -> SecretKey,
) {
  fun encrypt(plaintext: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
    cipher.updateAAD(PREFIX_BYTES)

    val iv = ENCODER.encodeToString(cipher.iv)
    val ciphertext =
      ENCODER.encodeToString(
        cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)),
      )
    return "$PREFIX$iv.$ciphertext"
  }

  fun decrypt(storedValue: String): String {
    require(isEncrypted(storedValue)) { "Unsupported credential format" }

    val payload = storedValue.removePrefix(PREFIX)
    val separatorIndex = payload.indexOf('.')
    require(separatorIndex > 0 && separatorIndex < payload.lastIndex) {
      "Malformed credential"
    }

    val iv = DECODER.decode(payload.substring(0, separatorIndex))
    val ciphertext = DECODER.decode(payload.substring(separatorIndex + 1))
    require(iv.size == GCM_IV_BYTES && ciphertext.size >= GCM_TAG_BYTES) {
      "Malformed credential"
    }

    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
    cipher.updateAAD(PREFIX_BYTES)
    return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
  }

  fun isEncrypted(storedValue: String): Boolean = storedValue.startsWith(PREFIX)

  companion object {
    private const val PREFIX = "mpvrx-credential:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    private val PREFIX_BYTES = PREFIX.toByteArray(StandardCharsets.UTF_8)
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val DECODER = Base64.getUrlDecoder()
  }
}

/** Supplies the app-only AES key. Android Keystore keys are intentionally excluded from backups. */
object AndroidNetworkCredentialKey {
  private const val KEYSTORE = "AndroidKeyStore"
  private const val KEY_ALIAS = "mpvrx.network-credentials.v1"

  @Synchronized
  fun getOrCreate(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    keyGenerator.init(
      KeyGenParameterSpec
        .Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build(),
    )
    return keyGenerator.generateKey()
  }
}

class NetworkCredentialUnavailableException(
  cause: Throwable? = null,
) : IllegalStateException(
    "Saved password is unavailable. Edit the connection and enter it again.",
    cause,
  )

class NetworkCredentialStorageException(
  cause: Throwable,
) : IllegalStateException("The password could not be stored securely. Try again.", cause)
