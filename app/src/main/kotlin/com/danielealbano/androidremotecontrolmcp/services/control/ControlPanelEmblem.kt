package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** A small outlined AI sparkle; decorative rather than an extra pause button. */
internal class ControlPanelEmblem(
    context: Context,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ControlPanelTokens.TEXT.toInt()
            style = Paint.Style.STROKE
            strokeWidth = STROKE_WIDTH
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / VIEWPORT, height / VIEWPORT)
        sparkle(canvas, MAIN_X, MAIN_Y, MAIN_RADIUS)
        sparkle(canvas, SMALL_X, SMALL_Y, SMALL_RADIUS)
        sparkle(canvas, SMALL_X, TINY_Y, TINY_RADIUS)
        canvas.restore()
    }

    private fun sparkle(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
    ) {
        val neck = radius * NECK_RATIO
        path.rewind()
        path.moveTo(x, y - radius)
        path.cubicTo(x + neck, y - neck, x + neck, y - neck, x + radius, y)
        path.cubicTo(x + neck, y + neck, x + neck, y + neck, x, y + radius)
        path.cubicTo(x - neck, y + neck, x - neck, y + neck, x - radius, y)
        path.cubicTo(x - neck, y - neck, x - neck, y - neck, x, y - radius)
        path.close()
        canvas.drawPath(path, paint)
    }

    private companion object {
        const val VIEWPORT = 24f
        const val STROKE_WIDTH = 1.5f
        const val NECK_RATIO = 0.24f
        const val MAIN_X = 10f
        const val MAIN_Y = 13f
        const val MAIN_RADIUS = 7f
        const val SMALL_X = 19f
        const val SMALL_Y = 5f
        const val SMALL_RADIUS = 2.5f
        const val TINY_Y = 20f
        const val TINY_RADIUS = 1.5f
    }
}
