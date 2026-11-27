/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import app.gyrolet.mpvrx.utils.logging.Log

/**
 * Cheap "has the video or audio library changed?" signal, so cold boots can skip a rescan.
 *
 * [MediaStore.getGeneration] advances for every MediaProvider write, including photos, screenshots
 * and downloads, so comparing it verbatim would rescan after almost any phone use. The token pairs
 * each volume's generation with its playable row count instead; [isTokenCurrent] accepts a newer
 * generation only when no video or audio row was modified since and the count is unchanged.
 *
 * Scope — this only speaks for MediaStore-indexed media. Files inside `.nomedia` trees are
 * deliberately absent from MediaStore, so this guard must never be used to skip
 * [FolderViewScanner.scanNoMediaFoldersIncrementally], which has its own fingerprint index.
 */
object MediaStoreGenerationGuard {
  private const val TAG = "MediaStoreGenerationGuard"

  private const val PREFS = "media_store_generation"
  private const val KEY_TOKEN = "token"

  /** Marker separating the recorded generation from caller payload in a combined blob. */
  internal const val PAYLOAD_PREFIX = "gen:"

  // v2 records per-volume playable row counts so unrelated (image/file) writes are ignored.
  private const val TOKEN_VERSION = "v2"

  /**
   * Generation and playable row count for every external volume, or null when it cannot be determined.
   *
   * Null means "unknown" and callers must then scan. It is returned below API 30, where
   * [MediaStore.getGeneration] does not exist, so those devices keep today's behaviour rather than
   * trusting a homegrown fingerprint that could miss a same-second edit.
   */
  fun currentToken(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return runCatching {
      val volumes = MediaStore.getExternalVolumeNames(context)
      // No external volume is a legitimate state (an emulator image with no mounted media), not a
      // failure, so this still yields a stable token.
      val perVolume =
        volumes.sorted().map { volume ->
          "$volume=${MediaStore.getGeneration(context, volume)}:${playableCount(context, volume)}"
        }
      // getVersion changes when MediaProvider is upgraded or its schema resets, which can renumber
      // generations; folding it in makes that a guaranteed mismatch instead of a false match.
      val providerVersion = runCatching { MediaStore.getVersion(context) }.getOrDefault("")
      "$TOKEN_VERSION|$providerVersion|${perVolume.joinToString(",")}"
    }.onFailure { error ->
      Log.w(TAG, "Could not read MediaStore generation", error)
    }.getOrNull()
  }

  /**
   * True when [recorded] still describes the video/audio library.
   *
   * A volume generation advances for *any* MediaProvider write — every photo, screenshot or
   * download — so comparing it verbatim invalidated every snapshot within hours of normal phone
   * use and forced a full rescan on the next launch. Only rows the browser can show matter: no
   * video/audio row modified after the recorded generation, and the same number of such rows
   * (deletions do not leave a modified row behind), means the previous scan is still exact.
   */
  fun isTokenCurrent(
    context: Context,
    recorded: String,
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return runCatching {
      val parts = recorded.split('|', limit = 3)
      if (parts.size != 3 || parts[0] != TOKEN_VERSION) return false
      val providerVersion = runCatching { MediaStore.getVersion(context) }.getOrDefault("")
      if (parts[1] != providerVersion) return false
      val recordedVolumes =
        parts[2].split(',').filter { it.isNotEmpty() }.associate { entry ->
          val volume = entry.substringBefore('=')
          val gen = entry.substringAfter('=').substringBefore(':').toLong()
          val count = entry.substringAfter(':').toInt()
          volume to (gen to count)
        }
      val volumes = MediaStore.getExternalVolumeNames(context)
      if (volumes != recordedVolumes.keys) return false
      volumes.all { volume ->
        val (generation, count) = recordedVolumes.getValue(volume)
        if (MediaStore.getGeneration(context, volume) == generation) return@all true
        playableCount(context, volume) == count && modifiedPlayableCount(context, volume, generation) == 0
      }
    }.onFailure { error ->
      Log.w(TAG, "Could not compare MediaStore generation", error)
    }.getOrDefault(false)
  }

  private fun playableCount(
    context: Context,
    volume: String,
  ): Int =
    countRows(context, MediaStore.Video.Media.getContentUri(volume), null, null) +
      countRows(context, MediaStore.Audio.Media.getContentUri(volume), null, null)

  private fun modifiedPlayableCount(
    context: Context,
    volume: String,
    sinceGeneration: Long,
  ): Int {
    val selection = "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
    val args = arrayOf(sinceGeneration.toString())
    return countRows(context, MediaStore.Video.Media.getContentUri(volume), selection, args) +
      countRows(context, MediaStore.Audio.Media.getContentUri(volume), selection, args)
  }

  private fun countRows(
    context: Context,
    uri: android.net.Uri,
    selection: String?,
    args: Array<String>?,
  ): Int =
    context.contentResolver
      .query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
      ?.use { it.count } ?: 0

  /**
   * Records the MediaStore state that a just-completed scan observed. A null token is ignored so the
   * stored value can never be downgraded to something a later comparison would accept.
   */
  fun remember(
    context: Context,
    token: String?,
  ) {
    if (token == null) return
    runCatching {
      prefs(context).edit().putString(KEY_TOKEN, token).apply()
    }.onFailure { error ->
      Log.w(TAG, "Could not persist MediaStore generation", error)
    }
  }

  /**
   * True when MediaStore is unchanged since the last [remember], meaning an existing snapshot still
   * describes the library and the scan can be skipped.
   */
  fun isUnchangedSinceLastScan(context: Context): Boolean {
    val previous = lastRemembered(context) ?: return false
    return isTokenCurrent(context, previous)
  }

  /**
   * True when a blob that embeds its own generation was produced by a scan that observed the current
   * MediaStore state.
   *
   * This is the form the tree snapshot uses. Embedding the generation in the payload keeps the two
   * atomic: there is no window where a payload and a separately stored token disagree, which is
   * exactly how a stale snapshot would end up being trusted.
   */
  fun isSnapshotCurrent(
    context: Context,
    payload: String,
  ): Boolean {
    // startsWith, not substringAfter: a corrupt or hand-edited blob that merely *contains* the
    // marker mid-string must not be able to pass as valid.
    if (!payload.startsWith(PAYLOAD_PREFIX)) return false
    val embedded = payload.removePrefix(PAYLOAD_PREFIX).substringBefore('\n')
    if (embedded.isBlank()) return false
    return isTokenCurrent(context, embedded)
  }

  /**
   * Prefixes a payload with the generation it was built from. Pair with [isSnapshotCurrent] rather
   * than [remember], so the freshness proof travels with the data instead of beside it.
   */
  fun withGeneration(
    payload: String,
    token: String?,
  ): String? {
    if (token == null) return null
    return "$PAYLOAD_PREFIX$token\n$payload"
  }

  /**
   * Forget the shared Folder-list token without discarding a valid, independent Tree snapshot.
   * Per-mode scan-option changes already produce distinct Tree cache keys.
   */
  fun forgetLastScan(context: Context) {
    runCatching { prefs(context).edit().remove(KEY_TOKEN).apply() }
      .onFailure { error ->
        Log.w(TAG, "Could not clear stored MediaStore generation", error)
      }
  }

  /**
   * Called when the library may have changed in ways a MediaStore query cannot see: a user-driven
   * refresh, a media event, or an in-app file mutation. Also drops the Tree snapshot.
   */
  fun invalidate(context: Context) {
    forgetLastScan(context)
    // Explicit hard refreshes and real media mutations must still drop Tree's snapshot.
    TreeViewCache.clear(context)
  }

  private fun lastRemembered(context: Context): String? =
    runCatching { prefs(context).getString(KEY_TOKEN, null) }
      .onFailure { error ->
        Log.w(TAG, "Could not read stored MediaStore generation", error)
      }.getOrNull()

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
