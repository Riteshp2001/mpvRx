/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.syncplay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

class SyncplayClient(
  private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
  private val socketFactory: () -> Socket = { Socket() },
) {
  private val json =
    Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

  private data class Connection(
    val socket: Socket,
    val reader: BufferedReader,
    val writer: BufferedWriter,
  )

  private val connectionLock = Any()
  private var generation = 0L
  private var pendingSocket: Socket? = null

  @Volatile private var connection: Connection? = null
  private val writeMutex = Mutex()

  // Deliver login/errors before a following EOF can cancel the session's collector.
  private val _messages = MutableSharedFlow<SyncplayMessage>()
  val messages: SharedFlow<SyncplayMessage> = _messages.asSharedFlow()

  suspend fun connect(
    host: String,
    port: Int,
  ): Result<Unit> {
    val attempt =
      synchronized(connectionLock) {
        disconnect()
        generation
      }
    try {
      return withContext(Dispatchers.IO) {
        val endpoint = SyncplayEndpoint.parse(host, port)
        val resolved = resolveSyncplayAddresses(endpoint.host, resolver)
        // Preserve the resolver's preferred family, but don't spend the whole deadline on it.
        val (preferred, alternate) = resolved.partition { (it is Inet4Address) == (resolved.first() is Inet4Address) }
        val addresses =
          buildList {
            for (index in 0 until maxOf(preferred.size, alternate.size)) {
              preferred.getOrNull(index)?.let(::add)
              alternate.getOrNull(index)?.let(::add)
            }
          }
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_MS * 1_000_000L
        var failure: IOException = SocketTimeoutException("Syncplay connection timed out")
        // InetSocketAddress(host, port) only tries one DNS answer. Try the other family too.
        for (address in addresses) {
          currentCoroutineContext().ensureActive()
          val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
          if (remaining <= 0) break
          val candidate = socketFactory()
          var accepted = false
          try {
            synchronized(connectionLock) {
              checkAttempt(attempt)
              pendingSocket = candidate
            }
            candidate.connect(InetSocketAddress(address, endpoint.port), minOf(remaining, ADDRESS_TIMEOUT_MS))
            candidate.keepAlive = true
            candidate.tcpNoDelay = true
            candidate.soTimeout = READ_TIMEOUT_MS
            val connected =
              Connection(
                candidate,
                BufferedReader(InputStreamReader(candidate.inputStream, StandardCharsets.UTF_8)),
                BufferedWriter(OutputStreamWriter(candidate.outputStream, StandardCharsets.UTF_8)),
              )
            currentCoroutineContext().ensureActive()
            synchronized(connectionLock) {
              checkAttempt(attempt)
              connection = connected
              pendingSocket = null
              accepted = true
            }
            return@withContext Result.success(Unit)
          } catch (error: IOException) {
            failure = error
          } finally {
            if (!accepted) {
              runCatching { candidate.close() }
              synchronized(connectionLock) { if (pendingSocket === candidate) pendingSocket = null }
            }
          }
        }
        Result.failure(failure)
      }
    } catch (cancelled: CancellationException) {
      synchronized(connectionLock) { if (generation == attempt) disconnect() }
      throw cancelled
    } catch (error: Exception) {
      synchronized(connectionLock) { if (generation == attempt) disconnect() }
      return Result.failure(error)
    }
  }

  suspend fun listen(): Result<Unit> =
    withContext(Dispatchers.IO) {
      val listening = connection ?: return@withContext Result.failure(EOFException())
      try {
        while (true) {
          currentCoroutineContext().ensureActive()
          val line = listening.reader.readProtocolLine() ?: throw EOFException("Syncplay server closed the connection")
          if (line.isBlank()) continue
          val message =
            try {
              json.decodeFromString<SyncplayMessage>(line)
            } catch (_: SerializationException) {
              // Do not log raw packets: Hello and room messages can contain private information.
              throw ProtocolException("Invalid Syncplay server response")
            }
          _messages.emit(message)
        }
        @Suppress("UNREACHABLE_CODE")
        Result.success(Unit)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Result.failure(error)
      } finally {
        closeConnection(listening)
      }
    }

  suspend fun sendMessage(message: SyncplayMessage): Boolean = sendRawMessage(json.encodeToString(message))

  suspend fun sendRawMessage(json: String): Boolean {
    val sending = connection ?: return false
    return withContext(Dispatchers.IO) {
      try {
        writeMutex.withLock {
          if (connection !== sending) return@withLock false
          sending.writer.write(json)
          sending.writer.write("\r\n")
          sending.writer.flush()
          true
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: IOException) {
        closeConnection(sending)
        false
      }
    }
  }

  fun disconnect() {
    synchronized(connectionLock) {
      generation++
      runCatching { pendingSocket?.close() }
      runCatching { connection?.socket?.close() }
      pendingSocket = null
      connection = null
    }
  }

  fun isConnected(): Boolean = connection?.socket?.let { it.isConnected && !it.isClosed } == true

  private fun checkAttempt(attempt: Long) {
    if (generation != attempt) throw CancellationException("Syncplay connection replaced")
  }

  private fun closeConnection(closing: Connection) {
    synchronized(connectionLock) {
      runCatching { closing.socket.close() }
      if (connection === closing) connection = null
    }
  }

  private fun BufferedReader.readProtocolLine(): String? {
    val line = StringBuilder()
    while (true) {
      val char = read()
      if (char == -1) {
        if (line.isNotEmpty()) throw ProtocolException("Incomplete Syncplay server response")
        return null
      }
      if (char == '\n'.code) return line.toString().removeSuffix("\r")
      if (line.length >= MAX_LINE_LENGTH) throw ProtocolException("Syncplay server response is too large")
      line.append(char.toChar())
    }
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 10_000
    const val ADDRESS_TIMEOUT_MS = 4_000
    const val READ_TIMEOUT_MS = 30_000
    const val MAX_LINE_LENGTH = 65_536
  }
}
