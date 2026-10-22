package app.gyrolet.mpvrx.core.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Platform persistence for [KeyValuePreferenceStore]; values are already string-encoded. */
interface KeyValueBackend {
  fun load(): Map<String, String>

  fun save(values: Map<String, String>)
}

/**
 * Platform-neutral store: desktop persists to a JSON file, iOS can back it with NSUserDefaults.
 * Every value is kept as a string so one backend format serves all preference types.
 */
class KeyValuePreferenceStore(
  private val backend: KeyValueBackend,
) : PreferenceStore {
  private val values = MutableStateFlow(backend.load())

  private fun write(key: String, value: String?) {
    values.update { current ->
      if (value == null) current - key else current + (key to value)
    }
    backend.save(values.value)
  }

  override fun getString(key: String, defaultValue: String): Preference<String> =
    Entry(key, defaultValue, { it }, { it })

  override fun getLong(key: String, defaultValue: Long): Preference<Long> =
    Entry(key, defaultValue, { it.toString() }, { it.toLongOrNull() ?: defaultValue })

  override fun getInt(key: String, defaultValue: Int): Preference<Int> =
    Entry(key, defaultValue, { it.toString() }, { it.toIntOrNull() ?: defaultValue })

  override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
    Entry(key, defaultValue, { it.toString() }, { it.toFloatOrNull() ?: defaultValue })

  override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
    Entry(key, defaultValue, { it.toString() }, { it.toBooleanStrictOrNull() ?: defaultValue })

  override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
    Entry(key, defaultValue, ::encodeSet, ::decodeSet)

  override fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T> = Entry(key, defaultValue, serializer, deserializer)

  private inner class Entry<T>(
    private val key: String,
    private val default: T,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
  ) : Preference<T> {
    override fun key() = key

    override fun get(): T = values.value[key]?.let { runCatching { decode(it) }.getOrNull() } ?: default

    override fun set(value: T) = write(key, encode(value))

    override fun isSet() = key in values.value

    override fun delete() = write(key, null)

    override fun defaultValue() = default

    override fun changes(): Flow<T> = values.map { get() }.distinctUntilChanged()
  }

  private companion object {
    // Unit separator never appears in paths or user labels.
    const val SET_SEPARATOR = '\u001F'

    fun encodeSet(set: Set<String>) = set.joinToString(SET_SEPARATOR.toString())

    fun decodeSet(raw: String): Set<String> = if (raw.isEmpty()) emptySet() else raw.split(SET_SEPARATOR).toSet()
  }
}
