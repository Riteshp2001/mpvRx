package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.background.AnimatedBackground
import app.gyrolet.mpvrx.ui.background.BackdropDrawingState
import app.gyrolet.mpvrx.ui.background.BackdropGate
import app.gyrolet.mpvrx.ui.background.BackgroundType
import app.gyrolet.mpvrx.ui.background.RandomBackground
import app.gyrolet.mpvrx.ui.background.RandomInterval
import app.gyrolet.mpvrx.ui.background.drawBackdrop
import app.gyrolet.mpvrx.ui.background.rememberActiveBackground
import app.gyrolet.mpvrx.ui.background.rememberBackdropPalette
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.appPressFeedback
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.roundedfilled.Ac_unit
import com.composables.icons.materialsymbols.roundedfilled.Auto_awesome
import com.composables.icons.materialsymbols.roundedfilled.Bubble_chart
import com.composables.icons.materialsymbols.roundedfilled.Calendar_today
import com.composables.icons.materialsymbols.roundedfilled.Celebration
import com.composables.icons.materialsymbols.roundedfilled.Check
import com.composables.icons.materialsymbols.roundedfilled.Circle
import com.composables.icons.materialsymbols.roundedfilled.Flare
import com.composables.icons.materialsymbols.roundedfilled.Gradient
import com.composables.icons.materialsymbols.roundedfilled.Grain
import com.composables.icons.materialsymbols.roundedfilled.Grid_view
import com.composables.icons.materialsymbols.roundedfilled.Hub
import com.composables.icons.materialsymbols.roundedfilled.Image
import com.composables.icons.materialsymbols.roundedfilled.Lens_blur
import com.composables.icons.materialsymbols.roundedfilled.Local_florist
import com.composables.icons.materialsymbols.roundedfilled.Pentagon
import com.composables.icons.materialsymbols.roundedfilled.Play_circle
import com.composables.icons.materialsymbols.roundedfilled.Radio_button_unchecked
import com.composables.icons.materialsymbols.roundedfilled.Rainy
import com.composables.icons.materialsymbols.roundedfilled.Schedule
import com.composables.icons.materialsymbols.roundedfilled.Screen_rotation
import com.composables.icons.materialsymbols.roundedfilled.Shuffle
import com.composables.icons.materialsymbols.roundedfilled.Visibility_off
import com.composables.icons.materialsymbols.roundedfilled.Water_drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

private val GeometricBackgrounds =
  listOf(
    BackgroundType.CIRCLES,
    BackgroundType.RINGS,
    BackgroundType.SHAPES,
    BackgroundType.GRID,
    BackgroundType.LAVA,
    BackgroundType.BOKEH,
  )
private val NatureBackgrounds =
  listOf(
    BackgroundType.SNOW,
    BackgroundType.RAIN,
    BackgroundType.SAKURA,
    BackgroundType.FIREFLIES,
    BackgroundType.BUBBLES,
  )
private val SkyBackgrounds =
  listOf(
    BackgroundType.SPACE,
    BackgroundType.AURORA,
    BackgroundType.PARTICLES,
    BackgroundType.CONSTELLATION,
  )

/** One live preview, static illustrated choices, and the same connected controls as Settings. */
@Serializable
object BackgroundAnimationPreferencesScreen : ScrollableSettingsScreen {
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val prefs = koinInject<AppearancePreferences>()
    val picked by prefs.backgroundType.collectAsState()
    val interval by prefs.randomBackgroundInterval.collectAsState()
    val parallax by prefs.backgroundParallax.collectAsState()
    val overWallpaper by prefs.backgroundOverWallpaper.collectAsState()
    val seasonal by prefs.seasonalThemes.collectAsState()
    val active = rememberActiveBackground()
    val (listState, searchHighlight) =
      rememberSettingsSearchList(BackgroundAnimationPreferencesScreen, MaterialTheme.colorScheme.primary)
    val previewVisible by remember {
      derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "preview" } }
    }
    val haptics = rememberAppHaptics()
    val scope = rememberCoroutineScope()
    val choose: (BackgroundType) -> Unit = { type ->
      if (type != picked || type == BackgroundType.RANDOM) {
        // Invalidate the shared random pick before publishing the mode change.
        if (type == BackgroundType.RANDOM) RandomBackground.reroll(prefs)
        prefs.backgroundType.set(type)
        haptics.selection(true)
        // Bring the live preview back into view after selecting a thumbnail further down the list.
        scope.launch { listState.animateScrollToItem(0) }
      }
    }

    DisposableEffect(Unit) {
      BackdropGate.covers++
      onDispose { BackdropGate.covers-- }
    }

    Scaffold(
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.bg_pref_title),
          onBack = { backstack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        Box(
          modifier = Modifier.fillMaxSize().padding(padding),
          contentAlignment = Alignment.TopCenter,
        ) {
          LazyColumn(
            state = listState,
            contentPadding =
              settingsContentPadding(
                PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
              ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().then(searchHighlight),
          ) {
            item(key = "preview") {
              Column(
                modifier = Modifier.settingsSearchTarget(R.string.bg_pref_title),
                verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
              ) {
                Surface(
                  shape = rememberConnectedShape(0, 2, ConnectedLayout.Vertical),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                  Box(Modifier.fillMaxWidth().height(192.dp)) {
                    AnimatedBackground(type = active.type, parallax = parallax, paused = !previewVisible)
                    if (active.type == BackgroundType.NONE) {
                      Icon(
                        MaterialSymbols.RoundedFilled.Visibility_off,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).size(40.dp),
                      )
                    }
                  }
                }
                Surface(
                  shape = rememberConnectedShape(1, 2, ConnectedLayout.Vertical),
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                  Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(
                      stringResource(R.string.bg_preview, stringResource(active.type.labelRes)),
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      stringResource(R.string.bg_picker_description),
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
            item(key = "mode") {
              ChoiceGrid(
                items = listOf(BackgroundType.NONE, BackgroundType.RANDOM),
                maxColumns = 2,
              ) { type, index, count, columns, modifier ->
                ChoiceTile(
                  selected = picked == type,
                  icon = iconFor(type),
                  label = stringResource(type.labelRes),
                  shape = rememberConnectedShape(index, count, ConnectedLayout.Grid, columns),
                  modifier = modifier.settingsSearchTarget(type.labelRes),
                  onClick = { choose(type) },
                )
              }
            }
            if (picked == BackgroundType.RANDOM) {
              item(key = "interval") {
                Column(modifier = backgroundItemMotion(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  SectionTitle(R.string.bg_interval_title)
                  ChoiceGrid(items = RandomInterval.entries, maxColumns = 3) { item, index, count, columns, modifier ->
                    ChoiceTile(
                      selected = interval == item,
                      icon = iconFor(item),
                      label = stringResource(item.labelRes),
                      shape = rememberConnectedShape(index, count, ConnectedLayout.Grid, columns),
                      modifier = modifier.settingsSearchTarget(item.labelRes),
                      onClick = {
                        if (interval != item) {
                          RandomBackground.reroll(prefs)
                          prefs.randomBackgroundInterval.set(item)
                          haptics.selection(true)
                          scope.launch { listState.animateScrollToItem(0) }
                        }
                      },
                    )
                  }
                }
              }
            }
            if (active.event != null && active.type.isSeasonal && picked != BackgroundType.NONE) {
              item(key = "seasonal_notice") {
                Surface(
                  modifier = backgroundItemMotion(),
                  shape = RoundedCornerShape(20.dp),
                  color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                  Text(
                    stringResource(R.string.bg_seasonal_notice, stringResource(active.type.labelRes)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                  )
                }
              }
            }
            item(key = "geometric") {
              BackgroundSection(R.string.bg_geometric, GeometricBackgrounds, picked, choose, backgroundItemMotion())
            }
            item(key = "nature") {
              BackgroundSection(R.string.bg_nature, NatureBackgrounds, picked, choose, backgroundItemMotion())
            }
            item(
              key = "sky",
            ) { BackgroundSection(R.string.bg_sky, SkyBackgrounds, picked, choose, backgroundItemMotion()) }
            item(key = "behavior") {
              Column(modifier = backgroundItemMotion(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(R.string.bg_behavior)
                ExpressivePreferenceGroup {
                  SwitchPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.bg_parallax_title),
                    value = parallax,
                    onValueChange = { prefs.backgroundParallax.set(it) },
                    title = { Text(stringResource(R.string.bg_parallax_title)) },
                    summary = { Text(stringResource(R.string.bg_parallax_summary)) },
                    icon = { PreferenceIcon(MaterialSymbols.RoundedFilled.Screen_rotation) },
                    enabled = picked != BackgroundType.NONE,
                    groupIndex = 0,
                    groupSize = 3,
                  )
                  SwitchPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.bg_over_wallpaper_title),
                    value = overWallpaper,
                    onValueChange = { prefs.backgroundOverWallpaper.set(it) },
                    title = { Text(stringResource(R.string.bg_over_wallpaper_title)) },
                    summary = { Text(stringResource(R.string.bg_over_wallpaper_summary)) },
                    icon = { PreferenceIcon(MaterialSymbols.RoundedFilled.Image) },
                    enabled = picked != BackgroundType.NONE,
                    groupIndex = 1,
                    groupSize = 3,
                  )
                  SwitchPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.bg_seasonal_title),
                    value = seasonal,
                    onValueChange = { prefs.seasonalThemes.set(it) },
                    title = { Text(stringResource(R.string.bg_seasonal_title)) },
                    summary = { Text(stringResource(R.string.bg_seasonal_summary)) },
                    icon = { PreferenceIcon(MaterialSymbols.RoundedFilled.Celebration) },
                    groupIndex = 2,
                    groupSize = 3,
                  )
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
private fun LazyItemScope.backgroundItemMotion(): Modifier =
  Modifier.animateItem(
    fadeInSpec = AppMotion.spatial(AppMotion.Effect.Alpha, snap()),
    placementSpec = AppMotion.spatial(AppMotion.Spatial.StandardOffset, snap()),
    fadeOutSpec = AppMotion.spatial(AppMotion.Effect.Alpha, snap()),
  )

@Composable
private fun PreferenceIcon(icon: ImageVector) {
  Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
}

@Composable
private fun SectionTitle(
  @StringRes title: Int,
) {
  Text(
    stringResource(title),
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 12.dp).semantics { heading() },
  )
}

@Composable
private fun BackgroundSection(
  @StringRes title: Int,
  types: List<BackgroundType>,
  picked: BackgroundType,
  choose: (BackgroundType) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SectionTitle(title)
    ChoiceGrid(items = types) { type, index, count, columns, modifier ->
      ChoiceTile(
        selected = picked == type,
        icon = iconFor(type),
        label = stringResource(type.labelRes),
        shape = rememberConnectedShape(index, count, ConnectedLayout.Grid, columns),
        artwork = type,
        modifier = modifier.settingsSearchTarget(type.labelRes),
        onClick = { choose(type) },
      )
    }
  }
}

/** Respect both available width and font scale; each tile keeps the same width and height. */
@Composable
private fun <T> ChoiceGrid(
  items: List<T>,
  maxColumns: Int = 3,
  tile: @Composable (T, Int, Int, Int, Modifier) -> Unit,
) {
  val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
  val gap = AppConnectedShapeTokens.spacing
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val columns = ((maxWidth + gap) / (144.dp * fontScale + gap)).toInt().coerceIn(1, maxColumns)
    // Fixed cell widths keep every tile equal, while a short final row sits in the center.
    val cellWidth = (maxWidth - gap * (columns - 1)) / columns
    Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.selectableGroup()) {
      items.chunked(columns).forEachIndexed { rowIndex, row ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
          modifier = Modifier.fillMaxWidth(),
        ) {
          row.forEachIndexed { column, item ->
            tile(item, rowIndex * columns + column, items.size, columns, Modifier.width(cellWidth))
          }
        }
      }
    }
  }
}

@Composable
private fun ChoiceTile(
  selected: Boolean,
  icon: ImageVector,
  label: String,
  shape: Shape,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  artwork: BackgroundType? = null,
) {
  val colors = MaterialTheme.colorScheme
  val interactions = remember { MutableInteractionSource() }
  val color by animateColorAsState(
    if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "backgroundChoiceColor",
  )
  val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
  val tileHeight = (if (artwork == null) 72.dp else 136.dp) + 24.dp * fontScale
  Surface(
    color = color,
    shape = shape,
    modifier =
      modifier
        .height(tileHeight)
        .tvFocusHighlight(shape, focusedScale = 1.01f)
        .appPressFeedback(interactions, pressedScale = 0.98f)
        .clip(shape)
        .selectable(
          selected = selected,
          role = Role.RadioButton,
          interactionSource = interactions,
          indication = ripple(),
          onClick = onClick,
        ),
  ) {
    Box(Modifier.fillMaxSize()) {
      if (artwork != null) {
        // Paint edge-to-edge behind both the illustration and its caption.
        BackgroundThumbnail(artwork, Modifier.fillMaxSize())
        Box(
          Modifier.fillMaxSize().background(
            Brush.verticalGradient(
              0f to Color.Transparent,
              0.52f to color.copy(alpha = 0.28f),
              1f to color.copy(alpha = 0.96f),
            ),
          ),
        )
      }
      Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (artwork == null) Arrangement.Center else Arrangement.Bottom,
      ) {
        if (artwork == null) {
          Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
          Spacer(Modifier.height(8.dp))
        }
        Text(
          text = label,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
          color =
            if (artwork !=
              null
            ) {
              colors.onSurface
            } else if (selected) {
              colors.onSecondaryContainer
            } else {
              colors.onSurface
            },
          textAlign = TextAlign.Center,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      if (selected) {
        // Overlay the marker rather than reserving space beside the label: it stays centered.
        Surface(
          modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
          shape = RoundedCornerShape(100),
          color = colors.secondaryContainer,
        ) {
          Icon(
            MaterialSymbols.RoundedFilled.Check,
            contentDescription = null,
            tint = colors.onSecondaryContainer,
            modifier = Modifier.padding(4.dp).size(16.dp),
          )
        }
      }
    }
  }
}

/** A still from the real renderer: no extra clocks, sensor subscriptions, or animated tile grid. */
@Composable
private fun BackgroundThumbnail(
  type: BackgroundType,
  modifier: Modifier = Modifier,
) {
  val palette = rememberBackdropPalette()
  val drawing = remember { BackdropDrawingState() }
  Canvas(modifier.clipToBounds()) {
    if (size.minDimension <= 0f) return@Canvas
    // Each renderer already uses its own canvas dimensions. Avoid a fake phone-sized
    // canvas that crops artwork and creates empty bands inside the connected tile.
    drawBackdrop(type, 12f, Offset.Zero, palette, drawing)
  }
}

private fun iconFor(type: BackgroundType): ImageVector =
  when (type) {
    BackgroundType.CIRCLES -> MaterialSymbols.RoundedFilled.Circle
    BackgroundType.RINGS -> MaterialSymbols.RoundedFilled.Radio_button_unchecked
    BackgroundType.SPACE -> MaterialSymbols.RoundedFilled.Auto_awesome
    BackgroundType.SHAPES -> MaterialSymbols.RoundedFilled.Pentagon
    BackgroundType.SNOW -> MaterialSymbols.RoundedFilled.Ac_unit
    BackgroundType.GRID -> MaterialSymbols.RoundedFilled.Grid_view
    BackgroundType.PARTICLES -> MaterialSymbols.RoundedFilled.Grain
    BackgroundType.LAVA -> MaterialSymbols.RoundedFilled.Water_drop
    BackgroundType.AURORA -> MaterialSymbols.RoundedFilled.Gradient
    BackgroundType.FIREFLIES -> MaterialSymbols.RoundedFilled.Flare
    BackgroundType.RAIN -> MaterialSymbols.RoundedFilled.Rainy
    BackgroundType.BOKEH -> MaterialSymbols.RoundedFilled.Lens_blur
    BackgroundType.SAKURA -> MaterialSymbols.RoundedFilled.Local_florist
    BackgroundType.CONSTELLATION -> MaterialSymbols.RoundedFilled.Hub
    BackgroundType.BUBBLES -> MaterialSymbols.RoundedFilled.Bubble_chart
    BackgroundType.NONE -> MaterialSymbols.RoundedFilled.Visibility_off
    BackgroundType.RANDOM -> MaterialSymbols.RoundedFilled.Shuffle
    else -> MaterialSymbols.RoundedFilled.Celebration
  }

private fun iconFor(interval: RandomInterval): ImageVector =
  when (interval) {
    RandomInterval.EACH_LAUNCH -> MaterialSymbols.RoundedFilled.Play_circle
    RandomInterval.EVERY_DAY -> MaterialSymbols.RoundedFilled.Calendar_today
    RandomInterval.EVERY_3_DAYS -> MaterialSymbols.RoundedFilled.Schedule
  }
