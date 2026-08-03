/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.proxy

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
