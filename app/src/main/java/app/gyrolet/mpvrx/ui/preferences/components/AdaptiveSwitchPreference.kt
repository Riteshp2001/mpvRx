package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject

@Composable
fun AdaptiveSwitchPreference(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    summary: (@Composable () -> Unit)? = null,
) {
    val preferences = koinInject<AppearancePreferences>()
    val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()
    val liquidToggleColor by preferences.liquidToggleColor.collectAsState()

    if (enableLiquidGlass) {
        LiquidSwitchPreference(
            value = value,
            onValueChange = onValueChange,
            title = title,
            modifier = modifier,
            enabled = enabled,
            summary = summary,
            accentColor = Color(liquidToggleColor)
        )
    } else {
        SwitchPreference(
            value = value,
            onValueChange = onValueChange,
            title = title,
            modifier = modifier,
            enabled = enabled,
            summary = summary
        )
    }
}
