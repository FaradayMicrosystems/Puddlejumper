package app.puddlejumper

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

/**
 * A bottom card you can swipe down to hide. While hidden, [peek] (a small handle at the bottom
 * edge) is shown; tapping or swiping up on it brings the card back.
 *
 * Only downward, mostly-vertical drags are taken, so sliders, switches and buttons inside keep working.
 */
class SwipeHideCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {

    /** Handle shown while this card is hidden. */
    var peek: View? = null
        set(value) {
            field = value
            value?.setOnTouchListener(PeekTouch())
            value?.isVisible = hidden && isVisible
        }

    var hidden = false
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (hidden) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                startVelocity(e)
            }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (dy > slop && dy > abs(dx) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycleVelocity()
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (hidden) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Touch landed on the card's own padding/background: start tracking a possible swipe.
                downX = e.rawX
                downY = e.rawY
                dragging = false
                startVelocity(e)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                val dy = e.rawY - downY
                if (!dragging && dy > slop) dragging = true
                if (dragging) translationY = dy.coerceAtLeast(0f)
                return true
            }
            MotionEvent.ACTION_UP -> {
                track(e)
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                recycleVelocity()
                if (dragging && (translationY > height * 0.3f || vy > minFling)) hide() else show()
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                recycleVelocity()
                dragging = false
                show()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** Slides the card off the bottom edge and shows [peek]. */
    fun hide() {
        hidden = true
        val margin = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        animate().translationY((height + margin).toFloat()).setDuration(180).start()
        peek?.isVisible = isVisible
    }

    /** Slides the card back into place and hides [peek]. */
    fun show(animate: Boolean = true) {
        hidden = false
        peek?.isVisible = false
        animate().cancel()
        if (animate) animate().translationY(0f).setDuration(180).start() else translationY = 0f
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        // Keep the handle in step when the screen swaps panels (e.g. starting navigation).
        peek?.isVisible = hidden && visibility == View.VISIBLE
    }

    private fun startVelocity(e: MotionEvent) {
        recycleVelocity()
        velocity = VelocityTracker.obtain()
        track(e)
    }

    /** Tracks screen coordinates, since the card itself moves under the finger. */
    private fun track(e: MotionEvent) {
        val raw = MotionEvent.obtain(e)
        raw.setLocation(e.rawX, e.rawY)
        velocity?.addMovement(raw)
        raw.recycle()
    }

    private fun recycleVelocity() {
        velocity?.recycle()
        velocity = null
    }

    /** Tap or swipe up on the handle brings the card back. */
    private inner class PeekTouch : OnTouchListener {
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                v.performClick()
                show()
            }
            return true
        }
    }
}
