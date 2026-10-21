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

enum class AppUpdateChannel {
  STABLE,
  PREVIEW,
  EXPERIMENTAL,
  ;

  companion object {
    fun fromStoredValue(value: String?): AppUpdateChannel =
      entries.firstOrNull { channel -> channel.name.equals(value, ignoreCase = true) } ?: STABLE
  }
}

@Serializable
data class Release(
  @SerialName("tag_name") val tagName: String,
  @SerialName("name") val name: String = "",
  @SerialName("body") val body: String = "",
  @SerialName("published_at") val publishedAt: String = "",
  @SerialName("assets") val assets: List<Asset> = emptyList(),
  @SerialName("channel") val channel: String? = null,
  @SerialName("commit_count") val commitCount: Int? = null,
  @SerialName("commit_sha") val commitSha: String? = null,
  @SerialName("updated_at") val updatedAt: String? = null,
  @SerialName("commit_title") val commitTitle: String? = null,
  @SerialName("commit_message") val commitMessage: String? = null,
  @SerialName("target_commitish") val targetCommitish: String? = null,
  @SerialName("prerelease") val prerelease: Boolean = false,
  @SerialName("draft") val draft: Boolean = false,
) {
  val isPreview: Boolean
    get() =
      channel.equals("preview", ignoreCase = true) || tagName.startsWith("preview-r", ignoreCase = true) ||
        tagName.contains("-preview", ignoreCase = true)

  val previewBuildNumber: Int?
    get() =
      if (isPreview) {
        commitCount ?: PREVIEW_TAG_REGEX.find(tagName)?.groupValues?.getOrNull(1)?.toIntOrNull()
      } else {
        null
      }

  val isExperimental: Boolean
    get() =
      channel.equals("experimental", ignoreCase = true) || tagName.equals("experimental", ignoreCase = true) ||
        tagName.startsWith("experimental-r", ignoreCase = true)

  val experimentalBuildNumber: Int?
    get() =
      if (isExperimental) {
        commitCount ?: BUILD_NUMBER_REGEX.find(tagName)?.groupValues?.getOrNull(1)?.toIntOrNull()
          ?: BUILD_NUMBER_REGEX.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
      } else {
        null
      }

  /** Preview and experimental feeds both describe one CI build of master; stable releases do not. */
  private val ciChannelName: String?
    get() =
      when {
        isExperimental -> "experimental"
        isPreview -> "preview"
        else -> null
      }

  private val ciBuildNumber: Int?
    get() = experimentalBuildNumber ?: previewBuildNumber

  val sourceCommit: String?
    get() = (commitSha ?: targetCommitish)?.trim()?.takeIf { Regex("[0-9a-fA-F]{7,40}").matches(it) }

  // Archived CI releases carry the commit title in their name and the full message in their notes.
  val displayCommitTitle: String?
    get() = commitTitle?.takeIf { it.isNotBlank() }
      ?: name.substringAfter(" · ", "").takeIf { it.isNotBlank() }

  val displayCommitMessage: String?
    get() = commitMessage?.takeIf { it.isNotBlank() }
      ?: body.takeIf { displayCommitTitle != null && it.isNotBlank() }

  fun belongsTo(channel: AppUpdateChannel): Boolean =
    !draft && when (channel) {
      AppUpdateChannel.STABLE -> !isPreview && !isExperimental && !prerelease
      AppUpdateChannel.PREVIEW -> isPreview
      AppUpdateChannel.EXPERIMENTAL -> isExperimental
    }

  // CI feeds are rolling, and an amended or force-pushed master republishes the same commit count,
  // so the key carries the commit: ignoring one build must not ignore a different build at that count.
  val updateKey: String
    get() {
      val channelName = ciChannelName ?: return tagName
      val buildNumber = ciBuildNumber ?: return tagName
      val commit = sourceCommit?.take(SHORT_SHA_LENGTH)
      return listOfNotNull("$channelName-r$buildNumber", commit).joinToString("-")
    }

  /** Deduplicate rolling/archived copies, while retaining distinct rebuilds of one commit. */
  val historyKey: String
    get() = updateKey + assets.sortedBy { it.name }.joinToString(separator = "|", prefix = "|") {
      it.expectedSha256 ?: it.downloadUrl
    }

  // Old assets stay available during publication. Never select one from a different build.
  val updateAssets: List<Asset>
    get() {
      val channelName = ciChannelName ?: return assets
      // Older tagged previews predate the r<count> CI naming scheme.
      if (isPreview && tagName.startsWith("v", ignoreCase = true)) return assets
      val buildNumber = ciBuildNumber ?: return emptyList()
      return assets.filter { it.name.startsWith("mpvRx-$channelName-r$buildNumber-", ignoreCase = true) }
    }

  companion object {
    private const val SHORT_SHA_LENGTH = 7
    private val PREVIEW_TAG_REGEX = Regex("""(?:preview-)?r(\d+)""", RegexOption.IGNORE_CASE)
    private val BUILD_NUMBER_REGEX = Regex("""\br(\d+)\b""", RegexOption.IGNORE_CASE)
  }
}

@Serializable
data class Asset(
  @SerialName("browser_download_url") val downloadUrl: String,
  @SerialName("name") val name: String,
  @SerialName("size") val size: Long,
  @SerialName("content_type") val contentType: String,
  // Pages feeds publish "sha256"; GitHub release assets publish "digest" as "sha256:<hex>".
  @SerialName("sha256") val sha256: String? = null,
  @SerialName("digest") val digest: String? = null,
) {
  val expectedSha256: String?
    get() =
      (sha256 ?: digest?.takeIf { it.startsWith(SHA256_DIGEST_PREFIX, ignoreCase = true) }?.drop(SHA256_DIGEST_PREFIX.length))
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

/** Matches a dash-separated token in an asset name such as `mpvRx-preview-r1969-arm64-v8a.apk`. */
internal fun String.hasApkAssetToken(token: String): Boolean =
  Regex(
    pattern = "(?:^|-)${Regex.escape(token)}(?:-|\\.apk$)",
    option = RegexOption.IGNORE_CASE,
  ).containsMatchIn(this)

