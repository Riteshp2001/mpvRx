package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.components.LiquidToggle
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
fun LiquidAdaptiveSwitchPreference(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    summary: (@Composable () -> Unit)? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    val backdrop = rememberLayerBackdrop()
    
    ListItem(
        headlineContent = title,
        supportingContent = summary,
        leadingContent = icon,
        trailingContent = {
            Box(contentAlignment = Alignment.Center) {
                LiquidToggle(
                    selected = { value },
                    onSelect = onValueChange,
                    backdrop = backdrop,
                    accentColor = accentColor
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onValueChange(!value) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
