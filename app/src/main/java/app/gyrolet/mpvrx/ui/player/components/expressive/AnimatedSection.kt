package app.gyrolet.mpvrx.ui.player.components.expressive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion

/**
 * Expandable section card with spring-animated icon rotation.
 */
@Composable
fun AnimatedSection(
  title: String,
  modifier: Modifier = Modifier,
  initiallyExpanded: Boolean = false,
  headerContent: @Composable ((isExpanded: Boolean) -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  var isExpanded by remember { mutableStateOf(initiallyExpanded) }

  val iconRotation by animateFloatAsState(
    targetValue = if (isExpanded) 180f else 0f,
    animationSpec = AppMotion.Spatial.Expressive,
    label = "AnimatedSectionIconRotation",
  )

  Column(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { isExpanded = !isExpanded }
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (headerContent != null) {
        headerContent(isExpanded)
      } else {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
          modifier = Modifier.weight(1f),
        )
      }
      Icon(
        imageVector = Icons.RoundedFilled.KeyboardArrowDown,
        contentDescription = if (isExpanded) "Collapse" else "Expand",
        modifier = Modifier.rotate(iconRotation),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically(
        animationSpec = AppMotion.spatial(
          AppMotion.IntSizeSpring,
          AppMotion.ReducedIntSize,
        ),
      ) + fadeIn(animationSpec = AppMotion.Effect.Alpha),
      exit = shrinkVertically(
        animationSpec = AppMotion.spatial(
          AppMotion.IntSizeSpring,
          AppMotion.ReducedIntSize,
        ),
      ) + fadeOut(animationSpec = AppMotion.Effect.Alpha),
    ) {
      Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        content()
      }
    }
  }
}

/**
 * Stateful variant that manages expand state externally.
 */
@Composable
fun AnimatedSection(
  title: String,
  isExpanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val iconRotation by animateFloatAsState(
    targetValue = if (isExpanded) 180f else 0f,
    animationSpec = AppMotion.Spatial.Expressive,
    label = "AnimatedSectionStatefulIconRotation",
  )

  Column(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onExpandedChange(!isExpanded) }
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      Icon(
        imageVector = Icons.RoundedFilled.KeyboardArrowDown,
        contentDescription = if (isExpanded) "Collapse" else "Expand",
        modifier = Modifier.rotate(iconRotation),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically(
        animationSpec = AppMotion.spatial(
          AppMotion.IntSizeSpring,
          AppMotion.ReducedIntSize,
        ),
      ) + fadeIn(animationSpec = AppMotion.Effect.Alpha),
      exit = shrinkVertically(
        animationSpec = AppMotion.spatial(
          AppMotion.IntSizeSpring,
          AppMotion.ReducedIntSize,
        ),
      ) + fadeOut(animationSpec = AppMotion.Effect.Alpha),
    ) {
      Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        content()
      }
    }
  }
}
