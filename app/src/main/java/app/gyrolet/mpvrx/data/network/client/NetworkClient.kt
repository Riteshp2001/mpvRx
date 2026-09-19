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
import app.gyrolet.mpvrx.domain.network.NetworkFile
import java.io.InputStream

/**
 * Common interface for all network protocol clients
 */
interface NetworkClient {
  /**
   * Connect to the server
   */
  suspend fun connect(): Result<Unit>

  /**
   * Disconnect from the server
   */
  suspend fun disconnect()

  /**
   * Check if currently connected
   */
  fun isConnected(): Boolean

  /**
   * List files and directories at the given path
   */
  suspend fun listFiles(path: String): Result<List<NetworkFile>>

  /**
   * Get input stream for a file
   */
  suspend fun getFileStream(
    path: String,
    offset: Long = 0L,
  ): Result<InputStream>

  /**
   * Get file size when the protocol can expose it cheaply.
   */
  suspend fun getFileSize(path: String): Result<Long> =
    Result.failure(UnsupportedOperationException("File size is not supported by this client"))

  /**
   * Get a credential-free origin URI for diagnostics and anonymous sources.
   *
   * Authenticated playback must use [app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy]
   * so secrets never enter intents, playlists, history, or logs.
   */
  suspend fun getFileUri(path: String): Result<Uri>
}

/**
 * The server rejected the stored credentials (as opposed to being unreachable).
 *
 * Clients throw this instead of leaking their protocol library's own error type, so callers can
 * tell "wrong password" apart from "server offline" without depending on that library.
 */
class NetworkAuthenticationException(
  message: String,
  cause: Throwable? = null,
) : IllegalStateException(message, cause)
