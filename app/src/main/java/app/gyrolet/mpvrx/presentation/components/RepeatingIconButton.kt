package app.gyrolet.mpvrx.presentation.components

import android.view.MotionEvent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.components.LiquidButton

import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RepeatingIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  maxDelayMillis: Long = 300,
  minDelayMillis: Long = 20,
  delayDecayFactor: Float = .25f,
  content: @Composable () -> Unit,
) {
  val currentClickListener by rememberUpdatedState(onClick)
  var pressed by remember { mutableStateOf(false) }

  val preferences = koinInject<AppearancePreferences>()
  val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()

  if (enableLiquidGlass) {
    val backdrop = rememberLayerBackdrop()
    LiquidButton(
      onClick = currentClickListener,
      backdrop = backdrop,
      modifier = modifier.pointerInteropFilter {
        pressed =
          when (it.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
            else -> pressed
          }
        true
      },
      isInteractive = true,
      height = 40.dp,
      horizontalPadding = 0.dp,
      content = { content() },
    )

    LaunchedEffect(pressed, enabled) {
      var currentDelayMillis = maxDelayMillis
      while (enabled && pressed) {
        currentClickListener()
        delay(currentDelayMillis)
        currentDelayMillis =
          (currentDelayMillis - (currentDelayMillis * delayDecayFactor))
            .toLong()
            .coerceAtLeast(minDelayMillis)
      }
    }
  } else {
    FilledTonalIconButton(
      modifier =
        modifier.pointerInteropFilter {
          pressed =
            when (it.action) {
              MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> true
              MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
              else -> pressed
            }

          true
        },
      onClick = {},
      enabled = enabled,
      interactionSource = interactionSource,
      content = content,
    )

    LaunchedEffect(pressed, enabled) {
      var currentDelayMillis = maxDelayMillis

      while (enabled && pressed) {
        currentClickListener()
        delay(currentDelayMillis)
        currentDelayMillis =
          (currentDelayMillis - (currentDelayMillis * delayDecayFactor))
            .toLong()
            .coerceAtLeast(minDelayMillis)
      }
    }
  }
}

