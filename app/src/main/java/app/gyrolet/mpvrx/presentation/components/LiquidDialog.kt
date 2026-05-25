package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.controls.components.PlayerLiquidTokens
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = AlertDialogDefaults.containerColor,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null
) {
    val preferences = koinInject<AppearancePreferences>()
    val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()
    val liquidBlur by preferences.liquidDialogBlur.collectAsState()
    val liquidSaturation by preferences.liquidDialogSaturation.collectAsState()
    val liquidBrightness by preferences.liquidDialogBrightness.collectAsState()
    val liquidLensRadius by preferences.liquidDialogLensRadius.collectAsState()
    val liquidLensDepth by preferences.liquidDialogLensDepth.collectAsState()
    val liquidAlpha by preferences.liquidDialogContainerAlpha.collectAsState()
    val liquidDarkText by preferences.liquidDialogDarkText.collectAsState()
    val density = LocalDensity.current

    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .then(
                    if (enableLiquidGlass) {
                        Modifier.drawBackdrop(
                            backdrop = rememberLayerBackdrop(),
                            shape = { shape },
                            effects = {
                                colorControls(
                                    brightness = liquidBrightness,
                                    saturation = liquidSaturation
                                )
                                blur(with(density) { liquidBlur.dp.toPx() })
                                lens(
                                    with(density) { liquidLensRadius.dp.toPx() },
                                    with(density) { liquidLensDepth.dp.toPx() },
                                    depthEffect = true
                                )
                            },
                            highlight = { Highlight.Plain },
                            onDrawSurface = {
                                drawRect(containerColor.copy(alpha = liquidAlpha))
                            }
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = shape,
            color = if (enableLiquidGlass) Color.Transparent else containerColor,
            tonalElevation = if (enableLiquidGlass) 0.dp else AlertDialogDefaults.TonalElevation
        ) {
            val dialogContentColor = if (enableLiquidGlass) {
                if (liquidDarkText) Color.Black else PlayerLiquidTokens.contentColor
            } else {
                AlertDialogDefaults.textContentColor
            }
            if (content != null) {
                CompositionLocalProvider(LocalContentColor provides dialogContentColor) {
                    content()
                }
            } else {
                CompositionLocalProvider(LocalContentColor provides dialogContentColor) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (title != null) {
                            CompositionLocalProvider(
                                LocalContentColor provides if (enableLiquidGlass) {
                                    if (liquidDarkText) Color.Black else PlayerLiquidTokens.contentColor
                                } else {
                                    AlertDialogDefaults.titleContentColor
                                }
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                    Box(Modifier.fillMaxWidth()) {
                                        title()
                                    }
                                }
                            }
                        }

                        if (text != null) {
                            CompositionLocalProvider(
                                LocalContentColor provides dialogContentColor
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                    Box(Modifier.fillMaxWidth()) {
                                        text()
                                    }
                                }
                            }
                        }

                        if (confirmButton != null || dismissButton != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (dismissButton != null) {
                                    dismissButton()
                                }
                                if (confirmButton != null) {
                                    confirmButton()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Box(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) {
        content()
    }
}
