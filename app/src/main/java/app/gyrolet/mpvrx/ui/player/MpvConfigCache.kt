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

  init {
    scope.launch {
      preferences.mpvConf.changes().collect {
        runCatching { ensureCurrent() }
          .onFailure { error -> Log.e(TAG, "Failed to refresh the mpv.conf cache", error) }
      }
    }
  }

  fun update(content: String): Boolean {
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
    ensureCurrent()
    val bytes = synchronized(lock) { atomicConfigFile.readFully() }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
  }

  private fun readCachedContent(): String? =
    if (!configFile.isFile) {
      null
    } else {
      runCatching { atomicConfigFile.readFully().toString(StandardCharsets.UTF_8) }.getOrNull()
    }

  private fun writeCachedContent(content: String) {
    configFile.parentFile?.mkdirs()
    val output = atomicConfigFile.startWrite()
    try {
      output.write(content.toByteArray(StandardCharsets.UTF_8))
      atomicConfigFile.finishWrite(output)
    } catch (error: Throwable) {
      atomicConfigFile.failWrite(output)
      throw error
    }
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