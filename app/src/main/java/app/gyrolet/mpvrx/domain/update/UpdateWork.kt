// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.domain.update

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.gyrolet.mpvrx.App
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

@Serializable
enum class UpdateStage {
  IDLE,
  QUEUED,
  DOWNLOADING,
  READY,
  INSTALLING,
  APPROVAL,
  PERMISSION,
  FAILED,
  CANCELLED,
  COMPLETE,
}

@Serializable
data class UpdateTaskState(
  val release: Release? = null,
  val requestId: String = "",
  val stage: UpdateStage = UpdateStage.IDLE,
  val automatic: Boolean = false,
  val progress: Float = 0f,
  val downloadError: Boolean = false,
  val sessionId: Int = -1,
  val sessionToken: String = "",
  val sessionStartedAt: Long = 0L,
  val useSystemInstaller: Boolean = false,
) {
  val busy: Boolean get() = stage in setOf(UpdateStage.QUEUED, UpdateStage.DOWNLOADING, UpdateStage.INSTALLING)
}

/** WorkManager owns the download; the persisted record also survives PackageInstaller killing us. */
object UpdateWork {
  const val DOWNLOAD_WORK = "app-update-download"
  private const val CHECK_WORK = "app-update-check"
  private const val AUTO_INSTALL_KEY = "auto_install_updates"
  private val json = Json { ignoreUnknownKeys = true }
  private val enqueueMutex = Mutex()
  internal val downloadMutex = Mutex()
  private val mutableState = MutableStateFlow(UpdateTaskState())
  private var initialized = false

  @Synchronized
  fun state(context: Context): StateFlow<UpdateTaskState> {
    if (!initialized) {
      val saved = storage(context).getString("state", null)
      mutableState.value = saved?.let { runCatching { json.decodeFromString<UpdateTaskState>(it) }.getOrNull() }
        ?: UpdateTaskState()
      val restoredRelease = mutableState.value.release
      val fromRetiredChannel =
        listOfNotNull(restoredRelease?.channel, restoredRelease?.tagName).any { value ->
          value.startsWith("experimental", ignoreCase = true) || value.startsWith("preview", ignoreCase = true)
        }
      if (fromRetiredChannel) {
        Log.i("UpdateWork", "Discarding a pending update from a retired update channel")
        mutableState.value = UpdateTaskState()
        check(storage(context).edit().putString("state", json.encodeToString(mutableState.value)).commit())
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD_WORK)
      }
      if (mutableState.value.release?.let { UpdateManager(context).isInstalledRelease(it) } == true) {
        mutableState.value = UpdateTaskState(stage = UpdateStage.COMPLETE)
        check(storage(context).edit().putString("state", json.encodeToString(mutableState.value)).commit())
      }
      initialized = true
    }
    return mutableState.asStateFlow()
  }

  @Synchronized
  fun update(
    context: Context,
    persist: Boolean = true,
    transform: (UpdateTaskState) -> UpdateTaskState,
  ) {
    state(context)
    val next = transform(mutableState.value)
    if (persist) check(storage(context).edit().putString("state", json.encodeToString(next)).commit())
    mutableState.value = next
  }

  fun automaticEnabled(context: Context): Boolean =
    BuildConfig.ENABLE_UPDATE_FEATURE &&
      context.getSharedPreferences("mpvrx_prefs", Context.MODE_PRIVATE).getBoolean(AUTO_INSTALL_KEY, false)

  fun schedule(context: Context) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return
    val workManager = WorkManager.getInstance(context)
    if (!automaticEnabled(context)) {
      workManager.cancelUniqueWork(CHECK_WORK)
      return
    }
    val request =
      PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
        .setConstraints(
          Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build(),
        ).build()
    workManager.enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  suspend fun setAutomatic(
    context: Context,
    enabled: Boolean,
  ) = withContext(Dispatchers.IO) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return@withContext
    context
      .getSharedPreferences(
        "mpvrx_prefs",
        Context.MODE_PRIVATE,
      ).edit()
      .putBoolean(AUTO_INSTALL_KEY, enabled)
      .commit()
    if (!enabled) cancelAutomatic(context)
    schedule(context)
  }

  suspend fun cancelAutomatic(context: Context) =
    enqueueMutex.withLock {
      val current = state(context).value
      if (current.automatic && current.stage != UpdateStage.INSTALLING && current.stage != UpdateStage.APPROVAL) {
        WorkManager
          .getInstance(context)
          .cancelUniqueWork(DOWNLOAD_WORK)
          .result
          .get()
        val cached = current.release?.let { UpdateManager(context).getApkFile(it) } != null
        update(context) {
          it.copy(
            automatic = false,
            stage = if (cached) UpdateStage.READY else UpdateStage.FAILED,
            downloadError = !cached,
          )
        }
      }
    }

  suspend fun cancelDownload(context: Context) =
    withContext(Dispatchers.IO) {
      WorkManager
        .getInstance(context)
        .cancelUniqueWork(DOWNLOAD_WORK)
        .result
        .get()
    }

  suspend fun enqueue(
    context: Context,
    release: Release,
    automatic: Boolean = false,
  ): Boolean =
    withContext(Dispatchers.IO) {
      if (!BuildConfig.ENABLE_UPDATE_FEATURE) return@withContext false
      enqueueMutex.withLock {
        if (automatic && !automaticEnabled(context)) return@withLock false
        val current = state(context).value
        if (current.stage == UpdateStage.INSTALLING || current.stage == UpdateStage.APPROVAL) return@withLock false
        val manager = WorkManager.getInstance(context)
        if (manager
            .getWorkInfosForUniqueWorkFlow(
              DOWNLOAD_WORK,
            ).first()
            .any { !it.state.isFinished }
        ) {
          return@withLock false
        }
        val request =
          OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(
              Constraints
                .Builder()
                .setRequiredNetworkType(if (automatic) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .setRequiresBatteryNotLow(automatic)
                .build(),
            ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Work Data is limited to 10 KB: keep release notes in our private record, not in Data.
        update(context) { UpdateTaskState(release, request.id.toString(), UpdateStage.QUEUED, automatic) }
        try {
          manager.enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.KEEP, request).result.get()
          true
        } catch (error: Exception) {
          update(context) { it.copy(stage = UpdateStage.FAILED, downloadError = true) }
          throw error
        }
      }
    }

  /** Restores interrupted work and observes cancellation from its notification. Run once per process. */
  suspend fun monitor(context: Context) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) return
    UpdateInstaller.reconcile(context)
    schedule(context)
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(DOWNLOAD_WORK).collect {
      enqueueMutex.withLock {
        val current = state(context).value
        if (current.stage != UpdateStage.QUEUED && current.stage != UpdateStage.DOWNLOADING) return@withLock
        // The flow may have queued a snapshot while enqueue held the lock. Query again before
        // declaring an interrupted enqueue; otherwise a brand-new download could appear failed.
        val info =
          WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWorkFlow(DOWNLOAD_WORK)
            .first()
            .firstOrNull { it.id.toString() == current.requestId }
        if (info == null || info.state == WorkInfo.State.CANCELLED || info.state == WorkInfo.State.FAILED) {
          val cached = current.release?.let { UpdateManager(context).getApkFile(it) } != null
          update(context) {
            if (it.requestId == current.requestId) {
              it.copy(
                stage =
                  if (cached) {
                    UpdateStage.READY
                  } else if (info?.state ==
                    WorkInfo.State.CANCELLED
                  ) {
                    UpdateStage.CANCELLED
                  } else {
                    UpdateStage.FAILED
                  },
                downloadError = !cached && info?.state != WorkInfo.State.CANCELLED,
              )
            } else {
              it
            }
          }
        } else if (info.state == WorkInfo.State.ENQUEUED) {
          update(context, persist = false) {
            it.copy(stage = UpdateStage.QUEUED, progress = info.progress.getFloat("progress", it.progress))
          }
        }
      }
    }
  }

  fun safeToInstallAutomatically(): Boolean = !App.isInForeground && PlaybackSession.state.value.currentItem == null

  private fun storage(context: Context) = context.getSharedPreferences("app_update_task", Context.MODE_PRIVATE)
}

class UpdateCheckWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val context = applicationContext
    if (!UpdateWork.automaticEnabled(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Result.success()
    if (UpdateWork.state(context).value.busy) return Result.success()
    return try {
      val release = UpdateManager(context).checkForUpdate()
      if (release != null && UpdateWork.automaticEnabled(context)) {
        UpdateWork.enqueue(context, release, automatic = true)
      }
      Result.success()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
  }
}

class UpdateDownloadWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result =
    withContext(Dispatchers.IO) {
      UpdateWork.downloadMutex.withLock {
        val context = applicationContext
        if (!BuildConfig.ENABLE_UPDATE_FEATURE) return@withContext Result.success()
        val initial = UpdateWork.state(context).value
        val release = initial.release ?: return@withContext Result.failure()
        if (initial.requestId != id.toString()) return@withContext Result.success()
        if (initial.stage in
          setOf(UpdateStage.INSTALLING, UpdateStage.APPROVAL, UpdateStage.COMPLETE)
        ) {
          return@withContext Result.success()
        }
        if (initial.automatic && !UpdateWork.automaticEnabled(context)) return@withContext Result.success()
        val manager = UpdateManager(context)
        try {
          if (manager.getApkFile(release) == null) {
            UpdateWork.update(context) { it.copy(stage = UpdateStage.DOWNLOADING, downloadError = false) }
            // Only explicit downloads promote to a foreground service. Scheduled downloads respect
            // background FGS restrictions and use ordinary WorkManager execution/constraints.
            if (!initial.automatic) {
              try {
                setForeground(UpdateNotifications.foreground(context, id, 0f))
              } catch (
                cancelled: CancellationException,
              ) {
                throw cancelled
              } catch (
                _: IllegalStateException,
              ) {
                // Resume as ordinary work if foreground promotion is restricted.
              } catch (
                _: SecurityException,
              ) {
                // The sheet remains usable when notification/FGS access is denied.
              }
            }
            var lastProgressTime = 0L
            manager.downloadUpdate(release).collect { progress ->
              UpdateWork.update(context, persist = false) { it.copy(progress = progress) }
              if (SystemClock.elapsedRealtime() - lastProgressTime >= 1000 || progress == 100f) {
                lastProgressTime = SystemClock.elapsedRealtime()
                setProgress(workDataOf("progress" to progress))
                UpdateNotifications.progress(context, id, progress)
              }
            }
          }
          currentCoroutineContext().ensureActive()
          UpdateWork.update(context) {
            if (it.requestId == id.toString() &&
              it.stage !in setOf(UpdateStage.INSTALLING, UpdateStage.APPROVAL, UpdateStage.COMPLETE)
            ) {
              it.copy(stage = UpdateStage.READY, progress = 100f)
            } else {
              it
            }
          }
          if (UpdateWork.state(context).value.stage != UpdateStage.READY) return@withContext Result.success()
          if (initial.automatic &&
            (!UpdateWork.safeToInstallAutomatically() || !UpdateWork.automaticEnabled(context))
          ) {
            UpdateNotifications.status(context, UpdateStage.READY)
            return@withContext if (UpdateWork.automaticEnabled(context)) Result.retry() else Result.success()
          }
          UpdateInstaller.install(context, release, automatic = initial.automatic)
          val afterInstall = UpdateWork.state(context).value
          if (initial.automatic &&
            afterInstall.automatic &&
            afterInstall.stage == UpdateStage.READY &&
            UpdateWork.automaticEnabled(context)
          ) {
            Result.retry()
          } else {
            Result.success()
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          if (runAttemptCount < 3) {
            UpdateWork.update(context) { it.copy(stage = UpdateStage.QUEUED) }
            Result.retry()
          } else {
            UpdateWork.update(context) { it.copy(stage = UpdateStage.FAILED, downloadError = true) }
            UpdateNotifications.status(context, UpdateStage.FAILED)
            Result.failure()
          }
        } finally {
          UpdateNotifications.cancelProgress(context)
        }
      }
    }
}
