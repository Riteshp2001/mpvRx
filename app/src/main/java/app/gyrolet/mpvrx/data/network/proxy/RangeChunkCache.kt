/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.proxy

import app.gyrolet.mpvrx.domain.network.NetworkPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded, progressively filled read cache for open-ended range requests.
 *
 * Some stores spend most of a second on every random range read. Players seek between distant
 * video and audio regions of non-interleaved media many times per second, so a chunk is filled in
 * the background while readers consume the bytes already available; after the first fill those
 * repeated requests never touch the slow store again.
 */
internal class RangeChunkCache(
  private val maxResidentBytes: Long,
  private val fillConcurrency: Int = 2,
) {
  class Chunk internal constructor(
    val start: Long,
    val capacity: Int,
  ) {
    val bytes = ByteArray(capacity)
    private val lock = ReentrantLock()
    private val progress = lock.newCondition()
    private var filled = 0
    private var complete = false
    private var failure: Throwable? = null

    val isComplete: Boolean
      get() = lock.withLock { complete }

    val availableBytes: Int
      get() = lock.withLock { filled }

    /** Blocks until at least [minBytes] are resident, the fill ends, or the deadline passes. */
    fun awaitAvailable(
      minBytes: Int,
      deadlineNanos: Long,
    ): Int {
      lock.withLock {
        while (true) {
          failure?.let { error -> throw (error as? IOException) ?: IOException("Chunk fill failed", error) }
          if (filled >= minBytes || complete) return filled
          val remaining = deadlineNanos - System.nanoTime()
          if (remaining <= 0L) throw IOException("Timed out waiting for a cached chunk")
          progress.awaitNanos(remaining)
        }
      }
    }

    fun copyTo(
      sourceOffset: Int,
      destination: ByteArray,
      destinationOffset: Int,
      length: Int,
    ): Int =
      lock.withLock {
        val count = minOf(length, (filled - sourceOffset).coerceAtLeast(0))
        if (count > 0) System.arraycopy(bytes, sourceOffset, destination, destinationOffset, count)
        count
      }

    internal fun append(
      data: ByteArray,
      offset: Int,
      length: Int,
    ) = lock.withLock {
      System.arraycopy(data, offset, bytes, filled, length)
      filled += length
      progress.signalAll()
    }

    internal fun finish() =
      lock.withLock {
        complete = true
        progress.signalAll()
      }

    internal fun fail(error: Throwable) =
      lock.withLock {
        if (failure == null) failure = error
        complete = true
        progress.signalAll()
      }
  }

  /** Tracks the upstream stream a fill is reading so [dispose] can interrupt it. */
  class FillContext internal constructor() {
    private val lock = Any()
    private var cancelled = false
    private var stream: InputStream? = null

    /** Returns false when the fill was already cancelled; the caller must close [stream] itself. */
    fun attach(stream: InputStream): Boolean {
      val closeNow =
        synchronized(lock) {
          if (cancelled) {
            true
          } else {
            this.stream = stream
            false
          }
        }
      if (closeNow) runCatching { stream.close() }
      return !closeNow
    }

    fun detach(stream: InputStream) {
      synchronized(lock) {
        if (this.stream === stream) this.stream = null
      }
    }

    fun throwIfCancelled() {
      if (synchronized(lock) { cancelled }) throw IOException("Chunk fill cancelled")
    }

    fun cancel() {
      val active =
        synchronized(lock) {
          cancelled = true
          stream.also { stream = null }
        }
      active?.let { runCatching { it.close() } }
    }
  }

  private data class Key(
    val path: NetworkPath,
    val index: Long,
  )

  private val lock = Any()
  private val resident = LinkedHashMap<Key, Chunk>(16, 0.75f, true)
  private var residentBytes = 0L
  private var disposed = false
  private val activeFills = HashMap<Chunk, FillContext>()
  private val fillGate = Semaphore(fillConcurrency)

  fun get(
    path: NetworkPath,
    index: Long,
  ): Chunk? = synchronized(lock) { resident[Key(path, index)] }

  /**
   * Returns the chunk for [index], scheduling a background fill when absent. Callers may read
   * bytes as they arrive through [Chunk.awaitAvailable]; they never wait for the whole chunk.
   */
  fun obtain(
    path: NetworkPath,
    index: Long,
    start: Long,
    capacity: Int,
    scope: CoroutineScope,
    load: suspend (Chunk, FillContext) -> Unit,
  ): Chunk {
    val key = Key(path, index)
    val created: Chunk
    synchronized(lock) {
      if (disposed) throw IOException("Chunk cache disposed")
      resident[key]?.let { return it }
      created = Chunk(start, capacity)
      resident[key] = created
      residentBytes += capacity
      evictLocked(key)
    }
    scope.launch {
      if (!fillGate.tryAcquire(FILL_PERMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        created.fail(IOException("Chunk cache is busy"))
        return@launch
      }
      val context = FillContext()
      val disposedNow =
        synchronized(lock) {
          if (disposed) {
            true
          } else {
            activeFills[created] = context
            false
          }
        }
      if (disposedNow) {
        fillGate.release()
        created.fail(IOException("Chunk cache disposed"))
        return@launch
      }
      try {
        load(created, context)
        created.finish()
      } catch (cancellation: CancellationException) {
        created.fail(cancellation)
        throw cancellation
      } catch (error: Throwable) {
        created.fail(error)
      } finally {
        synchronized(lock) { activeFills.remove(created) }
        fillGate.release()
      }
    }
    return created
  }

  /** Prefetches only when no foreground fill is running, so it cannot starve the player. */
  fun prefetch(
    scope: CoroutineScope,
    path: NetworkPath,
    index: Long,
    start: Long,
    capacity: Int,
    load: suspend (Chunk, FillContext) -> Unit,
  ) {
    synchronized(lock) {
      if (disposed || resident.containsKey(Key(path, index))) return
    }
    if (fillGate.availablePermits() < fillConcurrency) return
    runCatching { obtain(path, index, start, capacity, scope, load) }
  }

  fun dispose() {
    val contexts: List<FillContext>
    synchronized(lock) {
      if (disposed) return
      disposed = true
      resident.values.forEach { it.fail(IOException("Chunk cache disposed")) }
      resident.clear()
      residentBytes = 0L
      contexts = activeFills.values.toList()
      activeFills.clear()
    }
    contexts.forEach { it.cancel() }
  }

  /** Drops every in-progress fill when a newer seek supersedes them. Completed chunks stay. */
  fun cancelInProgressFills() {
    val contexts = mutableListOf<FillContext>()
    synchronized(lock) {
      if (disposed) return
      val iterator = resident.entries.iterator()
      while (iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.value.isComplete) continue
        iterator.remove()
        residentBytes -= entry.value.capacity
        entry.value.fail(IOException("Chunk fill superseded"))
        activeFills.remove(entry.value)?.let(contexts::add)
      }
    }
    contexts.forEach { it.cancel() }
  }

  private fun evictLocked(pinned: Key) {
    val iterator = resident.entries.iterator()
    while (residentBytes > maxResidentBytes && resident.size > 1 && iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.key == pinned || !entry.value.isComplete) continue
      iterator.remove()
      residentBytes -= entry.value.capacity
    }
  }

  private companion object {
    const val FILL_PERMIT_TIMEOUT_MS = 15_000L
  }
}
