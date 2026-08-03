/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
