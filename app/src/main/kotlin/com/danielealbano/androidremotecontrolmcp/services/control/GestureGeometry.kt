package com.danielealbano.androidremotecontrolmcp.services.control

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF

internal data class GestureGeometry(
    val measure: PathMeasure,
    val bounds: RectF,
    val originX: Float,
    val originY: Float,
)

/** Uses the platform's tap-path probing technique without changing the injected path. */
internal fun measureGesturePath(path: Path): GestureGeometry {
    val measure = PathMeasure(path, false)
    val origin = FloatArray(2)
    val bounds = RectF()
    if (measure.length == 0f) {
        val probe = Path(path).apply { lineTo(-1f, -1f) }
        PathMeasure(probe, false).getPosTan(0f, origin, null)
        bounds.set(origin[0], origin[1], origin[0], origin[1])
    } else {
        measure.getPosTan(0f, origin, null)
        @Suppress("DEPRECATION")
        path.computeBounds(bounds, true)
    }
    return GestureGeometry(measure, bounds, origin[0], origin[1])
}
