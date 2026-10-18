package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.PathMeasure
import android.graphics.Rect
import android.os.SystemClock

/** Distinct approach, accepted-input and confirmed-completion boundaries never synthesize input. */
@Suppress("TooManyFunctions")
internal class ControlCursorRenderer(
    context: Context,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    private val painter = ControlCursorPainter(context)
    private val traces = mutableListOf<CursorTrace>()
    private val position = FloatArray(2)
    private val density = context.resources.displayMetrics.density
    private val movementThreshold = MOVEMENT_THRESHOLD_DP * density
    private var action: ControlCursorAction? = null
    private var startedAt = 0L
    private var completedAt = 0L
    private var approach: CursorApproach? = null
    private var lastTip: CursorPoint? = null
    var phase: ControlCursorPhase? = null
        private set

    /** Session updates only select a verb; a real successful action must supply a position. */
    fun updateAction(toolName: String?) {
        action = ControlCursorAction.fromTool(toolName)
    }

    fun prepareGesture(
        gesture: GestureDescription,
        width: Int,
        height: Int,
    ): Long {
        val geometry = measureGesturePath(gesture.getStroke(0).path)
        return prepare(CursorPoint(geometry.originX, geometry.originY), width, height)
    }

    fun prepareTarget(
        bounds: Rect,
        width: Int,
        height: Int,
    ): Long {
        if (bounds.isEmpty) return 0L
        return prepare(CursorPoint(bounds.exactCenterX(), bounds.exactCenterY()), width, height)
    }

    private fun prepare(
        target: CursorPoint,
        width: Int,
        height: Int,
    ): Long {
        val now = uptimeMillis()
        rememberTip(now)
        traces.clear()
        val next =
            ControlCursorApproach.plan(
                lastTip,
                target,
                CursorSize(width.toFloat(), height.toFloat()),
                density,
                ValueAnimator.areAnimatorsEnabled(),
                now,
            )
        approach = next
        phase = ControlCursorPhase.APPROACH
        return next.durationMillis
    }

    fun showGesture(gesture: GestureDescription) {
        approach = null
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
        phase = ControlCursorPhase.EXECUTING
    }

    /** Only a successful platform callback may change execution to confirmed completion. */
    fun completeGesture() {
        if (phase != ControlCursorPhase.EXECUTING || traces.isEmpty()) return
        lastTip = locate(traces.first(), 1f)
        completedAt = uptimeMillis()
        phase = ControlCursorPhase.COMPLETED
    }

    fun showTarget(bounds: Rect) {
        if (bounds.isEmpty) return
        approach = null
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
        completedAt = startedAt
        lastTip = traces.first().origin
        phase = ControlCursorPhase.COMPLETED
    }

    fun clear() {
        rememberTip(uptimeMillis())
        traces.clear()
        approach = null
        action = null
        phase = null
    }

    fun draw(
        canvas: Canvas,
        now: Long,
        width: Int,
        height: Int,
    ): Boolean {
        val viewport = CursorSize(width.toFloat(), height.toFloat())
        approach?.let {
            val point = it.sample(now)
            lastTip = point
            painter.draw(
                canvas,
                point,
                viewport,
                ControlCursorVisual(action ?: ControlCursorAction.CLICK, ControlCursorPhase.APPROACH),
                true,
            )
            return true
        }
        return drawCurrent(canvas, now, viewport)
    }

    private fun drawCurrent(
        canvas: Canvas,
        now: Long,
        viewport: CursorSize,
    ): Boolean {
        val currentPhase = phase ?: return false
        val completionAlpha =
            if (currentPhase == ControlCursorPhase.COMPLETED) {
                ControlCursorTimeline.completionAlpha(now - completedAt)
            } else {
                null
            }
        return if (currentPhase == ControlCursorPhase.COMPLETED && completionAlpha == null) {
            traces.clear()
            phase = null
            false
        } else {
            drawTraces(canvas, now, viewport, currentPhase, completionAlpha)
        }
    }

    private fun drawTraces(
        canvas: Canvas,
        now: Long,
        viewport: CursorSize,
        currentPhase: ControlCursorPhase,
        completionAlpha: Float?,
    ): Boolean {
        val elapsed = now - startedAt
        var active = false
        var showLabel = true
        for (trace in traces) {
            val frame =
                completionAlpha?.let { ControlCursorFrame(1f, it) }
                    ?: ControlCursorTimeline.sample(elapsed, trace.delay, trace.duration)
            if (frame != null) {
                val point = locate(trace, frame.progress)
                if (showLabel) lastTip = point
                val clickAction =
                    trace.action == ControlCursorAction.CLICK ||
                        trace.action == ControlCursorAction.LONG_PRESS
                val click =
                    if (clickAction && ValueAnimator.areAnimatorsEnabled()) {
                        ControlCursorTimeline.clickEffect(elapsed - trace.delay)
                    } else {
                        null
                    }
                painter.draw(
                    canvas,
                    point,
                    viewport,
                    ControlCursorVisual(trace.action, currentPhase, frame.alpha, click),
                    showLabel,
                )
                showLabel = false
                active = true
            } else if (ControlCursorTimeline.hasPendingStroke(elapsed, trace.delay)) {
                active = true
            }
        }
        if (!active) {
            rememberTip(now)
            traces.clear()
            phase = null
        }
        return active
    }

    private fun rememberTip(now: Long) {
        val moving = approach
        if (moving != null) {
            lastTip = moving.sample(now)
        } else {
            traces.firstOrNull()?.let {
                val progress =
                    if (phase == ControlCursorPhase.COMPLETED) {
                        1f
                    } else {
                        ((now - startedAt - it.delay).toFloat() / it.duration.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    }
                lastTip = locate(it, progress)
            }
        }
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
