package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.zhanghai.compose.preference.SwitchPreference

@Composable
fun AdaptiveSwitchPreference(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    summary: (@Composable () -> Unit)? = null,
) {
    SwitchPreference(
        value = value,
        onValueChange = onValueChange,
        title = title,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        summary = summary
    )
}
