package app.gyrolet.mpvrx.ui.player

import android.os.SystemClock
import android.util.Log
import `is`.xyz.mpv.MPVLib
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Tracks ownership of libmpv's process-global core and serializes native destruction away from
 * Android's UI thread.
 *
 * The Activity normally owns the core. Ownership moves to the playback service only when the
 * Activity is actually destroyed for detached background playback. This distinction prevents a
 * foreground service stop from destroying a core that a still-visible Activity is using.
 */
internal object MpvTeardownCoordinator {
  private const val TAG = "MpvTeardown"
  private const val DESTROY_DRAIN_DELAY_MS = 200L

  private enum class CoreOwner {
    NONE,
    ACTIVITY,
    DETACHED_SERVICE,
    TEARING_DOWN,
  }

  private val lock = Any()
  private val executor =
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "mpvrx-mpv-teardown").apply { isDaemon = true }
    }

  @Volatile
  private var pendingTeardown: Future<*>? = null

  private var coreOwner = CoreOwner.NONE

  fun markActivityCoreInitialized() {
    synchronized(lock) {
      check(coreOwner == CoreOwner.NONE) {
        "Cannot register a new MPV core while $coreOwner still owns the previous one"
      }
      coreOwner = CoreOwner.ACTIVITY
    }
  }

  /** Called only after the Activity has removed its observers and committed to destruction. */
  fun handoffToDetachedService(): Boolean =
    synchronized(lock) {
      if (coreOwner != CoreOwner.ACTIVITY) {
        Log.w(TAG, "Cannot hand off MPV core owned by $coreOwner")
        false
      } else {
        coreOwner = CoreOwner.DETACHED_SERVICE
        true
      }
    }

  fun destroyActivityCoreAsync(reason: String): Boolean =
    destroyAsyncIf(reason) { owner -> owner == CoreOwner.ACTIVITY }

  fun destroyDetachedCoreAsync(reason: String): Boolean =
    destroyAsyncIf(reason) { owner -> owner == CoreOwner.DETACHED_SERVICE }

  /** Fresh Activity startup may reclaim any tracked core whose previous Android owner vanished. */
  fun destroyAnyCoreAsync(reason: String): Boolean =
    destroyAsyncIf(reason) { owner ->
      owner == CoreOwner.ACTIVITY || owner == CoreOwner.DETACHED_SERVICE
    }

  private fun destroyAsyncIf(
    reason: String,
    canDestroy: (CoreOwner) -> Boolean,
  ): Boolean =
    synchronized(lock) {
      if (!canDestroy(coreOwner)) return@synchronized false

      coreOwner = CoreOwner.TEARING_DOWN
      pendingTeardown =
        executor.submit {
          Log.d(TAG, "Starting native teardown: $reason")
          try {
            destroyNativeCore(reason)
          } catch (error: Throwable) {
            Log.e(TAG, "Native teardown failed: $reason", error)
          } finally {
            synchronized(lock) {
              coreOwner = CoreOwner.NONE
            }
            Log.d(TAG, "Finished native teardown: $reason")
          }
        }
      true
    }

  private fun destroyNativeCore(reason: String) {
    runCatching { MPVLib.setPropertyBoolean("pause", true) }
      .onFailure { error -> Log.e(TAG, "Error pausing MPV during $reason", error) }
    runCatching { MPVLib.setPropertyString("vo", "null") }
      .onFailure { error -> Log.e(TAG, "Error disabling video output during $reason", error) }
    runCatching { MPVLib.detachSurface() }
      .onFailure { error -> Log.e(TAG, "Error detaching MPV surface during $reason", error) }
    runCatching { MPVLib.command("quit") }
      .onFailure { error -> Log.e(TAG, "Error quitting MPV during $reason", error) }

    // mpv's quit command is asynchronous. Keep its short drain off the main thread so Android
    // can finish Window/HWUI teardown before the renderer's native mutexes are destroyed.
    SystemClock.sleep(DESTROY_DRAIN_DELAY_MS)
    MPVLib.destroy()
  }

  /** Prevents a new player from initializing while the previous global core is still closing. */
  fun awaitIdle(timeoutMs: Long): Boolean {
    val teardown = synchronized(lock) { pendingTeardown } ?: return true
    return try {
      teardown.get(timeoutMs, TimeUnit.MILLISECONDS)
      true
    } catch (_: TimeoutException) {
      Log.e(TAG, "Native teardown did not finish within ${timeoutMs}ms")
      false
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      Log.e(TAG, "Interrupted while waiting for native teardown")
      false
    } catch (error: ExecutionException) {
      // destroyAsync contains operation failures, but guard against executor-level failure too.
      Log.e(TAG, "Unable to await native teardown", error.cause ?: error)
      false
    }
  }
}
