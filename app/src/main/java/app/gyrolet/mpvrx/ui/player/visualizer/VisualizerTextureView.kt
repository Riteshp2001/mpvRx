package app.gyrolet.mpvrx.ui.player.visualizer

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import javax.microedition.khronos.opengles.GL10

internal interface VsyncRenderer : GLSurfaceView.Renderer {
  fun onFrame(frameTimeNanos: Long)

  override fun onDrawFrame(gl: GL10?) = onFrame(System.nanoTime())
}

internal abstract class VisualizerTextureView(
  context: Context,
) : TextureView(context), TextureView.SurfaceTextureListener {
  private var glRenderer: GLSurfaceView.Renderer? = null
  private var renderSession: RenderSession? = null
  private var retiringSession: RenderSession? = null

  var renderMode: Int = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    set(value) {
      require(value == GLSurfaceView.RENDERMODE_WHEN_DIRTY || value == GLSurfaceView.RENDERMODE_CONTINUOUSLY)
      field = value
      updateRendering()
    }

  init {
    isOpaque = false
    surfaceTextureListener = this
  }

  protected fun setRenderer(renderer: GLSurfaceView.Renderer) {
    check(glRenderer == null)
    glRenderer = renderer
  }

  override fun onSurfaceTextureAvailable(
    surface: SurfaceTexture,
    width: Int,
    height: Int,
  ) {
    renderSession = RenderSession(surface, checkNotNull(glRenderer), width, height, retiringSession)
    retiringSession = null
    updateRendering()
  }

  override fun onSurfaceTextureSizeChanged(
    surface: SurfaceTexture,
    width: Int,
    height: Int,
  ) {
    renderSession?.resize(width, height)
  }

  override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
    val session = renderSession ?: return true
    renderSession = null
    retiringSession = session
    session.release()
    return false
  }

  override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

  override fun onVisibilityChanged(
    changedView: View,
    visibility: Int,
  ) {
    super.onVisibilityChanged(changedView, visibility)
    updateRendering()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    updateRendering()
  }

  private fun updateRendering() {
    renderSession?.setRenderingEnabled(
      isShown && windowVisibility == VISIBLE && renderMode == GLSurfaceView.RENDERMODE_CONTINUOUSLY,
    )
  }

  private class RenderSession(
    private val texture: SurfaceTexture,
    private val renderer: GLSurfaceView.Renderer,
    width: Int,
    height: Int,
    previousSession: RenderSession?,
  ) {
    private val thread = HandlerThread("VisualizerGL").apply { start() }
    private val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE
    private var choreographer: Choreographer? = null
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> drawFrame(frameTimeNanos) }
    private var surfaceWidth = width.coerceAtLeast(1)
    private var surfaceHeight = height.coerceAtLeast(1)
    private var sizeChanged = true
    private var renderingEnabled = false
    private var ready = false

    @Volatile
    private var stopping = false

    init {
      handler.post {
        try {
          previousSession?.thread?.join()
          if (!stopping) {
            createEgl()
            renderer.onSurfaceCreated(null, null)
            choreographer = Choreographer.getInstance()
            ready = true
            requestFrame()
          }
        } catch (error: Exception) {
          if (error is InterruptedException) Thread.currentThread().interrupt()
          fail(error)
        }
      }
    }

    fun resize(
      width: Int,
      height: Int,
    ) {
      handler.post {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        sizeChanged = true
        requestFrame()
      }
    }

    fun setRenderingEnabled(enabled: Boolean) {
      handler.post {
        renderingEnabled = enabled
        if (enabled) {
          requestFrame()
        } else {
          choreographer?.removeFrameCallback(frameCallback)
        }
      }
    }

    fun release() {
      stopping = true
      handler.post {
        try {
          releaseEgl()
        } finally {
          texture.release()
          thread.quitSafely()
        }
      }
    }

    private fun requestFrame() {
      if (!ready || stopping) return
      choreographer?.removeFrameCallback(frameCallback)
      choreographer?.postFrameCallback(frameCallback)
    }

    private fun drawFrame(frameTimeNanos: Long) {
      if (!ready || stopping) return
      try {
        if (sizeChanged) {
          renderer.onSurfaceChanged(null, surfaceWidth, surfaceHeight)
          sizeChanged = false
        }
        if (renderer is VsyncRenderer) {
          renderer.onFrame(frameTimeNanos)
        } else {
          renderer.onDrawFrame(null)
        }
        check(EGL14.eglSwapBuffers(display, surface)) { "Unable to swap visualizer buffers" }
        if (renderingEnabled) requestFrame()
      } catch (error: RuntimeException) {
        fail(error)
      }
    }

    private fun createEgl() {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      check(display != EGL14.EGL_NO_DISPLAY) { "Unable to get visualizer EGL display" }
      val version = IntArray(2)
      check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Unable to initialize visualizer EGL" }
      val attributes =
        intArrayOf(
          EGL14.EGL_RENDERABLE_TYPE,
          EGLExt.EGL_OPENGL_ES3_BIT_KHR,
          EGL14.EGL_SURFACE_TYPE,
          EGL14.EGL_WINDOW_BIT,
          EGL14.EGL_RED_SIZE,
          8,
          EGL14.EGL_GREEN_SIZE,
          8,
          EGL14.EGL_BLUE_SIZE,
          8,
          EGL14.EGL_ALPHA_SIZE,
          8,
          EGL14.EGL_DEPTH_SIZE,
          16,
          EGL14.EGL_NONE,
        )
      val configs = arrayOfNulls<EGLConfig>(1)
      val configCount = IntArray(1)
      check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, configCount, 0) && configCount[0] > 0) {
        "No transparent OpenGL ES 3 visualizer configuration"
      }
      val config = checkNotNull(configs[0])
      context =
        EGL14.eglCreateContext(
          display,
          config,
          EGL14.EGL_NO_CONTEXT,
          intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
          0,
        )
      check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create visualizer EGL context" }
      surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
      check(surface != EGL14.EGL_NO_SURFACE) { "Unable to create visualizer EGL surface" }
      check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Unable to bind visualizer EGL context" }
    }

    private fun fail(error: Exception) {
      Log.e("VisualizerTextureView", "Visualizer rendering failed", error)
      releaseEgl()
    }

    private fun releaseEgl() {
      ready = false
      choreographer?.removeFrameCallback(frameCallback)
      choreographer = null
      if (display != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
      }
      EGL14.eglReleaseThread()
      display = EGL14.EGL_NO_DISPLAY
      context = EGL14.EGL_NO_CONTEXT
      surface = EGL14.EGL_NO_SURFACE
    }
  }
}
