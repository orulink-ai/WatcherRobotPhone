package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.danielealbano.androidremotecontrolmcp.R

/** A compact, independently touchable panel; the surrounding effects remain touch-through. */
internal class ControlPanelView(
    context: Context,
) : LinearLayout(context) {
    private val status = label(STATUS_TEXT_SP, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD) }
    private val detail = label(DETAIL_TEXT_SP, SECONDARY_TEXT)
    private val emblem =
        label(EMBLEM_TEXT_SP, ACCENT).apply {
            text = "✦"
            gravity = Gravity.CENTER
        }
    private val takeover = action(R.string.control_overlay_takeover, ACCENT)
    private val stop = action(R.string.control_overlay_stop, STOP_TEXT)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(PADDING_START_DP), dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP))
        background = surface(BACKGROUND, dp(PANEL_RADIUS_DP).toFloat(), BORDER)
        elevation = dp(ELEVATION_DP).toFloat()
        addView(emblem, LayoutParams(dp(EMBLEM_WIDTH_DP), dp(EMBLEM_HEIGHT_DP)))
        val labels =
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(LABEL_PADDING_DP), 0, dp(LABEL_PADDING_DP), 0)
                addView(status)
                addView(
                    detail,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(DETAIL_MARGIN_DP)
                    },
                )
            }
        addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(takeover, LayoutParams(dp(TAKEOVER_WIDTH_DP), dp(BUTTON_SIZE_DP)))
        addView(
            stop,
            LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP)).apply { marginStart = dp(BUTTON_MARGIN_DP) },
        )
    }

    fun setActions(
        onTakeover: () -> Unit,
        onStop: () -> Unit,
    ) {
        takeover.setOnClickListener { onTakeover() }
        stop.setOnClickListener { onStop() }
    }

    fun render(
        state: ControlSessionState,
        waitingForGesture: Boolean = false,
        stopping: Boolean = false,
    ) {
        val title = titleResource(state, waitingForGesture, stopping)
        status.setText(title)
        detail.text = detailLabel(state, waitingForGesture, stopping)
        renderButtons(state, waitingForGesture, stopping)
        emblem.setTextColor(if (state.isPaused) PAUSED_ACCENT else ACCENT)
        contentDescription = context.getString(title)
    }

    private fun titleResource(
        state: ControlSessionState,
        waitingForGesture: Boolean,
        stopping: Boolean,
    ): Int =
        when {
            stopping -> R.string.control_overlay_stopping
            state.isPaused && waitingForGesture -> R.string.control_overlay_handing_over
            state.isPaused -> R.string.control_overlay_paused
            state.isActive -> R.string.control_overlay_active
            else -> R.string.control_overlay_waiting
        }

    private fun detailLabel(
        state: ControlSessionState,
        waitingForGesture: Boolean,
        stopping: Boolean,
    ): String =
        if ((state.isPaused || stopping) && waitingForGesture) {
            context.getString(R.string.control_overlay_finishing_gesture)
        } else if (state.isPaused) {
            context.getString(R.string.control_overlay_local_control)
        } else {
            actionLabel(state.activeTool ?: state.lastTool.orEmpty())
        }

    private fun renderButtons(
        state: ControlSessionState,
        waitingForGesture: Boolean,
        stopping: Boolean,
    ) {
        takeover.setText(if (state.isPaused) R.string.control_overlay_resume else R.string.control_overlay_takeover)
        takeover.isEnabled = !(state.isPaused && waitingForGesture) && !stopping
        takeover.alpha = if (takeover.isEnabled) 1f else DISABLED_ALPHA
        stop.isEnabled = !stopping
        stop.alpha = if (stop.isEnabled) 1f else DISABLED_ALPHA
    }

    private fun actionLabel(toolName: String): String {
        val label =
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
        return context.getString(label)
    }

    private fun label(
        size: Float,
        color: Int,
    ): TextView =
        TextView(context).apply {
            textSize = size
            setTextColor(color)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun action(
        text: Int,
        color: Int,
    ): TextView =
        label(BUTTON_TEXT_SP, color).apply {
            setText(text)
            gravity = Gravity.CENTER
            background =
                RippleDrawable(
                    ColorStateList.valueOf(RIPPLE_COLOR),
                    surface(BUTTON_BACKGROUND, dp(BUTTON_RADIUS_DP).toFloat(), BUTTON_BORDER),
                    surface(Color.WHITE, dp(BUTTON_RADIUS_DP).toFloat(), Color.WHITE),
                )
            isClickable = true
            isFocusable = true
        }

    private fun surface(
        color: Int,
        radius: Float,
        border: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), border)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATUS_TEXT_SP = 13f
        private const val DETAIL_TEXT_SP = 11f
        private const val EMBLEM_TEXT_SP = 17f
        private const val BUTTON_TEXT_SP = 12f
        private const val PADDING_START_DP = 12
        private const val PADDING_DP = 10
        private const val PANEL_RADIUS_DP = 24
        private const val ELEVATION_DP = 8
        private const val EMBLEM_WIDTH_DP = 30
        private const val EMBLEM_HEIGHT_DP = 40
        private const val LABEL_PADDING_DP = 8
        private const val DETAIL_MARGIN_DP = 3
        private const val TAKEOVER_WIDTH_DP = 56
        private const val BUTTON_SIZE_DP = 48
        private const val BUTTON_MARGIN_DP = 6
        private const val BUTTON_RADIUS_DP = 14
        private const val DISABLED_ALPHA = 0.4f
        private const val RIPPLE_COLOR = 0x557ABEFF
        private const val BACKGROUND = 0xF20D1524.toInt()
        private const val BORDER = 0x995784BE.toInt()
        private const val ACCENT = 0xFF91C8FF.toInt()
        private const val PAUSED_ACCENT = 0xFFFFD38C.toInt()
        private const val SECONDARY_TEXT = 0xFFB0BED2.toInt()
        private const val STOP_TEXT = 0xFFFFA399.toInt()
        private const val BUTTON_BACKGROUND = 0xFF1D2B40.toInt()
        private const val BUTTON_BORDER = 0xFF344861.toInt()
    }
}
