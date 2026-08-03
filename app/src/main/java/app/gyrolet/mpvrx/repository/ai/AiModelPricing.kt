/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object AiModelPricing {
  fun isZeroCost(pricing: JsonObject?): Boolean {
    if (pricing == null || pricing.isEmpty()) return false

    var sawNumericPrice = false
    for ((_, value) in pricing) {
      val primitive = value as? JsonPrimitive ?: continue
      val number = primitive.contentOrNull?.toDoubleOrNull() ?: continue
      sawNumericPrice = true
      if (number != 0.0) return false
    }

    return sawNumericPrice
  }
}
