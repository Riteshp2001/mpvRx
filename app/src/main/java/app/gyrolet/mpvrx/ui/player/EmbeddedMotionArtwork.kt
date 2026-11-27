/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Keep compressed motion covers bounded; regular bitmaps remain the fallback. */
internal object EmbeddedMotionArtwork {
  private const val MAX_ENCODED_BYTES = 8 * 1024 * 1024

  fun animatedBytes(bytes: ByteArray?): ByteArray? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
      bytes == null ||
      bytes.size > MAX_ENCODED_BYTES ||
      bytes.size < 21
    ) {
      return null
    }

    val gif = bytes.matchesAscii(0, "GIF87a") || bytes.matchesAscii(0, "GIF89a")
    val animatedWebp =
      bytes.matchesAscii(0, "RIFF") &&
        bytes.matchesAscii(8, "WEBP") &&
        bytes.matchesAscii(12, "VP8X") &&
        (bytes[20].toInt() and 0x02) != 0
    return bytes.takeIf { gif || animatedWebp }
  }

  private fun ByteArray.matchesAscii(
    offset: Int,
    value: String,
  ): Boolean =
    offset >= 0 &&
      size >= offset + value.length &&
      value.indices.all { i -> this[offset + i].toInt() == value[i].code }
}

/** Only active covers decode animated frames, off the UI thread. */
@Composable
internal fun rememberMotionArtworkDrawable(bytes: ByteArray?): AnimatedImageDrawable? {
  val image by produceState<AnimatedImageDrawable?>(initialValue = null, key1 = bytes) {
    value =
      if (bytes == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        null
      } else {
        withContext(Dispatchers.Default) {
          runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
              val largest = maxOf(info.size.width, info.size.height)
              if (largest > 1024) decoder.setTargetSampleSize((largest + 1023) / 1024)
            } as? AnimatedImageDrawable
          }.getOrNull()
        }
      }
  }
  return image
}

/** Animation follows audio playback and the visible lifecycle. */
@Composable
internal fun MotionArtworkImage(
  drawable: AnimatedImageDrawable,
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
  fit: Boolean = false,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  AndroidView(
    modifier = modifier,
    factory = { context ->
      ImageView(context).apply {
        scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        setImageDrawable(drawable)
      }
    },
    update = { view ->
      view.scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
      if (view.drawable !== drawable) view.setImageDrawable(drawable)
    },
    onRelease = { view -> view.setImageDrawable(null) },
  )
  DisposableEffect(drawable, lifecycleOwner, isPlaying) {
    val lifecycle = lifecycleOwner.lifecycle

    fun syncAnimation() {
      if (isPlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        drawable.start()
      } else {
        drawable.stop()
      }
    }
    val observer = LifecycleEventObserver { _, _ -> syncAnimation() }
    lifecycle.addObserver(observer)
    syncAnimation()
    onDispose {
      lifecycle.removeObserver(observer)
      drawable.stop()
    }
  }
}
