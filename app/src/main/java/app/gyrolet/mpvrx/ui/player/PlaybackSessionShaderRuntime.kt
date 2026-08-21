/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.domain.hdr.MpvShaderRuntime

/** Bridges the domain-layer shader pipeline contract onto the process-wide playback session. */
object PlaybackSessionShaderRuntime : MpvShaderRuntime {
  override fun command(vararg args: String) {
    PlaybackSession.command(*args)
  }

  override fun getPropertyString(property: String): String? = PlaybackSession.getPropertyString(property)

  override fun setPropertyString(
    property: String,
    value: String,
  ) {
    PlaybackSession.setPropertyString(property, value)
  }
}
