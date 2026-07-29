/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.components.expressive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ElevationTokens

/**
 * Expressive card with spring-animated scale feedback on press and selection.
 */
@Composable
fun ExpressiveCard(
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  onClick: (() -> Unit)? = null,
  onLongClick: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  var isPressed by remember { mutableStateOf(false) }

  val targetScale =
    when {
      isPressed -> 0.98f
      selected -> 1.02f
      else -> 1.0f
    }

  val scale by animateFloatAsState(
    targetValue = targetScale,
    animationSpec = AppMotion.Spatial.Expressive,
    label = "ExpressiveCardScale",
  )

  val selectionColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)

  Card(
    modifier =
      modifier
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .pressable(onPress = { isPressed = true }, onRelease = { isPressed = false })
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = { onClick?.invoke() },
        ),
    colors =
      CardDefaults.cardColors(
        containerColor = if (selected) selectionColor else MaterialTheme.colorScheme.surfaceContainerLow,
      ),
    elevation =
      CardDefaults.cardElevation(
        defaultElevation = ElevationTokens.Level1,
        pressedElevation = ElevationTokens.Level2,
      ),
  ) {
    content()
  }
}

/**
 * Outlined variant of ExpressiveCard.
 */
@Composable
fun ExpressiveOutlinedCard(
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  onClick: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  var isPressed by remember { mutableStateOf(false) }

  val targetScale =
    when {
      isPressed -> 0.98f
      selected -> 1.02f
      else -> 1.0f
    }

  val scale by animateFloatAsState(
    targetValue = targetScale,
    animationSpec = AppMotion.Spatial.Expressive,
    label = "ExpressiveOutlinedCardScale",
  )

  val selectionColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)

  OutlinedCard(
    modifier =
      modifier
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .pressable(onPress = { isPressed = true }, onRelease = { isPressed = false })
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = { onClick?.invoke() },
        ),
    colors =
      CardDefaults.outlinedCardColors(
        containerColor = if (selected) selectionColor else MaterialTheme.colorScheme.surfaceContainerLow,
      ),
  ) {
    content()
  }
}

/**
 * Elevated variant of ExpressiveCard.
 */
@Composable
fun ExpressiveElevatedCard(
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  onClick: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  var isPressed by remember { mutableStateOf(false) }

  val targetScale =
    when {
      isPressed -> 0.98f
      selected -> 1.02f
      else -> 1.0f
    }

  val scale by animateFloatAsState(
    targetValue = targetScale,
    animationSpec = AppMotion.Spatial.Expressive,
    label = "ExpressiveElevatedCardScale",
  )

  val selectionColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)

  ElevatedCard(
    modifier =
      modifier
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .pressable(onPress = { isPressed = true }, onRelease = { isPressed = false })
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = { onClick?.invoke() },
        ),
    colors =
      CardDefaults.elevatedCardColors(
        containerColor = if (selected) selectionColor else MaterialTheme.colorScheme.surfaceContainer,
      ),
    elevation =
      CardDefaults.elevatedCardElevation(
        defaultElevation = ElevationTokens.Level2,
        pressedElevation = ElevationTokens.Level3,
      ),
  ) {
    content()
  }
}

private fun Modifier.pressable(
  onPress: () -> Unit,
  onRelease: () -> Unit,
): Modifier =
  this.pointerInput(Unit) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent()
        if (event.changes.any { it.pressed }) {
          onPress()
        } else {
          onRelease()
        }
      }
    }
  }
