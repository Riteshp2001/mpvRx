/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Reads whether an MP4/MOV/3GP video track is stored a quarter turn from its display orientation.
 *
 * MediaStore indexes video rotation only from Android 11, so older devices launch phone
 * recordings with rotation 0. This walks box headers only (moov -> trak -> tkhd, mdia -> hdlr)
 * and skips every payload it does not need, so it never reads sample tables or media data.
 * Box visits and elapsed time are capped, keeping it safe to run before the player window exists.
 */
internal object ContainerRotationProbe {
  private val isoBmffExtensions = setOf("mp4", "m4v", "mov", "qt", "3gp", "3g2")
  private const val MAX_BOX_VISITS = 512
  private const val TIME_BUDGET_NANOS = 40_000_000L
  private const val FIXED_ONE = 0x10000

  /** 90 for a quarter-turned video track, 0 for an upright one, null when unknown. */
  fun quarterTurnDegrees(path: String): Int? {
    if (path.substringAfterLast('.', "").lowercase() !in isoBmffExtensions) return null
    return runCatching {
      RandomAccessFile(path, "r").use { file -> Reader(file).videoTrackQuarterTurn() }
    }.getOrNull()
  }

  private class Box(val type: String, val payloadStart: Long, val end: Long)

  private class Reader(private val file: RandomAccessFile) {
    private val deadline = System.nanoTime() + TIME_BUDGET_NANOS
    private var visits = 0

    fun videoTrackQuarterTurn(): Int? {
      val moov = children(0L, file.length()).firstOrNull { it.type == "moov" } ?: return null
      for (trak in children(moov.payloadStart, moov.end).filter { it.type == "trak" }) {
        var matrixRotation: Int? = null
        var isVideo = false
        for (box in children(trak.payloadStart, trak.end)) {
          when (box.type) {
            "tkhd" -> matrixRotation = readTrackHeaderRotation(box)
            "mdia" -> isVideo = isVideoMedia(box)
          }
        }
        if (isVideo) return matrixRotation
      }
      return null
    }

    private fun children(start: Long, end: Long): Sequence<Box> =
      sequence {
        var position = start
        while (position + 8 <= end) {
          if (++visits > MAX_BOX_VISITS || System.nanoTime() > deadline) return@sequence
          file.seek(position)
          val size32 = file.readInt().toLong() and 0xFFFFFFFFL
          val type = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
          val (size, headerSize) =
            when (size32) {
              0L -> (end - position) to 8L
              1L -> file.readLong() to 16L
              else -> size32 to 8L
            }
          if (size < headerSize || position + size > end) return@sequence
          yield(Box(type, position + headerSize, position + size))
          position += size
        }
      }

    private fun isVideoMedia(mdia: Box): Boolean {
      val hdlr = children(mdia.payloadStart, mdia.end).firstOrNull { it.type == "hdlr" } ?: return false
      if (hdlr.end - hdlr.payloadStart < 12) return false
      // version/flags (4) and pre_defined (4) precede the handler type.
      file.seek(hdlr.payloadStart + 8)
      return ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII) == "vide"
    }

    private fun readTrackHeaderRotation(tkhd: Box): Int? {
      file.seek(tkhd.payloadStart)
      val version = file.readUnsignedByte()
      // flags (3) + times/ids/duration (version 1: 32, version 0: 20) + reserved/layer/group/volume (16).
      val matrixOffset = 3L + (if (version == 1) 32L else 20L) + 16L
      if (tkhd.end - tkhd.payloadStart < 1L + matrixOffset + 36L) return null
      file.seek(tkhd.payloadStart + 1L + matrixOffset)
      val matrix = IntArray(9) { file.readInt() }
      val a = matrix[0]
      val b = matrix[1]
      val c = matrix[3]
      val d = matrix[4]
      val near = FIXED_ONE / 256
      return when {
        abs(a) < near && abs(d) < near && abs(abs(b) - FIXED_ONE) < near && abs(abs(c) - FIXED_ONE) < near -> 90
        abs(b) < near && abs(c) < near -> 0
        else -> null
      }
    }
  }
}
