/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.preferenceTheme

/**
 * Shared visual contract for every preference-backed settings screen.
 *
 * Preference storage remains owned by ComposePreference; this wrapper only supplies mpvRx's
 * expressive density, hierarchy and semantic colors so individual screens cannot silently drift
 * back to the library defaults.
 */
@Composable
internal fun ProvideExpressivePreferenceLocals(content: @Composable () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  ProvidePreferenceLocals(
    theme =
      preferenceTheme(
        categoryPadding = PaddingValues(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 10.dp),
        categoryColor = colors.primary,
        categoryTextStyle = typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        horizontalSpacing = 16.dp,
        verticalSpacing = 6.dp,
        iconContainerMinWidth = 52.dp,
        iconColor = colors.primary,
        titleColor = colors.onSurface,
        titleTextStyle = typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        summaryColor = colors.onSurfaceVariant,
        summaryTextStyle = typography.bodyMedium,
        dividerHeight = 20.dp,
      ),
    content = content,
  )
}
