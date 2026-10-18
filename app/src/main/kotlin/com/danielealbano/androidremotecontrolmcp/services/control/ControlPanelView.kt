package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.danielealbano.androidremotecontrolmcp.R
import kotlin.math.ceil
import com.danielealbano.androidremotecontrolmcp.services.control.ControlPanelTokens as Tokens

/** Persistent bottom controls. Only optional explanatory text expands, never the essential actions. */
internal class ControlPanelView(
    context: Context,
) : LinearLayout(context) {
    private val style = ControlPanelStyle(context)
    private val taskTitle = style.label(Tokens.STATUS_TEXT_SP)
    private val status = style.label(Tokens.DETAIL_TEXT_SP, style.accent)
    private val detail = style.label(Tokens.DETAIL_TEXT_SP, style.secondary).apply { maxLines = 2 }
    private val header = LinearLayout(context)
    private val row = LinearLayout(context)
    private val actionViews = ControlPanelActions(context, style)
    private var expanded = false
    private var lastState = ControlSessionState()
    private var lastWaitingForGesture = false
    private var lastStopping = false
    val dragHandle: View get() = header
    val preferredCompactWidth: Int get() = style.dp(Tokens.COMPACT_MIN_WIDTH_DP)
    val minimumExpandedHeight: Int
        get() = row.measuredHeight + paddingTop + paddingBottom + style.dp(Tokens.TOUCH_TARGET_DP)
    var onConfigurationUpdate: (() -> Unit)? = null
    var onTouchInteraction: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        background = style.panelSurface()
        clipToOutline = true
        elevation = style.dp(Tokens.PANEL_ELEVATION_DP).toFloat()
        setPadding(
            style.dp(Tokens.PANEL_PADDING_HORIZONTAL_DP),
            style.dp(Tokens.PANEL_PADDING_VERTICAL_DP),
            style.dp(Tokens.PANEL_PADDING_HORIZONTAL_DP),
            style.dp(Tokens.PANEL_PADDING_VERTICAL_DP),
        )
        buildHeader()
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(header)
        row.addView(actionViews.view)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            detail,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = style.dp(Tokens.ACTION_ROW_GAP_DP)
            },
        )
        setExpanded(false)
        render(ControlSessionState())
    }

    private fun buildHeader() {
        header.gravity = Gravity.CENTER_VERTICAL
        header.minimumWidth = style.dp(Tokens.TOUCH_TARGET_DP)
        header.minimumHeight = style.dp(Tokens.TOUCH_TARGET_DP)
        header.isClickable = true
        header.isFocusable = true
        header.addView(
            ControlPanelEmblem(context),
            LayoutParams(style.dp(Tokens.EMBLEM_SIZE_DP), style.dp(Tokens.EMBLEM_SIZE_DP)),
        )
        val labels = LinearLayout(context).apply { orientation = VERTICAL }
        taskTitle.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        status.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        detail.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        labels.addView(taskTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        labels.addView(
            status,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = style.dp(Tokens.DETAIL_GAP_DP)
            },
        )
        header.addView(
            labels,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = style.dp(Tokens.INDICATOR_LABEL_GAP_DP)
            },
        )
    }

    fun setActions(
        onToggleExpanded: () -> Unit,
        onTakeover: () -> Unit,
        onPause: () -> Unit,
        onStop: () -> Unit,
    ) {
        header.setOnClickListener { onToggleExpanded() }
        actionViews.setCallbacks(onTakeover, onPause, onStop)
    }

    fun setExpanded(value: Boolean) {
        expanded = value
        detail.visibility = if (value) VISIBLE else GONE
    }

    /** Optional details yield space first when the keyboard limits the available area. */
    fun limitHeight(availableHeight: Int?) {
        detail.maxHeight =
            availableHeight?.let {
                val gap = style.dp(Tokens.ACTION_ROW_GAP_DP)
                val occupiedHeight = row.measuredHeight + paddingTop + paddingBottom + gap
                (it - occupiedHeight).coerceAtLeast(0)
            } ?: Int.MAX_VALUE
    }

    fun render(
        state: ControlSessionState,
        waitingForGesture: Boolean = false,
        stopping: Boolean = false,
    ) {
        lastState = state
        lastWaitingForGesture = waitingForGesture
        lastStopping = stopping
        val labels = ControlPanelLabels(context, state, waitingForGesture, stopping)
        taskTitle.setText(R.string.control_overlay_task_title)
        status.text = labels.title
        status.setTextColor(if (state.isPaused) style.paused else style.accent)
        detail.text = labels.detail
        header.contentDescription = context.getString(R.string.control_overlay_expand, labels.title)
        actionViews.render(state, waitingForGesture, stopping)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        background = style.panelSurface()
        taskTitle.textSize = Tokens.STATUS_TEXT_SP
        status.textSize = Tokens.DETAIL_TEXT_SP
        detail.textSize = Tokens.DETAIL_TEXT_SP
        taskTitle.setTextColor(style.text)
        detail.setTextColor(style.secondary)
        actionViews.applyTheme()
        render(lastState, lastWaitingForGesture, lastStopping)
        onConfigurationUpdate?.invoke()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchInteraction?.invoke(true)
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                onTouchInteraction?.invoke(false)
            }
        }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val titleWidth = ceil(taskTitle.paint.measureText(taskTitle.text.toString())).toInt()
        val headerWidth =
            maxOf(
                style.dp(Tokens.HEADER_MIN_WIDTH_DP),
                titleWidth + style.dp(Tokens.EMBLEM_SIZE_DP + Tokens.INDICATOR_LABEL_GAP_DP),
            )
        val stacked = headerWidth + actionViews.preferredWidth + style.dp(Tokens.ACTION_ROW_GAP_DP) > availableWidth
        row.orientation = if (stacked) VERTICAL else HORIZONTAL
        header.layoutParams =
            if (stacked) {
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                )
            } else {
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
        actionViews.prepare(stacked)
        actionViews.view.layoutParams =
            LayoutParams(
                if (stacked) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                val gap = style.dp(Tokens.ACTION_ROW_GAP_DP)
                if (stacked) topMargin = gap else marginStart = gap
            }
        detail.visibility = if (expanded) VISIBLE else GONE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
