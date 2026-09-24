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
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.URI

class SftpClient(
  private val connection: NetworkConnection,
) : NetworkClient {
  companion object {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val SERVER_ALIVE_INTERVAL_MS = 15_000
  }

  @Volatile
  private var session: Session? = null

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val host = connection.host.trim().removePrefix("[").removeSuffix("]")
        val username = if (connection.isAnonymous) "anonymous" else connection.username
        val candidate = JSch().getSession(username, host, connection.port)
        candidate.setConfig("StrictHostKeyChecking", "no")
        // Skipping GSSAPI avoids a long Kerberos negotiation stall against plain SSH servers.
        candidate.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        val password = if (connection.isAnonymous) "" else connection.password
        candidate.setPassword(password.toByteArray(Charsets.UTF_8))
        candidate.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
        candidate.connect(CONNECT_TIMEOUT_MS)

        try {
          withChannel(candidate) { channel ->
            val attrs = channel.stat(remotePath(NetworkPath.from("")))
            if (!attrs.isDir) throw IOException("SFTP base path is not a directory")
          }
        } catch (error: Exception) {
          runCatching { candidate.disconnect() }
          throw error
        }

        session?.let { previous -> runCatching { previous.disconnect() } }
        session = candidate
        Result.success(Unit)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      val active = session
      session = null
      runCatching { active?.disconnect() }
    }
  }

  override fun isConnected(): Boolean = session?.isConnected == true

  override suspend fun listFiles(path: String, onSnapshot: (suspend (List<NetworkFile>) -> Unit)?): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      try {
        val activeSession = requireSession()
        val directory = NetworkPath.from(path)
        val files =
          withChannel(activeSession) { channel ->
            val partial = mutableListOf<NetworkFile>()
            val publisher = app.gyrolet.mpvrx.utils.media.ProgressiveResultsPublisher(onSnapshot) { partial }
            val files = channel
              .ls(remotePath(directory))
              .filterIsInstance<ChannelSftp.LsEntry>()
              .filter { it.filename != "." && it.filename != ".." }
              .mapNotNull { entry ->
                toNetworkFile(channel, directory, entry)?.also { file ->
                  partial += file
                  publisher.publishIfNeeded()
                }
              }
            publisher.publishIfNeeded(force = true)
            files
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
        val activeSession = requireSession()
        val size =
          withChannel(activeSession) { channel ->
            val attrs = channel.stat(remotePath(NetworkPath.from(path)))
            if (attrs.isDir) throw IOException("File not found or is a directory")
            attrs.size
          }
        Result.success(size)
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
      var channelToClose: ChannelSftp? = null
      try {
        val activeSession = requireSession()
        // Concurrent range streams must not share one channel: ChannelSftp serializes its
        // request pipeline, so each stream owns a channel that lives until the stream closes.
        val channel = activeSession.openChannel("sftp") as ChannelSftp
        channelToClose = channel
        channel.connect(CONNECT_TIMEOUT_MS)
        val rawStream = channel.get(remotePath(NetworkPath.from(path)), null, offset)
        channelToClose = null

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
              runCatching { channel.disconnect() }
            }
          },
        )
      } catch (cancellation: CancellationException) {
        runCatching { channelToClose?.disconnect() }
        throw cancellation
      } catch (error: Exception) {
        runCatching { channelToClose?.disconnect() }
        Result.failure(error)
      }
    }

  /** Credential-free origin URI. Authenticated playback must use the loopback proxy. */
  override suspend fun getFileUri(path: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        val host = connection.host.trim().removePrefix("[").removeSuffix("]")
        val uri = URI("sftp", null, host, connection.port, remotePath(NetworkPath.from(path)), null, null)
        Result.success(Uri.parse(uri.toASCIIString()))
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  private fun requireSession(): Session =
    session?.takeIf { it.isConnected } ?: throw IOException("Not connected")

  private inline fun <T> withChannel(
    session: Session,
    block: (ChannelSftp) -> T,
  ): T {
    val channel = session.openChannel("sftp") as ChannelSftp
    channel.connect(CONNECT_TIMEOUT_MS)
    try {
      return block(channel)
    } finally {
      runCatching { channel.disconnect() }
    }
  }

  private fun toNetworkFile(
    channel: ChannelSftp,
    directory: NetworkPath,
    entry: ChannelSftp.LsEntry,
  ): NetworkFile? =
    runCatching {
      val childPath = directory.child(entry.filename)
      // Resolve symlinks so linked directories browse as directories.
      val attrs =
        if (entry.attrs.isLink) {
          runCatching { channel.stat(remotePath(childPath)) }.getOrNull() ?: entry.attrs
        } else {
          entry.attrs
        }
      NetworkFile(
        name = entry.filename,
        path = childPath.value,
        isDirectory = attrs.isDir,
        size = attrs.size,
        lastModified = attrs.mTime * 1000L,
        mimeType = if (!attrs.isDir) NetworkMimeTypes.forFileName(entry.filename) else null,
      )
    }.getOrNull()

  private fun remotePath(path: NetworkPath): String {
    val segments = NetworkPath.from(connection.path).segments + path.segments
    return if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}"
  }
}
