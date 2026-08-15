/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import java.math.BigDecimal
import java.net.URI

internal const val SEEK_PREVIEW_CACHE_BUCKETS_PER_SECOND = 1.0
internal const val SEEK_PREVIEW_EOF_MARGIN_SECONDS = 0.1

internal fun normalizeSeekPreviewPosition(
  positionSeconds: Double,
  durationSeconds: Double,
): Double {
  val normalized = positionSeconds.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
  return if (durationSeconds.isFinite() && durationSeconds > 0.0) {
    normalized.coerceAtMost(durationSeconds)
  } else {
    normalized
  }
}

internal fun formatSeekPreviewSeconds(positionSeconds: Double): String =
  BigDecimal
    .valueOf(positionSeconds.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0)
    .stripTrailingZeros()
    .toPlainString()

internal fun seekPreviewBucket(positionSeconds: Double): Int =
  (normalizeSeekPreviewPosition(positionSeconds, Double.NaN) * SEEK_PREVIEW_CACHE_BUCKETS_PER_SECOND)
    .toInt()
    .coerceAtLeast(0)

internal fun seekPreviewBucketStart(bucket: Int): Double =
  bucket.coerceAtLeast(0) / SEEK_PREVIEW_CACHE_BUCKETS_PER_SECOND

internal fun safeSeekThumbnailTime(
  positionSeconds: Double,
  durationSeconds: Double,
): Double {
  val normalized = normalizeSeekPreviewPosition(positionSeconds, durationSeconds)
  val perSecondTarget = seekPreviewBucketStart(seekPreviewBucket(normalized))
  if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return perSecondTarget
  return perSecondTarget.coerceAtMost((durationSeconds - SEEK_PREVIEW_EOF_MARGIN_SECONDS).coerceAtLeast(0.0))
}

internal fun firstSeekPreviewSource(vararg candidates: String?): String? =
  candidates.firstNotNullOfOrNull { candidate -> candidate?.trim()?.takeIf(String::isNotEmpty) }

internal fun isNetworkSeekPreviewSource(
  source: String,
  demuxerViaNetwork: Boolean,
): Boolean {
  if (demuxerViaNetwork) return true
  val scheme = runCatching { URI(source).scheme?.lowercase() }.getOrNull() ?: return false
  return scheme in
    setOf(
      "http",
      "https",
      "ftp",
      "ftps",
      "rtsp",
      "rtmp",
      "rtmps",
      "mms",
      "mmsh",
      "srt",
      "rist",
      "udp",
      "tcp",
    )
}

@Suppress("LongParameterList")
internal fun seekPreviewCacheKey(
  sourceIdentity: String,
  generation: Long,
  bucket: Int,
  dimension: Int,
  videoTrack: Int,
  edition: Int,
  rotation: Int,
): String =
  buildString {
    append(sourceIdentity)
    append("|g=")
    append(generation)
    append("|b=")
    append(bucket)
    append("|d=")
    append(dimension)
    append("|vid=")
    append(videoTrack)
    append("|edition=")
    append(edition)
    append("|rotation=")
    append(((rotation % 360) + 360) % 360)
  }
