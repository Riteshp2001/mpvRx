package app.gyrolet.mpvrx.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R

// Roboto Flex font family (variable font supporting weights 100-900)
val RobotoFlex = FontFamily(
  Font(
    resId = R.font.roboto_flex,
    weight = FontWeight.Thin,
    style = FontStyle.Normal,
  ),
  Font(
    resId = R.font.roboto_flex,
    weight = FontWeight.ExtraLight,
    style = FontStyle.Normal,
  ),
  Font(
    resId = R.font.roboto_flex,
    weight = FontWeight.Light,
    style = FontStyle.Normal,
  ),
  Font(
    resId = R.font.roboto_flex,
    weight = FontWeight.Normal,
    style = FontStyle.Normal,
  ),
  Font(
    resId = R.font.roboto_flex,
    weight = FontWeight.Medium,
    style = FontStyle.Normal,
  ),
  Font(
    resId = R.font.roboto_flex,
    weight = FontWeight.SemiBold,
    style = FontStyle.Normal,
  ),
  Font(
    resId = R.font.roboto_flex,
    weight = FontWeight.Bold,
    style = FontStyle.Normal,
  ),
  Font(
    resId = R.font.roboto_flex,
    weight = FontWeight.ExtraBold,
    style = FontStyle.Normal,
  ),
  Font(
    resId = R.font.roboto_flex,
    weight = FontWeight.Black,
    style = FontStyle.Normal,
  ),
)

val SystemTypography = Typography()

// Use Roboto Flex typography app-wide
val AppTypography = SystemTypography.run {
  copy(
    displayLarge = displayLarge.copy(fontFamily = RobotoFlex),
    displayMedium = displayMedium.copy(fontFamily = RobotoFlex),
    displaySmall = displaySmall.copy(fontFamily = RobotoFlex),
    headlineLarge = headlineLarge.copy(fontFamily = RobotoFlex),
    headlineMedium = headlineMedium.copy(fontFamily = RobotoFlex),
    headlineSmall = headlineSmall.copy(fontFamily = RobotoFlex),
    titleLarge = titleLarge.copy(fontFamily = RobotoFlex),
    titleMedium = titleMedium.copy(fontFamily = RobotoFlex),
    titleSmall = titleSmall.copy(fontFamily = RobotoFlex),
    bodyLarge = bodyLarge.copy(fontFamily = RobotoFlex),
    bodyMedium = bodyMedium.copy(fontFamily = RobotoFlex),
    bodySmall = bodySmall.copy(fontFamily = RobotoFlex),
    labelLarge = labelLarge.copy(fontFamily = RobotoFlex),
    labelMedium = labelMedium.copy(fontFamily = RobotoFlex),
    labelSmall = labelSmall.copy(fontFamily = RobotoFlex),
  )
}

