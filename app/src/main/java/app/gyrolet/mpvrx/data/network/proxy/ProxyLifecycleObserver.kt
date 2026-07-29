/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.data.network.proxy

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Lifecycle observer to properly manage the proxy server lifecycle
 */
class ProxyLifecycleObserver : DefaultLifecycleObserver {

  companion object {
    private const val TAG = "ProxyLifecycleObserver"
  }

  override fun onStop(owner: LifecycleOwner) {
    super.onStop(owner)
    // When the app goes to background, keep the proxy running
  }

  override fun onDestroy(owner: LifecycleOwner) {
    super.onDestroy(owner)
    // Clean up proxy when app is destroyed
    NetworkStreamingProxy.stopInstance()
  }
}

