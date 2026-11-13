/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.fonts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * mpv's fonts.conf, which libass reads through fontconfig whenever it builds a subtitle renderer.
 * mpvlib writes a basic one. mpvRx's also searches the downloaded fallback fonts, and mpvlib leaves
 * a fonts.conf without its own marker line alone.
 */
internal object MpvFontConfig {
  private const val TAG = "MpvFontConfig"
  private const val FILE_NAME = "fonts.conf"
  private const val FALLBACK_FONTS_DIRECTORY_NAME = "fallback-fonts"
  private const val MARKER = "<!-- Written by mpvRx. Remove this line to keep your own changes. -->"
  private const val MPVLIB_MARKER_PREFIX = "<!-- Written by mpvlib."

  /** Outside mpv's fonts folder, which the user font sync clears of anything it did not copy. */
  fun fallbackFontsDirectory(context: Context): File = File(context.filesDir, FALLBACK_FONTS_DIRECTORY_NAME)

  /** Call before the mpv core starts. A fonts.conf the user wrote is left alone. */
  fun ensure(context: Context) {
    val file = File(context.filesDir, FILE_NAME)
    val fallbackDirectory = fallbackFontsDirectory(context)
    val cacheDirectory = File(context.cacheDir, "fontconfig")
    val content =
      listOf(
        MARKER,
        "<fontconfig>",
        "<dir>/system/fonts/</dir>",
        "<dir>/product/fonts/</dir>",
        "<dir>${fallbackDirectory.path}</dir>",
        "<cachedir>${cacheDirectory.path}</cachedir>",
        // Android has no API for its generic families, so name the stock ones.
        "<alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>",
        "<alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>",
        "<alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>",
        // libass draws only outlines. A colour emoji font would leave emoji blank and win over
        // the monochrome Noto Emoji that mpvRx downloads for them.
        "<selectfont><rejectfont><pattern><patelt name=\"color\"><bool>true</bool></patelt></pattern></rejectfont></selectfont>",
        "</fontconfig>",
        "",
      ).joinToString("\n")
    try {
      fallbackDirectory.mkdirs()
      cacheDirectory.mkdirs()
      if (file.exists()) {
        val existing = file.readText()
        if (existing == content || !(existing.startsWith(MARKER) || existing.startsWith(MPVLIB_MARKER_PREFIX))) return
      }
      file.writeText(content)
    } catch (error: IOException) {
      Log.w(TAG, "Could not write fonts.conf", error)
    }
  }
}
