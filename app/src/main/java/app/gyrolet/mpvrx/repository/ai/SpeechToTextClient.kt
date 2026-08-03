/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.ai

import kotlinx.serialization.Serializable
import java.io.File

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
