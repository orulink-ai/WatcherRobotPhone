package com.danielealbano.androidremotecontrolmcp.services.control

import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEdgeColorMode
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig

/** Cached color and alpha fields. Frames only move matrices; shaders rebuild on palette or size changes. */
internal class ControlEdgeShaders(
    private val side: ControlEdgeSide,
    private val width: Float,
    private val height: Float,
    config: ControlEffectsConfig,
) {
    private val vertical = side == ControlEdgeSide.LEFT || side == ControlEdgeSide.RIGHT
    private val matrix = Matrix()
    private val cycleLength = (if (vertical) height else width) * PALETTE_LENGTH_SCALE
    private val colors = palette(config)
    private val colorField =
        LinearGradient(
            0f,
            0f,
            if (vertical) 0f else cycleLength,
            if (vertical) cycleLength else 0f,
            colors,
            null,
            Shader.TileMode.REPEAT,
        )
    private val fade =
        LinearGradient(0f, 0f, 1f, 0f, FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP)
    private val focus =
        RadialGradient(0f, 0f, 1f, FOCUS_COLORS, FOCUS_STOPS, Shader.TileMode.CLAMP)
    val base: Shader = ComposeShader(colorField, fade, PorterDuff.Mode.DST_IN)
    val highlight: Shader = ComposeShader(colorField, focus, PorterDuff.Mode.DST_IN)

    fun moveColor(time: Long) {
        val progress = (time % COLOR_PERIOD_MS).toFloat() / COLOR_PERIOD_MS
        val shift = cycleLength * (progress + side.ordinal * EDGE_COLOR_PHASE)
        matrix.setTranslate(if (vertical) 0f else -shift, if (vertical) -shift else 0f)
        colorField.setLocalMatrix(matrix)
    }

    fun placeBase(depth: Float) {
        // Unit fade always runs along x; rotate it onto the inward normal of this edge.
        when (side) {
            ControlEdgeSide.LEFT -> {
                matrix.setScale(depth, 1f)
            }

            ControlEdgeSide.RIGHT -> {
                matrix.setScale(-depth, 1f)
                matrix.postTranslate(width, 0f)
            }

            ControlEdgeSide.TOP -> {
                matrix.setRotate(RIGHT_ANGLE)
                matrix.postScale(1f, depth)
            }

            ControlEdgeSide.BOTTOM -> {
                matrix.setRotate(-RIGHT_ANGLE)
                matrix.postScale(1f, depth)
                matrix.postTranslate(0f, height)
            }
        }
        fade.setLocalMatrix(matrix)
    }

    fun placeFocus(
        x: Float,
        y: Float,
        radiusX: Float,
        radiusY: Float,
    ) {
        matrix.setScale(radiusX, radiusY)
        matrix.postTranslate(x, y)
        focus.setLocalMatrix(matrix)
    }

    companion object {
        private const val PALETTE_LENGTH_SCALE = 1.65f
        private const val EDGE_COLOR_PHASE = 0.21f
        private const val COLOR_PERIOD_MS = 24000L
        private const val RIGHT_ANGLE = 90f
        private val FADE_COLORS = intArrayOf(0xF5FFFFFF.toInt(), 0xC2FFFFFF.toInt(), 0x50FFFFFF, Color.TRANSPARENT)
        private val FADE_STOPS = floatArrayOf(0f, 0.14f, 0.52f, 1f)
        private val FOCUS_COLORS = intArrayOf(Color.WHITE, 0xE0FFFFFF.toInt(), 0x60FFFFFF, Color.TRANSPARENT)
        private val FOCUS_STOPS = floatArrayOf(0f, 0.28f, 0.64f, 1f)

        @Suppress("MagicNumber")
        private fun palette(config: ControlEffectsConfig): IntArray =
            when (config.colorMode) {
                ControlEdgeColorMode.BLUE -> {
                    intArrayOf(0xFF1769FF.toInt(), 0xFF3380FF.toInt(), 0xFF1769FF.toInt())
                }

                ControlEdgeColorMode.CUSTOM -> {
                    intArrayOf(config.customColorArgb, config.customColorArgb)
                }

                ControlEdgeColorMode.MULTICOLOR -> {
                    intArrayOf(
                        0xFF2870FF.toInt(),
                        0xFF13C9E2.toInt(),
                        0xFF6C4EFF.toInt(),
                        0xFFE446CB.toInt(),
                        0xFFFF725D.toInt(),
                        0xFF2870FF.toInt(),
                    )
                }
            }
    }
}
