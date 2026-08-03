/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Salted SHA-256 hashing for the Secure Folder PIN and security-question answer.
 *
 * Nothing here is meant to defend against a determined attacker with root/adb access to the
 * device — it's a lightweight local gate (same threat model as a launcher app-lock), so a
 * per-value random salt + SHA-256 is enough. We never store the raw PIN/answer.
 */
object PinHasher {
  private const val ALGORITHM = "SHA-256"
  private const val SALT_BYTES = 16

  /** Generates a new random salt, Base64-encoded for storage in SharedPreferences. */
  fun generateSalt(): String {
    val bytes = ByteArray(SALT_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  /** Hashes [value] with [salt]. Both PIN digits and free-text answers work the same way. */
  fun hash(
    value: String,
    salt: String,
  ): String {
    val digest = MessageDigest.getInstance(ALGORITHM)
    digest.update(Base64.decode(salt, Base64.NO_WRAP))
    val hashed = digest.digest(value.trim().toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(hashed, Base64.NO_WRAP)
  }

  /** Constant-time-ish comparison (MessageDigest.isEqual is timing-safe) against a stored hash. */
  fun verify(
    value: String,
    salt: String,
    expectedHash: String,
  ): Boolean {
    if (salt.isBlank() || expectedHash.isBlank()) return false
    return runCatching {
      val actual = Base64.decode(hash(value, salt), Base64.NO_WRAP)
      val expected = Base64.decode(expectedHash, Base64.NO_WRAP)
      MessageDigest.isEqual(actual, expected)
    }.getOrDefault(false)
  }
}
