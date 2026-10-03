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
 * [MediaStore.getMediaVersion] only exists from API 29 and changes whenever MediaProvider's tables
 * are written. Below that there is no equivalent token, so one is managed locally and bumped by
 * [MediaScanReceiver] whenever the platform reports a media scan. That fallback cannot see copies
 * made without a media scan, so [MAX_SNAPSHOT_AGE_MS] caps how long any snapshot is trusted.
 */
object MediaLibraryFreshness {
  private const val TAG = "MediaLibraryFreshness"

  private const val PREFERENCES = "library_freshness"
  private const val KEY_TOKEN = "scanned_token"
  private const val KEY_RECORDED_AT = "recorded_at"

  /**
   * Longest a snapshot is trusted without any corroborating token. Keeps a missed change on
   * API 26-28 from hiding new media indefinitely, while still avoiding a rescan per app launch.
   */
  private const val MAX_SNAPSHOT_AGE_MS = 6L * 60L * 60L * 1000L

  /** Whether a folder snapshot taken from the current media state can still be used as-is. */
  fun isSnapshotFresh(context: Context): Boolean {
    val recordedAt = prefs(context).getLong(KEY_RECORDED_AT, 0L)
    if (recordedAt <= 0L) return false
    if (System.currentTimeMillis() - recordedAt > MAX_SNAPSHOT_AGE_MS) return false

    val expected = prefs(context).getString(KEY_TOKEN, null) ?: return false
    return expected == currentToken(context)
  }

  /** Called after a scan completes, alongside writing the snapshot it produced. */
  fun recordScan(context: Context) {
    runCatching {
      prefs(context)
        .edit()
        .putString(KEY_TOKEN, currentToken(context))
        .putLong(KEY_RECORDED_AT, System.currentTimeMillis())
        .apply()
    }.onFailure { error ->
      Log.w(TAG, "Unable to record media library freshness", error)
    }
  }

  /**
   * Invalidates the snapshot when the platform reports new or changed media. Redundant on API 29+
   * where the MediaStore token already moved, but it is the only signal available below that.
   */
  fun markLibraryChanged(context: Context) {
    runCatching {
      prefs(context)
        .edit()
        .putString(KEY_TOKEN, "changed:${System.currentTimeMillis()}")
        .apply()
    }.onFailure { error ->
      Log.w(TAG, "Unable to invalidate media library freshness", error)
    }
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  private fun currentToken(context: Context): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      runCatching { MediaStore.getMediaVersion(context) }.getOrElse { error ->
        Log.w(TAG, "Unable to read the MediaStore version", error)
        "unavailable"
      }
    } else {
      "legacy"
    }
}