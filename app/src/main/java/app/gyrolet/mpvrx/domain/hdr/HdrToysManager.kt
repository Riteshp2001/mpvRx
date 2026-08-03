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
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the hdr-toys GLSL shader pipeline.
 *
 * On first use it copies all bundled hdr-toys shaders from assets into
 * [Context.filesDir]/shaders/hdr-toys/ so that mpv can reference them via
 * the `~~/shaders/` config-dir prefix.  Subsequent calls reuse the cached
 * files unless they have been deleted.
 *
 * Usage:
 *  - [initialize] — call once; safe to call repeatedly (idempotent).
 *  - [apply]      — load a [HdrToysProfile]'s shader chain into the running mpv instance.
 *  - [clear]      — remove all hdr-toys shaders from mpv without affecting other shaders.
 */
class HdrToysManager(
  private val context: Context,
) {
  private var initialized = false

  @Synchronized
  fun initialize(): Boolean {
    if (initialized && requiredShadersExist()) return true

    return runCatching {
      val destination = File(context.filesDir, TARGET_DIR)
      destination.mkdirs()
      copyAssetDirectory(ASSET_DIR, destination)
      val ready = requiredShadersExist()
      initialized = ready
      ready
    }.onFailure { error ->
      initialized = false
      Log.w(TAG, "Failed to initialize hdr-toys shaders", error)
    }.getOrDefault(false)
  }

  /**
   * Applies [profile]'s shader chain to the current mpv instance.
   * Returns true if all shaders were successfully appended.
   */
  fun apply(profile: HdrToysProfile): Boolean {
    if (!initialize()) {
      clear()
      return false
    }
    clear()
    profile.mpvShaderPaths.forEach { shaderPath ->
      MPVLib.command("change-list", "glsl-shaders", "append", shaderPath)
    }
    return true
  }

  /** Removes every hdr-toys shader from mpv's active glsl-shaders list. */
  fun clear() {
    HdrToysProfile.allMpvShaderPaths
      .toList()
      .asReversed()
      .forEach { shaderPath ->
        runCatching { MPVLib.command("change-list", "glsl-shaders", "remove", shaderPath) }
      }
    HdrToysProfile.allShaderPaths.forEach { relPath ->
      val absolutePath = File(context.filesDir, "shaders/$relPath").absolutePath
      runCatching { MPVLib.command("change-list", "glsl-shaders", "remove", absolutePath) }
    }
    runCatching {
      val activeShaders = MPVLib.getPropertyString("glsl-shaders")
      if (!activeShaders.isNullOrEmpty()) {
        val remaining =
          activeShaders
            .split(":")
            .map { it.trim() }
            .filter { path -> path.isNotEmpty() && !path.contains("hdr-toys") }
        MPVLib.setPropertyString("glsl-shaders", remaining.joinToString(":"))
      }
    }
  }

  private fun requiredShadersExist(): Boolean =
    HdrToysProfile.allShaderPaths.all { shaderPath ->
      val file = File(context.filesDir, "shaders/$shaderPath")
      file.exists() && file.length() > 0L
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
      if (nestedChildren.isEmpty() && child.endsWith(".glsl")) {
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
    const val ASSET_DIR = "shaders/hdr-toys"
    const val TARGET_DIR = "shaders/hdr-toys"
  }
}
