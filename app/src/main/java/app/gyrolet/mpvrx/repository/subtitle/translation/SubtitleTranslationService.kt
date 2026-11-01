/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.gyrolet.mpvrx.preferences.AiPreferences
import app.gyrolet.mpvrx.preferences.SubtitleTranslationPreferences
import app.gyrolet.mpvrx.preferences.SubtitleTranslationProvider
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.ai.AiService
import app.gyrolet.mpvrx.ui.player.resolveLocalPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

/** Reads a subtitle, translates it with the configured provider, saves it and remembers it for reuse. */
class SubtitleTranslationService(
  private val context: Context,
  private val preferences: SubtitleTranslationPreferences,
  private val subtitlesPreferences: SubtitlesPreferences,
  private val aiPreferences: AiPreferences,
  private val aiService: AiService,
  private val client: OkHttpClient,
  private val json: Json,
) {
  data class Request(
    /** External subtitle file; null when translating a track embedded in the video. */
    val subtitleUri: Uri?,
    val subtitleFileName: String?,
    val videoUri: Uri?,
    /** The player's media title; names the per-video folder inside the subtitle save folder. */
    val mediaTitle: String,
    val trackLanguage: String?,
    /** ISO 639-1 target, see [TranslationLanguages]. */
    val targetLanguage: String,
    val embeddedTrack: EmbeddedTrack? = null,
    /** Font the player renders plain subtitles with; translated ASS styles switch to it for non-Latin targets. */
    val subtitleFont: String? = null,
  )

  /** Identifies an MKV subtitle track. */
  data class EmbeddedTrack(
    /** mpv's src-id; a fallback only, since it isn't guaranteed to be the Matroska track number. */
    val matroskaTrackNumber: Long?,
    /** Position among the file's embedded subtitle tracks. */
    val ordinal: Int,
  )

  sealed interface Status {
    data class Preparing(
      val message: String,
    ) : Status

    data class Extracting(
      val fraction: Float,
    ) : Status

    data class Translating(
      val progress: SubtitleTranslationEngine.Progress,
    ) : Status

    data object Saving : Status
  }

  sealed interface Outcome {
    val uri: Uri?

    data class Saved(
      override val uri: Uri,
      val fileName: String,
      val location: SubtitleOutputWriter.Location,
      val sourceLanguage: String?,
      val failedUnits: Int,
    ) : Outcome

    data class Reused(
      override val uri: Uri,
      val fileName: String,
    ) : Outcome

    data class AlreadyInTargetLanguage(
      val language: String,
    ) : Outcome {
      override val uri: Uri? = null
    }
  }

  private val engine by lazy { SubtitleTranslationEngine(FileTranslationBatchCache(context, json)) }
  private val index by lazy { TranslatedSubtitleIndex(context, json) }
  private val writer by lazy { SubtitleOutputWriter(context) }

  val providerName: String get() = preferences.provider.get().displayName

  suspend fun translate(
    request: Request,
    onStatus: (Status) -> Unit,
  ): Outcome =
    withContext(Dispatchers.IO) {
      val input = if (request.embeddedTrack != null) readEmbedded(request, onStatus) else readFile(request, onStatus)
      val decoded = input.decoded
      val format = input.format
      val extension = input.extension
      val hint = input.languageHint

      onStatus(Status.Preparing("Detecting language"))
      val source = MlKitLanguageDetector.detect(sampleText(decoded.text, format)) ?: hint
      val target = request.targetLanguage
      if (source != null && sameLanguage(source, target)) {
        return@withContext Outcome.AlreadyInTargetLanguage(TranslationLanguages.displayName(target))
      }

      val translator = createTranslator()
      try {
        val rtlFix = preferences.rtlFix.get()
        val reuseKey = SubtitleTranslationEngine.sha256(listOf(decoded.text, target, translator.cacheId, rtlFix.toString()).joinToString("\u001f"))
        val sourceKey = SubtitleTranslationEngine.sha256(listOf(decoded.text, target).joinToString("\u001f"))
        index.find(reuseKey)?.let { return@withContext Outcome.Reused(Uri.parse(it.uri), it.fileName) }

        if (translator.requiresSourceLanguage && source == null) {
          throw SubtitleTranslationException.UnsupportedLanguage(
            "Couldn't detect the subtitle's language, which ${providerName} needs. Try another provider.",
          )
        }
        translator.prepare(source, target) { onStatus(Status.Preparing(it)) }

        val result =
          engine.translate(decoded.text, format, translator, source, target, rtlFix) { progress ->
            onStatus(Status.Translating(progress))
          }
        if (result.translatedUnits == 0 && result.failedUnits > 0) {
          throw SubtitleTranslationException.InvalidResponse("$providerName returned no usable translations.")
        }

        onStatus(Status.Saving)
        val outputExtension = extension.takeIf { SubtitleFormat.fromExtension(it) == format } ?: format.name.lowercase()
        val base = outputBaseName(request)
        val names =
          buildList {
            // The language stays last: players read it from the tag right before the extension.
            add("$base.$target.$outputExtension")
            for (n in 2..9) add("$base.$n.$target.$outputExtension")
          }
        val content =
          if (format == SubtitleFormat.ASS && !request.subtitleFont.isNullOrBlank() && !TranslationLanguages.usesLatinScript(target)) {
            // Fonts named by the original styles usually lack Arabic/CJK/… glyphs.
            SubtitleDocument.withAssFont(result.content, request.subtitleFont)
          } else {
            result.content
          }
        val output = if (format == SubtitleFormat.SRT) UTF8_BOM + content.toByteArray() else content.toByteArray()
        val written =
          writer.write(
            fileNames = names,
            bytes = output,
            videoUri = request.videoUri,
            subtitleUri = request.subtitleUri,
            subtitleFolderTree = subtitlesPreferences.subtitleSaveFolder.get(),
            mediaFolderName = request.mediaTitle.substringBeforeLast("."),
            isOurs = { identity -> index.isOurs(identity, sourceKey) },
          )
        index.put(
          TranslatedSubtitleIndex.Entry(reuseKey, written.uri.toString(), written.fileName, written.identity, System.currentTimeMillis(), sourceKey),
        )
        // The local copy loads in the player now; Jellyfin keeps the permanent one beside the movie.
        val location =
          input.jellyfin?.let { server ->
            onStatus(Status.Preparing("Saving to Jellyfin"))
            jellyfinBridge.upload(server, content, format, target)
            SubtitleOutputWriter.Location.JELLYFIN_SERVER
          } ?: written.location
        Outcome.Saved(written.uri, written.fileName, location, source, result.failedUnits)
      } finally {
        translator.close()
      }
    }

  private class Input(
    val decoded: SubtitleTextDecoder.Decoded,
    val format: SubtitleFormat,
    val extension: String,
    val languageHint: String?,
    /** Set for Jellyfin streams: the translation is also uploaded there, next to the movie. */
    val jellyfin: JellyfinSubtitleBridge.Source? = null,
  )

  private val jellyfinBridge by lazy { JellyfinSubtitleBridge(client, json) }

  private suspend fun readJellyfin(
    source: JellyfinSubtitleBridge.Source,
    embedded: EmbeddedTrack,
    onStatus: (Status) -> Unit,
  ): Input {
    onStatus(Status.Preparing("Getting subtitle from Jellyfin"))
    val track =
      jellyfinBridge.embeddedTracks(source).getOrNull(embedded.ordinal)
        ?: throw SubtitleTranslationException.Configuration("Jellyfin doesn't list that subtitle track.")
    if (!track.isText) {
      throw SubtitleTranslationException.UnsupportedLanguage(
        "This is an image-based subtitle (${track.codec.uppercase()}). It contains pictures, not text, so it can't be translated.",
      )
    }
    val (content, format) = jellyfinBridge.download(source, track)
    if (content.isBlank()) throw SubtitleTranslationException.Configuration("That subtitle track is empty.")
    return Input(
      SubtitleTextDecoder.Decoded(content, "UTF-8"),
      format,
      if (format == SubtitleFormat.ASS) "ass" else "srt",
      TranslationLanguages.normalizeCode(track.language),
      jellyfin = source,
    )
  }

  private fun readFile(
    request: Request,
    onStatus: (Status) -> Unit,
  ): Input {
    onStatus(Status.Preparing("Reading subtitle"))
    val uri = request.subtitleUri ?: throw SubtitleTranslationException.Configuration("No subtitle file selected.")
    val fileName = request.subtitleFileName.orEmpty()
    val bytes =
      runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        ?: throw SubtitleTranslationException.Configuration("Couldn't read the subtitle file.")
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val hint = TranslationLanguages.normalizeCode(request.trackLanguage) ?: languageFromFileName(fileName)
    val decoded = SubtitleTextDecoder.decode(bytes, hint)
    val format =
      SubtitleFormat.fromExtension(extension)
        ?: SubtitleFormat.sniff(decoded.text)
        ?: throw SubtitleTranslationException.Configuration(
          "Translation supports SRT, WebVTT and ASS/SSA subtitles${if (extension.isNotBlank()) ", not .$extension" else ""}.",
        )
    return Input(decoded, format, extension, hint)
  }

  /** Extracts an embedded MKV track. The video file is opened read-only and never modified. */
  private suspend fun readEmbedded(
    request: Request,
    onStatus: (Status) -> Unit,
  ): Input {
    val embedded = request.embeddedTrack!!
    JellyfinSubtitleBridge.parse(request.videoUri)?.let { return readJellyfin(it, embedded, onStatus) }
    val videoUri = request.videoUri ?: throw SubtitleTranslationException.Configuration("Couldn't find the video file.")
    if (videoUri.scheme !in setOf("file", "content")) {
      throw SubtitleTranslationException.Configuration("Embedded subtitles can only be translated for videos stored on this device.")
    }
    onStatus(Status.Extracting(0f))
    val descriptor =
      runCatching { context.contentResolver.openFileDescriptor(videoUri, "r") }.getOrNull()
        ?: throw SubtitleTranslationException.Configuration("Couldn't open the video file to read its subtitles.")
    val result =
      descriptor.use { pfd ->
        java.io.FileInputStream(pfd.fileDescriptor).use { stream ->
          val source = FileChannelSource(stream.channel)
          if (!MatroskaSubtitleExtractor.isMatroska(source)) {
            throw SubtitleTranslationException.Configuration("Translating embedded subtitles works for MKV videos. Add the subtitle as a file instead.")
          }
          MatroskaSubtitleExtractor.extract(
            source,
            // mpv lists a file's subtitle tracks in Matroska order, so the ordinal is authoritative.
            select = { tracks ->
              tracks.getOrNull(embedded.ordinal)
                ?: embedded.matroskaTrackNumber?.let { number -> tracks.firstOrNull { it.number == number } }
            },
            onProgress = { onStatus(Status.Extracting(it)) },
          )
        }
      }
    if (result.cueCount == 0) throw SubtitleTranslationException.Configuration("That subtitle track is empty.")
    val hint = TranslationLanguages.normalizeCode(result.track.language) ?: TranslationLanguages.normalizeCode(request.trackLanguage)
    val extension =
      when (result.format) {
        SubtitleFormat.SRT -> "srt"
        SubtitleFormat.VTT -> "vtt"
        SubtitleFormat.ASS -> "ass"
      }
    return Input(SubtitleTextDecoder.Decoded(result.content, "UTF-8"), result.format, extension, hint)
  }

  private fun createTranslator(): SubtitleTranslator =
    when (preferences.provider.get()) {
      SubtitleTranslationProvider.ON_DEVICE -> MlKitSubtitleTranslator()
      SubtitleTranslationProvider.MYMEMORY -> MyMemorySubtitleTranslator(client, json, preferences.myMemoryEmail.get())
      SubtitleTranslationProvider.LIBRETRANSLATE ->
        LibreTranslateSubtitleTranslator(client, json, preferences.libreTranslateUrl.get(), preferences.libreTranslateApiKey.get())
      SubtitleTranslationProvider.AI -> AiSubtitleTranslator(aiService, aiPreferences)
    }

  /** Settings "Test connection" for LibreTranslate; returns how many languages the server offers. */
  suspend fun testLibreTranslate(
    url: String,
    apiKey: String,
  ): Result<Int> =
    withContext(Dispatchers.IO) {
      runCatching { LibreTranslateSubtitleTranslator(client, json, url, apiKey) }.fold(
        onSuccess = { it.testConnection() },
        onFailure = { Result.failure(it) },
      )
    }

  /** Names the output after the video when known, so players auto-load it ("Movie.mkv" → "Movie.ar.srt"). */
  private fun outputBaseName(request: Request): String {
    val videoName =
      request.videoUri?.let { uri ->
        runCatching { uri.resolveLocalPath(context) }.getOrNull()?.let { File(it).name }
          ?: displayName(uri)
      }
    val base =
      videoName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        ?: request.subtitleFileName?.takeIf { it.isNotBlank() }?.let { stripLanguageTag(it.substringBeforeLast('.')) }
        // Streams (Jellyfin) have no file name; the media title keeps copies of different movies apart.
        ?: request.mediaTitle.substringBeforeLast('.')
    return base.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").take(180).ifBlank { "subtitle" }
  }

  private fun displayName(uri: Uri): String? =
    when (uri.scheme) {
      "content" ->
        runCatching {
          context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
          }
        }.getOrNull()
      "file" -> uri.lastPathSegment
      else -> null
    }

  private fun sampleText(
    content: String,
    format: SubtitleFormat,
  ): String {
    val document = SubtitleDocument.parse(content, format)
    val processor = CueTextProcessor(format)
    val cues = document.cues
    // Spread the sample over the file; the first cues are often credits or sound effects.
    val step = (cues.size / 80).coerceAtLeast(1)
    return cues
      .filterIndexed { i, _ -> i % step == 0 }
      .flatMap { processor.plan(it).units }
      .joinToString(" ") { it.text.replace(Regex("""⟦\d+⟧"""), " ") }
  }

  private companion object {
    val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun sameLanguage(
      a: String,
      b: String,
    ): Boolean = a.equals(b, ignoreCase = true) || (a.substringBefore('-') == b.substringBefore('-') && "zh" != a.substringBefore('-'))

    fun languageFromFileName(fileName: String): String? = TranslationLanguages.fromFileName(fileName)

    fun stripLanguageTag(base: String): String {
      val parts = base.split('.')
      if (parts.size < 2) return base
      val trimmed = parts.dropLastWhile { part -> part.lowercase() in setOf("forced", "sdh", "hi", "cc") || languageFromFileName("x.$part.srt") != null }
      return trimmed.joinToString(".").ifBlank { base }
    }
  }
}
