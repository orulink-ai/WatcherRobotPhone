package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Coordinates session feedback, gesture lifetimes, and local takeover. Main thread only.
 * Public platform callbacks and private ownership transitions share this small coordinator.
 */
@Suppress("TooManyFunctions")
internal class ControlOverlayController(
    context: Context,
    private val session: ControlSession,
    private val scope: CoroutineScope,
    private val onStop: () -> Unit,
    effectsConfig: Flow<ControlEffectsConfig> = flowOf(ControlEffectsConfig()),
) {
    private val windows = ControlOverlayWindows(context, scope, ::takeOver, ::togglePause, ::stop)
    private var enabled = true
    private var captureDepth = 0
    private val gestures = ControlGestureFeedback()
    private val gestureWatchdog = ControlGestureWatchdog(scope, SystemClock::uptimeMillis, ::expireGesture)
    private var stopping = false
    private var stopped = false
    private val observer = scope.launch { session.state.collect { refresh(it) } }
    private val effectsConfigObserver =
        scope.launch {
            effectsConfig
                .retryWhen { error, attempt ->
                    if (error !is IOException) return@retryWhen false
                    if (attempt == 0L) Log.w(TAG, "Could not read overlay appearance; retrying", error)
                    delay(SETTINGS_RETRY_MS)
                    true
                }.catch { error ->
                    Log.w(TAG, "Could not observe overlay appearance; using defaults", error)
                    emit(ControlEffectsConfig())
                }.collect { config -> windows.effects.config = config.normalized() }
        }

    fun setEnabled(value: Boolean) {
        enabled = value
        refresh()
    }

    suspend fun prepareGesture(gesture: GestureDescription): Long {
        val avoidsPanel = windows.attached && windows.intersectsPanel(gesture)
        val duration =
            (0 until gesture.strokeCount).maxOfOrNull { index ->
                gesture.getStroke(index).run { startTime + this.duration }
            } ?: 0L
        val token = gestures.prepare(avoidsPanel, duration)
        try {
            windows.updateVisibility(captureDepth > 0, session.state.value.isPaused, gestures.avoidsPanel)
            if (avoidsPanel) delay(CAPTURE_SETTLE_MS)
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            runCatching { finishGesture(token, cancelled = true) }
            throw error
        }
        return token
    }

    fun finishGesture(
        token: Long,
        cancelled: Boolean = false,
    ) {
        endGesture(token, gestures.finish(token, cancelled))
    }

    private fun expireGesture(token: Long) {
        runCatching { endGesture(token, gestures.expire(token, SystemClock.uptimeMillis())) }
            .onFailure { Log.w(TAG, "Could not expire gesture feedback", it) }
    }

    private fun endGesture(
        token: Long,
        result: GestureFeedbackEnd?,
    ) {
        if (result == null) return
        gestureWatchdog.cancel(token)
        if (result.clearCursor) windows.effects.clearFeedback()
        if (stopping && !gestures.hasGestures) completeStop() else refresh()
    }

    fun showGesture(
        token: Long,
        gesture: GestureDescription,
    ) {
        val deadline = gestures.dispatched(token, SystemClock.uptimeMillis()) ?: return
        gestureWatchdog.schedule(token, deadline)
        val state = session.state.value
        if (windows.attached && !state.isPaused) {
            windows.effects.updateAction(state.activeTool ?: state.lastTool)
            windows.effects.showGesture(gesture)
        }
    }

    fun showTarget(bounds: Rect) {
        val state = session.state.value
        if (windows.attached && !state.isPaused) {
            gestures.showTarget()
            windows.effects.updateAction(state.activeTool ?: state.lastTool)
            windows.effects.showTarget(bounds)
        }
    }

    suspend fun <T> withoutVisuals(block: suspend () -> T): T {
        captureDepth++
        windows.updateVisibility(captureDepth > 0, session.state.value.isPaused, gestures.avoidsPanel)
        return try {
            // Let SurfaceFlinger consume the hidden frame before starting the capture.
            delay(CAPTURE_SETTLE_MS)
            block()
        } finally {
            captureDepth--
            windows.updateVisibility(captureDepth > 0, session.state.value.isPaused, gestures.avoidsPanel)
        }
    }

    fun destroy() {
        observer.cancel()
        effectsConfigObserver.cancel()
        gestureWatchdog.cancelAll()
        windows.detach()
        gestures.clear()
    }

    fun refresh(state: ControlSessionState = session.state.value) {
        if (state.lastTool == null && !state.isPaused) {
            stopping = false
            stopped = false
        }
        if (!enabled || stopped || state.lastTool == null) {
            windows.detach()
            return
        }
        windows.attach()
        windows.renderPanel(state, gestures.hasGestures, stopping)
        windows.effects.isActive = state.isActive
        if (state.isPaused) windows.effects.clearFeedback()
        windows.effects.updateAction(if (state.isPaused) null else state.activeTool ?: state.lastTool)
        windows.updateVisibility(captureDepth > 0, session.state.value.isPaused, gestures.avoidsPanel)
    }

    private fun takeOver() {
        if (!stopping) session.takeOver()
    }

    private fun togglePause() {
        if (stopping) return
        if (session.state.value.isPaused) {
            if (!gestures.hasGestures) session.resume()
        } else {
            session.pause()
        }
    }

    private fun stop() {
        if (stopping) return
        stopping = true
        session.pause()
        if (!gestures.hasGestures) completeStop() else refresh()
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
        private const val CAPTURE_SETTLE_MS = 40L
        private const val SETTINGS_RETRY_MS = 1_000L

        fun isControlWindow(title: CharSequence?): Boolean =
            when (title) {
                EFFECTS_WINDOW_TITLE, PANEL_WINDOW_TITLE -> true
                else -> false
            }
    }
}
