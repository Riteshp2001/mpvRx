package app.gyrolet.mpvrx.ui.icons

import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

@Immutable
class AppIcon(
  private val ltrImageVector: ImageVector,
  private val rtlImageVector: ImageVector? = null,
  val mirrorInRtl: Boolean = false,
) {
  internal fun resolve(isRtl: Boolean): ImageVector =
    if (isRtl) rtlImageVector ?: ltrImageVector else ltrImageVector

  internal fun hasExplicitRtlSource(): Boolean = rtlImageVector != null
}

@Composable
fun Icon(
  imageVector: AppIcon,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  tint: Color = LocalContentColor.current,
) {
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val mirroredModifier =
    if (isRtl && !imageVector.hasExplicitRtlSource() && imageVector.mirrorInRtl) {
      modifier.scale(scaleX = -1f, scaleY = 1f)
    } else {
      modifier
    }

  MaterialIcon(
    imageVector = imageVector.resolve(isRtl),
    contentDescription = contentDescription,
    modifier = mirroredModifier,
    tint = tint,
  )
}

