/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.content.Context
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

internal class ParticleFeedbackRenderer(
  private val context: Context,
  private val sourceAudio: AudioFeatures,
  palette: VisualizerPalette,
  reducedMotion: Boolean,
) : GLSurfaceView.Renderer {
  private val audioSmoother = AudioReactiveSmoother()

  @Volatile private var requestedPalette = palette
  @Volatile private var reducedMotionEnabled = reducedMotion

  private object Cfg {
    const val SIM_SIZE = 512
    const val NUM_PARTICLES = SIM_SIZE * SIM_SIZE
    const val DECAY = 0.920f
    const val DIFFUSE = 0.006f
    const val BRIGHT = 0.024f
    const val EXPOSURE = 0.75f
    const val CHROMATIC_ABERRATION = 0.008f
    const val GRAIN = 0.035f
    const val VIGNETTE = 0.0f
  }

  private val random = Random()

  private var bassSmoothed = 0.10f
  private var midSmoothed = 0.08f
  private var highSmoothed = 0.05f
  private var energySmoothed = 0.10f
  private var beatSmoothed = 0f
  private var bassAvg = 0.25f
  private var lastBeatNanos = 0L
  private var beatCount = 0
  private var hueCurrent = 0.55f
  private var hueTarget = 0.55f
  private var flareSmoothed = 0.15f

  private var pInit = 0
  private var pSim = 0
  private var pPts = 0
  private var pDecay = 0
  private var pComp = 0

  private var dummyVao = 0
  private var dummyVbo = 0

  private val simTex = IntArray(2)
  private val simFbo = IntArray(2)
  private var simSrc = 0

  private val trailTex = IntArray(2)
  private val trailFbo = IntArray(2)
  private var trailSrc = 0

  private var viewportWidth = 1
  private var viewportHeight = 1

  // Uniform locations
  private var uSimState = -1
  private var uSimTime = -1
  private var uSimDt = -1
  private var uSimBass = -1
  private var uSimMid = -1
  private var uSimHigh = -1
  private var uSimBeat = -1
  private var uSimEnergy = -1

  private var uPtsState = -1
  private var uPtsSimSize = -1
  private var uPtsAspect = -1
  private var uPtsHue = -1
  private var uPtsEnergy = -1
  private var uPtsBeat = -1
  private var uPtsHigh = -1
  private var uPtsBright = -1

  private var uDecayTrail = -1
  private var uDecayTexel = -1
  private var uDecayFactor = -1
  private var uDecayDiff = -1

  private var uCompTrail = -1
  private var uCompAspect = -1
  private var uCompFlare = -1
  private var uCompHue = -1
  private var uCompFrame = -1
  private var uCompGrain = -1
  private var uCompCA = -1
  private var uCompExposure = -1
  private var uCompVig = -1
  private var uCompIsDarkTheme = -1
  private var uCompPrimaryColor = -1
  private var uCompSecondaryColor = -1

  private var previousFrameNanos = 0L
  private var elapsedSeconds = 0f
  private var frameCounter = 0L

  fun updatePalette(palette: VisualizerPalette) {
    requestedPalette = palette
    updateDynamicPaletteHue()
  }

  fun setReducedMotion(reducedMotion: Boolean) {
    reducedMotionEnabled = reducedMotion
  }

  private fun updateDynamicPaletteHue() {
    val hsv = FloatArray(3)
    Color.colorToHSV(requestedPalette.primary, hsv)
    hueTarget = hsv[0] / 360f
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

    cacheUniforms()
    createDummyVao()
    createSimPingPong()
    updateDynamicPaletteHue()

    // Initialize particles state
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, simFbo[0])
    GLES30.glViewport(0, 0, Cfg.SIM_SIZE, Cfg.SIM_SIZE)
    GLES30.glUseProgram(pInit)
    GLES30.glBindVertexArray(dummyVao)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

    previousFrameNanos = System.nanoTime()
    elapsedSeconds = 0f
    frameCounter = 0L
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    viewportWidth = max(2, width)
    viewportHeight = max(2, height)
    allocTrailBuffers()
  }

  override fun onDrawFrame(gl: GL10?) {
    val nowNanos = System.nanoTime()
    val dtSeconds = ((nowNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(1f / 240f, 1f / 24f)
    previousFrameNanos = nowNanos
    elapsedSeconds += dtSeconds
    frameCounter++

    updateAudioAnalysis(dtSeconds, elapsedSeconds)
    val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()

    GLES30.glBindVertexArray(dummyVao)
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
    GLES30.glUniform1f(uSimBass, bassSmoothed)
    GLES30.glUniform1f(uSimMid, midSmoothed)
    GLES30.glUniform1f(uSimHigh, highSmoothed)
    GLES30.glUniform1f(uSimBeat, beatSmoothed)
    GLES30.glUniform1f(uSimEnergy, energySmoothed)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    simSrc = nextSim

    /* 2. Decay + Diffuse Previous Trail */
    val nextTrail = 1 - trailSrc
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trailFbo[nextTrail])
    GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    GLES30.glUseProgram(pDecay)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex[trailSrc])
    GLES30.glUniform1i(uDecayTrail, 0)
    GLES30.glUniform2f(uDecayTexel, 1f / viewportWidth, 1f / viewportHeight)
    GLES30.glUniform1f(uDecayFactor, Cfg.DECAY)
    GLES30.glUniform1f(uDecayDiff, Cfg.DIFFUSE)
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
    GLES30.glUniform1f(uPtsHue, hueCurrent)
    GLES30.glUniform1f(uPtsEnergy, energySmoothed)
    GLES30.glUniform1f(uPtsBeat, beatSmoothed)
    GLES30.glUniform1f(uPtsHigh, highSmoothed)
    GLES30.glUniform1f(uPtsBright, Cfg.BRIGHT)
    GLES30.glDrawArrays(GLES30.GL_POINTS, 0, Cfg.NUM_PARTICLES)
    GLES30.glDisable(GLES30.GL_BLEND)
    trailSrc = nextTrail

    /* 4. Generate Mipmaps for Bloom */
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex[trailSrc])
    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

    /* 5. Composite Pass to Screen with Dynamic Colors & Theme Adaptation */
    val primaryRgb = requestedPalette.primaryRgb()
    val secondaryRgb = requestedPalette.secondaryRgb()
    val bgRgb = requestedPalette.backgroundRgb()
    val bgLuminance = 0.299f * bgRgb[0] + 0.587f * bgRgb[1] + 0.114f * bgRgb[2]
    val isDarkTheme = if (bgLuminance < 0.5f) 1.0f else 0.0f

    GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    GLES30.glClearColor(0f, 0f, 0f, 0f)
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    GLES30.glUseProgram(pComp)
    GLES30.glUniform1i(uCompTrail, 0)
    GLES30.glUniform1f(uCompAspect, aspect)
    GLES30.glUniform1f(uCompFlare, flareSmoothed)
    GLES30.glUniform1f(uCompHue, hueCurrent)
    GLES30.glUniform1f(uCompFrame, (frameCounter % 1024).toFloat())
    GLES30.glUniform1f(uCompGrain, Cfg.GRAIN)
    GLES30.glUniform1f(uCompCA, Cfg.CHROMATIC_ABERRATION)
    GLES30.glUniform1f(uCompExposure, Cfg.EXPOSURE)
    GLES30.glUniform1f(uCompVig, Cfg.VIGNETTE)
    GLES30.glUniform1f(uCompIsDarkTheme, isDarkTheme)
    GLES30.glUniform3f(uCompPrimaryColor, primaryRgb[0], primaryRgb[1], primaryRgb[2])
    GLES30.glUniform3f(uCompSecondaryColor, secondaryRgb[0], secondaryRgb[1], secondaryRgb[2])
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glBindVertexArray(0)
  }

  private fun updateAudioAnalysis(dt: Float, nowSec: Float) {
    val audio = audioSmoother.update(
      AudioFeatureFrame(
        energy = sourceAudio.energy,
        bass = sourceAudio.bass,
        mid = sourceAudio.mid,
        treble = sourceAudio.treble,
        centroid = sourceAudio.centroid,
        beat = sourceAudio.beat,
      ),
      dt,
    )

    if (sourceAudio.active) {
      val bassTarget = (audio.bass * 1.6f).coerceAtMost(1f)
      val midTarget = (audio.mid * 1.8f).coerceAtMost(1f)
      val highTarget = (audio.treble * 2.2f).coerceAtMost(1f)
      val energyTarget = (audio.energy * 1.4f).coerceAtMost(1f)

      bassSmoothed = smoothVal(bassSmoothed, bassTarget, dt, 24f, 6f)
      midSmoothed = smoothVal(midSmoothed, midTarget, dt, 16f, 5f)
      highSmoothed = smoothVal(highSmoothed, highTarget, dt, 20f, 7f)
      energySmoothed = smoothVal(energySmoothed, energyTarget, dt, 11f, 4f)

      bassAvg += (bassTarget - bassAvg) * (1f - exp(-dt * 0.8f))
      val nowNanos = System.nanoTime()
      if (bassTarget > bassAvg * 1.25f + 0.04f && (nowNanos - lastBeatNanos) > 160_000_000L) {
        beatSmoothed = 1f
        lastBeatNanos = nowNanos
        beatCount++
      }
    } else {
      // Fluid continuous fallback motion when audio features are starting up
      bassSmoothed = 0.12f + 0.06f * kotlin.math.sin(nowSec * 0.8f)
      midSmoothed = 0.09f + 0.04f * kotlin.math.cos(nowSec * 1.1f)
      highSmoothed = 0.06f + 0.03f * kotlin.math.sin(nowSec * 1.4f)
      energySmoothed = 0.12f + 0.05f * kotlin.math.sin(nowSec * 0.6f)
    }

    beatSmoothed *= exp(-dt * 5.5f)
    var dh = hueTarget - hueCurrent
    if (dh > 0.5f) dh -= 1f
    if (dh < -0.5f) dh += 1f
    hueCurrent = (hueCurrent + dh * (1f - exp(-dt * 1.2f)) + 1f) % 1f
    flareSmoothed = 0.15f + 1.1f * bassSmoothed.pow(1.4f) + 0.7f * beatSmoothed
  }

  private fun smoothVal(cur: Float, target: Float, dt: Float, upRate: Float, dnRate: Float): Float {
    val rate = if (target > cur) upRate else dnRate
    return cur + (target - cur) * (1f - exp(-dt * rate))
  }

  private fun createDummyVao() {
    val vaos = IntArray(1)
    val vbos = IntArray(1)
    GLES30.glGenVertexArrays(1, vaos, 0)
    GLES30.glGenBuffers(1, vbos, 0)
    dummyVao = vaos[0]
    dummyVbo = vbos[0]

    GLES30.glBindVertexArray(dummyVao)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dummyVbo)
    val dummyData = FloatArray(Cfg.NUM_PARTICLES * 4)
    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, dummyData.size * Float.SIZE_BYTES, GlUtils.floatBuffer(dummyData), GLES30.GL_STATIC_DRAW)
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 1, GLES30.GL_FLOAT, false, 0, 0)
    GLES30.glBindVertexArray(0)
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
    val levels = max(1, log2(max(viewportWidth, viewportHeight).toDouble()).toInt() + 1)
    for (i in 0 until 2) {
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex[i])
      GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA16F, viewportWidth, viewportHeight)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
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
    uSimBass = GLES30.glGetUniformLocation(pSim, "uBass")
    uSimMid = GLES30.glGetUniformLocation(pSim, "uMid")
    uSimHigh = GLES30.glGetUniformLocation(pSim, "uHigh")
    uSimBeat = GLES30.glGetUniformLocation(pSim, "uBeat")
    uSimEnergy = GLES30.glGetUniformLocation(pSim, "uEnergy")

    uPtsState = GLES30.glGetUniformLocation(pPts, "uState")
    uPtsSimSize = GLES30.glGetUniformLocation(pPts, "uSimSize")
    uPtsAspect = GLES30.glGetUniformLocation(pPts, "uAspect")
    uPtsHue = GLES30.glGetUniformLocation(pPts, "uHue")
    uPtsEnergy = GLES30.glGetUniformLocation(pPts, "uEnergy")
    uPtsBeat = GLES30.glGetUniformLocation(pPts, "uBeat")
    uPtsHigh = GLES30.glGetUniformLocation(pPts, "uHigh")
    uPtsBright = GLES30.glGetUniformLocation(pPts, "uBright")

    uDecayTrail = GLES30.glGetUniformLocation(pDecay, "uTrail")
    uDecayTexel = GLES30.glGetUniformLocation(pDecay, "uTexel")
    uDecayFactor = GLES30.glGetUniformLocation(pDecay, "uDecay")
    uDecayDiff = GLES30.glGetUniformLocation(pDecay, "uDiff")

    uCompTrail = GLES30.glGetUniformLocation(pComp, "uTrail")
    uCompAspect = GLES30.glGetUniformLocation(pComp, "uAspect")
    uCompFlare = GLES30.glGetUniformLocation(pComp, "uFlare")
    uCompHue = GLES30.glGetUniformLocation(pComp, "uHue")
    uCompFrame = GLES30.glGetUniformLocation(pComp, "uFrame")
    uCompGrain = GLES30.glGetUniformLocation(pComp, "uGrain")
    uCompCA = GLES30.glGetUniformLocation(pComp, "uCA")
    uCompExposure = GLES30.glGetUniformLocation(pComp, "uExposure")
    uCompVig = GLES30.glGetUniformLocation(pComp, "uVig")
    uCompIsDarkTheme = GLES30.glGetUniformLocation(pComp, "uIsDarkTheme")
    uCompPrimaryColor = GLES30.glGetUniformLocation(pComp, "uPrimaryColor")
    uCompSecondaryColor = GLES30.glGetUniformLocation(pComp, "uSecondaryColor")
  }
}
