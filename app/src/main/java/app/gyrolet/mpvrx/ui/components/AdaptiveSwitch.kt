package app.gyrolet.mpvrx.ui.components

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.koin.compose.koinInject

@Composable
fun AdaptiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val preferences = koinInject<AppearancePreferences>()
    val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()
    val liquidToggleColor by preferences.liquidToggleColor.collectAsState()
    
    if (enableLiquidGlass) {
        val backdrop = rememberLayerBackdrop()
        LiquidToggle(
            selected = { checked },
            onSelect = { onCheckedChange?.invoke(it) },
            backdrop = backdrop,
            modifier = modifier,
            accentColor = Color(liquidToggleColor)
        )
    } else {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled
        )
    }
}
