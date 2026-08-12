/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

/** The renderer configuration that mpv will actually be initialized with. */
data class RenderBackendSelection(
  val vo: String,
  val gpuApi: String,
  val gpuContext: String,
  val reason: String,
) {
  val configurationKey: String
    get() = "$vo|$gpuApi|$gpuContext"

  /** Native Linear HDR is deliberately restricted to the reliable Android lane. */
  val supportsLinearHdr: Boolean
    get() = vo == "gpu-next" && gpuApi == "vulkan" && gpuContext == "androidvk"

  val hdrToysRenderer: HdrToysRenderer
    get() = if (vo == "gpu-next") HdrToysRenderer.GPU_NEXT else HdrToysRenderer.GPU
}

enum class HdrToysRenderer {
  GPU_NEXT,
  GPU,
}

/**
 * Pure renderer policy shared by player initialization and HDR state resolution.
 *
 * Keeping this decision in one place is important: preference booleans describe intent, while
 * this value describes the backend mpv will really use after capability and compatibility
 * fallbacks have been applied.
 */
object RenderBackendResolver {
  fun resolve(
    gpuNextRequested: Boolean,
    vulkanRequested: Boolean,
    vulkanSupported: Boolean,
    anime4kActive: Boolean,
    forceOpenGlFallback: Boolean,
  ): RenderBackendSelection {
    val useVulkan = vulkanRequested && vulkanSupported && !forceOpenGlFallback

    if (anime4kActive && gpuNextRequested && !useVulkan) {
      return RenderBackendSelection(
        vo = "gpu",
        gpuApi = "opengl",
        gpuContext = "android",
        reason = "Anime4K requires vo=gpu when gpu-next/Vulkan is unavailable",
      )
    }

    if (gpuNextRequested && useVulkan) {
      return RenderBackendSelection(
        vo = "gpu-next",
        gpuApi = "vulkan",
        gpuContext = "androidvk",
        reason = "gpu-next with Vulkan",
      )
    }

    if (gpuNextRequested) {
      return RenderBackendSelection(
        vo = "gpu-next",
        gpuApi = "opengl",
        gpuContext = "android",
        reason = "gpu-next with OpenGL",
      )
    }

    if (useVulkan) {
      return RenderBackendSelection(
        vo = "gpu",
        gpuApi = "vulkan",
        gpuContext = "androidvk",
        reason = "vo=gpu with Vulkan",
      )
    }

    val fallbackReason =
      when {
        forceOpenGlFallback -> "Vulkan initialization failed; using gpu/OpenGL"
        vulkanRequested && !vulkanSupported -> "Vulkan capability check failed; using gpu/OpenGL"
        else -> "vo=gpu with OpenGL"
      }
    return RenderBackendSelection(
      vo = "gpu",
      gpuApi = "opengl",
      gpuContext = "android",
      reason = fallbackReason,
    )
  }
}
