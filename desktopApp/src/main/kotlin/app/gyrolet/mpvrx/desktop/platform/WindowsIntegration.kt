package app.gyrolet.mpvrx.desktop.platform

import androidx.compose.ui.graphics.Color
import app.gyrolet.mpvrx.core.platform.DesktopOs
import app.gyrolet.mpvrx.core.platform.currentDesktopOs
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/** Windows-only shell integration: accent colour, dark caption and caption tint (Windows 11). */
object WindowsIntegration {
  private interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
  }

  private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
  private const val DWMWA_CAPTION_COLOR = 35
  private const val DWMWA_TEXT_COLOR = 36

  private val dwm: Dwmapi? by lazy {
    if (currentDesktopOs != DesktopOs.Windows) {
      null
    } else {
      runCatching { Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
    }
  }

  /** Personalization accent colour, used to seed the Dynamic palette like Android's wallpaper colours. */
  fun accentColor(): Color? {
    if (currentDesktopOs != DesktopOs.Windows) return null
    return runCatching {
      // Stored as 0xAABBGGRR.
      val abgr = Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\DWM", "AccentColor")
      val r = abgr and 0xFF
      val g = (abgr shr 8) and 0xFF
      val b = (abgr shr 16) and 0xFF
      Color(r, g, b)
    }.getOrNull()
  }

  fun applyCaption(hwndValue: Long, dark: Boolean, caption: Color, text: Color) {
    val api = dwm ?: return
    if (hwndValue == 0L) return
    val hwnd = WinDef.HWND(com.sun.jna.Pointer(hwndValue))
    api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(if (dark) 1 else 0), 4)
    // Win32 COLORREF is 0x00BBGGRR; older Windows ignores these attributes.
    api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(caption.toColorRef()), 4)
    api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, IntByReference(text.toColorRef()), 4)
  }

  private fun Color.toColorRef(): Int {
    val r = (red * 255).toInt()
    val g = (green * 255).toInt()
    val b = (blue * 255).toInt()
    return (b shl 16) or (g shl 8) or r
  }
}
