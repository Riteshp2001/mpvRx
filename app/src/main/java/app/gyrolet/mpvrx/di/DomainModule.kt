package app.gyrolet.mpvrx.di

import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.domain.thumbnail.CoilVideoThumbnailDecoder
import app.gyrolet.mpvrx.domain.thumbnail.toThumbnailStrategy
import app.gyrolet.mpvrx.network.AndroidCookieJar
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.repository.IntroDbRepository
import app.gyrolet.mpvrx.repository.wyzie.WyzieSearchRepository
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.FileSystem
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val domainModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(AndroidCookieJar())
            .build()
    }
    single<ImageLoader> {
        val context = androidContext()
        val browserPreferences = get<BrowserPreferences>()

        val imageHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val host = original.url.host
                val request = original.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                    .header("Referer", "https://$host")
                    .build()
                chain.proceed(request)
            })
            .build()

        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient }))
                add(
                    CoilVideoThumbnailDecoder.Factory(
                        thumbnailStrategy = {
                            browserPreferences.thumbnailMode.get().toThumbnailStrategy(
                                browserPreferences.thumbnailFramePosition.get()
                            )
                        }
                    )
                )
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache(
                DiskCache.Builder()
                    .fileSystem(FileSystem.SYSTEM)
                    .directory(context.cacheDir.resolve("thumbnails"))
                    .maxSizePercent(0.05)
                    .build()
            )
            .crossfade(true)
            .build()
    }
    single { Anime4KManager(androidContext()) }
    single { WyzieSearchRepository(androidContext(), get(), get(), get()) }
    single { IntroDbRepository(get(), get()) }
}
