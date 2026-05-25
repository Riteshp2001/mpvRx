package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.ui.components.LiquidToggle
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import app.gyrolet.mpvrx.ui.preferences.components.AdaptiveSwitchPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Serializable
object LiquidSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val preferences = koinInject<AppearancePreferences>()
        val backstack = LocalBackStack.current
        val screenBackdrop = rememberLayerBackdrop()
        val enableLiquidGlassCurrent by preferences.enableLiquidGlass.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Liquid Glass Effects",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { backstack.popSafely() }) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            ProvidePreferenceLocals {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                item {
                    PreferenceSectionHeader(title = "General")
                }

                item {
                    PreferenceCard {
                        val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()
                        AdaptiveSwitchPreference(
                            value = enableLiquidGlass,
                            onValueChange = { enabled ->
                                if (enabled &&
                                    preferences.liquidButtonBlur.get() <= 0f &&
                                    preferences.liquidButtonLensRadius.get() <= 0f &&
                                    preferences.liquidButtonLensDepth.get() <= 0f
                                ) {
                                    preferences.liquidButtonBlur.set(26f)
                                    preferences.liquidButtonLensRadius.set(42f)
                                    preferences.liquidButtonLensDepth.set(72f)
                                    preferences.liquidDialogBlur.set(32f)
                                    preferences.liquidDialogSaturation.set(1.3f)
                                    preferences.liquidDialogBrightness.set(0.08f)
                                    preferences.liquidDialogLensRadius.set(55f)
                                    preferences.liquidDialogLensDepth.set(85f)
                                    preferences.liquidDialogContainerAlpha.set(0.35f)
                                }
                                preferences.enableLiquidGlass.set(enabled)
                            },
                            title = { Text(text = stringResource(id = R.string.pref_anim_liquid_glass_title)) },
                            summary = {
                                Text(
                                    text = stringResource(id = R.string.pref_anim_liquid_glass_summary),
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        )
                    }
                }

                if (enableLiquidGlassCurrent) {
                    item {
                        PreferenceSectionHeader(title = "Appearance & Accents")
                    }

                    item {
                        val liquidToggleColor by preferences.liquidToggleColor.collectAsState()
                        val toggleColorIsPreset = TogglePresets.any { it.color == liquidToggleColor }
                        val isToggleCustomActive = !toggleColorIsPreset

                        PreferenceCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Toggle Button Accent",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "Accent glow for liquid toggle floating actions",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                color = Color(liquidToggleColor),
                                                shape = CircleShape
                                            )
                                            .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    var previewSelected by remember { mutableStateOf(true) }
                                    LiquidToggle(
                                        selected = { previewSelected },
                                        onSelect = { previewSelected = it },
                                        backdrop = screenBackdrop,
                                        accentColor = Color(liquidToggleColor)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TogglePresets.forEach { preset ->
                                        PremiumColorChip(
                                            name = preset.name,
                                            presetColor = Color(preset.color),
                                            selected = liquidToggleColor == preset.color,
                                            onClick = { preferences.liquidToggleColor.set(preset.color) }
                                        )
                                    }
                                    CustomColorChip(
                                        selected = isToggleCustomActive,
                                        onClick = {
                                            if (!isToggleCustomActive) {
                                                preferences.liquidToggleColor.set(0xFF536DFE.toInt())
                                            }
                                        }
                                    )
                                }

                                AnimatedVisibility(visible = isToggleCustomActive) {
                                    Column {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        PremiumColorPicker(
                                            color = liquidToggleColor,
                                            onColorChange = { preferences.liquidToggleColor.set(it) },
                                            badgeColor = Color(liquidToggleColor)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        val liquidSeekbarColor by preferences.liquidSeekbarColor.collectAsState()
                        val seekbarColorIsPreset = SeekbarPresets.any { it.color == liquidSeekbarColor }
                        val isSeekbarCustomActive = !seekbarColorIsPreset

                        PreferenceCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Seekbar Accent Color",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "Accent glow for premium player seek bar lines",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                color = Color(liquidSeekbarColor),
                                                shape = CircleShape
                                            )
                                            .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.9f)
                                            .height(6.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(3.dp)
                                            ),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.65f)
                                                .height(6.dp)
                                                .background(
                                                    brush = Brush.horizontalGradient(
                                                        listOf(Color(liquidSeekbarColor), Color(liquidSeekbarColor).copy(alpha = 0.6f))
                                                    ),
                                                    shape = RoundedCornerShape(3.dp)
                                                )
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .background(
                                                    color = Color(liquidSeekbarColor),
                                                    shape = CircleShape
                                                )
                                                .border(2.dp, Color.White, CircleShape)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SeekbarPresets.forEach { preset ->
                                        PremiumColorChip(
                                            name = preset.name,
                                            presetColor = Color(preset.color),
                                            selected = liquidSeekbarColor == preset.color,
                                            onClick = { preferences.liquidSeekbarColor.set(preset.color) }
                                        )
                                    }
                                    CustomColorChip(
                                        selected = isSeekbarCustomActive,
                                        onClick = {
                                            if (!isSeekbarCustomActive) {
                                                preferences.liquidSeekbarColor.set(0xFFFF4500.toInt())
                                            }
                                        }
                                    )
                                }

                                AnimatedVisibility(visible = isSeekbarCustomActive) {
                                    Column {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        PremiumColorPicker(
                                            color = liquidSeekbarColor,
                                            onColorChange = { preferences.liquidSeekbarColor.set(it) },
                                            badgeColor = Color(liquidSeekbarColor)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        PreferenceSectionHeader(title = "Buttons Parameters")
                    }

                    item {
                        val liquidBlur by preferences.liquidButtonBlur.collectAsState()
                        val liquidLensRadius by preferences.liquidButtonLensRadius.collectAsState()
                        val liquidLensDepth by preferences.liquidButtonLensDepth.collectAsState()

                        PreferenceCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Liquid Button Parameters",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "Customize physical refraction and lens distortion settings",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                PremiumParameterSlider(
                                    value = liquidBlur,
                                    onValueChange = { preferences.liquidButtonBlur.set(it) },
                                    valueRange = 0f..64f,
                                    title = "Blur Intensity",
                                    summary = "Softness and glow propagation of the backdrop glass",
                                    icon = Icons.Default.BlurOn,
                                    accentColor = MaterialTheme.colorScheme.primary
                                )

                                PremiumParameterSlider(
                                    value = liquidLensRadius,
                                    onValueChange = { preferences.liquidButtonLensRadius.set(it) },
                                    valueRange = 0f..100f,
                                    title = "Lens Radius",
                                    summary = "Horizontal scale of the physical chromatic lens",
                                    icon = Icons.Default.AspectRatio,
                                    accentColor = MaterialTheme.colorScheme.secondary
                                )

                                PremiumParameterSlider(
                                    value = liquidLensDepth,
                                    onValueChange = { preferences.liquidButtonLensDepth.set(it) },
                                    valueRange = 0f..200f,
                                    title = "Lens Depth",
                                    summary = "Chromatic aberration offset and optical refraction index",
                                    icon = Icons.Default.BlurOff,
                                    accentColor = MaterialTheme.colorScheme.tertiary
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            preferences.liquidButtonBlur.set(26f)
                                            preferences.liquidButtonLensRadius.set(42f)
                                            preferences.liquidButtonLensDepth.set(72f)
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Reset Parameters")
                                    }
                                }
                            }
                        }
                    }

                    item {
                        PreferenceSectionHeader(title = "Dialog & Sheet Parameters")
                    }

                    item {
                        val liquidBlur by preferences.liquidDialogBlur.collectAsState()
                        val liquidSaturation by preferences.liquidDialogSaturation.collectAsState()
                        val liquidBrightness by preferences.liquidDialogBrightness.collectAsState()
                        val liquidLensRadius by preferences.liquidDialogLensRadius.collectAsState()
                        val liquidLensDepth by preferences.liquidDialogLensDepth.collectAsState()
                        val liquidAlpha by preferences.liquidDialogContainerAlpha.collectAsState()
                        var showPreview by remember { mutableStateOf(false) }

                        if (showPreview) {
                            ConfirmDialog(
                                title = "Liquid Dialog Preview",
                                subtitle = "This is a mockup of how your dialogs and sheets will look with the current liquid glass settings.",
                                onConfirm = { showPreview = false },
                                onCancel = { showPreview = false }
                            )
                        }

                        PreferenceCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Liquid Dialog & Sheet Parameters",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "Tweak optical effects, saturation, opacity, and chromatic aberration",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = { showPreview = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Show Live Preview")
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                PremiumParameterSlider(
                                    value = liquidBlur,
                                    onValueChange = { preferences.liquidDialogBlur.set(it) },
                                    valueRange = 0f..64f,
                                    title = "Blur Intensity",
                                    summary = "Overall backdrop blur diffusion radius",
                                    icon = Icons.Default.BlurOn,
                                    accentColor = MaterialTheme.colorScheme.primary
                                )

                                PremiumParameterSlider(
                                    value = liquidSaturation,
                                    onValueChange = { preferences.liquidDialogSaturation.set(it) },
                                    valueRange = 0f..3f,
                                    title = "Backdrop Saturation",
                                    summary = "Vibrancy and depth multiplier of underlying content",
                                    icon = Icons.Default.AutoAwesome,
                                    accentColor = MaterialTheme.colorScheme.secondary
                                )

                                PremiumParameterSlider(
                                    value = liquidBrightness,
                                    onValueChange = { preferences.liquidDialogBrightness.set(it) },
                                    valueRange = -1f..1f,
                                    title = "Brightness Offset",
                                    summary = "Exposure boost or dimming overlay for readability",
                                    icon = Icons.Default.BrightnessMedium,
                                    accentColor = MaterialTheme.colorScheme.tertiary
                                )

                                PremiumParameterSlider(
                                    value = liquidLensRadius,
                                    onValueChange = { preferences.liquidDialogLensRadius.set(it) },
                                    valueRange = 0f..100f,
                                    title = "Lens Radius",
                                    summary = "Glass curvature diameter for the dialog pane",
                                    icon = Icons.Default.AspectRatio,
                                    accentColor = MaterialTheme.colorScheme.primary
                                )

                                PremiumParameterSlider(
                                    value = liquidLensDepth,
                                    onValueChange = { preferences.liquidDialogLensDepth.set(it) },
                                    valueRange = 0f..200f,
                                    title = "Lens Depth",
                                    summary = "Chromatic edge scattering and refraction power",
                                    icon = Icons.Default.BlurOff,
                                    accentColor = MaterialTheme.colorScheme.secondary
                                )

                                PremiumParameterSlider(
                                    value = liquidAlpha,
                                    onValueChange = { preferences.liquidDialogContainerAlpha.set(it) },
                                    valueRange = 0f..1f,
                                    title = "Base Container Alpha",
                                    summary = "Base translucency of the dialog backing plate",
                                    icon = Icons.Default.Opacity,
                                    accentColor = MaterialTheme.colorScheme.tertiary
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            preferences.liquidDialogBlur.set(32f)
                                            preferences.liquidDialogSaturation.set(1.3f)
                                            preferences.liquidDialogBrightness.set(0.08f)
                                            preferences.liquidDialogLensRadius.set(55f)
                                            preferences.liquidDialogLensDepth.set(85f)
                                            preferences.liquidDialogContainerAlpha.set(0.35f)
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Reset Parameters")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class ColorPreset(
    val name: String,
    val color: Int
)

val TogglePresets = listOf(
    ColorPreset("Royal Indigo", 0xFF536DFE.toInt()),
    ColorPreset("Teal Dream", 0xFF00BFA5.toInt()),
    ColorPreset("Amber Flare", 0xFFFFAB00.toInt()),
    ColorPreset("Sunset Rose", 0xFFFF4081.toInt()),
    ColorPreset("Emerald Glow", 0xFF00E676.toInt()),
    ColorPreset("Navy Blue", 0xFF000080.toInt()),
)

val SeekbarPresets = listOf(
    ColorPreset("Chili Orange", 0xFFFF4500.toInt()),
    ColorPreset("Ocean Breeze", 0xFF00B0FF.toInt()),
    ColorPreset("Teal Dream", 0xFF00BFA5.toInt()),
    ColorPreset("Sunset Rose", 0xFFFF4081.toInt()),
    ColorPreset("Emerald Glow", 0xFF00E676.toInt()),
    ColorPreset("Deep Violet", 0xFF7C4DFF.toInt()),
)

@Composable
fun PremiumColorChip(
    name: String,
    presetColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "chipScale"
    )

    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = 2.dp,
                brush = Brush.linearGradient(
                    colors = if (selected) {
                        listOf(presetColor, presetColor.copy(alpha = 0.5f))
                    } else {
                        listOf(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (selected) 0.3f else 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .width(100.dp)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(presetColor, presetColor.copy(alpha = 0.6f))
                        ),
                        shape = CircleShape
                    )
                    .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CustomColorChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "chipScale"
    )
    val rainbowColors = listOf(
        Color(0xFFFF0000), Color(0xFFFF7F00), Color(0xFFFFFF00),
        Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFF4B0082), Color(0xFF9400D3)
    )
    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = 2.dp,
                brush = Brush.sweepGradient(rainbowColors),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (selected) 0.3f else 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .width(100.dp)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        brush = Brush.sweepGradient(rainbowColors),
                        shape = CircleShape
                    )
                    .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = "Custom Color",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = "Custom",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PremiumGradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    title: String,
    gradientColors: List<Color>,
    badgeColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .background(
                        color = badgeColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = value.roundToInt().toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(
                        brush = Brush.horizontalGradient(gradientColors),
                        shape = RoundedCornerShape(4.dp)
                    )
            )
            
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = badgeColor,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
fun PremiumParameterSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    title: String,
    summary: String,
    icon: AppIcon,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = accentColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        activeTrackColor = accentColor,
                        thumbColor = accentColor
                    )
                )
            }
        }
    }
}

@Composable
fun PremiumColorPicker(
    color: Int,
    onColorChange: (Int) -> Unit,
    badgeColor: Color
) {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val a = (color shr 24) and 0xFF

    fun updateColor(nr: Int = r, ng: Int = g, nb: Int = b, na: Int = a) {
        onColorChange((na shl 24) or (nr shl 16) or (ng shl 8) or nb)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PremiumGradientSlider(
            value = r.toFloat(),
            onValueChange = { updateColor(nr = it.roundToInt()) },
            valueRange = 0f..255f,
            title = "Red Channel",
            gradientColors = listOf(Color(0xFF000000), Color(0xFFFF0000)),
            badgeColor = Color(0xFFFF3B30)
        )
        PremiumGradientSlider(
            value = g.toFloat(),
            onValueChange = { updateColor(ng = it.roundToInt()) },
            valueRange = 0f..255f,
            title = "Green Channel",
            gradientColors = listOf(Color(0xFF000000), Color(0xFF00FF00)),
            badgeColor = Color(0xFF34C759)
        )
        PremiumGradientSlider(
            value = b.toFloat(),
            onValueChange = { updateColor(nb = it.roundToInt()) },
            valueRange = 0f..255f,
            title = "Blue Channel",
            gradientColors = listOf(Color(0xFF000000), Color(0xFF0000FF)),
            badgeColor = Color(0xFF007AFF)
        )
        PremiumGradientSlider(
            value = a.toFloat(),
            onValueChange = { updateColor(na = it.roundToInt()) },
            valueRange = 0f..255f,
            title = "Alpha Transparency",
            gradientColors = listOf(Color(0x00FFFFFF), Color(0xFFFFFFFF)),
            badgeColor = badgeColor
        )
    }
}

@Composable
fun PreferenceSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(top = 8.dp)
    )
}

@Composable
fun PreferenceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        content = content
    )
}
}
