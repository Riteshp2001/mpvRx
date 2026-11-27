/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.BuildConfig

/**
 * Asks again as soon as storage access is revoked, instead of leaving the user to find it in
 * system settings. The optional notification and audio-recording permissions are never asked for
 * on startup; only the onboarding cards and the features that use them ask, when the user acts.
 *
 * Only a grant that was seen as granted and is missing now counts as a revocation, so a permission
 * the user never gave is not pushed at them. Each revocation is asked once; a prompt that was
 * dismissed is not repeated on every resume.
 */
object PermissionRecovery {
  enum class Kind { STORAGE, NOTIFICATIONS, AUDIO }

  private const val PREFS = "permission_recovery"

  fun isGranted(
    context: Context,
    kind: Kind,
  ): Boolean =
    when (kind) {
      Kind.STORAGE -> isStorageGranted(context)
      Kind.NOTIFICATIONS -> isNotificationGranted(context)
      Kind.AUDIO -> isAudioGranted(context)
    }

  fun isStorageGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      if (!BuildConfig.SCOPED_STORAGE_ONLY) return Environment.isExternalStorageManager()
      if (Environment.isExternalStorageManager()) return true
    }
    return ContextCompat.checkSelfPermission(context, PermissionUtils.getStoragePermission()) ==
      PackageManager.PERMISSION_GRANTED
  }

  fun isNotificationGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED

  fun isAudioGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED

  /** Standard builds on Android 11+ use All files access, which is granted only on its settings page. */
  fun usesAllFilesAccess(): Boolean = !BuildConfig.SCOPED_STORAGE_ONLY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

  /** The system dialog for [kind], or null when the grant can only be given on a settings page. */
  fun runtimePermission(kind: Kind): String? =
    when (kind) {
      Kind.STORAGE -> if (usesAllFilesAccess()) null else PermissionUtils.getStoragePermission()
      Kind.NOTIFICATIONS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
      Kind.AUDIO -> Manifest.permission.RECORD_AUDIO
    }

  /**
   * Kinds that were granted at the last check and are missing now. A revoked kind stays pending
   * until a prompt for it is launched, so it is not lost when another prompt is shown first.
   */
  fun pendingRevocations(context: Context): List<Kind> {
    val prefs = prefs(context)
    val pending = mutableListOf<Kind>()
    val edit = prefs.edit()
    Kind.entries.forEach { kind ->
      if (isGranted(context, kind)) {
        edit.putBoolean(kind.name, true)
      } else if (prefs.getBoolean(kind.name, false)) {
        pending += kind
      }
    }
    edit.apply()
    return pending
  }

  fun acknowledge(
    context: Context,
    kinds: Collection<Kind>,
  ) {
    val edit = prefs(context).edit()
    kinds.forEach { edit.putBoolean(it.name, false) }
    edit.apply()
  }

  /**
   * Launches one prompt for every revoked grant that has a system dialog. Revoked all-files access
   * opens its settings page instead, and only when no dialog is launched in the same pass.
   */
  fun requestRevoked(
    activity: Activity,
    dialogLauncher: ActivityResultLauncher<Array<String>>,
  ) {
    val pending = pendingRevocations(activity).filter { it == Kind.STORAGE }
    if (pending.isEmpty()) return
    val dialogKinds = pending.filter { runtimePermission(it) != null }
    if (dialogKinds.isNotEmpty()) {
      acknowledge(activity, dialogKinds)
      dialogLauncher.launch(dialogKinds.mapNotNull { runtimePermission(it) }.toTypedArray())
    } else if (Kind.STORAGE in pending && usesAllFilesAccess()) {
      acknowledge(activity, listOf(Kind.STORAGE))
      openAllFilesAccessSettings(activity)
    }
  }

  /** Opens the All files access page for this app, falling back to app details only if it is missing. */
  fun openAllFilesAccessSettings(context: Context): Boolean {
    val packageUri = Uri.parse("package:${context.packageName}")
    val intents =
      listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = packageUri },
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = packageUri },
      )
    return intents.any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
  }

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
