package app.gyrolet.mpvrx.utils

import android.os.Build

/**
 * Maps an Android device's build identifiers to a Qualcomm Adreno GPU
 * model number. Used by the GPU driver recommendation UI because the
 * native bridge's getGpuInfo() currently only reports the generic string
 * "Qualcomm Adreno GPU Detected" without a model number, which makes
 * GpuDriverManager.getRecommendedDriver(0) always return "Unsupported".
 *
 * Build.SOC_MODEL is preferred (API 31+, stable manufacturer-provided
 * identifier). Build.BOARD codenames are the fallback for older devices
 * and for OEMs that don't fill SOC_MODEL. Returns 0 if no entry matches.
 */
object AdrenoLookup {

    // Build.SOC_MODEL -> Adreno number. Strings are the Qualcomm part
    // numbers manufacturers print on the SoC (e.g. SM8550 = SD 8 Gen 2).
    private val socToAdreno: Map<String, Int> = mapOf(
        // 8-series (flagship)
        "SM8850" to 840,  // SD 8 Elite Gen 5 (rumored)
        "SM8750" to 830,  // SD 8 Elite
        "SM8650" to 750,  // SD 8 Gen 3
        "SM8635" to 735,  // SD 8s Gen 3
        "SM8550" to 740,  // SD 8 Gen 2
        "SM8475" to 730,  // SD 8+ Gen 1
        "SM8450" to 730,  // SD 8 Gen 1
        "SM8350" to 660,  // SD 888
        "SM8325" to 660,  // SD 888+
        "SM8250" to 650,  // SD 865 / 865+
        "SM8150" to 640,  // SD 855 / 855+
        // 7-series
        "SM7675" to 732,  // SD 7+ Gen 3
        "SM7550" to 720,  // SD 7 Gen 3
        "SM7475" to 725,  // SD 7+ Gen 2
        "SM7450" to 644,  // SD 7 Gen 1
        "SM6450" to 710,  // SD 7s Gen 2
        "SM7325" to 642,  // SD 778G / 778G+
        "SM7250" to 620,  // SD 765G / 768G
        "SM7225" to 619,  // SD 750G
        // 6-series
        "SM6375" to 619,  // SD 695
        "SM6350" to 618,  // SD 690 / 732G
        "SM6225" to 610,  // SD 4 Gen 2
        // 4-series
        "SM4375" to 619,  // SD 4 Gen 1
        "SM4350" to 619,  // SD 480 5G
    )

    // Build.BOARD codename -> Adreno number. Lowercased before lookup.
    // Codenames are Qualcomm platform internal names and are stable
    // across OEMs unless the OEM explicitly overrides Build.BOARD.
    private val boardToAdreno: Map<String, Int> = mapOf(
        // 8-series codenames
        "sun" to 830,        // SD 8 Elite
        "pineapple" to 750,  // SD 8 Gen 3
        "kalama" to 740,     // SD 8 Gen 2
        "bitra" to 730,      // SD 8+ Gen 1 alt
        "waipio" to 730,     // SD 8+ Gen 1
        "taro" to 730,       // SD 8 Gen 1
        "lahaina" to 660,    // SD 888
        "kona" to 650,       // SD 865
        "msm8150" to 640,    // SD 855
        "sdm855" to 640,     // SD 855
        "sdm845" to 630,     // SD 845
        "msm8998" to 540,    // SD 835
        // 7-series
        "crow" to 725,       // SD 7+ Gen 2
        "parrot" to 644,     // SD 7 Gen 1
        "ravelin" to 710,    // SD 7s Gen 2
        "diwali" to 642,     // SD 778G+
        "shima" to 642,      // SD 778G
        "yupik" to 642,      // SD 778G+
        "saipan" to 620,     // SD 765G / 768G
        // 6/4-series
        "holi" to 619,       // SD 480 5G / 4 Gen 1
        "khaje" to 619,      // SD 4 Gen 1
        "bengal" to 610,     // SD 460
        "monaco" to 619,     // SD 4 Gen 2
    )

    /** Resolves the Adreno model number from device build info. 0 = unknown. */
    fun resolve(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MODEL
            if (soc.isNotEmpty()) socToAdreno[soc]?.let { return it }
        }
        boardToAdreno[Build.BOARD.lowercase()]?.let { return it }
        return 0
    }

    /** Human-readable label, e.g. "Qualcomm Adreno 740". Null if unknown. */
    fun displayLabel(): String? {
        val n = resolve()
        return if (n > 0) "Qualcomm Adreno $n" else null
    }
}
