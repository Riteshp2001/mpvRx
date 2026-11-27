/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.engine

/**
 * The kodi distribution's engine runs in its own process (mpvlib-kodi's KodiEngineService), which
 * holds Kodi alone: none of the app's initialization runs there, and the process ends whenever the
 * player core is destroyed.
 */
object EngineProcess {
  private const val SUFFIX = ":kodi"

  fun isEngineProcess(
    processName: String?,
    packageName: String,
  ): Boolean = processName == packageName + SUFFIX
}
