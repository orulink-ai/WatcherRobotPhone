package com.danielealbano.androidremotecontrolmcp.services.control

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** Optional hardware acceptance: exercises our own overlay over the current app, without app actions. */
class ControlOverlayDeviceTest {
    @Test
    fun bottomBarExpandsDetailsAndKeepsTakeoverAndStopAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Preserve the user's enabled accessibility services during visual acceptance.
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline = SystemClock.uptimeMillis() + 10000L
        while (McpAccessibilityService.instance == null && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
        }
        val service = McpAccessibilityService.instance
        assumeNotNull("Enable this build's accessibility service for hardware overlay acceptance", service)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var windows: ControlOverlayWindows? = null
        try {
            instrumentation.runOnMainSync {
                val overlay = ControlOverlayWindows(checkNotNull(service), scope, {}, {}, {})
                windows = overlay
                overlay.attach()
                assertTrue("Accessibility overlay must attach", overlay.attached)
                overlay.renderPanel(ControlSessionState(lastTool = "get_screen_state"), false, false)
            }
            instrumentation.waitForIdleSync()
            capture(automation, "compact")
            var compactWidth = 0
            var compactHeight = 0
            instrumentation.runOnMainSync {
                val overlay = checkNotNull(windows)
                compactWidth = overlay.panel.width
                compactHeight = overlay.panel.height
                assertTrue("Take over text must be visible", hasVisibleAction(overlay.panel, R.string.control_overlay_takeover))
                assertTrue("Stop text must be visible", hasVisibleAction(overlay.panel, R.string.control_overlay_stop))
                val location = IntArray(2)
                overlay.panel.getLocationOnScreen(location)
                assertTrue(
                    "The control bar starts near the bottom",
                    location[1] > checkNotNull(service).resources.displayMetrics.heightPixels / 2,
                )
                // Hold disclosure open while collecting a compositor screenshot, independently of capture latency.
                overlay.panel.onTouchInteraction?.invoke(true)
                overlay.panel.dragHandle.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val panel = checkNotNull(windows).panel
                assertTrue("Disclosure must expand its actual window", panel.width > compactWidth || panel.height > compactHeight)
                assertTrue("Stop must be visible in the actual window", hasVisibleAction(panel, R.string.control_overlay_stop))
                assertTrue("Take over must remain visible", hasVisibleAction(panel, R.string.control_overlay_takeover))
                assertTrue("Stop must remain visible", hasVisibleAction(panel, R.string.control_overlay_stop))
            }
            capture(automation, "expanded")
            instrumentation.runOnMainSync {
                checkNotNull(windows).panel.onTouchInteraction?.invoke(false)
                checkNotNull(windows).panel.dragHandle.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("Collapsing restores the compact hit region", checkNotNull(windows).panel.width == compactWidth)
            }
        } finally {
            instrumentation.runOnMainSync { windows?.detach() }
            scope.cancel()
        }
    }

    private fun capture(
        automation: UiAutomation,
        state: String,
    ) {
        // waitForIdleSync covers View traversal; SurfaceFlinger must also consume the changed overlay frame.
        SystemClock.sleep(150)
        val screenshot = checkNotNull(automation.takeScreenshot())
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(directory, "control-overlay-device-$state.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }

    private fun hasVisibleAction(
        view: View,
        resource: Int,
    ): Boolean {
        if (!view.isShown) return false
        val label = view.context.getString(resource)
        if (view.isClickable && view is TextView && view.text.toString() == label) {
            val bounds = Rect()
            return view.getGlobalVisibleRect(bounds) && bounds.width() >= view.width && bounds.height() >= view.height
        }
        return view is ViewGroup && (0 until view.childCount).any { hasVisibleAction(view.getChildAt(it), resource) }
    }
}
