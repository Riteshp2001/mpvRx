package app.gyrolet.mpvrx.exoplayer.core.common

import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguageManager {

    fun applyToCurrent(languageTag: String) {
        val normalizedLanguageTag = languageTag.trim()
        val localeList = if (normalizedLanguageTag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(normalizedLanguageTag)
        }

        AppCompatDelegate.setApplicationLocales(localeList)
        updateDefaultLocales(normalizedLanguageTag)
    }

    private fun updateDefaultLocales(languageTag: String) {
        if (languageTag.isEmpty()) {
            Locale.setDefault(Locale.getDefault())
            LocaleList.setDefault(LocaleList.getAdjustedDefault())
            return
        }

        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        LocaleList.setDefault(LocaleList(locale))
    }
}

