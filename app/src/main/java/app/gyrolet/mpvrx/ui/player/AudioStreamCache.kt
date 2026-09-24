package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.gyrolet.mpvrx.preferences.AudioPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale

@UnstableApi
internal object AudioStreamCache {
  private var cache: SimpleCache? = null

  @Synchronized
  private fun cache(context: Context): SimpleCache? {
    cache?.let { return it }
    val freeBytes = context.cacheDir.usableSpace
    if (freeBytes < 32L * 1024 * 1024) return null
    return runCatching {
      SimpleCache(
        File(context.cacheDir, "audio_streams"),
        LeastRecentlyUsedCacheEvictor((freeBytes / 10).coerceIn(8L * 1024 * 1024, 256L * 1024 * 1024)),
        StandaloneDatabaseProvider(context.applicationContext),
      ).also { cache = it }
    }.getOrNull()
  }

  fun factory(context: Context, upstream: DataSource.Factory, source: AudioPlaybackSource): DataSource.Factory {
    val root = Uri.parse(source.uri)
    val adaptive = source.mimeType?.lowercase(Locale.ROOT) in setOf(
      "application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml",
    ) || root.path.orEmpty().endsWith(".m3u8", true) || root.path.orEmpty().endsWith(".mpd", true)
    val credentials = if (root.isHierarchical) {
      listOf("api_key", "token", "access_token", "key").associateWith { root.getQueryParameter(it).orEmpty() }
    } else emptyMap()
    val representation = if (root.isHierarchical) {
      root.queryParameterNames.sorted().filterNot { name ->
        name.lowercase(Locale.ROOT) in setOf("expire", "expires", "signature", "sig", "lsig", "policy", "key-pair-id") ||
          name.startsWith("x-amz-", true) || name.startsWith("x-goog-", true)
      }.associateWith { root.getQueryParameters(it) }
    } else emptyMap()
    val identity = Json.encodeToString(listOf(
      source.item.stableId,
      root.scheme.orEmpty(), root.encodedAuthority.orEmpty(), root.encodedPath.orEmpty(),
      Json.encodeToString(source.item.headers.toSortedMap()), Json.encodeToString(credentials),
      Json.encodeToString(representation),
    ))
    val partition = digest(identity)
    val adaptiveSession = if (adaptive) java.util.UUID.randomUUID().toString() else ""
    return DataSource.Factory {
      val preferences = org.koin.java.KoinJavaComponent.get<AudioPreferences>(AudioPreferences::class.java)
      val storage = if (preferences.streamingCacheEnabled.get()) cache(context) else null
      if (storage == null) upstream.createDataSource() else {
        val cached = CacheDataSource.Factory()
          .setCache(storage)
          .setUpstreamDataSourceFactory(upstream)
          .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
          .setCacheKeyFactory { spec ->
            if (!adaptive && spec.uri == root) partition
            else digest(Json.encodeToString(listOf(partition, adaptiveSession, spec.key ?: spec.uri.toString())))
          }.createDataSource()
        object : DataSource {
          private val listeners = mutableListOf<TransferListener>()
          private var delegate: DataSource? = null

          override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
          }

          override fun open(dataSpec: DataSpec): Long {
            val path = dataSpec.uri.path.orEmpty()
            val bypass = dataSpec.uri.scheme !in setOf("http", "https") ||
              path.endsWith(".m3u8", true) || path.endsWith(".mpd", true) || adaptive && dataSpec.uri == root ||
              dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET
            val selected = if (bypass) upstream.createDataSource() else cached
            delegate = selected
            listeners.forEach(selected::addTransferListener)
            return selected.open(dataSpec)
          }

          override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

          override fun getUri(): Uri? = delegate?.uri

          override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

          override fun close() {
            val selected = delegate
            delegate = null
            selected?.close()
          }
        }
      }
    }
  }

  private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}