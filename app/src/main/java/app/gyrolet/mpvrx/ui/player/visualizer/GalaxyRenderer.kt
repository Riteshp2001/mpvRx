package app.gyrolet.mpvrx.ui.player.visualizer

import android.content.Context
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal class GalaxyRenderer(
    private val context: Context,
    private val sourceAudio: AudioFeatures,
    palette: VisualizerPalette,
    reducedMotion: Boolean,
) : GLSurfaceView.Renderer {
    private val audioSmoother = AudioReactiveSmoother()

    @Volatile private var requestedPalette = palette
    @Volatile private var reducedMotionEnabled = reducedMotion
    @Volatile private var targetYaw = 0f
    @Volatile private var targetPitch = 0f

    private var appliedPalette: VisualizerPalette? = null
    private var backgroundR = 0f
    private var backgroundG = 0f
    private var backgroundB = 0f
    private var primaryR = 1f
    private var primaryG = 1f
    private var primaryB = 1f
    private var secondaryR = 1f
    private var secondaryG = 1f
    private var secondaryB = 1f
    private var tertiaryR = 1f
    private var tertiaryG = 1f
    private var tertiaryB = 1f

    private var program = 0
    private var starVao = 0
    private var starVbo = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1

    @Suppress("LocalVariableName") private var uMvp = -1
    @Suppress("LocalVariableName") private var uTime = -1
    @Suppress("LocalVariableName") private var uEnergy = -1
    @Suppress("LocalVariableName") private var uBass = -1
    @Suppress("LocalVariableName") private var uMid = -1
    @Suppress("LocalVariableName") private var uTreble = -1
    @Suppress("LocalVariableName") private var uBeat = -1
    @Suppress("LocalVariableName") private var uViewportHeight = -1
    @Suppress("LocalVariableName") private var uReducedMotion = -1
    @Suppress("LocalVariableName") private var uPrimaryColor = -1
    @Suppress("LocalVariableName") private var uSecondaryColor = -1
    @Suppress("LocalVariableName") private var uTertiaryColor = -1

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)

    private var yaw = 0f
    private var pitch = 0f
    private var previousFrameNanos = 0L
    private var elapsedSeconds = 0f
    private var frameTimeEmaMs = 16.6f
    private var qualityFrameCounter = 0
    private var drawCount = INITIAL_DRAW_COUNT

    fun updatePalette(palette: VisualizerPalette) {
        requestedPalette = palette
    }

    fun setReducedMotion(reducedMotion: Boolean) {
        reducedMotionEnabled = reducedMotion
    }

    fun addTouchRotation(normalizedDx: Float, normalizedDy: Float) {
        targetYaw += normalizedDx * 125f
        targetPitch = (targetPitch + normalizedDy * 90f).coerceIn(-34f, 34f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        program = GlUtils.createProgram(
            GlUtils.readAssetText(context, "shaders/visualizer/galaxy_vertex.glsl"),
            GlUtils.readAssetText(context, "shaders/visualizer/galaxy_fragment.glsl"),
        )
        cacheUniformLocations()
        createStarBuffer()
        applyPalette(force = true)

        previousFrameNanos = System.nanoTime()
        elapsedSeconds = 0f
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)

        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        Matrix.perspectiveM(projection, 0, 44f, aspect, 0.1f, 30f)
        Matrix.setLookAtM(view, 0, 0f, 0.15f, 8.2f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val frameMs = ((now - previousFrameNanos) / NANOS_PER_MILLISECOND).coerceAtMost(50f)
        previousFrameNanos = now
        elapsedSeconds += frameMs / MILLISECONDS_PER_SECOND
        val time = elapsedSeconds
        frameTimeEmaMs += (frameMs - frameTimeEmaMs) * FRAME_TIME_EMA_WEIGHT

        val audio = audioSmoother.update(
            AudioFeatureFrame(
                energy = sourceAudio.energy,
                bass = sourceAudio.bass,
                mid = sourceAudio.mid,
                treble = sourceAudio.treble,
                centroid = sourceAudio.centroid,
                beat = sourceAudio.beat,
            ),
            frameMs / MILLISECONDS_PER_SECOND,
        )

        applyPalette(force = false)
        updateAdaptiveDrawCount()
        updateMatrices(time, audio)

        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uTime, time)
        GLES30.glUniform1f(uEnergy, audio.energy)
        GLES30.glUniform1f(uBass, audio.bass)
        GLES30.glUniform1f(uMid, audio.mid)
        GLES30.glUniform1f(uTreble, audio.treble)
        GLES30.glUniform1f(uBeat, audio.beat)
        GLES30.glUniform1f(uViewportHeight, surfaceHeight.toFloat())
        GLES30.glUniform1f(uReducedMotion, if (reducedMotionEnabled) 1f else 0f)
        GLES30.glUniform3f(uPrimaryColor, primaryR, primaryG, primaryB)
        GLES30.glUniform3f(uSecondaryColor, secondaryR, secondaryG, secondaryB)
        GLES30.glUniform3f(uTertiaryColor, tertiaryR, tertiaryG, tertiaryB)

        GLES30.glBindVertexArray(starVao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, drawCount)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun updateMatrices(time: Float, audio: AudioFeatureFrame) {
        yaw += (targetYaw - yaw) * TOUCH_EASING
        pitch += (targetPitch - pitch) * TOUCH_EASING

        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, GALAXY_TILT_DEGREES, 1f, 0f, 0f)
        val automaticRotation = if (reducedMotionEnabled) 0f else time * (2.4f + audio.mid * 2.8f)
        Matrix.rotateM(model, 0, automaticRotation, 0f, 0f, 1f)

        val fullScale = BASE_SCALE + audio.energy * 0.012f + audio.bass * 0.030f + audio.beat * 0.024f
        val reducedScale = BASE_SCALE + audio.energy * 0.005f
        val scale = if (reducedMotionEnabled) reducedScale else fullScale
        Matrix.scaleM(model, 0, scale, scale, scale)

        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
    }

    private fun createStarBuffer() {
        val stars = createGalaxyStars(MAX_STAR_COUNT)
        val buffer: FloatBuffer = GlUtils.floatBuffer(stars)
        val vaos = IntArray(1)
        val vbos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glGenBuffers(1, vbos, 0)
        starVao = vaos[0]
        starVbo = vbos[0]

        GLES30.glBindVertexArray(starVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            stars.size * Float.SIZE_BYTES,
            buffer,
            GLES30.GL_STATIC_DRAW,
        )
        val stride = STAR_COMPONENT_COUNT * Float.SIZE_BYTES
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(
            1,
            3,
            GLES30.GL_FLOAT,
            false,
            stride,
            3 * Float.SIZE_BYTES,
        )
        GLES30.glBindVertexArray(0)
    }

    private fun createGalaxyStars(count: Int): FloatArray {
        val random = Random(STAR_SEED)
        val stars = FloatArray(count * STAR_COMPONENT_COUNT)
        val tau = (PI * 2.0).toFloat()
        var offset = 0

        repeat(count) { index ->
            val normalizedRadius = random.nextFloat().toDouble().pow(0.58).toFloat()
            val radius = MIN_RADIUS + normalizedRadius * GALAXY_RADIUS
            val arm = index % SPIRAL_ARM_COUNT
            val armAngle = arm * tau / SPIRAL_ARM_COUNT
            val armSpread = (0.055f + radius * 0.105f) * random.nextGaussian().toFloat()
            val angle = armAngle + radius * SPIRAL_TWIST + armSpread
            val radialScatter = random.nextGaussian().toFloat() * (0.025f + radius * 0.018f)
            val scatteredRadius = radius + radialScatter
            val x = cos(angle) * scatteredRadius
            val y = sin(angle) * scatteredRadius
            val diskThickness = (0.025f + (1f - normalizedRadius) * 0.18f)
            val z = random.nextGaussian().toFloat() * diskThickness
            val coreBoost = 1f - min(1f, radius / 1.25f)
            val baseSize = 1.05f + random.nextFloat() * 1.65f + coreBoost * 0.75f
            val colorBand = when {
                index % 11 == 0 -> 2f
                index % 3 == 0 -> 1f
                else -> 0f
            }
            val phase = random.nextFloat() * tau

            stars[offset++] = x
            stars[offset++] = y
            stars[offset++] = z
            stars[offset++] = baseSize
            stars[offset++] = colorBand
            stars[offset++] = phase
        }
        return stars
    }

    private fun applyPalette(force: Boolean) {
        val palette = requestedPalette
        if (!force && appliedPalette == palette) return
        appliedPalette = palette

        backgroundR = Color.red(palette.background) / 255f
        backgroundG = Color.green(palette.background) / 255f
        backgroundB = Color.blue(palette.background) / 255f
        primaryR = Color.red(palette.primary) / 255f
        primaryG = Color.green(palette.primary) / 255f
        primaryB = Color.blue(palette.primary) / 255f
        secondaryR = Color.red(palette.secondary) / 255f
        secondaryG = Color.green(palette.secondary) / 255f
        secondaryB = Color.blue(palette.secondary) / 255f
        tertiaryR = Color.red(palette.tertiary) / 255f
        tertiaryG = Color.green(palette.tertiary) / 255f
        tertiaryB = Color.blue(palette.tertiary) / 255f
        GLES30.glClearColor(backgroundR, backgroundG, backgroundB, 1f)
    }

    private fun updateAdaptiveDrawCount() {
        qualityFrameCounter++
        if (qualityFrameCounter < QUALITY_SAMPLE_FRAMES) return
        qualityFrameCounter = 0

        drawCount = when {
            frameTimeEmaMs > SLOW_FRAME_THRESHOLD_MS -> max(MIN_STAR_COUNT, drawCount - STAR_COUNT_STEP)
            frameTimeEmaMs < FAST_FRAME_THRESHOLD_MS -> min(MAX_STAR_COUNT, drawCount + STAR_COUNT_STEP)
            else -> drawCount
        }
    }

    private fun cacheUniformLocations() {
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uEnergy = GLES30.glGetUniformLocation(program, "uEnergy")
        uBass = GLES30.glGetUniformLocation(program, "uBass")
        uMid = GLES30.glGetUniformLocation(program, "uMid")
        uTreble = GLES30.glGetUniformLocation(program, "uTreble")
        uBeat = GLES30.glGetUniformLocation(program, "uBeat")
        uViewportHeight = GLES30.glGetUniformLocation(program, "uViewportHeight")
        uReducedMotion = GLES30.glGetUniformLocation(program, "uReducedMotion")
        uPrimaryColor = GLES30.glGetUniformLocation(program, "uPrimaryColor")
        uSecondaryColor = GLES30.glGetUniformLocation(program, "uSecondaryColor")
        uTertiaryColor = GLES30.glGetUniformLocation(program, "uTertiaryColor")
    }

    private companion object {
        const val STAR_COMPONENT_COUNT = 6
        const val SPIRAL_ARM_COUNT = 4
        const val MIN_STAR_COUNT = 4_096
        const val INITIAL_DRAW_COUNT = 5_632
        const val MAX_STAR_COUNT = 6_656
        const val STAR_COUNT_STEP = 512
        const val STAR_SEED = 0x4D_50_56_52L
        const val QUALITY_SAMPLE_FRAMES = 150
        const val SLOW_FRAME_THRESHOLD_MS = 20.5f
        const val FAST_FRAME_THRESHOLD_MS = 14.5f
        const val FRAME_TIME_EMA_WEIGHT = 0.025f
        const val TOUCH_EASING = 0.075f
        const val GALAXY_TILT_DEGREES = 56f
        const val BASE_SCALE = 0.92f
        const val MIN_RADIUS = 0.035f
        const val GALAXY_RADIUS = 3.25f
        const val SPIRAL_TWIST = 1.72f
        const val NANOS_PER_MILLISECOND = 1_000_000f
        const val MILLISECONDS_PER_SECOND = 1_000f
    }
}
