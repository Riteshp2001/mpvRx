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

