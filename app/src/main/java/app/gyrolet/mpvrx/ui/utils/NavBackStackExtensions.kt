/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.utils

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Pops the current entry without ever leaving the NavDisplay with an empty stack.
 *
 * Returns true when an entry was removed, or false when the caller is already at the root.
 */
fun NavBackStack<*>.popSafely(): Boolean {
  if (size <= 1) return false

  removeLastOrNull()
  return true
}

/**
 * Replaces the current top entry with [screen] instead of pushing on top of it.
 *
 * Used when a screen is a transient gate/step (e.g. the Secure Folder PIN screen) that
 * shouldn't remain in the back stack once its job is done — otherwise pressing back from the
 * destination screen would land back on the gate instead of whatever was open before it.
 */
fun <T : NavKey> NavBackStack<T>.replaceTop(screen: T) {
  if (isEmpty()) {
    add(screen)
  } else {
    this[lastIndex] = screen
  }
}
