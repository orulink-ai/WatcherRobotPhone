package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.core.view.isVisible
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig

/** Draws feedback for dispatched gestures without receiving or generating touch input. */
internal class ControlEffectsView(
    context: Context,
) : View(context) {
    private val edges = ControlEdgeRenderer(resources.displayMetrics.density)
    private val cursor = ControlCursorRenderer(context)
    var config = ControlEffectsConfig()
        set(value) {
            val normalized = value.normalized()
            if (field == normalized) return
            field = normalized
            edges.updateConfig(normalized)
            invalidate()
        }
    var isActive = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun showGesture(gesture: GestureDescription) {
        cursor.showGesture(gesture)
        invalidate()
    }

    fun updateAction(toolName: String?) {
        cursor.updateAction(toolName)
    }

    fun showTarget(bounds: Rect) {
        cursor.showTarget(bounds)
        invalidate()
    }

    fun clearFeedback() {
        cursor.clear()
        invalidate()
    }

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int,
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        edges.resize(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val animateFrame = ValueAnimator.areAnimatorsEnabled()
        edges.draw(canvas, now, isActive, animateFrame)
        val transientFeedback = cursor.draw(canvas, now, width, height)
        val animating = animateFrame || transientFeedback
        if (isVisible && animating) {
            if (isActive || transientFeedback) {
                postInvalidateOnAnimation()
            } else {
                postInvalidateDelayed(IDLE_FRAME_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val IDLE_FRAME_INTERVAL_MS = 50L
    }
}
