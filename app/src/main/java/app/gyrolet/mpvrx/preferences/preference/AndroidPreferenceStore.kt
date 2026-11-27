/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences.preference

import android.content.Context
import android.content.SharedPreferences
import app.gyrolet.mpvrx.preferences.preference.AndroidPreference.BooleanPrimitive
import app.gyrolet.mpvrx.preferences.preference.AndroidPreference.FloatPrimitive
import app.gyrolet.mpvrx.preferences.preference.AndroidPreference.IntPrimitive
import app.gyrolet.mpvrx.preferences.preference.AndroidPreference.LongPrimitive
import app.gyrolet.mpvrx.preferences.preference.AndroidPreference.Object
import app.gyrolet.mpvrx.preferences.preference.AndroidPreference.StringPrimitive
import app.gyrolet.mpvrx.preferences.preference.AndroidPreference.StringSetPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap

/** The app's default preferences file, identical to androidx PreferenceManager.getDefaultSharedPreferences. */
fun Context.defaultSharedPreferences(): SharedPreferences =
  getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

class AndroidPreferenceStore(
  context: Context,
  private val sharedPreferences: SharedPreferences = context.defaultSharedPreferences(),
) : PreferenceStore {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // Collectors are numerous — a single browser grid observes a dozen preferences per card — and the
  // upstream callbackFlow is cold, so without sharing each collection would register its own
  // OnSharedPreferenceChangeListener and every write would fan out across all of them.
  private val keyFlow =
    sharedPreferences.keyFlow.shareIn(
      scope,
      SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
      replay = 0,
    )

  override fun getString(
    key: String,
    defaultValue: String,
  ): Preference<String> =
    StringPrimitive(sharedPreferences, keyFlow, key, defaultValue).also {
      declare(key, PreferenceKind.STRING)
    }

  override fun getLong(
    key: String,
    defaultValue: Long,
  ): Preference<Long> =
    LongPrimitive(sharedPreferences, keyFlow, key, defaultValue).also {
      declare(key, PreferenceKind.LONG)
    }

  override fun getInt(
    key: String,
    defaultValue: Int,
  ): Preference<Int> =
    IntPrimitive(sharedPreferences, keyFlow, key, defaultValue).also {
      declare(key, PreferenceKind.INT)
    }

  override fun getFloat(
    key: String,
    defaultValue: Float,
  ): Preference<Float> =
    FloatPrimitive(sharedPreferences, keyFlow, key, defaultValue).also {
      declare(key, PreferenceKind.FLOAT)
    }

  override fun getBoolean(
    key: String,
    defaultValue: Boolean,
  ): Preference<Boolean> =
    BooleanPrimitive(sharedPreferences, keyFlow, key, defaultValue).also {
      declare(key, PreferenceKind.BOOLEAN)
    }

  override fun getStringSet(
    key: String,
    defaultValue: Set<String>,
  ): Preference<Set<String>> =
    StringSetPrimitive(sharedPreferences, keyFlow, key, defaultValue).also { declare(key, PreferenceKind.STRING_SET) }

  override fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T> =
    Object(
      preferences = sharedPreferences,
      keyFlow = keyFlow,
      key = key,
      defaultValue = defaultValue,
      serializer = serializer,
      deserializer = deserializer,
    ).also { declare(key, PreferenceKind.STRING) { text -> deserializer(text) } }

  override fun getAll(): Map<String, *> = sharedPreferences.all ?: emptyMap<String, Any>()

  // What each preference reads its key as, so restoring a backup can reject values of the wrong
  // kind or unreadable structured text instead of storing them for some screen to trip over.
  private class Declaration(
    val kind: PreferenceKind,
    val textValidator: ((String) -> Unit)?,
  )

  private val declarations = ConcurrentHashMap<String, Declaration>()

  private fun declare(
    key: String,
    kind: PreferenceKind,
    textValidator: ((String) -> Unit)? = null,
  ) {
    declarations[key] = Declaration(kind, textValidator)
  }

  override fun declaredKind(key: String): PreferenceKind? = declarations[key]?.kind

  override fun acceptsStoredText(
    key: String,
    value: String,
  ): Boolean {
    val validator = declarations[key]?.textValidator ?: return true
    return try {
      validator(value)
      true
    } catch (_: Exception) {
      false
    }
  }
}

private val SharedPreferences.keyFlow
  get() =
    callbackFlow {
      val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key: String? ->
          trySend(
            key,
          )
        }
      registerOnSharedPreferenceChangeListener(listener)
      awaitClose {
        unregisterOnSharedPreferenceChangeListener(listener)
      }
    }
