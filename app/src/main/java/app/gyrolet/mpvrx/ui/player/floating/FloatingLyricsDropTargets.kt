/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Floating-lyrics drop interaction based on Mochy Music (GPL-3.0):
 * https://github.com/realidkroo/Mochy-Music/tree/bde01cb4321895c406ca93679462447236c2b2c3/app/src/main/kotlin/com/mochyy/mochymusic/floating
 */

package app.gyrolet.mpvrx.ui.player.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.performAppHapticFeedback
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class FloatingPillDrop { CLOSE, NEVER }

internal class FloatingLyricsDropTargets(
  private val context: Context,
  private val reducedMotion: () -> Boolean,
) {
  private val density get() = context.resources.displayMetrics.density
  private fun px(value: Float): Int = (value * density).roundToInt()
  private val targets = listOf(
    Target(FloatingPillDrop.CLOSE, R.string.ui_close, Color.WHITE, 0xFF1C1C1E.toInt()),
    Target(FloatingPillDrop.NEVER, R.string.floating_lyrics_never, 0xFFE5484D.toInt(), Color.WHITE),
  )
  private val row = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    clipChildren = false
    clipToPadding = false
    targets.forEachIndexed { index, target ->
      val layout = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      if (index > 0) layout.marginStart = px(12f)
      addView(target.view, layout)
    }
  }
  val view = FrameLayout(context).apply {
    background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x8C000000.toInt(), Color.TRANSPARENT))
    alpha = 0f
    visibility = View.GONE
    clipChildren = false
    clipToPadding = false
    addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
  }
  private val bounds = RectF()
  private var hovered: FloatingPillDrop? = null

  fun layoutParams(bottomInset: Int): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(220f) + bottomInset, Gravity.BOTTOM)

  fun show(bottomInset: Int) {
    view.animate().cancel()
    view.layoutParams = layoutParams(bottomInset)
    val layout = row.layoutParams
    check(layout is FrameLayout.LayoutParams)
    layout.bottomMargin = px(28f) + bottomInset
    row.layoutParams = layout
    hovered = null
    val maxWidth = ((context.resources.displayMetrics.widthPixels - px(28f)) / 2).coerceAtLeast(px(48f))
    targets.forEach { it.reset() }
    targets.forEach { it.view.maxWidth = maxWidth }
    view.visibility = View.VISIBLE
    if (reducedMotion()) {
      view.alpha = 1f
    } else {
      view.animate().alpha(1f).setDuration(180).start()
      targets.forEachIndexed { index, target ->
        target.view.translationY = px(72f).toFloat()
        target.view.alpha = 0f
        target.view.animate().translationY(0f).alpha(1f).setStartDelay(index * 45L)
          .setDuration(360).setInterpolator(OvershootInterpolator(1.4f)).start()
      }
    }
  }

  fun hide() {
    if (view.visibility != View.VISIBLE) return
    if (reducedMotion()) { hideNow(); return }
    targets.forEach {
      it.stop()
      it.view.animate().translationY(px(56f).toFloat()).alpha(0f).setStartDelay(0)
        .setDuration(170).setInterpolator(AccelerateInterpolator()).start()
    }
    view.animate().alpha(0f).setDuration(200).withEndAction { view.visibility = View.GONE }.start()
  }

  fun hideNow() {
    view.animate().cancel()
    targets.forEach { it.stop(); it.view.animate().cancel() }
    view.alpha = 0f
    view.visibility = View.GONE
    hovered = null
  }

  fun hover(center: Offset, cover: Int): FloatingPillDrop? {
    var best: FloatingPillDrop? = null
    var distance = Float.MAX_VALUE
    for (target in targets) {
      if (!boundsOf(target)) continue
      val reach = px(28f) + if (hovered == target.drop) px(18f) else 0
      val inside = center.x >= bounds.left - reach && center.x <= bounds.right + reach &&
        center.y >= bounds.top - cover - reach && center.y <= bounds.bottom + reach
      val away = abs(center.x - bounds.centerX())
      if (inside && away < distance) { best = target.drop; distance = away }
    }
    if (best != hovered) {
      hovered = best
      targets.forEach { it.light(it.drop == best) }
      if (best != null) view.performAppHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    return best
  }

  fun restSpot(drop: FloatingPillDrop, cover: Int): Offset? {
    if (!boundsOf(targets.first { it.drop == drop })) return null
    return Offset(bounds.centerX() - cover / 2f, bounds.top - px(10f) - cover)
  }

  fun center(drop: FloatingPillDrop): Offset? {
    if (!boundsOf(targets.first { it.drop == drop })) return null
    return Offset(bounds.centerX(), bounds.centerY())
  }

  fun swallow(drop: FloatingPillDrop) {
    if (reducedMotion()) return
    val target = targets.first { it.drop == drop }.view
    target.animate().scaleX(0.92f).scaleY(0.92f).setDuration(110).setInterpolator(DecelerateInterpolator())
      .withEndAction { target.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2f)).start() }.start()
  }

  private fun boundsOf(target: Target): Boolean {
    if (view.visibility != View.VISIBLE || !view.isLaidOut || !target.view.isLaidOut) return false
    val left = view.left + row.left + target.view.left.toFloat()
    val top = view.top + row.top + target.view.top.toFloat()
    bounds.set(left, top, left + target.view.width, top + target.view.height)
    return true
  }

  private inner class Target(val drop: FloatingPillDrop, label: Int, private val activeFill: Int, private val activeInk: Int) {
    private val fill = GradientDrawable().apply { cornerRadius = px(100f).toFloat(); setColor(REST_FILL) }
    private val icon = ContextCompat.getDrawable(context, Icons.Platform.Close)?.mutate()?.apply {
      setBounds(0, 0, px(16f), px(16f))
      setTint(Color.WHITE)
    }
    private var amount = 0f
    private var animation: ValueAnimator? = null
    val view = TextView(context).apply {
      text = context.getString(label)
      textSize = 14f
      typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
      setTextColor(Color.WHITE)
      gravity = Gravity.CENTER_VERTICAL
      setCompoundDrawablesRelative(icon, null, null, null)
      compoundDrawablePadding = px(6f)
      setPaddingRelative(px(16f), px(12f), px(18f), px(12f))
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.END
      background = fill
    }

    fun light(on: Boolean) {
      stop()
      if (reducedMotion()) { paint(if (on) 1f else 0f); return }
      animation = ValueAnimator.ofFloat(amount, if (on) 1f else 0f).apply {
        duration = if (on) 240 else 160
        interpolator = if (on) OvershootInterpolator(2.2f) else DecelerateInterpolator()
        addUpdateListener { paint(it.animatedValue as Float) }
        start()
      }
    }

    fun stop() { animation?.cancel(); animation = null }

    fun reset() {
      stop()
      view.animate().cancel()
      view.translationY = 0f
      view.alpha = 1f
      paint(0f)
    }

    private fun paint(value: Float) {
      amount = value
      val progress = value.coerceIn(0f, 1f)
      fill.setColor(ColorUtils.blendARGB(REST_FILL, activeFill, progress))
      val ink = ColorUtils.blendARGB(Color.WHITE, activeInk, progress)
      view.setTextColor(ink)
      icon?.setTint(ink)
      view.scaleX = 1f + 0.1f * value
      view.scaleY = view.scaleX
    }
  }

  private companion object {
    const val REST_FILL = 0xF0242528.toInt()
  }
}
