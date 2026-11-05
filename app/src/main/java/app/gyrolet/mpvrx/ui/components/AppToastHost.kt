package app.gyrolet.mpvrx.ui.components

import android.os.SystemClock
import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LocalAppToastHost = staticCompositionLocalOf { false }
private const val MAX_QUEUED_TOASTS = 6
private const val MAX_VISIBLE_TOASTS = 3

// Equal horizontal insets preserve the window's center when a landscape cutout or
// navigation bar occupies only one edge. Delegate reads so inset animations stay live.
private class CenteredToastInsets(private val insets: WindowInsets) : WindowInsets {
  override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
    maxOf(insets.getLeft(density, layoutDirection), insets.getRight(density, layoutDirection))

  override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
    getLeft(density, layoutDirection)

  override fun getTop(density: Density): Int = insets.getTop(density)

  override fun getBottom(density: Density): Int = insets.getBottom(density)
}

private class AppToastPresentation(
  val toast: AppToastMessage,
  val host: SnackbarHostState,
) : SnackbarVisuals {
  override val message: String get() = toast.text
  override val actionLabel: String? get() = toast.action?.label
  override val withDismissAction: Boolean = false
  override val duration: SnackbarDuration = SnackbarDuration.Indefinite
}

private interface AppToastHostSink : AppToastSink {
  fun clear()
}

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
  val safeDrawing = WindowInsets.safeDrawing
  val toastInsets = remember(safeDrawing) { CenteredToastInsets(safeDrawing) }
  val owner = remember { Any() }
  val messages = remember { mutableStateMapOf<Long, AppToastPresentation>() }
  val cleanupScope = rememberCoroutineScope()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val density = LocalDensity.current
  var frontToastSize by remember { mutableStateOf(IntSize.Zero) }
  val stackPeek = with(density) { 8.dp.toPx() }
  val sink = remember(lifecycle, activity, view, accessibility) {
    object : AppToastHostSink {
      // Timed toasts share the newest deadline, so a stack leaves together instead of one by one.
      private var groupDeadline = 0L
      private var groupExpiry: Job? = null

      override fun clear() {
        groupExpiry?.cancel()
        groupExpiry = null
        groupDeadline = 0L
        messages.values.toList().forEach { it.host.currentSnackbarData?.dismiss() }
        messages.clear()
      }

      override fun show(message: AppToastMessage): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !view.isAttachedToWindow ||
          activity?.isInPictureInPictureMode == true
        ) return false
        if (message.id !in messages && messages.size >= MAX_QUEUED_TOASTS) {
          messages.keys.minOrNull()?.let { oldest ->
            messages.remove(oldest)?.host?.currentSnackbarData?.dismiss()
          }
        }
        val host = messages[message.id]?.host ?: SnackbarHostState()
        messages[message.id] = AppToastPresentation(message, host)
        if (message.type != AppToastType.Loading) {
          val timeout = if (message.duration == AppToast.LENGTH_LONG) 6_000L else 3_500L
          extendGroup(
            accessibility?.calculateRecommendedTimeoutMillis(
              timeout, containsIcons = true, containsText = true, containsControls = true,
            ) ?: timeout,
          )
        }
        return true
      }

      override fun dismiss(id: Long) {
        val dismissed = messages[id] ?: return
        dismissed.host.currentSnackbarData?.dismiss()
        cleanupScope.launch {
          delay(200L)
          if (messages[id] === dismissed) messages.remove(id)
          if (messages.isEmpty()) {
            groupExpiry?.cancel()
            groupExpiry = null
            groupDeadline = 0L
          }
        }
      }

      private fun extendGroup(timeoutMillis: Long) {
        // Accessibility can ask to keep toasts until the user dismisses them.
        if (timeoutMillis >= Int.MAX_VALUE) {
          groupExpiry?.cancel()
          groupExpiry = null
          groupDeadline = Long.MAX_VALUE
          return
        }
        if (groupDeadline == Long.MAX_VALUE) return
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        if (groupExpiry?.isActive == true && deadline <= groupDeadline) return
        groupDeadline = deadline
        groupExpiry?.cancel()
        groupExpiry = cleanupScope.launch {
          delay(timeoutMillis)
          messages.values.filter { it.toast.type != AppToastType.Loading }.forEach { dismiss(it.toast.id) }
        }
      }
    }
  }
  DisposableEffect(owner, lifecycle, sink) {
    fun deactivate() {
      AppToastRouter.unregister(owner)
      sink.clear()
    }
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> AppToastRouter.register(owner, sink)
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> deactivate()
        else -> Unit
      }
    }
    val attachment = object : View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(view: View) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) AppToastRouter.register(owner, sink)
      }

      override fun onViewDetachedFromWindow(view: View) = deactivate()
    }
    lifecycle.addObserver(observer)
    view.addOnAttachStateChangeListener(attachment)
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) AppToastRouter.register(owner, sink)
    onDispose {
      lifecycle.removeObserver(observer)
      view.removeOnAttachStateChangeListener(attachment)
      deactivate()
    }
  }
  CompositionLocalProvider(LocalAppToastHost provides true) {
    Box(Modifier.fillMaxSize()) {
      content()
      Box(
        modifier = Modifier.matchParentSize()
          .windowInsetsPadding(toastInsets)
          .padding(horizontal = 12.dp, vertical = 8.dp)
          .zIndex(Float.MAX_VALUE),
        contentAlignment = Alignment.BottomCenter,
      ) {
        Box(
          modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
          contentAlignment = Alignment.BottomCenter,
        ) {
          val visibleToasts = messages.values.sortedBy { it.toast.id }.takeLast(MAX_VISIBLE_TOASTS)
          visibleToasts.forEachIndexed { index, presentation ->
            androidx.compose.runtime.key(presentation.toast.id) {
              val isFront = index == visibleToasts.lastIndex
              val depth by animateFloatAsState(
                targetValue = (visibleToasts.lastIndex - index).toFloat(),
                animationSpec = if (reducedMotion) snap() else tween(180),
                label = "toastStackDepth",
              )
              LaunchedEffect(presentation) {
                presentation.host.showSnackbar(presentation)
                if (messages[presentation.toast.id] === presentation) sink.dismiss(presentation.toast.id)
              }
              val toastContent: @Composable (SnackbarData) -> Unit = { data ->
                val rendered = data.visuals as? AppToastPresentation
                if (rendered != null) {
                  Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SwipeDismissibleToast(onDismiss = { sink.dismiss(rendered.toast.id) }) {
                      AppToastContent(
                        rendered.toast,
                        reducedMotion,
                        onDismiss = { sink.dismiss(rendered.toast.id) },
                        modifier = if (isFront) {
                          Modifier.onSizeChanged { if (it != IntSize.Zero) frontToastSize = it }
                        } else {
                          Modifier.fillMaxSize()
                        },
                      )
                    }
                  }
                }
              }
              val cardSize = if (!isFront && frontToastSize != IntSize.Zero) {
                with(density) { Modifier.size(frontToastSize.width.toDp(), frontToastSize.height.toDp()) }
              } else {
                Modifier.fillMaxWidth()
              }
              Box(
                modifier = cardSize
                  .zIndex(index.toFloat())
                  .graphicsLayer {
                    translationY = -depth * stackPeek
                    scaleX = 1f - depth * 0.04f
                    alpha = if (isFront || frontToastSize != IntSize.Zero) 1f else 0f
                  }
                  .then(if (isFront) Modifier else Modifier.clipToBounds().clearAndSetSemantics {}),
                contentAlignment = Alignment.BottomCenter,
              ) {
                if (reducedMotion) {
                  presentation.host.currentSnackbarData?.let { toastContent(it) }
                } else {
                  SnackbarHost(presentation.host, modifier = Modifier.fillMaxWidth(), snackbar = toastContent)
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
private fun AppToastContent(
  message: AppToastMessage,
  reducedMotion: Boolean,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
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
  val defaultIcon = when (message.type) {
    AppToastType.Success -> Icons.RoundedFilled.CheckCircle
    AppToastType.Warning -> Icons.RoundedFilled.Warning
    AppToastType.Error -> Icons.RoundedFilled.Warning
    AppToastType.Normal, AppToastType.Info, AppToastType.Loading -> Icons.RoundedFilled.Info
  }
  Row(
    modifier = modifier
      .widthIn(max = 400.dp)
      .clip(shape)
      .background(surface)
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
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false).padding(vertical = 6.dp),
    )
    message.action?.let { action ->
      TextButton(onClick = { onDismiss(); action.onClick() }, modifier = Modifier.widthIn(max = 112.dp)) {
        Text(action.label, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}
