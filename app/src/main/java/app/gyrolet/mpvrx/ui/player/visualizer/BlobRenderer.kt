package app.gyrolet.mpvrx.ui.player.visualizer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

internal class BlobRenderer(
    private val context: Context,
    private val sourceAudio: AudioFeatures,
    initialPalette: VisualizerPalette,
    reducedMotion: Boolean,
) : GLSurfaceView.Renderer {

    private val audio = AudioFeatures()
    private val audioSmoother = AudioReactiveSmoother()

    private var blobProgram = 0
    private var brightProgram = 0
    private var blurProgram = 0
    private var compositeProgram = 0

    @Suppress("LocalVariableName")
    private var uBlobMvp = -1
    @Suppress("LocalVariableName")
    private var uBlobTime = -1
    @Suppress("LocalVariableName")
    private var uBlobAudio = -1
    @Suppress("LocalVariableName")
    private var uBlobBass = -1
    @Suppress("LocalVariableName")
    private var uBlobMid = -1
    @Suppress("LocalVariableName")
    private var uBlobTreble = -1
    @Suppress("LocalVariableName")
    private var uBlobBeat = -1
    @Suppress("LocalVariableName")
    private var uBlobColor = -1
    @Suppress("LocalVariableName")
    private var uBlobIntensity = -1
    @Suppress("LocalVariableName")
    private var uBrightScene = -1
    @Suppress("LocalVariableName")
    private var uBrightThreshold = -1
    @Suppress("LocalVariableName")
    private var uBlurImage = -1
    @Suppress("LocalVariableName")
    private var uBlurDirection = -1
    @Suppress("LocalVariableName")
    private var uCompositeScene = -1
    @Suppress("LocalVariableName")
    private var uCompositeBloom = -1
    @Suppress("LocalVariableName")
    private var uCompositeBloomStrength = -1
    @Suppress("LocalVariableName")
    private var uCompositeExposure = -1
    @Suppress("LocalVariableName")
    private var uCompositeBackground = -1

    private var meshVao = 0
    private var meshVbo = 0
    private var meshEbo = 0
    private var lineIndexCount = 0

    private var quadVao = 0
    private var quadVbo = 0

    private var sceneTarget = RenderTarget.EMPTY
    private var bloomA = RenderTarget.EMPTY
    private var bloomB = RenderTarget.EMPTY

    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var renderScale = 0.82f
    private var targetsDirty = true

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)

    @Volatile private var palette = initialPalette
    @Volatile private var reducedMotionEnabled = reducedMotion
    private var appliedPalette: VisualizerPalette? = null
    private var backgroundRgb = initialPalette.backgroundRgb()
    private var primaryRgb = initialPalette.primaryRgb()
    private var secondaryRgb = initialPalette.secondaryRgb()
    private var tertiaryRgb = initialPalette.tertiaryRgb()
    private var smoothR = primaryRgb[0]
    private var smoothG = primaryRgb[1]
    private var smoothB = primaryRgb[2]

    @Volatile private var targetYaw = 0f
    @Volatile private var targetPitch = 0f
    private var yaw = 0f
    private var pitch = 0f

    @Volatile private var pinchScale = 1f
    private var zoomDistance = 8.6f

    fun setPinchScale(scale: Float) {
        pinchScale = scale.coerceIn(0.60f, 1.15f)
    }

    fun getPinchScale(): Float = pinchScale

    fun updatePalette(value: VisualizerPalette) {
        palette = value
    }

    fun setReducedMotion(value: Boolean) {
        reducedMotionEnabled = value
    }

    private var startNanos = 0L
    private var previousFrameNanos = 0L
    private var frameTimeEmaMs = 16.6f
    private var qualityFrameCounter = 0

    fun addTouchRotation(normalizedDx: Float, normalizedDy: Float) {
        targetYaw += normalizedDx * 150f
        targetPitch = (targetPitch + normalizedDy * 100f).coerceIn(-38f, 38f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        blobProgram = GlUtils.createProgram(
            GlUtils.readAssetText(context, "shaders/visualizer/blob_vertex.glsl"),
            GlUtils.readAssetText(context, "shaders/visualizer/blob_fragment.glsl")
        )
        brightProgram = GlUtils.createProgram(
            GlUtils.readAssetText(context, "shaders/visualizer/quad_vertex.glsl"),
            GlUtils.readAssetText(context, "shaders/visualizer/bright_fragment.glsl")
        )
        blurProgram = GlUtils.createProgram(
            GlUtils.readAssetText(context, "shaders/visualizer/quad_vertex.glsl"),
            GlUtils.readAssetText(context, "shaders/visualizer/blur_fragment.glsl")
        )
        compositeProgram = GlUtils.createProgram(
            GlUtils.readAssetText(context, "shaders/visualizer/quad_vertex.glsl"),
            GlUtils.readAssetText(context, "shaders/visualizer/composite_fragment.glsl")
        )

        cacheUniformLocations()
        createMesh()
        createQuad()
        startNanos = System.nanoTime()
        previousFrameNanos = startNanos
        targetsDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
        renderScale = when {
            width.toLong() * height.toLong() >= 3_000_000L -> 0.68f
            width.toLong() * height.toLong() >= 2_000_000L -> 0.76f
            else -> 0.86f
        }
        targetsDirty = true

        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        Matrix.perspectiveM(projection, 0, 45f, aspect, 0.1f, 30f)
        Matrix.setLookAtM(view, 0, 0f, -0.18f, zoomDistance, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val time = (now - startNanos) / 1_000_000_000f
        val frameMs = (now - previousFrameNanos) / 1_000_000f
        previousFrameNanos = now
        frameTimeEmaMs += (frameMs.coerceAtMost(50f) - frameTimeEmaMs) * 0.025f
        updateSmoothedAudio(frameMs / 1_000f)
        updatePaletteIfNeeded()

        if (targetsDirty) recreateTargets()
        updateAdaptiveQuality()
        val visualTime = if (reducedMotionEnabled) 0f else time
        updateMatrices(visualTime)
        updateColor(visualTime)

        renderBlob(visualTime)
        extractBrightPass()
        blurBloom(horizontal = true)
        blurBloom(horizontal = false)
        compositeToScreen()
    }

    private fun updateSmoothedAudio(deltaSeconds: Float) {
        val smoothed = audioSmoother.update(
            AudioFeatureFrame(
                energy = sourceAudio.energy,
                bass = sourceAudio.bass,
                mid = sourceAudio.mid,
                treble = sourceAudio.treble,
                centroid = sourceAudio.centroid,
                beat = sourceAudio.beat,
            ),
            deltaSeconds,
        )
        audio.energy = smoothed.energy
        audio.bass = smoothed.bass
        audio.mid = smoothed.mid
        audio.treble = smoothed.treble
        audio.centroid = smoothed.centroid
        audio.beat = smoothed.beat
        audio.active = sourceAudio.active
    }

    private fun createMesh() {
        val mesh = Icosphere.create(subdivisions = 5)
        val vertices: FloatBuffer = GlUtils.floatBuffer(mesh.positions)
        val indices: IntBuffer = GlUtils.intBuffer(mesh.lineIndices)
        lineIndexCount = mesh.lineIndices.size

        val vaos = IntArray(1)
        val buffers = IntArray(2)
        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glGenBuffers(2, buffers, 0)
        meshVao = vaos[0]
        meshVbo = buffers[0]
        meshEbo = buffers[1]

        GLES30.glBindVertexArray(meshVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            mesh.positions.size * Float.SIZE_BYTES,
            vertices,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * Float.SIZE_BYTES, 0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshEbo)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            mesh.lineIndices.size * Int.SIZE_BYTES,
            indices,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindVertexArray(0)
    }

    private fun createQuad() {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
        val buffer = GlUtils.floatBuffer(vertices)
        val vaos = IntArray(1)
        val vbos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glGenBuffers(1, vbos, 0)
        quadVao = vaos[0]
        quadVbo = vbos[0]

        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            buffer,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * Float.SIZE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(
            1,
            2,
            GLES30.GL_FLOAT,
            false,
            4 * Float.SIZE_BYTES,
            2 * Float.SIZE_BYTES
        )
        GLES30.glBindVertexArray(0)
    }

    @Suppress("LocalVariableName")
    private fun updateMatrices(time: Float) {
        yaw += (targetYaw - yaw) * 0.065f
        pitch += (targetPitch - pitch) * 0.065f

        Matrix.setIdentityM(model, 0)
        val idleRotation = time * (4.5f + audio.mid * 5f)
        Matrix.rotateM(model, 0, if (reducedMotionEnabled) 0f else yaw + idleRotation, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, if (reducedMotionEnabled) 0f else pitch + time * 1.7f, 1f, 0f, 0f)
        val reactiveScale =
            if (reducedMotionEnabled) {
                0.94f + audio.energy * 0.025f
            } else {
                0.84f + audio.energy * 0.10f + audio.bass * 0.085f + audio.beat * 0.055f
            }
        val scale = pinchScale * reactiveScale
        Matrix.scaleM(model, 0, scale, scale, scale)
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
    }

    private fun updateColor(time: Float) {
        val drift = if (reducedMotionEnabled) 0f else (kotlin.math.sin(time * 0.24f) + 1f) * 0.08f
        val spectralMix = (audio.centroid * 0.72f + audio.treble * 0.18f + drift).coerceIn(0f, 1f)
        val beatMix = (audio.beat * 0.68f).coerceIn(0f, 1f)
        val targetR = lerp(lerp(primaryRgb[0], secondaryRgb[0], spectralMix), tertiaryRgb[0], beatMix)
        val targetG = lerp(lerp(primaryRgb[1], secondaryRgb[1], spectralMix), tertiaryRgb[1], beatMix)
        val targetB = lerp(lerp(primaryRgb[2], secondaryRgb[2], spectralMix), tertiaryRgb[2], beatMix)

        val colorSpeed = 0.055f + audio.energy * 0.08f + audio.beat * 0.12f
        smoothR += (targetR - smoothR) * colorSpeed
        smoothG += (targetG - smoothG) * colorSpeed
        smoothB += (targetB - smoothB) * colorSpeed
    }

    @Suppress("LocalVariableName")
    private fun renderBlob(time: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneTarget.fbo)
        GLES30.glViewport(0, 0, sceneTarget.width, sceneTarget.height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)

        GLES30.glUseProgram(blobProgram)
        GLES30.glUniformMatrix4fv(uBlobMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uBlobTime, time)
        GLES30.glUniform1f(uBlobAudio, max(audio.energy, 0.035f))
        GLES30.glUniform1f(uBlobBass, audio.bass)
        GLES30.glUniform1f(uBlobMid, audio.mid)
        GLES30.glUniform1f(uBlobTreble, audio.treble)
        GLES30.glUniform1f(uBlobBeat, audio.beat)
        GLES30.glUniform3f(uBlobColor, smoothR, smoothG, smoothB)
        GLES30.glUniform1f(
            uBlobIntensity,
            0.38f + audio.energy * 0.28f + audio.beat * 0.22f
        )

        GLES30.glLineWidth(1.2f)
        GLES30.glBindVertexArray(meshVao)
        GLES30.glDrawElements(GLES30.GL_LINES, lineIndexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    @Suppress("LocalVariableName")
    private fun extractBrightPass() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomA.fbo)
        GLES30.glViewport(0, 0, bloomA.width, bloomA.height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(brightProgram)
        bindTexture(0, sceneTarget.texture, uBrightScene)
        GLES30.glUniform1f(
            uBrightThreshold,
            (0.52f - audio.energy * 0.04f).coerceAtLeast(0.42f)
        )
        drawQuad()
    }

    @Suppress("LocalVariableName")
    private fun blurBloom(horizontal: Boolean) {
        val source = if (horizontal) bloomA else bloomB
        val target = if (horizontal) bloomB else bloomA
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.fbo)
        GLES30.glViewport(0, 0, target.width, target.height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(blurProgram)
        bindTexture(0, source.texture, uBlurImage)
        val dx = if (horizontal) 1f / source.width.toFloat() else 0f
        val dy = if (horizontal) 0f else 1f / source.height.toFloat()
        GLES30.glUniform2f(uBlurDirection, dx, dy)
        drawQuad()
    }

    @Suppress("LocalVariableName")
    private fun compositeToScreen() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(compositeProgram)
        bindTexture(0, sceneTarget.texture, uCompositeScene)
        bindTexture(1, bloomA.texture, uCompositeBloom)
        GLES30.glUniform1f(
            uCompositeBloomStrength,
            0.34f + audio.energy * 0.24f + audio.beat * 0.12f
        )
        GLES30.glUniform1f(uCompositeExposure, 0.82f)
        GLES30.glUniform3f(
            uCompositeBackground,
            backgroundRgb[0],
            backgroundRgb[1],
            backgroundRgb[2],
        )
        drawQuad()
    }

    private fun drawQuad() {
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    @Suppress("LocalVariableName")
    private fun bindTexture(unit: Int, texture: Int, samplerLocation: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(samplerLocation, unit)
    }

    private fun recreateTargets() {
        deleteTarget(sceneTarget)
        deleteTarget(bloomA)
        deleteTarget(bloomB)

        val sceneWidth = max(1, (surfaceWidth * renderScale).toInt())
        val sceneHeight = max(1, (surfaceHeight * renderScale).toInt())
        val bloomWidth = max(1, sceneWidth / 2)
        val bloomHeight = max(1, sceneHeight / 2)

        sceneTarget = createTarget(sceneWidth, sceneHeight, "scene")
        bloomA = createTarget(bloomWidth, bloomHeight, "bloom-a")
        bloomB = createTarget(bloomWidth, bloomHeight, "bloom-b")
        targetsDirty = false
    }

    private fun createTarget(width: Int, height: Int, label: String): RenderTarget {
        val textures = IntArray(1)
        val framebuffers = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )

        GLES30.glGenFramebuffers(1, framebuffers, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textures[0],
            0
        )
        GlUtils.checkFramebuffer(label)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return RenderTarget(framebuffers[0], textures[0], width, height)
    }

    private fun deleteTarget(target: RenderTarget) {
        if (target.fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(target.fbo), 0)
        if (target.texture != 0) GLES30.glDeleteTextures(1, intArrayOf(target.texture), 0)
    }

    private fun updateAdaptiveQuality() {
        qualityFrameCounter++
        if (qualityFrameCounter < 180) return
        qualityFrameCounter = 0

        val previous = renderScale
        renderScale = when {
            frameTimeEmaMs > 20.5f -> max(0.55f, renderScale - 0.07f)
            frameTimeEmaMs < 14.2f -> min(0.90f, renderScale + 0.04f)
            else -> renderScale
        }
        if (kotlin.math.abs(previous - renderScale) > 0.001f) targetsDirty = true
    }

    @Suppress("LocalVariableName")
    private fun cacheUniformLocations() {
        uBlobMvp = GLES30.glGetUniformLocation(blobProgram, "uMvp")
        uBlobTime = GLES30.glGetUniformLocation(blobProgram, "uTime")
        uBlobAudio = GLES30.glGetUniformLocation(blobProgram, "uAudio")
        uBlobBass = GLES30.glGetUniformLocation(blobProgram, "uBass")
        uBlobMid = GLES30.glGetUniformLocation(blobProgram, "uMid")
        uBlobTreble = GLES30.glGetUniformLocation(blobProgram, "uTreble")
        uBlobBeat = GLES30.glGetUniformLocation(blobProgram, "uBeat")
        uBlobColor = GLES30.glGetUniformLocation(blobProgram, "uColor")
        uBlobIntensity = GLES30.glGetUniformLocation(blobProgram, "uIntensity")
        uBrightScene = GLES30.glGetUniformLocation(brightProgram, "uScene")
        uBrightThreshold = GLES30.glGetUniformLocation(brightProgram, "uThreshold")
        uBlurImage = GLES30.glGetUniformLocation(blurProgram, "uImage")
        uBlurDirection = GLES30.glGetUniformLocation(blurProgram, "uDirection")
        uCompositeScene = GLES30.glGetUniformLocation(compositeProgram, "uScene")
        uCompositeBloom = GLES30.glGetUniformLocation(compositeProgram, "uBloom")
        uCompositeBloomStrength = GLES30.glGetUniformLocation(compositeProgram, "uBloomStrength")
        uCompositeExposure = GLES30.glGetUniformLocation(compositeProgram, "uExposure")
        uCompositeBackground = GLES30.glGetUniformLocation(compositeProgram, "uBackground")
    }

    private fun updatePaletteIfNeeded() {
        val next = palette
        if (next == appliedPalette) return
        backgroundRgb = next.backgroundRgb()
        primaryRgb = next.primaryRgb()
        secondaryRgb = next.secondaryRgb()
        tertiaryRgb = next.tertiaryRgb()
        if (appliedPalette == null) {
            smoothR = primaryRgb[0]
            smoothG = primaryRgb[1]
            smoothB = primaryRgb[2]
        }
        appliedPalette = next
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount

    private data class RenderTarget(
        val fbo: Int,
        val texture: Int,
        val width: Int,
        val height: Int
    ) {
        companion object {
            val EMPTY = RenderTarget(0, 0, 1, 1)
        }
    }
}
