/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.MainActivity
import app.gyrolet.mpvrx.R
import java.io.File

/** Runtime custom colors use a pinned shortcut; app-drawer icons remain packaged. */
internal object CustomIconShortcut {
  private const val ID = "mpvrx.custom-background"
  private const val PREFERENCES = "custom_icon_shortcut"
  private const val LEGACY_IMAGE_FILE = "custom_launcher_background.png"

  enum class Result { Requested, Updated, Unsupported, Failed }

  fun savedColor(context: Context): Int =
    prefs(context).getInt("color", 0xFFC4F5E2.toInt())

  fun savedMark(context: Context, fallback: IconMark): IconMark {
    val name = prefs(context).getString("mark", null)
    return IconMark.entries.firstOrNull { it.name == name } ?: fallback
  }

  /** Migrate saved image selections back to color and discard the obsolete private image. */
  fun appliedMode(context: Context): String {
    val preferences = prefs(context)
    val mode = preferences.getString("active_mode", "preset")
    val legacyImage = File(context.filesDir, LEGACY_IMAGE_FILE)
    if (legacyImage.exists()) runCatching { legacyImage.delete() }
    if (mode == "image") {
      preferences.edit().putString("active_mode", "color").apply()
      return "color"
    }
    return if (mode == "color") "color" else "preset"
  }

  fun saveApplied(context: Context, mode: String, color: Int, mark: IconMark) {
    require(mode == "preset" || mode == "color")
    prefs(context).edit()
      .putString("active_mode", mode)
      .putInt("color", color or 0xFF000000.toInt())
      .putString("mark", mark.name)
      .apply()
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun render(context: Context, color: Int, mark: IconMark, size: Int = 432): Bitmap? {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(color or 0xFF000000.toInt())
    val foreground = ContextCompat.getDrawable(context, mark.layer)?.mutate() ?: return null
    foreground.setBounds(0, 0, size, size)
    foreground.draw(canvas)
    return bitmap
  }

  /**
   * The icon cut to the device's icon shape, the way launchers show the built-in icons. Recents
   * draws task icons as given, so [render]'s full-bleed adaptive layer would show as a plain square.
   */
  fun renderMasked(context: Context, color: Int, mark: IconMark, size: Int): Bitmap? {
    val foreground = ContextCompat.getDrawable(context, mark.layer)?.mutate() ?: return null
    val icon = AdaptiveIconDrawable(ColorDrawable(color or 0xFF000000.toInt()), foreground)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
      icon.setBounds(0, 0, size, size)
      icon.draw(Canvas(it))
    }
  }

  /** Optional custom color shortcut, subject to launcher's pin confirmation. */
  fun apply(context: Context, color: Int, mark: IconMark): Result {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Result.Unsupported
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return Result.Unsupported
    val pinned = manager.pinnedShortcuts.firstOrNull { it.id == ID }
    if (pinned == null && !manager.isRequestPinShortcutSupported) return Result.Unsupported
    if (pinned?.isImmutable == true) return Result.Failed
    val bitmap = render(context, color, mark) ?: return Result.Failed
    val shortcut = ShortcutInfo.Builder(context, ID)
      .setShortLabel(context.getString(R.string.app_name))
      .setIcon(Icon.createWithAdaptiveBitmap(bitmap))
      .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
      .build()
    return if (pinned != null) {
      if (!manager.updateShortcuts(listOf(shortcut))) Result.Failed
      else {
        if (!pinned.isEnabled) manager.enableShortcuts(listOf(ID))
        Result.Updated
      }
    } else {
      if (manager.requestPinShortcut(shortcut, null)) Result.Requested else Result.Failed
    }
  }
}
