package app.gyrolet.mpvrx.exoplayer.feature.player.ui.controls

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.exoplayer.core.ui.extensions.copy
import app.gyrolet.mpvrx.ui.icons.Icon

@Composable
fun ControlsTopModernView(
    modifier: Modifier = Modifier,
    title: String,
    onBackClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    val systemBarsPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(systemBarsPadding.copy(bottom = 0.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            modifier = Modifier.testTag("btn_back"),
            onClick = onBackClick,
        ) {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = NextIcons.ArrowBack,
                contentDescription = stringResource(R.string.navigate_up),
                tint = Color.White,
            )
        }
        Text(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            modifier = Modifier.testTag("btn_menu"),
            onClick = onMenuClick,
        ) {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = NextIcons.Menu,
                contentDescription = stringResource(R.string.menu),
                tint = Color.White,
            )
        }
    }
}
