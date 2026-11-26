/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.kodi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import java.io.File
import java.net.URLEncoder

/**
 * Starts the Kodi engine that the kodi distribution bundles (the mpvlib-kodi AAR).
 *
 * Kodi runs in this app's `:kodi` process: libkodi.so runs its own event loop and calls exit(0)
 * when the user quits Kodi, which must not end mpvRx. Its components are not exported, so they
 * are started by class name; this code has no compile-time dependency on the AAR.
 */
object KodiIntegration {
  private const val TAG = "KodiIntegration"
  private const val PROCESS_SUFFIX = ":kodi"

  // Kodi's entry point: extracts its assets on first run and checks storage access.
  private const val SPLASH_ACTIVITY = "org.xbmc.kodi.Splash"
  private const val KODI_LIBRARY = "libkodi.so"
  private const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"

  // Schemes Kodi opens itself. It resolves content:// only through MediaStore's _data column.
  private val NETWORK_SCHEMES =
    "http https ftp ftps sftp smb nfs dav davs rtsp rtmp rtmps rtp mms mmsh udp tcp".split(' ').toSet()
  private val HEADER_SCHEMES = setOf("http", "https", "dav", "davs")

  fun isKodiProcess(
    processName: String?,
    packageName: String,
  ): Boolean = processName == packageName + PROCESS_SUFFIX

  /** True when this build bundles Kodi and the device installed its native library. */
  fun isAvailable(context: Context): Boolean =
    BuildConfig.ENABLE_KODI &&
      File(context.applicationInfo.nativeLibraryDir, KODI_LIBRARY).isFile

  fun open(context: Context): Boolean = start(context, splashIntent(context))

  /** Plays [video] in Kodi. Returns false when Kodi cannot open it. */
  fun play(
    context: Context,
    video: Video,
  ): Boolean {
    val target = kodiUri(context, video.uri, video.path) ?: return false
    return play(context, target, video.mimeType)
  }

  /**
   * Plays the item mpvRx is playing. Local media is handed over as a file or MediaStore item;
   * streams use the URL mpv plays, with its request headers in Kodi's `url|Header=value` form.
   */
  fun play(
    context: Context,
    item: PlaybackItem,
  ): Boolean {
    val original = Uri.parse(item.originalUri)
    val target =
      if (original.scheme.isLocalScheme()) {
        kodiUri(context, original)
      } else {
        kodiUri(context, Uri.parse(item.playableUri), headers = item.headers)
          ?: kodiUri(context, original)
      } ?: return false
    return play(context, target, item.mimeType)
  }

  private fun play(
    context: Context,
    uri: Uri,
    mimeType: String?,
  ): Boolean {
    val intent =
      splashIntent(context).apply {
        action = Intent.ACTION_VIEW
        setDataAndType(uri, mimeType?.takeIf(String::isNotBlank) ?: "video/*")
      }
    return start(context, intent)
  }

  /** The form of [uri] that Kodi can open, or null. Prefers a readable file for local media. */
  private fun kodiUri(
    context: Context,
    uri: Uri,
    path: String? = null,
    headers: Map<String, String> = emptyMap(),
  ): Uri? {
    existingFile(path)?.let { return Uri.fromFile(it) }
    val scheme = uri.scheme?.lowercase()
    return when {
      // Uri.parse keeps a plain path unchanged in toString().
      scheme == null || scheme == "file" -> existingFile(uri.toString())?.let(Uri::fromFile)
      scheme == "content" ->
        documentFile(uri)?.let(Uri::fromFile)
          ?: uri.takeIf { it.authority == MediaStore.AUTHORITY }
      scheme in NETWORK_SCHEMES -> withHeaders(uri, scheme, headers)
      else -> null
    }
  }

  private fun withHeaders(
    uri: Uri,
    scheme: String,
    headers: Map<String, String>,
  ): Uri {
    if (headers.isEmpty() || scheme !in HEADER_SCHEMES) return uri
    val options =
      headers.entries.joinToString("&") { (name, value) ->
        "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
      }
    // Kodi reads URL-encoded protocol options after '|'; Uri.parse keeps the string as given.
    return Uri.parse("$uri|$options")
  }

  private fun documentFile(uri: Uri): File? {
    if (uri.authority != EXTERNAL_STORAGE_DOCUMENTS) return null
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    val volume = documentId.substringBefore(':')
    val root =
      if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory()
      } else {
        File("/storage", volume)
      }
    return File(root, documentId.substringAfter(':', "")).takeIf(File::isFile)
  }

  private fun existingFile(path: String?): File? {
    val file =
      when {
        path.isNullOrBlank() -> null
        path.startsWith("/") -> File(path)
        path.startsWith("file:", ignoreCase = true) -> Uri.parse(path).path?.let(::File)
        else -> null
      }
    return file?.takeIf(File::isFile)
  }

  private fun String?.isLocalScheme(): Boolean =
    this == null || equals("file", ignoreCase = true) || equals("content", ignoreCase = true)

  private fun splashIntent(context: Context): Intent =
    Intent()
      .setClassName(context.packageName, SPLASH_ACTIVITY)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  private fun start(
    context: Context,
    intent: Intent,
  ): Boolean {
    if (!isAvailable(context)) return false
    return try {
      context.startActivity(intent)
      true
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "Kodi is not part of this build", e)
      false
    } catch (e: SecurityException) {
      Log.w(TAG, "Cannot start Kodi", e)
      false
    }
  }
}
