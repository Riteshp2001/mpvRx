package app.gyrolet.mpvrx.domain.network

import androidx.compose.runtime.Immutable

/**
 * Represents a file or directory on a network share
 */
@Immutable
data class NetworkFile(
  val name: String,
  val path: String,
  val size: Long,
  val isDirectory: Boolean,
  val lastModified: Long = 0,
  val mimeType: String? = null,
)

