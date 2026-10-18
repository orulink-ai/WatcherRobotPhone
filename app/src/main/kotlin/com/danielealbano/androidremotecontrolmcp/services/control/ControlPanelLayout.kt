package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** Measures a bottom-centred control bar and retains user drag positions across layout changes. */
internal class ControlPanelLayout(
    private val context: Context,
    private val windowManager: WindowManager,
    private val panel: ControlPanelView,
) {
    private var preferredPosition: PanelPosition? = null

    fun create(
        expanded: Boolean,
        touching: Boolean,
        current: WindowManager.LayoutParams?,
    ): WindowManager.LayoutParams {
        val (availableWidth, availableHeight) = availableArea()
        panel.setExpanded(expanded)
        panel.limitHeight(null)
        var width = panelWidth(availableWidth, expanded)
        measurePanel(width)
        val fitsExpanded = availableHeight >= panel.minimumExpandedHeight
        if (expanded && !fitsExpanded) {
            panel.setExpanded(false)
            width = panelWidth(availableWidth, false)
            measurePanel(width)
        }
        if (expanded && fitsExpanded) {
            panel.limitHeight(availableHeight)
            measurePanel(width)
        }
        val position = position(availableWidth, availableHeight, width, current.takeIf { touching })
        return controlOverlayLayoutParams(width, panel.measuredHeight).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = position.x
            y = position.y
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            title = ControlOverlayController.PANEL_WINDOW_TITLE
        }
    }

    fun move(
        position: PanelPosition,
        params: WindowManager.LayoutParams,
    ) {
        val (width, height) = availableArea()
        params.x = position.x.coerceIn(0, (width - panel.width).coerceAtLeast(0))
        params.y = position.y.coerceIn(0, (height - panel.height).coerceAtLeast(0))
        preferredPosition = PanelPosition(params.x, params.y)
    }

    fun dock(params: WindowManager.LayoutParams) {
        preferredPosition = PanelPosition(params.x, params.y)
    }

    fun hasSameGeometry(
        previous: WindowManager.LayoutParams?,
        current: WindowManager.LayoutParams,
    ): Boolean = previous?.let(::bounds) == bounds(current)

    private fun panelWidth(
        availableWidth: Int,
        expanded: Boolean,
    ): Int =
        minOf(
            if (expanded) dp(PANEL_WIDTH_DP) else panel.preferredCompactWidth,
            (availableWidth - dp(PANEL_MARGIN_DP * 2)).coerceAtLeast(1),
        )

    private fun measurePanel(width: Int) {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
    }

    private fun availableArea(): Pair<Int, Int> {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        return Pair(
            (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1),
            (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1),
        )
    }

    private fun position(
        availableWidth: Int,
        availableHeight: Int,
        width: Int,
        current: WindowManager.LayoutParams?,
    ): PanelPosition =
        if (current != null) {
            PanelPosition(
                current.x.coerceIn(0, (availableWidth - width).coerceAtLeast(0)),
                current.y.coerceIn(0, (availableHeight - panel.measuredHeight).coerceAtLeast(0)),
            )
        } else {
            placeBottomControlPanel(
                availableWidth,
                availableHeight,
                width,
                panel.measuredHeight,
                dp(PANEL_MARGIN_DP),
                preferredPosition,
            )
        }

    private fun bounds(params: WindowManager.LayoutParams): Rect =
        Rect(params.x, params.y, params.x + params.width, params.y + params.height)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val PANEL_WIDTH_DP = 344
        private const val PANEL_MARGIN_DP = 12
    }
}

internal fun controlOverlayLayoutParams(
    width: Int,
    height: Int,
): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )
