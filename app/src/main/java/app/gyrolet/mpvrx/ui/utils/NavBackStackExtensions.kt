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

/** Ignore repeat taps on the same destination before the outgoing screen has left composition. */
fun <T : NavKey> NavBackStack<T>.navigateTo(screen: T) {
  if (lastOrNull() != screen) add(screen)
}

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
 * Pops entries until [predicate] matches the top entry, leaving that entry in place.
 *
 * Used when a multi-step flow (e.g. playlist picker → network browser → subfolder) must return
 * straight to its origin instead of unwinding one screen at a time. Never empties the stack.
 *
 * Returns true when at least one entry was removed.
 */
fun <T : NavKey> NavBackStack<T>.popUpTo(predicate: (T) -> Boolean): Boolean {
  // Nothing matching means nothing to unwind to; popping anyway would strand the caller at the
  // root of the stack instead of leaving the flow where it was.
  val target = indexOfLast(predicate)
  if (target < 0) return false

  var popped = false
  while (size > 1 && lastIndex > target) {
    removeLastOrNull()
    popped = true
  }
  return popped
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
