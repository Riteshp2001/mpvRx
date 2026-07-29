/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
   * Get file URI for playback
   */
  suspend fun getFileUri(path: String): Result<Uri>
}
