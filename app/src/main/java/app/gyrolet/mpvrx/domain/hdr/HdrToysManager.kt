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
import app.gyrolet.mpvrx.utils.logging.Log
import java.io.File
import java.io.FileOutputStream

/** Abstraction over the running player's shader pipeline so the domain layer stays UI-free. */
interface MpvShaderRuntime {
  fun command(vararg args: String)

  fun getPropertyString(property: String): String?

  fun setPropertyString(
    property: String,
    value: String,
  )
}

/**
 * Manages the hdr-toys GLSL shader pipeline.
 *
 * On first use it copies the bundled shaders of every [HdrToysProfile] from assets into
 * [Context.filesDir]/shaders/hdr-toys/ so that mpv can reference them via
 * the `~~/shaders/` config-dir prefix, and deletes any other file there.  Subsequent
 * calls reuse the cached files unless they have been deleted.
 *
 * Usage:
 *  - [initialize] — call once; safe to call repeatedly (idempotent).
 *  - [apply]      — load a [HdrToysProfile]'s shader chain into the running mpv instance.
 *  - [clear]      — remove all hdr-toys shaders from mpv without affecting other shaders.
 */
class HdrToysManager(
  private val context: Context,
  private val shaderRuntime: MpvShaderRuntime,
) {
  private var initialized = false
  private val shadersRoot = File(context.filesDir, SHADERS_DIR)

  @Synchronized
  fun initialize(): Boolean {
    if (initialized && requiredShadersExist()) return true

    return runCatching {
      HdrToysProfile.allShaderPaths.forEach { shaderPath ->
        copyAssetFile("$SHADERS_DIR/$shaderPath", File(shadersRoot, shaderPath))
      }
      removeUnusedShaders()
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
      shaderRuntime.command("change-list", "glsl-shaders", "append", shaderPath)
    }
    return true
  }

  /** Removes every hdr-toys shader from mpv's active glsl-shaders list. */
  fun clear() {
    HdrToysProfile.allMpvShaderPaths
      .toList()
      .asReversed()
      .forEach { shaderPath ->
        runCatching { shaderRuntime.command("change-list", "glsl-shaders", "remove", shaderPath) }
      }
    HdrToysProfile.allShaderPaths.forEach { relPath ->
      val absolutePath = File(shadersRoot, relPath).absolutePath
      runCatching { shaderRuntime.command("change-list", "glsl-shaders", "remove", absolutePath) }
    }
    runCatching {
      val activeShaders = shaderRuntime.getPropertyString("glsl-shaders")
      if (!activeShaders.isNullOrEmpty()) {
        val remaining =
          activeShaders
            .split(":")
            .map { it.trim() }
            .filter { path -> path.isNotEmpty() && !path.contains("hdr-toys") }
        shaderRuntime.setPropertyString("glsl-shaders", remaining.joinToString(":"))
      }
    }
  }

  private fun requiredShadersExist(): Boolean =
    HdrToysProfile.allShaderPaths.all { shaderPath ->
      val file = File(shadersRoot, shaderPath)
      file.exists() && file.length() > 0L
    }

  /** Older versions copied every hdr-toys shader, most of which no profile uses. */
  private fun removeUnusedShaders() {
    File(shadersRoot, TARGET_DIR).walkBottomUp().forEach { file ->
      val relativePath = file.relativeTo(shadersRoot).invariantSeparatorsPath
      when {
        file.isFile && relativePath !in HdrToysProfile.allShaderPaths -> file.delete()
        file.isDirectory && file.list().isNullOrEmpty() -> file.delete()
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
    const val SHADERS_DIR = "shaders"
    const val TARGET_DIR = "hdr-toys"
  }
}
