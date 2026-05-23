package app.gyrolet.mpvrx.exoplayer.settings.utils

import java.util.Locale
import app.gyrolet.mpvrx.exoplayer.core.common.Logger

object LocalesHelper {

    val appSupportedLocales: List<Pair<String, String>> = listOf(
        Pair("English", "en"),
        Pair("简体中文", "zh-CN"),
        Pair("繁體中文", "zh-TW"),
    )

    private val chineseVariants = listOf(
        Pair("中文（简体）", "zh-Hans"),
        Pair("中文（繁體）", "zh-Hant"),
    )

    fun getAvailableLocales(): List<Pair<String, String>> = try {
        val baseLocales = Locale.getAvailableLocales()
            .filter { it.language.isNotBlank() && it.language != "und" }
            .map { Pair(it.displayLanguage, it.language) }
            .filter { (name, code) -> name != code && name.isNotBlank() }
            .distinctBy { it.second }
            .filter { it.second != "zh" }
            .sortedBy { it.first }
        baseLocales + chineseVariants
    } catch (e: Exception) {
        Logger.error(TAG, "Failed to load available locales", e)
        listOf()
    }

    fun getLocaleDisplayLanguage(key: String): String = try {
        if (key.isBlank()) return ""

        chineseVariants.firstOrNull { it.second == key }?.first?.let { return it }

        Locale.getAvailableLocales().firstOrNull { locale ->
            locale.isO3Language == key || locale.language == key
        }?.displayLanguage.orEmpty()
    } catch (e: Exception) {
        Logger.error(TAG, "Failed to resolve locale display language: $key", e)
        ""
    }

    fun getAppLocaleDisplayName(languageTag: String): String = appSupportedLocales.firstOrNull {
        it.second == languageTag
    }?.first.orEmpty()

    private const val TAG = "LocalesHelper"
}
