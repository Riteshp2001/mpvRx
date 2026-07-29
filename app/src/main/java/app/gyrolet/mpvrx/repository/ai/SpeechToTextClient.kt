/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.repository.ai

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class SpeechSegment(
  val startMs: Long,
  val endMs: Long,
  val text: String,
)

@Serializable
data class SpeechTranscript(
  val text: String,
  val segments: List<SpeechSegment> = emptyList(),
)

interface SpeechToTextClient {
  suspend fun transcribe(
    apiKey: String,
    audioFile: File,
    language: String?,
    model: String? = null,
  ): Result<SpeechTranscript>
}
