package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test: WebView node collapsing reduces the node count on a large web page.
 *
 * Loads the bundled content-heavy WebView page (hundreds of articles → well over a thousand raw
 * accessibility nodes), confirms the raw node count (via `uiautomator dump`) exceeds 1000, then
 * confirms the collapsed `get_screen_state` output reports meaningfully fewer nodes.
 *
 * To ISOLATE the merge from the structural-only filtering that `get_screen_state` always applies,
 * the reduction is measured against the keep-FILTERED baseline (`uiAutomatorKeptNodeCount`) — i.e.
 * the nodes that would survive filtering BEFORE the merge — not the raw total. As a second,
 * independent proof that the collapse actually ran (and that Chromium populated `webRole`), it also
 * asserts the output contains image markdown (`![`), a wrapper produced ONLY by the merge.
 *
 * The content-preserving correctness of the collapse is covered exhaustively by
 * `WebViewNodeMergerTest` (JVM unit tests).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2EWebViewNodeReductionTest {

    private val mcpClient = SharedAndroidContainer.mcpClient

    companion object {
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        private const val MIN_RAW_NODES = 1000
        private const val MIN_REDUCTION = 0.25
        private const val SETTLE_ATTEMPTS = 3
        private const val SETTLE_WAIT_MS = 10_000L

        // Parses the "nodes:<start>-<end>/<total>" pagination marker; total = collapsed node count.
        private val NODES_TOTAL_REGEX = Regex("""nodes:\d+-\d+/(\d+)""")

        // A collapsed TSV data row: node id (hex hash) followed by the tab column separator.
        private val TSV_ROW_REGEX = Regex("""(?m)^node_[0-9a-f]+\t""")
    }

    @BeforeEach
    fun ensureAccessibility() {
        SharedAndroidContainer.ensureAccessibilityService()
    }

    @Test
    fun `heavy webview page node count is collapsed`() = runBlocking {
        mcpClient.callTool("${TOOL_PREFIX}press_home")
        Thread.sleep(1_000)

        AndroidContainerSetup.launchHeavyWebViewTestApp()

        // The web accessibility tree populates asynchronously; wait until it is large enough.
        var rawNodes = 0
        repeat(SETTLE_ATTEMPTS) { attempt ->
            rawNodes = AndroidContainerSetup.uiAutomatorNodeCount()
            println("[E2E NodeReduction] attempt ${attempt + 1}: raw uiautomator nodes=$rawNodes")
            if (rawNodes > MIN_RAW_NODES) return@repeat
            Thread.sleep(SETTLE_WAIT_MS)
        }
        assertTrue(
            rawNodes > MIN_RAW_NODES,
            "expected > $MIN_RAW_NODES raw nodes after ${SETTLE_ATTEMPTS} attempts, got $rawNodes",
        )

        // Keep-filtered baseline (nodes surviving filtering BEFORE the merge) isolates the merge.
        val keptRawNodes = AndroidContainerSetup.uiAutomatorKeptNodeCount()
        val result = mcpClient.callTool("${TOOL_PREFIX}get_screen_state")
        val text = (result.content[0] as TextContent).text
        val mergedNodes = collapsedNodeCount(text)
        val reduction = (keptRawNodes - mergedNodes).toDouble() / keptRawNodes
        println(
            "[E2E NodeReduction] raw=$rawNodes keptRaw=$keptRawNodes merged=$mergedNodes " +
                "merge-reduction=${"%.1f".format(reduction * 100)}%",
        )

        assertTrue(mergedNodes > 0, "collapsed node count should be positive (output:\n${text.take(500)})")
        assertTrue(keptRawNodes > 0, "keep-filtered baseline should be positive")
        assertTrue(
            reduction >= MIN_REDUCTION,
            "expected >= ${(MIN_REDUCTION * 100).toInt()}% merge reduction vs filtered baseline, got " +
                "${"%.1f".format(reduction * 100)}% (keptRaw=$keptRawNodes merged=$mergedNodes)",
        )
        // The "![" image-markdown wrapper is produced ONLY by the WebView collapse, so its presence
        // proves the merge actually ran (isolating it from the structural-only filtering that
        // get_screen_state always applies).
        assertTrue(
            text.contains("!["),
            "merged output must contain image markdown (proving the WebView collapse ran); " +
                "output excerpt:\n${text.take(800)}",
        )
    }

    /**
     * Returns the collapsed node count from a `get_screen_state` response: the pagination total when
     * the output is paged, otherwise the number of TSV data rows on the single page.
     */
    private fun collapsedNodeCount(screenState: String): Int {
        val paged = NODES_TOTAL_REGEX.find(screenState)?.groupValues?.get(1)?.toIntOrNull()
        if (paged != null) return paged
        return TSV_ROW_REGEX.findAll(screenState).count()
    }
}
