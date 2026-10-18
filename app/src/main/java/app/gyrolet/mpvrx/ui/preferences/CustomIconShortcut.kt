/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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

  /** Second gradient stop, or null when the saved background is a solid colour. */
  fun savedColorEnd(context: Context): Int? =
    prefs(context).takeIf { it.getBoolean("gradient", false) }?.getInt("color_end", 0xFF000000.toInt())

  /** Gradient direction in degrees: 0 runs left to right, 90 top to bottom. */
  fun savedAngle(context: Context): Float = prefs(context).getFloat("angle", 135f)

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

  fun saveApplied(
    context: Context,
    mode: String,
    color: Int,
    mark: IconMark,
    colorEnd: Int? = null,
    angle: Float = savedAngle(context),
  ) {
    require(mode == "preset" || mode == "color")
    prefs(context).edit()
      .putString("active_mode", mode)
      .putInt("color", color or 0xFF000000.toInt())
      .putBoolean("gradient", colorEnd != null)
      .putInt("color_end", (colorEnd ?: 0) or 0xFF000000.toInt())
      .putFloat("angle", angle)
      .putString("mark", mark.name)
      .apply()
  }

  /** Start and end points of a gradient line at [angle] degrees that spans a [width] x [height] box edge to edge. */
  fun gradientLine(width: Float, height: Float, angle: Float): FloatArray {
    val radians = Math.toRadians(angle.toDouble())
    val dx = kotlin.math.cos(radians).toFloat()
    val dy = kotlin.math.sin(radians).toFloat()
    val reach = kotlin.math.abs(width / 2f * dx) + kotlin.math.abs(height / 2f * dy)
    val cx = width / 2f
    val cy = height / 2f
    return floatArrayOf(cx - dx * reach, cy - dy * reach, cx + dx * reach, cy + dy * reach)
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun render(
    context: Context,
    color: Int,
    mark: IconMark,
    size: Int = 432,
    colorEnd: Int? = null,
    angle: Float = 135f,
  ): Bitmap? {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (colorEnd != null) {
      val line = gradientLine(size.toFloat(), size.toFloat(), angle)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(line[0], line[1], line[2], line[3],
          color or 0xFF000000.toInt(), colorEnd or 0xFF000000.toInt(), Shader.TileMode.CLAMP)
      }
      canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    } else {
      canvas.drawColor(color or 0xFF000000.toInt())
    }
    val foreground = ContextCompat.getDrawable(context, mark.layer)?.mutate() ?: return null
    foreground.setBounds(0, 0, size, size)
    foreground.draw(canvas)
    return bitmap
  }

  /** Optional custom color shortcut, subject to launcher's pin confirmation. */
  fun apply(context: Context, color: Int, mark: IconMark, colorEnd: Int? = null, angle: Float = 135f): Result {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Result.Unsupported
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return Result.Unsupported
    val pinned = manager.pinnedShortcuts.firstOrNull { it.id == ID }
    if (pinned == null && !manager.isRequestPinShortcutSupported) return Result.Unsupported
    if (pinned?.isImmutable == true) return Result.Failed
    val bitmap = render(context, color, mark, colorEnd = colorEnd, angle = angle) ?: return Result.Failed
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
