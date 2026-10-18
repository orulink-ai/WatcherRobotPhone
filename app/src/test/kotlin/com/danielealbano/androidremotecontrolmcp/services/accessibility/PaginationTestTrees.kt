package com.danielealbano.androidremotecontrolmcp.services.accessibility

/**
 * Shared test infrastructure for get_screen_state pagination tests (Plan 47). Reused by
 * CompactTreeFormatterTest, ScreenIntrospectionToolsTest, and ScreenIntrospectionIntegrationTest.
 *
 * Builds deterministic trees with a controllable kept-node count and a known kept-ancestor chain,
 * so tests can assert page boundaries and that a page's hierarchy includes the kept-ancestors of
 * that page's rows.
 */
object PaginationTestTrees {
    /**
     * A window whose tree has exactly [keptLeafCount] kept leaf nodes (each with text), nested under
     * a chain of [ancestorDepth] kept container nodes (each with a resourceId, so kept). Pre-order
     * kept order: the [ancestorDepth] containers (outermost first), then the [keptLeafCount] leaves.
     * Total kept nodes = [ancestorDepth] + [keptLeafCount].
     */
    fun keptNodeWindow(
        keptLeafCount: Int,
        ancestorDepth: Int = 2,
        windowId: Int = 1,
        packageName: String = "com.example.app",
    ): WindowData {
        require(ancestorDepth >= 1) { "ancestorDepth must be >= 1" }
        val leaves =
            (0 until keptLeafCount).map { i ->
                AccessibilityNodeData(
                    id = "node_leaf_$i",
                    className = "android.widget.TextView",
                    text = "leaf$i",
                    bounds = BoundsData(0, i, 100, i + 1),
                    enabled = true,
                    visible = true,
                )
            }
        var node = container(ancestorDepth - 1, leaves)
        for (depth in ancestorDepth - 2 downTo 0) {
            node = container(depth, listOf(node))
        }
        return WindowData(
            windowId = windowId,
            windowType = "APPLICATION",
            packageName = packageName,
            title = "Test",
            focused = true,
            tree = node,
        )
    }

    fun result(
        window: WindowData,
        degraded: Boolean = false,
    ): MultiWindowResult = MultiWindowResult(windows = listOf(window), degraded = degraded)

    private fun container(
        index: Int,
        children: List<AccessibilityNodeData>,
    ): AccessibilityNodeData =
        AccessibilityNodeData(
            id = "node_anc_$index",
            className = "android.widget.FrameLayout",
            resourceId = "com.example.app:id/anc_$index",
            bounds = BoundsData(0, 0, 100, 100),
            enabled = true,
            visible = true,
            children = children,
        )
}
