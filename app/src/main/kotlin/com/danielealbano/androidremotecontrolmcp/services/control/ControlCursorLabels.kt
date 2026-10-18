package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.R

internal class ControlCursorLabels(
    private val context: Context,
) {
    fun text(
        action: ControlCursorAction,
        phase: ControlCursorPhase,
    ): String =
        context.getString(
            when (phase) {
                ControlCursorPhase.APPROACH -> R.string.control_cursor_approach
                ControlCursorPhase.EXECUTING -> executing(action)
                ControlCursorPhase.COMPLETED -> completed(action)
            },
        )

    private fun executing(action: ControlCursorAction): Int =
        when (action) {
            ControlCursorAction.CLICK -> R.string.control_cursor_click
            ControlCursorAction.LONG_PRESS -> R.string.control_cursor_long_press
            ControlCursorAction.SWIPE -> R.string.control_cursor_swipe
            ControlCursorAction.SCROLL -> R.string.control_cursor_scroll
            ControlCursorAction.TYPE -> R.string.control_cursor_type
        }

    private fun completed(action: ControlCursorAction): Int =
        when (action) {
            ControlCursorAction.CLICK -> R.string.control_cursor_clicked
            ControlCursorAction.LONG_PRESS -> R.string.control_cursor_pressed
            ControlCursorAction.SWIPE -> R.string.control_cursor_swiped
            ControlCursorAction.SCROLL -> R.string.control_cursor_scrolled
            ControlCursorAction.TYPE -> R.string.control_cursor_typed
        }
}
