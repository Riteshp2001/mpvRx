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
      "Anime4K_Darken_Fast.glsl",
      "Anime4K_Darken_HQ.glsl",
      "Anime4K_Darken_VeryFast.glsl",
      "Anime4K_Thin_Fast.glsl",
      "Anime4K_Thin_HQ.glsl",
      "Anime4K_Thin_VeryFast.glsl",
      "Anime4K_Deblur_DoG.glsl",
      "Anime4K_Deblur_Original.glsl",
      "Ani4Kv2_ArtCNN_C4F32_i2_CMP.glsl",

    )
    val BUILT_IN_SHADER_FILES: Set<String> = REQUIRED_SHADER_FILES.toSet()
    val DEFAULT_QUALITY = Quality.BALANCED
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
    C_PLUS(app.gyrolet.mpvrx.R.string.anime4k_mode_c_plus),
    ARTCNN(app.gyrolet.mpvrx.R.string.anime4k_mode_artcnn)
  }

  private var shaderDir: File? = null
  private var isInitialized = false
  @Volatile
  private var enableDarken: Boolean = true
  @Volatile
  private var enableThin: Boolean = true
  @Volatile
  private var enableDeblur: Boolean = false

  fun setPostFilters(
    darken: Boolean,
    thin: Boolean,
    deblur: Boolean,
  ) {
    enableDarken = darken
    enableThin = thin
    enableDeblur = deblur
  }

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
      // Read the original shader source code from assets
      val originalContent = context.assets.open("$SHADER_DIR/$fileName").use { input ->
        input.bufferedReader().use { it.readText() }
      }

      // Dynamically compile and optimize the shader GLSL code
      val optimizedContent = optimizeShaderContent(fileName, originalContent)

      // Write the optimized shader code to the destination file
      destFile.writeText(optimizedContent)
      android.util.Log.i("Anime4KManager", "Optimized and copied shader: $fileName")
      return true
    } catch (e: Exception) {
      android.util.Log.e("Anime4KManager", "Failed to copy and optimize shader: $fileName", e)
      return false
    }
  }

  /**
   * Dynamically optimizes GLSL shader code for mobile GPUs:
   * 1. Injects 'precision mediump float;' to force ultra-fast FP16 mode.
   * 2. Eliminates redundant texture fetches in C.R.E.L.U. shader passes.
   */
  private fun optimizeShaderContent(fileName: String, content: String): String {
    if (!fileName.endsWith(".glsl")) return content

    val lines = content.lines()
    val newLines = mutableListOf<String>()
    var inHeader = false
    var precisionInjectedForBlock = false

    for (line in lines) {
      val trimmed = line.trim()
      if (trimmed.startsWith("//!")) {
        if (!inHeader) {
          inHeader = true
          precisionInjectedForBlock = false
        }
        newLines.add(line)
      } else {
        if (inHeader) {
          inHeader = false
          if (!precisionInjectedForBlock) {
            // Inject precision at the FIRST body line of the pass — i.e. the
            // moment we leave the //! directive block — regardless of whether
            // that line is blank or a // comment. Most Anime4K shaders put a
            // blank line between the //! directives and the code; the previous
            // `trimmed.isNotEmpty() && !startsWith("//")` guard caused those
            // passes (Clamp, AutoDownscalePre, Thin, Darken, Deblur, ArtCNN and
            // every Anime4K-Ultra pass) to be skipped entirely, silently leaving
            // them at mpv's global `precision highp float` (FP32). A leading
            // `precision` declaration is valid GLSL at the top of the hook body,
            // so emitting it here (before the blank/comment) is safe.
            // Choose float precision from this pass's body (until the next //!
            // header or end of file). Keep highp int for every pass.
            val floatPrecision = if (passNeedsHighpFloat(lines, i)) "highp" else "mediump"
            newLines.add("precision $floatPrecision float;")
            newLines.add("precision highp int;")
            precisionInjectedForBlock = true
          }
        }
        newLines.add(line)
      }
    }
    val withPrecision = newLines.joinToString("\n")

    // Optimize redundant texture fetches inside C.R.E.L.U. convolution passes
    return optimizeCreluPasses(withPrecision)
  }

  private fun optimizeCreluPasses(content: String): String {
    val passes = content.split("(?=(?://!DESC|//!HOOK))".toRegex())
    val optimizedPasses = passes.map { pass ->
      optimizeSinglePass(pass)
    }
    return optimizedPasses.joinToString("")
  }

  private fun optimizeSinglePass(pass: String): String {
    val go0Regex = """#define\s+go_0\([^\)]+\)\s+\(max\(\(?\s*([A-Za-z0-9_]+)_texOff\(vec2\([^\)]+\)\)\)?\s*,\s*0\.0\)\)""".toRegex()
    val go1Regex = """#define\s+go_1\([^\)]+\)\s+\(max\(\-\(?\s*([A-Za-z0-9_]+)_texOff\(vec2\([^\)]+\)\)\)?\s*,\s*0\.0\)\)""".toRegex()

    val match0 = go0Regex.find(pass)
    val match1 = go1Regex.find(pass)

    if (match0 == null || match1 == null) {
      return pass
    }

    val texName = match0.groupValues[1]
    val texName1 = match1.groupValues[1]

    if (texName != texName1) {
      return pass
    }

    var optimized = pass
    optimized = optimized.replace(match0.value, "// optimized go_0 macro")
    optimized = optimized.replace(match1.value, "// optimized go_1 macro")

    val hookStartRegex = """vec4\s+hook\(\s*\)\s*\{""".toRegex()
    val hookDeclaration = """
      vec4 hook() {
          vec4 t_m1_m1 = ${texName}_texOff(vec2(-1.0, -1.0));
          vec4 t_m1_0  = ${texName}_texOff(vec2(-1.0, 0.0));
          vec4 t_m1_1  = ${texName}_texOff(vec2(-1.0, 1.0));
          vec4 t_0_m1  = ${texName}_texOff(vec2(0.0, -1.0));
          vec4 t_0_0   = ${texName}_texOff(vec2(0.0, 0.0));
          vec4 t_0_1   = ${texName}_texOff(vec2(0.0, 1.0));
          vec4 t_1_m1  = ${texName}_texOff(vec2(1.0, -1.0));
          vec4 t_1_0   = ${texName}_texOff(vec2(1.0, 0.0));
          vec4 t_1_1   = ${texName}_texOff(vec2(1.0, 1.0));
    """.trimIndent()

    optimized = optimized.replaceFirst(hookStartRegex, hookDeclaration)

    val go0CallRegex = """go_0\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)""".toRegex()
    optimized = go0CallRegex.replace(optimized) { result ->
      val x = mapCoord(result.groupValues[1])
      val y = mapCoord(result.groupValues[2])
      "max(t_${x}_${y}, 0.0)"
    }

    val go1CallRegex = """go_1\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)""".toRegex()
    optimized = go1CallRegex.replace(optimized) { result ->
      val x = mapCoord(result.groupValues[1])
      val y = mapCoord(result.groupValues[2])
      "max(-t_${x}_${y}, 0.0)"
    }

    // Defensive: mapCoord() emits "unknown" for any offset outside {-1,0,1}.
    // If that ever happens (a future shader with a larger kernel), the rewrite
    // would reference an undeclared texel like `t_unknown_0` and fail to
    // compile, causing mpv to drop the whole shader. Bail to the original pass
    // in that case so correctness never depends on the kernel being 3x3.
    if (optimized.contains("_unknown")) {
      return pass
    }

    return optimized
  }

  private fun mapCoord(c: String): String {
    return when (c.trim()) {
      "-1.0", "-1" -> "m1"
      "0.0", "0" -> "0"
      "1.0", "1" -> "1"
      else -> "unknown"
    }
  }

  /**
   * Get shader chain for the specified mode and quality
   * Returns empty string if mode is OFF or initialization failed
   */
  fun getShaderChain(mode: Mode, quality: Quality): String {
    return getShaderPaths(mode, quality).joinToString(":")
  }

  fun getShaderPaths(mode: Mode): List<String> = getShaderPaths(mode, DEFAULT_QUALITY)

  fun getShaderPaths(mode: Mode, quality: Quality): List<String> {
    return getShaderFiles(mode, quality).map { file ->
      file.absolutePath
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
      Mode.ARTCNN -> {
        shaders.add(getShaderFile("Ani4Kv2_ArtCNN_C4F32_i2_CMP.glsl"))
      }
      Mode.OFF -> { /* Already handled */ }
    }

    // Optional Anime4K edge/detail modules, matching the Android Anime4K fork order.
    if (enableDeblur) {
      shaders.add(getShaderFile("Anime4K_Deblur_DoG.glsl"))
    }
    if (enableDarken) {
      shaders.add(getShaderFile(darkenShaderFile(quality)))
    }
    if (enableThin) {
      shaders.add(getShaderFile(thinShaderFile(quality)))
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

  private fun darkenShaderFile(quality: Quality): String =
    when (quality) {
      Quality.FAST -> "Anime4K_Darken_Fast.glsl"
      Quality.BALANCED,
      Quality.HIGH -> "Anime4K_Darken_HQ.glsl"
    }

  private fun thinShaderFile(quality: Quality): String =
    when (quality) {
      Quality.FAST -> "Anime4K_Thin_Fast.glsl"
      Quality.BALANCED,
      Quality.HIGH -> "Anime4K_Thin_HQ.glsl"
    }
}
