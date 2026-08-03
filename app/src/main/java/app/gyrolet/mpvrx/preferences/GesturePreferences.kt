/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.ui.player.SingleActionGesture

class GesturePreferences(
  preferenceStore: PreferenceStore,
) {
  val doubleTapToSeekDuration = preferenceStore.getInt("double_tap_to_seek_duration", 10)
  val doubleTapSeekAreaWidth = preferenceStore.getInt("double_tap_seek_area_width", 35)
  val leftSingleActionGesture = preferenceStore.getEnum("left_double_tap_gesture", SingleActionGesture.Seek)
  val centerSingleActionGesture = preferenceStore.getEnum("center_drag_gesture", SingleActionGesture.PlayPause)
  val rightSingleActionGesture = preferenceStore.getEnum("right_drag_gesture", SingleActionGesture.Seek)
  val useSingleTapForCenter = preferenceStore.getBoolean("use_single_tap_for_center", false)
  val mediaPreviousGesture = preferenceStore.getEnum("meda_previous_gesture", SingleActionGesture.Seek)
  val mediaPlayGesture = preferenceStore.getEnum("media_play_gesture", SingleActionGesture.PlayPause)
  val mediaNextGesture = preferenceStore.getEnum("media_next_gesture", SingleActionGesture.Seek)
  val tapThumbnailToSelect = preferenceStore.getBoolean("tap_thumbnail_to_select", false)
  val centerVerticalSubtitlePositionGesture =
    preferenceStore.getBoolean("center_vertical_subtitle_position_gesture", true)
  val enableCenterSwipeUpGesture =
    preferenceStore.getBoolean("enable_center_swipe_up_gesture", true)
  val pinchToZoomSubtitles =
    preferenceStore.getBoolean("pinch_to_zoom_subtitles", true)
  val swipeSubtitlesToSeekDialog =
    preferenceStore.getBoolean("swipe_subtitles_to_seek_dialog", true)
  val swipeSubtitlesInvertDirection =
    preferenceStore.getBoolean("swipe_subtitles_invert_direction", false)
}
