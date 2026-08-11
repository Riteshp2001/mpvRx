/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.hdr

import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import java.io.File
import java.io.FileOutputStream

/**
 * Manages HDR Toys for both mpv renderers used by mpvRx.
 *
 * The main [LATEST_ROOT] is a byte-for-byte copy of current upstream HDR Toys and is used by
 * gpu-next. Current upstream explicitly targets gpu-next and now relies on libplacebo shader
 * features that legacy vo=gpu does not fully implement. To preserve mpvRx v2.0.0 behaviour on
 * legacy gpu/OpenGL, [LEGACY_ROOT] carries the known-working HDR Toys tree from the v2.0.0 tag.
 *
 * LINEAR HDR is not an HDR Toys profile at runtime; PlayerViewModel/MPVView restrict it to
 * gpu-next + Vulkan separately.
 */
class HdrToysManager(
  private val context: Context,
) {
  private var initialized = false

  @Synchronized
  fun initialize(): Boolean {
    if (initialized && requiredShadersExist()) return true

    return runCatching {
      copyAssetDirectory(
        assetPath = "$ASSET_BASE/$LATEST_ROOT",
        destination = File(context.filesDir, "$TARGET_BASE/$LATEST_ROOT"),
      )
      copyAssetDirectory(
        assetPath = "$ASSET_BASE/$LEGACY_ROOT",
        destination = File(context.filesDir, "$TARGET_BASE/$LEGACY_ROOT"),
      )
      val ready = requiredShadersExist()
      initialized = ready
      ready
    }.onFailure { error ->
      initialized = false
      Log.w(TAG, "Failed to initialize HDR Toys shader sets", error)
    }.getOrDefault(false)
  }

  /**
   * Apply the selected HDR Toys profile.
   *
   * [legacyGpu] selects the v2.0.0-compatible shader tree for vo=gpu. gpu-next always receives the
   * latest pinned upstream shader set.
   */
  fun apply(
    profile: HdrToysProfile,
    legacyGpu: Boolean = false,
  ): Boolean {
    if (!initialize()) {
      clear()
      return false
    }

    clear()
    val root = if (legacyGpu) LEGACY_ROOT else LATEST_ROOT
    shaderPaths(profile, root).forEach { shaderPath ->
      PlaybackSession.command("change-list", "glsl-shaders", "append", shaderPath)
    }
    Log.d(TAG, "Applied ${profile.name} using $root (${if (legacyGpu) "vo=gpu" else "gpu-next"})")
    return true
  }

  /** Removes both current and legacy HDR Toys paths without affecting other shader stacks. */
  fun clear() {
    SHADER_ROOTS.forEach { root ->
      HdrToysProfile.entries.forEach { profile ->
        shaderPaths(profile, root)
          .asReversed()
          .forEach { shaderPath ->
            runCatching { PlaybackSession.command("change-list", "glsl-shaders", "remove", shaderPath) }
          }
      }

      HdrToysProfile.allShaderPaths.forEach { originalPath ->
        val relative = stripOriginalRoot(originalPath)
        val absolutePath = File(context.filesDir, "$TARGET_BASE/$root/$relative").absolutePath
        runCatching { PlaybackSession.command("change-list", "glsl-shaders", "remove", absolutePath) }
      }
    }

    runCatching {
      val activeShaders = PlaybackSession.getPropertyString("glsl-shaders")
      if (!activeShaders.isNullOrEmpty()) {
        val remaining =
          activeShaders
            .split(":")
            .map { it.trim() }
            .filter { path -> path.isNotEmpty() && !path.contains("hdr-toys") }
        PlaybackSession.setPropertyString("glsl-shaders", remaining.joinToString(":"))
      }
    }
  }

  private fun shaderPaths(
    profile: HdrToysProfile,
    root: String,
  ): List<String> =
    profile.shaderPaths.map { originalPath ->
      "~~/shaders/$root/${stripOriginalRoot(originalPath)}"
    }

  private fun stripOriginalRoot(path: String): String = path.removePrefix("hdr-toys/")

  private fun requiredShadersExist(): Boolean =
    SHADER_ROOTS.all { root ->
      HdrToysProfile.allShaderPaths.all { originalPath ->
        val relative = stripOriginalRoot(originalPath)
        val file = File(context.filesDir, "$TARGET_BASE/$root/$relative")
        file.exists() && file.length() > 0L
      }
    }

  private fun copyAssetDirectory(
    assetPath: String,
    destination: File,
  ) {
    val children = context.assets.list(assetPath).orEmpty()
    destination.mkdirs()
    children.forEach { child ->
      val childAssetPath = "$assetPath/$child"
      val childDestination = File(destination, child)
      val nestedChildren = context.assets.list(childAssetPath).orEmpty()
      if (nestedChildren.isEmpty()) {
        copyAssetFile(childAssetPath, childDestination)
      } else {
        copyAssetDirectory(childAssetPath, childDestination)
      }
    }
  }

  private fun copyAssetFile(
    assetPath: String,
    destination: File,
  ) {
    destination.parentFile?.mkdirs()
    context.assets.open(assetPath).use { input ->
      FileOutputStream(destination).use { output ->
        input.copyTo(output)
      }
    }
  }

  private companion object {
    const val TAG = "HdrToysManager"
    const val ASSET_BASE = "shaders"
    const val TARGET_BASE = "shaders"
    const val LATEST_ROOT = "hdr-toys"
    const val LEGACY_ROOT = "hdr-toys-legacy"
    val SHADER_ROOTS = listOf(LATEST_ROOT, LEGACY_ROOT)
  }
}
