/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.ai

import android.content.Context
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.preferences.AiPreferences
import app.gyrolet.mpvrx.preferences.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.Locale

data class SubtitleGenerationProgress(
  val progress: Float,
  val stage: String,
)

@kotlinx.serialization.Serializable
private data class SttResponse(
  val text: String? = null,
  val segments: List<SttSegment> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class SttSegment(
  val start: Double = 0.0,
  val end: Double = 0.0,
  val text: String? = null,
)

data class GeneratedSubtitle(
  val content: String,
  val extension: String,
)

class SubtitleGenerationService(
  private val context: Context,
  private val preferences: AiPreferences,
  private val groqSpeechClient: GroqSpeechClient,
  private val openRouterSpeechClient: OpenRouterSpeechClient,
  private val okHttpClient: OkHttpClient,
  private val json: Json,
) {
  companion object {
    private const val CHUNK_DURATION_MS = 15_000L
    private const val CHUNK_OVERLAP_MS = 5_000L
    private const val CHUNK_STEP_MS = CHUNK_DURATION_MS - CHUNK_OVERLAP_MS
  }

  private val audioExtractor = RealtimeAudioChunkExtractor(context)

  internal suspend fun generateSubtitles(
    mediaInput: RealtimeMediaInput,
    videoDurationMs: Long,
    language: String?,
    outputFormat: String,
    onProgress: (SubtitleGenerationProgress) -> Unit = {},
  ): Result<GeneratedSubtitle> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        require(videoDurationMs > 0L) { "Video duration is unavailable" }
        val normalizedFormat =
          outputFormat.lowercase(Locale.ROOT).let {
            if (it == "vtt") "vtt" else "srt"
          }
        val totalChunks = ((videoDurationMs + CHUNK_STEP_MS - 1) / CHUNK_STEP_MS).toInt()
        val allSegments = mutableListOf<SpeechSegment>()
        var previousEndMs = 0L

        for (chunkIndex in 0 until totalChunks) {
          currentCoroutineContext().ensureActive()

          val chunkStartMs = chunkIndex * CHUNK_STEP_MS
          val chunkEndMs = (chunkStartMs + CHUNK_DURATION_MS).coerceAtMost(videoDurationMs)

          val extractProgress = 0.1f + (chunkIndex.toFloat() / totalChunks) * 0.35f
          onProgress(
            SubtitleGenerationProgress(
              extractProgress,
              "Extracting chunk ${chunkIndex + 1}/$totalChunks",
            ),
          )

          val audioChunk = audioExtractor.extract(mediaInput, chunkStartMs, chunkEndMs)

          try {
            val transcribeProgress = 0.45f + (chunkIndex.toFloat() / totalChunks) * 0.45f
            onProgress(
              SubtitleGenerationProgress(
                transcribeProgress,
                "Transcribing chunk ${chunkIndex + 1}/$totalChunks",
              ),
            )

            val transcript =
              transcribe(audioChunk, language).getOrElse { error ->
                throw IllegalStateException(
                  "Transcription failed for chunk ${chunkIndex + 1}/$totalChunks: ${error.message}",
                  error,
                )
              }
            val relativeSegments =
              if (transcript.segments.isEmpty() && transcript.text.isNotBlank()) {
                listOf(SpeechSegment(0L, chunkEndMs - chunkStartMs, transcript.text.trim()))
              } else {
                transcript.segments
              }
            if (relativeSegments.isEmpty()) continue

            val newSegments =
              relativeSegments
                .map { segment ->
                  SpeechSegment(
                    startMs = chunkStartMs + segment.startMs,
                    endMs = chunkStartMs + segment.endMs,
                    text = segment.text,
                  )
                }.filter { segment -> segment.endMs > previousEndMs }

            if (newSegments.isNotEmpty()) {
              allSegments.addAll(newSegments)
              allSegments.sortBy { it.startMs }
              deduplicateSegments(allSegments)
              previousEndMs = allSegments.lastOrNull()?.endMs ?: 0L
            }
          } finally {
            audioChunk.delete()
          }
        }

        currentCoroutineContext().ensureActive()
        onProgress(SubtitleGenerationProgress(0.9f, "Writing subtitle"))

        val segments =
          allSegments.ifEmpty {
            createHeuristicSegments("")
          }
        val content =
          if (normalizedFormat == "vtt") {
            toVtt(segments)
          } else {
            toSrt(segments)
          }

        onProgress(SubtitleGenerationProgress(1f, "Subtitle ready"))
        GeneratedSubtitle(content = content, extension = normalizedFormat)
      }
    }

  private suspend fun transcribe(
    audioFile: File,
    language: String?,
  ): Result<SpeechTranscript> {
    val provider = preferences.sttProvider.get()
    val sttModel = preferences.sttModelFor(provider).get()
    return when (provider) {
      AiProvider.GROQ -> {
        val key = preferences.groqApiKey.get()
        if (key.isBlank()) {
          Result.failure(Exception("Groq API key not configured."))
        } else {
          groqSpeechClient.transcribe(key, audioFile, language, sttModel)
        }
      }
      AiProvider.OPENAI -> {
        val key = preferences.openaiApiKey.get()
        if (key.isBlank()) {
          Result.failure(Exception("OpenAI API key not configured."))
        } else {
          transcribeOpenAiCompatible(
            "https://api.openai.com/v1/audio/transcriptions",
            key,
            sttModel.takeIf { it.startsWith("whisper") || it.contains("transcribe") } ?: "whisper-1",
            audioFile,
            language,
          )
        }
      }
      AiProvider.OPENROUTER -> {
        val key = preferences.openrouterApiKey.get()
        if (key.isBlank()) {
          Result.failure(Exception("OpenRouter API key not configured."))
        } else {
          openRouterSpeechClient.transcribe(key, audioFile, language, sttModel)
        }
      }
      else -> Result.failure(Exception("Only online providers (Groq, OpenAI, OpenRouter) are supported."))
    }
  }

  private suspend fun transcribeOpenAiCompatible(
    baseUrl: String,
    apiKey: String,
    model: String,
    audioFile: File,
    language: String?,
  ): Result<SpeechTranscript> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        val apiClient =
          okHttpClient.withAiUploadTimeouts()

        val bodyBuilder =
          MultipartBody
            .Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
              "response_format",
              if (model.startsWith("whisper", ignoreCase = true)) "verbose_json" else "json",
            ).addFormDataPart("temperature", "0")
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(audioMediaType(audioFile)))

        if (!language.isNullOrBlank()) bodyBuilder.addFormDataPart("language", language)

        val request =
          Request
            .Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(bodyBuilder.build())
            .build()

        apiClient.newCall(request).awaitResponse().use { response ->
          val responseBody = response.body.string()
          if (!response.isSuccessful) {
            throw Exception("STT failed: HTTP ${response.code} ${responseBody.take(240)}")
          }

          val parsed = json.decodeFromString(SttResponse.serializer(), responseBody)
          SpeechTranscript(
            text = parsed.text.orEmpty().trim(),
            segments =
              parsed.segments.mapNotNull { segment ->
                val text = segment.text?.trim().orEmpty()
                if (text.isBlank()) return@mapNotNull null
                SpeechSegment(
                  startMs = (segment.start * 1000).toLong(),
                  endMs = (segment.end * 1000).toLong(),
                  text = text,
                )
              },
          )
        }
      }
    }

  private fun createHeuristicSegments(text: String): List<SpeechSegment> {
    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return words.chunked(9).mapIndexed { index, chunk ->
      val start = index * 3500L
      SpeechSegment(
        startMs = start,
        endMs = start + 3200L,
        text = chunk.joinToString(" "),
      )
    }
  }

  private fun deduplicateSegments(segments: MutableList<SpeechSegment>) {
    var i = 0
    while (i < segments.size - 1) {
      val current = segments[i]
      val next = segments[i + 1]
      if (current.endMs >= next.startMs && current.text == next.text) {
        segments.removeAt(i + 1)
      } else {
        i++
      }
    }
  }

  private fun toSrt(segments: List<SpeechSegment>): String =
    segments
      .mapIndexed { index, segment ->
        "${index + 1}\n${formatSrtTime(segment.startMs)} --> ${formatSrtTime(segment.endMs)}\n${segment.text.trim()}"
      }.joinToString("\n\n")

  private fun toVtt(segments: List<SpeechSegment>): String =
    "WEBVTT\n\n" +
      segments.joinToString("\n\n") { segment ->
        "${formatVttTime(segment.startMs)} --> ${formatVttTime(segment.endMs)}\n${segment.text.trim()}"
      }

  private fun formatSrtTime(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    val seconds = (ms % 60_000) / 1000
    val millis = ms % 1000
    return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
  }

  private fun formatVttTime(ms: Long): String = formatSrtTime(ms).replace(',', '.')
}
