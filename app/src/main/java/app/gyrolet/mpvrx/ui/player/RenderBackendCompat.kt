/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.preferences.DecoderPreferences
import org.koin.core.context.GlobalContext

/** Renderer capability lookup that also works while libmpv is still in initOptions(). */
internal object RenderBackendCompat {
  fun isGpuNextOutput(): Boolean {
    when (PlaybackSession.getPropertyString("vo")) {
      "gpu-next" -> return true
      "gpu" -> return false
      // `vo=null` is intentionally used while Android has no Surface. In that state the renderer
      // choice has not changed, so fall through to the persisted backend selection below.
    }

    // Before MPVLib.init() properties are not readable yet, so mirror the backend-selection branch
    // from MPVView using persisted preferences. This specifically catches the normal gpu/OpenGL
    // path where hdr-toys' gpu-next shader assumptions otherwise produce washed output.
    return runCatching {
      val preferences = GlobalContext.get().get<DecoderPreferences>()
      val gpuNext = preferences.gpuNext.get()
      if (!gpuNext) return@runCatching false

      val anime4kActive =
        preferences.enableAnime4K.get() && preferences.anime4kMode.get() != "OFF"
      val vulkanRequested = preferences.useVulkan.get()

      // MPVView deliberately falls back to legacy gpu/OpenGL for this known unsupported pairing.
      !(anime4kActive && !vulkanRequested)
    }.getOrDefault(false)
  }
}
