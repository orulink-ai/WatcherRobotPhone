package com.danielealbano.androidremotecontrolmcp.services.control

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotBounds
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutorImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Opt-in hardware acceptance of production input and feedback over our own native test content.
 * This does not navigate or mutate Xiaohongshu or any other app.
 * Enable with -e overlayGestureDemo true; -e recordDemo true adds four-second recording holds.
 */
class ControlOverlayGestureDeviceTest {
    private val acceptance = JSONObject()

    @Test
    fun productionTapAndSwipeReachOwnedContentAndDisplayTheRealCursor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Opt in to owned-UI gesture acceptance with overlayGestureDemo=true",
            arguments.getString("overlayGestureDemo") == "true",
        )
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline = SystemClock.uptimeMillis() + SERVICE_WAIT_MS
        while (McpAccessibilityService.instance == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100L)
        }
        val service = McpAccessibilityService.instance
        assumeNotNull("Enable the target app's accessibility service before running this acceptance", service)
        assumeTrue(
            "Enable animations to inspect the optional cursor approach phase",
            ValueAnimator.areAnimatorsEnabled(),
        )
        val entryPoint =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                McpAccessibilityService.NodeCacheEntryPoint::class.java,
            )
        val session = entryPoint.controlSession()
        assumeTrue("Do not interrupt another phone task", session.state.value.activeTool == null)
        assumeTrue("Resume local takeover before opting into gesture acceptance", !session.state.value.isPaused)
        // Rebinding the last accessibility service can disconnect an earlier cached automation connection.
        instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES or UiAutomation.FLAG_DONT_USE_ACCESSIBILITY,
        )
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val executor = ActionExecutorImpl(entryPoint.nodeCache(), AccessibilityTreeParser())
        val recordDemo = arguments.getString("recordDemo") == "true"
        var activity: MainActivity? = null
        try {
            val started = launchOwnedActivity(automation)
            activity = started
            ensureServerRunning(started)
            lateinit var content: DemoContent
            instrumentation.runOnMainSync {
                session.reset()
                content = createOwnedContent(started)
                session.enterControl()
            }
            SystemClock.sleep(SETTLE_MS)
            val demo = DemoRuntime(checkNotNull(service), session, executor, automation, content, started)
            assertNotNull("Local entry must attach the panel without a tool", panelFrame(demo, "entered"))
            assertVisible(demo)
            if (recordDemo) SystemClock.sleep(RECORD_PAUSE_MS)

            lateinit var tap: ScreenPoint
            lateinit var swipe: SwipeCoordinates
            instrumentation.runOnMainSync {
                tap = selectTap(demo)
                swipe =
                    SwipeCoordinates(
                        pointIn(content.scroll, 0.72f, 0.74f),
                        pointIn(content.scroll, 0.72f, 0.3f),
                    )
                assertEquals(0, content.taps.get())
                assertEquals(0, content.scroll.scrollY)
            }
            verifyTap(demo, tap)
            SystemClock.sleep(IDLE_GAP_MS)
            assertTrue("The session must remain engaged between tools", session.state.value.isEngaged)
            assertNull("The idle gap must contain no active tool", session.state.value.activeTool)
            assertNotNull("The idle panel must remain on screen", panelFrame(demo, "idle"))
            assertVisible(demo)
            verifySwipe(demo, swipe)
            verifyCaptureStaysVisible(demo)
            verifyExitAndReentry(demo, recordDemo)
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
            File(instrumentation.targetContext.cacheDir, "control-overlay-gesture-acceptance.json")
                .writeText(acceptance.toString(2))
        }
    }

    /** Lifecycle completion does not require an idle main queue while the live preview animates. */
    private fun launchOwnedActivity(automation: UiAutomation): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val externalToken = InstrumentationRegistry.getArguments().getString("ownedLaunchToken")
        val launchToken = externalToken ?: UUID.randomUUID().toString()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val resumed = CountDownLatch(1)
        val candidate = AtomicReference<MainActivity?>()
        val lastStage = AtomicReference<Stage?>()
        val callback =
            ActivityLifecycleCallback { observed, stage ->
                if (observed is MainActivity && observed.intent.getStringExtra(OWNED_LAUNCH_EXTRA) == launchToken) {
                    candidate.set(observed)
                    lastStage.set(stage)
                    if (stage == Stage.RESUMED) resumed.countDown()
                }
            }
        val component = ComponentName(context, MainActivity::class.java).flattenToString()
        check(component.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")))
        check(OWNED_LAUNCH_EXTRA.matches(Regex("[A-Za-z0-9_]+")))
        check(launchToken.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        val strategy = if (externalToken == null) "uiautomation-shell" else "external-harness"
        acceptance.put("activityLaunchStrategy", strategy).put("activityLaunchToken", launchToken)
        val command =
            "am start -n $component -f 0x18000000 " +
                "--es $OWNED_LAUNCH_EXTRA $launchToken"
        var successful = false
        val deadline = SystemClock.uptimeMillis() + ACTIVITY_START_WAIT_MS
        try {
            instrumentation.runOnMainSync {
                monitor.addLifecycleCallback(callback)
            }
            Log.i("ControlOverlayAcceptance", "OWNED_LAUNCH_READY token=$launchToken strategy=$strategy")
            if (externalToken == null) {
                // Only launches the owned task; the external harness can supply the same strict UUID instead.
                val descriptor = checkNotNull(automation.executeShellCommand(command))
                val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                val output = input.bufferedReader().use { it.readText() }
                acceptance.put("activityLaunchShellOutput", output.take(2000))
            }
            val didResume =
                resumed.await(
                    (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L),
                    TimeUnit.MILLISECONDS,
                )
            val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            acceptance.put(
                "activityLaunch",
                JSONObject().put("lastStage", lastStage.get()?.name ?: JSONObject.NULL).put("keyguardLocked", locked),
            )
            assertTrue(
                "Owned MainActivity did not reach RESUMED within 15 seconds; lastStage=${lastStage.get()}, " +
                    "keyguardLocked=$locked",
                didResume,
            )
            val activity = checkNotNull(candidate.get())
            assertEquals(launchToken, activity.intent.getStringExtra(OWNED_LAUNCH_EXTRA))
            successful = true
            return activity
        } finally {
            instrumentation.runOnMainSync {
                monitor.removeLifecycleCallback(callback)
                if (!successful) {
                    runCatching {
                        candidate
                            .get()
                            ?.takeIf {
                                it.intent.getStringExtra(OWNED_LAUNCH_EXTRA) == launchToken
                            }?.finishAndRemoveTask()
                    }
                    // A launch may create its task before the Activity reaches a lifecycle callback.
                    val tasks =
                        runCatching {
                            context.getSystemService(ActivityManager::class.java).appTasks
                        }.getOrDefault(emptyList())
                    tasks.forEach { task ->
                        val owned =
                            runCatching {
                                task.taskInfo?.baseIntent?.getStringExtra(OWNED_LAUNCH_EXTRA) == launchToken
                            }.getOrDefault(false)
                        if (owned) runCatching { task.finishAndRemoveTask() }
                    }
                }
            }
        }
    }

    private fun verifyTap(
        demo: DemoRuntime,
        tap: ScreenPoint,
    ) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val operation =
            async(Dispatchers.Default) {
                demo.session.runTool("tap") { demo.executor.tap(tap.x, tap.y) }.getOrThrow()
                instrumentation.runOnMainSync {
                    val completed = cursorSnapshot(demo)
                    assertEquals(ControlCursorPhase.COMPLETED.name, completed.getString("phase"))
                    assertEquals(demo.activity.getString(R.string.control_cursor_clicked), completed.getString("label"))
                    assertEquals("The owned button must receive exactly one actual tap", 1, demo.content.taps.get())
                    acceptance.put("tapCompleted", completed)
                }
                delay(TAP_CAPTURE_DELAY_MS)
                withContext(Dispatchers.IO) { capture(demo.automation, "tap") }
            }
        delay(APPROACH_PROBE_MS)
        instrumentation.runOnMainSync {
            val moving = cursorSnapshot(demo)
            assertEquals("No input may arrive during the approach", 0, demo.content.taps.get())
            assertEquals(ControlCursorPhase.APPROACH.name, moving.getString("phase"))
            assertEquals(demo.activity.getString(R.string.control_cursor_approach), moving.getString("label"))
            val distance = hypot(moving.getDouble("tipX") - tap.x, moving.getDouble("tipY") - tap.y)
            assertTrue("The pre-input pointer must still be moving toward the target", distance > 1.0)
            acceptance.put(
                "tapApproachAt50ms",
                moving.put("tapCount", demo.content.taps.get()).put("targetX", tap.x).put("targetY", tap.y),
            )
        }
        withContext(Dispatchers.IO) { capture(demo.automation, "approach") }.recycle()
        val image = operation.await()
        try {
            assertTrue(
                "Confirmed production feedback must retain the pointer at its actual target",
                cursorAt(image, tap, demo.activity.resources.displayMetrics.density),
            )
        } finally {
            image.recycle()
        }
    }

    private fun verifySwipe(
        demo: DemoRuntime,
        swipe: SwipeCoordinates,
    ) = runBlocking {
        val swipeImage =
            async(Dispatchers.IO) {
                delay(SWIPE_DURATION_MS / 2L)
                capture(demo.automation, "swipe")
            }
        demo.session
            .runTool("swipe") {
                demo.executor.swipe(swipe.start.x, swipe.start.y, swipe.end.x, swipe.end.y, SWIPE_DURATION_MS)
            }.getOrThrow()
        val image = swipeImage.await()
        try {
            assertTrue(
                "During real dispatch the pointer must occur along the owned scroll path",
                cursorAlong(image, swipe, demo.activity.resources.displayMetrics.density),
            )
        } finally {
            image.recycle()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("A real swipe must move the owned ScrollView", demo.content.scroll.scrollY > 0)
            acceptance.put("swipeScrollY", demo.content.scroll.scrollY)
        }
    }

    private fun verifyCaptureStaysVisible(demo: DemoRuntime) =
        runBlocking {
            // These are real AccessibilityService capture requests, including the normal 350ms gate.
            val captures =
                async(Dispatchers.IO) {
                    repeat(3) { index ->
                        assertNotNull("Each exact capture must carry panel bounds", panelFrame(demo, "capture-$index"))
                    }
                }
            var visibilitySamples = 0
            while (!captures.isCompleted) {
                assertVisible(demo)
                visibilitySamples++
                delay(16L)
            }
            captures.await()
            assertTrue("Visibility must be sampled while real capture requests are in flight", visibilitySamples > 0)
            acceptance.put("continuousCaptureVisibilitySamples", visibilitySamples)
        }

    private fun verifyExitAndReentry(
        demo: DemoRuntime,
        recordDemo: Boolean,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val serverBefore = McpServerService.serverStatus.value
        assertTrue("The actual local server must be running before control exit", serverBefore is ServerStatus.Running)
        instrumentation.runOnMainSync {
            demo.content.status.text = "退出 AI 控制 · 保持服务运行"
            demo.session.exitControl()
        }
        SystemClock.sleep(EXIT_SETTLE_MS)
        assertNull("The panel must detach after its exit transition", panelFrame(demo, "exited"))
        assertTrue("Explicit exit must leave the control session", !demo.session.state.value.isEngaged)
        assertSame(
            "Local mode exit must retain the accessibility service",
            demo.service,
            McpAccessibilityService.instance,
        )
        assertEquals(
            "Local mode exit must not change the server lifecycle",
            serverBefore,
            McpServerService.serverStatus.value,
        )
        assertTrue(
            "The actual local server must remain running after control exit",
            McpServerService.serverStatus.value is ServerStatus.Running,
        )
        acceptance.put("serverStateAcrossExit", serverBefore.toString())
        acceptance.put("serverAfterExit", McpServerService.serverStatus.value.toString())
        if (recordDemo) SystemClock.sleep(RECORD_PAUSE_MS)
        instrumentation.runOnMainSync { demo.session.enterControl() }
        SystemClock.sleep(SETTLE_MS)
        assertTrue(demo.session.state.value.isEngaged)
        assertNotNull("Local reentry must restore the same live overlay", panelFrame(demo, "reentered"))
        assertVisible(demo)
        assertTrue(
            "Local reentry must retain the running server",
            McpServerService.serverStatus.value is ServerStatus.Running,
        )
    }

    private fun ensureServerRunning(activity: MainActivity) {
        if (McpServerService.serverStatus.value !is ServerStatus.Running) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.startForegroundService(
                    Intent(activity, McpServerService::class.java).setAction(McpServerService.ACTION_START),
                )
            }
        }
        val deadline = SystemClock.uptimeMillis() + SERVER_START_WAIT_MS
        while (McpServerService.serverStatus.value !is ServerStatus.Running && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100L)
        }
        val status = McpServerService.serverStatus.value
        assertTrue("Foreground server did not reach Running within 15 seconds: $status", status is ServerStatus.Running)
        acceptance.put("serverBeforeEntry", status.toString())
    }

    private fun panelFrame(
        demo: DemoRuntime,
        label: String,
    ): ScreenshotBounds? =
        runBlocking {
            val frame = checkNotNull(demo.service.takeScreenshotFrame()) { "Production screenshot capture failed" }
            try {
                val metadata = checkNotNull(frame.captureMetadata)
                assertEquals(frame.bitmap.width, metadata.screenWidth)
                assertEquals(frame.bitmap.height, metadata.screenHeight)
                val bounds = metadata.panelBounds
                synchronized(acceptance) {
                    val panel =
                        bounds?.let {
                            JSONObject()
                                .put("left", it.left)
                                .put("top", it.top)
                                .put("right", it.right)
                                .put("bottom", it.bottom)
                        } ?: JSONObject.NULL
                    acceptance.put(
                        label,
                        JSONObject()
                            .put("width", metadata.screenWidth)
                            .put("height", metadata.screenHeight)
                            .put("panelBounds", panel),
                    )
                }
                val copy = checkNotNull(frame.bitmap.copy(Bitmap.Config.ARGB_8888, false))
                try {
                    save(copy, label)
                } finally {
                    copy.recycle()
                }
                bounds
            } finally {
                frame.bitmap.recycle()
            }
        }

    private fun assertVisible(demo: DemoRuntime) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val windows = productionWindows(demo.service)
            assertTrue("The real control window must remain attached", windows.attached)
            assertTrue("Capturing must not hide the real effects view", windows.effects.isShown)
            assertTrue("Capturing must not hide the real panel view", windows.panel.isShown)
            assertTrue("The settled panel must retain its visible opacity", windows.panel.alpha > 0.95f)
        }
    }

    /** Read-only View snapshots: canvas labels do not appear as accessibility text nodes. */
    private fun cursorSnapshot(demo: DemoRuntime): JSONObject {
        val windows = productionWindows(demo.service)
        val cursor = field(windows.effects, "cursor") as ControlCursorRenderer
        val phase = checkNotNull(cursor.phase)
        val traces = field(cursor, "traces") as List<*>
        val action =
            if (phase == ControlCursorPhase.APPROACH) {
                field(cursor, "action") as ControlCursorAction
            } else {
                field(checkNotNull(traces.firstOrNull()), "action") as ControlCursorAction
            }
        val tip = field(cursor, "lastTip") as? CursorPoint
        return JSONObject()
            .put("observedAtUptimeMs", SystemClock.uptimeMillis())
            .put("phase", phase.name)
            .put("action", action.name)
            .put("label", ControlCursorLabels(demo.service).text(action, phase))
            .put("tipX", tip?.x ?: JSONObject.NULL)
            .put("tipY", tip?.y ?: JSONObject.NULL)
    }

    private fun productionWindows(service: McpAccessibilityService): ControlOverlayWindows {
        val controller = checkNotNull(field(service, "controlOverlay"))
        return field(controller, "windows") as ControlOverlayWindows
    }

    private fun selectTap(demo: DemoRuntime): ScreenPoint {
        val preferred = pointIn(demo.content.button, 0.72f, 0.5f)
        val cursor = field(productionWindows(demo.service).effects, "cursor") as ControlCursorRenderer
        val previous = field(cursor, "lastTip") as? CursorPoint
        val nearby =
            previous != null && hypot(previous.x - preferred.x, previous.y - preferred.y) <
                40f * demo.activity.resources.displayMetrics.density
        // Repeated runs must exercise movement rather than a correct zero-distance approach.
        return if (nearby) pointIn(demo.content.button, 0.48f, 0.5f) else preferred
    }

    private fun field(
        owner: Any,
        name: String,
    ): Any? =
        owner.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(owner)
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
        save(image, label)
        return image
    }

    private fun save(
        image: Bitmap,
        label: String,
    ) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(directory, "control-overlay-gesture-$label.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
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

    private data class DemoRuntime(
        val service: McpAccessibilityService,
        val session: ControlSession,
        val executor: ActionExecutorImpl,
        val automation: UiAutomation,
        val content: DemoContent,
        val activity: MainActivity,
    )

    companion object {
        private const val SERVICE_WAIT_MS = 10000L
        private const val ACTIVITY_START_WAIT_MS = 15000L
        private const val OWNED_LAUNCH_EXTRA = "control_overlay_acceptance_launch_token"
        private const val SERVER_START_WAIT_MS = 15000L
        private const val SETTLE_MS = 600L
        private const val RECORD_PAUSE_MS = 4000L
        private const val TAP_CAPTURE_DELAY_MS = 400L
        private const val SWIPE_DURATION_MS = 1800L
        private const val APPROACH_PROBE_MS = 50L
        private const val IDLE_GAP_MS = 1000L
        private const val EXIT_SETTLE_MS = 700L
    }
}
