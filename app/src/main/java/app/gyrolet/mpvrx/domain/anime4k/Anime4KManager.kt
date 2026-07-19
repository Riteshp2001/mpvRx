package app.gyrolet.mpvrx.domain.anime4k

import android.content.Context

import java.io.File

/**
 * Anime4K Manager
 * Manages GLSL shaders for real-time anime upscaling
 */
class Anime4KManager(private val context: Context) {

  companion object {
    val BUILT_IN_SHADER_FILES: Set<String> = Anime4KShaderCatalog.requiredFileSet
    val DEFAULT_QUALITY = Quality.BALANCED

    // GLSL tokens that require FP32 (highp) float precision. Passes containing any
    // of these (FSR EASU/RCAS) are excluded from the FP16/mediump optimization.
    private val FP32_MARKERS = listOf(
      "floatBitsToUint",
      "uintBitsToFloat",
      "floatBitsToInt",
      "intBitsToFloat",
      "bitfieldExtract",
      "bitfieldInsert",
      "FsrEasu",
      "FsrRcas",
      "APrxLoRcpF1",
      "APrxLoRsqF1",
      "APrxMedRcpF1",
    )
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
      shaderDir = File(context.filesDir, Anime4KShaderCatalog.INSTALL_DIRECTORY)
      if (!shaderDir!!.exists()) {
        val created = shaderDir!!.mkdirs()
        if (!created) {
          return false
        }
      }

      // List and copy all shader files from assets.
      // If any required file is missing/invalid, force-copy it.
      val shaderFiles = context.assets.list(Anime4KShaderCatalog.ASSET_DIRECTORY)
        ?.filter { it.endsWith(".glsl") }
        .orEmpty()
      for (fileName in shaderFiles) {
        val forceCopy = fileName in Anime4KShaderCatalog.requiredFileSet
        if (!copyShaderFromAssets(fileName, forceCopy = forceCopy)) {
          return false
        }
      }

      val missingRequiredFiles = Anime4KShaderCatalog.requiredFiles.any { required ->
        val file = File(shaderDir, required)
        !file.exists() || file.length() <= 0L
      }
      if (missingRequiredFiles) {
        return false
      }

      removeLegacyFlatShaderCopies()
      
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
      val originalContent = context.assets.open("${Anime4KShaderCatalog.ASSET_DIRECTORY}/$fileName").use { input ->
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

  private fun removeLegacyFlatShaderCopies() {
    val legacyDirectory = File(context.filesDir, "shaders")
    Anime4KShaderCatalog.requiredFiles.forEach { fileName ->
      File(legacyDirectory, fileName).takeIf(File::isFile)?.delete()
    }
  }

  /**
   * Dynamically optimizes GLSL shader code for mobile GPUs:
   * 1. Injects a per-pass float precision qualifier (default) AND explicitly rewrites
   *    all vec4/mat4/vec2/vec3/float variable declarations to use mediump (or highp
   *    for passes that require FP32). This dual approach is required because many
   *    mobile GPU drivers (especially Qualcomm Adreno) will silently promote a
   *    default mediump back to highp when the compiler detects mat4 multiplications
   *    with many float literals — ignoring the default precision header entirely.
   *    Explicit qualifiers on each declaration cannot be overridden by the driver.
   * 2. Eliminates redundant texture fetches in C.R.E.L.U. shader passes.
   *
   * FP16 safety: CNN convolutions and edge/blur filters tolerate FP16 well and gain
   * a large speed-up. However, FSR (EASU/RCAS) relies on raw bit manipulation
   * (floatBitsToUint/uintBitsToFloat) and fast reciprocal/rsqrt bit tricks that
   * produce garbage under FP16. Those passes must stay highp. Integers are always
   * kept highp (mediump int is too narrow for the bit tricks and texel indices).
   *
   * ArtCNN shaders already manage their own precision via
   * GL_EXT_shader_explicit_arithmetic_types_float16 — the injected qualifiers are
   * redundant but harmless for those passes.
   */
  private fun optimizeShaderContent(fileName: String, content: String): String {
    if (!fileName.endsWith(".glsl")) return content

    val lines = content.lines()
    val newLines = mutableListOf<String>()
    var inHeader = false
    var precisionInjectedForBlock = false
    var currentPassNeedsHighp = false

    for (i in lines.indices) {
      val line = lines[i]
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
          if (!precisionInjectedForBlock && trimmed.isNotEmpty() && !trimmed.startsWith("//")) {
            currentPassNeedsHighp = passNeedsHighpFloat(lines, i)
            val floatPrecision = if (currentPassNeedsHighp) "highp" else "mediump"
            // 1. Default precision header (catches anything we miss below)
            newLines.add("precision $floatPrecision float;")
            newLines.add("precision highp int;")
            precisionInjectedForBlock = true
          }
        }
        // 2. Explicitly qualify every float-type variable declaration in the body.
        // This is what actually enforces FP16 on drivers that ignore the default.
        val rewritten = if (precisionInjectedForBlock && !trimmed.startsWith("//!")) {
          rewriteLineWithExplicitPrecision(line, if (currentPassNeedsHighp) "highp" else "mediump")
        } else {
          line
        }
        newLines.add(rewritten)
      }
    }
    val withPrecision = newLines.joinToString("\n")

    // Optimize redundant texture fetches inside C.R.E.L.U. convolution passes
    return optimizeCreluPasses(withPrecision)
  }

  /**
   * Rewrites unqualified float-type declarations in a single GLSL line to use
   * an explicit precision qualifier. Only modifies actual variable declarations —
   * skips comments, already-qualified types, function signatures, and #defines.
   *
   * Examples (with precision = "mediump"):
   *   `vec4 result = ...`     → `mediump vec4 result = ...`
   *   `mat4(-0.09, ...)       → `mediump mat4(-0.09, ...)`  (local declaration inside body)
   *   `float g = 0.0;`        → `mediump float g = 0.0;`
   *   `mediump vec4 x = ...`  → unchanged (already qualified)
   *   `highp vec4 x = ...`    → unchanged (already qualified)
   *   `vec4 hook() {`         → unchanged (function return type, not a variable)
   *   `// vec4 comment`       → unchanged (comment)
   *   `#define go_0(...)`     → unchanged (macro)
   */
  private fun rewriteLineWithExplicitPrecision(line: String, precision: String): String {
    val trimmed = line.trim()

    // Skip blank lines, comments, preprocessor directives
    if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) return line

    // Skip lines that already have an explicit precision qualifier
    if (trimmed.startsWith("mediump ") || trimmed.startsWith("highp ") || trimmed.startsWith("lowp ")) return line

    // Types to qualify. Order matters: check longer names first (vec4 before vec).
    val floatTypes = listOf("mat4", "mat3", "mat2", "vec4", "vec3", "vec2", "float")

    val leading = line.length - line.trimStart().length
    val indent = line.take(leading)
    val rest = trimmed

    for (type in floatTypes) {
      // Match `type` followed by a space or `(` — a variable declaration or constructor.
      // Do NOT match function signatures like `vec4 hook()` (contains no `=` on same line
      // and is followed by `(`+identifier+`)` pattern); those are return types.
      if (rest.startsWith("$type ") || rest.startsWith("$type(")) {
        // Exclude function definitions: `vec4 identifier(` without `=` before `(`
        val afterType = rest.removePrefix(type).trimStart()
        val isFunctionDef = afterType.contains("(") &&
          !afterType.substringBefore("(").contains("=") &&
          afterType.first() != '('  // constructor call starts with `(`
        if (isFunctionDef) return line

        return "$indent$precision $rest"
      }
    }
    return line
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

  /**
   * Returns true if the pass body starting at [bodyStart] contains FP32-sensitive
   * operations (FSR bit tricks) that must not be compiled as FP16/mediump.
   */
  private fun passNeedsHighpFloat(lines: List<String>, bodyStart: Int): Boolean {
    var j = bodyStart
    while (j < lines.size && !lines[j].trim().startsWith("//!")) {
      val body = lines[j]
      if (FP32_MARKERS.any { body.contains(it) }) {
        return true
      }
      j++
    }
    return false
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
