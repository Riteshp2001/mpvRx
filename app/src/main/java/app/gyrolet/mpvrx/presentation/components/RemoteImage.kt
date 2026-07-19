package app.gyrolet.mpvrx.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@Composable
fun RemoteImage(
  url: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  alpha: Float = 1f,
) {
  val context = LocalContext.current
  val client = koinInject<OkHttpClient>()
  var bitmap by remember(url) { mutableStateOf(RemoteImageLoader.getFromMemory(url)) }

  LaunchedEffect(url) {
    if (bitmap == null) {
      bitmap = withContext(Dispatchers.IO) {
        RemoteImageLoader.load(context, client, url)
      }
    }
  }

  bitmap?.let { loaded ->
    Image(
      bitmap = loaded.asImageBitmap(),
      contentDescription = contentDescription,
      modifier = modifier,
      contentScale = contentScale,
      alpha = alpha,
    )
  }
}

private object RemoteImageLoader {
  private const val MAX_IMAGE_DIMENSION = 1024
  private const val CACHE_DIRECTORY = "remote_images"
  private val memoryCache = object : LruCache<String, Bitmap>(
    ((Runtime.getRuntime().maxMemory() / 1024L) / 32L).toInt(),
  ) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
  }

  fun getFromMemory(url: String): Bitmap? = synchronized(memoryCache) { memoryCache.get(url) }

  fun load(context: Context, client: OkHttpClient, url: String): Bitmap? {
    getFromMemory(url)?.let { return it }

    val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    val cacheFile = File(cacheDirectory, hash(url))
    decodeSampled(cacheFile)?.let { bitmap ->
      synchronized(memoryCache) { memoryCache.put(url, bitmap) }
      return bitmap
    }

    val host = runCatching { java.net.URI(url).host }.getOrNull()
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", "Mozilla/5.0 (Android) mpvRx")
      .apply { if (!host.isNullOrBlank()) header("Referer", "https://$host") }
      .build()

    return runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use null
        val bytes = response.body.bytes()
        FileOutputStream(cacheFile).use { it.write(bytes) }
        decodeSampled(cacheFile)?.also { bitmap ->
          synchronized(memoryCache) { memoryCache.put(url, bitmap) }
        }
      }
    }.getOrNull()
  }

  private fun decodeSampled(file: File): Bitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_IMAGE_DIMENSION) {
      sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
      file.absolutePath,
      BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
      },
    )
  }

  private fun hash(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray())
      .joinToString("") { byte -> "%02x".format(byte) }
}
