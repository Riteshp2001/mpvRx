/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.repository.ai

import kotlinx.serialization.Serializable

@Serializable
data class LocalModelBenchmark(
  val modelId: String,
  val loadMs: Long,
  val tokensPerSecond: Float,
  val memoryEstimateMb: Int,
  val benchmarkedAtMs: Long,
) {
  val loadLabel: String
    get() = if (loadMs >= 1000) {
      "%.1fs load".format(loadMs / 1000f)
    } else {
      "${loadMs}ms load"
    }

  val speedLabel: String
    get() = "%.1f tok/s".format(tokensPerSecond)
}
