@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("UtilityTools")
class UtilityToolsTest {
    private val mockTreeParser = mockk<AccessibilityTreeParser>()
    private val mockElementFinder = mockk<ElementFinder>()
    private val mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()
    private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
    private val mockRootNode = mockk<AccessibilityNodeInfo>()
    private val mockWindowInfo = mockk<AccessibilityWindowInfo>()
    private val mockClipboardManager = mockk<ClipboardManager>()
    private val mockContext = mockk<Context>()

    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
        )

    private val sampleWindows =
        listOf(
            WindowData(
                windowId = 0,
                windowType = "APPLICATION",
                packageName = "com.example",
                title = "Test",
                activityName = ".Main",
                layer = 0,
                focused = true,
                tree = sampleTree,
            ),
        )

    private val sampleBounds = BoundsData(50, 800, 250, 1000)

    private val sampleElementInfo =
        ElementInfo(
            id = "node_abc",
            text = "Result",
            className = "android.widget.TextView",
            bounds = sampleBounds,
            clickable = false,
            enabled = true,
            visible = true,
        )

    private fun extractTextContent(result: CallToolResult): String {
        assertEquals(1, result.content.size)
        val textContent = result.content[0] as TextContent
        return textContent.text
    }

    @Suppress("LongMethod")
    @BeforeEach
    fun setUp() {
        every { mockAccessibilityServiceProvider.isReady() } returns true
        every { mockAccessibilityServiceProvider.clearFrameworkNodeCache() } returns Unit
        every { mockWindowInfo.id } returns 0
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockWindowInfo.recycle() } returns Unit
        every { mockRootNode.refresh() } returns true
        every { mockRootNode.packageName } returns "com.example"
        // Raw-node walk support: rawNodeExists() reads these properties directly
        every { mockRootNode.text } returns null
        every { mockRootNode.contentDescription } returns null
        every { mockRootNode.viewIdResourceName } returns null
        every { mockRootNode.className } returns null
        every { mockRootNode.childCount } returns 0
        every { mockRootNode.availableExtraData } returns emptyList()
        every {
            mockAccessibilityServiceProvider.getAccessibilityWindows()
        } returns listOf(mockWindowInfo)
        every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
        every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
        every { mockAccessibilityServiceProvider.getContext() } returns mockContext
        every { mockContext.getSystemService(ClipboardManager::class.java) } returns mockClipboardManager
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("GetClipboardTool")
    inner class GetClipboardToolTests {
        private val tool = GetClipboardTool(mockAccessibilityServiceProvider, PrivacyToolTestDoubles.passthroughGate())

        @Test
        fun `returns clipboard text`() =
            runTest {
                val mockClip = mockk<ClipData>()
                val mockItem = mockk<ClipData.Item>()
                every { mockClipboardManager.primaryClip } returns mockClip
                every { mockClip.itemCount } returns 1
                every { mockClip.getItemAt(0) } returns mockItem
                every { mockItem.text } returns "copied text"

                val result = tool.execute(null)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                assertEquals("copied text", parsed["text"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns null when clipboard empty`() =
            runTest {
                every { mockClipboardManager.primaryClip } returns null

                val result = tool.execute(null)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                assertTrue(
                    parsed["text"]?.jsonPrimitive?.content == null ||
                        parsed["text"]?.jsonPrimitive?.content == "null",
                )
            }
    }

    @Nested
    @DisplayName("SetClipboardTool")
    inner class SetClipboardToolTests {
        private val tool =
            SetClipboardTool(mockAccessibilityServiceProvider, PrivacyToolTestDoubles.identitySubstitutor())

        @Test
        fun `sets clipboard text`() =
            runTest {
                every { mockClipboardManager.setPrimaryClip(any()) } returns Unit
                val params = buildJsonObject { put("text", "new content") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Clipboard set"))
                verify { mockClipboardManager.setPrimaryClip(any()) }
            }

        @Test
        fun `throws error when text missing`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("WaitForNodeTool")
    inner class WaitForNodeToolTests {
        private val tool =
            WaitForNodeTool(
                mockTreeParser,
                mockElementFinder,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
            )

        @Test
        fun `finds node on first attempt`() =
            runTest {
                every { mockRootNode.text } returns "Result"
                every {
                    mockElementFinder.findElements(sampleWindows, FindBy.TEXT, "Result", false)
                } returns listOf(sampleElementInfo)
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "Result")
                        put("timeout", 5000)
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                assertEquals(true, parsed["found"]?.jsonPrimitive?.content?.toBoolean())
                assertEquals(
                    "node_abc",
                    parsed["node"]
                        ?.jsonObject
                        ?.get("node_id")
                        ?.jsonPrimitive
                        ?.content,
                )
            }

        @Test
        fun `clears the framework node cache before the raw poll read`() =
            runTest {
                // wait_for_node's Phase-1 raw check (rawNodeExists) reads the tree directly (not via
                // get_screen_state), so on a JS-mutated WebView it would otherwise poll a stale
                // framework cache and never see a freshly-added node. The clear MUST precede that read.
                //
                // The node is deliberately ABSENT so the raw check stays false and Phase 2
                // (getFreshWindows, which ALSO clears) never runs — otherwise a subsequence
                // verifyOrder would be satisfied by Phase 2 alone and this test would still pass even
                // if the rawNodeExists clear were removed. SystemClock is mocked so the poll loop
                // times out immediately instead of blocking for the real timeout.
                mockkStatic(SystemClock::class)
                try {
                    var clockMs = 0L
                    every { SystemClock.elapsedRealtime() } answers {
                        val current = clockMs
                        clockMs += 200L
                        current
                    }
                    val params =
                        buildJsonObject {
                            put("by", "text")
                            put("value", "Missing")
                            put("timeout", 2000)
                        }

                    tool.execute(params)

                    verifyOrder {
                        mockAccessibilityServiceProvider.clearFrameworkNodeCache()
                        mockAccessibilityServiceProvider.getAccessibilityWindows()
                    }
                } finally {
                    unmockkStatic(SystemClock::class)
                }
            }

        @Test
        fun `throws error for invalid by parameter`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "invalid")
                        put("value", "test")
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error for empty value`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "")
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error for timeout exceeding max`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "test")
                        put("timeout", 50000)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Timeout must be between"))
            }

        @Test
        fun `throws error for negative timeout`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "test")
                        put("timeout", -1)
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `finds node after multiple poll attempts`() =
            runTest {
                // Raw node returns no match for calls 1-2, match on call 3+
                var rawCallCount = 0
                every { mockRootNode.text } answers {
                    rawCallCount++
                    if (rawCallCount >= 3) "Delayed" else null
                }
                every {
                    mockElementFinder.findElements(any<List<WindowData>>(), eq(FindBy.TEXT), eq("Delayed"), eq(false))
                } returns listOf(sampleElementInfo)
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "Delayed")
                        put("timeout", 10000)
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                assertEquals(true, parsed["found"]?.jsonPrimitive?.content?.toBoolean())
                assertTrue(parsed["attempts"]?.jsonPrimitive?.content?.toInt()!! >= 3)
            }

        @Test
        fun `returns timed out message when node never found`() =
            runTest {
                mockkStatic(SystemClock::class)
                try {
                    var clockMs = 0L
                    every { SystemClock.elapsedRealtime() } answers {
                        val current = clockMs
                        clockMs += 200L
                        current
                    }
                    // No findElements mock needed — rawNodeExists returns false,
                    // findElements is never called
                    val params =
                        buildJsonObject {
                            put("by", "text")
                            put("value", "Missing")
                            put("timeout", 2000)
                        }

                    val result = tool.execute(params)
                    val text = extractTextContent(result)
                    assertTrue(text.contains("timed out"))
                } finally {
                    unmockkStatic(SystemClock::class)
                }
            }

        @Test
        fun `rawNodeExists returns true on first matching node and exits early`() {
            val child0 = mockk<AccessibilityNodeInfo>()
            every { child0.text } returns "Match"
            every { child0.contentDescription } returns null
            every { child0.viewIdResourceName } returns "com.example:id/child0"
            every { child0.className } returns "android.widget.TextView"
            every { child0.childCount } returns 0
            every { child0.availableExtraData } returns emptyList()
            every { child0.refresh() } returns true

            every { mockRootNode.childCount } returns 3
            every { mockRootNode.getChild(0) } returns child0

            val result = rawNodeExists(FindBy.TEXT, "Match", mockAccessibilityServiceProvider)
            assertTrue(result)

            // Second and third children should NOT be accessed (early exit)
            verify(exactly = 0) { mockRootNode.getChild(1) }
            verify(exactly = 0) { mockRootNode.getChild(2) }
        }

        @Test
        fun `rawNodeExists refreshes virtual and Compose child nodes`() {
            // Child with viewIdResourceName == null -> should be refreshed
            val virtualChild = mockk<AccessibilityNodeInfo>()
            every { virtualChild.text } returns null
            every { virtualChild.contentDescription } returns null
            every { virtualChild.viewIdResourceName } returns null
            every { virtualChild.className } returns "android.widget.TextView"
            every { virtualChild.childCount } returns 0
            every { virtualChild.availableExtraData } returns emptyList()
            every { virtualChild.refresh() } returns true

            // Child with Compose semantics key -> should be refreshed
            val composeChild = mockk<AccessibilityNodeInfo>()
            every { composeChild.text } returns null
            every { composeChild.contentDescription } returns null
            every { composeChild.viewIdResourceName } returns "com.example:id/compose"
            every { composeChild.className } returns "android.widget.TextView"
            every { composeChild.childCount } returns 0
            every { composeChild.availableExtraData } returns
                listOf(
                    AccessibilityTreeParser.COMPOSE_SEMANTICS_ID_KEY,
                )
            every { composeChild.refresh() } returns true

            // Child with non-null resourceId and no Compose key -> should NOT be refreshed
            val realChild = mockk<AccessibilityNodeInfo>()
            every { realChild.text } returns null
            every { realChild.contentDescription } returns null
            every { realChild.viewIdResourceName } returns "com.example:id/real"
            every { realChild.className } returns "android.widget.Button"
            every { realChild.childCount } returns 0
            every { realChild.availableExtraData } returns emptyList()
            every { realChild.refresh() } returns true

            every { mockRootNode.childCount } returns 3
            every { mockRootNode.getChild(0) } returns virtualChild
            every { mockRootNode.getChild(1) } returns composeChild
            every { mockRootNode.getChild(2) } returns realChild

            rawNodeExists(FindBy.TEXT, "nonexistent", mockAccessibilityServiceProvider)

            verify { virtualChild.refresh() }
            verify { composeChild.refresh() }
            verify(exactly = 0) { realChild.refresh() }
        }

        @Test
        fun `rawNodeExists respects MAX_TREE_DEPTH - stops recursion beyond depth 100`() {
            // Build a chain of nodes deeper than MAX_TREE_DEPTH (100)
            // with the matching node at depth 101
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            for (i in 0..101) {
                val node = mockk<AccessibilityNodeInfo>()
                every { node.text } returns if (i == 101) "DeepMatch" else null
                every { node.contentDescription } returns null
                every { node.viewIdResourceName } returns "com.example:id/node$i"
                every { node.className } returns "android.widget.FrameLayout"
                every { node.availableExtraData } returns emptyList()
                every { node.refresh() } returns true
                nodes.add(node)
            }
            // Wire up the chain
            for (i in 0 until nodes.size - 1) {
                every { nodes[i].childCount } returns 1
                every { nodes[i].getChild(0) } returns nodes[i + 1]
            }
            every { nodes.last().childCount } returns 0

            // Override root node to be the first in the chain
            every { mockWindowInfo.root } returns nodes[0]

            val result = rawNodeExists(FindBy.TEXT, "DeepMatch", mockAccessibilityServiceProvider)
            assertTrue(!result, "Should NOT find match at depth 101 (beyond MAX_TREE_DEPTH)")
        }

        @Test
        fun `rawNodeExists finds match at exactly MAX_TREE_DEPTH`() {
            // Build a chain of nodes with the matching node at exactly depth 100
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            for (i in 0..100) {
                val node = mockk<AccessibilityNodeInfo>()
                every { node.text } returns if (i == 100) "AtLimit" else null
                every { node.contentDescription } returns null
                every { node.viewIdResourceName } returns "com.example:id/node$i"
                every { node.className } returns "android.widget.FrameLayout"
                every { node.availableExtraData } returns emptyList()
                every { node.refresh() } returns true
                nodes.add(node)
            }
            for (i in 0 until nodes.size - 1) {
                every { nodes[i].childCount } returns 1
                every { nodes[i].getChild(0) } returns nodes[i + 1]
            }
            every { nodes.last().childCount } returns 0

            every { mockWindowInfo.root } returns nodes[0]

            val result = rawNodeExists(FindBy.TEXT, "AtLimit", mockAccessibilityServiceProvider)
            assertTrue(result, "Should find match at exactly MAX_TREE_DEPTH (depth 100)")
        }

        @Test
        fun `rawNodeExists skips windows with null roots and finds match in remaining windows`() {
            val nullRootWindow1 = mockk<AccessibilityWindowInfo>(relaxed = true)
            val nullRootWindow2 = mockk<AccessibilityWindowInfo>(relaxed = true)
            val validWindow = mockk<AccessibilityWindowInfo>(relaxed = true)

            every { nullRootWindow1.root } returns null
            every { nullRootWindow2.root } returns null

            val validRoot = mockk<AccessibilityNodeInfo>()
            every { validRoot.text } returns "FoundInThirdWindow"
            every { validRoot.contentDescription } returns null
            every { validRoot.viewIdResourceName } returns null
            every { validRoot.className } returns null
            every { validRoot.childCount } returns 0
            every { validRoot.availableExtraData } returns emptyList()
            every { validRoot.refresh() } returns true
            every { validWindow.root } returns validRoot

            every {
                mockAccessibilityServiceProvider.getAccessibilityWindows()
            } returns listOf(nullRootWindow1, nullRootWindow2, validWindow)

            val result = rawNodeExists(FindBy.TEXT, "FoundInThirdWindow", mockAccessibilityServiceProvider)
            assertTrue(result)

            // Verify all windows are recycled
            verify { nullRootWindow1.recycle() }
            verify { nullRootWindow2.recycle() }
            verify { validWindow.recycle() }
        }

        @Test
        fun `rawNodeExists falls back to single root node when no windows available`() {
            every {
                mockAccessibilityServiceProvider.getAccessibilityWindows()
            } returns emptyList()

            val fallbackRoot = mockk<AccessibilityNodeInfo>()
            every { fallbackRoot.text } returns "FallbackMatch"
            every { fallbackRoot.contentDescription } returns null
            every { fallbackRoot.viewIdResourceName } returns null
            every { fallbackRoot.className } returns null
            every { fallbackRoot.childCount } returns 0
            every { fallbackRoot.availableExtraData } returns emptyList()
            every { fallbackRoot.refresh() } returns true
            every { mockAccessibilityServiceProvider.getRootNode() } returns fallbackRoot

            val result = rawNodeExists(FindBy.TEXT, "FallbackMatch", mockAccessibilityServiceProvider)
            assertTrue(result)
        }

        @Test
        fun `rawNodeExists throws PermissionDenied when not ready`() {
            every { mockAccessibilityServiceProvider.isReady() } returns false

            assertThrows<McpToolException.PermissionDenied> {
                rawNodeExists(FindBy.TEXT, "anything", mockAccessibilityServiceProvider)
            }
        }

        @Test
        fun `rawNodeExists throws ActionFailed when no windows and no root node`() {
            every {
                mockAccessibilityServiceProvider.getAccessibilityWindows()
            } returns emptyList()
            every { mockAccessibilityServiceProvider.getRootNode() } returns null

            assertThrows<McpToolException.ActionFailed> {
                rawNodeExists(FindBy.TEXT, "anything", mockAccessibilityServiceProvider)
            }
        }

        @Test
        fun `race condition - raw check finds but full parse misses - continues polling`() =
            runTest {
                // First call: raw finds match but findElements returns empty (race)
                // Second call: both find match
                var rawCallCount = 0
                every { mockRootNode.text } answers {
                    rawCallCount++
                    "RaceTarget"
                }
                var findCallCount = 0
                every {
                    mockElementFinder.findElements(
                        any<List<WindowData>>(),
                        eq(FindBy.TEXT),
                        eq("RaceTarget"),
                        eq(false),
                    )
                } answers {
                    findCallCount++
                    if (findCallCount >= 2) listOf(sampleElementInfo) else emptyList()
                }
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "RaceTarget")
                        put("timeout", 10000)
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                assertEquals(true, parsed["found"]?.jsonPrimitive?.content?.toBoolean())
                assertTrue(parsed["attempts"]?.jsonPrimitive?.content?.toInt()!! >= 2)
            }

        @Test
        fun `all four FindBy criteria work with rawNodeExists`() {
            // TEXT
            every { mockRootNode.text } returns "TextMatch"
            assertTrue(rawNodeExists(FindBy.TEXT, "TextMatch", mockAccessibilityServiceProvider))
            every { mockRootNode.text } returns null

            // CONTENT_DESC
            every { mockRootNode.contentDescription } returns "DescMatch"
            assertTrue(rawNodeExists(FindBy.CONTENT_DESC, "DescMatch", mockAccessibilityServiceProvider))
            every { mockRootNode.contentDescription } returns null

            // RESOURCE_ID
            every { mockRootNode.viewIdResourceName } returns "com.example:id/resMatch"
            assertTrue(rawNodeExists(FindBy.RESOURCE_ID, "resMatch", mockAccessibilityServiceProvider))
            every { mockRootNode.viewIdResourceName } returns null

            // CLASS_NAME
            every { mockRootNode.className } returns "android.widget.Button"
            assertTrue(rawNodeExists(FindBy.CLASS_NAME, "Button", mockAccessibilityServiceProvider))
        }

        @Test
        fun `rawNodeExists uses case-insensitive contains matching`() {
            every { mockRootNode.text } returns "Hello World"

            assertTrue(rawNodeExists(FindBy.TEXT, "hello", mockAccessibilityServiceProvider))
            assertTrue(rawNodeExists(FindBy.TEXT, "HELLO", mockAccessibilityServiceProvider))
            assertTrue(rawNodeExists(FindBy.TEXT, "World", mockAccessibilityServiceProvider))
        }

        @Test
        fun `rawNodeExists recycles AccessibilityWindowInfo objects`() {
            every { mockRootNode.text } returns "Found"

            rawNodeExists(FindBy.TEXT, "Found", mockAccessibilityServiceProvider)

            verify { mockWindowInfo.recycle() }
        }

        @Test
        fun `rawNodeExists recycles AccessibilityWindowInfo objects on exception`() {
            val badRoot = mockk<AccessibilityNodeInfo>()
            every { badRoot.refresh() } returns true
            every { badRoot.text } returns null
            every { badRoot.contentDescription } returns null
            every { badRoot.viewIdResourceName } returns null
            every { badRoot.className } returns null
            every { badRoot.childCount } returns 1
            every { badRoot.availableExtraData } returns emptyList()
            every { badRoot.getChild(0) } throws RuntimeException("Simulated crash")

            every { mockWindowInfo.root } returns badRoot

            try {
                rawNodeExists(FindBy.TEXT, "anything", mockAccessibilityServiceProvider)
            } catch (_: RuntimeException) {
                // Expected
            }

            // Windows should still be recycled despite exception
            verify { mockWindowInfo.recycle() }
        }

        @Test
        fun `rawNodeExists returns false when node property is null`() {
            // All properties are null by default from setUp()
            // Searching for any value should return false
            val result = rawNodeExists(FindBy.TEXT, "anything", mockAccessibilityServiceProvider)
            assertTrue(!result)
        }

        @Test
        fun `rawNodeExists skips null children and continues searching`() {
            val matchChild = mockk<AccessibilityNodeInfo>()
            every { matchChild.text } returns "FoundAfterNull"
            every { matchChild.contentDescription } returns null
            every { matchChild.viewIdResourceName } returns "com.example:id/match"
            every { matchChild.className } returns "android.widget.TextView"
            every { matchChild.childCount } returns 0
            every { matchChild.availableExtraData } returns emptyList()
            every { matchChild.refresh() } returns true

            every { mockRootNode.childCount } returns 3
            every { mockRootNode.getChild(0) } returns null
            every { mockRootNode.getChild(1) } returns matchChild
            every { mockRootNode.getChild(2) } returns null

            val result = rawNodeExists(FindBy.TEXT, "FoundAfterNull", mockAccessibilityServiceProvider)
            assertTrue(result)
        }
    }

    @Nested
    @DisplayName("WaitForIdleTool")
    inner class WaitForIdleToolTests {
        private val tool = WaitForIdleTool(mockAccessibilityServiceProvider)

        private fun mockRawNode(
            className: String = "android.widget.FrameLayout",
            text: String? = null,
            bounds: BoundsData = BoundsData(0, 0, 1080, 2400),
            children: List<AccessibilityNodeInfo> = emptyList(),
        ): AccessibilityNodeInfo {
            val node = mockk<AccessibilityNodeInfo>()
            every { node.className } returns className
            every { node.text } returns text
            val rectSlot = slot<Rect>()
            every { node.getBoundsInScreen(capture(rectSlot)) } answers {
                rectSlot.captured.left = bounds.left
                rectSlot.captured.top = bounds.top
                rectSlot.captured.right = bounds.right
                rectSlot.captured.bottom = bounds.bottom
            }
            every { node.childCount } returns children.size
            for (i in children.indices) {
                every { node.getChild(i) } returns children[i]
            }
            return node
        }

        @Test
        fun `detects idle when tree does not change`() =
            runTest {
                // Same raw node returned each time -> idle detected
                val stableRoot = mockRawNode()
                every { mockWindowInfo.root } returns stableRoot

                val params = buildJsonObject { put("timeout", 5000) }
                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("idle") == true)
                assertEquals(100, parsed["similarity"]?.jsonPrimitive?.int)
            }

        @Test
        fun `clears the framework node cache before fingerprinting`() =
            runTest {
                // wait_for_idle fingerprints the raw tree directly, so on a JS-mutated WebView a
                // stale framework cache would make the fingerprint look unchanged and report idle
                // while content is still updating. The clear MUST precede the read.
                val stableRoot = mockRawNode()
                every { mockWindowInfo.root } returns stableRoot

                val params = buildJsonObject { put("timeout", 5000) }
                tool.execute(params)

                verifyOrder {
                    mockAccessibilityServiceProvider.clearFrameworkNodeCache()
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                }
            }

        @Test
        fun `match_percentage defaults to 100 when not provided`() =
            runTest {
                // Same raw node each time -> idle at 100% similarity
                val stableRoot = mockRawNode()
                every { mockWindowInfo.root } returns stableRoot

                val params = buildJsonObject { put("timeout", 5000) }
                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                assertEquals(100, parsed["similarity"]?.jsonPrimitive?.int)
            }

        @Test
        fun `detects idle with match_percentage below 100 when tree has minor changes`() =
            runTest {
                mockkStatic(SystemClock::class)
                try {
                    var clockMs = 0L
                    every { SystemClock.elapsedRealtime() } answers { clockMs }

                    // Build raw tree with 10 stable children + 1 changing child
                    var callCount = 0
                    every { mockWindowInfo.root } answers {
                        callCount++
                        clockMs += 300L
                        val stableChildren =
                            (1 until 10).map { i ->
                                mockRawNode(
                                    className = "android.widget.TextView",
                                    text = "stable_$i",
                                    bounds = BoundsData(0, i * 100, 1080, (i + 1) * 100),
                                )
                            }
                        val changingChild =
                            mockRawNode(
                                className = "android.widget.TextView",
                                text = "changing_$callCount",
                                bounds = BoundsData(0, 0, 1080, 100),
                            )
                        mockRawNode(children = listOf(changingChild) + stableChildren)
                    }

                    // With match_percentage=80, the minor change should still be considered idle
                    val params =
                        buildJsonObject {
                            put("timeout", 10000)
                            put("match_percentage", 80)
                        }
                    val result = tool.execute(params)
                    val text = extractTextContent(result)
                    val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                    assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("idle") == true)
                    val similarity = parsed["similarity"]?.jsonPrimitive?.int ?: 0
                    assertTrue(similarity in 80..100, "Expected similarity between 80 and 100, got $similarity")
                } finally {
                    unmockkStatic(SystemClock::class)
                }
            }

        @Test
        fun `throws error for match_percentage above 100`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("timeout", 5000)
                        put("match_percentage", 101)
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error for negative match_percentage`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("timeout", 5000)
                        put("match_percentage", -1)
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `timeout response includes similarity field`() =
            runTest {
                mockkStatic(SystemClock::class)
                try {
                    var clockMs = 0L
                    every { SystemClock.elapsedRealtime() } answers { clockMs }

                    // Return different raw tree each time to force timeout
                    var callCount = 0
                    every { mockWindowInfo.root } answers {
                        callCount++
                        clockMs += 600L
                        mockRawNode(text = "changing_$callCount")
                    }

                    val params = buildJsonObject { put("timeout", 1000) }
                    val result = tool.execute(params)
                    val text = extractTextContent(result)
                    val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                    assertTrue(parsed.containsKey("similarity"))
                    assertTrue(parsed.containsKey("elapsedMs"))
                    assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("timed out") == true)
                } finally {
                    unmockkStatic(SystemClock::class)
                }
            }

        @Test
        fun `throws error for timeout exceeding max`() =
            runTest {
                val params = buildJsonObject { put("timeout", 50000) }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error for zero timeout`() =
            runTest {
                val params = buildJsonObject { put("timeout", 0) }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws permission denied when accessibility service not ready`() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                val params = buildJsonObject { put("timeout", 5000) }

                assertThrows<McpToolException.PermissionDenied> { tool.execute(params) }
            }

        @Test
        fun `falls back to root node when no accessibility windows available`() =
            runTest {
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns emptyList()
                val stableRoot = mockRawNode()
                every { mockAccessibilityServiceProvider.getRootNode() } returns stableRoot

                val params = buildJsonObject { put("timeout", 5000) }
                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("idle") == true)
            }

        @Test
        fun `times out when all windows return null root nodes`() =
            runTest {
                mockkStatic(SystemClock::class)
                try {
                    var clockMs = 0L
                    every { SystemClock.elapsedRealtime() } answers { clockMs }
                    every { mockWindowInfo.root } returns null
                    every {
                        mockAccessibilityServiceProvider.getAccessibilityWindows()
                    } answers {
                        clockMs += 600L
                        listOf(mockWindowInfo)
                    }

                    val params = buildJsonObject { put("timeout", 1000) }
                    val result = tool.execute(params)
                    val text = extractTextContent(result)
                    assertTrue(text.contains("timed out"))
                } finally {
                    unmockkStatic(SystemClock::class)
                }
            }
    }
}
