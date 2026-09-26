/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.framegen

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages the lifecycle of the Lossless Scaling Frame Generation library (Lossless.dll).
 *
 * Mirrors Eden Emulator's LosslessScalingHelper, adapted for mpvRx:
 *  - Install:  user picks the Lossless.dll file → we copy it to internal storage
 *              and call native [FrameGenNative.prepareLosslessDll] to validate +
 *              extract SPIR-V shaders into a local cache.
 *  - Validate: [FrameGenNative.validateLosslessDll] checks the cached shader state.
 *  - Remove:   deletes the library and shader cache via [FrameGenNative.removeLosslessDll].
 *
 * GPU support is queried once via [FrameGenNative.supportsFrameGeneration]; the result
 * is cached because it cannot change at runtime.
 *
 * All methods are safe to call from any thread.  State is exposed as [StateFlow]s for
 * Compose and coroutine consumers.
 */
object LosslessScalingHelper {

    /** Result codes matching the native LosslessStatus enum. */
    const val RESULT_OK = 0
    const val RESULT_NOT_INSTALLED = 1
    const val RESULT_UNREADABLE = 2
    const val RESULT_NOT_PE = 3
    const val RESULT_MISSING_SHADERS = 4
    const val RESULT_TRANSLATION_FAILED = 5
    const val RESULT_CACHE_UNUSABLE = 6

    private val _installed = MutableStateFlow(false)
    /** Whether a valid, prepared Lossless Scaling library is present. */
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val _gpuSupported = MutableStateFlow<Boolean?>(null)
    /** Whether the device GPU can run the LSFG compute shaders (Vulkan required). */
    val gpuSupported: StateFlow<Boolean?> = _gpuSupported.asStateFlow()

    private val _statusText = MutableStateFlow("")
    /** Human-readable status string for display in the UI. */
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    // ── GPU capability ──────────────────────────────────────────────────────

    /**
     * Queries GPU support once and caches the result.  Subsequent calls return
     * the cached value immediately (no JNI overhead).
     */
    fun checkGpuSupport(): Boolean {
        val cached = _gpuSupported.value
        if (cached != null) return cached
        val result = FrameGenNative.supportsFrameGeneration()
        _gpuSupported.value = result
        return result
    }

    // ── Library status ──────────────────────────────────────────────────────

    /**
     * Reads the current install status from native and updates [installed] /
     * [statusText].  Call this on startup and after any install/remove.
     */
    fun refreshStatus(): Boolean {
        val ok = FrameGenNative.validateLosslessDll() == RESULT_OK
        _installed.value = ok
        _statusText.value = if (ok) "Lossless Scaling library installed ✓"
                            else "Lossless Scaling library not installed"
        return ok
    }

    // ── Install ─────────────────────────────────────────────────────────────

    /**
     * Copies the user-selected URI to the library destination path obtained from
     * [FrameGenNative.getLosslessDllPath], then calls [FrameGenNative.prepareLosslessDll]
     * to validate the PE file and build the SPIR-V shader cache.
     *
     * Must be called from a coroutine (suspends on IO).
     *
     * @return One of the [RESULT_OK] … [RESULT_CACHE_UNUSABLE] constants.
     */
    suspend fun install(contentResolver: android.content.ContentResolver, source: Uri): Int =
        withContext(Dispatchers.IO) {
            val destinationPath = FrameGenNative.getLosslessDllPath()
            val destination = File(destinationPath)
            destination.parentFile?.mkdirs()

            val copied = copyUri(contentResolver, source, destination)
            if (!copied) {
                refreshStatus()
                return@withContext RESULT_NOT_INSTALLED
            }

            val result = FrameGenNative.prepareLosslessDll()
            if (result != RESULT_OK) {
                // Roll back — library is invalid
                FrameGenNative.removeLosslessDll()
            }
            refreshStatus()
            result
        }

    // ── Remove ──────────────────────────────────────────────────────────────

    /**
     * Deletes the installed library and its shader cache.
     *
     * @return `true` if the removal succeeded.
     */
    suspend fun remove(): Boolean = withContext(Dispatchers.IO) {
        val removed = FrameGenNative.removeLosslessDll()
        refreshStatus()
        removed
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun copyUri(
        resolver: android.content.ContentResolver,
        source: Uri,
        destination: File,
    ): Boolean = runCatching {
        resolver.openInputStream(source)?.use { input: InputStream ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
        destination.exists() && destination.length() > 0
    }.getOrDefault(false)
}
