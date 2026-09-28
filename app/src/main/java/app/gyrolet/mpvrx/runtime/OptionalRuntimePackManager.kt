/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

enum class OptionalRuntimePack(
  val packageName: String,
  val kind: String,
  private val assetPrefix: String,
  private val abiSpecific: Boolean,
  val requiredNativeLibraries: Set<String>,
  val requiredAssets: Set<String>,
) {
  Online(
    "app.gyrolet.mpvrx.runtime.online",
    "online",
    "mpvRx-online-runtime",
    true,
    setOf("libpython.so", "libqjs.so"),
    setOf("assets/ytdl/python313.zip", "assets/guessit/packages.json", "assets/guessit/parse_filename.py"),
  ),
  Torrent(
    "app.gyrolet.mpvrx.runtime.torrent",
    "torrent",
    "mpvRx-torrent-runtime",
    true,
    setOf("libtorrent4j.so"),
    emptySet(),
  ),
  Visual(
    "app.gyrolet.mpvrx.runtime.visual",
    "visual",
    "mpvRx-visual-runtime",
    false,
    emptySet(),
    setOf(
      "assets/shaders/anime4k/Anime4K_Restore_CNN_M.glsl",
      "assets/shaders/hdr-toys/utils/transform.glsl",
    ),
  ),
  ;

  fun releaseAssetName(): String =
    if (abiSpecific) "$assetPrefix-${OptionalRuntimePackManager.deviceAbi()}.apk" else "$assetPrefix.apk"

  fun downloadUrl(): String = "${BuildConfig.RUNTIME_PACK_BASE_URL.trimEnd('/')}/${releaseAssetName()}"
}

object OptionalRuntimePackManager {
  private const val PACK_API = 1
  private const val META_API = "app.gyrolet.mpvrx.RUNTIME_PACK_API"
  private const val META_KIND = "app.gyrolet.mpvrx.RUNTIME_PACK_KIND"

  private val httpClient =
    OkHttpClient
      .Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(5, TimeUnit.MINUTES)
      .callTimeout(10, TimeUnit.MINUTES)
      .followRedirects(true)
      .build()
  private val downloadLocks = OptionalRuntimePack.entries.associateWith { Mutex() }

  class InstallPermissionRequiredException : IllegalStateException()

  fun deviceAbi(): String =
    Build.SUPPORTED_ABIS.firstOrNull {
      it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86" || it == "x86_64"
    } ?: Build.SUPPORTED_ABIS.first()

  fun observeInstalled(
    context: Context,
    pack: OptionalRuntimePack,
  ): Flow<Boolean> =
    callbackFlow {
      val appContext = context.applicationContext
      val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(
            context: Context?,
            intent: Intent?,
          ) {
            if (intent?.data?.schemeSpecificPart == pack.packageName) {
              trySend(isInstalled(appContext, pack))
            }
          }
        }
      val filter =
        IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_REPLACED)
          addAction(Intent.ACTION_PACKAGE_REMOVED)
          addDataScheme("package")
        }
      ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
      trySend(isInstalled(appContext, pack))
      awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

  fun isInstalled(
    context: Context,
    pack: OptionalRuntimePack,
  ): Boolean = trustedApplicationInfo(context, pack) != null

  fun versionCode(
    context: Context,
    pack: OptionalRuntimePack,
  ): Long? =
    trustedApplicationInfo(context, pack)?.let {
      val info = packageInfo(context.packageManager, pack.packageName)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
      }
    }

  fun packContext(
    context: Context,
    pack: OptionalRuntimePack,
  ): Context? =
    trustedApplicationInfo(context, pack)?.let {
      runCatching {
        context.createPackageContext(pack.packageName, Context.CONTEXT_IGNORE_SECURITY)
      }.getOrNull()
    }

  fun nativeLibrary(
    context: Context,
    pack: OptionalRuntimePack,
    fileName: String,
  ): File? =
    trustedApplicationInfo(context, pack)
      ?.nativeLibraryDir
      ?.let(::File)
      ?.resolve(fileName)
      ?.takeIf(File::isFile)

  fun prepareInstalledNativeRuntimes(context: Context) {
    materializeNativeLibrary(context, OptionalRuntimePack.Torrent, "libtorrent4j.so")?.let { library ->
      runCatching { System.loadLibrary("c++_shared") }
      System.setProperty("libtorrent4j.jni.path", library.absolutePath)
    }
  }

  fun materializeNativeLibrary(
    context: Context,
    pack: OptionalRuntimePack,
    fileName: String,
  ): File? {
    val source = nativeLibrary(context, pack, fileName) ?: return null
    val version = versionCode(context, pack) ?: return null
    val directory = File(context.codeCacheDir, "runtime-packs/${pack.kind}/$version/${deviceAbi()}")
    val target = File(directory, fileName)
    if (target.isFile && target.length() == source.length()) return target
    if (!directory.exists() && !directory.mkdirs()) return null
    val temporary = File(directory, "$fileName.tmp")
    return runCatching {
      source.inputStream().use { input ->
        FileOutputStream(temporary).use { output ->
          input.copyTo(output)
          output.fd.sync()
        }
      }
      check(temporary.length() == source.length())
      if (target.exists() && !target.delete()) error("Could not replace native runtime")
      if (!temporary.renameTo(target)) error("Could not finalize native runtime")
      target.setReadable(true, true)
      target.setExecutable(true, true)
      directory.parentFile?.parentFile
        ?.listFiles()
        ?.filter { it != directory.parentFile }
        ?.forEach(File::deleteRecursively)
      target
    }.onFailure {
      temporary.delete()
      target.delete()
    }.getOrNull()
  }

  suspend fun downloadAndRequestInstall(
    context: Context,
    pack: OptionalRuntimePack,
    onProgress: (Float) -> Unit = {},
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      downloadLocks.getValue(pack).withLock {
        val directory = File(context.externalCacheDir ?: context.cacheDir, "runtime-packs").apply(File::mkdirs)
        val target = File(directory, pack.releaseAssetName())
        val temporary = File(directory, "${target.name}.part")
        val result = runCatching {
          if (isInstalled(context, pack)) return@runCatching
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            withContext(Dispatchers.Main) {
              context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              )
            }
            throw InstallPermissionRequiredException()
          }

          temporary.delete()

          val request = Request.Builder().url(pack.downloadUrl()).build()
          httpClient.newCall(request).awaitResponse().use { response ->
            check(response.isSuccessful) { "Runtime download failed (${response.code})" }
            val body = checkNotNull(response.body) { "Runtime download was empty" }
            val expectedLength = body.contentLength()
            var copied = 0L
            body.byteStream().use { input ->
              FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                  val count = input.read(buffer)
                  if (count < 0) break
                  output.write(buffer, 0, count)
                  copied += count
                  if (expectedLength > 0L) onProgress(copied.toFloat() / expectedLength)
                }
                output.fd.sync()
              }
            }
            check(copied > 0L) { "Runtime download was empty" }
            if (expectedLength > 0L) check(copied == expectedLength) { "Runtime download was incomplete" }
          }
          if (target.exists() && !target.delete()) error("Could not replace old runtime installer")
          if (!temporary.renameTo(target)) error("Could not finalize runtime installer")
          validateDownloadedApk(context, pack, target)

          withContext(Dispatchers.Main) { launchInstaller(context, target) }
        }.onFailure {
          temporary.delete()
          target.delete()
        }
        result.exceptionOrNull()?.let { error ->
          if (error is CancellationException) throw error
        }
        result
      }
    }

  fun requestUninstall(
    context: Context,
    pack: OptionalRuntimePack,
  ) {
    context.startActivity(
      Intent(Intent.ACTION_DELETE, Uri.parse("package:${pack.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }

  fun packForPackage(packageName: String?): OptionalRuntimePack? =
    OptionalRuntimePack.entries.firstOrNull { it.packageName == packageName }

  fun cleanExtractedData(
    context: Context,
    pack: OptionalRuntimePack,
  ) {
    when (pack) {
      OptionalRuntimePack.Online -> {
        File(context.filesDir, "ytdl").deleteRecursively()
        File(context.filesDir, "guessit").deleteRecursively()
        File(context.codeCacheDir, "runtime-packs/online").deleteRecursively()
      }
      OptionalRuntimePack.Torrent -> {
        File(context.cacheDir, "torrent_streaming").deleteRecursively()
        File(context.codeCacheDir, "runtime-packs/torrent").deleteRecursively()
      }
      OptionalRuntimePack.Visual -> {
        File(context.filesDir, "shaders/anime4k").deleteRecursively()
        File(context.filesDir, "shaders/hdr-toys").deleteRecursively()
      }
    }
    File(context.externalCacheDir ?: context.cacheDir, "runtime-packs/${pack.releaseAssetName()}").delete()
  }

  fun cleanInstaller(
    context: Context,
    pack: OptionalRuntimePack,
  ) {
    File(context.externalCacheDir ?: context.cacheDir, "runtime-packs/${pack.releaseAssetName()}").delete()
  }

  private fun launchInstaller(
    context: Context,
    apk: File,
  ) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.runtime-packs", apk)
    context.startActivity(
      Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }

  private fun trustedApplicationInfo(
    context: Context,
    pack: OptionalRuntimePack,
  ): ApplicationInfo? =
    runCatching {
      val packageManager = context.packageManager
      val appInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          packageManager.getApplicationInfo(
            pack.packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
          )
        } else {
          @Suppress("DEPRECATION")
          packageManager.getApplicationInfo(pack.packageName, PackageManager.GET_META_DATA)
        }
      check(appInfo.metaData?.getInt(META_API) == PACK_API)
      check(appInfo.metaData?.getString(META_KIND) == pack.kind)
      check(packageInfo(packageManager, pack.packageName).versionName == BuildConfig.RUNTIME_PACK_VERSION_NAME)
      val hostDigests = signingDigests(packageManager, context.packageName)
      val packDigests = signingDigests(packageManager, pack.packageName)
      check(hostDigests.isNotEmpty() && hostDigests == packDigests)
      appInfo
    }.getOrNull()

  private fun validateDownloadedApk(
    context: Context,
    pack: OptionalRuntimePack,
    apk: File,
  ) {
    val packageManager = context.packageManager
    val info =
      checkNotNull(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
          packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          @Suppress("DEPRECATION")
          packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA,
          )
        } else {
          @Suppress("DEPRECATION")
          packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA,
          )
        },
      ) { "Downloaded file is not an Android package" }
    check(info.packageName == pack.packageName) { "Downloaded runtime has the wrong package name" }
    check(info.applicationInfo?.metaData?.getInt(META_API) == PACK_API) { "Downloaded runtime has an incompatible API" }
    check(info.applicationInfo?.metaData?.getString(META_KIND) == pack.kind) { "Downloaded runtime has the wrong type" }
    check(info.versionName == BuildConfig.RUNTIME_PACK_VERSION_NAME) { "Downloaded runtime is for a different app version" }
    val hostDigests = signingDigests(packageInfo(packageManager, context.packageName))
    val packDigests = signingDigests(info)
    check(hostDigests.isNotEmpty() && hostDigests == packDigests) { "Downloaded runtime has an invalid signature" }
    if (pack.requiredNativeLibraries.isNotEmpty() || pack.requiredAssets.isNotEmpty()) {
      ZipFile(apk).use { archive ->
        pack.requiredNativeLibraries.forEach { library ->
          check(archive.getEntry("lib/${deviceAbi()}/$library") != null) {
            "Downloaded runtime does not support ${deviceAbi()}"
          }
        }
        pack.requiredAssets.forEach { asset ->
          check(archive.getEntry(asset) != null) { "Downloaded runtime is missing $asset" }
        }
      }
    }
  }

  private fun signingDigests(
    packageManager: PackageManager,
    packageName: String,
  ): Set<String> = signingDigests(packageInfo(packageManager, packageName))

  private fun signingDigests(info: PackageInfo): Set<String> {
    val signatures =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signingInfo = checkNotNull(info.signingInfo)
        if (signingInfo.hasMultipleSigners()) {
          signingInfo.apkContentsSigners.orEmpty()
        } else {
          signingInfo.signingCertificateHistory.orEmpty()
        }
      } else {
        @Suppress("DEPRECATION")
        info.signatures.orEmpty()
      }
    return signatures.mapTo(mutableSetOf()) { signature ->
      MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }
  }

  private fun packageInfo(
    packageManager: PackageManager,
    packageName: String,
  ): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.getPackageInfo(
        packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
      )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      @Suppress("DEPRECATION")
      packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
      @Suppress("DEPRECATION")
      packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }
}

class OptionalRuntimePackChangedReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    val pack = OptionalRuntimePackManager.packForPackage(intent.data?.schemeSpecificPart) ?: return
    if (intent.action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
      OptionalRuntimePackManager.cleanExtractedData(context.applicationContext, pack)
    } else if (intent.action == Intent.ACTION_PACKAGE_ADDED || intent.action == Intent.ACTION_PACKAGE_REPLACED) {
      OptionalRuntimePackManager.cleanInstaller(context.applicationContext, pack)
    }
  }
}
