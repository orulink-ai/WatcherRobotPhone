package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private const val MARK_SIZE = 40
private const val RING_RADIUS_FRACTION = 0.23f
private const val LEFT_CENTER_FRACTION = 0.39f
private const val RIGHT_CENTER_FRACTION = 0.61f
private const val STROKE_FRACTION = 0.065f

/** Decorative interlocking rings. The adjacent title provides the brand's accessible name. */
object OrulinkBrandMark {
    @Composable
    operator fun invoke(modifier: Modifier = Modifier) {
        val colors = MaterialTheme.colorScheme
        Canvas(
            modifier =
                modifier
                    .size(MARK_SIZE.dp)
                    .background(colors.primaryContainer, MaterialTheme.shapes.medium),
        ) {
            val ringStyle = Stroke(width = size.minDimension * STROKE_FRACTION, cap = StrokeCap.Round)
            val brush = Brush.linearGradient(listOf(colors.primary, colors.tertiary))
            listOf(LEFT_CENTER_FRACTION, RIGHT_CENTER_FRACTION).forEach { centerFraction ->
                drawCircle(
                    brush = brush,
                    radius = size.minDimension * RING_RADIUS_FRACTION,
                    center = Offset(size.width * centerFraction, center.y),
                    style = ringStyle,
                )
            }
        }
    }
}
