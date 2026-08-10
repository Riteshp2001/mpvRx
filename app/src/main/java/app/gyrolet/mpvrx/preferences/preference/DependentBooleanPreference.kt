/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.preferences.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Boolean preference that can only be enabled while [required] is enabled.
 *
 * If an older app version left the stored value enabled while the dependency is disabled, the
 * stale value is cleared as soon as it is observed. This keeps every consumer on the same rule
 * instead of relying on individual screens or activities to remember the dependency.
 */
class DependentBooleanPreference(
  private val delegate: Preference<Boolean>,
  private val required: Preference<Boolean>,
) : Preference<Boolean> {
  override fun key(): String = delegate.key()

  override fun get(): Boolean {
    val allowed = required.get()
    val enabled = delegate.get()
    if (enabled && !allowed) delegate.set(false)
    return enabled && allowed
  }

  override fun set(value: Boolean) {
    delegate.set(value && required.get())
  }

  override fun isSet(): Boolean = delegate.isSet()

  override fun delete() = delegate.delete()

  override fun defaultValue(): Boolean = delegate.defaultValue() && required.defaultValue()

  override fun changes(): Flow<Boolean> =
    combine(delegate.changes(), required.changes()) { enabled, allowed -> enabled to allowed }
      .onEach { (enabled, allowed) ->
        if (enabled && !allowed) delegate.set(false)
      }.map { (enabled, allowed) -> enabled && allowed }
      .distinctUntilChanged()

  override fun stateIn(scope: CoroutineScope): StateFlow<Boolean> =
    changes().stateIn(
      scope = scope,
      started = SharingStarted.Eagerly,
      initialValue = get(),
    )
}
