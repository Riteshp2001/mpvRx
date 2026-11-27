/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.components.AppToastType

/**
 * `vo=mediacodec_embed`: MediaCodec draws decoded frames straight to the video Surface and mpv's
 * renderer is skipped. Only HW+ decoding can feed it, and shaders, HDR modes, video filters and
 * motion interpolation never reach the picture. FongMi's mpv draws subtitles and OSD on its
 * overlay Surface; the other builds show none.
 */
internal object MediaCodecEmbed {
  const val VO = "mediacodec_embed"

  /** Whether this player was started on it, from the setting or from mpv.conf. */
  fun isActive(): Boolean = PlaybackSession.videoOutput == VO

  /** Returns true, and says why in a toast, when [feature] cannot work on this output. */
  fun blocks(
    context: Context,
    @StringRes feature: Int,
  ): Boolean {
    if (!isActive()) return false
    AppToast
      .makeText(
        context,
        context.getString(R.string.player_mediacodec_embed_unsupported, context.getString(feature)),
        AppToast.LENGTH_LONG,
      ).withType(AppToastType.Warning)
      .show()
    return true
  }
}
