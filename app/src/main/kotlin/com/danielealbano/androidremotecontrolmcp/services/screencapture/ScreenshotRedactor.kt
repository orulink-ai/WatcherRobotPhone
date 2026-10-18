package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import javax.inject.Inject

/**
 * Paints opaque black boxes over the bounds of flagged accessibility nodes before a screenshot is
 * encoded — the only way to hide PII in the pixels (screenshots cannot be pseudonymized). Bounds are
 * in screen coordinates and scaled to the (resized) bitmap.
 */
class ScreenshotRedactor
    @Inject
    constructor() {
        fun mask(
            bitmap: Bitmap,
            bounds: List<BoundsData>,
            screenWidth: Int,
            screenHeight: Int,
        ): Bitmap {
            if (bounds.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return bitmap
            val copy =
                checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true)) {
                    "Failed to create a mutable copy of the screenshot bitmap"
                }
            val scaleX = copy.width.toFloat() / screenWidth
            val scaleY = copy.height.toFloat() / screenHeight
            val canvas = Canvas(copy)
            val paint =
                Paint().apply {
                    color = Color.BLACK
                    style = Paint.Style.FILL
                }
            for (rect in computeMaskRects(bounds, scaleX, scaleY, copy.width, copy.height)) {
                canvas.drawRect(RectF(rect.left, rect.top, rect.right, rect.bottom), paint)
            }
            return copy
        }

        /** A scaled, padded, clamped mask rectangle in bitmap coordinates. Pure data (no Android types). */
        internal data class MaskRect(
            val left: Float,
            val top: Float,
            val right: Float,
            val bottom: Float,
        )

        internal fun computeMaskRects(
            bounds: List<BoundsData>,
            scaleX: Float,
            scaleY: Float,
            bitmapWidth: Int,
            bitmapHeight: Int,
        ): List<MaskRect> =
            bounds.mapNotNull { box ->
                val left = (box.left * scaleX - MASK_PADDING_PX).coerceIn(0f, bitmapWidth.toFloat())
                val top = (box.top * scaleY - MASK_PADDING_PX).coerceIn(0f, bitmapHeight.toFloat())
                val right = (box.right * scaleX + MASK_PADDING_PX).coerceIn(0f, bitmapWidth.toFloat())
                val bottom = (box.bottom * scaleY + MASK_PADDING_PX).coerceIn(0f, bitmapHeight.toFloat())
                if (right > left && bottom > top) MaskRect(left, top, right, bottom) else null
            }

        companion object {
            private const val MASK_PADDING_PX = 2f
        }
    }
