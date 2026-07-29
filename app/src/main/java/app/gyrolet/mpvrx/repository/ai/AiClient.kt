/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.repository.ai

data class AiGenerationOptions(
  val maxTokens: Int = 200,
  val temperature: Double = 0.3,
)

data class AiSource(
  val url: String,
  val title: String? = null,
)

data class AiGeneratedContent(
  val text: String,
  val reasoning: String? = null,
  val sources: List<AiSource> = emptyList(),
)

interface AiClient {
  suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>>
  suspend fun verifyKey(apiKey: String): Result<String>
  suspend fun generateContent(
    apiKey: String,
    model: String,
    instruction: String,
    userInput: String,
    options: AiGenerationOptions = AiGenerationOptions(),
  ): Result<AiGeneratedContent>
}
