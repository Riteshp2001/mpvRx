// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.remote

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
internal data class PairedPhone(
  val id: String,
  val name: String,
  val secret: String,
)

@Serializable
internal data class PairedTv(
  val tvId: String,
  val name: String,
  val pairId: String,
  val secret: String,
  val host: String,
  val port: Int,
)

/** Pairing secrets live in their own file, so settings exports and backups never include them. */
internal class RemoteControlStore(
  context: Context,
) {
  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  var serverEnabled: Boolean
    get() = prefs.getBoolean(KEY_SERVER_ENABLED, true)
    set(value) {
      prefs.edit().putBoolean(KEY_SERVER_ENABLED, value).apply()
    }

  val tvId: String
    get() =
      synchronized(lock) {
        prefs.getString(KEY_TV_ID, null)
          ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_TV_ID, it).commit() }
      }

  fun pairedPhones(): List<PairedPhone> = read(KEY_PHONES, PairedPhone.serializer())

  fun pairedPhone(id: String): PairedPhone? = pairedPhones().firstOrNull { it.id == id }

  fun addPairedPhone(phone: PairedPhone) =
    update(KEY_PHONES, PairedPhone.serializer()) { phones -> (phones + phone).takeLast(MAX_PAIRED) }

  fun removePairedPhone(id: String) =
    update(KEY_PHONES, PairedPhone.serializer()) { phones -> phones.filterNot { it.id == id } }

  fun pairedTvs(): List<PairedTv> = read(KEY_TVS, PairedTv.serializer())

  fun pairedTv(tvId: String): PairedTv? = pairedTvs().firstOrNull { it.tvId == tvId }

  fun savePairedTv(tv: PairedTv) =
    update(KEY_TVS, PairedTv.serializer()) { tvs -> (tvs.filterNot { it.tvId == tv.tvId } + tv).takeLast(MAX_PAIRED) }

  fun removePairedTv(tvId: String) = update(KEY_TVS, PairedTv.serializer()) { tvs -> tvs.filterNot { it.tvId == tvId } }

  private fun <T> read(
    key: String,
    serializer: KSerializer<T>,
  ): List<T> =
    prefs
      .getString(key, null)
      ?.let { stored -> runCatching { json.decodeFromString(ListSerializer(serializer), stored) }.getOrNull() }
      .orEmpty()

  private fun <T> update(
    key: String,
    serializer: KSerializer<T>,
    transform: (List<T>) -> List<T>,
  ) {
    synchronized(lock) {
      val next = transform(read(key, serializer))
      prefs.edit().putString(key, json.encodeToString(ListSerializer(serializer), next)).commit()
    }
  }

  private companion object {
    const val FILE = "remote_control"
    const val KEY_SERVER_ENABLED = "server_enabled"
    const val KEY_TV_ID = "tv_id"
    const val KEY_PHONES = "paired_phones"
    const val KEY_TVS = "paired_tvs"
    const val MAX_PAIRED = 16
    val lock = Any()
  }
}
