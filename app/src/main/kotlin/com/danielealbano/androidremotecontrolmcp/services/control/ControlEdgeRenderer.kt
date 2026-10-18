package com.danielealbano.androidremotecontrolmcp.services.control

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import kotlin.math.min

/** Four independently shaped inward glows. Never participates in touch input. */
internal class ControlEdgeRenderer(
    private val density: Float,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activity = ControlEdgeActivity()
    private val clock = ControlEdgeClock()
    private val frame = RectF()
    private val clip = Path()
    private var config = ControlEffectsConfig()
    private var width = 0f
    private var height = 0f
    private var reveal = 1f
    private var shaders: List<ControlEdgeShaders> = emptyList()

    fun updateConfig(value: ControlEffectsConfig) {
        val next = value.normalized()
        val paletteChanged = next.colorMode != config.colorMode || next.customColorArgb != config.customColorArgb
        config = next
        if (paletteChanged && width > 0f && height > 0f) rebuildShaders()
    }

    fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = width.toFloat()
        this.height = height.toFloat()
        frame.set(
            FRAME_INSET_DP * density,
            FRAME_INSET_DP * density,
            this.width - FRAME_INSET_DP * density,
            this.height - FRAME_INSET_DP * density,
        )
        clip.reset()
        clip.addRoundRect(frame, FRAME_RADIUS_DP * density, FRAME_RADIUS_DP * density, Path.Direction.CW)
        if (width > 0 && height > 0) rebuildShaders() else shaders = emptyList()
    }

    fun draw(
        canvas: Canvas,
        now: Long,
        active: Boolean,
        animate: Boolean,
        visibility: Float = 1f,
    ) {
        reveal = if (visibility.isFinite()) visibility.coerceIn(0f, 1f) else 0f
        if (shaders.isEmpty() || reveal <= 0f) return
        val save = canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        val strength = activity.sample(now, active, animate)
        val time = clock.sample(now, config.speed, animate)
        for (side in ControlEdgeSide.entries) {
            val light = ControlEdgeMotion.sample(side, time, strength, animate, config.motionRange)
            val fields = shaders[side.ordinal]
            fields.moveColor(time)
            drawBase(canvas, side, light, fields)
            drawFocus(canvas, side, light, fields)
        }
        paint.shader = null
        canvas.restoreToCount(save)
    }

    private fun rebuildShaders() {
        shaders = ControlEdgeSide.entries.map { ControlEdgeShaders(it, width, height, config) }
    }

    private fun depth(
        light: ControlEdgeLight,
        focus: Boolean = false,
    ): Float =
        min(
            config.glowWidthDp * density * light.widthScale * if (focus) FOCUS_DEPTH_SCALE else 1f,
            min(width, height) * MAX_INWARD_FRACTION,
        ) * reveal

    private fun drawBase(
        canvas: Canvas,
        side: ControlEdgeSide,
        light: ControlEdgeLight,
        fields: ControlEdgeShaders,
    ) {
        val depth = depth(light)
        fields.placeBase(depth)
        paint.shader = fields.base
        paint.alpha = (light.opacity * config.intensity * BASE_ALPHA * reveal).toInt().coerceIn(0, MAX_ALPHA)
        when (side) {
            ControlEdgeSide.LEFT -> canvas.drawRect(0f, 0f, depth, height, paint)
            ControlEdgeSide.RIGHT -> canvas.drawRect(width - depth, 0f, width, height, paint)
            ControlEdgeSide.TOP -> canvas.drawRect(0f, 0f, width, depth, paint)
            ControlEdgeSide.BOTTOM -> canvas.drawRect(0f, height - depth, width, height, paint)
        }
    }

    private fun drawFocus(
        canvas: Canvas,
        side: ControlEdgeSide,
        light: ControlEdgeLight,
        fields: ControlEdgeShaders,
    ) {
        val vertical = side == ControlEdgeSide.LEFT || side == ControlEdgeSide.RIGHT
        val depth = depth(light, focus = true)
        val reach = (if (vertical) height else width) * light.spread
        val x =
            when (side) {
                ControlEdgeSide.LEFT -> 0f
                ControlEdgeSide.RIGHT -> width
                else -> width * light.focus
            }
        val y =
            when (side) {
                ControlEdgeSide.TOP -> 0f
                ControlEdgeSide.BOTTOM -> height
                else -> height * light.focus
            }
        val radiusX = if (vertical) depth else reach
        val radiusY = if (vertical) reach else depth
        fields.placeFocus(x, y, radiusX, radiusY)
        paint.shader = fields.highlight
        paint.alpha = (light.opacity * config.intensity * MAX_ALPHA * reveal).toInt().coerceIn(0, MAX_ALPHA)
        canvas.drawRect(
            (x - radiusX).coerceAtLeast(0f),
            (y - radiusY).coerceAtLeast(0f),
            (x + radiusX).coerceAtMost(width),
            (y + radiusY).coerceAtMost(height),
            paint,
        )
    }

    companion object {
        private const val FRAME_INSET_DP = 1f
        private const val FRAME_RADIUS_DP = 28f
        private const val FOCUS_DEPTH_SCALE = 1.65f
        private const val MAX_INWARD_FRACTION = 0.32f
        private const val MAX_ALPHA = 255
        private const val BASE_ALPHA = 235
    }
}
