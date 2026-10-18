package com.danielealbano.androidremotecontrolmcp.services.control

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Drags only the disclosure/header, leaving takeover and stop as independent touch targets. */
internal class ControlPanelDrag(
    private val touchSlop: Int,
    private val position: () -> PanelPosition,
    private val onMove: (PanelPosition) -> Unit,
    private val onDock: () -> Unit,
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var origin = PanelPosition(0, 0)
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility") // Taps call performClick; TalkBack invokes the same click action.
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                origin = position()
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (hypot(dx, dy) > touchSlop) dragging = true
                if (dragging) onMove(PanelPosition(origin.x + dx.roundToInt(), origin.y + dy.roundToInt()))
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) onDock() else view.performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDock()
            }
        }
        return true
    }
}
