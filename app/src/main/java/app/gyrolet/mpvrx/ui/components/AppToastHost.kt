package app.gyrolet.mpvrx.ui.components

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
private const val TOAST_ANIMATION_MS = 200

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
  val order: Long,
) {
  var exiting by mutableStateOf(false)
}

private interface AppToastHostSink : AppToastSink {
  fun clear()
  fun interacting(id: Long, active: Boolean)
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
  val stackPeek = with(density) { 12.dp.toPx() }
  val enterDistance = with(density) { 24.dp.toPx() }
  val sink = remember(lifecycle, activity, view, accessibility, reducedMotion) {
    object : AppToastHostSink {
      private var nextOrder = 0L
      private val expiryJobs = mutableMapOf<Long, Job>()
      private val removalJobs = mutableMapOf<Long, Job>()
      private val interactingIds = mutableSetOf<Long>()

      override fun clear() {
        expiryJobs.values.forEach { it.cancel() }
        removalJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
        removalJobs.clear()
        interactingIds.clear()
        messages.clear()
      }

      override fun show(message: AppToastMessage): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !view.isAttachedToWindow ||
          activity?.isInPictureInPictureMode == true
        ) return false
        if (message.id !in messages && messages.size >= MAX_QUEUED_TOASTS) {
          messages.values.minByOrNull { it.order }?.toast?.id?.let { oldest ->
            expiryJobs.remove(oldest)?.cancel()
            removalJobs.remove(oldest)?.cancel()
            interactingIds.remove(oldest)
            messages.remove(oldest)
          }
        }
        removalJobs.remove(message.id)?.cancel()
        messages[message.id] = AppToastPresentation(message, ++nextOrder)
        scheduleExpiry(message)
        return true
      }

      override fun dismiss(id: Long) {
        val dismissed = messages[id] ?: return
        if (dismissed.exiting) return
        expiryJobs.remove(id)?.cancel()
        interactingIds.remove(id)
        if (reducedMotion) {
          messages.remove(id)
          return
        }
        dismissed.exiting = true
        removalJobs[id] = cleanupScope.launch {
          delay(TOAST_ANIMATION_MS.toLong())
          if (messages[id] === dismissed) messages.remove(id)
          removalJobs.remove(id)
        }
      }

      override fun interacting(id: Long, active: Boolean) {
        val entry = messages[id] ?: return
        if (entry.exiting) return
        if (active) {
          interactingIds.add(id)
          expiryJobs.remove(id)?.cancel()
        } else if (interactingIds.remove(id)) {
          scheduleExpiry(entry.toast)
        }
      }

      private fun scheduleExpiry(message: AppToastMessage) {
        expiryJobs.remove(message.id)?.cancel()
        if (message.type == AppToastType.Loading || message.id in interactingIds) return
        val timeout = if (message.duration == AppToast.LENGTH_LONG) 6_000L else 3_500L
        val recommended = accessibility?.calculateRecommendedTimeoutMillis(
          timeout, containsIcons = true, containsText = true, containsControls = true,
        ) ?: timeout
        // Accessibility can ask to keep a toast until it is explicitly dismissed.
        if (recommended >= Int.MAX_VALUE) return
        expiryJobs[message.id] = cleanupScope.launch {
          delay(recommended)
          expiryJobs.remove(message.id)
          dismiss(message.id)
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
          // Keep keyed entries composed even behind the visible stack. Revealing an older
          // toast must not restart its entrance animation or create another snackbar queue.
          val visibleToasts = messages.values.sortedBy { it.order }
          visibleToasts.forEachIndexed { index, presentation ->
            androidx.compose.runtime.key(presentation.toast.id) {
              val isFront = index == visibleToasts.lastIndex
              val targetDepth = visibleToasts.lastIndex - index
              var entered by remember { mutableStateOf(false) }
              LaunchedEffect(Unit) { entered = true }
              val visibility by animateFloatAsState(
                targetValue = if (entered && !presentation.exiting && targetDepth < MAX_VISIBLE_TOASTS) 1f else 0f,
                animationSpec = if (reducedMotion) snap() else tween(TOAST_ANIMATION_MS),
                label = "toastVisibility",
              )
              val depth by animateFloatAsState(
                targetValue = targetDepth.coerceAtMost(MAX_VISIBLE_TOASTS).toFloat(),
                animationSpec = if (reducedMotion) snap() else tween(TOAST_ANIMATION_MS),
                label = "toastStackDepth",
              )
              Box(
                modifier = Modifier.fillMaxWidth()
                  .zIndex(index.toFloat())
                  .graphicsLayer {
                    translationY = -depth * stackPeek + (1f - visibility) * enterDistance
                    scaleX = 1f - depth * 0.05f
                    alpha = visibility
                  }
                  .then(if (isFront && !presentation.exiting) Modifier else Modifier.clearAndSetSemantics {}),
                contentAlignment = Alignment.BottomCenter,
              ) {
                if (isFront) {
                  SwipeDismissibleToast(
                    onDismiss = { sink.dismiss(presentation.toast.id) },
                    onInteracting = { sink.interacting(presentation.toast.id, it) },
                  ) {
                    AppToastContent(
                      presentation.toast,
                      reducedMotion,
                      onDismiss = { sink.dismiss(presentation.toast.id) },
                      modifier = Modifier.fillMaxWidth()
                        .onSizeChanged { if (it != IntSize.Zero) frontToastSize = it },
                    )
                  }
                } else {
                  // Back cards expose their surface only, never overlapping text or hidden
                  // action buttons. Every layer shares the front card's width and height.
                  ToastCardSurface(
                    message = presentation.toast,
                    modifier = Modifier.fillMaxWidth()
                      .height(with(density) { frontToastSize.height.toDp() }.coerceAtLeast(48.dp)),
                  ) {}
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
private fun ToastCardSurface(
  message: AppToastMessage,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val tone = when (message.type) {
    AppToastType.Success, AppToastType.Loading -> colors.primary
    AppToastType.Info -> colors.secondary
    AppToastType.Warning -> colors.tertiary
    AppToastType.Error -> colors.error
    AppToastType.Normal -> colors.onSurfaceVariant
  }
  val shape = RoundedCornerShape(24.dp)
  Box(
    modifier.clip(shape)
      .background(lerp(colors.surfaceContainerHigh, tone, 0.06f))
      .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape),
  ) { content() }
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
  val defaultIcon = when (message.type) {
    AppToastType.Success -> Icons.RoundedFilled.CheckCircle
    AppToastType.Warning -> Icons.RoundedFilled.Warning
    AppToastType.Error -> Icons.RoundedFilled.Warning
    AppToastType.Normal, AppToastType.Info, AppToastType.Loading -> Icons.RoundedFilled.Info
  }
  ToastCardSurface(message, modifier) {
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
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
        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
      )
      message.action?.let { action ->
        TextButton(onClick = { onDismiss(); action.onClick() }, modifier = Modifier.widthIn(max = 112.dp)) {
          Text(action.label, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}
