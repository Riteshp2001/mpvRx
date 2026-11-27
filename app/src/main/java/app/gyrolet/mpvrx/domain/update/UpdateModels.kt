/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Release(
  @SerialName("tag_name") val tagName: String,
  @SerialName("name") val name: String,
  @SerialName("body") val body: String,
  @SerialName("published_at") val publishedAt: String,
  @SerialName("assets") val assets: List<Asset>,
  // GitHub releases have no channel; only records saved from retired feeds carry one.
  @SerialName("channel") val channel: String? = null,
)

@Serializable
data class Asset(
  @SerialName("browser_download_url") val downloadUrl: String,
  @SerialName("name") val name: String,
  @SerialName("size") val size: Long,
  @SerialName("content_type") val contentType: String,
  // GitHub release assets publish "digest" as "sha256:<hex>".
  @SerialName("digest") val digest: String? = null,
) {
  val expectedSha256: String?
    get() =
      digest
        ?.takeIf { it.startsWith(SHA256_DIGEST_PREFIX, ignoreCase = true) }
        ?.drop(SHA256_DIGEST_PREFIX.length)
        ?.trim()
        ?.lowercase()
        ?.takeIf { SHA256_HEX_REGEX.matches(it) }

  /** Human-readable package identity, e.g. "Standard · arm64-v8a" or "FongMi · Universal". */
  val variantLabel: String
    get() {
      val flavor =
        when {
          name.hasApkAssetToken(APK_VARIANT_FONGMI) -> "FongMi"
          name.hasApkAssetToken(APK_VARIANT_NO_VULKAN) -> "No Vulkan"
          name.hasApkAssetToken(APK_VARIANT_PLAYSTORE) -> "Play Store"
          else -> "Standard"
        }
      val abi = APK_ABIS.firstOrNull { name.hasApkAssetToken(it) } ?: "Universal"
      return "$flavor · $abi"
    }

  companion object {
    private const val SHA256_DIGEST_PREFIX = "sha256:"
    private val SHA256_HEX_REGEX = Regex("[0-9a-f]{64}")
  }
}

internal const val APK_UNIVERSAL = "universal"
internal const val APK_VARIANT_FONGMI = "fongmi"
internal const val APK_VARIANT_NO_VULKAN = "no-vulkan"
internal const val APK_VARIANT_PLAYSTORE = "playstore"

/** ABI splits published next to the universal APK, matching the Android ABI names. */
internal val APK_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

/** Matches a dash-separated token in an asset name such as `mpvRx-arm64-v8a-v3.1.1.apk`. */
internal fun String.hasApkAssetToken(token: String): Boolean =
  Regex(
    pattern = "(?:^|-)${Regex.escape(token)}(?:-|\\.apk$)",
    option = RegexOption.IGNORE_CASE,
  ).containsMatchIn(this)
