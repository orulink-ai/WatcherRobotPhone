package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.danielealbano.androidremotecontrolmcp.R
import kotlin.math.ceil
import com.danielealbano.androidremotecontrolmcp.services.control.ControlPanelTokens as Tokens

/** Two persistent actions: local ownership (or returning it) and ending the task. */
internal class ControlPanelActions(
    private val context: Context,
    private val style: ControlPanelStyle,
) {
    private val ownership = style.action(context.getString(R.string.control_overlay_takeover))
    private val stop = style.action(context.getString(R.string.control_overlay_stop), destructive = true)
    val view = LinearLayout(context)
    private var lastState = ControlSessionState()

    init {
        view.gravity = Gravity.CENTER_VERTICAL
        view.addView(ownership)
        view.addView(stop)
        prepare(stacked = false)
    }

    val preferredWidth: Int get() =
        actionWidth(ownership) + actionWidth(stop) + style.dp(Tokens.ACTION_GAP_DP)

    fun prepare(stacked: Boolean) {
        listOf(ownership, stop).forEachIndexed { index, action ->
            action.layoutParams =
                if (stacked) {
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    LinearLayout.LayoutParams(actionWidth(action), LinearLayout.LayoutParams.WRAP_CONTENT)
                }.apply {
                    if (index > 0) marginStart = style.dp(Tokens.ACTION_GAP_DP)
                }
        }
    }

    fun setCallbacks(
        onTakeover: () -> Unit,
        onPause: () -> Unit,
        onStop: () -> Unit,
    ) {
        ownership.setOnClickListener { if (lastState.isPaused) onPause() else onTakeover() }
        stop.setOnClickListener { onStop() }
    }

    fun render(
        state: ControlSessionState,
        waitingForGesture: Boolean,
        stopping: Boolean,
    ) {
        lastState = state
        ownership.setText(
            when {
                state.isTakenOver -> R.string.control_overlay_return_to_ai
                state.isPaused -> R.string.control_overlay_resume
                else -> R.string.control_overlay_takeover
            },
        )
        ownership.contentDescription = ownership.text
        stop.setText(R.string.control_overlay_stop)
        stop.contentDescription = stop.text
        enable(ownership, !stopping && !(state.isPaused && waitingForGesture))
        enable(stop, !stopping)
    }

    fun applyTheme() {
        style.applyAction(ownership)
        style.applyAction(stop, destructive = true)
    }

    private fun enable(
        view: TextView,
        enabled: Boolean,
    ) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else Tokens.DISABLED_ALPHA
    }

    private fun actionWidth(view: TextView): Int =
        maxOf(
            style.dp(Tokens.ACTION_MIN_WIDTH_DP),
            ceil(view.paint.measureText(view.text.toString())).toInt() + style.dp(Tokens.ACTION_PADDING_DP * 2),
        )
}
