/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.TransactionTooLargeException
import android.widget.Toast
import java.nio.charset.StandardCharsets

object SafeClipboard {
  const val MAX_CLIPBOARD_BYTES: Int = 512 * 1024
  private const val RETRY_BYTES: Int = 128 * 1024
  private const val LEGACY_DEBUG_DUMP_LABEL = "mpvrx_logs"

  data class TruncatedText(
    val text: String,
    val originalBytes: Int,
    val copiedBytes: Int,
    val truncated: Boolean,
  )

  data class CopyResult(
    val copiedBytes: Int,
    val originalBytes: Int,
    val truncated: Boolean,
  )

  fun copyPlainText(
    context: Context,
    label: String,
    text: CharSequence,
    showToast: Boolean = true,
  ): CopyResult {
    val rawText = text.toString()

    // Advanced Settings historically copied the entire dump before opening the debug-log UI.
    // The interactive viewer now owns explicit Copy/Share/Export actions, so opening "Dump logs"
    // must not mutate the user's clipboard or show a misleading "Copied" toast. Keep this guard
    // narrowly scoped to that legacy call site; crash-report and explicit-copy labels are unchanged.
    if (label == LEGACY_DEBUG_DUMP_LABEL) {
      val byteCount = rawText.toByteArray(StandardCharsets.UTF_8).size
      return CopyResult(copiedBytes = 0, originalBytes = byteCount, truncated = false)
    }

    val clipboard =
      context.getSystemService(ClipboardManager::class.java)
        ?: error("Clipboard service unavailable")
    val first = truncateUtf8(rawText, MAX_CLIPBOARD_BYTES)
    return try {
      clipboard.setPrimaryClip(ClipData.newPlainText(label, first.text))
      if (showToast) showToast(context, first.toastMessage())
      CopyResult(first.copiedBytes, first.originalBytes, first.truncated)
    } catch (error: TransactionTooLargeException) {
      retrySmallClipboard(context, clipboard, label, rawText, showToast)
    } catch (error: RuntimeException) {
      if (error.message?.contains("TransactionTooLarge", ignoreCase = true) == true) {
        retrySmallClipboard(context, clipboard, label, rawText, showToast)
      } else {
        throw error
      }
    }
  }

  fun truncateUtf8(
    text: String,
    maxBytes: Int = MAX_CLIPBOARD_BYTES,
  ): TruncatedText {
    val bytes = text.toByteArray(StandardCharsets.UTF_8).size
    if (bytes <= maxBytes) {
      return TruncatedText(text = text, originalBytes = bytes, copiedBytes = bytes, truncated = false)
    }

    fun suffixFor(copiedBytes: Int) =
      "\n\n[MPVRX: copied first $copiedBytes of $bytes bytes. Use Share/Export for full content.]"

    val suffixBytes = suffixFor(0).toByteArray(StandardCharsets.UTF_8).size
    val budget = (maxBytes - suffixBytes).coerceAtLeast(0)
    val builder = StringBuilder()
    var index = 0
    var copiedBytes = 0
    while (index < text.length) {
      val codePoint = Character.codePointAt(text, index)
      val charCount = Character.charCount(codePoint)
      val token = String(Character.toChars(codePoint))
      val tokenBytes = token.toByteArray(StandardCharsets.UTF_8).size
      if (copiedBytes + tokenBytes > budget) break
      builder.append(token)
      copiedBytes += tokenBytes
      index += charCount
    }

    var suffix = suffixFor(copiedBytes)
    var output = builder.toString() + suffix
    while (output.toByteArray(StandardCharsets.UTF_8).size > maxBytes && builder.isNotEmpty()) {
      val lastCodePointStart = builder.offsetByCodePoints(builder.length, -1)
      val removed = builder.substring(lastCodePointStart)
      copiedBytes -= removed.toByteArray(StandardCharsets.UTF_8).size
      builder.delete(lastCodePointStart, builder.length)
      suffix = suffixFor(copiedBytes)
      output = builder.toString() + suffix
    }
    return TruncatedText(
      text = output,
      originalBytes = bytes,
      copiedBytes = output.toByteArray(StandardCharsets.UTF_8).size,
      truncated = true,
    )
  }

  private fun retrySmallClipboard(
    context: Context,
    clipboard: ClipboardManager,
    label: String,
    text: String,
    showToast: Boolean,
  ): CopyResult {
    val small = truncateUtf8(text, RETRY_BYTES)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, small.text))
    if (showToast) showToast(context, small.toastMessage())
    return CopyResult(small.copiedBytes, small.originalBytes, truncated = true)
  }

  private fun TruncatedText.toastMessage(): String =
    if (truncated) {
      "Copied truncated text (${copiedBytes / 1024} KB of ${originalBytes / 1024} KB)"
    } else {
      "Copied to clipboard"
    }

  private fun showToast(
    context: Context,
    message: String,
  ) {
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
  }
}
