/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import androidx.compose.material3.Icon as MaterialIcon

@Immutable
class AppIcon(
  private val ltrImageVector: ImageVector? = null,
  private val rtlImageVector: ImageVector? = null,
  val mirrorInRtl: Boolean = false,
  @DrawableRes val drawableRes: Int? = null,
) {
  constructor(imageVector: ImageVector, rtlImageVector: ImageVector? = null, mirrorInRtl: Boolean = false) :
    this(ltrImageVector = imageVector, rtlImageVector = rtlImageVector, mirrorInRtl = mirrorInRtl, drawableRes = null)

  constructor(
    @DrawableRes drawableRes: Int,
  ) :
    this(ltrImageVector = null, rtlImageVector = null, mirrorInRtl = false, drawableRes = drawableRes)

  internal fun resolve(isRtl: Boolean): ImageVector? = if (isRtl) rtlImageVector ?: ltrImageVector else ltrImageVector

  internal fun hasExplicitRtlSource(): Boolean = rtlImageVector != null
}

@Composable
fun Icon(
  imageVector: AppIcon,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  tint: Color = LocalContentColor.current,
) {
  val drawableRes = imageVector.drawableRes
  if (drawableRes != null) {
    MaterialIcon(
      painter =
        androidx.compose.ui.res
          .painterResource(drawableRes),
      contentDescription = contentDescription,
      modifier = modifier.iconShadow(LocalIconShadow.current),
      tint = tint,
    )
  } else {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mirroredModifier =
      if (isRtl && !imageVector.hasExplicitRtlSource() && imageVector.mirrorInRtl) {
        modifier.scale(scaleX = -1f, scaleY = 1f)
      } else {
        modifier
      }

    MaterialIcon(
      imageVector = imageVector.resolve(isRtl)!!,
      contentDescription = contentDescription,
      modifier = mirroredModifier.iconShadow(LocalIconShadow.current),
      tint = tint,
    )
  }
}

/** Only the glyph changes; accessibility always describes the current action, not the outgoing one. */
@Composable
fun AnimatedIcon(
  imageVector: AppIcon,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  tint: Color = LocalContentColor.current,
  reduceMotion: Boolean = AppMotion.shouldReduceMotion(),
) {
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val animatedTint by animateColorAsState(
    targetValue = tint,
    animationSpec = if (reduceMotion) snap() else AppMotion.Effect.Color,
    label = "appActionIconTint",
  )
  AnimatedContent(
    targetState = imageVector,
    contentKey = { it.drawableRes ?: it.resolve(isRtl) },
    modifier =
      modifier.size(24.dp).clearAndSetSemantics {
        if (contentDescription != null) this.contentDescription = contentDescription
      },
    transitionSpec = { AppMotion.iconChange(reduceMotion).using(null) },
    contentAlignment = Alignment.Center,
    label = "appActionIcon",
  ) { shown ->
    Icon(shown, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = animatedTint)
  }
}
