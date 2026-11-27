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
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.preferences.AiPreferences
import app.gyrolet.mpvrx.preferences.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

@kotlinx.serialization.Serializable
private data class OpenAiTranscriptionResponse(
  val text: String? = null,
  val segments: List<OpenAiTranscriptionSegment> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class OpenAiTranscriptionSegment(
  val start: Double = 0.0,
  val end: Double = 0.0,
  val text: String? = null,
)

internal data class RealtimeSubtitleRequest(
  val mediaInput: RealtimeMediaInput,
  val videoDurationMs: Long,
  val startPositionMs: Long,
  val sourceLanguage: String?,
  val targetLanguage: String?,
)

class RealtimeSubtitleService(
  private val context: Context,
  private val preferences: AiPreferences,
  private val groqSpeechClient: GroqSpeechClient,
  private val openRouterSpeechClient: OpenRouterSpeechClient,
  private val okHttpClient: OkHttpClient,
  private val json: Json,
  private val aiService: AiService,
) {
  companion object {
    private const val CHUNK_DURATION_MS = 5_000L
    private const val CHUNK_OVERLAP_MS = 1_000L
    private const val CHUNK_STEP_MS = CHUNK_DURATION_MS - CHUNK_OVERLAP_MS
    private const val LOOKAHEAD_MS = 20_000L
    private const val SEEK_PREROLL_MS = 1_000L
    private const val POSITION_WAIT_MS = 250L
    private const val MAX_CONCURRENT_CHUNKS = 2
    private const val MAX_TRANSCRIPTION_RETRIES = 2
    private const val INITIAL_RETRY_DELAY_MS = 500L
    private const val MAX_CHUNK_SIZE_BYTES = 20L * 1024 * 1024
    private const val SEEK_IN_PROGRESS = -1L
  }

  private val audioExtractor = RealtimeAudioChunkExtractor(context)
  private var job: Job? = null
  private var seekRequests: Channel<Long>? = null

  data class RealtimeProgress(
    val chunkIndex: Int,
    val totalChunks: Int,
    val stage: String,
  )

  internal fun start(
    request: RealtimeSubtitleRequest,
    scope: CoroutineScope,
    positionProvider: () -> Long,
    onProgress: (RealtimeProgress) -> Unit,
    onNewContent: (String) -> Unit,
    onComplete: () -> Unit,
    onError: (String) -> Unit,
  ) {
    stop()
    val requests = Channel<Long>(Channel.CONFLATED)
    seekRequests = requests
    job =
      scope.launch(Dispatchers.IO) {
        val timeline = RealtimeSubtitleTimeline()
        try {
          requests
            .receiveAsFlow()
            .onStart { emit(request.startPositionMs) }
            .collectLatest { positionMs ->
              if (positionMs == SEEK_IN_PROGRESS) awaitCancellation()
              if (
                processFromPosition(
                  request = request,
                  restartPositionMs = positionMs,
                  timeline = timeline,
                  positionProvider = positionProvider,
                  onProgress = onProgress,
                  onNewContent = onNewContent,
                )
              ) {
                throw SessionCompleted()
              }
            }
        } catch (_: SessionCompleted) {
          withContext(Dispatchers.Main) { onComplete() }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (error: Exception) {
          withContext(Dispatchers.Main) {
            onError(error.message ?: "Real-time subtitle processing failed")
          }
        } finally {
          if (seekRequests === requests) seekRequests = null
        }
      }
  }

  internal fun seekTo(positionMs: Long) {
    seekRequests?.trySend(positionMs.coerceAtLeast(0L))
  }

  internal fun onSeekStarted() {
    seekRequests?.trySend(SEEK_IN_PROGRESS)
  }

  fun stop() {
    seekRequests?.close()
    seekRequests = null
    job?.cancel()
    job = null
  }

  private suspend fun processFromPosition(
    request: RealtimeSubtitleRequest,
    restartPositionMs: Long,
    timeline: RealtimeSubtitleTimeline,
    positionProvider: () -> Long,
    onProgress: (RealtimeProgress) -> Unit,
    onNewContent: (String) -> Unit,
  ): Boolean {
    val firstChunkStart =
      (restartPositionMs - SEEK_PREROLL_MS)
        .coerceIn(0L, (request.videoDurationMs - 1L).coerceAtLeast(0L))
    val totalChunks =
      ((request.videoDurationMs - firstChunkStart + CHUNK_STEP_MS - 1L) / CHUNK_STEP_MS)
        .toInt()
        .coerceAtLeast(1)
    var nextChunkStart = firstChunkStart
    var completedChunks = 0

    while (currentCoroutineContext().isActive && nextChunkStart < request.videoDurationMs) {
      val playbackPosition = maxOf(restartPositionMs, positionProvider()).coerceIn(0L, request.videoDurationMs)
      val allowedStart = (playbackPosition + LOOKAHEAD_MS).coerceAtMost(request.videoDurationMs)
      if (nextChunkStart > allowedStart) {
        delay(POSITION_WAIT_MS)
        continue
      }

      val batchStarts =
        buildList {
          var candidate = nextChunkStart
          repeat(MAX_CONCURRENT_CHUNKS) {
            if (candidate < request.videoDurationMs && candidate <= allowedStart) {
              add(candidate)
              candidate += CHUNK_STEP_MS
            }
          }
        }
      if (batchStarts.isEmpty()) {
        delay(POSITION_WAIT_MS)
        continue
      }

      emitProgress(
        onProgress,
        completedChunks,
        totalChunks,
        context.getString(
          R.string.realtime_subtitles_processing,
          (completedChunks + 1).coerceAtMost(totalChunks),
          totalChunks,
        ),
      )
      coroutineScope {
        val pending =
          batchStarts.map { chunkStartMs ->
            async { transcribeChunk(request, chunkStartMs) }
          }

        pending.forEach { deferred ->
          currentCoroutineContext().ensureActive()
          val chunk = deferred.await()
          var candidates =
            timeline.absoluteCandidates(
              chunkStartMs = chunk.startMs,
              chunkDurationMs = chunk.endMs - chunk.startMs,
              segments = chunk.transcript.segments,
            )
          if (!request.targetLanguage.isNullOrBlank() && candidates.isNotEmpty()) {
            emitProgress(
              onProgress,
              completedChunks,
              totalChunks,
              context.getString(R.string.realtime_subtitles_translating),
            )
            val translation =
              aiService.translateRealtimeCues(
                candidates.map(RealtimeSubtitleCue::sourceText),
                request.targetLanguage,
              )
            translation
              .onSuccess { translated ->
                candidates = candidates.mapIndexed { index, cue -> cue.copy(displayText = translated[index]) }
              }.onFailure {
                emitProgress(
                  onProgress,
                  completedChunks,
                  totalChunks,
                  context.getString(R.string.realtime_subtitles_translation_fallback),
                )
              }
          }

          if (candidates.isNotEmpty()) {
            val snapshot = timeline.merge(candidates)
            withContext(Dispatchers.Main) { onNewContent(snapshot.toSrt()) }
          }
          completedChunks++
          emitProgress(
            onProgress,
            completedChunks,
            totalChunks,
            context.getString(
              R.string.realtime_subtitles_processing,
              completedChunks.coerceAtMost(totalChunks),
              totalChunks,
            ),
          )
        }
      }

      nextChunkStart = batchStarts.last() + CHUNK_STEP_MS
    }

    return nextChunkStart >= request.videoDurationMs
  }

  private suspend fun transcribeChunk(
    request: RealtimeSubtitleRequest,
    startMs: Long,
  ): TranscribedChunk {
    val endMs = (startMs + CHUNK_DURATION_MS).coerceAtMost(request.videoDurationMs)
    val audioFile = audioExtractor.extract(request.mediaInput, startMs, endMs)
    try {
      check(audioFile.length() <= MAX_CHUNK_SIZE_BYTES) { "Audio chunk exceeds the transcription provider limit" }
      var lastError: Throwable? = null
      repeat(MAX_TRANSCRIPTION_RETRIES + 1) { attempt ->
        currentCoroutineContext().ensureActive()
        val result = transcribe(audioFile, request.sourceLanguage)
        result.getOrNull()?.let { transcript ->
          val normalizedTranscript =
            if (transcript.segments.isEmpty() && transcript.text.isNotBlank()) {
              transcript.copy(
                segments =
                  listOf(
                    SpeechSegment(
                      startMs = 0L,
                      endMs = endMs - startMs,
                      text = transcript.text.trim(),
                    ),
                  ),
              )
            } else {
              transcript
            }
          return TranscribedChunk(startMs, endMs, normalizedTranscript)
        }
        lastError = result.exceptionOrNull()
        if (attempt < MAX_TRANSCRIPTION_RETRIES) {
          delay(INITIAL_RETRY_DELAY_MS shl attempt)
        }
      }
      throw IllegalStateException(
        "Transcription failed at ${startMs / 1000}s after ${MAX_TRANSCRIPTION_RETRIES + 1} attempts: " +
          (lastError?.message ?: "unknown provider error"),
        lastError,
      )
    } finally {
      audioFile.delete()
    }
  }

  private suspend fun emitProgress(
    callback: (RealtimeProgress) -> Unit,
    chunkIndex: Int,
    totalChunks: Int,
    stage: String,
  ) {
    withContext(Dispatchers.Main) {
      callback(RealtimeProgress(chunkIndex.coerceAtMost(totalChunks), totalChunks, stage))
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
        if (key.isBlank()) return Result.failure(IllegalStateException("Groq API key is not configured"))
        groqSpeechClient.transcribe(key, audioFile, language, sttModel)
      }

      AiProvider.OPENAI -> {
        val key = preferences.openaiApiKey.get()
        if (key.isBlank()) return Result.failure(IllegalStateException("OpenAI API key is not configured"))
        transcribeOpenAiCompatible(
          baseUrl = "https://api.openai.com/v1/audio/transcriptions",
          apiKey = key,
          model = sttModel.takeIf { it.startsWith("whisper") || it.contains("transcribe") } ?: "whisper-1",
          audioFile = audioFile,
          language = language,
        )
      }

      AiProvider.OPENROUTER -> {
        val key = preferences.openrouterApiKey.get()
        if (key.isBlank()) return Result.failure(IllegalStateException("OpenRouter API key is not configured"))
        openRouterSpeechClient.transcribe(key, audioFile, language, sttModel)
      }

      else -> Result.failure(IllegalStateException("$provider does not support speech-to-text"))
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
      try {
        val fileSizeMb = audioFile.length() / (1024 * 1024)
        if (audioFile.length() > MAX_CHUNK_SIZE_BYTES) {
          throw Exception("Audio file too large ($fileSizeMb MB) for transcription API limit. Try a shorter video.")
        }

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

        if (!language.isNullOrBlank()) {
          bodyBuilder.addFormDataPart("language", language)
        }

        val request =
          Request
            .Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(bodyBuilder.build())
            .build()

        apiClient.newCall(request).awaitResponse().use { response ->
          val body = response.body.string()
          if (!response.isSuccessful) {
            throw Exception("OpenAI transcription failed: HTTP ${response.code} ${body.take(240)}")
          }

          val parsed = json.decodeFromString(OpenAiTranscriptionResponse.serializer(), body)
          Result.success(
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
            ),
          )
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  private data class TranscribedChunk(
    val startMs: Long,
    val endMs: Long,
    val transcript: SpeechTranscript,
  )

  private class SessionCompleted : RuntimeException()
}

internal fun audioMediaType(file: File): okhttp3.MediaType {
  val ext = file.name.substringAfterLast('.', "").lowercase()
  return when (ext) {
    "wav" -> "audio/wav".toMediaType()
    "webm" -> "audio/webm".toMediaType()
    else -> "audio/mp4".toMediaType()
  }
}
