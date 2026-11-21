/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.player.controls

import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.VideoAspect

internal fun VideoAspect.icon() = when (this) {
  VideoAspect.Fit -> Icons.RoundedFilled.AspectRatio
  VideoAspect.Crop -> Icons.RoundedFilled.FitScreen
  VideoAspect.Stretch -> Icons.RoundedFilled.ZoomOutMap
}
