package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.PathMeasure
import android.graphics.Rect
import android.os.SystemClock

/** Observes dispatched Android paths. Rendering never synthesizes input or delays dispatch. */
internal class ControlCursorRenderer(
    context: Context,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    private val painter = ControlCursorPainter(context)
    private val traces = mutableListOf<CursorTrace>()
    private val position = FloatArray(2)
    private val movementThreshold = MOVEMENT_THRESHOLD_DP * context.resources.displayMetrics.density
    private var action: ControlCursorAction? = null
    private var startedAt = 0L

    /** Session updates only select a verb; a real successful action must supply a position. */
    fun updateAction(toolName: String?) {
        action = ControlCursorAction.fromTool(toolName)
    }

    fun showGesture(gesture: GestureDescription) {
        traces.clear()
        repeat(gesture.strokeCount) { index ->
            val stroke = gesture.getStroke(index)
            val geometry = measureGesturePath(stroke.path)
            val fallback =
                when {
                    geometry.measure.length > movementThreshold -> ControlCursorAction.SWIPE
                    stroke.duration >= LONG_PRESS_MS -> ControlCursorAction.LONG_PRESS
                    else -> ControlCursorAction.CLICK
                }
            traces +=
                CursorTrace(
                    geometry.measure,
                    CursorPoint(geometry.originX, geometry.originY),
                    stroke.startTime,
                    stroke.duration,
                    action ?: fallback,
                )
        }
        startedAt = uptimeMillis()
    }

    fun showTarget(bounds: Rect) {
        if (bounds.isEmpty) return
        traces.clear()
        traces +=
            CursorTrace(
                null,
                CursorPoint(bounds.exactCenterX(), bounds.exactCenterY()),
                0L,
                0L,
                action ?: ControlCursorAction.CLICK,
            )
        startedAt = uptimeMillis()
    }

    fun clear() {
        traces.clear()
        action = null
    }

    fun draw(
        canvas: Canvas,
        now: Long,
        width: Int,
        height: Int,
    ): Boolean {
        val elapsed = now - startedAt
        var active = false
        var showLabel = true
        val viewport = CursorSize(width.toFloat(), height.toFloat())
        for (trace in traces) {
            val frame = ControlCursorTimeline.sample(elapsed, trace.delay, trace.duration)
            if (frame != null) {
                painter.draw(canvas, locate(trace, frame.progress), viewport, trace.action, frame.alpha, showLabel)
                showLabel = false
                active = true
            } else if (ControlCursorTimeline.hasPendingStroke(elapsed, trace.delay)) {
                active = true
            }
        }
        if (!active) traces.clear()
        return active
    }

    private fun locate(
        trace: CursorTrace,
        progress: Float,
    ): CursorPoint {
        val path = trace.path
        return if (path != null && path.getPosTan(progress * path.length, position, null)) {
            CursorPoint(position[0], position[1])
        } else {
            trace.origin
        }
    }

    private data class CursorTrace(
        val path: PathMeasure?,
        val origin: CursorPoint,
        val delay: Long,
        val duration: Long,
        val action: ControlCursorAction,
    )

    companion object {
        private const val MOVEMENT_THRESHOLD_DP = 2f
        private const val LONG_PRESS_MS = 500L
    }
}
