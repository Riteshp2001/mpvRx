/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.util.Log
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Process-wide invariant for Ambient teardown.
 *
 * Ambient shader generation is asynchronous. A render-prep job can pass its initial enabled check,
 * then Ambient can be switched OFF before that job reaches its next cancellable suspension point.
 * Without a process-level invariant the stale job may briefly rewrite video-scale-x/y (or append its
 * just-built OUTPUT shader) after teardown, leaving a shrunk/expanded picture with no matching
 * remapper. When the persisted Ambient switch is OFF, identity scale and zero Ambient shaders are
 * therefore treated as hard renderer invariants rather than one-shot cleanup commands.
 */
object AmbientOutputInvariantGuard {
  private const val TAG = "AmbientOutputGuard"
  private const val SCALE_EPSILON = 0.000001
  private const val AMBIENT_SHADER_PREFIX = "ambient_"
  private const val FLOW_PALETTE_PREFIX = "mpvrx_flow_palette_"
  private const val OLD_FLOW_CADENCE_PREFIX = "mpvrx_flow_cadence_"

  private val installed = AtomicBoolean(false)

  fun install(
    scope: CoroutineScope,
    preferences: PlayerPreferences,
  ) {
    if (!installed.compareAndSet(false, true)) return

    val ambientEnabled =
      preferences.isAmbientEnabled
        .changes()
        .onStart { emit(preferences.isAmbientEnabled.get()) }
        .distinctUntilChanged()

    scope.launch(Dispatchers.Default) {
      combine(
        ambientEnabled,
        PlaybackSession.propDouble["video-scale-x"],
        PlaybackSession.propDouble["video-scale-y"],
        PlaybackSession.propString["glsl-shaders"],
      ) { enabled, scaleX, scaleY, shaders ->
        GuardState(enabled, scaleX, scaleY, shaders)
      }.collectLatest { state ->
        if (state.enabled || !PlaybackSession.isInitialized) return@collectLatest
        enforceDisabledState(state)
      }
    }
  }

  private fun enforceDisabledState(state: GuardState) {
    var corrected = false

    if (state.scaleX != null && abs(state.scaleX - 1.0) > SCALE_EPSILON) {
      PlaybackSession.setPropertyDouble("video-scale-x", 1.0)
      corrected = true
    }
    if (state.scaleY != null && abs(state.scaleY - 1.0) > SCALE_EPSILON) {
      PlaybackSession.setPropertyDouble("video-scale-y", 1.0)
      corrected = true
    }

    parseShaderPaths(state.shaders).filter(::isAmbientOwnedShader).forEach { path ->
      runCatching { PlaybackSession.command("change-list", "glsl-shaders", "remove", path) }
        .onFailure { error -> Log.w(TAG, "Failed to remove stale Ambient shader $path", error) }
      if (path.startsWith('/')) runCatching { File(path).delete() }
      corrected = true
    }

    // Ambient is the only feature that deliberately asks subtitles to blend with the expanded video.
    if (corrected) {
      PlaybackSession.setPropertyString("blend-subtitles", "no")
      Log.d(TAG, "Restored identity video scale after Ambient teardown race")
    }
  }

  private fun parseShaderPaths(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw
      .split(',', ';', ':')
      .map { it.trim().trim('"', '\'') }
      .filter { it.endsWith(".glsl", ignoreCase = true) }
  }

  private fun isAmbientOwnedShader(path: String): Boolean {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    return fileName.startsWith(AMBIENT_SHADER_PREFIX) ||
      fileName.startsWith(FLOW_PALETTE_PREFIX) ||
      fileName.startsWith(OLD_FLOW_CADENCE_PREFIX)
  }

  private data class GuardState(
    val enabled: Boolean,
    val scaleX: Double?,
    val scaleY: Double?,
    val shaders: String?,
  )
}
