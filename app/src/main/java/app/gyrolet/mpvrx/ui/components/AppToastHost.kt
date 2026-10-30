package app.gyrolet.mpvrx.ui.components

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion
import com.dokar.sonner.ToastType
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LocalAppToastHost = staticCompositionLocalOf { false }
private const val MAX_QUEUED_TOASTS = 6

@Composable
internal fun AppToastHost(content: @Composable () -> Unit) {
  if (LocalAppToastHost.current) {
    content()
    return
  }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val activity = LocalActivity.current
  val view = LocalView.current
  val accessibility = LocalAccessibilityManager.current
  val owner = remember { Any() }
  val messages = remember { mutableStateMapOf<Long, AppToastMessage>() }
  val cleanupScope = rememberCoroutineScope()
  val toaster = rememberToasterState(onToastDismissed = { toast ->
    val id = toast.id as? Long
    val dismissed = id?.let(messages::get)
    if (id != null && dismissed != null) cleanupScope.launch {
      delay(200L)
      if (messages[id] === dismissed) messages.remove(id)
    }
  })
  val haze = rememberHazeState()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val sink = remember(toaster, lifecycle, activity, view, accessibility) {
    object : AppToastSink {
      override fun show(message: AppToastMessage): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !view.isAttachedToWindow ||
          activity?.isInPictureInPictureMode == true
        ) return false
        if (message.id !in messages && messages.size >= MAX_QUEUED_TOASTS) {
          messages.keys.minOrNull()?.let { oldest ->
            toaster.dismiss(oldest)
            messages.remove(oldest)
          }
        }
        messages[message.id] = message
        val duration = if (message.type == AppToastType.Loading) {
          Duration.INFINITE
        } else {
          val timeout = if (message.duration == AppToast.LENGTH_LONG) 6_000L else 3_500L
          (accessibility?.calculateRecommendedTimeoutMillis(
            timeout, containsIcons = true, containsText = true, containsControls = true,
          ) ?: timeout).milliseconds
        }
        val type = when (message.type) {
          AppToastType.Success -> ToastType.Success
          AppToastType.Info -> ToastType.Info
          AppToastType.Warning -> ToastType.Warning
          AppToastType.Error -> ToastType.Error
          AppToastType.Normal, AppToastType.Loading -> ToastType.Normal
        }
        toaster.show(message = message.text, id = message.id, type = type, duration = duration)
        return true
      }

      override fun dismiss(id: Long) {
        toaster.dismiss(id)
      }
    }
  }
  DisposableEffect(owner, lifecycle, sink) {
    fun deactivate() {
      AppToastRouter.unregister(owner)
      toaster.dismissAll()
      messages.clear()
    }
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> AppToastRouter.register(owner, sink)
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> deactivate()
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) AppToastRouter.register(owner, sink)
    onDispose {
      lifecycle.removeObserver(observer)
      deactivate()
    }
  }
  CompositionLocalProvider(LocalAppToastHost provides true) {
    Box(Modifier.fillMaxSize()) {
      Box(
        Modifier.fillMaxSize().then(
          if (messages.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.hazeSource(haze)
          else Modifier,
        ),
      ) { content() }
      Toaster(
        state = toaster,
        modifier = Modifier.widthIn(max = 440.dp)
          .zIndex(Float.MAX_VALUE)
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        maxVisibleToasts = 2,
        swipeable = false,
        alignment = Alignment.BottomCenter,
        contentPadding = { PaddingValues(0.dp) },
        containerPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        elevation = 0.dp,
        border = { BorderStroke(0.dp, Color.Transparent) },
        background = { SolidColor(Color.Transparent) },
        shape = { RoundedCornerShape(24.dp) },
        enterTransitionDuration = if (reducedMotion) 0 else 180,
        exitTransitionDuration = if (reducedMotion) 0 else 150,
        iconSlot = {},
        actionSlot = {},
        closeButton = null,
        messageSlot = { toast ->
          val message = (toast.id as? Long)?.let(messages::get)
          if (message != null) {
            androidx.compose.runtime.key(message.id) {
              SwipeDismissibleToast(onDismiss = { toaster.dismiss(toast.id) }) {
                AppToastContent(message, haze, reducedMotion)
              }
            }
          }
        },
      )
    }
  }
}

@Composable
private fun AppToastContent(message: AppToastMessage, haze: HazeState, reducedMotion: Boolean) {
  val colors = MaterialTheme.colorScheme
  val tone = when (message.type) {
    AppToastType.Success, AppToastType.Loading -> colors.primary
    AppToastType.Info -> colors.secondary
    AppToastType.Warning -> colors.tertiary
    AppToastType.Error -> colors.error
    AppToastType.Normal -> colors.onSurfaceVariant
  }
  val surface = lerp(colors.surfaceContainerHigh, tone, 0.06f)
  val shape = RoundedCornerShape(24.dp)
  val blurStyle = remember(surface) {
    HazeBlurStyle {
      blurRadius(24.dp)
      backgroundColor(surface)
      colorEffects(listOf(HazeColorEffect.tint(surface.copy(alpha = 0.48f))))
      fallbackColorEffect(HazeColorEffect.tint(surface.copy(alpha = 0.94f)))
      noiseFactor(0.01f)
    }
  }
  val defaultIcon = when (message.type) {
    AppToastType.Success -> Icons.RoundedFilled.CheckCircle
    AppToastType.Warning -> Icons.RoundedFilled.Warning
    AppToastType.Error -> Icons.RoundedFilled.Warning
    AppToastType.Normal, AppToastType.Info, AppToastType.Loading -> Icons.RoundedFilled.Info
  }
  Row(
    modifier = Modifier
      .widthIn(max = 400.dp)
      .clip(shape)
      .then(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.hazeBlur(input = HazeInput.Sources(haze), style = blurStyle)
        else Modifier.background(surface),
      )
      .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
      .semantics { liveRegion = LiveRegionMode.Polite }
      .padding(horizontal = 12.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (message.type == AppToastType.Loading && message.icon == null && !reducedMotion) {
      CircularProgressIndicator(modifier = Modifier.size(20.dp), color = tone, strokeWidth = 2.dp)
    } else {
      when (val icon = message.icon) {
        is AppToastIcon.Symbol -> Icon(icon.value, contentDescription = null, modifier = Modifier.size(20.dp), tint = tone)
        is AppToastIcon.Emoji -> Text(icon.value, fontSize = 20.sp)
        null -> Icon(defaultIcon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tone)
      }
    }
    Spacer(Modifier.width(4.dp))
    Text(
      message.text,
      style = MaterialTheme.typography.bodyMedium,
      color = colors.onSurface,
      maxLines = 4,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).padding(vertical = 6.dp),
    )
  }
}
