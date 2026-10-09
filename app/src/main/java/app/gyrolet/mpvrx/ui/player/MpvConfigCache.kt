/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class MpvConfigCache(
  context: Context,
  private val preferences: AdvancedPreferences,
) {
  private val lock = Any()
  private val configFile = File(context.applicationContext.filesDir, FILE_NAME)
  private val atomicConfigFile = AtomicFile(configFile)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * The exact bytes of the last read/written mpv.conf, mirrored from [readCachedContent] and
   * [writeCachedContent] while [lock] is held. [ensureCurrent] already reads the whole file to
   * decide whether it must be rewritten, so [configurationKey] can hash this copy instead of
   * reading the same file a second time in the same open. Never mutated in place, only replaced.
   */
  private var cachedBytes: ByteArray? = null

  /**
   * [configurationKey] is on the player-open path and used to read the whole file plus hash it on
   * every open. These two fields let it return immediately when neither the stored configuration
   * nor the preference it was derived from has moved. A changed preference — or an explicit
   * [update] — invalidates them, so the file is re-read and re-hashed as before. The only case no
   * longer observed is an out-of-band rewrite of this app-private file with the preference held
   * constant, which has no supported flow.
   */
  @Volatile
  private var cachedConfigurationKey: String? = null

  @Volatile
  private var configurationKeyPreferenceValue: String? = null

  init {
    scope.launch {
      preferences.mpvConf.changes().collect {
        runCatching { ensureCurrent() }
          .onFailure { error -> Log.e(TAG, "Failed to refresh the mpv.conf cache", error) }
      }
    }
  }

  fun update(content: String): Boolean {
    cachedConfigurationKey = null
    val result =
      synchronized(lock) {
        updateLocked(content = content, updatePreference = true)
      }
    reloadIfNeeded(result)
    return result.changed
  }

  fun ensureCurrent(): Boolean {
    val result =
      synchronized(lock) {
        updateLocked(content = preferences.mpvConf.get(), updatePreference = false)
      }
    reloadIfNeeded(result)
    return result.changed
  }

  fun configurationKey(): String {
    val preferenceValue = preferences.mpvConf.get()
    cachedConfigurationKey?.takeIf { configurationKeyPreferenceValue == preferenceValue }?.let { return it }
    ensureCurrent()
    return synchronized(lock) {
      // [ensureCurrent] always leaves [cachedBytes] matching the file. The fallback only covers a
      // concurrent external writer between that call and here; it keeps the pre-existing behaviour
      // of hashing whatever is on disk instead of trusting a possibly stale mirror.
      val bytes = cachedBytes ?: atomicConfigFile.readFully().also { cachedBytes = it }
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
    }.also {
      cachedConfigurationKey = it
      configurationKeyPreferenceValue = preferenceValue
    }
  }

  private fun readCachedContent(): String? {
    if (!configFile.isFile) {
      cachedBytes = null
      return null
    }
    val bytes =
      runCatching { atomicConfigFile.readFully() }
        .onSuccess { cachedBytes = it }
        .getOrNull()
    return bytes?.toString(StandardCharsets.UTF_8)
  }

  private fun writeCachedContent(content: String) {
    configFile.parentFile?.mkdirs()
    val bytes = content.toByteArray(StandardCharsets.UTF_8)
    val output = atomicConfigFile.startWrite()
    try {
      output.write(bytes)
      atomicConfigFile.finishWrite(output)
    } catch (error: Throwable) {
      atomicConfigFile.failWrite(output)
      throw error
    }
    cachedBytes = bytes
  }

  private fun updateLocked(
    content: String,
    updatePreference: Boolean,
  ): CacheUpdate {
    val cachedContentChanged = readCachedContent() != content
    if (cachedContentChanged) writeCachedContent(content)

    val preferenceChanged = updatePreference && preferences.mpvConf.get() != content
    if (preferenceChanged) preferences.mpvConf.set(content)
    return CacheUpdate(cachedContentChanged, preferenceChanged)
  }

  private fun reloadIfNeeded(result: CacheUpdate) {
    if (result.cachedContentChanged) {
      PlaybackSession.reloadMpvConfig(configFile.absolutePath)
    }
  }

  private data class CacheUpdate(
    val cachedContentChanged: Boolean,
    val preferenceChanged: Boolean,
  ) {
    val changed: Boolean
      get() = cachedContentChanged || preferenceChanged
  }

  companion object {
    const val FILE_NAME = "mpv.conf"
    private const val TAG = "MpvConfigCache"
  }
}