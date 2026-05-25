package app.gyrolet.mpvrx.domain.gpu

object GpuDriverBridge {
    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("adrenotools_bridge")
            isLibraryLoaded = true
            android.util.Log.i("GpuDriverBridge", "Successfully loaded adrenotools_bridge")
        } catch (t: Throwable) {
            isLibraryLoaded = false
            android.util.Log.e("GpuDriverBridge", "Failed to load adrenotools_bridge", t)
        }
    }

    fun isAvailable(): Boolean = isLibraryLoaded

    external fun setDriver(
        hookLibDir: String?,
        customDriverDir: String?,
        customDriverName: String?,
        fileRedirectDir: String?,
        tmpDir: String?
    ): Boolean

    external fun isAdrenoDevice(): Boolean

    external fun getGpuInfo(): String
}
