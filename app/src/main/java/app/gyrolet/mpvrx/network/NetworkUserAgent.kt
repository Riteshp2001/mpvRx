/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.network

import android.content.Context
import android.os.Build
import android.os.Looper
import android.webkit.WebSettings
import app.gyrolet.mpvrx.BuildConfig

/** Uses the installed Android WebView identity when available, with a truthful app fallback. */
object NetworkUserAgent {
  @Volatile private var cachedWebViewUserAgent: String? = null

  fun resolve(
    context: Context,
    customUserAgent: String? = null,
  ): String {
    customUserAgent?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    cachedWebViewUserAgent?.let { return it }

    if (Looper.myLooper() == Looper.getMainLooper()) {
      runCatching { WebSettings.getDefaultUserAgent(context.applicationContext) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?.let { userAgent ->
          cachedWebViewUserAgent = userAgent
          return userAgent
        }
    }

    return "mpvRx/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL})"
  }
}
