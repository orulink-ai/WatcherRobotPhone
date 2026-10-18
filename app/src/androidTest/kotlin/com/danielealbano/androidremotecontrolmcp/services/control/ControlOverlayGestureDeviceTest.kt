package com.danielealbano.androidremotecontrolmcp.services.control

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutorImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Opt-in hardware acceptance of production input and feedback over our own native test content.
 * This does not navigate or mutate Xiaohongshu or any other app.
 * Enable with -e overlayGestureDemo true; -e recordDemo true adds three seconds at both ends.
 */
class ControlOverlayGestureDeviceTest {
    @Test
    fun productionTapAndSwipeReachOwnedContentAndDisplayTheRealCursor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Opt in to owned-UI gesture acceptance with overlayGestureDemo=true",
            arguments.getString("overlayGestureDemo") == "true",
        )
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline = SystemClock.uptimeMillis() + SERVICE_WAIT_MS
        while (McpAccessibilityService.instance == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100L)
        }
        val service = McpAccessibilityService.instance
        assumeNotNull("Enable the target app's accessibility service before running this acceptance", service)
        val entryPoint =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                McpAccessibilityService.NodeCacheEntryPoint::class.java,
            )
        val session = entryPoint.controlSession()
        assumeTrue("Do not interrupt another phone task", session.state.value.activeTool == null)
        assumeTrue("Resume local takeover before opting into gesture acceptance", !session.state.value.isPaused)
        val executor = ActionExecutorImpl(entryPoint.nodeCache(), AccessibilityTreeParser())
        val recordDemo = arguments.getString("recordDemo") == "true"
        var activity: MainActivity? = null
        try {
            val intent =
                Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                )
            val started = instrumentation.startActivitySync(intent) as MainActivity
            activity = started
            lateinit var content: DemoContent
            instrumentation.runOnMainSync {
                session.reset()
                content = createOwnedContent(started)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(SETTLE_MS)
            if (recordDemo) SystemClock.sleep(RECORD_PAUSE_MS)

            lateinit var tap: ScreenPoint
            lateinit var swipe: SwipeCoordinates
            instrumentation.runOnMainSync {
                tap = pointIn(content.button, 0.72f, 0.5f)
                swipe =
                    SwipeCoordinates(
                        pointIn(content.scroll, 0.72f, 0.74f),
                        pointIn(content.scroll, 0.72f, 0.3f),
                    )
                assertEquals(0, content.taps.get())
                assertEquals(0, content.scroll.scrollY)
            }
            runBlocking {
                session.runTool("tap") { executor.tap(tap.x, tap.y) }.getOrThrow()
                // Capture after actual completion, including any future pointer approach phase.
                val tapImage =
                    async(Dispatchers.IO) {
                        delay(TAP_CAPTURE_DELAY_MS)
                        capture(automation, "tap")
                    }
                val image = tapImage.await()
                try {
                    assertTrue(
                        "Production tap feedback must show the outlined pointer at its actual dispatched position",
                        cursorAt(image, tap, started.resources.displayMetrics.density),
                    )
                } finally {
                    image.recycle()
                }
            }
            instrumentation.runOnMainSync {
                assertEquals("A real tap must reach the owned button exactly once", 1, content.taps.get())
            }
            // Let the tap tail disappear so the next compositor image can only show swipe feedback.
            SystemClock.sleep(800L)
            runBlocking {
                val swipeImage =
                    async(Dispatchers.IO) {
                        delay(SWIPE_DURATION_MS / 2L)
                        capture(automation, "swipe")
                    }
                session
                    .runTool("swipe") {
                        executor.swipe(swipe.start.x, swipe.start.y, swipe.end.x, swipe.end.y, SWIPE_DURATION_MS)
                    }.getOrThrow()
                val image = swipeImage.await()
                try {
                    assertTrue(
                        "During real dispatch the pointer must occur along the owned scroll path",
                        cursorAlong(image, swipe, started.resources.displayMetrics.density),
                    )
                } finally {
                    image.recycle()
                }
            }
            instrumentation.runOnMainSync {
                assertTrue("A real swipe must move the owned ScrollView", content.scroll.scrollY > 0)
                content.status.text = "验收完成 · 点击 1 次 · 已真实滚动"
            }
            if (recordDemo) SystemClock.sleep(RECORD_PAUSE_MS)
        } finally {
            instrumentation.runOnMainSync {
                session.reset()
                activity?.finishAndRemoveTask()
            }
        }
    }

    private fun createOwnedContent(activity: MainActivity): DemoContent {
        val taps = AtomicInteger()
        val root =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFFF2F4F9.toInt())
                setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16))
                setOnApplyWindowInsetsListener { view, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(
                        bars.left + dp(activity, 16),
                        bars.top + dp(activity, 16),
                        bars.right + dp(activity, 16),
                        bars.bottom + dp(activity, 16),
                    )
                    insets
                }
            }
        root.addView(
            TextView(activity).apply {
                text = "手机控制 · 真机验收"
                textSize = 24f
                setTextColor(0xFF202632.toInt())
            },
        )
        val status =
            TextView(activity).apply {
                text = "自有测试内容 · 尚未点击"
                textSize = 16f
                setPadding(0, dp(activity, 12), 0, dp(activity, 16))
            }
        root.addView(status)
        val button =
            Button(activity).apply {
                text = "点击验证"
                textSize = 18f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 20), 0, dp(activity, 20), 0)
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF5C72E8.toInt())
                setOnClickListener { status.text = "自有按钮收到真实点击：${taps.incrementAndGet()} 次" }
            }
        root.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 80)))
        val scroll =
            ScrollView(activity).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
            }
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        repeat(60) { index ->
            val row =
                LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 16), 0, 0, 0)
                    setBackgroundColor(if (index % 2 == 0) Color.WHITE else 0xFFF8FAFD.toInt())
                }
            row.addView(
                TextView(activity).apply {
                    text = "验证项目 ${index + 1}"
                    textSize = 18f
                    setTextColor(0xFF273142.toInt())
                },
                LinearLayout.LayoutParams(dp(activity, 180), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 72)))
        }
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        activity.setContentView(root)
        root.requestApplyInsets()
        return DemoContent(button, status, scroll, taps)
    }

    private fun pointIn(
        view: View,
        fractionX: Float,
        fractionY: Float,
    ): ScreenPoint {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        assertTrue("Owned test content must be laid out before dispatch", view.width > 0 && view.height > 0)
        return ScreenPoint(location[0] + view.width * fractionX, location[1] + view.height * fractionY)
    }

    private fun capture(
        automation: UiAutomation,
        label: String,
    ): Bitmap {
        val image = checkNotNull(automation.takeScreenshot()) { "Compositor screenshot unavailable" }
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(directory, "control-overlay-gesture-$label.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return image
    }

    private fun cursorAt(
        image: Bitmap,
        point: ScreenPoint,
        density: Float,
    ): Boolean {
        val bodyX = (point.x + 2f * density).roundToInt()
        val bodyY = (point.y + 6f * density).roundToInt()
        return cursorPattern(image, bodyX, bodyY, density)
    }

    private fun cursorAlong(
        image: Bitmap,
        swipe: SwipeCoordinates,
        density: Float,
    ): Boolean {
        val x = (swipe.start.x + 2f * density).roundToInt()
        val top =
            swipe.end.y
                .roundToInt()
                .coerceAtLeast(0)
        val bottom = (swipe.start.y + 22f * density).roundToInt().coerceAtMost(image.height - 1)
        return (top..bottom).any { y -> cursorPattern(image, x, y, density) }
    }

    private fun cursorPattern(
        image: Bitmap,
        x: Int,
        y: Int,
        density: Float,
    ): Boolean {
        val outlineX = (x - 2.5f * density).roundToInt()
        if (outlineX !in 0 until image.width || x !in 0 until image.width || y !in 0 until image.height) return false
        val body = image.getPixel(x, y)
        val outline = image.getPixel(outlineX, y)
        return Color.red(body) < 90 && Color.green(body) < 90 && Color.blue(body) < 110 &&
            Color.red(outline) > 190 && Color.green(outline) > 190 && Color.blue(outline) > 190
    }

    private fun dp(
        activity: MainActivity,
        value: Int,
    ): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private data class ScreenPoint(
        val x: Float,
        val y: Float,
    )

    private data class SwipeCoordinates(
        val start: ScreenPoint,
        val end: ScreenPoint,
    )

    private data class DemoContent(
        val button: Button,
        val status: TextView,
        val scroll: ScrollView,
        val taps: AtomicInteger,
    )

    companion object {
        private const val SERVICE_WAIT_MS = 10000L
        private const val SETTLE_MS = 600L
        private const val RECORD_PAUSE_MS = 3000L
        private const val TAP_CAPTURE_DELAY_MS = 400L
        private const val SWIPE_DURATION_MS = 1800L
    }
}
