/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.framegen

import app.gyrolet.mpvrx.BuildConfig

/**
 * JNI bridge to the native frame-generation layer in mpvlibAndroid.
 *
 * These methods are implemented in the AAR's JNI layer (framegen.cpp).
 * Until the AAR is rebuilt with LSFG support, stub implementations in
 * [FrameGenNativeStub] are returned so the APK compiles and the UI is
 * fully functional.
 *
 * Method signatures mirror Eden's NativeLibrary.kt declarations for
 * `supportsFrameGeneration`, `getLosslessDllPath`, `validateLosslessDll`,
 * `prepareLosslessDll`, and `removeLosslessDll`.
 */
object FrameGenNative {

    /**
     * Returns true if the device GPU has the Vulkan compute capabilities
     * required to run the LSFG optical-flow shaders.
     *
     * Native: `jboolean Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_supportsFrameGeneration`
     */
    external fun supportsFrameGeneration(): Boolean

    /**
     * Returns the absolute path where Lossless.dll should be stored in
     * internal storage (e.g. `<filesDir>/lossless/Lossless.dll`).
     *
     * Native: `jstring Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_getLosslessDllPath`
     */
    external fun getLosslessDllPath(): String

    /**
     * Checks whether the installed Lossless.dll is valid and its shader cache
     * is usable.
     *
     * @return [LosslessScalingHelper.RESULT_OK] if everything is ready,
     *         or one of the other RESULT_* error codes.
     *
     * Native: `jint Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_validateLosslessDll`
     */
    external fun validateLosslessDll(): Int

    /**
     * Parses the installed Lossless.dll, extracts SPIR-V shaders, and
     * writes them to the shader cache.  Must be called after [getLosslessDllPath]
     * resolves and the file has been copied there.
     *
     * @return [LosslessScalingHelper.RESULT_OK] on success, or an error code.
     *
     * Native: `jint Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_prepareLosslessDll`
     */
    external fun prepareLosslessDll(): Int

    /**
     * Deletes the installed Lossless.dll and its SPIR-V shader cache.
     *
     * @return `true` if removal succeeded.
     *
     * Native: `jboolean Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_removeLosslessDll`
     */
    external fun removeLosslessDll(): Boolean

    /**
     * Initializes internal storage root directory so native paths resolve to
     * `<filesDir>/lossless/Lossless.dll`.
     *
     * Native: `void Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_initStorageRoot`
     */
    external fun initStorageRoot(path: String)

    /**
     * Enables or disables real-time frame generation during playback.
     * The native layer stores this flag and the mpv present hook reads it
     * each frame to decide whether to run the LSFG compute pass.
     *
     * @param enabled Whether frame generation should be active.
     * @param multiplier Number of generated frames per real frame (2, 3, or 4).
     *
     * Native: `void Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_setFrameGenEnabled`
     */
    external fun setFrameGenEnabled(enabled: Boolean, multiplier: Int)

    /**
     * Returns the GPU model name from Vulkan physical device properties (e.g. Adreno 730).
     */
    external fun getGpuModel(): String

    /**
     * Returns the GPU Vulkan driver version.
     */
    external fun getVulkanDriverVersion(): String

    /**
     * Returns the supported Vulkan API version (e.g. 1.3.268).
     */
    external fun getVulkanApiVersion(): String

    /**
     * Returns true if GPU hardware supports Vulkan float16 + memory model required for LSFG.
     */
    external fun isGpuHardwareSupported(): Boolean

    /**
     * Updates native GPU device info cached values.
     */
    external fun setGpuDeviceInfo(name: String, apiVersion: String, driverVersion: String, isSupported: Boolean)

    init {
        if (BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN) {
            try { System.loadLibrary("mpv") } catch (t: Throwable) {}
            try { System.loadLibrary("player") } catch (t: Throwable) {}
            try { System.loadLibrary("framegen") } catch (t: Throwable) {}
        }
    }
}
