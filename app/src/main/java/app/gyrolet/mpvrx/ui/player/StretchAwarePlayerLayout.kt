/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Keeps the persisted Stretch aspect tied to the current viewport dimensions.
 *
 * PlayerActivity handles orientation changes in-place, so the ViewModel survives rotation.
 * Stretch used to store the portrait display ratio in mpv's video-aspect-override and leave that
 * value untouched after rotating to landscape, producing a tall/narrow frame after rotation.
 * Reapplying the ratio when the actual player root changes size keeps Stretch correct in both
 * orientations without affecting Fit, Crop, or user-defined custom aspect ratios.
 */
class StretchAwarePlayerLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr), KoinComponent {
  private val playerPreferences: PlayerPreferences by inject()

  override fun onSizeChanged(
    w: Int,
    h: Int,
    oldw: Int,
    oldh: Int,
  ) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w <= 0 || h <= 0 || (w == oldw && h == oldh)) return

    // Run after the new orientation's surface/layout size has settled.
    post {
      if (!PlaybackSession.isInitialized) return@post
      if (playerPreferences.lastCustomAspectRatio.get() > 0f) return@post
      if (playerPreferences.lastVideoAspect.get() != VideoAspect.Stretch) return@post

      val rotate = PlaybackSession.getPropertyInt("video-params/rotate") ?: 0
      val isVideoRotated = rotate % 180 == 90
      val viewportRatio =
        if (isVideoRotated) {
          h.toDouble() / w.toDouble()
        } else {
          w.toDouble() / h.toDouble()
        }

      PlaybackSession.setPropertyDouble("video-aspect-override", viewportRatio)
      PlaybackSession.setPropertyDouble("panscan", 0.0)
    }
  }
}
