package app.gyrolet.mpvrx.core.preferences

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Same contract as the Android app's preference layer so screens port 1:1. */
interface Preference<T> {
  fun key(): String

  fun get(): T

  fun set(value: T)

  fun isSet(): Boolean

  fun delete()

  fun defaultValue(): T

  fun changes(): Flow<T>

  fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, get())
}

operator fun <T> Preference<Set<T>>.plusAssign(item: T) {
  set(get() + item)
}

operator fun <T> Preference<Set<T>>.minusAssign(item: T) {
  set(get() - item)
}

interface PreferenceStore {
  fun getString(key: String, defaultValue: String = ""): Preference<String>

  fun getLong(key: String, defaultValue: Long = 0): Preference<Long>

  fun getInt(key: String, defaultValue: Int = 0): Preference<Int>

  fun getFloat(key: String, defaultValue: Float = 0f): Preference<Float>

  fun getBoolean(key: String, defaultValue: Boolean = false): Preference<Boolean>

  fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Preference<Set<String>>

  fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T>
}

inline fun <reified T : Enum<T>> PreferenceStore.getEnum(key: String, defaultValue: T): Preference<T> =
  getObject(
    key = key,
    defaultValue = defaultValue,
    serializer = { it.name },
    deserializer = { stored -> enumValues<T>().firstOrNull { it.name == stored } ?: defaultValue },
  )
