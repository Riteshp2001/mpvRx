/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.content.Context
import android.opengl.GLES30
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal class ParticleFeedbackRenderer(
  private val context: Context,
  private val sourceAudio: AudioFeatures,
  palette: VisualizerPalette,
  reducedMotion: Boolean,
  private val interaction: VisualizerInteraction,
) : VsyncRenderer {
  private val audioSmoother = AudioReactiveSmoother()

  @Volatile private var requestedPalette = palette
  @Volatile private var reducedMotionEnabled = reducedMotion

  private object Cfg {
    // 20,736 particles: a modest density increase while keeping simulation and fill cost bounded.
    const val SIM_SIZE = 144
    const val NUM_PARTICLES = SIM_SIZE * SIM_SIZE
    const val TRAIL_SCALE = 0.75f
    const val MAX_TRAIL_DIMENSION = 1152f
    const val DECAY = 0.90f // Retention per 1/60 second, not per display frame.
    const val DIFFUSE = 0.004f
    // Keep aggregate light comparable as density rises, preserving individual particle detail.
    const val BRIGHT = 0.18f * 16_384f / NUM_PARTICLES
    const val EXPOSURE = 0.45f
  }

  private var appliedPalette: VisualizerPalette? = null
  private var primaryRgb = palette.primaryRgb()
  private var secondaryRgb = palette.secondaryRgb()

  private var subBassSmoothed = 0.08f
  private var bassSmoothed = 0.10f
  private var lowMidSmoothed = 0.08f
  private var midSmoothed = 0.08f
  private var highMidSmoothed = 0.06f
  private var highSmoothed = 0.05f
  private var energySmoothed = 0.10f
  private var fluxSmoothed = 0.05f
  private var beatSmoothed = 0f
  private var lastBeatNanos = 0L

  private var pInit = 0
  private var pSim = 0
  private var pPts = 0
  private var pDecay = 0
  private var pComp = 0

  private var pointVao = 0

  private val simTex = IntArray(2)
  private val simFbo = IntArray(2)
  private var simSrc = 0

  private val trailTex = IntArray(2)
  private val trailFbo = IntArray(2)
  private var trailSrc = 0

  private var viewportWidth = 1
  private var viewportHeight = 1
  private var trailWidth = 1
  private var trailHeight = 1

  // Uniform locations
  private var uSimState = -1
  private var uSimTime = -1
  private var uSimDt = -1
  private var uSimSubBass = -1
  private var uSimBass = -1
  private var uSimLowMid = -1
  private var uSimMid = -1
  private var uSimHighMid = -1
  private var uSimTreble = -1
  private var uSimPointer = -1
  private var uSimMotion = -1
  private var uSimBeat = -1
  private var uSimEnergy = -1
  private var uSimFlux = -1

  private var uPtsState = -1
  private var uPtsSimSize = -1
  private var uPtsAspect = -1
  private var uPtsViewportHeight = -1
  private var uPtsEnergy = -1
  private var uPtsBeat = -1
  private var uPtsSubBass = -1
  private var uPtsBass = -1
  private var uPtsFlux = -1
  private var uPtsBright = -1
  private var uPtsEmission = -1

  private var uDecayTrail = -1
  private var uDecayTexel = -1
  private var uDecayFactor = -1
  private var uDecayDiff = -1

  private var uCompTrail = -1
  private var uCompExposure = -1
  private var uCompPrimaryColor = -1
  private var uCompSecondaryColor = -1

  private var previousFrameNanos = 0L
  private var elapsedSeconds = 0f

  fun updatePalette(palette: VisualizerPalette) {
    requestedPalette = palette
  }

  fun setReducedMotion(reducedMotion: Boolean) {
    reducedMotionEnabled = reducedMotion
  }

  private fun updatePaletteIfNeeded() {
    val palette = requestedPalette
    if (appliedPalette == palette) return
    primaryRgb = palette.primaryRgb()
    secondaryRgb = palette.secondaryRgb()
    appliedPalette = palette
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glClearColor(0f, 0f, 0f, 0f)

    pInit = GlUtils.createProgram(
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_quad_vertex.glsl"),
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_init_fragment.glsl"),
    )
    pSim = GlUtils.createProgram(
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_quad_vertex.glsl"),
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_sim_fragment.glsl"),
    )
    pPts = GlUtils.createProgram(
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_point_vertex.glsl"),
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_point_fragment.glsl"),
    )
    pDecay = GlUtils.createProgram(
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_quad_vertex.glsl"),
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_decay_fragment.glsl"),
    )
    pComp = GlUtils.createProgram(
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_quad_vertex.glsl"),
      GlUtils.readAssetText(context, "shaders/visualizer/particle/particle_comp_fragment.glsl"),
    )

    // Texture names from a retired EGL context must never be deleted in the new context.
    trailTex.fill(0)
    trailFbo.fill(0)
    cacheUniforms()
    createVao()
    createSimPingPong()

    // Initialize particles state
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, simFbo[0])
    GLES30.glViewport(0, 0, Cfg.SIM_SIZE, Cfg.SIM_SIZE)
    GLES30.glUseProgram(pInit)
    GLES30.glBindVertexArray(pointVao)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

    previousFrameNanos = 0L
    simSrc = 0
    elapsedSeconds = 0f
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    viewportWidth = max(2, width)
    viewportHeight = max(2, height)
    // Bound feedback bandwidth on large/high-density displays while preserving the aspect ratio.
    val scale = min(Cfg.TRAIL_SCALE, Cfg.MAX_TRAIL_DIMENSION / max(viewportWidth, viewportHeight))
    trailWidth = max(4, (viewportWidth * scale).toInt())
    trailHeight = max(4, (viewportHeight * scale).toInt())
    allocTrailBuffers()
  }

  override fun onFrame(frameTimeNanos: Long) {
    val dtSeconds = if (previousFrameNanos == 0L || frameTimeNanos - previousFrameNanos > 250_000_000L) {
      1f / 60f
    } else {
      ((frameTimeNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
    }
    previousFrameNanos = frameTimeNanos
    elapsedSeconds += dtSeconds
    val frameScale = dtSeconds * 60f
    val decay = Cfg.DECAY.pow(frameScale)
    // Scale emission with the amount lost to decay, keeping steady brightness at any refresh rate.
    val emission = (1f - decay) / (1f - Cfg.DECAY)
    val diffusion = (1f - (1f - 4f * Cfg.DIFFUSE).pow(frameScale)) * 0.25f

    updatePaletteIfNeeded()
    updateAudioAnalysis(dtSeconds, elapsedSeconds)
    interaction.advance(dtSeconds)
    val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()

    GLES30.glBindVertexArray(pointVao)
    GLES30.glDisable(GLES30.GL_BLEND)

    /* 1. Simulate */
    val nextSim = 1 - simSrc
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, simFbo[nextSim])
    GLES30.glViewport(0, 0, Cfg.SIM_SIZE, Cfg.SIM_SIZE)
    GLES30.glUseProgram(pSim)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, simTex[simSrc])
    GLES30.glUniform1i(uSimState, 0)
    GLES30.glUniform1f(uSimTime, elapsedSeconds)
    GLES30.glUniform1f(uSimDt, dtSeconds)
    GLES30.glUniform3f(uSimPointer, interaction.x * aspect, interaction.y, interaction.strength)
    GLES30.glUniform1f(uSimMotion, if (reducedMotionEnabled) 0.2f else 1f)
    GLES30.glUniform1f(uSimSubBass, subBassSmoothed)
    GLES30.glUniform1f(uSimBass, bassSmoothed)
    GLES30.glUniform1f(uSimLowMid, lowMidSmoothed)
    GLES30.glUniform1f(uSimMid, midSmoothed)
    GLES30.glUniform1f(uSimHighMid, highMidSmoothed)
    GLES30.glUniform1f(uSimTreble, highSmoothed)
    GLES30.glUniform1f(uSimBeat, beatSmoothed)
    GLES30.glUniform1f(uSimEnergy, energySmoothed)
    GLES30.glUniform1f(uSimFlux, fluxSmoothed)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    simSrc = nextSim

    /* 2. Decay + Diffuse Previous Trail */
    val nextTrail = 1 - trailSrc
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trailFbo[nextTrail])
    GLES30.glViewport(0, 0, trailWidth, trailHeight)
    GLES30.glUseProgram(pDecay)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex[trailSrc])
    GLES30.glUniform1i(uDecayTrail, 0)
    GLES30.glUniform2f(uDecayTexel, 1f / trailWidth, 1f / trailHeight)
    GLES30.glUniform1f(uDecayFactor, decay)
    GLES30.glUniform1f(uDecayDiff, diffusion)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

    /* 3. Additive Points into Trail FBO */
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
    GLES30.glUseProgram(pPts)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, simTex[simSrc])
    GLES30.glUniform1i(uPtsState, 0)
    GLES30.glUniform1i(uPtsSimSize, Cfg.SIM_SIZE)
    GLES30.glUniform1f(uPtsAspect, aspect)
    GLES30.glUniform1f(uPtsViewportHeight, trailHeight.toFloat())
    GLES30.glUniform1f(uPtsEnergy, energySmoothed)
    GLES30.glUniform1f(uPtsBeat, beatSmoothed)
    GLES30.glUniform1f(uPtsSubBass, subBassSmoothed)
    GLES30.glUniform1f(uPtsBass, bassSmoothed)
    GLES30.glUniform1f(uPtsFlux, fluxSmoothed)
    GLES30.glUniform1f(uPtsBright, Cfg.BRIGHT * emission)
    GLES30.glUniform1f(uPtsEmission, emission)
    GLES30.glDrawArrays(GLES30.GL_POINTS, 0, Cfg.NUM_PARTICLES)
    GLES30.glDisable(GLES30.GL_BLEND)
    trailSrc = nextTrail

    // Refresh both alternating buffers. Updating every other frame only ever refreshed one of
    // them, making bloom alternate between fresh and stale/empty data. Only three levels exist.
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex[trailSrc])
    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

    /* Composite the palette-colored trail with bounded opacity. */
    GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    GLES30.glClearColor(0f, 0f, 0f, 0f)
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFuncSeparate(
      GLES30.GL_SRC_ALPHA,
      GLES30.GL_ONE_MINUS_SRC_ALPHA,
      GLES30.GL_ONE,
      GLES30.GL_ONE_MINUS_SRC_ALPHA,
    )

    GLES30.glUseProgram(pComp)
    GLES30.glUniform1i(uCompTrail, 0)
    GLES30.glUniform1f(uCompExposure, Cfg.EXPOSURE)
    GLES30.glUniform3f(uCompPrimaryColor, primaryRgb[0], primaryRgb[1], primaryRgb[2])
    GLES30.glUniform3f(uCompSecondaryColor, secondaryRgb[0], secondaryRgb[1], secondaryRgb[2])
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glBindVertexArray(0)
  }

  private fun updateAudioAnalysis(dt: Float, nowSec: Float) {
    val audio = audioSmoother.update(
      AudioFeatureFrame(
        energy = sourceAudio.scaledEnergy(),
        subBass = sourceAudio.scaledSubBass(),
        bass = sourceAudio.scaledBass(),
        lowMid = sourceAudio.scaledLowMid(),
        mid = sourceAudio.scaledMid(),
        highMid = sourceAudio.scaledHighMid(),
        treble = sourceAudio.scaledTreble(),
        centroid = sourceAudio.scaledCentroid(),
        beat = sourceAudio.scaledBeat(),
        spectralFlux = sourceAudio.scaledSpectralFlux(),
      ),
      dt,
    )

    if (sourceAudio.active) {
      val subBassTarget = (audio.subBass * 1.5f).coerceAtMost(1f)
      val bassTarget = (audio.bass * 1.6f).coerceAtMost(1f)
      val lowMidTarget = (audio.lowMid * 1.7f).coerceAtMost(1f)
      val midTarget = (audio.mid * 1.8f).coerceAtMost(1f)
      val highMidTarget = (audio.highMid * 2.0f).coerceAtMost(1f)
      val highTarget = (audio.treble * 2.2f).coerceAtMost(1f)
      val energyTarget = (audio.energy * 1.4f).coerceAtMost(1f)
      val fluxTarget = (audio.spectralFlux * 1.8f).coerceAtMost(1f)

      subBassSmoothed = smoothVal(subBassSmoothed, subBassTarget, dt, 26f, 7f)
      bassSmoothed = smoothVal(bassSmoothed, bassTarget, dt, 24f, 6f)
      lowMidSmoothed = smoothVal(lowMidSmoothed, lowMidTarget, dt, 20f, 6f)
      midSmoothed = smoothVal(midSmoothed, midTarget, dt, 16f, 5f)
      highMidSmoothed = smoothVal(highMidSmoothed, highMidTarget, dt, 18f, 6f)
      highSmoothed = smoothVal(highSmoothed, highTarget, dt, 20f, 7f)
      energySmoothed = smoothVal(energySmoothed, energyTarget, dt, 11f, 4f)
      fluxSmoothed = smoothVal(fluxSmoothed, fluxTarget, dt, 30f, 8f)

      // The analyzer is the sole beat authority; the local debounce only edge-detects the ~50ms
      // FFT window during which the shared beat flag stays high.
      val nowNanos = System.nanoTime()
      if (sourceAudio.scaledBeat() > 0.5f && (nowNanos - lastBeatNanos) > 160_000_000L) {
        beatSmoothed = 1f
        lastBeatNanos = nowNanos
      }
    } else {
      // Fluid continuous fallback motion when audio features are starting up
      subBassSmoothed = 0.10f + 0.05f * kotlin.math.sin(nowSec * 0.7f)
      bassSmoothed = 0.12f + 0.06f * kotlin.math.sin(nowSec * 0.8f)
      lowMidSmoothed = 0.10f + 0.05f * kotlin.math.cos(nowSec * 0.95f)
      midSmoothed = 0.09f + 0.04f * kotlin.math.cos(nowSec * 1.1f)
      highMidSmoothed = 0.07f + 0.035f * kotlin.math.sin(nowSec * 1.25f)
      highSmoothed = 0.06f + 0.03f * kotlin.math.sin(nowSec * 1.4f)
      energySmoothed = 0.12f + 0.05f * kotlin.math.sin(nowSec * 0.6f)
      fluxSmoothed = 0.04f + 0.02f * kotlin.math.cos(nowSec * 1.6f)
    }

    beatSmoothed *= exp(-dt * 5.5f)
  }

  private fun smoothVal(cur: Float, target: Float, dt: Float, upRate: Float, dnRate: Float): Float {
    val rate = if (target > cur) upRate else dnRate
    return cur + (target - cur) * (1f - exp(-dt * rate))
  }

  private fun createVao() {
    val vaos = IntArray(1)
    GLES30.glGenVertexArrays(1, vaos, 0)
    pointVao = vaos[0]
    // Both shaders use gl_VertexID; no per-particle dummy vertex buffer is needed.
  }

  private fun createSimPingPong() {
    GLES30.glGenTextures(2, simTex, 0)
    GLES30.glGenFramebuffers(2, simFbo, 0)
    for (i in 0 until 2) {
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, simTex[i])
      GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, Cfg.SIM_SIZE, Cfg.SIM_SIZE)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, simFbo[i])
      GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, simTex[i], 0)
      GlUtils.checkFramebuffer("SimFbo[$i]")
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
  }

  private fun allocTrailBuffers() {
    for (i in 0 until 2) {
      if (trailTex[i] != 0) {
        GLES30.glDeleteTextures(1, trailTex, i)
        GLES30.glDeleteFramebuffers(1, trailFbo, i)
      }
    }
    GLES30.glGenTextures(2, trailTex, 0)
    GLES30.glGenFramebuffers(2, trailFbo, 0)
    // The composite samples only levels 0 and 2, so do not allocate a full mip chain.
    val levels = 3
    trailSrc = 0
    for (i in 0 until 2) {
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex[i])
      GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA16F, trailWidth, trailHeight)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, levels - 1)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trailFbo[i])
      GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, trailTex[i], 0)
      GlUtils.checkFramebuffer("TrailFbo[$i]")

      GLES30.glClearColor(0f, 0f, 0f, 0f)
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex[i])
      GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
  }

  private fun cacheUniforms() {
    uSimState = GLES30.glGetUniformLocation(pSim, "uState")
    uSimTime = GLES30.glGetUniformLocation(pSim, "uTime")
    uSimDt = GLES30.glGetUniformLocation(pSim, "uDt")
    uSimSubBass = GLES30.glGetUniformLocation(pSim, "uSubBass")
    uSimBass = GLES30.glGetUniformLocation(pSim, "uBass")
    uSimLowMid = GLES30.glGetUniformLocation(pSim, "uLowMid")
    uSimMid = GLES30.glGetUniformLocation(pSim, "uMid")
    uSimHighMid = GLES30.glGetUniformLocation(pSim, "uHighMid")
    uSimTreble = GLES30.glGetUniformLocation(pSim, "uTreble")
    uSimPointer = GLES30.glGetUniformLocation(pSim, "uPointer")
    uSimMotion = GLES30.glGetUniformLocation(pSim, "uMotion")
    uSimBeat = GLES30.glGetUniformLocation(pSim, "uBeat")
    uSimEnergy = GLES30.glGetUniformLocation(pSim, "uEnergy")
    uSimFlux = GLES30.glGetUniformLocation(pSim, "uFlux")

    uPtsState = GLES30.glGetUniformLocation(pPts, "uState")
    uPtsSimSize = GLES30.glGetUniformLocation(pPts, "uSimSize")
    uPtsAspect = GLES30.glGetUniformLocation(pPts, "uAspect")
    uPtsViewportHeight = GLES30.glGetUniformLocation(pPts, "uViewportHeight")
    uPtsEnergy = GLES30.glGetUniformLocation(pPts, "uEnergy")
    uPtsBeat = GLES30.glGetUniformLocation(pPts, "uBeat")
    uPtsSubBass = GLES30.glGetUniformLocation(pPts, "uSubBass")
    uPtsBass = GLES30.glGetUniformLocation(pPts, "uBass")
    uPtsFlux = GLES30.glGetUniformLocation(pPts, "uFlux")
    uPtsBright = GLES30.glGetUniformLocation(pPts, "uBright")
    uPtsEmission = GLES30.glGetUniformLocation(pPts, "uEmission")

    uDecayTrail = GLES30.glGetUniformLocation(pDecay, "uTrail")
    uDecayTexel = GLES30.glGetUniformLocation(pDecay, "uTexel")
    uDecayFactor = GLES30.glGetUniformLocation(pDecay, "uDecay")
    uDecayDiff = GLES30.glGetUniformLocation(pDecay, "uDiff")

    uCompTrail = GLES30.glGetUniformLocation(pComp, "uTrail")
    uCompExposure = GLES30.glGetUniformLocation(pComp, "uExposure")
    uCompPrimaryColor = GLES30.glGetUniformLocation(pComp, "uPrimaryColor")
    uCompSecondaryColor = GLES30.glGetUniformLocation(pComp, "uSecondaryColor")
  }
}
