package app.gyrolet.mpvrx.desktop.mpv

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * mpv software render context drawing straight into a Skia bitmap's pixel memory.
 * Software output keeps Compose overlays fully composable above video on every desktop OS;
 * a GPU interop path can replace this class later without touching callers.
 */
internal class SoftwareRenderContext private constructor(
  private val api: LibMpv,
  val handle: Pointer,
) {
  private val format = cString("bgr0")
  private val sizeMemory = Memory(8)
  private val strideMemory = Memory(Native.SIZE_T_SIZE.toLong())
  private var bitmap: Bitmap? = null

  fun setUpdateCallback(callback: LibMpv.RenderUpdateCallback?) {
    api.mpv_render_context_set_update_callback(handle, callback, null)
  }

  fun update(): Long = api.mpv_render_context_update(handle)

  /** Renders the current frame at [width]x[height] and returns an immutable snapshot. */
  fun render(width: Int, height: Int): Image? {
    if (width <= 0 || height <= 0) return null
    val target =
      bitmap?.takeIf { it.width == width && it.height == height }
        ?: Bitmap().also { fresh ->
          bitmap?.close()
          fresh.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
          bitmap = fresh
        }
    val pixels = target.peekPixels() ?: return null
    sizeMemory.setInt(0, width)
    sizeMemory.setInt(4, height)
    if (Native.SIZE_T_SIZE == 8) strideMemory.setLong(0, target.rowBytes.toLong()) else strideMemory.setInt(0, target.rowBytes)
    val params =
      RenderParams(
        listOf(
          MpvRenderParam.SW_SIZE to sizeMemory,
          MpvRenderParam.SW_FORMAT to format,
          MpvRenderParam.SW_STRIDE to strideMemory,
          MpvRenderParam.SW_POINTER to Pointer(pixels.addr),
        ),
      )
    val result = api.mpv_render_context_render(handle, params.memory)
    if (result < 0) return null
    target.notifyPixelsChanged()
    // Copies pixels, so the bitmap can be reused for the next frame immediately.
    return Image.makeFromBitmap(target)
  }

  fun release() {
    api.mpv_render_context_set_update_callback(handle, null, null)
    api.mpv_render_context_free(handle)
    bitmap?.close()
    bitmap = null
  }

  companion object {
    fun create(api: LibMpv, mpv: Pointer): SoftwareRenderContext {
      val apiType = cString("sw")
      val params = RenderParams(listOf(MpvRenderParam.API_TYPE to apiType))
      val ref = PointerByReference()
      val code = api.mpv_render_context_create(ref, mpv, params.memory)
      check(code >= 0) { "mpv_render_context_create failed: ${api.mpv_error_string(code)}" }
      return SoftwareRenderContext(api, ref.value)
    }
  }
}
