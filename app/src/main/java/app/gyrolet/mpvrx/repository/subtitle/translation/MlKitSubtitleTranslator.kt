/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import app.gyrolet.mpvrx.network.HomeNetworkGuard
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit on-device translation: official, free, no API key, and works offline once the
 * language models (~30 MB each) are downloaded. Quality is below cloud engines.
 */
class MlKitSubtitleTranslator : SubtitleTranslator {
  override val cacheId: String = "mlkit"
  override val maxTextsPerRequest: Int = 40
  override val maxCharsPerRequest: Int = 4_000
  override val requiresSourceLanguage: Boolean = true
  override val preservesInlineMarkup: Boolean = false

  private var translator: Translator? = null
  private var pair: Pair<String, String>? = null

  override suspend fun prepare(
    sourceLanguage: String?,
    targetLanguage: String,
    onStatus: (String) -> Unit,
  ) {
    val source =
      sourceLanguage?.let(::mlKitLanguage)
        ?: throw SubtitleTranslationException.UnsupportedLanguage(
          if (sourceLanguage == null) {
            "Couldn't detect the subtitle's language, which offline translation needs."
          } else {
            "Offline translation doesn't support ${TranslationLanguages.displayName(sourceLanguage)}."
          },
        )
    val target =
      mlKitLanguage(targetLanguage)
        ?: throw SubtitleTranslationException.UnsupportedLanguage(
          "Offline translation doesn't support ${TranslationLanguages.displayName(targetLanguage)}.",
        )
    if (pair == source to target && translator != null) return

    close()
    val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
    translator = client
    pair = source to target

    val missing = missingModels(source, target)
    if (missing.isNotEmpty()) {
      if (HomeNetworkGuard.enabled) throw modelsNeedInternet(missing)
      onStatus("Downloading offline ${missing.joinToString(" + ") { TranslationLanguages.displayName(it) }} model")
    }
    try {
      client.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      throw SubtitleTranslationException.Network(
        "Couldn't download the offline language model. It needs an internet connection once.",
        e,
      )
    }
  }

  override suspend fun translate(
    texts: List<String>,
    sourceLanguage: String?,
    targetLanguage: String,
  ): List<String> {
    val client = translator ?: throw SubtitleTranslationException.Configuration("Offline translator isn't ready")
    return texts.map { text ->
      try {
        client.translate(text).awaitTask()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw SubtitleTranslationException.InvalidResponse("Offline translation failed: ${e.message}")
      }
    }
  }

  override fun close() {
    translator?.close()
    translator = null
    pair = null
  }

  private suspend fun missingModels(
    source: String,
    target: String,
  ): List<String> {
    val downloaded = runCatching { downloadedLanguages() }.getOrDefault(emptySet())
    return listOf(source, target).filter { it != TranslateLanguage.ENGLISH && it !in downloaded }
  }

  companion object {
    /** ISO codes of offline models already on the device. */
    suspend fun downloadedLanguages(): Set<String> =
      RemoteModelManager
        .getInstance()
        .getDownloadedModels(TranslateRemoteModel::class.java)
        .awaitTask()
        .map { it.language }
        .toSet()

    /**
     * Model downloads come from Google's servers, so "Home network only" refuses them: the user
     * turns it off for the one-time download and back on afterwards.
     */
    private fun modelsNeedInternet(codes: List<String>) =
      SubtitleTranslationException.Configuration(
        "The offline ${codes.joinToString(" + ") { TranslationLanguages.displayName(it) }} model isn't downloaded yet. " +
          "Downloading it needs the internet once: turn off Home network only (Settings → Advanced), " +
          "download it in Settings → Subtitles → Offline languages, then turn Home network only back on.",
      )

    /** Downloads one language's offline model (~30 MB) from Google's servers. */
    suspend fun downloadLanguage(code: String) {
      if (HomeNetworkGuard.enabled) throw modelsNeedInternet(listOf(code))
      val language = mlKitLanguage(code) ?: throw SubtitleTranslationException.UnsupportedLanguage("Offline translation doesn't support ${TranslationLanguages.displayName(code)}.")
      RemoteModelManager
        .getInstance()
        .download(TranslateRemoteModel.Builder(language).build(), DownloadConditions.Builder().build())
        .awaitTask()
    }

    suspend fun deleteLanguage(code: String) {
      val language = mlKitLanguage(code) ?: return
      if (language == TranslateLanguage.ENGLISH) return
      RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).awaitTask()
    }

    fun mlKitLanguage(code: String): String? =
      TranslateLanguage.fromLanguageTag(
        when (code) {
          "zh-TW" -> "zh"
          else -> code
        },
      )
  }
}

/** On-device language identification (bundled model, no download, no network). */
object MlKitLanguageDetector {
  /** Returns an ISO 639-1 code, or null if the sample is too short or ambiguous. */
  suspend fun detect(sample: String): String? {
    if (sample.isBlank()) return null
    val client = LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.6f).build())
    return try {
      client
        .identifyLanguage(sample.take(4_000))
        .awaitTask()
        .takeIf { it != "und" }
        ?.let(TranslationLanguages::normalizeCode)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    } finally {
      client.close()
    }
  }
}

internal suspend fun <T> Task<T>.awaitTask(): T =
  suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
  }
