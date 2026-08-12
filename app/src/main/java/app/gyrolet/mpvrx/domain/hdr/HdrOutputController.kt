/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.hdr

import android.util.Log
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.ui.player.HdrScreenMode
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.RenderBackendSelection
import app.gyrolet.mpvrx.ui.player.applyHdrScreenOutputOptions
import app.gyrolet.mpvrx.ui.player.applyHdrScreenOutputProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The complete, observable truth for requested and effective HDR output. */
data class HdrPipelineState(
  val requestedMode: HdrScreenMode,
  val effectiveMode: HdrScreenMode,
  val backend: RenderBackendSelection? = null,
  val boostSdrToHdr: Boolean = false,
  val hdrToysReady: Boolean = false,
  val error: String? = null,
) {
  val isEnabled: Boolean
    get() = effectiveMode != HdrScreenMode.OFF

  val isLinearAvailable: Boolean
    get() = backend?.supportsLinearHdr == true

  val pipelineReady: Boolean
    get() = when (effectiveMode) {
      HdrScreenMode.OFF -> true
      HdrScreenMode.LINEAR -> isLinearAvailable
      else -> hdrToysReady
    }

  val isCurrentPipelineDegraded: Boolean
    get() = requestedMode != effectiveMode || !pipelineReady

  fun isModeAvailable(mode: HdrScreenMode): Boolean =
    when (mode) {
      HdrScreenMode.OFF -> true
      HdrScreenMode.LINEAR -> isLinearAvailable
      else -> hdrToysReady
    }
}

/**
 * Sole owner of HDR preference migration, backend capability resolution and mpv application.
 * UI and player code consume [state]; they never recompute readiness from preference booleans.
 */
class HdrOutputController(
  private val preferences: DecoderPreferences,
  private val hdrToysManager: HdrToysManager,
) {
  private val _state = MutableStateFlow(initialState())
  val state: StateFlow<HdrPipelineState> = _state.asStateFlow()

  @Synchronized
  fun prepareBackend(backend: RenderBackendSelection): HdrPipelineState {
    val resolved = resolve(preferences.hdrScreenMode.get(), backend)
    _state.value = resolved
    return resolved
  }

  /** Applies init-time options and the renderer-specific pinned shader snapshot. */
  @Synchronized
  fun applyForInitialization(
    backend: RenderBackendSelection,
    precedingShaderPaths: List<String> = emptyList(),
  ): HdrPipelineState {
    var resolved = prepareBackend(backend)
    val profile = resolved.effectiveMode.hdrToysProfile
    val hdrShaderPaths =
      if (profile == null) {
        emptyList()
      } else {
        hdrToysManager.shaderPaths(profile, backend.hdrToysRenderer).orEmpty().also { paths ->
          if (paths.isEmpty()) {
            resolved =
              resolved.copy(
                effectiveMode = HdrScreenMode.OFF,
                hdrToysReady = false,
                error = "Bundled HDR Toys shaders are unavailable",
              )
            _state.value = resolved
          }
        }
      }
    applyHdrScreenOutputOptions(
      mode = resolved.effectiveMode,
      pipelineReady = resolved.pipelineReady,
      boostSdrToHdr = resolved.boostSdrToHdr,
      hdrShaderOptions = shaderOptionsFor(resolved),
    )
    (precedingShaderPaths + hdrShaderPaths).forEach { shaderPath ->
      // Retain shaders loaded by the user's profile/mpv.conf and preserve duplicate passes.
      PlaybackSession.setOptionString("glsl-shaders-append", shaderPath)
    }
    return _state.value
  }

  /** Selects and persists one mode. OFF is the only disabled representation. */
  @Synchronized
  fun select(mode: HdrScreenMode): HdrPipelineState {
    val acceptedMode = mode
    preferences.hdrScreenMode.set(acceptedMode)
    if (acceptedMode != HdrScreenMode.OFF) preferences.lastHdrMode.set(acceptedMode)
    _state.value = resolve(acceptedMode, _state.value.backend)
    applyRuntimeState()
    return _state.value
  }

  @Synchronized
  fun toggle(): HdrPipelineState {
    val next =
      if (_state.value.requestedMode == HdrScreenMode.OFF) {
        preferences.lastHdrMode.get().takeUnless { it == HdrScreenMode.OFF }
          ?: HdrScreenMode.defaultEnabledMode
      } else {
        HdrScreenMode.OFF
      }
    return select(next)
  }

  /** Re-applies the resolved state after file load or shader-stack reconstruction. */
  @Synchronized
  fun refreshRuntime(): HdrPipelineState {
    _state.value = resolve(preferences.hdrScreenMode.get(), _state.value.backend)
    applyRuntimeState()
    return _state.value
  }

  private fun applyRuntimeState() {
    val current = _state.value
    runCatching {
      applyHdrScreenOutputProperties(
        mode = current.effectiveMode,
        pipelineReady = current.pipelineReady,
        boostSdrToHdr = current.boostSdrToHdr,
        hdrShaderOptions = shaderOptionsFor(current),
      )
      applyShaderProfile(current)
    }.onFailure { error ->
      Log.e(TAG, "Failed to apply HDR pipeline ${current.effectiveMode}", error)
      _state.value =
        current.copy(
          effectiveMode = HdrScreenMode.OFF,
          hdrToysReady = false,
          error = error.message ?: "HDR pipeline application failed",
        )
      applyHdrScreenOutputProperties(HdrScreenMode.OFF, pipelineReady = true)
      hdrToysManager.clear()
    }
  }

  private fun applyShaderProfile(current: HdrPipelineState) {
    val profile = current.effectiveMode.hdrToysProfile
    val backend = current.backend
    if (profile == null || backend == null) {
      hdrToysManager.clear()
      return
    }
    if (!hdrToysManager.apply(profile, backend.hdrToysRenderer)) {
      error("Bundled HDR Toys shaders are unavailable")
    }
  }

  private fun shaderOptionsFor(current: HdrPipelineState): List<Pair<String, String>> =
    if (current.backend?.vo == "gpu-next") current.effectiveMode.hdrToysProfile?.shaderOptions.orEmpty()
    else emptyList()

  private fun resolve(
    requested: HdrScreenMode,
    backend: RenderBackendSelection?,
  ): HdrPipelineState {
    val candidate =
      if (requested == HdrScreenMode.LINEAR && backend?.supportsLinearHdr != true) {
        HdrScreenMode.defaultEnabledMode
      } else {
        requested
      }
    val assetsReady = backend != null && hdrToysManager.initialize()
    val effective = if (candidate.hdrToysProfile == null || assetsReady) candidate else HdrScreenMode.OFF
    val fallback =
      when {
        candidate.hdrToysProfile != null && !assetsReady -> "Bundled HDR Toys shaders are unavailable"
        requested == HdrScreenMode.LINEAR && candidate != requested ->
          "Linear HDR requires the active gpu-next/Vulkan renderer; using BT.2020"
        else -> null
      }
    return HdrPipelineState(
      requestedMode = requested,
      effectiveMode = effective,
      backend = backend,
      boostSdrToHdr = preferences.boostSdrToHdr.get(),
      hdrToysReady = assetsReady,
      error = fallback,
    )
  }

  private fun initialState(): HdrPipelineState {
    val migratedMode =
      if (preferences.legacyHdrScreenOutput.isSet()) {
        val legacyEnabled = preferences.legacyHdrScreenOutput.get()
        val stored = preferences.hdrScreenMode.get()
        val migrated =
          if (legacyEnabled) stored.takeUnless { it == HdrScreenMode.OFF } ?: HdrScreenMode.defaultEnabledMode
          else HdrScreenMode.OFF
        preferences.hdrScreenMode.set(migrated)
        preferences.legacyHdrScreenOutput.delete()
        migrated
      } else {
        preferences.hdrScreenMode.get()
      }
    return resolveWithoutBackend(migratedMode)
  }

  private fun resolveWithoutBackend(requested: HdrScreenMode): HdrPipelineState =
    HdrPipelineState(
      requestedMode = requested,
      effectiveMode = HdrScreenMode.OFF,
      boostSdrToHdr = preferences.boostSdrToHdr.get(),
      error = "Waiting for an active renderer",
    )

  private companion object {
    const val TAG = "HdrOutputController"
  }
}
