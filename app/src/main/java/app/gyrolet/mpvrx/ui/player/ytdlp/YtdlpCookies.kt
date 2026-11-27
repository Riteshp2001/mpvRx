// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.player.ytdlp

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

internal class InvalidYtdlpCookiesException : IOException()

internal object YtdlpCookies {
  private const val MAX_COOKIE_BYTES = 1_048_576
  private const val COOKIE_FILE = "youtube-cookies.txt"

  fun isYouTubeSource(source: String): Boolean {
    val uri = Uri.parse(source)
    if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return false
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    return host == "youtu.be" ||
      host == "youtube.com" ||
      host.endsWith(".youtube.com") ||
      host == "youtube-nocookie.com" ||
      host.endsWith(".youtube-nocookie.com")
  }

  fun isAuthenticationError(message: String): Boolean {
    val text = message.lowercase(Locale.ROOT)
    return (
      text.contains("sign in") &&
        (
          text.contains("not a bot") ||
            text.contains("confirm your age") ||
            text.contains("verify your age") ||
            text.contains("private video")
        )
    ) ||
      text.contains("login required") ||
      text.contains("cookies are no longer valid") ||
      text.contains("cookies have expired") ||
      text.contains("failed to load cookies") ||
      text.contains("could not load cookies")
  }

  fun hasCookieOptionOverride(rawOptions: String): Boolean =
    YtdlpOptionsBuilder.parseRawOptions(rawOptions).any {
      it.key.lowercase(Locale.ROOT) in setOf("cookies", "no-cookies", "cookies-from-browser")
    }

  fun importCookies(
    context: Context,
    uri: Uri,
  ): File {
    val bytes =
      context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          if (output.size() + count > MAX_COOKIE_BYTES) throw InvalidYtdlpCookiesException()
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      } ?: throw IOException("Cookie document could not be opened")
    val payload = validatedYouTubeCookies(bytes.toString(Charsets.UTF_8))
    val destination = managedFile(context)
    val atomic = AtomicFile(destination)
    val stream = atomic.startWrite()
    try {
      stream.write(payload.toByteArray(Charsets.UTF_8))
      atomic.finishWrite(stream)
    } catch (error: Throwable) {
      atomic.failWrite(stream)
      throw error
    }
    return destination
  }

  fun clearManagedFile(context: Context) {
    val file = managedFile(context)
    listOf(file, File("${file.path}.bak"), File("${file.path}.new")).forEach { candidate ->
      if (candidate.exists() && !candidate.delete()) throw IOException("Cookie file could not be removed")
    }
  }

  private fun managedFile(context: Context): File = File(context.noBackupFilesDir, COOKIE_FILE)

  private fun validatedYouTubeCookies(content: String): String {
    val lines = content.removePrefix("\uFEFF").lineSequence().toList()
    val header = lines.firstOrNull()?.trim().orEmpty()
    if (header != "# Netscape HTTP Cookie File" && header != "# HTTP Cookie File") {
      throw InvalidYtdlpCookiesException()
    }
    val now = System.currentTimeMillis() / 1_000L
    val cookies =
      lines.drop(1).mapNotNull { line ->
        if (line.isBlank() || (line.startsWith("#") && !line.startsWith("#HttpOnly_"))) return@mapNotNull null
        val fields = line.split('\t')
        if (fields.size != 7) throw InvalidYtdlpCookiesException()
        val cookieDomain = fields[0].removePrefix("#HttpOnly_")
        val domain = cookieDomain.removePrefix(".").lowercase(Locale.ROOT)
        // Never import the unrelated account sessions in a whole-browser cookie export.
        if (domain != "youtube.com" && !domain.endsWith(".youtube.com")) return@mapNotNull null
        val expires = fields[4].toLongOrNull() ?: throw InvalidYtdlpCookiesException()
        if (fields[1] !in setOf("TRUE", "FALSE") ||
          fields[3] !in setOf("TRUE", "FALSE") ||
          (fields[1] == "TRUE") != cookieDomain.startsWith(".") ||
          !fields[2].startsWith("/") ||
          fields[5].isBlank() ||
          expires < 0L ||
          fields.any { field -> field.any { it.isISOControl() } }
        ) {
          throw InvalidYtdlpCookiesException()
        }
        line.takeIf { expires == 0L || expires > now }
      }
    if (cookies.isEmpty()) throw InvalidYtdlpCookiesException()
    return "# Netscape HTTP Cookie File\n" + cookies.joinToString("\n", postfix = "\n")
  }
}
