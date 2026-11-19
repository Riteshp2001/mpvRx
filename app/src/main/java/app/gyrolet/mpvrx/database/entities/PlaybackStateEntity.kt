/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class PlaybackStateEntity(
  @PrimaryKey val mediaTitle: String,
  val lastPosition: Int, // in seconds
  val playbackSpeed: Double,
  val videoZoom: Float = 0f,
  val sid: Int,
  val secondarySid: Int = -1, // Secondary subtitle track ID (-1 means disabled)
  val subDelay: Int,
  val subSpeed: Double,
  val aid: Int,
  val audioDelay: Int,
  val timeRemaining: Int = 0, // in seconds (duration - lastPosition)
  val externalSubtitles: String = "", // Comma-separated list of external subtitle URIs
  val hasBeenWatched: Boolean = false, // Persistent flag: true if video has ever reached the watched threshold
  val newLabelOverride: Boolean? = null,
  // True only when [manualAudioId] holds a choice the user made by hand for this file.
  // Selections mpv or the automatic correction produced are never stored as manual: on the
  // next open they are decided again by the preferred-language rules. Rows written before
  // this column existed keep the default false, so old saved [aid] values are not manual.
  val hasManualAudioSelection: Boolean = false,
  // The hand-picked audio track: >0 is an mpv track id, 0 is audio turned off by hand,
  // -1 is no manual record (only meaningful while [hasManualAudioSelection] is true).
  val manualAudioId: Int = -1,
  // Separator-joined ("|") URIs of external audio tracks added by hand, re-added on the
  // next open before the manual selection is restored, mirroring [externalSubtitles].
  val externalAudio: String = "",
)
