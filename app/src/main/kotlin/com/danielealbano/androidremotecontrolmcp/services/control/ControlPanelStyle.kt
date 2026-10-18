package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.TextView
import com.danielealbano.androidremotecontrolmcp.services.control.ControlPanelTokens as Tokens

/** A dark glass surface keeps the same visual identity over light and dark third-party apps. */
internal class ControlPanelStyle(
    private val context: Context,
) {
    val text get() = Tokens.TEXT.toInt()
    val secondary get() = Tokens.SECONDARY.toInt()
    val accent get() = Tokens.ACCENT.toInt()
    val paused get() = Tokens.PAUSED.toInt()

    fun label(
        size: Float,
        color: Int = text,
    ): TextView =
        TextView(context).apply {
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }

    fun action(
        caption: CharSequence,
        destructive: Boolean = false,
    ): TextView =
        label(Tokens.ACTION_TEXT_SP).apply {
            text = caption
            gravity = Gravity.CENTER
            maxLines = 2
            minimumWidth = dp(Tokens.TOUCH_TARGET_DP)
            minimumHeight = dp(Tokens.TOUCH_TARGET_DP)
            val padding = dp(Tokens.ACTION_PADDING_DP)
            setPadding(padding, padding, padding, padding)
            applyAction(this, destructive)
            isClickable = true
            isFocusable = true
        }

    fun applyAction(
        view: TextView,
        destructive: Boolean = false,
    ) {
        view.textSize = Tokens.ACTION_TEXT_SP
        view.setTextColor(if (destructive) Tokens.DESTRUCTIVE.toInt() else text)
        val fill =
            GradientDrawable().apply {
                setColor(if (destructive) Tokens.DESTRUCTIVE_SURFACE else Tokens.ACTION_SURFACE)
                cornerRadius = dp(Tokens.ACTION_RADIUS_DP).toFloat()
            }
        view.background = RippleDrawable(ColorStateList.valueOf(Tokens.RIPPLE), fill, null)
    }

    fun panelSurface(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Tokens.SURFACE_START.toInt(), Tokens.SURFACE_END.toInt()),
        ).apply {
            cornerRadius = dp(Tokens.PANEL_RADIUS_DP).toFloat()
            setStroke(dp(Tokens.BORDER_WIDTH_DP).coerceAtLeast(1), Tokens.BORDER)
        }

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
