<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 44: `wait_for_node` Performance Optimization

## User Story 1: Lightweight raw-node search for `wait_for_node` polling

Optimize `WaitForNodeTool` by replacing the heavyweight `getFreshWindows()` + `ElementFinder.findElements()` poll loop with a lightweight raw `AccessibilityNodeInfo` walk during polling, and only doing the full parse on the final successful iteration. Also reduce `POLL_INTERVAL_MS` from 500ms to 150ms.

### Acceptance Criteria

- [x] Raw-node search walks all windows (multi-window path + single-window fallback), refreshes root nodes and virtual/Compose child nodes, and matches by the four `FindBy` criteria using case-insensitive contains (same as `ElementFinder.matchesValue` with `exactMatch=false`).
- [x] Raw-node search returns `true` on first match (early exit) without visiting remaining nodes.
- [x] Raw-node search does NOT create `AccessibilityNodeData`, `BoundsData`, `CachedNode` objects, and does NOT populate `AccessibilityNodeCache`.
- [x] `WaitForNodeTool.execute()` uses raw-node search for polling. On the final successful iteration, it calls `getFreshWindows()` + `ElementFinder.findElements()` to build the response.
- [x] `POLL_INTERVAL_MS` is changed from 500ms to 150ms.
- [x] `MAX_TREE_DEPTH` is respected (same depth limit as `AccessibilityTreeParser.MAX_TREE_DEPTH` = 100).
- [x] `AccessibilityWindowInfo` objects are recycled after each poll iteration (same as `getFreshWindows`).
- [x] Error handling is preserved: `PermissionDenied` propagates, other `McpToolException` retries on next poll.
- [x] The tool's external contract (response JSON shape, parameters, behavior) is unchanged.
- [x] All existing unit tests and integration tests pass.
- [x] New unit tests cover the raw-node search function.

---

### Task 1.1: Change `COMPOSE_SEMANTICS_ID_KEY` visibility

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`
**Operation**: Modify

Change `COMPOSE_SEMANTICS_ID_KEY` from `private const` to `internal const` in `AccessibilityTreeParser.Companion` (line 326).

```kotlin
// Before:
private const val COMPOSE_SEMANTICS_ID_KEY = "androidx.compose.ui.semantics.id"

// After:
internal const val COMPOSE_SEMANTICS_ID_KEY = "androidx.compose.ui.semantics.id"
```

#### Definition of Done

- [x] `COMPOSE_SEMANTICS_ID_KEY` is `internal const val` in `AccessibilityTreeParser.Companion`.
- [x] No other changes to `AccessibilityTreeParser.kt`.

---

### Task 1.2: Implement `rawNodeExists` function

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`
**Operation**: Modify — add a new `internal` top-level function

Add `rawNodeExists()` that walks raw `AccessibilityNodeInfo` trees across all windows (with single-window fallback) and returns `true` on first match.

**Imports to add** (not already in `UtilityTools.kt`):
```kotlin
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
```

Already imported: `AccessibilityServiceProvider`, `AccessibilityTreeParser`, `McpToolException`.

```kotlin
/**
 * Lightweight raw-node search that walks [AccessibilityNodeInfo] trees directly
 * without creating intermediate [AccessibilityNodeData] objects or populating
 * [AccessibilityNodeCache]. Returns true on first match (early exit).
 *
 * Refreshes root nodes and virtual/Compose child nodes using the same logic as
 * [AccessibilityTreeParser.parseNode] to ensure fresh data from Jetpack Compose
 * and other virtual node providers.
 *
 * Child nodes obtained via [AccessibilityNodeInfo.getChild] are NOT recycled.
 * On API 33+ (this project's minSdk) [AccessibilityNodeInfo.recycle] is a no-op.
 * This matches the pattern used by [TreeFingerprint.populateFingerprintFromRaw].
 *
 * @param findBy The search criteria type.
 * @param value The search value (matched case-insensitive contains, same as
 *   [ElementFinder.matchesValue] with exactMatch=false).
 * @param accessibilityServiceProvider Provider for accessibility windows and root nodes.
 * @return true if at least one node matching the criteria exists.
 * @throws McpToolException.PermissionDenied if accessibility service is not ready.
 * @throws McpToolException.ActionFailed if no windows or root nodes are available.
 */
internal fun rawNodeExists(
    findBy: FindBy,
    value: String,
    accessibilityServiceProvider: AccessibilityServiceProvider,
): Boolean {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled or not ready. " +
                "Please enable it in Android Settings > Accessibility.",
        )
    }

    val accessibilityWindows = accessibilityServiceProvider.getAccessibilityWindows()

    if (accessibilityWindows.isNotEmpty()) {
        try {
            for (window in accessibilityWindows) {
                val rootNode = window.root ?: continue
                rootNode.refresh()
                if (rawNodeMatchesRecursive(rootNode, findBy, value, 0)) {
                    return true
                }
            }
            // All windows processed, no match
            return false
        } finally {
            for (w in accessibilityWindows) {
                @Suppress("DEPRECATION")
                w.recycle()
            }
        }
    }

    // Fallback to single-window mode
    val rootNode = accessibilityServiceProvider.getRootNode()
        ?: throw McpToolException.ActionFailed(
            "No windows available and no active window root node. " +
                "The screen may be transitioning.",
        )
    rootNode.refresh()
    return rawNodeMatchesRecursive(rootNode, findBy, value, 0)
}

/**
 * Recursively walks a raw [AccessibilityNodeInfo] tree checking for a match.
 * Returns true immediately on first match (early exit). Refreshes virtual/Compose
 * child nodes before reading their properties.
 *
 * Child nodes obtained via [AccessibilityNodeInfo.getChild] are NOT recycled —
 * [AccessibilityNodeInfo.recycle] is a no-op on API 33+ (this project's minSdk).
 */
private fun rawNodeMatchesRecursive(
    node: AccessibilityNodeInfo,
    findBy: FindBy,
    value: String,
    depth: Int,
): Boolean {
    // Check current node
    val nodeValue = when (findBy) {
        FindBy.TEXT -> node.text?.toString()
        FindBy.CONTENT_DESC -> node.contentDescription?.toString()
        FindBy.RESOURCE_ID -> node.viewIdResourceName
        FindBy.CLASS_NAME -> node.className?.toString()
    }

    if (nodeValue != null && nodeValue.contains(value, ignoreCase = true)) {
        return true
    }

    // Depth guard — same limit as AccessibilityTreeParser.MAX_TREE_DEPTH
    if (depth >= AccessibilityTreeParser.MAX_TREE_DEPTH) {
        return false
    }

    // Recurse into children
    val childCount = node.childCount
    for (i in 0 until childCount) {
        val child = node.getChild(i) ?: continue

        // Refresh virtual/Compose nodes — same logic as AccessibilityTreeParser.parseNode
        if (child.viewIdResourceName == null ||
            child.availableExtraData.contains(AccessibilityTreeParser.COMPOSE_SEMANTICS_ID_KEY)
        ) {
            child.refresh()
        }

        if (rawNodeMatchesRecursive(child, findBy, value, depth + 1)) {
            return true
        }
    }

    return false
}
```

#### Definition of Done

- [x] `rawNodeExists()` function is added to `UtilityTools.kt` as `internal` top-level function.
- [x] `rawNodeMatchesRecursive()` is added as `private` top-level function.
- [x] Both functions match the code above exactly.
- [x] The two imports listed above are added to `UtilityTools.kt`.

---

### Task 1.3: Modify `WaitForNodeTool.execute()` to use two-phase polling

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`
**Operation**: Modify — change the poll loop in `WaitForNodeTool.execute()` and `POLL_INTERVAL_MS`

Replace the current poll loop (lines 228-257) with:
1. **Phase 1 (lightweight poll)**: Call `rawNodeExists()` — if it returns `false`, delay and retry.
2. **Phase 2 (full parse on match)**: When `rawNodeExists()` returns `true`, call `getFreshWindows()` + `elementFinder.findElements()` to build the full `ElementInfo` response. If `findElements` returns empty (race condition: node disappeared between raw check and full parse), continue polling.

Change `POLL_INTERVAL_MS` from `500L` to `150L`.

```kotlin
// Replace the poll loop (lines 228-257) with:

while (SystemClock.elapsedRealtime() - startTime < timeout) {
    attemptCount++

    try {
        // Phase 1: lightweight raw-node existence check (no intermediate objects, no cache)
        val exists = rawNodeExists(findBy, value, accessibilityServiceProvider)

        if (exists) {
            // Phase 2: full parse to build response and populate cache
            val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            val elements = elementFinder.findElements(multiWindowResult.windows, findBy, value, false)

            if (elements.isNotEmpty()) {
                val element = elements.first()
                val elapsed = SystemClock.elapsedRealtime() - startTime
                Log.d(TAG, "wait_for_node: found after ${elapsed}ms ($attemptCount attempts)")

                val resultJson =
                    buildJsonObject {
                        put("found", true)
                        put("elapsedMs", elapsed)
                        put("attempts", attemptCount)
                        put("node", McpToolUtils.buildNodeJson(element))
                    }
                return McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
            }
            // Node disappeared between raw check and full parse — continue polling
            Log.d(TAG, "wait_for_node: raw check found match but full parse did not, retrying")
        }
    } catch (e: McpToolException) {
        // If accessibility service becomes unavailable during polling, propagate
        if (e is McpToolException.PermissionDenied) throw e
        // Other errors (e.g., stale tree) — retry on next poll
        Log.d(TAG, "wait_for_node: poll attempt $attemptCount failed: ${e.message}")
    }

    delay(POLL_INTERVAL_MS)
}
```

Change the constant:
```kotlin
// Before:
private const val POLL_INTERVAL_MS = 500L

// After:
private const val POLL_INTERVAL_MS = 150L
```

#### Definition of Done

- [x] Poll loop uses `rawNodeExists()` for lightweight checking, then `getFreshWindows()` + `findElements()` only on match.
- [x] Race condition handled: if `findElements()` returns empty after a raw match, polling continues.
- [x] `POLL_INTERVAL_MS` is `150L`.
- [x] Error handling preserved: `PermissionDenied` propagates, other exceptions retry.
- [x] The `treeParser` and `elementFinder` dependencies remain in `WaitForNodeTool` constructor (needed for the final full-parse phase).
- [x] The `nodeCache` dependency remains in `WaitForNodeTool` constructor (needed by `getFreshWindows` in the final phase).
- [x] Response JSON shape unchanged.

---

### Task 1.4: Update unit tests for `WaitForNodeTool`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityToolsTest.kt`
**Operation**: Modify

The existing tests mock `ElementFinder.findElements()` but the poll loop now calls `rawNodeExists()` first, which walks raw `AccessibilityNodeInfo` nodes directly via `AccessibilityServiceProvider`. Existing tests MUST be updated to also set up mock raw nodes so that `rawNodeExists()` can find them before the full parse path runs.

Additionally, add new tests for:
- Raw-node search with early exit (verifies that `rawNodeExists` returns `true` when a matching node is in the tree).
- Two-phase behavior: raw check finds node, then full parse builds response.
- Race condition: raw check finds node but full parse does not (node disappeared), polling continues.

**Setup changes for existing WaitForNodeTool tests**: The `setUp()` method already mocks `mockWindowInfo.root` returning `mockRootNode` and `mockRootNode.refresh()`. Add baseline raw node property stubs to `setUp()` so `rawNodeMatchesRecursive` can walk the root node without throwing:

```kotlin
// Add to setUp() after existing mockRootNode stubs:
every { mockRootNode.text } returns null
every { mockRootNode.contentDescription } returns null
every { mockRootNode.viewIdResourceName } returns null
every { mockRootNode.className } returns null
every { mockRootNode.childCount } returns 0
every { mockRootNode.availableExtraData } returns emptyList()
```

With these defaults, `rawNodeExists` will return `false` (no match). Tests that expect a match MUST override the relevant property (e.g., `every { mockRootNode.text } returns "Result"`).

**Setup**: `tool` — `WaitForNodeTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider, mockNodeCache)`. Mock raw `AccessibilityNodeInfo` nodes via MockK with properties matching the search criteria.

| Test | Verifies |
|------|----------|
| `finds node on first attempt` (existing, update) | Override `mockRootNode.text` to `"Result"` so `rawNodeExists` returns `true`, then `findElements` returns `ElementInfo`. Response contains `found=true` and correct `node_id`. |
| `finds node after multiple poll attempts` (existing, update) | Use a call counter on `mockWindowInfo.root` (or `getAccessibilityWindows`) answer to return different raw nodes: for calls 1-2 the root node has `text = null` (no match, `rawNodeExists` returns `false`), for call 3+ the root node has `text = "Delayed"` (match). When `rawNodeExists` returns `true`, `findElements` is called for the full parse — mock it to return `sampleElementInfo`. Verify `found=true` and `attempts >= 3`. |
| `returns timed out message when node never found` (existing, update) | Raw node properties never match (mock `mockRootNode.text` to a non-matching value, `childCount` to 0). **Clock advancement MUST move from `findElements` answer to `SystemClock.elapsedRealtime()` answer** — with the new two-phase approach, `rawNodeExists` returns `false` so `findElements` is never called and the old clock advancement mechanism would hang. Remove the `findElements` mock. Verify timeout response. |
| `throws error for invalid by parameter` (existing, no change needed) | Validation happens before poll loop. |
| `throws error for empty value` (existing, no change needed) | Validation happens before poll loop. |
| `throws error for timeout exceeding max` (existing, no change needed) | Validation happens before poll loop. |
| `throws error for negative timeout` (existing, no change needed) | Validation happens before poll loop. |
| `rawNodeExists returns true on first matching node and exits early` (new) | Build raw tree with 3 children, matching node is first child. Verify `rawNodeExists` returns `true`. Verify second and third children are NOT accessed (using `verify(exactly = 0)` on `getChild(1)` and `getChild(2)` after the parent's `getChild(0)` returns the match). |
| `rawNodeExists refreshes virtual and Compose child nodes` (new) | Build raw tree with a child that has `viewIdResourceName == null`. Verify `child.refresh()` is called. Build another with `availableExtraData` containing `COMPOSE_SEMANTICS_ID_KEY`. Verify `child.refresh()` is called. Build a third with a non-null `viewIdResourceName` and no Compose key. Verify `child.refresh()` is NOT called. |
| `rawNodeExists respects MAX_TREE_DEPTH - stops recursion beyond depth 100` (new) | Build a chain of raw nodes deeper than `MAX_TREE_DEPTH` (100) with the matching node at depth 101. Verify `rawNodeExists` returns `false`. |
| `rawNodeExists finds match at exactly MAX_TREE_DEPTH` (new) | Build a chain of raw nodes with the matching node at exactly depth 100 (`MAX_TREE_DEPTH`). Verify `rawNodeExists` returns `true` (the current node is checked before the depth guard stops recursion into children). |
| `rawNodeExists skips windows with null roots and finds match in remaining windows` (new) | Multi-window setup with 3 windows: window 1 has `root = null`, window 2 has `root = null`, window 3 has a valid root node with matching `text`. Verify `rawNodeExists` returns `true`. Verify windows are still recycled. |
| `rawNodeExists falls back to single root node when no windows available` (new) | `getAccessibilityWindows()` returns empty list, `getRootNode()` returns a matching node. Verify returns `true`. |
| `rawNodeExists throws PermissionDenied when not ready` (new) | `isReady()` returns `false`. Verify `McpToolException.PermissionDenied` is thrown. |
| `rawNodeExists throws ActionFailed when no windows and no root node` (new) | `getAccessibilityWindows()` returns empty, `getRootNode()` returns `null`. Verify `McpToolException.ActionFailed` is thrown. |
| `race condition - raw check finds but full parse misses - continues polling` (new) | First call: `rawNodeExists` finds match (raw node has matching text), but `findElements` returns empty. Second call: both find match. Verify `found=true` and `attempts >= 2`. |
| `all four FindBy criteria work with rawNodeExists` (new) | Test `rawNodeExists` with `FindBy.TEXT` (match on `node.text`), `FindBy.CONTENT_DESC` (match on `node.contentDescription`), `FindBy.RESOURCE_ID` (match on `node.viewIdResourceName`), `FindBy.CLASS_NAME` (match on `node.className`). Verify each returns `true`. |
| `rawNodeExists uses case-insensitive contains matching` (new) | Raw node has `text = "Hello World"`, search value is `"hello"`. Verify returns `true`. |
| `rawNodeExists recycles AccessibilityWindowInfo objects` (new) | Verify `window.recycle()` is called on all windows after the search, even when a match is found early. |
| `rawNodeExists recycles AccessibilityWindowInfo objects on exception` (new) | A child node's `getChild()` throws an exception during the walk. Verify `window.recycle()` is still called on all windows (finally block guarantee). |
| `rawNodeExists returns false when node property is null` (new) | Raw node has `text = null`, search by `FindBy.TEXT` with value `"anything"`. Verify returns `false`. Confirms the `if (nodeValue != null)` guard in `rawNodeMatchesRecursive`. |
| `rawNodeExists skips null children and continues searching` (new) | Build a node with `childCount = 3` where `getChild(0)` returns `null`, `getChild(1)` returns a matching node. Verify `rawNodeExists` returns `true`. Confirms the `?: continue` null-child skip in `rawNodeMatchesRecursive`. |

#### Definition of Done

- [x] `setUp()` method has baseline raw node property stubs (`text`, `contentDescription`, `viewIdResourceName`, `className`, `childCount`, `availableExtraData`) returning null/0/empty.
- [x] All existing `WaitForNodeTool` tests are updated to work with the new two-phase poll loop.
- [x] All new tests listed above are implemented and passing.
- [x] No NEW `@Suppress` annotations are added to the test file (the existing `@file:Suppress("DEPRECATION")` at line 1 is pre-existing and out of scope).

---

### Task 1.5: Update `McpIntegrationTestHelper.setupMultiWindowMock` for raw-node walk compatibility

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`
**Operation**: Modify

`rawNodeExists()` now walks raw `AccessibilityNodeInfo` nodes directly. The `mockRootNode` in `setupMultiWindowMock` (line 91) is created as `mockk<AccessibilityNodeInfo>()` (NOT relaxed), so unstubbed property reads (`text`, `childCount`, `className`, `contentDescription`, `viewIdResourceName`, `availableExtraData`) will throw `MockKException`. Add stubs for these properties so `rawNodeMatchesRecursive` can walk the mock root node without errors.

Add the following stubs after `every { mockRootNode.packageName } returns packageName` (line 102):

```kotlin
// Raw-node walk support: rawNodeExists() reads these properties directly
// from AccessibilityNodeInfo without going through AccessibilityTreeParser.
// Return null/0/empty so the root node does not match any search criteria
// (individual tests that need a match will override these stubs).
every { mockRootNode.text } returns null
every { mockRootNode.contentDescription } returns null
every { mockRootNode.viewIdResourceName } returns null
every { mockRootNode.className } returns null
every { mockRootNode.childCount } returns 0
every { mockRootNode.availableExtraData } returns emptyList()
```

Tests that expect a match MUST override the relevant property (e.g., `every { mockRootNode.text } returns "Hello World"`).

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/UtilityIntegrationTest.kt`
**Operation**: Modify — update `wait_for_node success returns node_id in response` test

The `setupMultiWindowMock` mock root node now has `text = null` by default. For the raw walk to find a match, the test MUST stub `mockRootNode.text` to the search value. However, `mockRootNode` is a local variable inside `setupMultiWindowMock` and is not returned to the caller.

Two approaches:
1. Make `setupMultiWindowMock` return the `mockRootNode` so callers can add stubs.
2. Add an optional `rootNodeText` parameter to `setupMultiWindowMock`.

Use approach 1 (more flexible, less coupling):

Change `setupMultiWindowMock` return type from `Unit` to `AccessibilityNodeInfo`:

```kotlin
// Before:
fun setupMultiWindowMock(
    deps: MockDependencies,
    tree: AccessibilityNodeData,
    screenInfo: ScreenInfo,
    packageName: String = "com.example.app",
    activityName: String = ".MainActivity",
    windowId: Int = 0,
) {

// After:
fun setupMultiWindowMock(
    deps: MockDependencies,
    tree: AccessibilityNodeData,
    screenInfo: ScreenInfo,
    packageName: String = "com.example.app",
    activityName: String = ".MainActivity",
    windowId: Int = 0,
): AccessibilityNodeInfo {
```

Add `return mockRootNode` at the end of the function (before the closing brace).

Then in `UtilityIntegrationTest.kt`, update the test to use the returned mock root node:

```kotlin
// Before:
McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)

// After:
val mockRootNode = McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
// Stub raw node text so rawNodeExists() finds the match
every { mockRootNode.text } returns "Hello World"
```

#### Definition of Done

- [x] `setupMultiWindowMock` stubs `text`, `contentDescription`, `viewIdResourceName`, `className`, `childCount`, `availableExtraData` on `mockRootNode` with null/0/empty defaults.
- [x] `setupMultiWindowMock` return type changed from `Unit` to `AccessibilityNodeInfo`, returns `mockRootNode`.
- [x] `UtilityIntegrationTest.wait_for_node success` test updated to stub `mockRootNode.text` with `"Hello World"`.
- [x] All integration tests in `UtilityIntegrationTest.kt` pass.
- [x] All integration tests in `ErrorHandlingIntegrationTest.kt` pass.
- [x] All integration tests in `ToolPermissionsIntegrationTest.kt` pass.
- [x] All integration tests in `McpProtocolIntegrationTest.kt` pass.
- [x] All other callers of `setupMultiWindowMock` still work (the return value can be ignored — changing from `Unit` to `AccessibilityNodeInfo` is source-compatible).

**IMPORTANT — `ErrorHandlingIntegrationTest.wait_for_node timeout` test fix**:

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ErrorHandlingIntegrationTest.kt`
**Operation**: Modify

The existing timeout test advances the clock inside the `findElements` answer block (`clockMs += 600L`). With the new two-phase approach, when the raw walk finds no match, `findElements` is never called, so the clock never advances and the loop runs forever.

Fix: change the clock advancement from the `findElements` answer to the `SystemClock.elapsedRealtime()` answer so the clock advances on every poll check regardless of which phase is reached. Remove the `findElements` mock entirely (it is never called when the raw walk finds no match).

```kotlin
// Before:
var clockMs = 0L
every { SystemClock.elapsedRealtime() } answers { clockMs }
// ...
every {
    deps.elementFinder.findElements(
        any<List<WindowData>>(),
        FindBy.TEXT,
        "nonexistent_element_xyz",
        false,
    )
} answers {
    clockMs += 600L
    emptyList()
}

// After:
var clockMs = 0L
every { SystemClock.elapsedRealtime() } answers {
    val current = clockMs
    clockMs += 200L
    current
}
// No findElements mock needed — rawNodeExists returns false, findElements is never called
```

- [x] `ErrorHandlingIntegrationTest.wait_for_node timeout` test updated to advance clock via `elapsedRealtime()` answer.

---

### Task 1.6: Run linting and full test suite

**Operation**: Run `make lint` and `make test`

#### Definition of Done

- [x] `make lint` passes with zero warnings and zero errors.
- [x] `make test` passes with all tests green.
- [x] `./gradlew build` succeeds with no warnings and no errors.

---

### Task 1.7: Final verification — review entire implementation from ground up

**Operation**: Re-read all changed files and verify correctness end-to-end.

Verify the following checklist:

- [x] `COMPOSE_SEMANTICS_ID_KEY` is `internal const val` in `AccessibilityTreeParser.Companion` — no other changes to that file.
- [x] `rawNodeExists()` refreshes root nodes via `rootNode.refresh()` for every window (multi-window path) and for the fallback single-window path.
- [x] `rawNodeExists()` refreshes virtual/Compose child nodes using the exact same condition as `AccessibilityTreeParser.parseNode`: `child.viewIdResourceName == null || child.availableExtraData.contains(AccessibilityTreeParser.COMPOSE_SEMANTICS_ID_KEY)`.
- [x] `rawNodeMatchesRecursive()` uses case-insensitive `contains` matching (same as `ElementFinder.matchesValue` with `exactMatch=false`).
- [x] `rawNodeMatchesRecursive()` returns `true` immediately on first match (early exit).
- [x] `rawNodeMatchesRecursive()` respects `AccessibilityTreeParser.MAX_TREE_DEPTH` (100).
- [x] `rawNodeExists()` recycles `AccessibilityWindowInfo` objects in a `finally` block (multi-window path).
- [x] `rawNodeExists()` handles the single-window fallback path when `getAccessibilityWindows()` returns empty.
- [x] `WaitForNodeTool.execute()` poll loop: calls `rawNodeExists()` first, then `getFreshWindows()` + `findElements()` only when `rawNodeExists()` returns `true`.
- [x] `WaitForNodeTool.execute()` handles the race condition (raw match but full parse miss) by continuing the poll loop.
- [x] `POLL_INTERVAL_MS` is `150L`.
- [x] Response JSON shape is identical to the original (no fields added, removed, or renamed).
- [x] No `AccessibilityNodeData`, `BoundsData`, or `CachedNode` objects are created during the lightweight polling phase.
- [x] No `AccessibilityNodeCache.populate()` is called during the lightweight polling phase.
- [x] Unit test `setUp()` has baseline raw node property stubs on `mockRootNode`.
- [x] `McpIntegrationTestHelper.setupMultiWindowMock` returns `AccessibilityNodeInfo` and stubs raw node properties with null/0/empty defaults.
- [x] `UtilityIntegrationTest.wait_for_node success` stubs `mockRootNode.text` with `"Hello World"` so `rawNodeExists` returns `true`.
- [x] `ErrorHandlingIntegrationTest.wait_for_node timeout` advances clock via `elapsedRealtime()` answer, not `findElements` answer.
- [x] All unit tests pass and cover the new behavior.
- [x] All integration tests pass.
- [x] No linting violations.
- [x] Build succeeds.
