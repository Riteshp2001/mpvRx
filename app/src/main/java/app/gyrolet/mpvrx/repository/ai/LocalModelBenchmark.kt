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

@Serializable
data class LocalModelBenchmark(
  val modelId: String,
  val loadMs: Long,
  val tokensPerSecond: Float,
  val memoryEstimateMb: Int,
  val benchmarkedAtMs: Long,
) {
  val loadLabel: String
    get() =
      if (loadMs >= 1000) {
        "%.1fs load".format(loadMs / 1000f)
      } else {
        "${loadMs}ms load"
      }

  val speedLabel: String
    get() = "%.1f tok/s".format(tokensPerSecond)
}
