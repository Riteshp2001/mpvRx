package app.gyrolet.mpvrx.utils

import android.util.Log

/**
 * Provides access to Freedreno/Turnip driver configuration through JNI bindings.
 * Logic migrated from Eden Android to ensure robust integration and stability.
 */
object NativeFreedrenoConfig {
    private const val TAG = "NativeFreedreno"
    private var isLibraryLoaded = false

    init {
        // One-shot library load — direct android.util.Log (no Koin dependency
        // here, since this can run before Koin is up).
        try {
            System.loadLibrary("adrenotools_bridge")
            isLibraryLoaded = true
            Log.i(TAG, "libadrenotools_bridge.so loaded for freedreno bridge")
        } catch (t: Throwable) {
            isLibraryLoaded = false
            Log.w(TAG, "libadrenotools_bridge.so not loaded: ${t.message}")
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
