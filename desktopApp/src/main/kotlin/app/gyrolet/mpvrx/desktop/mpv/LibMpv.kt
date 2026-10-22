package app.gyrolet.mpvrx.desktop.mpv

import app.gyrolet.mpvrx.core.platform.DesktopOs
import app.gyrolet.mpvrx.core.platform.currentDesktopOs
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** libmpv client + render API (client.h / render.h), loaded through JNA on every desktop OS. */
@Suppress("FunctionName")
interface LibMpv : Library {
  fun mpv_client_api_version(): Long

  fun mpv_create(): Pointer?

  fun mpv_initialize(ctx: Pointer): Int

  fun mpv_terminate_destroy(ctx: Pointer)

  fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int

  fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int

  fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?

  fun mpv_command(ctx: Pointer, args: Array<String?>): Int

  fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int

  fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int

  fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer

  fun mpv_wakeup(ctx: Pointer)

  fun mpv_free(data: Pointer)

  fun mpv_error_string(error: Int): String

  fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int

  fun mpv_render_context_set_update_callback(ctx: Pointer, callback: RenderUpdateCallback?, data: Pointer?)

  fun mpv_render_context_update(ctx: Pointer): Long

  fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int

  fun mpv_render_context_free(ctx: Pointer)

  fun interface RenderUpdateCallback : Callback {
    fun invoke(data: Pointer?)
  }

  companion object {
    @Volatile private var cached: Result<LoadedLibMpv>? = null

    /** Loads libmpv once; failures are cached so the UI can report them instead of crashing. */
    fun load(): Result<LoadedLibMpv> =
      cached ?: synchronized(this) {
        cached ?: runCatching { locateAndLoad() }.also { cached = it }
      }

    private fun locateAndLoad(): LoadedLibMpv {
      val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
      val attempts = mutableListOf<String>()
      for (candidate in candidates()) {
        try {
          val lib = Native.load(candidate, LibMpv::class.java, options)
          return LoadedLibMpv(lib, candidate)
        } catch (error: UnsatisfiedLinkError) {
          attempts += "$candidate: ${error.message?.lineSequence()?.firstOrNull()}"
        }
      }
      throw IllegalStateException(
        "libmpv was not found. Run scripts/fetch-libmpv-windows.ps1 or set MPVRX_LIBMPV_PATH.\n" + attempts.joinToString("\n"),
      )
    }

    private fun candidates(): List<String> {
      val names =
        when (currentDesktopOs) {
          DesktopOs.Windows -> listOf("libmpv-2.dll", "mpv-2.dll", "mpv-1.dll")
          DesktopOs.MacOs -> listOf("libmpv.2.dylib", "libmpv.dylib")
          DesktopOs.Linux -> listOf("libmpv.so.2", "libmpv.so")
        }
      val directories =
        buildList {
          System.getProperty("compose.application.resources.dir")?.let { add(File(it)) }
          System.getProperty("user.dir")?.let { dir ->
            add(File(dir))
            add(File(dir, "native/${nativeFolder()}"))
            add(File(dir, "desktopApp/native/${nativeFolder()}"))
          }
          ProcessHandle.current().info().command().orElse(null)?.let { add(File(it).parentFile) }
        }
      val explicit = System.getenv("MPVRX_LIBMPV_PATH")?.takeIf { it.isNotBlank() }?.let(::File)
      val files =
        listOfNotNull(explicit?.takeIf(File::isFile)) +
          directories.filterNotNull().flatMap { dir -> names.map { File(dir, it) } }.filter(File::isFile)
      // Bare names fall back to the OS loader search path (PATH, ldconfig, DYLD paths).
      return files.map(File::getAbsolutePath).distinct() + listOf("mpv-2", "mpv")
    }

    fun nativeFolder(): String {
      val arch = if (System.getProperty("os.arch").contains("aarch64")) "arm64" else "x64"
      return when (currentDesktopOs) {
        DesktopOs.Windows -> "windows-$arch"
        DesktopOs.MacOs -> "macos-$arch"
        DesktopOs.Linux -> "linux-$arch"
      }
    }
  }
}

class LoadedLibMpv(
  val api: LibMpv,
  val source: String,
)

internal object MpvFormat {
  const val NONE = 0
  const val STRING = 1
  const val FLAG = 3
  const val INT64 = 4
  const val DOUBLE = 5
}

internal object MpvEventId {
  const val SHUTDOWN = 1
  const val LOG_MESSAGE = 2
  const val START_FILE = 6
  const val END_FILE = 7
  const val FILE_LOADED = 8
  const val VIDEO_RECONFIG = 17
  const val PLAYBACK_RESTART = 21
  const val PROPERTY_CHANGE = 22
}

internal object MpvEndReason {
  const val EOF = 0
  const val ERROR = 4
}

internal object MpvRenderParam {
  const val INVALID = 0
  const val API_TYPE = 1
  const val SW_SIZE = 17
  const val SW_FORMAT = 18
  const val SW_STRIDE = 19
  const val SW_POINTER = 20
}

internal const val MPV_RENDER_UPDATE_FRAME = 1L

/** Struct offsets for the mpv event structs on the running ABI. */
internal object MpvLayout {
  private val ptr = Native.POINTER_SIZE.toLong()
  const val EVENT_ID = 0L
  const val EVENT_ERROR = 4L
  const val EVENT_DATA = 16L
  const val PROPERTY_NAME = 0L
  val PROPERTY_FORMAT = ptr
  val PROPERTY_DATA = ptr * 2
  val RENDER_PARAM_SIZE = ptr * 2
  val RENDER_PARAM_DATA = ptr
}

/** Builds a null-terminated `mpv_render_param[]` array in native memory. */
internal class RenderParams(entries: List<Pair<Int, Pointer?>>) {
  val memory = Memory(MpvLayout.RENDER_PARAM_SIZE * (entries.size + 1)).apply { clear() }

  init {
    entries.forEachIndexed { index, (type, data) ->
      val base = index * MpvLayout.RENDER_PARAM_SIZE
      memory.setInt(base, type)
      memory.setPointer(base + MpvLayout.RENDER_PARAM_DATA, data)
    }
  }
}

internal fun cString(value: String): Memory {
  val bytes = value.toByteArray(Charsets.UTF_8)
  return Memory(bytes.size + 1L).apply {
    write(0, bytes, 0, bytes.size)
    setByte(bytes.size.toLong(), 0)
  }
}
