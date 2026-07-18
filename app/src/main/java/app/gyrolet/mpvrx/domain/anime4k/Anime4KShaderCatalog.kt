package app.gyrolet.mpvrx.domain.anime4k

/** Single source of truth for the Anime4K shaders bundled with the app. */
object Anime4KShaderCatalog {
  const val ASSET_DIRECTORY = "shaders/anime4k"
  const val INSTALL_DIRECTORY = "shaders/anime4k"

  val requiredFiles = listOf(
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

  val requiredFileSet: Set<String> = requiredFiles.toSet()
}
