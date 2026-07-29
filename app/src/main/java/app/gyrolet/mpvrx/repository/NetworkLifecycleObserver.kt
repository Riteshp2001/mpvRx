/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.repository

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NetworkLifecycleObserver(
  private val networkRepository: NetworkRepository,
) : DefaultLifecycleObserver {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onStop(owner: LifecycleOwner) {
    super.onStop(owner)
    Log.d("NetworkLifecycle", "App in background: Disconnecting all idle network shares to save battery")
    scope.launch {
      try {
        networkRepository.disconnectAll()
      } catch (e: Exception) {
        Log.e("NetworkLifecycle", "Error disconnecting shares", e)
      }
    }
  }

  override fun onDestroy(owner: LifecycleOwner) {
    // Cancel any pending disconnect work and release the SupervisorJob so
    // the CoroutineScope is not leaked when MainActivity is destroyed
    // (config change, process death, etc.). Without this, every Activity
    // recreation leaks a scope with a live job. See issue 2.5 in the
    // leak audit.
    scope.cancel()
    super.onDestroy(owner)
  }
}
