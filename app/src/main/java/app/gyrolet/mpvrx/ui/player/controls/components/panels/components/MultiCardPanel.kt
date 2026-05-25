package app.gyrolet.mpvrx.ui.player.controls.components.panels.components

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.content.res.Configuration.ORIENTATION_PORTRAIT
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.controls.CARDS_MAX_WIDTH
import app.gyrolet.mpvrx.ui.theme.spacing
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import org.koin.compose.koinInject

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MultiCardPanel(
  onDismissRequest: () -> Unit,
  @StringRes titleRes: Int,
  cardCount: Int,
  modifier: Modifier = Modifier,
  cards: @Composable (Int, Modifier) -> Unit,
) {
  BackHandler(onBack = onDismissRequest)
  val orientation = LocalConfiguration.current.orientation
  val density = LocalDensity.current
  val cards = remember { movableContentOf { p1: Int, p2: Modifier -> cards(p1, p2) } }

  val preferences = koinInject<AppearancePreferences>()
  val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()
  val liquidBlur by preferences.liquidDialogBlur.collectAsState()
  val liquidSaturation by preferences.liquidDialogSaturation.collectAsState()
  val liquidBrightness by preferences.liquidDialogBrightness.collectAsState()
  val liquidLensRadius by preferences.liquidDialogLensRadius.collectAsState()
  val liquidLensDepth by preferences.liquidDialogLensDepth.collectAsState()
  val liquidAlpha by preferences.liquidDialogContainerAlpha.collectAsState()

  ConstraintLayout(modifier = modifier.fillMaxSize()) {
    val settingsCards = createRef()

    val pagerState = rememberPagerState { cardCount }
    if (orientation == ORIENTATION_PORTRAIT) {
      Column(
        modifier =
          Modifier
            .then(
                if (enableLiquidGlass) {
                    Modifier.drawBackdrop(
                        backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop(),
                        shape = { androidx.compose.ui.graphics.RectangleShape },
                        effects = {
                            colorControls(
                                brightness = liquidBrightness,
                                saturation = liquidSaturation
                            )
                            blur(with(density) { liquidBlur.dp.toPx() })
                            lens(
                                with(density) { liquidLensRadius.dp.toPx() },
                                with(density) { liquidLensDepth.dp.toPx() },
                                depthEffect = true
                            )
                        },
                        highlight = { Highlight.Plain },
                        onDrawSurface = {
                            drawRect(Color.Black.copy(alpha = liquidAlpha))
                        }
                    )
                } else {
                    Modifier
                }
            )
            .constrainAs(settingsCards) {
                top.linkTo(parent.top)
                start.linkTo(parent.start)
            },
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
      ) {
        TopAppBar(
          title = {
            Text(
              text = stringResource(titleRes),
              style = MaterialTheme.typography.headlineMedium.copy(shadow = Shadow(blurRadius = 20f)),
            )
          },
          navigationIcon = {
            IconButton(onClick = onDismissRequest) {
              Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
            }
          },
          colors = TopAppBarDefaults.topAppBarColors().copy(containerColor = Color.Transparent),
        )
        HorizontalPager(
          state = pagerState,
          pageSize = PageSize.Fixed(LocalConfiguration.current.screenWidthDp.dp * 0.9f),
          verticalAlignment = Alignment.Top,
          pageSpacing = MaterialTheme.spacing.smaller,
          contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.smaller),
          beyondViewportPageCount = 1,
        ) { page ->
          cards(page, Modifier.fillMaxWidth())
        }
      }
    } else {
      Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        modifier =
          Modifier
            .constrainAs(settingsCards) {
              top.linkTo(parent.top)
              end.linkTo(parent.end, 32.dp)
            }
            .verticalScroll(rememberScrollState()),
      ) {
        Spacer(Modifier.height(MaterialTheme.spacing.medium))
        Row(
          Modifier
            .width(CARDS_MAX_WIDTH),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = stringResource(titleRes),
            style =
              MaterialTheme.typography.headlineMedium.copy(
                shadow = Shadow(blurRadius = 20f),
              ),
          )
          IconButton(onDismissRequest) {
            Icon(imageVector = Icons.Default.Close, contentDescription = null)
          }
        }
        repeat(cardCount) { cards(it, Modifier) }
        Spacer(Modifier.height(MaterialTheme.spacing.medium))
      }
    }
  }
}




