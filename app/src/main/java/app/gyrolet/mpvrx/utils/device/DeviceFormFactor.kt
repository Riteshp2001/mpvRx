/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceFormFactor {
  fun isTelevision(context: Context): Boolean {
    val packageManager = context.packageManager
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return TvDevicePolicy.isTelevision(
      uiModeType = uiModeManager?.currentModeType,
      hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
      hasLeanbackOnly = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY),
      hasTelevisionHardware = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION),
    )
  }

  fun hasTouchscreen(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

internal object TvDevicePolicy {
  fun isTelevision(
    uiModeType: Int?,
    hasLeanback: Boolean,
    hasLeanbackOnly: Boolean,
    hasTelevisionHardware: Boolean,
  ): Boolean =
    uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || hasLeanback || hasLeanbackOnly || hasTelevisionHardware
}
