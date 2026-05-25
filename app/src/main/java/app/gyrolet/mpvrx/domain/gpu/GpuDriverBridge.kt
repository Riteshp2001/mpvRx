package app.gyrolet.mpvrx.domain.gpu

object GpuDriverBridge {
    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("adrenotools_bridge")
            isLibraryLoaded = true
        } catch (_: Throwable) {
            isLibraryLoaded = false
        }
    }

    fun isAvailable(): Boolean = isLibraryLoaded

    external fun setDriver(
        hookLibDir: String?,
        customDriverDir: String?,
        customDriverName: String?,
        fileRedirectDir: String?
    ): Boolean

    external fun isAdrenoDevice(): Boolean

    external fun getGpuInfo(): String
}
