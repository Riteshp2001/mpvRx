// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import app.gyrolet.mpvrx.R
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

internal object StatisticsTranslations {
  fun localizedContext(
    context: Context,
    locales: LocaleListCompat = AppCompatDelegate.getApplicationLocales(),
  ): Context {
    val tags = (if (locales.isEmpty) LocaleManagerCompat.getSystemLocales(context) else locales).toLanguageTags()
    val application = context.applicationContext
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocales(LocaleList.forLanguageTags(tags))
    return application.createConfigurationContext(configuration)
  }

  fun payload(context: Context): String {
    val english = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
    val keys = context.createConfigurationContext(english).resources.getStringArray(R.array.stats_lua_labels)
    val labels = context.resources.getStringArray(R.array.stats_lua_labels)
    return buildJsonObject {
      put(
        "labels",
        buildJsonObject {
          keys.forEachIndexed { index, key ->
            if (key != labels[index]) put(key, labels[index])
          }
        },
      )
      put(
        "case_fold",
        buildJsonObject {
          labels.asSequence().flatMap { it.asSequence() }.distinct().forEach { char ->
            if (char.code > 127) {
              val upper = char.uppercaseChar()
              val lower = char.lowercaseChar()
              if (upper != lower) put(upper.toString(), lower.toString())
            }
          }
        },
      )
    }.toString()
  }
}
