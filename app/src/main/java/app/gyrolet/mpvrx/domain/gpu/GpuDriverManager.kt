package app.gyrolet.mpvrx.domain.gpu

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import app.gyrolet.mpvrx.utils.GpuLog
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

private const val TAG = "GpuDriverManager"

// ELF magic bytes: 0x7f 'E' 'L' 'F'. Any extracted .so we plan to dlopen
// MUST start with these four bytes — otherwise the package is corrupt or
// the user dropped in a non-driver zip and we'd crash inside the linker.
private val ELF_MAGIC = byteArrayOf(0x7f, 0x45, 0x4c, 0x46)

class GpuDriverManager(private val context: Context, private val okHttpClient: OkHttpClient) {
    private val driversDir = File(context.filesDir, "gpu_drivers")
    private val json = Json { ignoreUnknownKeys = true }

    // Installed-drivers cache. Reads are cheap individually but happen on
    // startup AND every screen open AND every driver selection. Invalidated
    // on install/delete AND auto-invalidated via mtime signature when the
    // gpu_drivers/ contents change externally (manual file-manager edits).
    @Volatile private var installedCache: List<GpuDriver>? = null
    @Volatile private var installedSignature: Map<String, Long>? = null

    // Remote-fetch cache, persisted to a temp file in cacheDir so it survives
    // app restarts. GitHub anon API allows 60 req/hr/IP and we issue 6
    // requests per fetch — without this, ~10 screen opens exhaust the limit.
    private val remoteCacheFile = File(context.cacheDir, "gpu_remote_drivers_cache.json")
    private val remoteCacheTtlMs = 60 * 60 * 1000L // 1 hour

    init {
        if (!driversDir.exists()) {
            driversDir.mkdirs()
        }
    }

    private val repoList = listOf(
        DriverRepo("Eden Adreno Tools", "eden-emulator/libadrenotools", 0),
        DriverRepo("Mr. Purple Turnip", "MrPurple666/purple-turnip", 1),
        DriverRepo("GameHub Adreno 8xx", "crueter/GameHub-8Elite-Drivers", 2),
        DriverRepo("KIMCHI Turnip", "K11MCH1/AdrenoToolsDrivers", 3, true),
        DriverRepo("Weab-Chan Freedreno", "Weab-chan/freedreno_turnip-CI", 4),
        DriverRepo("Whitebelyash Turnip", "whitebelyash/freedreno_turnip-CI", 5),
    )

    private val driverMap = listOf(
        IntRange(Int.MIN_VALUE, 9) to "Unsupported",
        IntRange(10, 99) to "KIMCHI Latest",
        IntRange(100, 599) to "Unsupported",
        IntRange(600, 639) to "Mr. Purple EOL-24.3.4",
        IntRange(640, 699) to "Mr. Purple T19",
        IntRange(700, 710) to "KIMCHI 25.2.0_r5",
        IntRange(711, 799) to "Mr. Purple T23",
        IntRange(800, 899) to "GameHub Adreno 8xx",
        IntRange(900, Int.MAX_VALUE) to "Unsupported"
    )

    fun getGpuModel(): String {
        if (!isArchitectureSupported() || !GpuDriverBridge.isAvailable()) {
            return "Architecture Not Supported (${Build.SUPPORTED_ABIS.firstOrNull()})"
        }
        return runCatching { GpuDriverBridge.getGpuInfo() }.getOrDefault("Unknown GPU (Bridge Error)")
    }

    fun isAdrenoSupported(): Boolean {
        if (!isArchitectureSupported() || !GpuDriverBridge.isAvailable()) {
            return false
        }
        return runCatching { GpuDriverBridge.isAdrenoDevice() }.getOrDefault(false)
    }

    private fun isArchitectureSupported(): Boolean {
        return Build.SUPPORTED_ABIS.contains("arm64-v8a")
    }

    fun parseAdrenoModel(gpuModel: String): Int {
        if (gpuModel.isEmpty()) return 0
        val modelList = gpuModel.split(" ")
        val adrenoIndex = modelList.indexOfFirst { it.contains("Adreno", ignoreCase = true) }
        if (adrenoIndex == -1) return 0
        for (i in adrenoIndex + 1 until modelList.size) {
            val part = modelList[i].removePrefix("(TM)").trim()
            if (part.isEmpty()) continue
            try {
                if (part.startsWith("A", ignoreCase = true)) {
                    return part.substring(1).toInt()
                }
                val modelNum = part.filter { it.isDigit() }.toIntOrNull()
                if (modelNum != null) return modelNum
            } catch (e: Exception) { }
        }
        return 0
    }

    fun getRecommendedDriver(adrenoModel: Int): String {
        return driverMap.firstOrNull { adrenoModel in it.first }?.second ?: "Unsupported"
    }

    /** Total on-disk size of the driver dir, in bytes. 0 if not present. */
    fun getDriverDiskSize(driverPath: String): Long {
        val dir = File(driverPath)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Sum of all installed driver sizes, in bytes. Used by the UI summary. */
    fun getTotalDriversDiskSize(): Long =
        driversDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Resolves the Adreno GPU model number for the current device. Used by
     * the recommendation UI (DeviceInfoHub + fetch sheet). Prefers a
     * Build.SOC_MODEL / Build.BOARD lookup over parsing the native bridge's
     * string, because the bridge currently only reports the generic
     * "Qualcomm Adreno GPU Detected" without a model number — which would
     * always parse to 0 and always recommend "Unsupported".
     */
    fun getAdrenoModelNumber(): Int {
        val fromBuild = app.gyrolet.mpvrx.utils.AdrenoLookup.resolve()
        if (fromBuild > 0) return fromBuild
        return parseAdrenoModel(getGpuModel())
    }

    fun getInstalledDriversSync(): List<GpuDriver> {
        // Fast path: compute the current mtime signature (cheap: 1 listFiles +
        // N stat calls). If it matches the cached snapshot the contents
        // haven't changed — return memoized list without reading any JSON.
        // This also catches manual file-manager edits the user might do.
        val currentSig = computeInstalledSignature()
        val cached = installedCache
        if (cached != null && installedSignature == currentSig) {
            GpuLog.v(TAG) { "installed-cache HIT (${cached.size} drivers, sig=$currentSig)" }
            return cached
        }
        GpuLog.d(TAG) { "installed-cache MISS — rescanning $driversDir (cachedSig=$installedSignature, currentSig=$currentSig)" }

        val drivers = mutableListOf<GpuDriver>()
        drivers.add(GpuDriver("system", "System Default", "Use the built-in system GPU driver", isSystem = true, driverPath = "", vulkanLibName = ""))

        driversDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val metaFile = File(dir, "meta.json")
            if (metaFile.exists()) {
                try {
                    val metaContent = metaFile.readText()
                    val metaJson = json.parseToJsonElement(metaContent).jsonObject
                    val (actualDir, actualLib) = resolveDriverLibAndDir(
                        dir,
                        metaJson["vulkanLibName"]?.jsonPrimitive?.content ?: "libvulkan_freedreno.so",
                    )
                    // installDate: prefer the value written into meta.json
                    // at install time; fall back to the dir's mtime so even
                    // older installs (pre-installDate-field) show something.
                    val installDate = metaJson["installDate"]?.jsonPrimitive?.longOrNull
                        ?: dir.lastModified()
                    val totalBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

                    drivers.add(GpuDriver(
                        id = dir.name,
                        name = metaJson["name"]?.jsonPrimitive?.content ?: dir.name,
                        description = metaJson["description"]?.jsonPrimitive?.content ?: "",
                        author = metaJson["author"]?.jsonPrimitive?.content ?: "",
                        version = metaJson["driverVersion"]?.jsonPrimitive?.content ?: "",
                        vendor = metaJson["vendor"]?.jsonPrimitive?.content ?: "",
                        driverPath = actualDir,
                        vulkanLibName = actualLib,
                        installDate = installDate,
                        fileSizeBytes = totalBytes,
                        minApi = metaJson["minApi"]?.jsonPrimitive?.content ?: "",
                    ))
                } catch (e: Exception) {
                    GpuLog.e(TAG, "Failed to parse meta.json in ${dir.name}", e)
                }
            }
        }
        installedCache = drivers
        installedSignature = currentSig
        GpuLog.d(TAG) { "installed-cache rebuilt: ${drivers.size} drivers (incl. system)" }
        return drivers
    }

    // Cheap snapshot of the gpu_drivers/ dir: { subfolderName -> mtime }.
    // Subfolder mtime changes whenever any file inside (including meta.json)
    // is added/removed/replaced, so this catches external edits without
    // having to actually read any file contents.
    private fun computeInstalledSignature(): Map<String, Long> {
        return driversDir.listFiles()
            ?.filter { it.isDirectory }
            ?.associate { it.name to it.lastModified() }
            ?: emptyMap()
    }

    suspend fun getInstalledDrivers(): List<GpuDriver> = withContext(Dispatchers.IO) {
        getInstalledDriversSync()
    }

    suspend fun fetchRemoteDriverGroups(forceRefresh: Boolean = false): List<RemoteDriverGroup> = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            GpuLog.d(TAG) { "fetchRemoteDriverGroups: forceRefresh — bypassing disk cache" }
        } else {
            readRemoteCache()?.let {
                GpuLog.v(TAG) { "remote-cache HIT (${it.size} groups, ${it.sumOf { g -> g.releases.size }} releases)" }
                return@withContext it
            }
            GpuLog.d(TAG) { "remote-cache MISS — fetching from GitHub" }
        }
        val fetchStart = SystemClock.uptimeMillis()
        val groups = repoList.map { repo ->
            async {
                val perRepoStart = SystemClock.uptimeMillis()
                try {
                    val request = Request.Builder()
                        // per_page=10 — only the 10 most-recent releases per
                        // repo. Default is 30; cuts response size ~3× and
                        // keeps anonymous rate-limit headroom.
                        .url("https://api.github.com/repos/${repo.path}/releases?per_page=10")
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "MpvRx-GpuFetcher")
                        .build()

                    val response = okHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    
                    val content = response.body?.string() ?: ""
                    val releasesJson = json.parseToJsonElement(content).jsonArray
                    
                    val remoteReleases = releasesJson.mapIndexed { index, release ->
                        val releaseObj = release.jsonObject
                        val tagName = releaseObj["tag_name"]?.jsonPrimitive?.content ?: ""
                        val titleName = releaseObj["name"]?.jsonPrimitive?.content ?: ""
                        val title = if (repo.useTagName) tagName else titleName
                        val prerelease = releaseObj["prerelease"]?.jsonPrimitive?.boolean ?: false
                        
                        val assets = releaseObj["assets"]?.jsonArray
                        val remoteDrivers = assets?.mapNotNull { asset ->
                            val assetObj = asset.jsonObject
                            val assetName = assetObj["name"]?.jsonPrimitive?.content ?: ""
                            if (assetName.endsWith(".zip")) {
                                RemoteGpuDriver(
                                    name = assetName,
                                    downloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content ?: ""
                                )
                            } else null
                        } ?: emptyList()

                        RemoteRelease(
                            title = title,
                            version = tagName,
                            isLatest = index == 0 && !prerelease,
                            drivers = remoteDrivers
                        )
                    }.filter { it.drivers.isNotEmpty() }

                    val group = RemoteDriverGroup(repo.name, remoteReleases, repo.sort)
                    GpuLog.v(TAG) { "fetched '${repo.name}' in ${SystemClock.uptimeMillis() - perRepoStart}ms: ${remoteReleases.size} releases" }
                    group
                } catch (e: Exception) {
                    GpuLog.w(TAG, "Failed to fetch from ${repo.name} after ${SystemClock.uptimeMillis() - perRepoStart}ms: ${e.message}")
                    RemoteDriverGroup(repo.name, emptyList(), repo.sort)
                }
            }
        }.awaitAll().sortedBy { it.sort }
        GpuLog.d(TAG) { "fetchRemoteDriverGroups: ${repoList.size} repos done in ${SystemClock.uptimeMillis() - fetchStart}ms, total ${groups.sumOf { it.releases.size }} releases" }
        // Only cache when at least one repo returned data — empty responses
        // usually mean rate-limit or transient network error and we'd rather
        // retry next time than serve empty for an hour.
        if (groups.any { it.releases.isNotEmpty() }) {
            writeRemoteCache(groups)
        } else {
            GpuLog.w(TAG, "All repo fetches returned empty — not caching (likely rate-limited or offline)")
        }
        groups
    }

    private fun readRemoteCache(): List<RemoteDriverGroup>? {
        if (!remoteCacheFile.exists()) {
            GpuLog.v(TAG) { "remote-cache file does not exist" }
            return null
        }
        return try {
            val envelope = json.decodeFromString<RemoteCacheFile>(remoteCacheFile.readText())
            val ageMs = System.currentTimeMillis() - envelope.timestamp
            if (ageMs < remoteCacheTtlMs) {
                GpuLog.v(TAG) { "remote-cache file age=${ageMs}ms (< ${remoteCacheTtlMs}ms TTL) — valid" }
                envelope.groups
            } else {
                GpuLog.d(TAG) { "remote-cache expired (age=${ageMs}ms) — deleting file" }
                // Expired — drop the file so we don't read it again next time.
                remoteCacheFile.delete()
                null
            }
        } catch (e: Exception) {
            // Corrupt file (partial write, format change, etc.) — discard.
            GpuLog.w(TAG, "remote-cache file corrupt — discarding: ${e.message}")
            remoteCacheFile.delete()
            null
        }
    }

    private fun writeRemoteCache(groups: List<RemoteDriverGroup>) {
        try {
            val envelope = RemoteCacheFile(System.currentTimeMillis(), groups)
            remoteCacheFile.writeText(json.encodeToString(envelope))
            GpuLog.v(TAG) { "remote-cache file written: ${remoteCacheFile.length()} bytes" }
        } catch (e: Exception) {
            GpuLog.w(TAG, "Failed to persist remote cache: ${e.message}")
        }
    }

    suspend fun installDriver(zipUri: Uri): Result<GpuDriver> = withContext(Dispatchers.IO) {
        val installStart = SystemClock.uptimeMillis()
        try {
            val tempId = UUID.randomUUID().toString()
            val targetDir = File(driversDir, tempId)
            targetDir.mkdirs()
            GpuLog.d(TAG) { "installDriver: extracting $zipUri → $targetDir" }
            var entryCount = 0
            var totalBytes = 0L

            val targetCanonical = targetDir.canonicalPath
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        val file = File(targetDir, entry.name)
                        // Zip-slip guard: reject entries whose resolved path
                        // escapes targetDir (e.g., name = "../../databases/x").
                        if (!file.canonicalPath.startsWith(targetCanonical + File.separator) &&
                            file.canonicalPath != targetCanonical) {
                            GpuLog.e(TAG, "zip-slip blocked: '${entry.name}' would escape $targetCanonical")
                            targetDir.deleteRecursively()
                            return@withContext Result.failure(
                                SecurityException("Invalid ZIP entry path: ${entry.name}")
                            )
                        }
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { output ->
                                totalBytes += zipStream.copyTo(output)
                            }
                            // Extracted .so files must be executable for the
                            // dynamic linker to mmap them. ZIP doesn't preserve
                            // POSIX mode bits reliably across producers, so set
                            // it explicitly here.
                            if (file.name.endsWith(".so")) {
                                file.setExecutable(true, false)
                            }
                            entryCount++
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }
            GpuLog.v(TAG) { "extracted $entryCount files, $totalBytes bytes" }

            val metaFile = File(targetDir, "meta.json")
            if (!metaFile.exists()) {
                GpuLog.w(TAG, "installDriver: zip missing meta.json — rejecting")
                targetDir.deleteRecursively()
                return@withContext Result.failure(Exception("Missing meta.json in driver package"))
            }

            val metaContent = metaFile.readText()
            val metaJson = json.parseToJsonElement(metaContent).jsonObject
            val (actualDir, actualLib) = resolveDriverLibAndDir(
                targetDir,
                metaJson["vulkanLibName"]?.jsonPrimitive?.content ?: "libvulkan_freedreno.so",
            )

            // ELF magic check: the .so we'll actually dlopen must be a real
            // ELF binary. Catches the "user dropped a non-driver zip" case
            // before adrenotools_open_libvulkan crashes the process.
            val mainLib = File(actualDir, actualLib)
            if (mainLib.exists() && !isValidElfBinary(mainLib)) {
                GpuLog.e(TAG, "installDriver: '$actualLib' is not a valid ELF binary — rejecting")
                targetDir.deleteRecursively()
                return@withContext Result.failure(
                    Exception("Driver file '$actualLib' is not a valid ELF binary. The package may be corrupt."),
                )
            }

            val driverName = metaJson["name"]?.jsonPrimitive?.content ?: tempId
            val driverVersion = metaJson["driverVersion"]?.jsonPrimitive?.content ?: ""

            // Dedup by name + version: if the same combo is already installed,
            // wipe the old dir so the new one replaces it cleanly. excludeId
            // keeps us from deleting the dir we just extracted to.
            findExistingDriver(driverName, driverVersion, excludeId = tempId)?.let { existingId ->
                GpuLog.i(TAG) { "replacing existing driver '$driverName' v$driverVersion (oldId=$existingId)" }
                File(driversDir, existingId).deleteRecursively()
            }

            // Stamp installDate into meta.json so future scans don't have to
            // fall back to the dir's mtime (which the OS may change for
            // unrelated reasons — touch by external tools, restore-from-backup).
            val installTimestamp = System.currentTimeMillis()
            runCatching {
                val updatedMeta = buildJsonObject {
                    metaJson.forEach { (k, v) -> put(k, v) }
                    put("installDate", installTimestamp)
                }
                metaFile.writeText(updatedMeta.toString())
            }.onFailure { GpuLog.w(TAG, "could not stamp installDate into meta.json: ${it.message}") }

            val finalSize = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

            val driver = GpuDriver(
                id = tempId,
                name = driverName,
                description = metaJson["description"]?.jsonPrimitive?.content ?: "",
                author = metaJson["author"]?.jsonPrimitive?.content ?: "",
                version = driverVersion,
                vendor = metaJson["vendor"]?.jsonPrimitive?.content ?: "",
                driverPath = actualDir,
                vulkanLibName = actualLib,
                installDate = installTimestamp,
                fileSizeBytes = finalSize,
                minApi = metaJson["minApi"]?.jsonPrimitive?.content ?: "",
            )
            // Mirror the install zip into the user's SAF gpudriver/ folder
            // (if configured) so they can recover it without re-downloading.
            runCatching { backupZipToSaf(zipUri, driver.name) }
                .onFailure { GpuLog.w(TAG, "SAF zip backup failed: ${it.message}") }
            installedCache = null
            GpuLog.i(TAG) { "installed '${driver.name}' v${driver.version} (id=${driver.id}) in ${SystemClock.uptimeMillis() - installStart}ms" }
            Result.success(driver)
        } catch (e: Exception) {
            GpuLog.e(TAG, "Failed to install driver after ${SystemClock.uptimeMillis() - installStart}ms", e)
            Result.failure(e)
        }
    }

    suspend fun downloadAndInstallDriver(remoteDriver: RemoteGpuDriver, onProgress: (Float) -> Unit): Result<GpuDriver> = withContext(Dispatchers.IO) {
        val dlStart = SystemClock.uptimeMillis()
        try {
            val tempFile = File(context.cacheDir, "driver_download.zip")
            GpuLog.d(TAG) { "download: ${remoteDriver.name} from ${remoteDriver.downloadUrl}" }
            val request = Request.Builder()
                .url(remoteDriver.downloadUrl)
                .header("User-Agent", "MpvRx-GpuFetcher")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

            val body = response.body ?: throw Exception("Empty response body")
            val totalSize = body.contentLength()
            GpuLog.v(TAG) { "download: ${totalSize} bytes expected" }

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalSize > 0) {
                            onProgress(totalRead.toFloat() / totalSize)
                        }
                    }
                }
            }
            GpuLog.d(TAG) { "download: complete in ${SystemClock.uptimeMillis() - dlStart}ms" }
            val result = installDriver(Uri.fromFile(tempFile))
            tempFile.delete()
            result
        } catch (e: Exception) {
            GpuLog.e(TAG, "Failed to download and install driver after ${SystemClock.uptimeMillis() - dlStart}ms", e)
            Result.failure(e)
        }
    }

    fun deleteDriver(id: String) {
        val dir = File(driversDir, id)
        // Read the driver name from meta.json BEFORE wiping the dir so we
        // can also remove its SAF backup zip (otherwise the storage section
        // keeps showing an "Install" entry for the driver the user just
        // deleted — confusing because they expected delete to mean "gone").
        val driverName = runCatching {
            File(dir, "meta.json").takeIf { it.exists() }
                ?.readText()
                ?.let { json.parseToJsonElement(it).jsonObject["name"]?.jsonPrimitive?.content }
        }.getOrNull()

        if (dir.exists()) {
            dir.deleteRecursively()
            GpuLog.i(TAG) { "deleted driver id=$id" }
        } else {
            GpuLog.w(TAG, "deleteDriver: id=$id not found on disk")
        }

        if (!driverName.isNullOrBlank()) {
            runCatching { deleteSafBackupByName(driverName) }
                .onFailure { GpuLog.w(TAG, "failed to clean SAF backup for '$driverName': ${it.message}") }
        }

        installedCache = null
    }

    // Removes the auto-created SAF backup zip (if any) that backupZipToSaf
    // wrote during installDriver. Filename derivation mirrors backupZipToSaf
    // so they stay in lockstep.
    private fun deleteSafBackupByName(driverName: String) {
        val safFolder = getSafGpuDriverFolder() ?: return
        val safeName = driverName.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".zip"
        val zipFile = safFolder.findFile(safeName) ?: return
        if (zipFile.delete()) {
            GpuLog.v(TAG) { "deleted SAF backup gpudriver/$safeName" }
        } else {
            GpuLog.w(TAG, "could not delete SAF backup gpudriver/$safeName")
        }
    }

    // Sniffs the first four bytes against the ELF magic. Returns true only if
    // the file exists, is readable, and starts with \x7fELF. Lazy stream — no
    // need to read the whole .so just to validate the header.
    private fun isValidElfBinary(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                fis.read(header) == 4 && header.contentEquals(ELF_MAGIC)
            }
        } catch (e: IOException) {
            GpuLog.w(TAG, "ELF check failed for ${file.name}: ${e.message}")
            false
        }
    }

    // Looks for a previously-installed driver with the same name + version.
    // Used by installDriver to atomically replace an old install when the
    // user reinstalls the same release (otherwise duplicates pile up).
    // excludeId skips a specific dir name so we don't delete the one we
    // just extracted to.
    private fun findExistingDriver(name: String, version: String, excludeId: String? = null): String? {
        driversDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            if (dir.name == excludeId) return@forEach
            val metaFile = File(dir, "meta.json")
            if (!metaFile.exists()) return@forEach
            try {
                val obj = json.parseToJsonElement(metaFile.readText()).jsonObject
                val existingName = obj["name"]?.jsonPrimitive?.content ?: ""
                val existingVersion = obj["driverVersion"]?.jsonPrimitive?.content ?: ""
                if (existingName == name && existingVersion == version) return dir.name
            } catch (_: Exception) { /* ignore unparseable metadata */ }
        }
        return null
    }

    // Walk a driver dir to find the actual vulkan library — driver packages
    // ship inconsistent layouts: some keep libvulkan_freedreno.so at the root,
    // some bury it under arm64-v8a/, some rename it. Prefer an exact filename
    // match, then any .so with "vulkan" in the name, then any .so as a last
    // resort. Returns (parentDir, fileName) of the chosen .so, or the fallback
    // (dir, expectedLib) if no .so files exist.
    //
    // As a side effect, marks every .so found executable (the linker needs it)
    // and read-only (defensive against stray edits at runtime).
    private fun resolveDriverLibAndDir(dir: File, expectedLib: String): Pair<String, String> {
        val soFiles = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList()
        if (soFiles.isEmpty()) return dir.absolutePath to expectedLib
        val match = soFiles.firstOrNull { it.name == expectedLib }
            ?: soFiles.firstOrNull { it.name.contains("vulkan", ignoreCase = true) }
            ?: soFiles.first()
        soFiles.forEach {
            it.setExecutable(true, false)
            it.setReadOnly()
        }
        return (match.parentFile?.absolutePath ?: dir.absolutePath) to match.name
    }

    // Resolves the user-selected SAF root and returns its `gpudriver/`
    // subfolder if present. Pulls the URI from the same preference key used
    // by AdvancedPreferencesScreen so external storage stays a single source
    // of truth.
    private fun getSafGpuDriverFolder(): androidx.documentfile.provider.DocumentFile? {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val baseStorageFolder = prefs.getString("base_storage_folder", "") ?: ""
        if (baseStorageFolder.isBlank()) return null
        return try {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                context,
                Uri.parse(baseStorageFolder),
            )
            tree?.findFile("gpudriver")?.takeIf { it.isDirectory }
        } catch (_: Exception) {
            null
        }
    }

    private fun backupZipToSaf(zipUri: Uri, driverName: String) {
        val safFolder = getSafGpuDriverFolder() ?: return
        val safeName = driverName.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".zip"
        val destFile = safFolder.findFile(safeName)
            ?: safFolder.createFile("application/zip", safeName)
            ?: return
        context.contentResolver.openInputStream(zipUri)?.use { input ->
            context.contentResolver.openOutputStream(destFile.uri)?.use { output ->
                input.copyTo(output)
            }
        }
        GpuLog.v(TAG) { "backed up '$driverName' zip to SAF gpudriver/$safeName" }
    }

    /**
     * Scans the user's SAF `gpudriver/` folder for zip packages and parses
     * each one's `meta.json` (read-only — does not extract). Used by the GPU
     * driver preferences screen to surface "drag-and-drop" drivers the user
     * dropped into the folder without launching the file-picker flow.
     */
    suspend fun getSafDrivers(): List<SafGpuDriver> = withContext(Dispatchers.IO) {
        val drivers = mutableListOf<SafGpuDriver>()
        val safFolder = getSafGpuDriverFolder() ?: return@withContext drivers

        safFolder.listFiles()
            .filter { it.name?.endsWith(".zip", ignoreCase = true) == true }
            .forEach { zipFile ->
                try {
                    context.contentResolver.openInputStream(zipFile.uri)?.use { inputStream ->
                        ZipInputStream(inputStream).use { zipStream ->
                            var entry = zipStream.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && entry.name.lowercase().endsWith("meta.json")) {
                                    val metaContent = String(zipStream.readBytes())
                                    val metaJson = json.parseToJsonElement(metaContent).jsonObject
                                    drivers.add(
                                        SafGpuDriver(
                                            uri = zipFile.uri,
                                            name = metaJson["name"]?.jsonPrimitive?.content
                                                ?: zipFile.name ?: "Unknown",
                                            description = metaJson["description"]?.jsonPrimitive?.content ?: "",
                                            author = metaJson["author"]?.jsonPrimitive?.content ?: "",
                                            version = metaJson["driverVersion"]?.jsonPrimitive?.content ?: "",
                                            vendor = metaJson["vendor"]?.jsonPrimitive?.content ?: "",
                                            fileSize = zipFile.length(),
                                        ),
                                    )
                                    break
                                }
                                zipStream.closeEntry()
                                entry = zipStream.nextEntry
                            }
                        }
                    }
                } catch (e: Exception) {
                    GpuLog.w(TAG, "Failed to parse SAF zip ${zipFile.name}: ${e.message}")
                }
            }
        drivers
    }

    data class SafGpuDriver(
        val uri: Uri,
        val name: String,
        val description: String,
        val author: String,
        val version: String,
        val vendor: String,
        val fileSize: Long,
    )

    @Serializable
    data class RemoteGpuDriver(val name: String, val downloadUrl: String)
    @Serializable
    data class RemoteRelease(val title: String, val version: String, val isLatest: Boolean, val drivers: List<RemoteGpuDriver>)
    @Serializable
    data class RemoteDriverGroup(val name: String, val releases: List<RemoteRelease>, val sort: Int)
    private data class DriverRepo(val name: String, val path: String, val sort: Int, val useTagName: Boolean = false)

    // Disk cache envelope: timestamp lets us check TTL without trusting
    // the OS file mtime (which can be wrong after timezone/clock changes).
    @Serializable
    private data class RemoteCacheFile(val timestamp: Long, val groups: List<RemoteDriverGroup>)
}
