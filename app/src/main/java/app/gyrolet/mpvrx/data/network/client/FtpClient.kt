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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.time.Duration

class FtpClient(
  private val connection: NetworkConnection,
) : NetworkClient {
  private var ftpClient: FTPClient? = null

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      var candidateToClose: FTPClient? = null
      try {
        val candidate = newClient(streaming = false)
        candidateToClose = candidate
        candidate.connect(connection.host, connection.port)
        ensurePositiveReply(candidate, "FTP server refused connection")

        if (!login(candidate)) throw IOException("FTP login failed")
        if (!candidate.setFileType(FTP.BINARY_FILE_TYPE)) throw IOException("FTP binary mode was rejected")
        candidate.enterLocalPassiveMode()
        runCatching { candidate.sendCommand("OPTS UTF8 ON") }

        val root = remotePath(NetworkPath.ROOT)
        if (root != "/" && !candidate.changeWorkingDirectory(root)) {
          throw IOException("FTP base path is unavailable (reply ${candidate.replyCode})")
        }

        ftpClient = candidate
        candidateToClose = null
        Result.success(Unit)
      } catch (cancellation: CancellationException) {
        closeClient(candidateToClose)
        throw cancellation
      } catch (error: Exception) {
        closeClient(candidateToClose)
        Result.failure(error)
      }
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      val client = ftpClient
      ftpClient = null
      closeClient(client)
    }
  }

  override fun isConnected(): Boolean = ftpClient?.isConnected == true

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      try {
        var client = ftpClient ?: return@withContext Result.failure(IOException("Not connected"))
        if (!client.isConnected || !runCatching { client.sendNoOp() }.getOrDefault(false)) {
          disconnect()
          connect().getOrThrow()
          client = ftpClient ?: throw IOException("FTP reconnect did not create a client")
        }

        val directory = NetworkPath.from(path)
        val rawFiles = client.listFiles(remotePath(directory))
        ensurePositiveReply(client, "FTP directory listing failed")
        val files =
          rawFiles.mapNotNull { file ->
            val name = file.name.takeUnless { it == "." || it == ".." } ?: return@mapNotNull null
            runCatching {
              val filePath = directory.child(name)
              NetworkFile(
                name = name,
                path = filePath.value,
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.size,
                lastModified = file.timestamp?.timeInMillis ?: 0,
                mimeType = if (!file.isDirectory) NetworkMimeTypes.forFileName(name) else null,
              )
            }.getOrNull()
          }
        Result.success(files)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  override suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      try {
        val client = ftpClient ?: return@withContext Result.failure(IOException("Not connected"))
        val wirePath = remotePath(NetworkPath.from(path))
        val file = client.mlistFile(wirePath) ?: client.listFiles(wirePath).firstOrNull()
        if (file == null || file.isDirectory || file.size < 0L) {
          Result.failure(IOException("FTP file size is unavailable"))
        } else {
          Result.success(file.size)
        }
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
      var clientToClose: FTPClient? = null
      try {
        val streamClient = newClient(streaming = true)
        clientToClose = streamClient
        streamClient.connect(connection.host, connection.port)
        ensurePositiveReply(streamClient, "FTP server refused connection")
        if (!login(streamClient)) throw IOException("FTP login failed")
        if (!streamClient.setFileType(FTP.BINARY_FILE_TYPE)) throw IOException("FTP binary mode was rejected")
        streamClient.enterLocalPassiveMode()
        runCatching { streamClient.sendCommand("OPTS UTF8 ON") }
        if (offset > 0L) streamClient.setRestartOffset(offset)

        val rawStream =
          streamClient.retrieveFileStream(remotePath(NetworkPath.from(path)))
            ?: throw IOException("FTP server rejected the file request (reply ${streamClient.replyCode})")
        val ownedClient = streamClient
        clientToClose = null

        Result.success(
          object : InputStream() {
            private var closed = false

            override fun read(): Int = rawStream.read()

            override fun read(b: ByteArray): Int = rawStream.read(b)

            override fun read(
              b: ByteArray,
              off: Int,
              len: Int,
            ): Int = rawStream.read(b, off, len)

            override fun available(): Int = rawStream.available()

            override fun close() {
              if (closed) return
              closed = true
              runCatching { rawStream.close() }
              runCatching { ownedClient.completePendingCommand() }
              closeClient(ownedClient)
            }
          },
        )
      } catch (cancellation: CancellationException) {
        closeClient(clientToClose)
        throw cancellation
      } catch (error: Exception) {
        closeClient(clientToClose)
        Result.failure(error)
      }
    }

  /** Credential-free origin URI. Authenticated playback must use the loopback proxy. */
  override suspend fun getFileUri(path: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        val host = connection.host.trim().removePrefix("[").removeSuffix("]")
        val uri = URI("ftp", null, host, connection.port, remotePath(NetworkPath.from(path)), null, null)
        Result.success(Uri.parse(uri.toASCIIString()))
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  private fun remotePath(path: NetworkPath): String {
    val segments = NetworkPath.from(connection.path).segments + path.segments
    return if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}"
  }

  private fun newClient(streaming: Boolean): FTPClient =
    FTPClient().apply {
      controlEncoding = "UTF-8"
      setConnectTimeout(15_000)
      setDataTimeout(if (streaming) Duration.ofMinutes(2) else Duration.ofSeconds(60))
      setDefaultTimeout(if (streaming) 120_000 else 60_000)
      setControlKeepAliveTimeout(if (streaming) Duration.ofMinutes(10) else Duration.ofMinutes(5))
      setControlKeepAliveReplyTimeout(if (streaming) Duration.ofSeconds(15) else Duration.ofSeconds(10))
      bufferSize = 64 * 1024
    }

  private fun login(client: FTPClient): Boolean =
    if (connection.isAnonymous) {
      client.login("anonymous", "")
    } else {
      client.login(connection.username, connection.password)
    }

  private fun ensurePositiveReply(
    client: FTPClient,
    message: String,
  ) {
    if (!FTPReply.isPositiveCompletion(client.replyCode)) {
      throw IOException("$message (reply ${client.replyCode})")
    }
  }

  private fun closeClient(client: FTPClient?) {
    if (client == null) return
    runCatching { if (client.isConnected) client.logout() }
    runCatching { if (client.isConnected) client.disconnect() }
  }
}
