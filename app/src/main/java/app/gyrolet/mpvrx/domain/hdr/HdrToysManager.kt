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
import app.gyrolet.mpvrx.ui.player.HdrToysRenderer
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import java.io.File
import java.io.FileOutputStream

/**
 * Installs and applies one exact, pinned HDR Toys shader snapshot.
 *
 * gpu-next selects the full Astra/Bottosson chain. vo=gpu selects portable Reinhard/Jedypod
 * shaders from that same pinned tree because Astra uses gpu-next-only BUFFER/STORAGE blocks and
 * unsigned tunable parameters.
 * Linear HDR never enters this manager; it remains mpv-native.
 */
class HdrToysManager(
  private val context: Context,
) {
  private var initialized = false

  @Synchronized
  fun initialize(): Boolean {
    if (initialized && requiredShadersExist()) return true
    if (requiredShadersExist()) {
      removeOldAppSnapshots()
      initialized = true
      return true
    }

    return runCatching {
      val destination = File(context.filesDir, "shaders/${HdrToysProfile.CURRENT_TARGET_DIR}")
      if (destination.exists()) destination.deleteRecursively()
      check(destination.mkdirs()) { "Could not create HDR Toys snapshot directory" }
      copyAssetDirectory(ASSET_DIR, destination)
      File(destination, COMPLETE_MARKER).writeText(UPSTREAM_COMMIT)
      removeOldAppSnapshots()
      requiredShadersExist().also { ready -> initialized = ready }
    }.onFailure { error ->
      initialized = false
      Log.w(TAG, "Failed to initialize HDR Toys shaders", error)
    }.getOrDefault(false)
  }

  /** Applies a backend-compatible, ordered shader chain to the active mpv core. */
  @Synchronized
  fun shaderPaths(
    profile: HdrToysProfile,
    renderer: HdrToysRenderer,
  ): List<String>? = if (initialize()) profile.mpvShaderPaths(renderer) else null

  /** Applies a backend-compatible, ordered shader chain to the active mpv core. */
  @Synchronized
  fun apply(
    profile: HdrToysProfile,
    renderer: HdrToysRenderer,
  ): Boolean {
    val shaderPaths = shaderPaths(profile, renderer)
    if (shaderPaths == null) {
      clear()
      return false
    }

    return PlaybackSession.updateShaderList { active ->
      val retained = active.filterNot { path -> path in ownedShaderPaths() }
      val ambientIndex = retained.indexOfFirst(::isAmbientShaderPath)
      if (ambientIndex < 0) retained + shaderPaths
      else retained.take(ambientIndex) + shaderPaths + retained.drop(ambientIndex)
    }
  }

  /** Removes only paths owned by the pinned snapshot, preserving every user shader. */
  @Synchronized
  fun clear() {
    PlaybackSession.updateShaderList { active ->
      active.filterNot { path -> path in ownedShaderPaths() }
    }
  }

  private fun ownedShaderPaths(): Set<String> {
    val mpvPaths =
      HdrToysRenderer.entries
        .flatMap { renderer -> HdrToysProfile.allMpvShaderPaths(renderer) }
    val absolutePaths =
      HdrToysProfile.allShaderPaths.map { path ->
        File(context.filesDir, "shaders/${HdrToysProfile.CURRENT_TARGET_DIR}/$path").absolutePath
      }
    return (mpvPaths + previousSnapshotShaderPaths() + absolutePaths).toSet()
  }

  private fun previousSnapshotShaderPaths(): Set<String> =
    PREVIOUS_SNAPSHOT_ROOTS
      .flatMap { root ->
        PREVIOUS_SHADER_PATHS.flatMap { path ->
          listOf(
            "~~/shaders/$root/$path",
            File(context.filesDir, "shaders/$root/$path").absolutePath,
          )
        }
      }.toSet()

  private fun isAmbientShaderPath(path: String): Boolean {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    return name.startsWith("ambient_") && name.endsWith(".glsl")
  }

  private fun requiredShadersExist(): Boolean {
    return runCatching {
      val root = File(context.filesDir, "shaders/${HdrToysProfile.CURRENT_TARGET_DIR}")
      val marker = File(root, COMPLETE_MARKER)
      marker.isFile && marker.readText().trim() == UPSTREAM_COMMIT &&
        HdrToysProfile.allShaderPaths.all { shaderPath ->
          File(root, shaderPath).let { file -> file.isFile && file.length() > 0L }
        }
    }.getOrDefault(false)
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
      FileOutputStream(destination).use { output -> input.copyTo(output) }
    }
  }

  private fun removeOldAppSnapshots() {
    val shadersDirectory = File(context.filesDir, "shaders").canonicalFile
    val oldSnapshots =
      PREVIOUS_SNAPSHOT_ROOTS
        .map { root -> File(shadersDirectory, root).canonicalFile }
        .filter { snapshot ->
          snapshot.parentFile == shadersDirectory && snapshot.name in PREVIOUS_SNAPSHOT_ROOTS && snapshot.exists()
        }
    if (oldSnapshots.isNotEmpty()) {
      val oldShaderPaths = previousSnapshotShaderPaths()
      if (PlaybackSession.isInitialized) {
        PlaybackSession.updateShaderList { active ->
          active.filterNot { path -> path in oldShaderPaths }
        }
      }
      oldSnapshots.forEach { snapshot -> snapshot.deleteRecursively() }
    }
  }

  private companion object {
    const val TAG = "HdrToysManager"
    const val ASSET_DIR = "shaders/hdr-toys"
    const val COMPLETE_MARKER = ".complete"
    const val UPSTREAM_COMMIT = "220ba8e1c18089d650800882cc9284b7ae44ec30"
    val PREVIOUS_SNAPSHOT_ROOTS = setOf("hdr-toys", "hdr-toys-legacy")
    val PREVIOUS_SHADER_PATHS =
      HdrToysProfile.allShaderPaths +
        setOf(
          "utils/clip_black.glsl",
          "utils/clip_alpha.glsl",
          "tone-mapping/astra.glsl",
          "gamut-mapping/bottosson.glsl",
        )
  }
}
