package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.R

internal class ControlPanelLabels(
    context: Context,
    state: ControlSessionState,
    waitingForGesture: Boolean,
    stopping: Boolean,
) {
    val title: String =
        context.getString(
            when {
                stopping -> R.string.control_overlay_stopping
                state.isTakenOver && waitingForGesture -> R.string.control_overlay_handing_over
                state.isPaused && waitingForGesture -> R.string.control_overlay_pausing
                state.isTakenOver -> R.string.control_overlay_paused
                state.isPaused -> R.string.control_overlay_user_paused
                state.isActive -> R.string.control_overlay_active
                else -> R.string.control_overlay_waiting
            },
        )
    val detail: String =
        context.getString(
            when {
                (state.isPaused || stopping) && waitingForGesture -> R.string.control_overlay_finishing_gesture
                state.isTakenOver -> R.string.control_overlay_local_control
                state.isPaused -> R.string.control_overlay_resume_hint
                state.isActive -> actionLabel(state.activeTool.orEmpty())
                else -> R.string.control_overlay_connected
            },
        )

    private fun actionLabel(toolName: String): Int =
        when {
            toolName == "get_screen_state" -> {
                R.string.control_overlay_reading
            }

            toolName.contains("swipe") || toolName.contains("scroll") -> {
                R.string.control_overlay_scrolling
            }

            toolName.contains("tap") || toolName.contains("click") || toolName == "long_press" -> {
                R.string.control_overlay_tapping
            }

            toolName.startsWith("type_") || toolName == "press_key" -> {
                R.string.control_overlay_typing
            }

            toolName == "open_app" -> {
                R.string.control_overlay_opening
            }

            else -> {
                R.string.control_overlay_connected
            }
        }
}
