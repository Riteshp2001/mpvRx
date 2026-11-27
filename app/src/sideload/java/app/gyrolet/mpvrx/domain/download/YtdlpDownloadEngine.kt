/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.download

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.network.AndroidCookieJar
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.HttpUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads HLS / extractor-backed links (YouTube, m3u8, ...) with the bundled yt-dlp
 * runtime, which handles playlist resolution, segment downloading and AES-128 decryption.
 * Jobs run one at a time inside [YtdlpDownloadService] so they survive backgrounding.
 */
class YtdlpDownloadEngine(
  private val context: Context,
  private val preferences: YtdlPreferences,
  private val outputReservations: DownloadOutputReservations,
) {
  enum class JobState { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

  data class Job(
    val id: Int,
    val url: String,
    val title: String,
    val directory: String,
    val formatSelector: String? = null,
    val mergeSeparateStreams: Boolean = false,
    val posterUrl: String? = null,
    val state: JobState = JobState.QUEUED,
    val progressPercent: Float = 0f,
    val detail: String = "",
    val error: String? = null,
    val outputFile: String? = null,
    val artifactFiles: Set<String> = emptySet(),
  ) {
    val isActive: Boolean get() = state == JobState.QUEUED || state == JobState.RUNNING
  }

  private val nextId = AtomicInteger(1)

  private data class JobFiles(
    val reservation: DownloadOutputReservations.Reservation,
    var published: File? = null,
  )

  private data class Removal(
    val job: Job,
    val deleteFiles: Boolean,
  )

  private val jobFiles = ConcurrentHashMap<Int, JobFiles>()
  private val removals = ConcurrentHashMap<Int, Removal>()
  private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _jobs = MutableStateFlow<List<Job>>(emptyList())
  val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

  @Volatile
  private var activeProcess: Process? = null

  @Volatile
  private var activeJobId: Int = -1

  @Volatile
  private var cancelRequested = false

  fun enqueue(
    url: String,
    title: String,
    directory: File,
    formatSelector: String? = null,
    mergeSeparateStreams: Boolean = false,
    posterUrl: String? = null,
  ): Int {
    val id = nextId.getAndIncrement()
    if (!directory.exists()) directory.mkdirs()
    cleanupWorkingDirectory(id)
    _jobs.update { current ->
      current +
        Job(
          id = id,
          url = url,
          title = title,
          directory = directory.absolutePath,
          formatSelector = formatSelector?.trim()?.takeIf(String::isNotBlank),
          mergeSeparateStreams = mergeSeparateStreams,
          posterUrl = posterUrl,
        )
    }
    YtdlpDownloadService.start(context)
    return id
  }

  fun cancel(id: Int) {
    _jobs.update { current ->
      current.map { job ->
        if (job.id == id && job.state == JobState.QUEUED) job.copy(state = JobState.CANCELLED) else job
      }
    }
    if (activeJobId == id) {
      cancelRequested = true
      activeProcess?.destroyForcibly()
    }
  }

  fun retry(id: Int) {
    _jobs.update { current ->
      current.map { job ->
        if (job.id == id && (job.state == JobState.FAILED || job.state == JobState.CANCELLED)) {
          job.copy(state = JobState.QUEUED, progressPercent = 0f, error = null, detail = "", outputFile = null)
        } else {
          job
        }
      }
    }
    YtdlpDownloadService.start(context)
  }

  fun remove(
    id: Int,
    deleteFiles: Boolean = false,
  ) {
    val job = _jobs.value.firstOrNull { it.id == id } ?: return
    if (job.isActive) cancel(id)
    removals[id] = Removal(job, deleteFiles)
    _jobs.update { current -> current.filterNot { it.id == id } }
    if (activeJobId != id) cleanupScope.launch { finishRemoval(id) }
  }

  /** Runs queued jobs sequentially until the queue drains. Called from the service. */
  suspend fun drainQueue(onJobUpdate: (Job) -> Unit) {
    while (true) {
      val job = _jobs.value.firstOrNull { it.state == JobState.QUEUED } ?: return
      updateJob(job.id) { it.copy(state = JobState.RUNNING) }
      currentJob(job.id)?.let(onJobUpdate)
      runJob(job.id, onJobUpdate)
    }
  }

  private suspend fun runJob(
    id: Int,
    onJobUpdate: (Job) -> Unit,
  ) {
    activeJobId = id
    try {
      runJobInternal(id, onJobUpdate)
    } catch (error: CancellationException) {
      activeProcess?.destroyForcibly()
      updateJob(id) { it.copy(state = JobState.CANCELLED, detail = "") }
      throw error
    } catch (error: Exception) {
      Log.e(TAG, "Could not prepare or finalize yt-dlp download $id", error)
      updateJob(id) { it.copy(state = JobState.FAILED, error = error.message ?: "Download failed") }
      currentJob(id)?.let(onJobUpdate)
    } finally {
      activeProcess = null
      activeJobId = -1
      withContext(NonCancellable + Dispatchers.IO) { finishRemoval(id) }
    }
  }

  private suspend fun runJobInternal(
    id: Int,
    onJobUpdate: (Job) -> Unit,
  ) {
    val queuedJob = currentJob(id) ?: return
    cancelRequested = false
    activeJobId = id

    try {
      if (queuedJob.posterUrl.isNullOrBlank() && HttpUtils.isYouTubeUrl(Uri.parse(queuedJob.url))) {
        HttpUtils.fetchYouTubeMetadata(queuedJob.url)?.let { metadata ->
          updateJob(id) {
            it.copy(title = metadata.title.takeIf(String::isNotBlank) ?: it.title, posterUrl = metadata.thumbnailUrl)
          }
          currentJob(id)?.let(onJobUpdate)
        }
      }
    } catch (error: CancellationException) {
      activeJobId = -1
      throw error
    }
    val job = currentJob(id)
    if (cancelRequested || job == null) {
      activeJobId = -1
      updateJob(id) { it.copy(state = JobState.CANCELLED, detail = "") }
      currentJob(id)?.let(onJobUpdate)
      return
    }

    val runtimeOutput = StringBuilder()
    val ready =
      try {
        YtdlpManager.ensureRuntimeInstalled(context) { message ->
          runtimeOutput.append(message)
          if (runtimeOutput.length > 8_192) runtimeOutput.delete(0, runtimeOutput.length - 8_192)
        }
      } catch (error: Exception) {
        if (error is CancellationException) {
          activeJobId = -1
          throw error
        }
        runtimeOutput.append(error.message ?: error.javaClass.simpleName)
        false
      }
    if (cancelRequested || currentJob(id) == null) {
      activeJobId = -1
      updateJob(id) { it.copy(state = JobState.CANCELLED, detail = "") }
      currentJob(id)?.let(onJobUpdate)
      return
    }
    if (!ready) {
      activeJobId = -1
      updateJob(id) {
        it.copy(
          state = JobState.FAILED,
          error = runtimeOutput.toString().trim().ifBlank { "yt-dlp runtime is not installed" },
        )
      }
      currentJob(id)?.let(onJobUpdate)
      return
    }

    val temporaryDirectory = workingDirectory(id)
    check(
      temporaryDirectory.isDirectory || temporaryDirectory.mkdirs(),
    ) { "Could not create download working directory" }
    val files =
      jobFiles[id] ?: JobFiles(
        outputReservations.reserve(File(job.directory), "${DownloadLocations.sanitizeName(job.title)}.mp4"),
      ).also { jobFiles[id] = it }
    val outputBaseName = files.reservation.baseName
    val outputTemplate =
      if (job.mergeSeparateStreams) {
        "$outputBaseName.f%(format_id)s.%(ext)s"
      } else {
        "$outputBaseName.%(ext)s"
      }
    val command =
      buildCommand(
        url = job.url,
        outputTemplate = outputTemplate,
        outputDirectory = temporaryDirectory.absolutePath,
        temporaryDirectory = temporaryDirectory.absolutePath,
        formatSelector = job.formatSelector,
      )
    val observedArtifacts = linkedSetOf<String>()
    val errorOutput = ArrayDeque<String>()
    var destination: String? = null
    var printedOutput: String? = null

    val result =
      withContext(Dispatchers.IO) {
        runCatching {
          if (cancelRequested) return@runCatching -1
          val process = startProcess(command)
          activeProcess = process
          if (cancelRequested) process.destroyForcibly()
          BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { line ->
              parseDestination(line)?.let { path ->
                destination = path
                resolveJobOutput(job, path)?.let { file -> observedArtifacts += file.absolutePath }
              }
              parseFinalOutput(line)?.let { path ->
                printedOutput = path
                resolveJobOutput(job, path)?.let { file -> observedArtifacts += file.absolutePath }
              }
              val progress = parseProgressLine(line)
              if (progress != null) {
                updateJob(id) { it.copy(progressPercent = progress.first, detail = progress.second) }
                currentJob(id)?.let(onJobUpdate)
              } else if (line.isNotBlank()) {
                errorOutput.addLast(line.take(2_048))
                if (errorOutput.size > 8) errorOutput.removeFirst()
              }
            }
          }
          val exitCode = runInterruptible { process.waitFor() }
          exitCode
        }
      }

    activeProcess = null

    result
      .onSuccess { exitCode ->
        when {
          cancelRequested ->
            updateJob(id) {
              it.copy(
                state = JobState.CANCELLED,
                detail = "",
                artifactFiles = it.artifactFiles + observedArtifacts,
              )
            }
          exitCode == 0 -> {
            val sourceArtifacts =
              observedArtifacts.toSet()
            val resolvedResult =
              runCatching {
                val temporaryOutput =
                  if (job.mergeSeparateStreams) {
                    updateJob(id) { it.copy(progressPercent = 99f, detail = "") }
                    val mergedOutput = File(temporaryDirectory, "merged.mp4")
                    check(!mergedOutput.exists() || mergedOutput.delete()) { "Could not reset owned merge output" }
                    YtdlpMediaMerger.merge(
                      context = context,
                      candidates = discoverSeparateStreamFiles(job).toList(),
                      outputFile = mergedOutput,
                    )
                  } else {
                    printedOutput
                      ?.let { resolveJobOutput(job, it) }
                      ?.takeIf(File::isFile)
                      ?: destination
                        ?.let { resolveJobOutput(job, it) }
                        ?.takeIf { isFinalOutputCandidate(job, it) }
                      ?: findNewestOutput(job)?.let(::File)
                      ?: throw IllegalStateException("yt-dlp finished without a playable output file")
                  }
                currentCoroutineContext().ensureActive()
                check(!cancelRequested && currentJob(id) != null) { "Download was cancelled before publication" }
                val output = File(job.directory, "$outputBaseName.${temporaryOutput.extension}")
                DownloadOutputReservations.publish(temporaryOutput, output).also { files.published = it }
              }
            resolvedResult
              .onSuccess { resolved ->
                cleanupWorkingDirectory(id)
                updateJob(id) {
                  it.copy(
                    state = JobState.SUCCESS,
                    progressPercent = 100f,
                    detail = "",
                    outputFile = resolved.absolutePath,
                    artifactFiles = setOf(resolved.absolutePath),
                  )
                }
                AppDownloadManager.notifyCompletedMedia(context, resolved)
              }.onFailure { error ->
                if (error is CancellationException) throw error
                updateJob(id) {
                  it.copy(
                    state = if (cancelRequested) JobState.CANCELLED else JobState.FAILED,
                    progressPercent = 0f,
                    detail = "",
                    error = error.message ?: "Could not finalize downloaded media",
                    artifactFiles = it.artifactFiles + sourceArtifacts,
                  )
                }
              }
          }
          else ->
            updateJob(id) {
              it.copy(
                state = JobState.FAILED,
                error =
                  errorOutput.lastOrNull { line -> line.startsWith("ERROR:", ignoreCase = true) }
                    ?: errorOutput.joinToString("\n").ifBlank { "yt-dlp exited with code $exitCode" },
                artifactFiles = it.artifactFiles + observedArtifacts,
              )
            }
        }
      }.onFailure { error ->
        if (error is CancellationException) throw error
        Log.e(TAG, "yt-dlp download failed", error)
        updateJob(id) {
          it.copy(
            state = if (cancelRequested) JobState.CANCELLED else JobState.FAILED,
            error = if (cancelRequested) null else error.message ?: "Unknown error",
            artifactFiles = it.artifactFiles + observedArtifacts,
          )
        }
      }
    currentJob(id)?.let(onJobUpdate)
  }

  private fun buildCommand(
    url: String,
    outputTemplate: String,
    outputDirectory: String,
    temporaryDirectory: String,
    formatSelector: String?,
  ): List<String> =
    buildList {
      add(YtdlpManager.getExecutablePath(context))
      add(File(YtdlpManager.getYtdlDir(context), "yt-dlp").absolutePath)
      add("--ignore-config")
      add("--no-playlist")
      add("--newline")
      add("--no-warnings")
      add("--no-colors")
      add("--progress")
      add("--retries")
      add("5")
      add("--fragment-retries")
      add("5")
      add("--concurrent-fragments")
      add("4")
      add("--no-keep-video")
      add("--paths")
      add("home:$outputDirectory")
      add("--paths")
      add("temp:$temporaryDirectory")
      add("--print")
      add("after_move:$FINAL_OUTPUT_PREFIX%(filepath)s")
      add("--format")
      add(formatSelector ?: DEFAULT_SINGLE_FILE_FORMAT)
      add("-o")
      add(outputTemplate)

      preferences.customUserAgent.get().takeIf(String::isNotBlank)?.let { userAgent ->
        add("--user-agent")
        add(userAgent)
      }
      preferences.referer.get().takeIf(String::isNotBlank)?.let { referer ->
        add("--referer")
        add(referer)
      }
      preferences.proxy.get().takeIf(String::isNotBlank)?.let { proxy ->
        add("--proxy")
        add(proxy)
      }
      preferences.extractorArgs.get().takeIf(String::isNotBlank)?.let { extractorArgs ->
        add("--extractor-args")
        add(extractorArgs)
      }
      if (preferences.geoBypass.get()) add("--geo-bypass")

      val cookiesFile =
        preferences.cookiesFile
          .get()
          .takeIf(String::isNotBlank)
          ?.let(::File)
          ?.takeIf(File::isFile)
          ?: AndroidCookieJar.playbackCookieFile(context).takeIf(File::isFile)
      cookiesFile?.let { file ->
        add("--cookies")
        add(file.absolutePath)
      }

      File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
        .takeIf(File::isFile)
        ?.let { quickJs ->
          add("--js-runtimes")
          add("quickjs:${quickJs.absolutePath}")
          add("--remote-components")
          add("ejs:github")
        }
      add("--")
      add(url)
    }

  private fun startProcess(command: List<String>): Process = YtdlpManager.startPythonProcess(command, context)

  private fun findNewestOutput(job: Job): String? =
    workingDirectory(job.id)
      .listFiles()
      ?.filter { isFinalOutputCandidate(job, it) }
      ?.maxByOrNull { it.lastModified() }
      ?.absolutePath

  private fun isFinalOutputCandidate(
    job: Job,
    file: File,
  ): Boolean {
    if (!file.isFile || file.extension.lowercase() !in PLAYABLE_EXTENSIONS) return false
    val prefix = "${jobFiles[job.id]?.reservation?.baseName ?: return false}."
    if (!file.name.startsWith(prefix)) return false
    return !file.name.removePrefix(prefix).contains('.')
  }

  private fun resolveJobOutput(
    job: Job,
    path: String,
  ): File? =
    runCatching {
      val rawPath = path.trim().removeSurrounding("\"")
      val candidate =
        File(
          rawPath,
        ).let { if (it.isAbsolute) it else File(workingDirectory(job.id), rawPath) }.canonicalFile
      candidate.takeIf { it.parentFile == workingDirectory(job.id).canonicalFile }
    }.getOrNull()

  private suspend fun finishRemoval(id: Int) {
    val removal = removals.remove(id) ?: return
    val files = jobFiles.remove(id)
    try {
      if (removal.deleteFiles || removal.job.state != JobState.SUCCESS) {
        files?.published?.let { output ->
          if (output.exists() && !output.delete()) Log.w(TAG, "Could not delete owned download ${output.name}")
        }
      }
      cleanupWorkingDirectory(id)
    } finally {
      files?.reservation?.let { outputReservations.release(it) }
    }
  }

  private fun discoverSeparateStreamFiles(job: Job): Sequence<File> {
    val fileNamePrefix = "${jobFiles[job.id]?.reservation?.baseName ?: return emptySequence()}.f"
    return workingDirectory(job.id)
      .walkTopDown()
      .maxDepth(2)
      .filter { file ->
        file.isFile &&
          file.name.startsWith(fileNamePrefix) &&
          file.extension.lowercase() in PLAYABLE_EXTENSIONS
      }.distinctBy(File::getAbsolutePath)
  }

  private fun workingDirectory(id: Int): File = File(context.cacheDir, "$WORK_DIRECTORY/$id")

  private fun cleanupWorkingDirectory(id: Int) {
    val directory = workingDirectory(id)
    if (directory.exists() && !directory.deleteRecursively()) {
      Log.w(TAG, "Could not remove working files for download $id")
    }
  }

  private fun currentJob(id: Int): Job? = _jobs.value.firstOrNull { it.id == id }

  private fun updateJob(
    id: Int,
    transform: (Job) -> Job,
  ) {
    _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
  }

  companion object {
    private const val TAG = "YtdlpDownloadEngine"
    private const val WORK_DIRECTORY = "ytdlp_downloads"
    private const val FINAL_OUTPUT_PREFIX = "MPVRX_FINAL_OUTPUT="
    private const val DEFAULT_SINGLE_FILE_FORMAT = "best/bestvideo/bestaudio"

    // Example: "[download]  42.3% of ~ 123.45MiB at 2.34MiB/s ETA 01:23"
    private val PROGRESS_REGEX = Regex("""\[download]\s+([0-9.]+)%(.*)""")
    private val DESTINATION_REGEX = Regex("""\[download] Destination: (.+)""")
    private val ALREADY_DOWNLOADED_REGEX = Regex("""\[download] (.+) has already been downloaded""")
    private val PLAYABLE_EXTENSIONS =
      setOf("mkv", "mp4", "m4v", "webm", "mov", "avi", "ts", "m2ts", "mp3", "m4a", "opus", "ogg", "flac", "wav")

    fun parseProgressLine(line: String): Pair<Float, String>? {
      val match = PROGRESS_REGEX.find(line.trim()) ?: return null
      val percent = match.groupValues[1].toFloatOrNull() ?: return null
      return percent.coerceIn(0f, 100f) to match.groupValues[2].trim()
    }

    fun parseDestination(line: String): String? =
      DESTINATION_REGEX
        .find(line.trim())
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: ALREADY_DOWNLOADED_REGEX
          .find(line.trim())
          ?.groupValues
          ?.get(1)
          ?.trim()

    fun parseFinalOutput(line: String): String? =
      line
        .trim()
        .takeIf { it.startsWith(FINAL_OUTPUT_PREFIX) }
        ?.removePrefix(FINAL_OUTPUT_PREFIX)
        ?.trim()
  }
}
