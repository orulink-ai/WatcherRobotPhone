package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coordinates session feedback, gesture lifetimes, and local takeover. Main thread only. */
internal class ControlOverlayController(
    context: Context,
    private val session: ControlSession,
    private val scope: CoroutineScope,
    private val onStop: () -> Unit,
) {
    private val windows = ControlOverlayWindows(context, ::toggleTakeover, ::stop)
    private var enabled = true
    private var captureDepth = 0
    private var idleDismiss: Job? = null
    private val gestures = mutableMapOf<Long, Boolean>()
    private var nextGesture = 0L
    private var stopping = false
    private var stopped = false
    private val observer = scope.launch { session.state.collect { refresh(it) } }

    fun setEnabled(value: Boolean) {
        enabled = value
        refresh()
    }

    suspend fun prepareGesture(gesture: GestureDescription): Long {
        val token = ++nextGesture
        val avoidsPanel = windows.attached && windows.intersectsPanel(gesture)
        gestures[token] = avoidsPanel
        windows.updateVisibility(captureDepth > 0, session.state.value.isPaused, gestures.values.any { it })
        try {
            if (avoidsPanel) delay(CAPTURE_SETTLE_MS)
        } catch (error: CancellationException) {
            finishGesture(token)
            throw error
        }
        return token
    }

    fun finishGesture(token: Long) {
        if (gestures.remove(token) == null) return
        if (stopping && gestures.isEmpty()) completeStop() else refresh()
    }

    fun showGesture(gesture: GestureDescription) {
        if (windows.attached && !session.state.value.isPaused) windows.effects.showGesture(gesture)
    }

    fun showTarget(bounds: Rect) {
        if (windows.attached && !session.state.value.isPaused) windows.effects.showTarget(bounds)
    }

    suspend fun <T> withoutVisuals(block: suspend () -> T): T {
        captureDepth++
        windows.updateVisibility(captureDepth > 0, session.state.value.isPaused, gestures.values.any { it })
        return try {
            // Let SurfaceFlinger consume the hidden frame before starting the capture.
            delay(CAPTURE_SETTLE_MS)
            block()
        } finally {
            captureDepth--
            windows.updateVisibility(captureDepth > 0, session.state.value.isPaused, gestures.values.any { it })
        }
    }

    fun destroy() {
        observer.cancel()
        idleDismiss?.cancel()
        windows.detach()
        gestures.clear()
    }

    fun refresh(state: ControlSessionState = session.state.value) {
        idleDismiss?.cancel()
        if (state.lastTool == null && !state.isPaused) {
            stopping = false
            stopped = false
        }
        if (!enabled || stopped || state.lastTool == null) {
            windows.detach()
            return
        }
        windows.attach()
        windows.panel.render(state, gestures.isNotEmpty(), stopping)
        windows.effects.isActive = state.isActive
        if (state.isPaused) windows.effects.clearFeedback()
        windows.updateVisibility(captureDepth > 0, session.state.value.isPaused, gestures.values.any { it })
        if (!state.isActive && !state.isPaused && gestures.isEmpty()) {
            idleDismiss =
                scope.launch {
                    delay(IDLE_DISMISS_MS)
                    windows.detach()
                }
        }
    }

    private fun toggleTakeover() {
        if (stopping) return
        if (session.state.value.isPaused) {
            if (gestures.isEmpty()) session.resume()
        } else {
            session.pause()
        }
    }

    private fun stop() {
        if (stopping) return
        stopping = true
        session.pause()
        if (gestures.isEmpty()) completeStop() else refresh()
    }

    private fun completeStop() {
        stopped = true
        windows.detach()
        runCatching { onStop() }.onFailure {
            Log.w(TAG, "Could not stop remote control", it)
            stopping = false
            stopped = false
            refresh()
        }
    }

    companion object {
        const val EFFECTS_WINDOW_TITLE = "Orulink control effects"
        const val PANEL_WINDOW_TITLE = "Orulink control panel"
        private const val TAG = "MCP:ControlOverlay"
        private const val IDLE_DISMISS_MS = 8000L
        private const val CAPTURE_SETTLE_MS = 40L

        fun isControlWindow(title: CharSequence?): Boolean =
            when (title) {
                EFFECTS_WINDOW_TITLE, PANEL_WINDOW_TITLE -> true
                else -> false
            }
    }
}
