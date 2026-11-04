/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import android.content.Context
import java.io.File

/**
 * Older versions armed a rollback after every settings import and could revert the import a few
 * launches later. Imports now keep every valid value and skip invalid ones, so a leftover rollback
 * is simply discarded.
 */
internal object SettingsRestoreGuard {
  fun discardLegacyRollback(context: Context) {
    File(context.noBackupFilesDir, "settings_restore_rollback.json").delete()
    context.deleteSharedPreferences("settings_restore_guard")
  }
}
