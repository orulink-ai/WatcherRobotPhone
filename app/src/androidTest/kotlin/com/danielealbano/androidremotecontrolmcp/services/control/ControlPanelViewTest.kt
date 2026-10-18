package com.danielealbano.androidremotecontrolmcp.services.control

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.danielealbano.androidremotecontrolmcp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Verifies real View visibility, interaction, and layout without dispatching phone gestures. */
class ControlPanelViewTest {
    @Test
    fun bottomBarKeepsTakeoverAndStopVisibleWithoutAnExtraPauseControl() =
        onMain {
            val context = context()
            val panel = ControlPanelView(context)
            var takeoverCount = 0
            var pauseCount = 0
            var stopCount = 0
            panel.setActions({}, { takeoverCount++ }, { pauseCount++ }, { stopCount++ })
            panel.render(ControlSessionState(activeTool = "tap", lastTool = "tap"))

            assertTrue(panel.dragHandle.isClickable)
            assertFalse(panel.dragHandle.contentDescription.isNullOrBlank())
            assertTrue(panel.hasVisibleAction(R.string.control_overlay_stop))
            assertFalse(panel.hasVisibleAction(R.string.control_overlay_pause))
            assertFalse(panel.hasVisibleAction(R.string.control_overlay_collapse))

            assertVisibleActionText(panel, R.string.control_overlay_takeover)
            assertVisibleActionText(panel, R.string.control_overlay_stop)
            panel.action(R.string.control_overlay_takeover).performClick()
            assertEquals(1, takeoverCount)
            assertEquals(0, pauseCount)
            panel.action(R.string.control_overlay_stop).performClick()
            assertEquals(1, takeoverCount)
            assertEquals(0, pauseCount)
            assertEquals(1, stopCount)
        }

    @Test
    fun expandingOnlyAddsDetailsAndKeepsTheSameTwoActions() =
        onMain {
            val panel = ControlPanelView(context())
            var expanded = false
            var toggleCount = 0
            var stopCount = 0
            var takeoverCount = 0
            var pauseCount = 0
            panel.setActions(
                onToggleExpanded = {
                    expanded = !expanded
                    toggleCount++
                    panel.setExpanded(expanded)
                },
                onTakeover = { takeoverCount++ },
                onPause = { pauseCount++ },
                onStop = { stopCount++ },
            )
            panel.render(ControlSessionState(lastTool = "get_screen_state"))

            panel.dragHandle.performClick()

            assertEquals(1, toggleCount)
            assertTrue(panel.hasVisibleAction(R.string.control_overlay_stop))
            assertFalse(panel.hasVisibleAction(R.string.control_overlay_pause))
            assertVisibleActionText(panel, R.string.control_overlay_takeover)
            panel.action(R.string.control_overlay_takeover).performClick()
            panel.action(R.string.control_overlay_stop).performClick()
            assertEquals(1, takeoverCount)
            assertEquals(0, pauseCount)
            assertEquals(1, stopCount)

            panel.dragHandle.performClick()

            assertEquals(2, toggleCount)
            assertTrue(panel.hasVisibleAction(R.string.control_overlay_stop))
            assertTrue(panel.hasVisibleAction(R.string.control_overlay_takeover))
            assertFalse(panel.hasVisibleAction(R.string.control_overlay_pause))
        }

    @Test
    fun pausedBarReusesTheTakeoverSlotToResumeWithoutAnotherPauseButton() =
        onMain {
            val panel = ControlPanelView(context())
            var takeoverCount = 0
            var resumeCount = 0
            panel.setActions({}, { takeoverCount++ }, { resumeCount++ }, {})
            panel.render(ControlSessionState(lastTool = "tap", isPaused = true))

            assertFalse(panel.hasVisibleAction(R.string.control_overlay_takeover))
            assertVisibleActionText(panel, R.string.control_overlay_resume)
            panel.action(R.string.control_overlay_resume).performClick()
            assertEquals(0, takeoverCount)
            assertEquals(1, resumeCount)
            assertVisibleActionText(panel, R.string.control_overlay_stop)
        }

    @Test
    fun takenOverStatusDiffersFromPauseAndKeepsResumeReachable() =
        onMain {
            val panel = ControlPanelView(context())
            var resumeCount = 0
            panel.setActions({}, {}, { resumeCount++ }, {})
            val paused = ControlSessionState(lastTool = "tap", isPaused = true)
            panel.render(paused)
            val pausedDescription = panel.dragHandle.contentDescription.toString()

            panel.render(paused.copy(isTakenOver = true))

            val takenOverDescription = panel.dragHandle.contentDescription.toString()
            assertFalse(takenOverDescription.isBlank())
            assertFalse("Takeover must be distinguishable from pause", pausedDescription == takenOverDescription)
            assertFalse(panel.hasVisibleAction(R.string.control_overlay_takeover))
            assertVisibleActionText(panel, R.string.control_overlay_return_to_ai)
            assertTrue(panel.action(R.string.control_overlay_return_to_ai).isEnabled)
            panel.action(R.string.control_overlay_return_to_ai).performClick()
            assertEquals(1, resumeCount)
        }

    @Test
    fun handingOverDisablesResumeUntilTheDispatchedGestureFinishes() =
        onMain {
            val panel = ControlPanelView(context())
            var resumeCount = 0
            panel.setActions({}, {}, { resumeCount++ }, {})
            panel.setExpanded(true)
            val paused = ControlSessionState(lastTool = "swipe", isPaused = true, isTakenOver = true)

            panel.render(paused, waitingForGesture = true)

            assertFalse(panel.action(R.string.control_overlay_return_to_ai).isEnabled)
            assertTrue(panel.action(R.string.control_overlay_stop).isEnabled)

            panel.render(paused, waitingForGesture = false)

            val resume = panel.action(R.string.control_overlay_return_to_ai)
            assertTrue(resume.isEnabled)
            resume.performClick()
            assertEquals(1, resumeCount)
        }

    @Test
    fun stoppingDisablesRepeatedStopAndResumeInBothPanelSizes() =
        onMain {
            val panel = ControlPanelView(context())
            panel.setActions({}, {}, {}, {})
            val paused = ControlSessionState(lastTool = "tap", isPaused = true)

            panel.setExpanded(true)
            panel.render(paused, waitingForGesture = true, stopping = true)

            assertFalse(panel.action(R.string.control_overlay_resume).isEnabled)
            assertFalse(panel.action(R.string.control_overlay_stop).isEnabled)

            panel.setExpanded(false)
            panel.render(paused, waitingForGesture = true, stopping = true)

            assertFalse(panel.action(R.string.control_overlay_resume).isEnabled)
            assertFalse(panel.action(R.string.control_overlay_stop).isEnabled)

            panel.render(ControlSessionState(activeTool = "tap", lastTool = "tap"), stopping = true)
            assertFalse(panel.action(R.string.control_overlay_takeover).isEnabled)
            assertFalse(panel.hasVisibleAction(R.string.control_overlay_pause))
        }

    @Test
    fun dispatchingAButtonTouchKeepsInteractionActiveUntilRelease() =
        onMain {
            val context = context()
            val panel = ControlPanelView(context)
            val interactions = mutableListOf<Boolean>()
            panel.setActions({}, {}, {}, {})
            panel.onTouchInteraction = { interactions += it }
            panel.render(ControlSessionState(activeTool = "tap", lastTool = "tap"))
            val width = minOf(panel.preferredCompactWidth, (312 * context.resources.displayMetrics.density).toInt())
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            val action = panel.action(R.string.control_overlay_takeover)
            val bounds = Rect(0, 0, action.width, action.height)
            panel.offsetDescendantRectToMyCoords(action, bounds)
            val downTime = SystemClock.uptimeMillis()
            val down =
                MotionEvent.obtain(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0,
                )
            val up =
                MotionEvent.obtain(
                    downTime,
                    downTime + 50,
                    MotionEvent.ACTION_UP,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0,
                )
            try {
                assertTrue(panel.dispatchTouchEvent(down))
                assertEquals(listOf(true), interactions)
                assertTrue(panel.dispatchTouchEvent(up))
                assertEquals(listOf(true, false), interactions)
            } finally {
                down.recycle()
                up.recycle()
            }
        }

    @Test
    fun narrowScreenAndLargeFontsKeepEveryVisibleControlTouchableAndInsideThePanel() =
        onMain {
            for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
                for (fontScale in listOf(1f, 2f)) {
                    val context = context(fontScale, locale)
                    val density = context.resources.displayMetrics.density
                    val availableWidth = (320 * density).toInt()
                    val minimumTarget = (48 * density).toInt()
                    val panel = ControlPanelView(context)
                    panel.setActions({}, {}, {}, {})
                    for (expanded in listOf(false, true)) {
                        val panelWidth = if (expanded) availableWidth else minOf(panel.preferredCompactWidth, availableWidth)
                        panel.setExpanded(expanded)
                        panel.render(ControlSessionState(activeTool = "get_screen_state", lastTool = "get_screen_state"))
                        panel.measure(
                            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        )
                        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)

                        assertEquals(panelWidth, panel.width)
                        assertVisibleActionText(panel, R.string.control_overlay_takeover)
                        assertVisibleActionText(panel, R.string.control_overlay_stop)
                        assertFalse(panel.hasVisibleAction(R.string.control_overlay_pause))
                        val controls = panel.visibleViews().filter { it.isClickable }.toList()
                        assertTrue(controls.isNotEmpty())
                        for (control in controls) {
                            val description = "${locale.language}, font=$fontScale, expanded=$expanded, ${control.contentDescription}"
                            assertTrue("Touch target too narrow: $description", control.width >= minimumTarget)
                            assertTrue("Touch target too short: $description", control.height >= minimumTarget)
                            val bounds = Rect(0, 0, control.width, control.height)
                            if (control !== panel) panel.offsetDescendantRectToMyCoords(control, bounds)
                            assertTrue(
                                "Control extends outside panel: $description",
                                Rect(0, 0, panel.width, panel.height).contains(bounds),
                            )
                        }
                    }
                }
            }
        }

    private fun assertVisibleActionText(
        panel: ControlPanelView,
        resource: Int,
    ) {
        val action = panel.action(resource)
        assertTrue("The action must expose visible text", action is TextView)
        assertEquals(panel.context.getString(resource), (action as TextView).text.toString())
    }

    private fun context(
        fontScale: Float = 1f,
        locale: Locale = Locale.US,
    ): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration =
            Configuration(target.resources.configuration).apply {
                this.fontScale = fontScale
                setLocale(locale)
                screenWidthDp = 320
            }
        return target.createConfigurationContext(configuration)
    }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }

    private fun View.action(label: Int): View =
        visibleViews().firstOrNull { it.isClickable && it.matches(context.getString(label)) }
            ?: throw AssertionError("No visible control for ${context.getString(label)}")

    private fun View.hasVisibleAction(label: Int): Boolean = visibleViews().any { it.isClickable && it.matches(context.getString(label)) }

    private fun View.matches(label: String): Boolean =
        contentDescription?.toString() == label || (this is TextView && text.toString() == label)

    private fun View.visibleViews(): Sequence<View> =
        sequence {
            if (visibility != View.VISIBLE) return@sequence
            yield(this@visibleViews)
            if (this@visibleViews is ViewGroup) {
                for (index in 0 until childCount) yieldAll(getChildAt(index).visibleViews())
            }
        }
}
