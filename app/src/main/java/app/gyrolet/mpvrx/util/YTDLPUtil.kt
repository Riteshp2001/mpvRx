package app.gyrolet.mpvrx.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class YTDLPUtil(private val context: Context) {

    private val runtimeManager = RuntimeManager(context)
    private val TAG = "YTDLPUtil"

    data class Format(
        val formatId: String,
        val ext: String,
        val resolution: String?,
        val vcodec: String?,
        val acodec: String?,
        val tbr: Float?,
        val filesize: Long?
    )

    data class VideoInfo(
        val title: String?,
        val duration: Int?,
        val thumbnail: String?,
        val streamingUrl: String?
    )

    suspend fun getStreamingUrl(url: String, preferredQuality: String = "best"): String? = withContext(Dispatchers.IO) {
        try {
            val request = YTDLRequest().apply {
                addOption("--no-warnings", "")
                addOption("--no-check-certificate", "")
                addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                addOption("--get-url", "")
                addOption("-f", preferredQuality)
                addOption("--socket-timeout", "30")
                addUrl(url)
            }
            val output = runtimeManager.execute(request).trim()
            output.lines().lastOrNull { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get streaming URL for $url", e)
            null
        }
    }

    suspend fun getStreamingUrlWithFallback(url: String): String? = withContext(Dispatchers.IO) {
        val qualityOptions = listOf("best", "best[height<=1080]", "best[height<=720]", "bestaudio/best")
        for (quality in qualityOptions) {
            try {
                val result = getStreamingUrl(url, quality)
                if (result != null) {
                    Log.d(TAG, "Successfully resolved with quality: $quality")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed with quality $quality: ${e.message}")
            }
        }
        null
    }

    suspend fun getFormats(url: String): List<Format> = withContext(Dispatchers.IO) {
        try {
            val request = YTDLRequest().apply {
                addOption("--no-warnings", "")
                addOption("--print", "%(formats)s")
                addOption("--socket-timeout", "30")
                addUrl(url)
            }
            val output = runtimeManager.execute(request).trim()
            parseFormats(output)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get formats for $url", e)
            emptyList()
        }
    }

    suspend fun getVideoInfo(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            val request = YTDLRequest().apply {
                addOption("--no-warnings", "")
                addOption("--print", "%(title)s|||%(duration)s|||%(thumbnail)s")
                addOption("--socket-timeout", "30")
                addUrl(url)
            }
            val output = runtimeManager.execute(request).trim()
            val parts = output.split("|||")
            VideoInfo(
                title = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
                duration = parts.getOrNull(1)?.toIntOrNull(),
                thumbnail = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                streamingUrl = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video info for $url", e)
            null
        }
    }

    private fun parseFormats(jsonStr: String): List<Format> {
        return try {
            val jsonArray = JSONArray(jsonStr)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                Format(
                    formatId = obj.optString("format_id", ""),
                    ext = obj.optString("ext", ""),
                    resolution = obj.optString("resolution", "").takeIf { it.isNotEmpty() },
                    vcodec = obj.optString("vcodec", "").takeIf { it.isNotEmpty() },
                    acodec = obj.optString("acodec", "").takeIf { it.isNotEmpty() },
                    tbr = obj.optDouble("tbr", -1.0).takeIf { it > 0 }?.toFloat(),
                    filesize = obj.optLong("filesize", -1).takeIf { it > 0 }
                )
            }.filter { it.ext.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse formats", e)
            emptyList()
        }
    }
}

class YTDLRequest {
    private val options = mutableListOf<String>()
    private var url: String = ""

    fun addOption(key: String, value: String = "") {
        options.add(key)
        if (value.isNotEmpty()) {
            options.add(value)
        }
    }

    fun addUrl(url: String) {
        this.url = url
    }

    fun buildCommand(): List<String> {
        val cmd = mutableListOf<String>()
        cmd.addAll(options)
        if (url.isNotEmpty()) {
            cmd.add(url)
        }
        return cmd
    }
}