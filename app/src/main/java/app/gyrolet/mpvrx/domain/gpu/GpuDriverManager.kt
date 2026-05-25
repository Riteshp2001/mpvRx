package app.gyrolet.mpvrx.domain.gpu

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

class GpuDriverManager(private val context: Context, private val okHttpClient: OkHttpClient) {
    private val driversDir: File
        get() {
            val dir = File(context.filesDir, "gpu_drivers")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private fun getSafGpuDriverFolder(): androidx.documentfile.provider.DocumentFile? {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val baseStorageFolder = prefs.getString("base_storage_folder", "") ?: ""
        if (baseStorageFolder.isBlank()) return null
        
        return try {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(baseStorageFolder))
            val gpuFolder = tree?.findFile("gpudriver")
            if (gpuFolder != null && gpuFolder.isDirectory) gpuFolder else null
        } catch (e: Exception) {
            null
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

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

    fun getInstalledDriversSync(): List<GpuDriver> {
        val drivers = mutableListOf<GpuDriver>()
        drivers.add(GpuDriver("system", "System Default", "Use the built-in system GPU driver", isSystem = true, driverPath = "", vulkanLibName = ""))

        driversDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val metaFile = File(dir, "meta.json")
            if (metaFile.exists()) {
                try {
                    val metaContent = metaFile.readText()
                    val metaJson = json.parseToJsonElement(metaContent).jsonObject
                    drivers.add(GpuDriver(
                        id = dir.name,
                        name = metaJson["name"]?.jsonPrimitive?.content ?: dir.name,
                        description = metaJson["description"]?.jsonPrimitive?.content ?: "",
                        author = metaJson["author"]?.jsonPrimitive?.content ?: "",
                        version = metaJson["driverVersion"]?.jsonPrimitive?.content ?: "",
                        vendor = metaJson["vendor"]?.jsonPrimitive?.content ?: "",
                        driverPath = dir.absolutePath,
                        vulkanLibName = metaJson["vulkanLibName"]?.jsonPrimitive?.content ?: "libvulkan_freedreno.so"
                    ))
                } catch (e: Exception) {
                    Log.e("GpuDriverManager", "Failed to parse meta.json in ${dir.name}", e)
                }
            }
        }
        return drivers
    }

    suspend fun getInstalledDrivers(): List<GpuDriver> = withContext(Dispatchers.IO) {
        getInstalledDriversSync()
    }

    suspend fun fetchRemoteDriverGroups(): List<RemoteDriverGroup> = withContext(Dispatchers.IO) {
        repoList.map { repo ->
            async {
                try {
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/${repo.path}/releases")
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "MpvRx-GpuFetcher")
                        .build()

                    val response = okHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    
                    val content = response.body.string()
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

                    RemoteDriverGroup(repo.name, remoteReleases, repo.sort)
                } catch (e: Exception) {
                    Log.e("GpuDriverManager", "Failed to fetch from ${repo.name}: ${e.message}")
                    RemoteDriverGroup(repo.name, emptyList(), repo.sort)
                }
            }
        }.awaitAll().sortedBy { it.sort }
    }

    suspend fun installDriver(zipUri: Uri): Result<GpuDriver> = withContext(Dispatchers.IO) {
        try {
            val tempId = UUID.randomUUID().toString()
            val targetDir = File(driversDir, tempId)
            targetDir.mkdirs()

            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        val file = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { output ->
                                zipStream.copyTo(output)
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }

            val metaFile = File(targetDir, "meta.json")
            if (!metaFile.exists()) {
                targetDir.deleteRecursively()
                return@withContext Result.failure(Exception("Missing meta.json in driver package"))
            }

            val metaContent = metaFile.readText()
            val metaJson = json.parseToJsonElement(metaContent).jsonObject
            val driver = GpuDriver(
                id = tempId,
                name = metaJson["name"]?.jsonPrimitive?.content ?: tempId,
                description = metaJson["description"]?.jsonPrimitive?.content ?: "",
                author = metaJson["author"]?.jsonPrimitive?.content ?: "",
                version = metaJson["driverVersion"]?.jsonPrimitive?.content ?: "",
                vendor = metaJson["vendor"]?.jsonPrimitive?.content ?: "",
                driverPath = targetDir.absolutePath,
                vulkanLibName = metaJson["vulkanLibName"]?.jsonPrimitive?.content ?: "libvulkan_freedreno.so"
            )

            // Backup the zip to the SAF folder if possible
            try {
                val safFolder = getSafGpuDriverFolder()
                if (safFolder != null) {
                    val zipFileName = driver.name.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".zip"
                    var destFile = safFolder.findFile(zipFileName)
                    if (destFile == null) {
                        destFile = safFolder.createFile("application/zip", zipFileName)
                    }
                    if (destFile != null) {
                        context.contentResolver.openInputStream(zipUri)?.use { input ->
                            context.contentResolver.openOutputStream(destFile.uri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GpuDriverManager", "Failed to backup zip to SAF", e)
            }

            Result.success(driver)
        } catch (e: Exception) {
            Log.e("GpuDriverManager", "Failed to install driver", e)
            Result.failure(e)
        }
    }

    suspend fun downloadAndInstallDriver(remoteDriver: RemoteGpuDriver, onProgress: (Float) -> Unit): Result<GpuDriver> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "driver_download.zip")
            val request = Request.Builder()
                .url(remoteDriver.downloadUrl)
                .header("User-Agent", "MpvRx-GpuFetcher")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            
            val body = response.body
            val totalSize = body.contentLength()
            
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
            
            val result = installDriver(Uri.fromFile(tempFile))
            tempFile.delete()
            result
        } catch (e: Exception) {
            Log.e("GpuDriverManager", "Failed to download and install driver", e)
            Result.failure(e)
        }
    }

    fun deleteDriver(id: String) {
        val dir = File(driversDir, id)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    suspend fun getSafDrivers(): List<SafGpuDriver> = withContext(Dispatchers.IO) {
        val drivers = mutableListOf<SafGpuDriver>()
        val safFolder = getSafGpuDriverFolder() ?: return@withContext drivers
        
        safFolder.listFiles().filter { it.name?.endsWith(".zip", ignoreCase = true) == true }.forEach { zipFile ->
            try {
                context.contentResolver.openInputStream(zipFile.uri)?.use { inputStream ->
                    java.util.zip.ZipInputStream(inputStream).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.lowercase().endsWith("meta.json")) {
                                val metaContent = String(zipStream.readBytes())
                                val metaJson = json.parseToJsonElement(metaContent).jsonObject
                                drivers.add(SafGpuDriver(
                                    uri = zipFile.uri,
                                    name = metaJson["name"]?.jsonPrimitive?.content ?: zipFile.name ?: "Unknown",
                                    description = metaJson["description"]?.jsonPrimitive?.content ?: "",
                                    author = metaJson["author"]?.jsonPrimitive?.content ?: "",
                                    version = metaJson["driverVersion"]?.jsonPrimitive?.content ?: "",
                                    vendor = metaJson["vendor"]?.jsonPrimitive?.content ?: "",
                                    fileSize = zipFile.length()
                                ))
                                break
                            }
                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GpuDriverManager", "Failed to parse SAF zip: ${zipFile.name}", e)
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
        val fileSize: Long
    )

    data class RemoteGpuDriver(val name: String, val downloadUrl: String)
    data class RemoteRelease(val title: String, val version: String, val isLatest: Boolean, val drivers: List<RemoteGpuDriver>)
    data class RemoteDriverGroup(val name: String, val releases: List<RemoteRelease>, val sort: Int)
    private data class DriverRepo(val name: String, val path: String, val sort: Int, val useTagName: Boolean = false)
}
