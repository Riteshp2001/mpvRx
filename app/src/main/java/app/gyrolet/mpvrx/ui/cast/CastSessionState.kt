/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
