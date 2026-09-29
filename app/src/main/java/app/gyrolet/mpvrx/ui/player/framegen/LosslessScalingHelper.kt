/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.framegen

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.utils.device.VulkanCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages the lifecycle of the Lossless Scaling Frame Generation library (Lossless.dll).
 *
 * Adapted from Eden Emulator's LosslessScalingHelper for mpvRx:
 *  - Hardware check: detects GPU device name, Vulkan driver version, 16-bit float support
 *    and Vulkan Memory Model support required by LSFG compute shaders.
 *  - Backend check: verifies that mpv is configured with Vulkan (gpu-api=vulkan, vo=gpu-next).
 *  - Install: user picks Lossless.dll -> copies to `<filesDir>/lossless/Lossless.dll` and
 *    compiles the SPIR-V shader cache (`lsfg_spirv.cache`).
 *  - Validate: checks whether installed Lossless.dll and its SPIR-V shader cache are usable.
 *  - Remove: deletes Lossless.dll and the shader cache.
 */
object LosslessScalingHelper {
    private const val TAG = "LosslessScalingHelper"

    val isAvailableInCurrentBuild: Boolean
        get() = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN

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
    /** Whether the device GPU has the hardware features (Vulkan 1.1+, float16, memory model) for LSFG. */
    val gpuSupported: StateFlow<Boolean?> = _gpuSupported.asStateFlow()

    private val _statusText = MutableStateFlow("")
    /** Human-readable status string for display in the UI. */
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private inline fun <T> safeNative(default: T, block: () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "Native call failed: ${t.message}")
            default
        }
    }

    // ── Storage Initialization ──────────────────────────────────────────────

    fun initStorage(context: Context) {
        if (!isAvailableInCurrentBuild) return
        try {
            FrameGenNative.initStorageRoot(context.filesDir.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "FrameGenNative.initStorageRoot failed: ${t.message}")
        }
    }

    fun getDestinationFile(context: Context): File {
        initStorage(context)
        val path = safeNative<String?>(null) { FrameGenNative.getLosslessDllPath() }
        return if (!path.isNullOrBlank() && File(path).isAbsolute) {
            File(path)
        } else {
            File(context.filesDir, "lossless/Lossless.dll")
        }
    }

    // ── GPU capability & Compatibility Check ────────────────────────────────

    data class GpuCompatibility(
        val gpuModel: String,
        val apiVersion: String,
        val driverVersion: String,
        val isHardwareSupported: Boolean,
        val isVulkanEnabled: Boolean,
        val isGpuNextEnabled: Boolean,
        val activeVo: String,
        val activeGpuApi: String,
    ) {
        val isRenderBackendReady: Boolean
            get() = isVulkanEnabled && isGpuNextEnabled && activeGpuApi == "vulkan" && activeVo == "gpu-next"

        val isFullyCompatible: Boolean
            get() = isAvailableInCurrentBuild && isHardwareSupported && isRenderBackendReady
    }

    @Volatile private var cachedGpuModel: String? = null
    @Volatile private var cachedApiVersion: String? = null
    @Volatile private var cachedDriverVersion: String? = null
    @Volatile private var cachedHwSupported: Boolean? = null

    private fun queryGpuProperties(context: Context): GpuPropertySnapshot {
        val m = cachedGpuModel
        val a = cachedApiVersion
        val d = cachedDriverVersion
        val h = cachedHwSupported
        if (m != null && a != null && d != null && h != null) {
            return GpuPropertySnapshot(m, a, d, h)
        }
        initStorage(context)

        // 1. Hardware Vulkan support check from Android package manager (0ms, non-blocking)
        val hwOk = VulkanCapabilities.isDeviceSupported(context)

        // 2. Vulkan API version from Android package manager feature flags
        var api = "1.3.0"
        try {
            val pm = context.packageManager
            val features = pm.systemAvailableFeatures
            val vulkanVersionFeature = features.firstOrNull {
                it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
            }
            if (vulkanVersionFeature != null) {
                val ver = vulkanVersionFeature.version
                val major = (ver shr 22) and 0x3FF
                val minor = (ver shr 12) and 0x3FF
                val patch = ver and 0xFFF
                api = "$major.$minor.$patch"
            }
        } catch (_: Throwable) {}

        // 3. GPU Model query safely via EGL offscreen context or Build fallback (never loads libvulkan.so)
        val eglModel = queryEglRenderer()
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        val model = when {
            !eglModel.isNullOrBlank() -> eglModel
            !socModel.isNullOrBlank() -> socModel
            Build.HARDWARE.isNotBlank() && Build.HARDWARE != "unknown" -> Build.HARDWARE
            else -> "Vulkan Compatible GPU"
        }
        val driver = ""

        cachedGpuModel = model
        cachedApiVersion = api
        cachedDriverVersion = driver
        cachedHwSupported = hwOk

        safeNative(Unit) {
            FrameGenNative.setGpuDeviceInfo(model, api, driver, hwOk)
        }

        return GpuPropertySnapshot(model, api, driver, hwOk)
    }

    private fun queryEglRenderer(): String? {
        return try {
            val dpy = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            if (dpy == android.opengl.EGL14.EGL_NO_DISPLAY) return null
            val vers = IntArray(2)
            if (!android.opengl.EGL14.eglInitialize(dpy, vers, 0, vers, 1)) return null
            val confAttr = intArrayOf(
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_NONE
            )
            val confs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConf = IntArray(1)
            android.opengl.EGL14.eglChooseConfig(dpy, confAttr, 0, confs, 0, 1, numConf, 0)
            val config = confs[0] ?: return null
            val ctxAttr = intArrayOf(
                android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                android.opengl.EGL14.EGL_NONE
            )
            val ctx = android.opengl.EGL14.eglCreateContext(dpy, config, android.opengl.EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
            val pbAttr = intArrayOf(
                android.opengl.EGL14.EGL_WIDTH, 1,
                android.opengl.EGL14.EGL_HEIGHT, 1,
                android.opengl.EGL14.EGL_NONE
            )
            val surf = android.opengl.EGL14.eglCreatePbufferSurface(dpy, config, pbAttr, 0)
            android.opengl.EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            val renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
            android.opengl.EGL14.eglMakeCurrent(dpy, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
            android.opengl.EGL14.eglDestroySurface(dpy, surf)
            android.opengl.EGL14.eglDestroyContext(dpy, ctx)
            android.opengl.EGL14.eglTerminate(dpy)
            renderer?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private data class GpuPropertySnapshot(
        val model: String,
        val api: String,
        val driver: String,
        val hwOk: Boolean,
    )

    fun getCompatibility(context: Context, decoderPreferences: DecoderPreferences): GpuCompatibility {
        val (model, api, driver, hwOk) = queryGpuProperties(context)
        val vulkanPref = decoderPreferences.useVulkan.get()
        val gpuNextPref = decoderPreferences.gpuNext.get()

        val vo = if (gpuNextPref) "gpu-next" else "gpu"
        val gpuApi = if (vulkanPref) "vulkan" else "opengl"

        return GpuCompatibility(
            gpuModel = model,
            apiVersion = api,
            driverVersion = driver,
            isHardwareSupported = hwOk,
            isVulkanEnabled = vulkanPref,
            isGpuNextEnabled = gpuNextPref,
            activeVo = vo,
            activeGpuApi = gpuApi,
        )
    }

    fun getCompatibility(context: Context): GpuCompatibility {
        val (model, api, driver, hwOk) = queryGpuProperties(context)
        return GpuCompatibility(
            gpuModel = model,
            apiVersion = api,
            driverVersion = driver,
            isHardwareSupported = hwOk,
            isVulkanEnabled = true,
            isGpuNextEnabled = true,
            activeVo = "gpu-next",
            activeGpuApi = "vulkan",
        )
    }

    fun checkGpuSupport(context: Context? = null): Boolean {
        if (!isAvailableInCurrentBuild) {
            _gpuSupported.value = false
            return false
        }
        if (context != null) {
            initStorage(context)
        }
        val cached = _gpuSupported.value
        if (cached != null) return cached

        val result = if (context != null) {
            VulkanCapabilities.isDeviceSupported(context)
        } else {
            safeNative(true) { FrameGenNative.supportsFrameGeneration() }
        }
        _gpuSupported.value = result
        return result
    }

    fun isReadyForFrameGeneration(decoderPreferences: DecoderPreferences): Boolean =
        isAvailableInCurrentBuild &&
          decoderPreferences.useVulkan.get() &&
          decoderPreferences.gpuNext.get() &&
          checkGpuSupport()

    // ── Library status ──────────────────────────────────────────────────────

    fun refreshStatus(context: Context? = null): Boolean {
        if (!isAvailableInCurrentBuild) {
            _installed.value = false
            _statusText.value = ""
            return false
        }
        if (context != null) {
            initStorage(context)
        }
        var ok = safeNative(false) { FrameGenNative.validateLosslessDll() == RESULT_OK }

        if (!ok && context != null) {
            val destination = getDestinationFile(context)
            if (destination.exists() && destination.length() > 0) {
                val fallbackStatus = FallbackPeParser.validate(destination)
                if (fallbackStatus == RESULT_OK) {
                    ok = true
                }
            }
        }

        _installed.value = ok
        _statusText.value = if (ok) "Lossless Scaling library installed ✓"
        else "Lossless Scaling library not installed"
        return ok
    }

    // ── Install ─────────────────────────────────────────────────────────────

    suspend fun install(context: Context, source: Uri): Int =
        withContext(Dispatchers.IO) {
            initStorage(context)
            val destination = getDestinationFile(context)
            Log.d(TAG, "Installing Lossless.dll to: ${destination.absolutePath}")

            try {
                destination.parentFile?.mkdirs()
                if (destination.exists()) {
                    destination.delete()
                }

                val copied = copyUri(context.contentResolver, source, destination)
                if (!copied || !destination.exists() || destination.length() == 0L) {
                    Log.e(TAG, "Failed to copy URI to ${destination.absolutePath}")
                    refreshStatus(context)
                    return@withContext RESULT_NOT_INSTALLED
                }

                Log.d(TAG, "Lossless.dll copied (${destination.length()} bytes). Running preparation...")

                var nativeResult = runCatching { FrameGenNative.prepareLosslessDll() }.getOrDefault(-1)
                Log.d(TAG, "FrameGenNative.prepareLosslessDll returned $nativeResult")

                if (nativeResult != RESULT_OK) {
                    Log.i(TAG, "Trying fallback PE & SPIR-V parser...")
                    val fallbackResult = FallbackPeParser.prepare(destination)
                    Log.i(TAG, "Fallback parser returned $fallbackResult")
                    if (fallbackResult == RESULT_OK) {
                        nativeResult = RESULT_OK
                    }
                }

                if (nativeResult != RESULT_OK) {
                    Log.w(TAG, "Lossless.dll preparation failed with code $nativeResult, rolling back")
                    runCatching { FrameGenNative.removeLosslessDll() }
                    if (destination.exists()) {
                        destination.delete()
                    }
                }

                refreshStatus(context)
                nativeResult
            } catch (e: Throwable) {
                Log.e(TAG, "Installation encountered exception", e)
                refreshStatus(context)
                RESULT_NOT_INSTALLED
            }
        }

    suspend fun install(contentResolver: ContentResolver, source: Uri): Int =
        withContext(Dispatchers.IO) {
            val path = runCatching { FrameGenNative.getLosslessDllPath() }.getOrNull()
            val destination = if (!path.isNullOrBlank() && File(path).isAbsolute) {
                File(path)
            } else {
                File("/data/data/app.gyrolet.mpvrx/files/lossless/Lossless.dll")
            }
            try {
                destination.parentFile?.mkdirs()
                if (destination.exists()) destination.delete()
                val copied = copyUri(contentResolver, source, destination)
                if (!copied || !destination.exists()) {
                    refreshStatus()
                    return@withContext RESULT_NOT_INSTALLED
                }
                var nativeResult = runCatching { FrameGenNative.prepareLosslessDll() }.getOrDefault(-1)
                if (nativeResult != RESULT_OK) {
                    val fallback = FallbackPeParser.prepare(destination)
                    if (fallback == RESULT_OK) nativeResult = RESULT_OK
                }
                if (nativeResult != RESULT_OK) {
                    runCatching { FrameGenNative.removeLosslessDll() }
                    if (destination.exists()) destination.delete()
                }
                refreshStatus()
                nativeResult
            } catch (e: Throwable) {
                Log.e(TAG, "Install failed", e)
                refreshStatus()
                RESULT_NOT_INSTALLED
            }
        }

    // ── Remove ──────────────────────────────────────────────────────────────

    suspend fun remove(context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        if (context != null) {
            initStorage(context)
        }
        var removed = runCatching { FrameGenNative.removeLosslessDll() }.getOrDefault(false)

        if (context != null) {
            val destination = getDestinationFile(context)
            val cache = File(destination.parentFile, "lsfg_spirv.cache")
            if (destination.exists()) destination.delete()
            if (cache.exists()) cache.delete()
            removed = true
        }

        refreshStatus(context)
        removed
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun copyUri(
        resolver: ContentResolver,
        source: Uri,
        destination: File,
    ): Boolean = runCatching {
        resolver.openInputStream(source)?.use { input: InputStream ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
        destination.exists() && destination.length() > 0
    }.onFailure { e ->
        Log.e(TAG, "copyUri failed", e)
    }.getOrDefault(false)

    // ── Fallback PE & SPIR-V Parser ──────────────────────────────────────────

    object FallbackPeParser {
        private const val DOS_MAGIC = 0x5A4D.toShort()
        private const val PE_SIGNATURE = 0x00004550
        private const val SPIRV_MAGIC = 0x07230203
        private const val CACHE_MAGIC = 0x4746534C // "LSFG"
        private const val CACHE_VERSION = 3

        private val REQUIRED_IDS = (listOf(255, 256) + (280..302).toList())

        fun validate(file: File): Int {
            return runCatching {
                val (spans, _) = parseShaderSpans(file)
                if (hasRequiredShaders(spans)) RESULT_OK else RESULT_MISSING_SHADERS
            }.getOrElse { e ->
                Log.w(TAG, "Fallback validate failed: ${e.message}")
                RESULT_NOT_PE
            }
        }

        fun prepare(file: File): Int {
            return runCatching {
                val (spans, fileBytes) = parseShaderSpans(file)
                if (!hasRequiredShaders(spans)) {
                    return RESULT_MISSING_SHADERS
                }
                val cacheFile = File(file.parentFile, "lsfg_spirv.cache")
                writeCache(cacheFile, fileBytes, spans)
                RESULT_OK
            }.getOrElse { e ->
                Log.e(TAG, "Fallback prepare failed", e)
                RESULT_TRANSLATION_FAILED
            }
        }

        private fun hasRequiredShaders(spans: Map<Int, ByteArray>): Boolean {
            return REQUIRED_IDS.all { id ->
                val targetId = id + 49
                val blob = spans[targetId] ?: return false
                isSpirvModule(blob)
            }
        }

        private fun isSpirvModule(blob: ByteArray): Boolean {
            if (blob.size < 20 || blob.size % 4 != 0) return false
            val magic = ByteBuffer.wrap(blob, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            return magic == SPIRV_MAGIC
        }

        private fun parseShaderSpans(file: File): Pair<Map<Int, ByteArray>, ByteArray> {
            val bytes = file.readBytes()
            if (bytes.size < 64) throw IllegalArgumentException("File too small")
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            if (buffer.short != DOS_MAGIC) throw IllegalArgumentException("Invalid DOS magic")
            val peOffset = buffer.getInt(0x3C)
            if (peOffset < 0 || peOffset + 4 > bytes.size) throw IllegalArgumentException("Invalid PE offset")
            if (buffer.getInt(peOffset) != PE_SIGNATURE) throw IllegalArgumentException("Invalid PE signature")

            val optionalHeaderOffset = peOffset + 4 + 20
            val optionalMagic = buffer.getShort(optionalHeaderOffset)
            val dataDirOffset = when (optionalMagic.toInt()) {
                0x010B -> optionalHeaderOffset + 96
                0x020B -> optionalHeaderOffset + 112
                else -> throw IllegalArgumentException("Unknown optional header magic")
            }

            val sectionCount = buffer.getShort(peOffset + 4 + 2).toInt() and 0xFFFF
            val optionalHeaderSize = buffer.getShort(peOffset + 4 + 16).toInt() and 0xFFFF
            val sectionTableOffset = peOffset + 4 + 20 + optionalHeaderSize

            class Section(val virtualAddr: Int, val virtualSize: Int, val rawAddr: Int, val rawSize: Int)

            val sections = mutableListOf<Section>()
            for (i in 0 until sectionCount) {
                val off = sectionTableOffset + i * 40
                if (off + 40 > bytes.size) break
                val vSize = buffer.getInt(off + 8)
                val vAddr = buffer.getInt(off + 12)
                val rSize = buffer.getInt(off + 16)
                val rAddr = buffer.getInt(off + 20)
                sections.add(Section(vAddr, vSize, rAddr, rSize))
            }

            fun rvaToFileOffset(rva: Int): Int? {
                for (s in sections) {
                    val span = maxOf(s.virtualSize, s.rawSize)
                    if (span > 0 && rva >= s.virtualAddr && rva < s.virtualAddr + span) {
                        return s.rawAddr + (rva - s.virtualAddr)
                    }
                }
                return null
            }

            val resourceRva = buffer.getInt(dataDirOffset + 2 * 8)
            if (resourceRva == 0) throw IllegalArgumentException("No resource directory")
            val resourceBase = rvaToFileOffset(resourceRva) ?: throw IllegalArgumentException("Invalid resource RVA")

            val spans = mutableMapOf<Int, ByteArray>()

            fun readResourceDirectory(dirOffset: Int): List<Pair<Int, Int>> {
                if (dirOffset + 16 > bytes.size) return emptyList()
                val namedCount = buffer.getShort(dirOffset + 12).toInt() and 0xFFFF
                val idCount = buffer.getShort(dirOffset + 14).toInt() and 0xFFFF
                val total = namedCount + idCount
                val entries = mutableListOf<Pair<Int, Int>>()
                for (i in 0 until total) {
                    val entryOff = dirOffset + 16 + i * 8
                    if (entryOff + 8 > bytes.size) break
                    val name = buffer.getInt(entryOff)
                    val data = buffer.getInt(entryOff + 4)
                    entries.add(Pair(name, data))
                }
                return entries
            }

            val typeEntries = readResourceDirectory(resourceBase)
            for ((typeId, typeData) in typeEntries) {
                // RT_RCDATA = 10
                if ((typeId and 0x7FFFFFFF) == 10 && (typeData and 0x80000000.toInt()) != 0) {
                    val nameDirOffset = resourceBase + (typeData and 0x7FFFFFFF)
                    val nameEntries = readResourceDirectory(nameDirOffset)
                    for ((resId, resData) in nameEntries) {
                        val actualId = resId and 0x7FFFFFFF
                        if ((resData and 0x80000000.toInt()) != 0) {
                            val langDirOffset = resourceBase + (resData and 0x7FFFFFFF)
                            val langEntries = readResourceDirectory(langDirOffset)
                            for ((_, langData) in langEntries) {
                                if ((langData and 0x80000000.toInt()) == 0) {
                                    val leafOffset = resourceBase + langData
                                    if (leafOffset + 8 <= bytes.size) {
                                        val dataRva = buffer.getInt(leafOffset)
                                        val dataSize = buffer.getInt(leafOffset + 4)
                                        val fileOff = rvaToFileOffset(dataRva)
                                        if (fileOff != null && fileOff + dataSize <= bytes.size && dataSize > 0) {
                                            val shaderBytes = bytes.copyOfRange(fileOff, fileOff + dataSize)
                                            spans[actualId] = shaderBytes
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
            }
            return Pair(spans, bytes)
        }

        private fun writeCache(cacheFile: File, dllBytes: ByteArray, spans: Map<Int, ByteArray>) {
            cacheFile.parentFile?.mkdirs()
            val raf = RandomAccessFile(cacheFile, "rw")
            try {
                raf.setLength(0)
                // CacheHeader: magic (4), version (4), source_size (8), source_hash (8), module_count (4) = 28 bytes
                val headerBuf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                headerBuf.putInt(CACHE_MAGIC)
                headerBuf.putInt(CACHE_VERSION)
                headerBuf.putLong(dllBytes.size.toLong())
                headerBuf.putLong(dllBytes.size.toLong() xor 0x5A5A5A5AL) // Fallback source hash
                headerBuf.putInt(REQUIRED_IDS.size)
                raf.write(headerBuf.array())

                for (id in REQUIRED_IDS) {
                    val targetId = id + 49
                    val blob = spans[targetId] ?: continue
                    val wordCount = blob.size / 4
                    val modBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    modBuf.putInt(id)
                    modBuf.putInt(wordCount)
                    raf.write(modBuf.array())
                    raf.write(blob, 0, wordCount * 4)
                }
            } finally {
                raf.close()
            }
        }
    }
}
