package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** Owns overlay attachment, layout, visibility, and hit testing. Accessed only on Main. */
internal class ControlOverlayWindows(
    private val context: Context,
    onTakeover: () -> Unit,
    onStop: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val effects = ControlEffectsView(context)
    val panel = ControlPanelView(context).apply { setActions(onTakeover, onStop) }
    var attached = false
        private set

    fun intersectsPanel(gesture: GestureDescription): Boolean {
        if (panel.width == 0) return true
        val location = IntArray(2)
        panel.getLocationOnScreen(location)
        val panelBounds =
            RectF(
                location[0].toFloat(),
                location[1].toFloat(),
                (location[0] + panel.width).toFloat(),
                (location[1] + panel.height).toFloat(),
            )
        return (0 until gesture.strokeCount).any { index ->
            val bounds = measureGesturePath(gesture.getStroke(index).path).bounds
            val hitSlop = dp(PANEL_HIT_SLOP_DP).toFloat()
            bounds.inset(-hitSlop, -hitSlop)
            RectF.intersects(panelBounds, bounds)
        }
    }

    fun attach() {
        if (attached) return
        runCatching {
            windowManager.addView(effects, effectsLayout())
            windowManager.addView(panel, panelLayout())
            attached = true
        }.onFailure {
            removeView(effects)
            removeView(panel)
            Log.w(TAG, "Could not display control overlay", it)
        }
    }

    fun detach() {
        effects.clearFeedback()
        effects.isActive = false
        removeView(effects)
        removeView(panel)
        attached = false
    }

    private fun removeView(view: View) {
        if (view.isAttachedToWindow) runCatching { windowManager.removeView(view) }
    }

    fun updateVisibility(
        captureHidden: Boolean,
        paused: Boolean,
        panelAvoided: Boolean,
    ) {
        effects.visibility = if (captureHidden || paused) View.INVISIBLE else View.VISIBLE
        panel.visibility = if (captureHidden || panelAvoided) View.INVISIBLE else View.VISIBLE
    }

    private fun effectsLayout(): WindowManager.LayoutParams =
        layout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.TOP or Gravity.START
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = ControlOverlayController.EFFECTS_WINDOW_TITLE
        }

    private fun panelLayout(): WindowManager.LayoutParams {
        val metrics = windowManager.currentWindowMetrics
        val width = minOf(dp(PANEL_WIDTH_DP), metrics.bounds.width() - dp(PANEL_MARGIN_DP * 2))
        return layout(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(PANEL_MARGIN_DP)
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            title = ControlOverlayController.PANEL_WINDOW_TITLE
        }
    }

    private fun layout(
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

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MCP:ControlOverlay"
        private const val PANEL_HIT_SLOP_DP = 8
        private const val PANEL_WIDTH_DP = 360
        private const val PANEL_MARGIN_DP = 16
    }
}
