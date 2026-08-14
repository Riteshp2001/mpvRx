/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import app.gyrolet.mpvrx.presentation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsSearchTarget(
  val screen: Screen,
  val key: String,
)

object SettingsSearchNavigation {
  private val _target = MutableStateFlow<SettingsSearchTarget?>(null)
  val target = _target.asStateFlow()

  fun open(preference: SearchablePreference) {
    _target.value = SettingsSearchTarget(preference.screen, preference.searchTargetKey)
  }

  fun clear(target: SettingsSearchTarget) {
    _target.compareAndSet(target, null)
  }
}

val SearchablePreference.searchTargetKey: String
  get() = titleRes?.let { "res:$it" } ?: "text:${title.orEmpty()}"

/** Scrolls to one concrete preference row and briefly highlights only that row. */
fun Modifier.settingsSearchTarget(
  @StringRes titleRes: Int,
): Modifier =
  composed {
    val requestedTarget by SettingsSearchNavigation.target.collectAsState()
    val requester = remember { BringIntoViewRequester() }
    var highlightVisible by remember { mutableStateOf(false) }
    val highlightColor = MaterialTheme.colorScheme.primary
    val key = "res:$titleRes"

    LaunchedEffect(requestedTarget, key) {
      val target = requestedTarget?.takeIf { it.key == key } ?: return@LaunchedEffect
      requester.bringIntoView()
      highlightVisible = true
      delay(1800)
      highlightVisible = false
      SettingsSearchNavigation.clear(target)
    }

    bringIntoViewRequester(requester)
      .drawWithContent {
        drawContent()
        if (highlightVisible) {
          drawRoundRect(
            color = highlightColor.copy(alpha = 0.14f),
            cornerRadius = CornerRadius(18f, 18f),
          )
        }
      }
  }

@Composable
fun rememberSettingsSearchList(
  @Suppress("UnusedParameter") screen: Screen,
  @Suppress("UnusedParameter") highlightColor: Color,
): Pair<LazyListState, Modifier> = rememberLazyListState() to Modifier

// Compatibility wrappers for screens without individually indexed rows.
@Composable
fun rememberSettingsSearchHighlight(
  @Suppress("UnusedParameter") screen: Screen,
  @Suppress("UnusedParameter") listState: LazyListState,
  @Suppress("UnusedParameter") highlightColor: Color,
): Modifier = Modifier

@Composable
fun rememberSettingsSearchHighlight(
  @Suppress("UnusedParameter") screen: Screen,
  @Suppress("UnusedParameter") scrollState: ScrollState,
  @Suppress("UnusedParameter") highlightColor: Color,
): Modifier = Modifier

@Composable
fun rememberSettingsSearchHighlight(
  @Suppress("UnusedParameter") screen: Screen,
  @Suppress("UnusedParameter") highlightColor: Color,
): Modifier = Modifier
