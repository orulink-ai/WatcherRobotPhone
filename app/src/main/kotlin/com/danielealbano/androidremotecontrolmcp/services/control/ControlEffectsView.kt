package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import androidx.core.view.isVisible
import kotlin.math.PI
import kotlin.math.sin

/** Draws feedback for dispatched gestures without receiving or generating touch input. */
internal class ControlEffectsView(
    context: Context,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val target = RectF()
    private val position = FloatArray(2)
    private val traces = mutableListOf<GestureTrace>()
    private var gradients = emptyList<LinearGradient>()
    private val flowMatrix = Matrix()
    private var flowGradient: SweepGradient? = null
    private var flowAngle = 0f
    private var previousFrameAt = 0L
    private var gestureStartedAt = 0L
    private var targetStartedAt = 0L
    var isActive = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun showGesture(gesture: GestureDescription) {
        traces.clear()
        repeat(gesture.strokeCount) { index ->
            val stroke = gesture.getStroke(index)
            val geometry = measureGesturePath(stroke.path)
            traces +=
                GestureTrace(geometry.measure, stroke.startTime, stroke.duration, geometry.originX, geometry.originY)
        }
        gestureStartedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun showTarget(bounds: Rect) {
        target.set(bounds)
        targetStartedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun clearFeedback() {
        traces.clear()
        target.setEmpty()
        invalidate()
    }

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int,
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val inset = FRAME_INSET_DP * density
        frame.set(inset, inset, width - inset, height - inset)
        val glow = GLOW_WIDTH_DP * density
        gradients =
            listOf(
                LinearGradient(0f, 0f, glow, 0f, GLOW_BLUE, Color.TRANSPARENT, Shader.TileMode.CLAMP),
                LinearGradient(
                    width.toFloat(),
                    0f,
                    width - glow,
                    0f,
                    GLOW_BLUE,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                ),
                LinearGradient(0f, 0f, 0f, glow, GLOW_BLUE, Color.TRANSPARENT, Shader.TileMode.CLAMP),
                LinearGradient(
                    0f,
                    height.toFloat(),
                    0f,
                    height - glow,
                    GLOW_BLUE,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                ),
            )
        flowGradient =
            SweepGradient(
                frame.centerX(),
                frame.centerY(),
                intArrayOf(BLUE, CYAN, FLOW_VIOLET, BLUE),
                null,
            )
        previousFrameAt = 0L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        drawFrame(canvas, now)
        drawTarget(canvas, now)
        drawGestures(canvas, now)
        if (isVisible) postInvalidateOnAnimation()
    }

    private fun drawFrame(
        canvas: Canvas,
        now: Long,
    ) {
        val pulsePeriod = if (isActive) ACTIVE_PULSE_MS else IDLE_PULSE_MS
        val pulseBase = if (isActive) PULSE_BASE_ALPHA else IDLE_ALPHA
        val pulseAmplitude = if (isActive) PULSE_AMPLITUDE else IDLE_PULSE_AMPLITUDE
        val pulse = pulseBase + pulseAmplitude * sin(now % pulsePeriod * 2.0 * PI / pulsePeriod).toFloat()
        val frameDelta = if (previousFrameAt == 0L) 0L else (now - previousFrameAt).coerceAtMost(MAX_FRAME_DELTA_MS)
        previousFrameAt = now
        val flowPeriod = if (isActive) ACTIVE_FLOW_MS else IDLE_FLOW_MS
        flowAngle = (flowAngle + frameDelta * FULL_ROTATION / flowPeriod) % FULL_ROTATION
        flowMatrix.setRotate(flowAngle, frame.centerX(), frame.centerY())
        flowGradient?.setLocalMatrix(flowMatrix)
        paint.style = Paint.Style.FILL
        paint.alpha = (pulse * MAX_ALPHA).toInt()
        val glow = GLOW_WIDTH_DP * density
        for (index in gradients.indices) {
            paint.shader = gradients[index]
            when (index) {
                0 -> canvas.drawRect(0f, 0f, glow, height.toFloat(), paint)
                1 -> canvas.drawRect(width - glow, 0f, width.toFloat(), height.toFloat(), paint)
                2 -> canvas.drawRect(0f, 0f, width.toFloat(), glow, paint)
                else -> canvas.drawRect(0f, height - glow, width.toFloat(), height.toFloat(), paint)
            }
        }
        paint.shader = flowGradient
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = FLOW_GLOW_STROKE_DP * density
        paint.alpha = (pulse * FLOW_GLOW_ALPHA).toInt()
        canvas.drawRoundRect(frame, FRAME_RADIUS_DP * density, FRAME_RADIUS_DP * density, paint)
        paint.strokeWidth = FRAME_STROKE_DP * density
        paint.alpha = (pulse * MAX_ALPHA).toInt()
        canvas.drawRoundRect(frame, FRAME_RADIUS_DP * density, FRAME_RADIUS_DP * density, paint)
        paint.shader = null
    }

    private fun drawTarget(
        canvas: Canvas,
        now: Long,
    ): Boolean {
        if (target.isEmpty) return false
        val progress = ((now - targetStartedAt).toFloat() / FEEDBACK_FADE_MS).coerceIn(0f, 1f)
        val animating = progress < 1f
        if (animating) {
            paint.color = CYAN
            paint.alpha = ((1f - progress) * MAX_ALPHA).toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = TARGET_STROKE_DP * density
            canvas.drawRoundRect(target, TARGET_RADIUS_DP * density, TARGET_RADIUS_DP * density, paint)
        } else {
            target.setEmpty()
        }
        return animating
    }

    private fun drawGestures(
        canvas: Canvas,
        now: Long,
    ): Boolean {
        val elapsed = now - gestureStartedAt
        var animating = false
        for (index in traces.indices) {
            val trace = traces[index]
            val time = elapsed - trace.delay
            if (time < 0) {
                animating = true
            } else if (time <= trace.duration + FEEDBACK_FADE_MS) {
                drawTrace(canvas, trace, time)
                animating = true
            }
        }
        if (!animating) traces.clear()
        return animating
    }

    private fun drawTrace(
        canvas: Canvas,
        trace: GestureTrace,
        time: Long,
    ) {
        val progress = (time.toFloat() / trace.duration.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val fade = (1f - (time - trace.duration).coerceAtLeast(0L).toFloat() / FEEDBACK_FADE_MS).coerceIn(0f, 1f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        // A trail of samples follows the actual path; it never changes gesture timing.
        repeat(TRAIL_SAMPLES) { index ->
            val distance = (progress - index * TRAIL_SPACING).coerceAtLeast(0f) * trace.path.length
            locate(trace, distance)
            paint.color = BLUE
            paint.alpha = (fade * TRAIL_ALPHA * (1f - index.toFloat() / TRAIL_SAMPLES)).toInt()
            val radius = (TRAIL_RADIUS_DP - index * TRAIL_RADIUS_STEP_DP) * density
            canvas.drawCircle(position[0], position[1], radius, paint)
        }
        locate(trace, progress * trace.path.length)
        drawTouchPoint(canvas, fade, (time.toFloat() / (trace.duration + FEEDBACK_FADE_MS)).coerceIn(0f, 1f))
    }

    private fun locate(
        trace: GestureTrace,
        distance: Float,
    ) {
        if (!trace.path.getPosTan(distance, position, null)) {
            position[0] = trace.originX
            position[1] = trace.originY
        }
    }

    private fun drawTouchPoint(
        canvas: Canvas,
        fade: Float,
        progress: Float,
    ) {
        paint.color = BLUE
        paint.alpha = (fade * HALO_ALPHA).toInt()
        canvas.drawCircle(position[0], position[1], TOUCH_HALO_RADIUS_DP * density, paint)
        paint.color = CYAN
        paint.alpha = (fade * MAX_ALPHA).toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = TOUCH_STROKE_DP * density
        val ringRadius = (TOUCH_RING_RADIUS_DP + progress * TOUCH_RING_EXPANSION_DP) * density
        canvas.drawCircle(position[0], position[1], ringRadius, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = (fade * MAX_ALPHA).toInt()
        canvas.drawCircle(position[0], position[1], TOUCH_CENTER_RADIUS_DP * density, paint)
    }

    private data class GestureTrace(
        val path: PathMeasure,
        val delay: Long,
        val duration: Long,
        val originX: Float,
        val originY: Float,
    )

    companion object {
        private const val FRAME_INSET_DP = 2f
        private const val GLOW_WIDTH_DP = 22f
        private const val FRAME_STROKE_DP = 1.3f
        private const val FLOW_GLOW_STROKE_DP = 5f
        private const val FLOW_GLOW_ALPHA = 65
        private const val FULL_ROTATION = 360f
        private const val ACTIVE_FLOW_MS = 3600L
        private const val IDLE_FLOW_MS = 6000L
        private const val MAX_FRAME_DELTA_MS = 100L
        private const val FRAME_RADIUS_DP = 28f
        private const val TARGET_STROKE_DP = 2f
        private const val TARGET_RADIUS_DP = 10f
        private const val TRAIL_RADIUS_DP = 7f
        private const val TRAIL_RADIUS_STEP_DP = 0.3f
        private const val TOUCH_HALO_RADIUS_DP = 22f
        private const val TOUCH_STROKE_DP = 1.5f
        private const val TOUCH_RING_RADIUS_DP = 13f
        private const val TOUCH_RING_EXPANSION_DP = 8f
        private const val TOUCH_CENTER_RADIUS_DP = 4f
        private const val PULSE_BASE_ALPHA = 0.78f
        private const val PULSE_AMPLITUDE = 0.22f
        private const val IDLE_ALPHA = 0.55f
        private const val IDLE_PULSE_AMPLITUDE = 0.12f
        private const val BLUE = 0xFF4B9EFF.toInt()
        private const val CYAN = 0xFF9BDEFF.toInt()
        private const val GLOW_BLUE = 0xA0358DFF.toInt()
        private const val FLOW_VIOLET = 0xFFAEABFF.toInt()
        private const val ACTIVE_PULSE_MS = 2400L
        private const val IDLE_PULSE_MS = 3600L
        private const val FEEDBACK_FADE_MS = 650L
        private const val MAX_ALPHA = 255
        private const val HALO_ALPHA = 45
        private const val TRAIL_ALPHA = 90
        private const val TRAIL_SAMPLES = 12
        private const val TRAIL_SPACING = 0.025f
    }
}
