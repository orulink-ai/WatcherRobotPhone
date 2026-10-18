package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue

/** Independently drawn reference-style pointer and compact action label; no touch surface. */
internal class ControlCursorPainter(
    private val context: Context,
) {
    private val density = context.resources.displayMetrics.density
    private val labels = ControlCursorLabels(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint =
        android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    TEXT_SIZE_SP,
                    context.resources.displayMetrics,
                )
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
    private val labelBounds = RectF()

    // Independent vector artwork. Coordinates describe the arrow silhouette, not layout constants.
    @Suppress("MagicNumber")
    private val arrow =
        Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 22f)
            lineTo(5.7f, 16.8f)
            lineTo(10.1f, 25f)
            lineTo(14.2f, 22.8f)
            lineTo(9.8f, 14.9f)
            lineTo(18f, 14.2f)
            close()
        }

    fun draw(
        canvas: Canvas,
        point: CursorPoint,
        viewport: CursorSize,
        visual: ControlCursorVisual,
        showLabel: Boolean,
    ) {
        if (showLabel) drawLabel(canvas, point, viewport, visual)
        visual.clickEffect?.let { drawClickRing(canvas, point, visual.alpha, it) }
        drawArrow(canvas, point, viewport, visual.alpha, visual.clickEffect?.scale ?: 1f)
    }

    private fun drawArrow(
        canvas: Canvas,
        point: CursorPoint,
        viewport: CursorSize,
        alpha: Float,
        pressScale: Float,
    ) {
        val save = canvas.save()
        canvas.translate(point.x, point.y)
        // Keep the tip at the dispatched coordinate while turning the body inward at edges.
        val scaleX = if (point.x + POINTER_WIDTH_DP * density > viewport.width) -density else density
        val scaleY = if (point.y + POINTER_HEIGHT_DP * density > viewport.height) -density else density
        canvas.scale(scaleX * pressScale, scaleY * pressScale)
        paint.shader = null
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = POINTER_OUTLINE_DP
        paint.color = Color.WHITE
        paint.alpha = (alpha * MAX_ALPHA).toInt()
        canvas.drawPath(arrow, paint)
        paint.style = Paint.Style.FILL
        paint.color = INK
        paint.alpha = (alpha * MAX_ALPHA).toInt()
        canvas.drawPath(arrow, paint)
        canvas.restoreToCount(save)
    }

    private fun drawClickRing(
        canvas: Canvas,
        point: CursorPoint,
        alpha: Float,
        effect: ControlCursorClickEffect,
    ) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = CLICK_STROKE_DP * density
        paint.color = CLICK_COLOR
        paint.alpha = ((1f - effect.progress) * alpha * CLICK_ALPHA).toInt()
        canvas.drawCircle(point.x, point.y, (CLICK_RADIUS_DP + CLICK_EXPANSION_DP * effect.progress) * density, paint)
    }

    private fun drawLabel(
        canvas: Canvas,
        point: CursorPoint,
        viewport: CursorSize,
        visual: ControlCursorVisual,
    ) {
        val alpha = visual.alpha
        val text = labels.text(visual.action, visual.phase)
        val metrics = textPaint.fontMetrics
        val labelHeight = maxOf(LABEL_HEIGHT_DP * density, metrics.descent - metrics.ascent + TEXT_PADDING_DP * density)
        val size = CursorSize(textPaint.measureText(text) + 2f * LABEL_PADDING_DP * density, labelHeight)
        val box =
            ControlCursorGeometry.placeLabel(
                point,
                size,
                viewport,
                CursorSize(LABEL_GAP_DP * density, (POINTER_HEIGHT_DP + LABEL_VERTICAL_GAP_DP) * density),
                MARGIN_DP * density,
            )
        labelBounds.set(box.left, box.top, box.left + box.width, box.top + box.height)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = SHADOW
        paint.alpha = (alpha * SHADOW_ALPHA).toInt()
        canvas.save()
        canvas.translate(0f, SHADOW_OFFSET_DP * density)
        canvas.drawRoundRect(labelBounds, LABEL_RADIUS_DP * density, LABEL_RADIUS_DP * density, paint)
        canvas.restore()
        paint.color = Color.WHITE
        paint.alpha = (alpha * LABEL_ALPHA).toInt()
        canvas.drawRoundRect(labelBounds, LABEL_RADIUS_DP * density, LABEL_RADIUS_DP * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = LABEL_OUTLINE_DP * density
        paint.color = LABEL_OUTLINE
        paint.alpha = (alpha * MAX_ALPHA).toInt()
        canvas.drawRoundRect(labelBounds, LABEL_RADIUS_DP * density, LABEL_RADIUS_DP * density, paint)
        drawLabelText(canvas, text, box, alpha)
    }

    private fun drawLabelText(
        canvas: Canvas,
        text: String,
        box: CursorBox,
        alpha: Float,
    ) {
        val available = (box.width - 2f * LABEL_PADDING_DP * density).coerceAtLeast(0f)
        val displayText = TextUtils.ellipsize(text, textPaint, available, TextUtils.TruncateAt.END).toString()
        val metrics = textPaint.fontMetrics
        textPaint.alpha = (alpha * MAX_ALPHA).toInt()
        canvas.drawText(
            displayText,
            box.left + LABEL_PADDING_DP * density,
            box.top + box.height / 2f - (metrics.ascent + metrics.descent) / 2f,
            textPaint,
        )
    }

    companion object {
        private const val POINTER_WIDTH_DP = 18f
        private const val POINTER_HEIGHT_DP = 25f
        private const val POINTER_OUTLINE_DP = 2.2f
        private const val TEXT_SIZE_SP = 13f
        private const val TEXT_PADDING_DP = 10f
        private const val LABEL_PADDING_DP = 10f
        private const val LABEL_HEIGHT_DP = 30f
        private const val LABEL_RADIUS_DP = 15f
        private const val LABEL_GAP_DP = 12f
        private const val LABEL_VERTICAL_GAP_DP = 8f
        private const val LABEL_OUTLINE_DP = 0.5f
        private const val MARGIN_DP = 8f
        private const val SHADOW_OFFSET_DP = 2f
        private const val INK = 0xFF252A32.toInt()
        private const val SHADOW = 0xFF0D1728.toInt()
        private const val LABEL_OUTLINE = 0xFFE5E7EB.toInt()
        private const val MAX_ALPHA = 255
        private const val LABEL_ALPHA = 248
        private const val SHADOW_ALPHA = 18
        private const val CLICK_RADIUS_DP = 10f
        private const val CLICK_EXPANSION_DP = 22f
        private const val CLICK_STROKE_DP = 1.4f
        private const val CLICK_COLOR = 0xFF87C6FF.toInt()
        private const val CLICK_ALPHA = 190
    }
}
