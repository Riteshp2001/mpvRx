package app.gyrolet.mpvrx.ui.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class MpvSessionState {
  IDLE,
  INITIALIZING,
  READY,
  LOADING,
  PLAYING,
  PAUSED,
  STOPPING,
  RELEASED,
  FAILED,
}

/**
 * Owns the process-global libmpv instance. Native work is never executed by a lifecycle callback.
 */
internal object MpvSessionCoordinator {
  private data class Session(
    val id: Long,
    val activityId: Long,
    val state: AtomicReference<MpvSessionState> = AtomicReference(MpvSessionState.IDLE),
    val closeStarted: AtomicBoolean = AtomicBoolean(false),
  )

  private val nextSessionId = AtomicLong()
  private val activeSessionId = AtomicLong()
  private val sessions = ConcurrentHashMap<Long, Session>()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val dispatcher =
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "mpvrx-player").apply { isDaemon = true }
    }.asCoroutineDispatcher()
  private val playerScope = CoroutineScope(SupervisorJob() + dispatcher)
  private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  fun begin(activityId: Long): Long {
    val session = Session(nextSessionId.incrementAndGet(), activityId)
    sessions[session.id] = session
    val previous = activeSessionId.getAndSet(session.id)
    if (previous != 0L && sessions[previous]?.state?.get() !in setOf(
        MpvSessionState.RELEASED,
        MpvSessionState.FAILED,
      )
    ) {
      Log.w(TAG, "Replacing active session=$previous with session=${session.id}; old callbacks are now stale")
    }
    transition(session, MpvSessionState.INITIALIZING, "begin")
    return session.id
  }

  fun isCurrent(sessionId: Long): Boolean =
    sessionId != 0L && activeSessionId.get() == sessionId

  fun acceptsCallbacks(sessionId: Long): Boolean {
    if (!isCurrent(sessionId)) return false
    return when (sessions[sessionId]?.state?.get()) {
      MpvSessionState.READY,
      MpvSessionState.LOADING,
      MpvSessionState.PLAYING,
      MpvSessionState.PAUSED,
        -> true
      else -> false
    }
  }

  fun state(sessionId: Long): MpvSessionState =
    sessions[sessionId]?.state?.get() ?: MpvSessionState.RELEASED

  fun initialize(
    sessionId: Long,
    operation: () -> Unit,
    onReady: () -> Unit,
    onFailure: (Throwable) -> Unit,
  ): Job? {
    val session = sessions[sessionId] ?: return null
    return launchTimed(session, "initialize", INITIALIZE_TIMEOUT_MS) {
      if (!isCurrent(sessionId) || session.state.get() != MpvSessionState.INITIALIZING) return@launchTimed
      runCatching(operation)
        .onSuccess {
          if (isCurrent(sessionId) && transition(session, MpvSessionState.READY, "initialized")) {
            mainHandler.post(onReady)
          }
        }.onFailure { error ->
          transition(session, MpvSessionState.FAILED, "initialize_failed")
          if (isCurrent(sessionId)) mainHandler.post { onFailure(error) }
        }
    }
  }

  fun execute(
    sessionId: Long,
    name: String,
    timeoutMs: Long = COMMAND_TIMEOUT_MS,
    drainDuringStop: Boolean = false,
    operation: () -> Unit,
  ): Job? {
    val session = sessions[sessionId] ?: return null
    if (!canQueueCommands(session)) {
      Log.w(TAG, "Rejecting command=$name session=$sessionId state=${session.state.get()}")
      return null
    }
    return launchTimed(session, name, timeoutMs) {
      val canDrain = drainDuringStop && session.state.get() == MpvSessionState.STOPPING
      if ((!isCurrent(sessionId) || !acceptsCommands(session)) && !canDrain) return@launchTimed
      runCatching(operation).onFailure { error ->
        Log.e(TAG, context(session, "command_failed", name), error)
      }
    }
  }

  suspend fun <T> query(sessionId: Long, name: String, operation: () -> T): T? {
    val session = sessions[sessionId] ?: return null
    return withContext(dispatcher) {
      if (!isCurrent(sessionId) || !acceptsCommands(session)) return@withContext null
      val started = SystemClock.elapsedRealtime()
      runCatching(operation)
        .onFailure { error -> Log.e(TAG, context(session, "query_failed", name), error) }
        .onSuccess {
          val elapsed = SystemClock.elapsedRealtime() - started
          if (elapsed >= SLOW_QUERY_MS) {
            Log.w(TAG, "${context(session, "slow_query", name)} elapsedMs=$elapsed")
          }
        }.getOrNull()
    }
  }

  fun markLoading(sessionId: Long) {
    sessions[sessionId]?.let { session ->
      if (isCurrent(sessionId)) transition(session, MpvSessionState.LOADING, "load_requested")
    }
  }

  fun markPlaying(sessionId: Long, paused: Boolean) {
    sessions[sessionId]?.let { session ->
      if (isCurrent(sessionId)) {
        transition(session, if (paused) MpvSessionState.PAUSED else MpvSessionState.PLAYING, "pause=$paused")
      }
    }
  }

  fun closeAsync(
    sessionId: Long,
    reason: String,
    keepNativeSession: Boolean,
    operation: () -> Unit,
    onClosed: () -> Unit = {},
  ): Job? {
    val session = sessions[sessionId] ?: return null
    if (!session.closeStarted.compareAndSet(false, true)) {
      Log.d(TAG, context(session, "close_ignored", reason))
      return null
    }
    transition(session, MpvSessionState.STOPPING, reason)
    if (activeSessionId.compareAndSet(sessionId, 0L)) {
      Log.d(TAG, context(session, "callbacks_invalidated", reason))
    }
    return launchTimed(session, "teardown:$reason", TEARDOWN_TIMEOUT_MS) {
      runCatching(operation).onFailure { error ->
        Log.e(TAG, context(session, "teardown_failed", reason), error)
      }
      transition(session, MpvSessionState.RELEASED, if (keepNativeSession) "detached" else reason)
      sessions.remove(sessionId)
      mainHandler.post(onClosed)
    }
  }

  /** Queues cleanup of a service-owned core before a fresh session initializes. */
  fun releaseDetachedSession(operation: () -> Unit): Job =
    playerScope.launch {
      val started = SystemClock.elapsedRealtime()
      val pending = AtomicBoolean(true)
      watchOperation("detached_teardown", 0L, TEARDOWN_TIMEOUT_MS, pending)
      try {
        operation()
      } catch (error: Throwable) {
        Log.e(TAG, "phase=detached_teardown thread=${Thread.currentThread().name}", error)
      } finally {
        pending.set(false)
        Log.i(TAG, "phase=detached_teardown_complete elapsedMs=${SystemClock.elapsedRealtime() - started}")
      }
    }

  private fun acceptsCommands(session: Session): Boolean =
    when (session.state.get()) {
      MpvSessionState.READY,
      MpvSessionState.LOADING,
      MpvSessionState.PLAYING,
      MpvSessionState.PAUSED,
        -> true
      else -> false
    }

  private fun canQueueCommands(session: Session): Boolean =
    session.state.get() == MpvSessionState.INITIALIZING || acceptsCommands(session)

  private fun launchTimed(
    session: Session,
    name: String,
    timeoutMs: Long,
    operation: () -> Unit,
  ): Job =
    playerScope.launch {
      val started = SystemClock.elapsedRealtime()
      val pending = AtomicBoolean(true)
      Log.d(TAG, context(session, "start", name))
      watchOperation(name, session.id, timeoutMs, pending)
      try {
        operation()
      } finally {
        pending.set(false)
        Log.d(TAG, "${context(session, "complete", name)} elapsedMs=${SystemClock.elapsedRealtime() - started}")
      }
    }

  private fun watchOperation(name: String, sessionId: Long, timeoutMs: Long, pending: AtomicBoolean) {
    watchdogScope.launch {
      delay(timeoutMs)
      if (pending.get()) {
        Log.e(
          TAG,
          "watchdog=player_operation_timeout session=$sessionId operation=$name timeoutMs=$timeoutMs " +
            "${PlayerDiagnostics.threadSummary()}",
        )
      }
    }
  }

  private fun transition(session: Session, target: MpvSessionState, reason: String): Boolean {
    val previous = session.state.getAndSet(target)
    if (previous == target) return true
    Log.i(TAG, "session=${session.id} activity=${session.activityId} state=$previous->$target reason=$reason thread=${Thread.currentThread().name}")
    return true
  }

  private fun context(session: Session, phase: String, operation: String): String =
    "session=${session.id} activity=${session.activityId} state=${session.state.get()} phase=$phase operation=$operation thread=${Thread.currentThread().name}"

  private const val TAG = "MpvSession"
  private const val COMMAND_TIMEOUT_MS = 5_000L
  private const val INITIALIZE_TIMEOUT_MS = 15_000L
  private const val TEARDOWN_TIMEOUT_MS = 5_000L
  private const val SLOW_QUERY_MS = 250L
}
