/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Durable record of the media state a persisted folder snapshot was built from.
 *
 * The folder list is snapshotted so the browser can paint before any scanning happens, but the
 * scan used to run anyway two seconds later, which is why reopening the app cost the same as a
 * cold launch. Comparing a cheap MediaStore token lets a warm start keep the snapshot instead of
 * re-deriving what it already has.
 *
 * [MediaStore.getVersion] only exists from API 29 and changes whenever MediaProvider's tables
 * are written. Below that there is no equivalent token, so this class reports "no signal" and the
 * caller falls back to its previous behaviour of always rescanning. [MAX_SNAPSHOT_AGE_MS] caps how
 * long any snapshot is trusted regardless, which covers a change the version did not report, such
 * as a copy made without triggering a media scan.
 */
object MediaLibraryFreshness {
  private const val TAG = "MediaLibraryFreshness"

  private const val PREFERENCES = "library_freshness"
  private const val KEY_TOKEN = "scanned_token"
  private const val KEY_RECORDED_AT = "recorded_at"

  /** Backstop for a change the token did not report, such as a copy made without a media scan. */
  private const val MAX_SNAPSHOT_AGE_MS = 6L * 60L * 60L * 1000L

  /** Whether a folder snapshot taken from the current media state can still be used as-is. */
  fun isSnapshotFresh(context: Context): Boolean {
    val recordedAt = prefs(context).getLong(KEY_RECORDED_AT, 0L)
    if (recordedAt <= 0L) return false
    if (System.currentTimeMillis() - recordedAt > MAX_SNAPSHOT_AGE_MS) return false

    val expected = prefs(context).getString(KEY_TOKEN, null) ?: return false
    // No token means no signal, which is treated as "changed" so an unreadable version can never
    // be mistaken for an unchanged library.
    val current = currentToken(context) ?: return false
    return expected == current
  }

  /** Called after a scan completes, alongside writing the snapshot it produced. */
  fun recordScan(context: Context) {
    val token = currentToken(context) ?: return
    runCatching {
      prefs(context)
        .edit()
        .putString(KEY_TOKEN, token)
        .putLong(KEY_RECORDED_AT, System.currentTimeMillis())
        .apply()
    }.onFailure { error ->
      Log.w(TAG, "Unable to record media library freshness", error)
    }
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  /**
   * A token that changes whenever MediaProvider's tables are written, or null when no such signal
   * is available.
   *
   * [MediaStore.getVersion] and [MediaStore.getExternalVolumeNames] are API 29+. There is no
   * equivalent below that, so those versions always rescan and only benefit from the age cap.
   */
  private fun currentToken(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

    return runCatching {
      val volumes = MediaStore.getExternalVolumeNames(context)
      if (volumes.isEmpty()) {
        null
      } else {
        volumes.sorted().joinToString("|") { volume -> "$volume=${MediaStore.getVersion(context, volume)}" }
      }
    }.onFailure { error ->
      Log.w(TAG, "Unable to read the MediaStore version", error)
    }.getOrNull()
  }
}