/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.preferences.preference

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface Preference<T> {
  fun key(): String

  fun get(): T

  fun set(value: T)

  fun isSet(): Boolean

  fun delete()

  fun defaultValue(): T

  fun changes(): Flow<T>

  fun stateIn(scope: CoroutineScope): StateFlow<T>
}

inline fun <reified T> Preference<T>.deleteAndGet(): T {
  delete()
  return get()
}

operator fun <T> Preference<Set<T>>.plusAssign(item: T) {
  set(get() + item)
}

operator fun <T> Preference<Set<T>>.minusAssign(item: T) {
  set(get() - item)
}

@Composable
fun <T> Preference<T>.collectAsState(): State<T> {
  val flow = remember(this) { changes() }
  return flow.collectAsState(initial = get())
}
