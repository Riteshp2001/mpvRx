package app.gyrolet.mpvrx.exoplayer.feature.player.subtitle

enum class SubtitleFontPolicy {
    Ass,
    SystemCaptionStyle,
    ExternalOrFallback,
}

fun decideSubtitleFontPolicy(
    isAssSubtitleSelected: Boolean,
    shouldUseSystemCaptionStyle: Boolean,
    hasExternalFont: Boolean,
): SubtitleFontPolicy {
    if (isAssSubtitleSelected) return SubtitleFontPolicy.Ass
    if (shouldUseSystemCaptionStyle) return SubtitleFontPolicy.SystemCaptionStyle
    return SubtitleFontPolicy.ExternalOrFallback
}
