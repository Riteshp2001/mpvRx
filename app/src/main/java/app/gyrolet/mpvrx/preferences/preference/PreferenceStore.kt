/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences.preference

interface PreferenceStore {
  fun getString(
    key: String,
    defaultValue: String = "",
  ): Preference<String>

  fun getLong(
    key: String,
    defaultValue: Long = 0,
  ): Preference<Long>

  fun getInt(
    key: String,
    defaultValue: Int = 0,
  ): Preference<Int>

  fun getFloat(
    key: String,
    defaultValue: Float = 0f,
  ): Preference<Float>

  fun getBoolean(
    key: String,
    defaultValue: Boolean = false,
  ): Preference<Boolean>

  fun getStringSet(
    key: String,
    defaultValue: Set<String> = emptySet(),
  ): Preference<Set<String>>

  fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T>

  fun getAll(): Map<String, *>
}

inline fun <reified T : Enum<T>> PreferenceStore.getEnum(
  key: String,
  defaultValue: T,
): Preference<T> =
  getObject(
    key = key,
    defaultValue = defaultValue,
    serializer = { it.name },
    deserializer = {
      try {
        enumValueOf(it)
      } catch (_: IllegalArgumentException) {
        defaultValue
      }
    },
  )
