package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.exoplayer.core.ui.extensions.withBottomFallback
import app.gyrolet.mpvrx.presentation.components.PlayerSheet

@Composable
fun BoxScope.OverlayView(
    modifier: Modifier = Modifier,
    shouldShow: Boolean,
    title: String,
    testTag: String? = null,
    contentPadding: PaddingValues = PaddingValues(),
    onDismissRequest: () -> Unit = {}, // Add this to allow dismissal
    content: @Composable ColumnScope.() -> Unit,
) {
    if (shouldShow) {
        val resolvedContentPadding = contentPadding.withBottomFallback()
        val layoutDirection = LocalLayoutDirection.current
        val endPadding = WindowInsets.safeDrawing
            .asPaddingValues()
            .calculateEndPadding(layoutDirection)

        PlayerSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier
                .then(
                    if (testTag != null) {
                        Modifier
                            .testTag(testTag)
                            .semantics { contentDescription = testTag }
                    } else Modifier
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(resolvedContentPadding)
                    .padding(top = 24.dp)
                    .padding(end = endPadding),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.size(8.dp))
                content()
            }
        }
    }
}
