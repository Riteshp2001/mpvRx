package app.gyrolet.mpvrx.domain.anime4k

import android.content.Context

import java.io.File
import java.io.FileOutputStream

/**
 * Anime4K Manager
 * Manages GLSL shaders for real-time anime upscaling
 */
class Anime4KManager(private val context: Context) {

  companion object {
    private const val SHADER_DIR = "shaders"
    private const val MPV_SHADER_PREFIX = "~~/shaders/"
    private val REQUIRED_SHADER_FILES = listOf(
      "Anime4K_Clamp_Highlights.glsl",
      "Anime4K_AutoDownscalePre_x2.glsl",
      "Anime4K_Restore_CNN_S.glsl",
      "Anime4K_Restore_CNN_M.glsl",
      "Anime4K_Restore_CNN_L.glsl",
      "Anime4K_Restore_CNN_Soft_S.glsl",
      "Anime4K_Restore_CNN_Soft_M.glsl",
      "Anime4K_Restore_CNN_Soft_L.glsl",
      "Anime4K_Upscale_CNN_x2_S.glsl",
      "Anime4K_Upscale_CNN_x2_M.glsl",
      "Anime4K_Upscale_CNN_x2_L.glsl",
      "Anime4K_Upscale_Denoise_CNN_x2_S.glsl",
      "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
      "Anime4K_Upscale_Denoise_CNN_x2_L.glsl",
    )
    val BUILT_IN_SHADER_FILES: Set<String> = REQUIRED_SHADER_FILES.toSet()
  }

  // Shader quality levels
  enum class Quality(val suffix: String, val titleRes: Int) {
    FAST("S", app.gyrolet.mpvrx.R.string.anime4k_quality_fast),
    BALANCED("M", app.gyrolet.mpvrx.R.string.anime4k_quality_balanced),
    HIGH("L", app.gyrolet.mpvrx.R.string.anime4k_quality_high)
  }

  // Anime4K modes
  enum class Mode(val titleRes: Int) {
    OFF(app.gyrolet.mpvrx.R.string.anime4k_mode_off),
    A(app.gyrolet.mpvrx.R.string.anime4k_mode_a),
    B(app.gyrolet.mpvrx.R.string.anime4k_mode_b),
    C(app.gyrolet.mpvrx.R.string.anime4k_mode_c),
    A_PLUS(app.gyrolet.mpvrx.R.string.anime4k_mode_a_plus),
    B_PLUS(app.gyrolet.mpvrx.R.string.anime4k_mode_b_plus),
    C_PLUS(app.gyrolet.mpvrx.R.string.anime4k_mode_c_plus)
  }

  private var shaderDir: File? = null
  private var isInitialized = false

  /**
   * Initialize: copy shaders from assets to internal storage
   * This must be called and complete successfully before using getShaderChain()
   */
  fun initialize(): Boolean {
    if (isInitialized) {
      return true
    }
    
    return try {
      // Create shader directory
      shaderDir = File(context.filesDir, SHADER_DIR)
      if (!shaderDir!!.exists()) {
        val created = shaderDir!!.mkdirs()
        if (!created) {
          return false
        }
      }

      // List and copy all shader files from assets.
      // If any required file is missing/invalid, force-copy it.
      val shaderFiles = context.assets.list(SHADER_DIR)?.filter { it.endsWith(".glsl") } ?: emptyList()
      for (fileName in shaderFiles) {
        val forceCopy = fileName in REQUIRED_SHADER_FILES
        if (!copyShaderFromAssets(fileName, forceCopy = forceCopy)) {
          return false
        }
      }

      val missingRequiredFiles = REQUIRED_SHADER_FILES.any { required ->
        val file = File(shaderDir, required)
        !file.exists() || file.length() <= 0L
      }
      if (missingRequiredFiles) {
        return false
      }
      
      isInitialized = true
      true
    } catch (e: Exception) {
      isInitialized = false
      false
    }
  }

  private fun copyShaderFromAssets(fileName: String, forceCopy: Boolean = false): Boolean {
    val destFile = File(shaderDir, fileName)

    // Skip only when not forced and file already exists and is valid.
    if (!forceCopy && destFile.exists() && destFile.length() > 0) {
      return true
    }

    try {
      context.assets.open("$SHADER_DIR/$fileName").use { input ->
        FileOutputStream(destFile).use { output ->
          input.copyTo(output)
        }
      }
      return true
    } catch (e: Exception) {
      return false
    }
  }

  /**
   * Get shader chain for the specified mode and quality
   * Returns empty string if mode is OFF or initialization failed
   */
  fun getShaderChain(mode: Mode, quality: Quality): String {
    return getShaderPaths(mode, quality).joinToString(":")
  }

  fun getShaderPaths(mode: Mode, quality: Quality): List<String> {
    return getShaderFiles(mode, quality).map { file ->
      "$MPV_SHADER_PREFIX${file.name}"
    }
  }

  fun getShaderFiles(mode: Mode, quality: Quality): List<File> {
    if (mode == Mode.OFF) {
      return emptyList()
    }

    if (!isInitialized && !initialize()) {
      return emptyList()
    }

    if (shaderDir == null || !shaderDir!!.exists()) {
      return emptyList()
    }

    val shaders = mutableListOf<File>()
    val q = quality.suffix

    // Always add Clamp_Highlights (prevent ringing)
    shaders.add(getShaderFile("Anime4K_Clamp_Highlights.glsl"))

    // Add shaders based on mode
    when (mode) {
      Mode.A -> {
        // Mode A: Restore -> Upscale -> Upscale
        shaders.add(getShaderFile("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.B -> {
        // Mode B: Restore_Soft -> Upscale -> Upscale
        shaders.add(getShaderFile("Anime4K_Restore_CNN_Soft_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.C -> {
        // Mode C: Upscale_Denoise -> Upscale
        shaders.add(getShaderFile("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.A_PLUS -> {
        // Mode A+A: Restore -> Upscale -> Restore -> Upscale
        shaders.add(getShaderFile("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderFile("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.B_PLUS -> {
        // Mode B+B: Restore_Soft -> Upscale -> Restore_Soft -> Upscale
        shaders.add(getShaderFile("Anime4K_Restore_CNN_Soft_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderFile("Anime4K_Restore_CNN_Soft_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.C_PLUS -> {
        // Mode C+A: Upscale_Denoise -> Restore -> Upscale
        shaders.add(getShaderFile("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderFile("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderFile("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.OFF -> { /* Already handled */ }
    }

    // Validate that all shader files exist
    val missingShaders = shaders.filterNot { file ->
      file.exists()
    }
    
    if (missingShaders.isNotEmpty()) {
      return emptyList()
    }

    return shaders
  }

  private fun getShaderFile(fileName: String): File {
    return File(shaderDir, fileName)
  }
}
