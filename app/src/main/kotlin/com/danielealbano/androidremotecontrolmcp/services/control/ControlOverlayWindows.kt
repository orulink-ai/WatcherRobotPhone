package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.CoroutineScope

/** Owns overlay attachment, layout, visibility, and hit testing. Accessed only on Main. */
@Suppress("TooManyFunctions")
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
    private var panelPassThrough = false
    private var gesturePassThrough = false
    private var hiddenCallback: (() -> Unit)? = null
    private var lifecycleVisible = true
    private var controlsVisible = true
    private val panelPresence = ControlOverlayPresence()
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
        effects.onPresenceFrame = { frame ->
            val panelFrame =
                panelPresence.sample(
                    SystemClock.uptimeMillis(),
                    controlsVisible,
                    ValueAnimator.areAnimatorsEnabled(),
                )
            panel.alpha = panelFrame.progress
            val retreat = PANEL_RETRACT_DP * context.resources.displayMetrics.density
            panel.translationY = retreat * (1f - panelFrame.progress)
            if (panelFrame.animating) effects.postInvalidateOnAnimation()
            val edgesRetracted = !frame.animating && frame.progress == 0f
            val panelRetracted = !panelFrame.animating && panelFrame.progress == 0f
            if (edgesRetracted && panelRetracted) {
                hiddenCallback?.let { complete ->
                    hiddenCallback = null
                    effects.post { if (!lifecycleVisible) complete() }
                }
            }
        }
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

    fun attach(initiallyVisible: Boolean = true) {
        if (attached) return
        runCatching {
            lifecycleVisible = initiallyVisible
            controlsVisible = initiallyVisible
            effects.setControlVisible(initiallyVisible, immediately = true)
            panelPresence.snap(initiallyVisible)
            panel.alpha = if (initiallyVisible) 1f else 0f
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
        hiddenCallback = null
        attached = false
        touching = false
        expansion.collapse()
        effects.clearFeedback()
        effects.isActive = false
        removeView(effects)
        removeView(panel)
        panelParams = null
        panelPassThrough = false
    }

    fun setModeVisible(
        visible: Boolean,
        keepControls: Boolean = false,
        onHidden: (() -> Unit)? = null,
    ) {
        lifecycleVisible = visible
        controlsVisible = visible || keepControls
        hiddenCallback = if (visible) null else onHidden
        if (!attached) {
            if (!visible) onHidden?.invoke()
            return
        }
        effects.setControlVisible(visible)
        updateInteraction(gesturePassThrough)
    }

    fun panelBounds(): Rect? {
        if (!attached || panel.width <= 0 || panel.height <= 0) return null
        val location = IntArray(2)
        panel.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + panel.width, location[1] + panel.height)
    }

    private fun removeView(view: View) {
        if (view.isAttachedToWindow) runCatching { windowManager.removeView(view) }
    }

    fun updateInteraction(panelAvoided: Boolean) {
        // Captures contain the actual visible display. Only local touch ownership changes during input.
        effects.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        gesturePassThrough = panelAvoided
        panelPassThrough = panelAvoided || !controlsVisible
        updatePanelTouchFlags()
    }

    private fun updatePanelTouchFlags() {
        val params = panelParams ?: return
        val flags =
            if (panelPassThrough) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
        if (params.flags != flags && attached) {
            params.flags = flags
            windowManager.updateViewLayout(panel, params)
        }
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
        if (panelPassThrough) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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
        private const val PANEL_RETRACT_DP = 18f
    }
}
