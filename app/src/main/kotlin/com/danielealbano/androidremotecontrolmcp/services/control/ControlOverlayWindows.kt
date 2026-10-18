package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.CoroutineScope

/** Owns overlay attachment, layout, visibility, and hit testing. Accessed only on Main. */
internal class ControlOverlayWindows(
    context: Context,
    scope: CoroutineScope,
    onTakeover: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val effects = ControlEffectsView(context)
    val panel = ControlPanelView(context)
    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    private val panelLayout = ControlPanelLayout(context, windowManager, panel)
    private val hitSlop = (PANEL_HIT_SLOP_DP * context.resources.displayMetrics.density).toInt().toFloat()
    private var panelParams: WindowManager.LayoutParams? = null
    private var touching = false
    private val expansion =
        ControlPanelExpansion(scope, {
            !accessibilityManager.isTouchExplorationEnabled
        }) { expanded ->
            panel.setExpanded(expanded)
            bindDragHandle()
            relayoutPanel()
        }
    private val drag =
        ControlPanelDrag(
            ViewConfiguration.get(context).scaledTouchSlop,
            { PanelPosition(panelParams?.x ?: 0, panelParams?.y ?: 0) },
            ::movePanel,
            ::dockPanel,
        )
    var attached = false
        private set

    init {
        panel.setActions(
            { if (expansion.isExpanded) expansion.collapse() else expansion.expand() },
            onTakeover,
            onPause,
            onStop,
        )
        bindDragHandle()
        panel.onTouchInteraction = { pressed ->
            touching = pressed
            if (pressed) {
                expansion.beginInteraction()
            } else {
                expansion.endInteraction()
                relayoutPanel()
            }
        }
        panel.onConfigurationUpdate = { panel.post { relayoutPanel() } }
        panel.setOnApplyWindowInsetsListener { _, insets ->
            panel.post { relayoutPanel() }
            insets
        }
    }

    private fun bindDragHandle() {
        panel.dragHandle.setOnTouchListener(drag)
    }

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
            bounds.inset(-hitSlop, -hitSlop)
            RectF.intersects(panelBounds, bounds)
        }
    }

    fun attach() {
        if (attached) return
        runCatching {
            windowManager.addView(effects, effectsLayout())
            val params = panelLayout.create(expansion.isExpanded, touching, panelParams)
            bindDragHandle()
            panelParams = params
            windowManager.addView(panel, params)
            attached = true
        }.onFailure {
            removeView(effects)
            removeView(panel)
            Log.w(TAG, "Could not display control overlay", it)
        }
    }

    fun detach() {
        attached = false
        touching = false
        expansion.collapse()
        effects.clearFeedback()
        effects.isActive = false
        removeView(effects)
        removeView(panel)
        panelParams = null
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
        controlOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.TOP or Gravity.START
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = ControlOverlayController.EFFECTS_WINDOW_TITLE
        }

    fun renderPanel(
        state: ControlSessionState,
        waitingForGesture: Boolean,
        stopping: Boolean,
    ) {
        panel.render(state, waitingForGesture, stopping)
        relayoutPanel()
    }

    private fun relayoutPanel() {
        if (!attached) return
        val params = panelLayout.create(expansion.isExpanded, touching, panelParams)
        bindDragHandle()
        if (panelLayout.hasSameGeometry(panelParams, params)) return
        panelParams = params
        windowManager.updateViewLayout(panel, params)
    }

    private fun movePanel(position: PanelPosition) {
        val params = panelParams ?: return
        panelLayout.move(position, params)
        windowManager.updateViewLayout(panel, params)
    }

    private fun dockPanel() {
        val params = panelParams ?: return
        panelLayout.dock(params)
        relayoutPanel()
    }

    companion object {
        private const val TAG = "MCP:ControlOverlay"
        private const val PANEL_HIT_SLOP_DP = 8
    }
}
