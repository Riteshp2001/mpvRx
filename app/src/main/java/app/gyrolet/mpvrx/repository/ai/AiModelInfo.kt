/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.repository.ai

import kotlinx.serialization.Serializable

@Serializable
data class AiModelInfo(
  val id: String,
  val displayName: String,
  val isFree: Boolean = false,
)
