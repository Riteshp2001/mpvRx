package app.gyrolet.mpvrx.exoplayer.core.ui.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R

@Composable
fun DoneButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
) {
    TextButton(
        enabled = isEnabled,
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(text = stringResource(R.string.generic_confirm))
    }
}

@Composable
fun CancelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
) {
    TextButton(
        enabled = isEnabled,
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(text = stringResource(R.string.generic_cancel))
    }
}
