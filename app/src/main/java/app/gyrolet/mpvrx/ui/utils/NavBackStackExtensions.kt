/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.utils

import androidx.navigation3.runtime.NavBackStack

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
@Suppress("UNCHECKED_CAST")
fun <T : Any> NavBackStack<T>.replaceTop(screen: T) {
  if (isEmpty()) {
    add(screen)
  } else {
    this[lastIndex] = screen
  }
}
