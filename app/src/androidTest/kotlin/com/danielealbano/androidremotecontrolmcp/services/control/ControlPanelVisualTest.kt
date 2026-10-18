package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.danielealbano.androidremotecontrolmcp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Draws real Android widgets for manual visual QA and checks the constrained action row. */
class ControlPanelVisualTest {
    @Test
    fun exportNativeContactSheetsForBothLanguagesAndThemes() =
        onMain {
            for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
                for (night in listOf(false, true)) {
                    exportContactSheet(locale, night)
                }
            }
        }

    private fun exportContactSheet(
        locale: Locale,
        night: Boolean,
    ) {
        val context = context(locale, night, 1f)
        val density = context.resources.displayMetrics.density
        val rows = cases().map { renderCase(it, locale, night) }
        val width = (320 * density).toInt()
        val gap = (12 * density).toInt()
        val captionHeight = (26 * density).toInt()
        val height = rows.sumOf { captionHeight + it.panel.height + gap } + gap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (night) 0xFF101112.toInt() else 0xFFEFF0F2.toInt())
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (night) Color.WHITE else 0xFF202124.toInt()
                    textSize = 11 * density
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
            var top = gap
            for (row in rows) {
                canvas.drawText(row.caption, gap.toFloat(), top + 16 * density, paint)
                top += captionHeight
                canvas.save()
                canvas.translate((width - row.panel.width - gap).coerceAtLeast(0).toFloat(), top.toFloat())
                row.panel.draw(canvas)
                canvas.restore()
                top += row.panel.height + gap
            }
            val theme = if (night) "night" else "day"
            val output = File(context.cacheDir, "control-panel-visual-${locale.language}-$theme.png")
            output.outputStream().use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            assertTrue(output.length() > 0)
            Log.i("ControlPanelVisualTest", "Native UI contact sheet: ${output.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderCase(
        case: VisualCase,
        locale: Locale,
        night: Boolean,
    ): RenderedCase {
        val context = context(locale, night, case.fontScale)
        val density = context.resources.displayMetrics.density
        val panel = ControlPanelView(context)
        panel.setActions({}, {}, {}, {})
        panel.setExpanded(case.expanded)
        panel.render(case.state)
        var expanded = case.expanded
        val availableWidth = (312 * density).toInt()
        var width = if (expanded) availableWidth else minOf(panel.preferredCompactWidth, availableWidth)
        val heightLimit = case.heightLimitDp?.let { (it * density).toInt() }
        panel.limitHeight(null)
        measure(panel, width)
        if (heightLimit != null) {
            if (expanded && heightLimit < panel.minimumExpandedHeight) {
                expanded = false
                width = minOf(panel.preferredCompactWidth, availableWidth)
                panel.setExpanded(false)
            } else if (expanded) {
                panel.limitHeight(heightLimit)
            }
            measure(panel, width)
        }
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        assertEquals(width, panel.width)
        if (heightLimit != null) {
            assertTrue("${case.caption}: panel exceeds available height", panel.height <= heightLimit)
        }
        val ownershipAction =
            when {
                case.state.isTakenOver -> R.string.control_overlay_return_to_ai
                case.state.isPaused -> R.string.control_overlay_resume
                else -> R.string.control_overlay_takeover
            }
        assertTouchableAndVisible(panel, panel.action(ownershipAction), density, case.caption)
        assertTouchableAndVisible(panel, panel.action(R.string.control_overlay_stop), density, case.caption)
        assertActionHasVisibleText(panel, ownershipAction)
        assertActionHasVisibleText(panel, R.string.control_overlay_stop)
        assertTouchableAndVisible(panel, panel.dragHandle, density, case.caption)
        val caption = if (case.expanded && !expanded) "${case.caption} / compact fallback" else case.caption
        return RenderedCase(caption, panel)
    }

    private fun assertActionHasVisibleText(
        panel: ControlPanelView,
        resource: Int,
    ) {
        val action = panel.action(resource)
        assertTrue("Action must use visible text", action is TextView)
        assertEquals(panel.context.getString(resource), (action as TextView).text.toString())
        val layout = action.layout ?: return
        for (line in 0 until layout.lineCount) {
            assertEquals("Action label is truncated", 0, layout.getEllipsisCount(line))
        }
    }

    private fun measure(
        panel: ControlPanelView,
        width: Int,
    ) {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
    }

    private fun assertTouchableAndVisible(
        panel: ControlPanelView,
        action: View,
        density: Float,
        caption: String,
    ) {
        val minimum = (48 * density).toInt()
        assertTrue("$caption: action is too narrow", action.width >= minimum)
        assertTrue("$caption: action is too short", action.height >= minimum)
        val bounds = Rect(0, 0, action.width, action.height)
        panel.offsetDescendantRectToMyCoords(action, bounds)
        assertTrue("$caption: action is clipped", Rect(0, 0, panel.width, panel.height).contains(bounds))
    }

    private fun cases(): List<VisualCase> {
        val ready = ControlSessionState(lastTool = "get_screen_state")
        val active = ControlSessionState(activeTool = "get_screen_state", lastTool = "get_screen_state")
        val paused = ControlSessionState(lastTool = "swipe", isPaused = true)
        val takenOver = paused.copy(isTakenOver = true)
        return listOf(
            VisualCase("Compact / Ready", ready),
            VisualCase("Compact / Active", active),
            VisualCase("Compact / Paused", paused),
            VisualCase("Compact / Taken over", takenOver),
            VisualCase("Expanded / Active", active, expanded = true),
            VisualCase("Expanded / Paused", paused, expanded = true),
            VisualCase("Expanded / Taken over", takenOver, expanded = true),
            VisualCase("2x font / Expanded / Active", active, expanded = true, fontScale = 2f),
            VisualCase("2x font / 240dp height / Active", active, expanded = true, fontScale = 2f, heightLimitDp = 240),
            VisualCase("2x font / 240dp height / Paused", paused, expanded = true, fontScale = 2f, heightLimitDp = 240),
        )
    }

    private fun context(
        locale: Locale,
        night: Boolean,
        fontScale: Float,
    ): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration =
            Configuration(target.resources.configuration).apply {
                setLocale(locale)
                this.fontScale = fontScale
                screenWidthDp = 320
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
        return target.createConfigurationContext(configuration)
    }

    private fun View.action(resource: Int): View {
        val label = context.getString(resource)
        return visibleViews().firstOrNull {
            it.isClickable && (it.contentDescription?.toString() == label || (it is TextView && it.text.toString() == label))
        } ?: throw AssertionError("Missing action: $label")
    }

    private fun View.visibleViews(): Sequence<View> =
        sequence {
            if (visibility != View.VISIBLE) return@sequence
            yield(this@visibleViews)
            if (this@visibleViews is ViewGroup) {
                for (index in 0 until childCount) yieldAll(getChildAt(index).visibleViews())
            }
        }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }

    private data class VisualCase(
        val caption: String,
        val state: ControlSessionState,
        val expanded: Boolean = false,
        val fontScale: Float = 1f,
        val heightLimitDp: Int? = null,
    )

    private data class RenderedCase(
        val caption: String,
        val panel: ControlPanelView,
    )
}
