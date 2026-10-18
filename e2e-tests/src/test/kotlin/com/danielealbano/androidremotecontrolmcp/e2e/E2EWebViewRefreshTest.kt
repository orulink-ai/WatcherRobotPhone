package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test: WebView Accessibility Tree Refresh
 *
 * Verifies that the MCP server returns fresh accessibility tree data for
 * WebView-based apps. WebView creates virtual accessibility nodes via
 * AccessibilityNodeProvider for each DOM element. These nodes can go stale
 * when page content changes via JavaScript.
 *
 * This test validates that get_screen_state clears the framework accessibility
 * node cache before reading. Chromium WebView suppresses/throttles the
 * TYPE_WINDOW_CONTENT_CHANGED events that would invalidate that cache, so after a
 * JavaScript DOM change the cache — and every subsequent read — stays stale;
 * per-node refresh() alone cannot re-fetch what the cache still serves as current.
 * Clearing the cache first (see AccessibilityServiceProvider.clearFrameworkNodeCache)
 * forces the read to round-trip live.
 *
 * Test flow:
 * 1. Launch a WebView activity that displays "Number: 0"
 * 2. Verify the initial value via get_screen_state
 * 3. Send intents to update the number 5 times via evaluateJavascript
 * 4. After each change, verify the new value is visible in get_screen_state
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EWebViewRefreshTest {

    private val mcpClient = SharedAndroidContainer.mcpClient

    companion object {
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX

        // Failure ceilings for the poll loop below, NOT expected durations: waitForTextInScreenState
        // returns the instant the text appears, so these only bound how long a genuine failure waits
        // before the test reports it. App launch (cold WebView process + page load) is the slower of
        // the two; a JS DOM update reflects on the next get_screen_state poll now that the fresh read
        // clears the framework a11y cache first.
        private const val APP_LAUNCH_TIMEOUT_MS = 20_000L
        private const val NUMBER_UPDATE_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 500L
        private const val UPDATE_COUNT = 5
    }

    @BeforeEach
    fun ensureAccessibility() {
        SharedAndroidContainer.ensureAccessibilityService()
        // Start from a CLEAN WebView. E2EWebViewNodeReductionTest leaves a heavy 2808-node page loaded in
        // the shared WebViewActivity; inheriting it both blocks the activity's main thread on launch and
        // leaves the WebView's virtual a11y nodes stuck, so a later simple-page DOM change never reflects.
        // Force-stopping the app makes the next launch a fresh process on the simple counter page.
        AndroidContainerSetup.forceStopComposeTestApp()
    }

    @Test
    @Order(1)
    fun `webview shows initial value zero`() = runBlocking {
        mcpClient.callTool("${TOOL_PREFIX}press_home")

        AndroidContainerSetup.launchWebViewTestApp()

        val screenText = waitForTextInScreenState("Number: 0", APP_LAUNCH_TIMEOUT_MS)
        assertNotNull(
            screenText,
            "WebView test app should show 'Number: 0' within ${APP_LAUNCH_TIMEOUT_MS}ms",
        )
    }

    @Test
    @Order(2)
    fun `accessibility tree reflects webview dom changes via javascript`() = runBlocking {
        AndroidContainerSetup.launchWebViewTestApp()
        val initialScreen = waitForTextInScreenState("Number:", APP_LAUNCH_TIMEOUT_MS)
        assertNotNull(initialScreen, "WebView test app should be visible")

        for (i in 1..UPDATE_COUNT) {
            AndroidContainerSetup.sendWebViewTestNumber(i)

            val expectedText = "Number: $i"
            val screenText = waitForTextInScreenState(expectedText, NUMBER_UPDATE_TIMEOUT_MS)

            if (screenText == null) {
                val logcat = AndroidContainerSetup.dumpWebViewTestAppLogs()
                println("[E2E WebViewRefresh] Logcat from WebViewTestApp:\n$logcat")

                val diagnosticResult = mcpClient.callTool("${TOOL_PREFIX}get_screen_state")
                val diagnosticText = (diagnosticResult.content[0] as? TextContent)?.text ?: "N/A"
                fail<Unit>(
                    "Accessibility tree did not reflect '$expectedText' within " +
                        "${NUMBER_UPDATE_TIMEOUT_MS}ms (update $i of $UPDATE_COUNT). " +
                        "Logcat:\n$logcat\n" +
                        "Screen state excerpt: ${diagnosticText.take(1000)}",
                )
            }

            println("[E2E WebViewRefresh] Update $i/$UPDATE_COUNT: '$expectedText' confirmed in tree")
        }
    }

    @Test
    @Order(3)
    fun `find_nodes reflects webview dom changes without a get_screen_state read`() =
        runBlocking {
            // The cache-clear lives in the shared getFreshWindows path, so a read that never goes
            // through get_screen_state must ALSO see fresh WebView content. find_nodes(by=text) reads
            // the tree directly by selector — no prior node id, no get_screen_state.
            AndroidContainerSetup.launchWebViewTestApp()
            assertTrue(
                waitForFindNodesMatch("Number: 0", APP_LAUNCH_TIMEOUT_MS),
                "find_nodes should locate 'Number: 0' after launch",
            )

            for (i in 1..UPDATE_COUNT) {
                AndroidContainerSetup.sendWebViewTestNumber(i)

                val expectedText = "Number: $i"
                if (!waitForFindNodesMatch(expectedText, NUMBER_UPDATE_TIMEOUT_MS)) {
                    val logcat = AndroidContainerSetup.dumpWebViewTestAppLogs()
                    fail<Unit>(
                        "find_nodes did not locate '$expectedText' within ${NUMBER_UPDATE_TIMEOUT_MS}ms " +
                            "(update $i of $UPDATE_COUNT). Logcat:\n$logcat",
                    )
                }
                println("[E2E WebViewRefresh] find_nodes update $i/$UPDATE_COUNT: '$expectedText' located")
            }
        }

    private suspend fun waitForTextInScreenState(
        expectedText: String,
        timeoutMs: Long,
    ): String? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val result = mcpClient.callTool("${TOOL_PREFIX}get_screen_state")
                if (result.isError != true) {
                    val text = (result.content[0] as? TextContent)?.text ?: ""
                    if (text.contains(expectedText)) {
                        return text
                    }
                }
            } catch (_: Exception) {
                // Transient error, retry
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Polls the `find_nodes` tool (by text) until at least one node matches [expectedText] or
     * [timeoutMs] elapses. Unlike [waitForTextInScreenState] this never calls `get_screen_state`,
     * so it exercises the shared getFreshWindows read path on its own.
     */
    private suspend fun waitForFindNodesMatch(
        expectedText: String,
        timeoutMs: Long,
    ): Boolean {
        val params = mapOf<String, Any?>("by" to "text", "value" to expectedText)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val result = mcpClient.callTool("${TOOL_PREFIX}find_nodes", params)
                if (result.isError != true) {
                    val text = (result.content[0] as? TextContent)?.text ?: ""
                    val nodes = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject["nodes"]?.jsonArray
                    if (nodes != null && nodes.isNotEmpty()) {
                        return true
                    }
                }
            } catch (_: Exception) {
                // Transient error, retry
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }
}
