/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.gyrolet.mpvrx.utils.logging.Log

/** Repairs alias overrides after an app upgrade, without adding work to video startup. */
class LauncherIconRepairReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    runCatching { LauncherIconManager.reconcile(context) }
      .onFailure { Log.w("LauncherIcon", "Unable to reconcile launcher aliases after upgrade", it) }
  }
}
