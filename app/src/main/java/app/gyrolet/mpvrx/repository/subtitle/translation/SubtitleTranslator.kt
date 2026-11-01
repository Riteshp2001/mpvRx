/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

/** A translation engine. Implementations translate plain text; markup is handled by the caller. */
interface SubtitleTranslator {
  /** Identifies the engine and its configuration for the resume cache (e.g. "libre:https://host"). */
  val cacheId: String
  val maxTextsPerRequest: Int
  val maxCharsPerRequest: Int

  /** Engines that can't auto-detect need the source language up front. */
  val requiresSourceLanguage: Boolean get() = false

  /**
   * Whether `⟦n⟧` markup placeholders survive translation without hurting it. Engines that treat
   * a placeholder-wrapped word as code (leaving it untranslated) get plain sentences instead; their
   * inline tags are re-attached at the line edges.
   */
  val preservesInlineMarkup: Boolean get() = true

  /** One-time setup before the first batch, such as downloading an offline model. */
  suspend fun prepare(
    sourceLanguage: String?,
    targetLanguage: String,
    onStatus: (String) -> Unit,
  ) = Unit

  /** Returns one translation per input, in order. Throws [SubtitleTranslationException]. */
  suspend fun translate(
    texts: List<String>,
    sourceLanguage: String?,
    targetLanguage: String,
  ): List<String>

  fun close() = Unit
}

/** Errors carry a message meant for the user. */
sealed class SubtitleTranslationException(
  message: String,
  cause: Throwable? = null,
  /** Worth retrying the same request after a pause. */
  val retryable: Boolean,
) : Exception(message, cause) {
  class Network(
    message: String = "No connection to the translation service. Check your internet connection.",
    cause: Throwable? = null,
  ) : SubtitleTranslationException(message, cause, retryable = true)

  class RateLimited(
    message: String,
    val retryAfterMs: Long? = null,
  ) : SubtitleTranslationException(message, retryable = true)

  class Server(
    message: String,
  ) : SubtitleTranslationException(message, retryable = true)

  class QuotaExceeded(
    message: String,
  ) : SubtitleTranslationException(message, retryable = false)

  class Auth(
    message: String,
  ) : SubtitleTranslationException(message, retryable = false)

  class Configuration(
    message: String,
  ) : SubtitleTranslationException(message, retryable = false)

  class UnsupportedLanguage(
    message: String,
  ) : SubtitleTranslationException(message, retryable = false)

  /** The engine answered, but not with one usable translation per input. Smaller batches may help. */
  class InvalidResponse(
    message: String,
  ) : SubtitleTranslationException(message, retryable = false)
}
