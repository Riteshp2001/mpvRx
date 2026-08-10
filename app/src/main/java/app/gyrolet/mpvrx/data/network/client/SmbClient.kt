/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.client

import android.net.Uri
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbClient(
  private val connection: NetworkConnection,
) : NetworkClient {
  companion object {
    // 1 MiB for every transport buffer plus another 1 MiB stream buffer was excessive on phones,
    // especially when mpv opens multiple range requests while seeking. 512 KiB keeps SMBJ reads
    // comfortably large without reserving several megabytes per active connection.
    private const val SMB_TRANSPORT_BUFFER_SIZE = 512 * 1024
    private const val SMB_STREAM_BUFFER_SIZE = 256 * 1024

    private fun newClient(): SMBClient =
      SMBClient(
        SmbConfig
          .builder()
          .withTransportLayerFactory(AsyncDirectTcpTransportFactory())
          .withTimeout(60000, TimeUnit.MILLISECONDS)
          .withSoTimeout(60000, TimeUnit.MILLISECONDS)
          .withReadBufferSize(SMB_TRANSPORT_BUFFER_SIZE)
          .withWriteBufferSize(SMB_TRANSPORT_BUFFER_SIZE)
          .withTransactBufferSize(SMB_TRANSPORT_BUFFER_SIZE)
          .withDialects(
            com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1,
            com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
            com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0,
            com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1,
            com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2,
          ).withDfsEnabled(false)
          .withMultiProtocolNegotiate(true)
          .withSigningRequired(false)
          .withEncryptData(false)
          .build(),
      )
  }

  private var smbClient: SMBClient? = null
  private var smbConnection: Connection? = null
  private var session: Session? = null
  private var shareName: String = ""
  private val connectionMutex = Mutex()

  /**
   * Recursively traverses the exception chain to detect underlying network transport or timeout failures.
   */
  private fun isNetworkError(e: Throwable?): Boolean {
    var current = e
    while (current != null) {
      if (current is java.util.concurrent.TimeoutException ||
        current is com.hierynomus.protocol.transport.TransportException ||
        current is com.hierynomus.smbj.common.SMBRuntimeException ||
        current is java.net.SocketException ||
        current is java.net.SocketTimeoutException ||
        current is java.io.EOFException
      ) {
        return true
      }
      current = current.cause
    }
    return false
  }

  /**
   * Executes a network operation with a single-retry mechanism.
   * Prevents race conditions during concurrent reconnects via strict session reference tracking.
   */
  private suspend fun <T> executeWithRetry(block: suspend () -> T): T {
    val sessionAtStart = session

    return try {
      if (session == null) {
        throw java.net.SocketException("Session is null on execute")
      }
      block()
    } catch (e: Exception) {
      if (isNetworkError(e)) {
        android.util.Log.w("SmbClient", "SMB transport failed; reconnecting")

        connectionMutex.withLock {
          if (session === sessionAtStart) {
            disconnect()
            connect().getOrThrow()
          }
        }

        block()
      } else {
        throw e
      }
    }
  }

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      var candidateClient: SMBClient? = null
      var candidateConnection: Connection? = null
      var candidateSession: Session? = null
      try {
        val configuredShare = configuredShareName()

        if (configuredShare.isEmpty()) {
          return@withContext Result.failure(
            Exception(
              "SMB share name is required (for example, /Media).",
            ),
          )
        }

        // Reject paths with subfolders
        if (configuredShare.contains('/') || configuredShare.contains('\\')) {
          return@withContext Result.failure(
            Exception(
              "SMB path must contain only the share name, not a subfolder.",
            ),
          )
        }

        val newClient = newClient()
        candidateClient = newClient
        withTimeout(15_000) {
          val newConnection = newClient.connect(connection.host, connection.port)
          candidateConnection = newConnection
          val authContext =
            if (connection.isAnonymous) {
              AuthenticationContext.anonymous()
            } else {
              AuthenticationContext(connection.username, connection.password.toCharArray(), null)
            }
          val newSession = newConnection.authenticate(authContext)
          candidateSession = newSession
          val diskShare =
            newSession.connectShare(configuredShare) as? DiskShare
              ?: throw IOException("Configured SMB share is not a disk share")
          diskShare.use { it.list("") }
        }

        shareName = configuredShare
        smbClient = candidateClient
        smbConnection = candidateConnection
        session = candidateSession
        candidateClient = null
        candidateConnection = null
        candidateSession = null
        Result.success(Unit)
      } catch (timeout: TimeoutCancellationException) {
        closeResources(candidateSession, candidateConnection, candidateClient)
        Result.failure(IOException("SMB connection timed out"))
      } catch (cancellation: CancellationException) {
        closeResources(candidateSession, candidateConnection, candidateClient)
        throw cancellation
      } catch (error: Exception) {
        closeResources(candidateSession, candidateConnection, candidateClient)
        Result.failure(error)
      }
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      val oldSession = session
      val oldConnection = smbConnection
      val oldClient = smbClient
      session = null
      smbConnection = null
      smbClient = null
      shareName = ""
      closeResources(oldSession, oldConnection, oldClient)
    }
  }

  override fun isConnected(): Boolean = session != null && smbConnection != null

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      try {
        val result =
          executeWithRetry {
            val sess = session ?: throw java.net.SocketException("Not connected")
            val directory = parseNetworkPath(path)

            val diskShare =
              sess.connectShare(shareName) as? DiskShare
                ?: throw IOException("Configured SMB share is not a disk share")

            diskShare.use { ds ->
              val rawFiles: List<FileIdBothDirectoryInformation> =
                try {
                  withTimeout(15_000) { ds.list(directory.relative) }
                } catch (_: TimeoutCancellationException) {
                  throw IOException("SMB directory listing timed out")
                }

              rawFiles.mapNotNull { fileInfo ->
                val fileName = fileInfo.fileName
                if (fileName == "." || fileName == "..") return@mapNotNull null
                runCatching {
                  val isDirectory = fileInfo.fileAttributes and 0x10 != 0L
                  NetworkFile(
                    name = fileName,
                    path = directory.child(fileName).value,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else fileInfo.endOfFile,
                    lastModified = fileInfo.lastWriteTime.toEpochMillis(),
                    mimeType = if (!isDirectory) NetworkMimeTypes.forFileName(fileName) else null,
                  )
                }.getOrNull()
              }
            }
          }
        Result.success(result)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  override suspend fun getFileStream(
    path: String,
    offset: Long,
  ): Result<InputStream> =
    withContext(Dispatchers.IO) {
      require(offset >= 0L) { "Stream offset must not be negative" }
      try {
        val result =
          executeWithRetry {
            val sess = session ?: throw java.net.SocketException("Not connected")
            val relativePath = parseNetworkPath(path).relative

            val diskShare =
              sess.connectShare(shareName) as? DiskShare
                ?: throw IOException("Configured SMB share is not a disk share")

            try {
              val file =
                diskShare.openFile(
                  relativePath,
                  EnumSet.of(AccessMask.GENERIC_READ),
                  null,
                  EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                  SMB2CreateDisposition.FILE_OPEN,
                  null,
                )

              val inputStream =
                object : InputStream() {
                  private var currentPosition = offset
                  private var closed = false
                  private var scratch = ByteArray(0)
                  private val singleByte = ByteArray(1)

                  override fun read(): Int {
                    val read = read(singleByte, 0, 1)
                    return if (read == 1) singleByte[0].toInt() and 0xFF else -1
                  }

                  override fun read(b: ByteArray): Int = read(b, 0, b.size)

                  override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                  ): Int {
                    if (off < 0 || len < 0 || off > b.size || len > b.size - off) {
                      throw IndexOutOfBoundsException("offset=$off, length=$len, bufferSize=${b.size}")
                    }
                    if (closed) return -1
                    if (len == 0) return 0

                    try {
                      val readBuffer =
                        if (off == 0 && len == b.size) {
                          b
                        } else {
                          // SMBJ reads up to the supplied array size, so the scratch array must
                          // exactly match the caller's requested length.
                          if (scratch.size != len) scratch = ByteArray(len)
                          scratch
                        }
                      val bytesRead = file.read(readBuffer, currentPosition)
                      if (bytesRead <= 0) {
                        return -1
                      } else {
                        if (readBuffer !== b) {
                          System.arraycopy(readBuffer, 0, b, off, bytesRead)
                        }
                        currentPosition += bytesRead
                        return bytesRead
                      }
                    } catch (error: Exception) {
                      throw IOException("SMB stream read failed", error)
                    }
                  }

                  override fun available(): Int =
                    runCatching {
                      (file.fileInformation.standardInformation.endOfFile - currentPosition)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt()
                    }.getOrDefault(0)

                  override fun close() {
                    if (closed) return
                    closed = true
                    runCatching { file.close() }
                    runCatching { diskShare.close() }
                  }
                }

              BufferedInputStream(inputStream, SMB_STREAM_BUFFER_SIZE)
            } catch (e: Exception) {
              diskShare.close()
              throw IOException("Failed to open SMB file", e)
            }
          }
        Result.success(result)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  override suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      try {
        val result =
          executeWithRetry {
            val sess = session ?: throw java.net.SocketException("Not connected")
            val diskShare =
              sess.connectShare(shareName) as? DiskShare
                ?: throw IOException("Configured SMB share is not a disk share")

            try {
              val file =
                diskShare.openFile(
                  parseNetworkPath(path).relative,
                  EnumSet.of(AccessMask.GENERIC_READ),
                  null,
                  EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                  SMB2CreateDisposition.FILE_OPEN,
                  null,
                )
              file.use { it.fileInformation.standardInformation.endOfFile }
            } catch (e: Exception) {
              throw IOException("Failed to get SMB file size", e)
            } finally {
              runCatching { diskShare.close() }
            }
          }
        Result.success(result)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  /** Credential-free origin URI. Authenticated playback must use the loopback proxy. */
  override suspend fun getFileUri(path: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        val host = connection.host.trim().removePrefix("[").removeSuffix("]")
        val networkPath = parseNetworkPath(path)
        val uriPath = "/${configuredShareName()}${if (networkPath.isRoot) "" else networkPath.value}"
        val uri = URI("smb", null, host, connection.port, uriPath, null, null)
        Result.success(Uri.parse(uri.toASCIIString()))
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  private fun parseNetworkPath(path: String): NetworkPath {
    if (!path.startsWith("smb://", ignoreCase = true)) return NetworkPath.from(path)

    val afterAuthority = path.substring(6).substringAfter('/', missingDelimiterValue = "")
    val legacyShare = afterAuthority.substringBefore('/')
    val expectedShare = shareName.takeIf(String::isNotEmpty) ?: configuredShareName()
    require(legacyShare.equals(expectedShare, ignoreCase = true)) {
      "SMB path is outside the configured share"
    }
    return NetworkPath.from(afterAuthority.substringAfter('/', missingDelimiterValue = ""))
  }

  private fun configuredShareName(): String = connection.path.trim('/', '\\')

  private fun closeResources(
    smbSession: Session?,
    networkConnection: Connection?,
    client: SMBClient?,
  ) {
    runCatching { smbSession?.close() }
    runCatching { networkConnection?.close() }
    runCatching { client?.close() }
  }
}
