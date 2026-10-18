package com.danielealbano.androidremotecontrolmcp.services.control

internal data class CursorPoint(
    val x: Float,
    val y: Float,
)

internal data class CursorSize(
    val width: Float,
    val height: Float,
)

internal data class CursorBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** Places the label around an unchanged action coordinate, including near display edges. */
internal object ControlCursorGeometry {
    fun placeLabel(
        point: CursorPoint,
        requestedSize: CursorSize,
        viewport: CursorSize,
        gap: CursorSize = CursorSize(LABEL_GAP_X, LABEL_GAP_Y),
        margin: Float = DISPLAY_MARGIN,
    ): CursorBox {
        val insetX = margin.coerceAtMost(viewport.width.coerceAtLeast(0f) / 2f)
        val insetY = margin.coerceAtMost(viewport.height.coerceAtLeast(0f) / 2f)
        val width = requestedSize.width.coerceIn(0f, (viewport.width - 2f * insetX).coerceAtLeast(0f))
        val height = requestedSize.height.coerceIn(0f, (viewport.height - 2f * insetY).coerceAtLeast(0f))
        val right = viewport.width - insetX
        val bottom = viewport.height - insetY
        val preferredX = if (point.x + gap.width + width <= right) point.x + gap.width else point.x - gap.width - width
        val preferredY =
            if (point.y + gap.height + height <= bottom) {
                point.y + gap.height
            } else {
                point.y - gap.height - height
            }
        return CursorBox(
            preferredX.coerceIn(insetX, (right - width).coerceAtLeast(insetX)),
            preferredY.coerceIn(insetY, (bottom - height).coerceAtLeast(insetY)),
            width,
            height,
        )
    }

    private const val LABEL_GAP_X = 12f
    private const val LABEL_GAP_Y = 33f
    private const val DISPLAY_MARGIN = 8f
}
