/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

/** Owners that are allowed to mutate keys inside mpv's shared glsl-shader-opts property. */
enum class ShaderOptionOwner {
  HDR_TOYS,
  FLOW_AMBIENT,
}

/**
 * Serializes read/merge/write updates to glsl-shader-opts.
 *
 * mpv exposes every shader parameter through one string property. Updating the whole property
 * from independent features caused HDR mode changes to erase Flow's transition and Flow samples
 * to restore stale HDR options. Each owner now replaces only its declared keys while all foreign
 * and other-owner entries are retained.
 */
object ShaderOptionRegistry {
  private val ownedKeys =
    mapOf(
      ShaderOptionOwner.HDR_TOYS to
        setOf(
          "astra/auto_exposure_limit_positive",
        ),
      ShaderOptionOwner.FLOW_AMBIENT to
        setOf(
          "mpvrx_flow_external",
          "mpvrx_flow_prev_r",
          "mpvrx_flow_prev_g",
          "mpvrx_flow_prev_b",
          "mpvrx_flow_target_r",
          "mpvrx_flow_target_g",
          "mpvrx_flow_target_b",
          "mpvrx_flow_start",
        ),
    )

  fun replace(
    owner: ShaderOptionOwner,
    values: List<Pair<String, String>>,
  ) {
    if (!PlaybackSession.isInitialized) return
    PlaybackSession.updateStringProperty(PROPERTY) { current ->
      merge(current, owner, values)
    }
  }

  fun clear(owner: ShaderOptionOwner) = replace(owner, emptyList())

  /** Pure merge helper also used for init-time option construction. */
  fun merge(
    current: String,
    owner: ShaderOptionOwner,
    values: List<Pair<String, String>>,
  ): String {
    val allowedKeys = ownedKeys.getValue(owner)
    check(values.all { (key, _) -> key in allowedKeys }) { "Shader option owner $owner wrote an unowned key" }

    val retained =
      splitSettings(current).filterNot { entry ->
        parseEntry(entry)?.first?.let(allowedKeys::contains) == true
      }
    return (retained + values.map { (key, value) -> "$key=$value" }).joinToString(",")
  }

  private fun parseEntry(entry: String): Pair<String, String>? {
    val separator = entry.indexOf('=')
    if (separator <= 0) return null
    val key = entry.substring(0, separator).trim()
    val value = entry.substring(separator + 1).trim()
    return if (key.isEmpty()) null else key to value
  }

  private fun splitSettings(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    raw.forEach { character ->
      when {
        escaped -> {
          current.append(character)
          escaped = false
        }
        character == '\\' -> {
          current.append(character)
          escaped = true
        }
        character == ',' -> {
          current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
          current.clear()
        }
        else -> current.append(character)
      }
    }
    current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
    return result
  }

  private const val PROPERTY = "glsl-shader-opts"
}
