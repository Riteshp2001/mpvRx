package app.gyrolet.mpvrx.domain.gpu

import android.util.Log

object GpuDriverBridge {
    private const val TAG = "GpuDriverBridge"
    private var isLibraryLoaded = false

    init {
        // One-shot library load — log directly via android.util.Log because
        // this runs the first time the object is touched, which can be
        // before Koin starts (GpuLog requires Koin to be up).
        try {
            System.loadLibrary("adrenotools_bridge")
            isLibraryLoaded = true
            Log.i(TAG, "libadrenotools_bridge.so loaded")
        } catch (t: Throwable) {
            isLibraryLoaded = false
            // Expected on non-arm64 devices where the .so isn't packaged.
            Log.w(TAG, "libadrenotools_bridge.so not loaded: ${t.message}")
        }
    }

    fun isAvailable(): Boolean = isLibraryLoaded

    // Loads the custom Vulkan driver via adrenotools and arms ByteHook so any
    // subsequent dlopen("libvulkan.so") from libmpv/libplacebo/libav* returns
    // the same handle. Caller supplies absolute paths; nulls/empties skip
    // optional features.
    //   hookLibDir         - applicationInfo.nativeLibraryDir (adrenotools hook .so live here)
    //   customDriverDir    - parent dir of the custom .so (or null for system)
    //   customDriverName   - filename, e.g. "libvulkan_freedreno.so"
    //   fileRedirectDir    - dir for ADRENOTOOLS_DRIVER_FILE_REDIRECT (null disables)
    //   tmpDir             - writable scratch dir for adrenotools' linker shim
    external fun setDriver(
        hookLibDir: String?,
        customDriverDir: String?,
        customDriverName: String?,
        fileRedirectDir: String?,
        tmpDir: String?
    ): Boolean

    external fun isAdrenoDevice(): Boolean

    external fun getGpuInfo(): String

    /**
     * Returns true if a Vulkan handle is currently loaded in the native
     * bridge (custom or system). Cheap — a mutex-guarded null check.
     */
    external fun isDriverActive(): Boolean

    /**
     * Returns true once `my_vkCreateInstance` has successfully routed a
     * vkCreateInstance call through the custom driver handle. Stays false
     * if the Android Vulkan loader bypassed our hooks and the instance
     * was created against the OEM driver. UI uses this to distinguish
     * "armed and working" from "armed but silently overridden".
     */
    external fun wasHookUsed(): Boolean

    /**
     * Unhooks all ByteHook stubs and clears the driver handle so libmpv
     * stops being routed through the custom driver. Idempotent. The
     * underlying .so is NOT dlclose'd to avoid use-after-free crashes
     * from live Vulkan objects; the handle leaks for the process
     * lifetime — acceptable, since drivers only get swapped a few times
     * per session at most.
     */
    external fun unloadDriver(): Boolean
}
