package app.gyrolet.mpvrx.ui.background

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.theme.AppMotion
import org.koin.compose.koinInject
import java.time.LocalDate

/**
 * Draws [type] full-screen and cross-fades when it changes. NONE and RANDOM draw nothing here:
 * RANDOM must be resolved to a concrete type first (see [rememberActiveBackground]).
 *
 * @param paused Stop advancing the clock, for when something opaque covers the page.
 */
@Composable
fun AnimatedBackground(
  type: BackgroundType,
  parallax: Boolean,
  modifier: Modifier = Modifier,
  paused: Boolean = false,
) {
  Crossfade(
    targetState = type,
    animationSpec = AppMotion.spatial(tween(durationMillis = 480, easing = FastOutSlowInEasing), snap()),
    modifier = modifier.fillMaxSize().clipToBounds(),
    label = "background_type",
  ) { shown ->
    if (shown == BackgroundType.NONE || shown == BackgroundType.RANDOM) {
      Box(Modifier.fillMaxSize())
    } else {
      BackdropCanvas(type = shown, parallax = parallax, running = !paused)
    }
  }
}

/** What the app should be drawing behind its screens right now. */
data class ActiveBackground(
  /** Concrete backdrop to draw (never RANDOM). NONE means nothing. */
  val type: BackgroundType,
  val parallax: Boolean,
  val overWallpaper: Boolean,
  val event: SeasonalEvent?,
)

/**
 * The user's choices from [AppearancePreferences] folded into one answer: RANDOM turned into a
 * concrete type, and a running seasonal event laid over the pick when seasonal themes are on.
 */
@Composable
fun rememberActiveBackground(): ActiveBackground {
  val prefs = koinInject<AppearancePreferences>()
  val picked by prefs.backgroundType.collectAsState()
  val interval by prefs.randomBackgroundInterval.collectAsState()
  val seasonal by prefs.seasonalThemes.collectAsState()
  val parallax by prefs.backgroundParallax.collectAsState()
  val overWallpaper by prefs.backgroundOverWallpaper.collectAsState()
  val today = rememberBackdropDate()
  val event = remember(today) { SeasonalEvent.on(today) }

  val revision = RandomBackground.revision
  val resolved =
    remember(picked, interval, today, revision) {
      if (picked == BackgroundType.RANDOM) RandomBackground.current(prefs, interval) else picked
    }
  val shown = event.backdropFor(picked, seasonal).let { if (it == BackgroundType.RANDOM) resolved else it }
  return ActiveBackground(shown, parallax, overWallpaper, event?.takeIf { seasonal })
}

@Composable
fun rememberSeasonalEvent(): SeasonalEvent? {
  val prefs = koinInject<AppearancePreferences>()
  val seasonal by prefs.seasonalThemes.collectAsState()
  val today = rememberBackdropDate()
  val event = remember(today) { SeasonalEvent.on(today) }
  return event?.takeIf { seasonal }
}

/** Re-evaluate seasonal and daily picks when returning to an app left open overnight. */
@Composable
private fun rememberBackdropDate(): LocalDate {
  var today by remember { mutableStateOf(LocalDate.now()) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }
  return today
}

/** Resolves RANDOM once per process (or per interval) so every caller agrees on the same pick. */
object RandomBackground {
  private var processPick: BackgroundType? = null
  var revision by mutableIntStateOf(0)
    private set

  /** Forget the current pick so the next lookup rolls again. */
  fun reroll(prefs: AppearancePreferences) {
    processPick = null
    prefs.lastRandomBackgroundDay.set(0L)
    revision++
  }

  fun current(
    prefs: AppearancePreferences,
    interval: RandomInterval,
  ): BackgroundType {
    processPick?.let { if (interval == RandomInterval.EACH_LAUNCH) return it }
    val today = LocalDate.now().toEpochDay()
    val last =
      BackgroundType.SHUFFLEABLE.firstOrNull { it.name == prefs.lastRandomBackground.get() }
    val lastDay = prefs.lastRandomBackgroundDay.get()
    if (interval != RandomInterval.EACH_LAUNCH &&
      last != null &&
      lastDay > 0L &&
      today - lastDay in 0L until interval.days.toLong()
    ) {
      processPick = last
      return last
    }
    // Never hand out the one the previous launch showed
    val next = BackgroundType.SHUFFLEABLE.filter { it != last }.random()
    prefs.lastRandomBackground.set(next.name)
    prefs.lastRandomBackgroundDay.set(today)
    processPick = next
    return next
  }
}

/** Counts opaque layers (full-screen dialogs) sitting over the app so the page behind can stop animating. */
object BackdropGate {
  var covers by mutableIntStateOf(0)
}
