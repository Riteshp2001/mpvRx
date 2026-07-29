/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.repository.ai

internal object AiModelCapabilities {
  fun isTextGenerationModel(id: String): Boolean {
    val value = id.lowercase()
    return listOf(
      "whisper",
      "transcribe",
      "tts",
      "speech",
      "embedding",
      "moderation",
      "dall-e",
      "gpt-image",
      "realtime",
      "audio-preview",
      "guard",
      "safety",
    ).none(value::contains)
  }
}
