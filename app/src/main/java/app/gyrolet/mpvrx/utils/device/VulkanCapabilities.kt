/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.gyrolet.mpvrx.BuildConfig

object VulkanCapabilities {
  private const val TAG = "VulkanCapabilities"
  private const val MIN_OPEN_GL_ES_VERSION = 0x00030001
  private const val MIN_VULKAN_VERSION = 0x00403000

  val isBackendIncluded: Boolean
    get() = BuildConfig.MPV_SUPPORTS_VULKAN

  /** Returns whether this APK and the device can both use mpv's Vulkan renderer. */
  fun isAvailable(context: Context): Boolean = isBackendIncluded && isDeviceSupported(context)

  /** Returns whether the device meets mpvRx's Vulkan renderer requirements. */
  fun isDeviceSupported(context: Context): Boolean {
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Log.d(TAG, "Vulkan unavailable: Android API ${Build.VERSION.SDK_INT} is below 33")
        return false
      }

      val packageManager = context.packageManager
      val configInfo = packageManager.systemAvailableFeatures.firstOrNull { it.name == null }
      val openGlEsVersion = configInfo?.reqGlEsVersion ?: 0
      if (openGlEsVersion < MIN_OPEN_GL_ES_VERSION) {
        Log.d(TAG, "Vulkan unavailable: OpenGL ES version is below 3.1")
        return false
      }

      val supported =
        packageManager.hasSystemFeature(
          PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
          MIN_VULKAN_VERSION,
        )
      Log.d(TAG, "Device Vulkan 1.3 support: $supported")
      return supported
    } catch (error: Exception) {
      Log.e(TAG, "Failed to check device Vulkan support", error)
      return false
    }
  }
}
