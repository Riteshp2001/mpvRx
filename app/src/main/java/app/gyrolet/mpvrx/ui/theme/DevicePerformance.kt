// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.gyrolet.mpvrx.utils.device.DevicePerformance

/**
 * Use the same cached device tier as thumbnail work and effect defaults, so drawing and loading
 * agree about low-RAM, older Android and budget devices. Explicit appearance settings still win.
 */
@Composable
fun rememberLowEndDevice(): Boolean {
  val context = LocalContext.current.applicationContext
  return remember(context) {
    DevicePerformance.init(context)
    DevicePerformance.isLowEnd
  }
}
