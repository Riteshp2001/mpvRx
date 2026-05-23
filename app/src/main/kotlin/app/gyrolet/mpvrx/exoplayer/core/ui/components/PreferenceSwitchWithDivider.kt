package app.gyrolet.mpvrx.exoplayer.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.ui.icons.AppIcon

@Composable
fun PreferenceSwitchWithDivider(
    title: String = "",
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier,
    description: String? = null,
    icon: AppIcon? = null,
    isEnabled: Boolean = true,
    isChecked: Boolean = true,
    onClick: (() -> Unit) = {},
    onChecked: () -> Unit = {},
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
) {
    PreferenceItem(
        modifier = modifier,
        title = title,
        description = description,
        icon = icon,
        onClick = onClick,
        isEnabled = isEnabled,
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VerticalDivider(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .height(40.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
                NextSwitch(
                    modifier = switchModifier,
                    isChecked = isChecked,
                    onCheckedChange = { onChecked() },
                    isEnabled = isEnabled,
                )
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreferenceCheckbox(
    title: String,
    description: String? = null,
    icon: AppIcon? = null,
    isEnabled: Boolean = true,
    isChecked: Boolean = true,
    onClick: (() -> Unit) = {},
    onLongClick: (() -> Unit) = {},
) {
    PreferenceItem(
        title = title,
        description = description,
        icon = icon,
        modifier = Modifier
            .toggleable(
                value = isChecked,
                enabled = isEnabled,
                onValueChange = { onClick() },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        isEnabled = isEnabled,
        trailingContent = {
            Checkbox(
                checked = isChecked,
                onCheckedChange = null,
            )
        },
    )
}
