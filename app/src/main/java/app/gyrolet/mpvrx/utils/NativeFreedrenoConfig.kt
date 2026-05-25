package app.gyrolet.mpvrx.utils

/**
 * Provides access to Freedreno/Turnip driver configuration through JNI bindings.
 * Logic migrated from Eden Android to ensure robust integration and stability.
 */
object NativeFreedrenoConfig {
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

    @JvmStatic
    @Synchronized
    external fun setFreedrenoBasePath(basePath: String)

    @JvmStatic
    @Synchronized
    external fun initializeFreedrenoConfig()

    @JvmStatic
    @Synchronized
    external fun saveFreedrenoConfig()

    @JvmStatic
    @Synchronized
    external fun reloadFreedrenoConfig()

    @JvmStatic
    @Synchronized
    external fun setFreedrenoEnv(varName: String, value: String): Boolean

    @JvmStatic
    @Synchronized
    external fun getFreedrenoEnv(varName: String): String

    @JvmStatic
    @Synchronized
    external fun isFreedrenoEnvSet(varName: String): Boolean

    @JvmStatic
    @Synchronized
    external fun clearFreedrenoEnv(varName: String): Boolean

    @JvmStatic
    @Synchronized
    external fun clearAllFreedrenoEnv()

    @JvmStatic
    @Synchronized
    external fun getFreedrenoEnvSummary(): String
}
