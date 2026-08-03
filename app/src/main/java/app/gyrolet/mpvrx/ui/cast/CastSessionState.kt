/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.cast

data class CastSessionState(
  val isConnected: Boolean = false,
  val deviceName: String? = null,
  val isPlaying: Boolean = false,
  val isPaused: Boolean = false,
  val isBuffering: Boolean = false,
  val currentPosition: Long = 0L,
  val duration: Long = 0L,
  val volume: Double = 1.0,
  val isMuted: Boolean = false,
  val playbackSpeed: Float = 1.0f,
  val title: String = "",
)
