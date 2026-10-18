<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 47: get_screen_state Cursor Pagination

Bound `get_screen_state` output so a single response can never exceed the model context window. Large screens (notably WebView/Chrome, which can expose ~1700 accessibility nodes) are split into pages of **200 TSV node rows**, served from a frozen snapshot identified by a capture timestamp. The agent walks pages via an opaque `<id>.<page>` cursor and may stop early once it has found what it needs.

## Rationale (not derivable from code)

- The crash in issue #92 is caused by an **uncapped** accessibility-tree serialization, not by the screenshot and not exclusively by WebView. Measured live: a repubblica.it Chrome tab produced 1,706 TSV rows / ~210 KB of text. The screenshot (≈80 KB JPEG, ~870 image tokens) is a minor, separate cost.
- The snapshot identity is **a capture timestamp only**. There is intentionally **NO change-detection mechanism** (no accessibility-event hooks, no content diffing). A snapshot is invalidated **only** when a new cursorless capture replaces it or the server is destroyed. A cursor carrying a stale id is simply rejected.
- The hierarchy block representation MUST remain **byte-for-byte identical** to today (indented `node_id`-only tree, kept nodes only, structural nodes collapsed, 2 spaces per kept-depth level). The ONLY pagination-specific behavior is that each page's hierarchy additionally includes the **kept-ancestors** of that page's TSV rows so the indented tree stays rooted/connected; those ancestor ids appear in the hierarchy even though their TSV row lives on another page.

## Scope boundaries (ABSOLUTE)

- **OUT OF SCOPE** (deliberate, not a limitation to fix here): WebView-subtree collapse, interactable-only / visibility filtering, screenshot size reduction, and any change to the TSV row format, flags, window-header format, note lines, or the hierarchy *representation*.
- Single-window `CompactTreeFormatter.format()` is NOT used by `get_screen_state` (test-only) and MUST NOT be modified.

## Settled behavior (authoritative — implementation MUST match exactly)

1. New optional tool parameter `cursor` (string). Omitted/blank ⇒ fresh cursorless capture.
2. **Cursorless call**: capture fresh (`getFreshWindows`, unchanged), compute total kept-node count.
   - If total kept nodes ≤ 200 (single page): output is **exactly today's** `formatMultiWindow` result (no `page:` line, no cursor note); additionally `clear()` the snapshot cache.
   - If total kept nodes > 200: stamp `id = base36(System.currentTimeMillis())`, store the snapshot (replacing any previous), return **page 1**.
   - `include_screenshot:true` is honored on the cursorless call (page 1) exactly as today.
3. **Cursor call** (`cursor = "<id>.<page>"`): served from the frozen snapshot. Never re-captures; never touches `nodeCache`.
   - Malformed cursor ⇒ normal text guidance (invalid cursor).
   - `id` not matching the currently cached snapshot ⇒ normal text guidance (snapshot gone, request fresh).
   - `page` < 1 or > `totalPages` ⇒ normal text guidance (no such page; snapshot has N pages).
   - `include_screenshot:true` on ANY cursor call ⇒ **no screenshot captured**; append a concise note that a screenshot is only available with page 1 / without a cursor.
   - All guidance results are `CallToolResult` with `isError=false` (normal text), via `McpToolUtils.untrustedTextResult`.
4. **Page size = 200** TSV rows.
5. Per page (when paginating), output adds one header line `page:<P>/<T> snapshot:<id> nodes:<start>-<end>/<total>` and one trailing `note:` line carrying the next cursor (or end marker) plus "you do NOT need to fetch every page — stop once you have found what you need."
6. Snapshot retains only serializable data (`MultiWindowResult`, `ScreenInfo`) — NO live `AccessibilityNodeInfo` references. Single slot. Cleared on `McpServerService.onDestroy`.
7. On a paginated page, a window section (its window header + TSV header + rows + hierarchy) is included ONLY when that window contributes at least one TSV row to that page. Windows with no rows on a given page are omitted from that page (their header does not appear). This is a consequence of per-page slicing, not a format change — when a window IS shown, its header/rows/hierarchy use the unchanged formats.

---

## User Story 1: Screen-state snapshot cache

Provide a single-slot, thread-safe, timestamp-keyed cache holding the most recent paginated screen-state snapshot. A new `store()` replaces any prior snapshot; `get(id)` returns the snapshot only when `id` matches the currently stored one.

### Acceptance Criteria
- [x] `ScreenStateSnapshot` data class holds `id`, `result`, `screenInfo`, `totalKeptNodes`, `totalPages` and no live node references.
- [x] `ScreenStateSnapshotCache` interface exposes `store`, `get(id)`, `clear`.
- [x] `ScreenStateSnapshotCacheImpl` is a Hilt `@Singleton`, thread-safe, returns the snapshot only on id match.
- [x] Bound in Hilt `ServiceModule`.

### Task 1.1: Snapshot model + cache interface

**Action 1.1.1** — Create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ScreenStateSnapshotCache.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.accessibility

/**
 * Immutable snapshot of a captured screen state, retained for cursor-based pagination of
 * get_screen_state. Holds only serializable data (no live AccessibilityNodeInfo references),
 * so it is safe to retain across calls.
 *
 * @property id Capture identifier: base36 of System.currentTimeMillis() at capture time.
 * @property result Parsed multi-window accessibility data captured at snapshot time.
 * @property screenInfo Screen dimensions/orientation captured at snapshot time.
 * @property totalKeptNodes Total number of kept TSV nodes across all windows.
 * @property totalPages Number of pages (ceil(totalKeptNodes / PAGE_SIZE)).
 */
data class ScreenStateSnapshot(
    val id: String,
    val result: MultiWindowResult,
    val screenInfo: ScreenInfo,
    val totalKeptNodes: Int,
    val totalPages: Int,
)

/**
 * Single-slot, thread-safe cache holding the most recent paginated screen-state snapshot.
 *
 * A new [store] replaces any previous snapshot. [get] returns the snapshot ONLY when its [id]
 * matches the currently stored snapshot; otherwise null (stale/replaced cursor). There is NO
 * change detection: a snapshot is invalidated only by a replacing [store] or by [clear].
 */
interface ScreenStateSnapshotCache {
    fun store(snapshot: ScreenStateSnapshot)

    fun get(id: String): ScreenStateSnapshot?

    fun clear()
}
```

**Definition of Done**:
- [x] File compiles; `ScreenStateSnapshot` references existing `MultiWindowResult` and `ScreenInfo`.

### Task 1.2: Cache implementation

**Action 1.2.1** — Create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ScreenStateSnapshotCacheImpl.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.accessibility

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenStateSnapshotCacheImpl
    @Inject
    constructor() : ScreenStateSnapshotCache {
        @Volatile
        private var snapshot: ScreenStateSnapshot? = null

        override fun store(snapshot: ScreenStateSnapshot) {
            this.snapshot = snapshot
        }

        override fun get(id: String): ScreenStateSnapshot? {
            val current = snapshot
            return if (current != null && current.id == id) current else null
        }

        override fun clear() {
            snapshot = null
        }
    }
```

**Definition of Done**:
- [x] `@Volatile` single-reference swap provides thread-safe replace/read.
- [x] `get` returns null on id mismatch.

### Task 1.3: Hilt binding

**Action 1.3.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` (modify): in `ServiceModule`, add the binding next to `bindAccessibilityNodeCache`.

```kotlin
    @Binds
    @Singleton
    abstract fun bindScreenStateSnapshotCache(impl: ScreenStateSnapshotCacheImpl): ScreenStateSnapshotCache
```

Add the corresponding imports for `ScreenStateSnapshotCache` and `ScreenStateSnapshotCacheImpl`.

**Definition of Done**:
- [x] Hilt graph resolves `ScreenStateSnapshotCache` as a singleton.

---

## User Story 2: Paginated formatting in CompactTreeFormatter

Add page-aware formatting that reuses the existing per-window TSV and hierarchy rendering. The hierarchy representation is unchanged; a page's hierarchy includes the kept-ancestor closure of that page's rows.

### Acceptance Criteria
- [x] `PAGE_SIZE = 200` constant added.
- [x] `countKeptNodes(result)` returns the exact number of TSV rows `formatMultiWindow` would emit.
- [x] `formatMultiWindowPage(...)` renders one page: header block + `page:` line + per-window TSV rows for that page + per-window `hierarchy:` block containing the page's rows AND their kept-ancestors (same indentation as today) + trailing cursor/end note.
- [x] Existing `format()` and `formatMultiWindow()` are unchanged.

### Task 2.1: Kept-node count + page-size constant

**Action 2.1.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt` (modify): add to the `companion object`:

```kotlin
        const val PAGE_SIZE = 200
```

**Action 2.1.2** — Modify same file: add a public count method (mirrors `walkTree`'s kept-node visitation order/filter).

```kotlin
        /** Total number of kept nodes across all windows (equals the number of TSV rows emitted). */
        fun countKeptNodes(result: MultiWindowResult): Int =
            result.windows.sumOf { countKeptInTree(it.tree) }

        private fun countKeptInTree(node: AccessibilityNodeData): Int {
            var count = if (shouldKeepNode(node)) 1 else 0
            for (child in node.children) {
                count += countKeptInTree(child)
            }
            return count
        }
```

**Definition of Done**:
- [x] `countKeptNodes` uses `shouldKeepNode` so it matches the TSV row count exactly.

### Task 2.2: Single-page formatting

**Action 2.2.1** — Modify same file: add `formatMultiWindowPage` and its private helpers. The page's global node order is the concatenation, in window order, of each window's pre-order kept-node list (identical order to `formatMultiWindow`).

```kotlin
        /**
         * Formats a single page (1-based [page]) of [snapshot] as a compact string.
         *
         * The header/notes/window-header/TSV-row/hierarchy formats are IDENTICAL to
         * [formatMultiWindow]; the only additions are the `page:` line and the trailing cursor note.
         * A window section is emitted only when it contributes at least one row to this page.
         * Each window's `hierarchy:` block lists the page's rows for that window PLUS their
         * kept-ancestors (kept-ancestor closure), preserving today's kept-depth indentation.
         *
         * [page] MUST be within 1..[ScreenStateSnapshot.totalPages] (validated by the caller).
         * Takes the [ScreenStateSnapshot] (not its individual fields) to stay within the
         * detekt LongParameterList threshold — NO @Suppress is used.
         */
        fun formatMultiWindowPage(
            snapshot: ScreenStateSnapshot,
            page: Int,
        ): String {
            val result = snapshot.result
            val screenInfo = snapshot.screenInfo
            val snapshotId = snapshot.id
            val totalKeptNodes = snapshot.totalKeptNodes
            val totalPages = snapshot.totalPages
            val startIndex = (page - 1) * PAGE_SIZE
            val endIndexExclusive = minOf(page * PAGE_SIZE, totalKeptNodes)
            val perWindowKept = result.windows.map { collectKeptNodes(it.tree) }

            val sb = StringBuilder()
            if (result.degraded) sb.appendLine(DEGRADATION_NOTE)
            sb.appendLine(NOTE_LINE)
            sb.appendLine(NOTE_LINE_CUSTOM_ELEMENTS)
            sb.appendLine(NOTE_LINE_FLAGS_LEGEND)
            sb.appendLine(NOTE_LINE_OFFSCREEN_HINT)
            sb.appendLine(
                "screen:${screenInfo.width}x${screenInfo.height} " +
                    "density:${screenInfo.densityDpi} orientation:${screenInfo.orientation}",
            )
            sb.appendLine(
                "page:$page/$totalPages snapshot:$snapshotId " +
                    "nodes:${startIndex + 1}-$endIndexExclusive/$totalKeptNodes",
            )

            var windowGlobalStart = 0
            for ((wIdx, windowData) in result.windows.withIndex()) {
                val kept = perWindowKept[wIdx]
                val windowEnd = windowGlobalStart + kept.size
                val overlapStart = maxOf(startIndex, windowGlobalStart)
                val overlapEnd = minOf(endIndexExclusive, windowEnd)
                if (overlapStart < overlapEnd) {
                    val pageNodes = kept.subList(overlapStart - windowGlobalStart, overlapEnd - windowGlobalStart)
                    appendWindowPageSection(sb, windowData, pageNodes)
                }
                windowGlobalStart = windowEnd
            }

            sb.appendLine(buildPaginationNote(snapshotId, page, totalPages))
            return sb.toString().trimEnd('\n')
        }

        private fun appendWindowPageSection(
            sb: StringBuilder,
            windowData: WindowData,
            pageNodes: List<AccessibilityNodeData>,
        ) {
            val pageNodeIds = pageNodes.mapTo(HashSet()) { it.id }
            sb.appendLine(buildWindowHeader(windowData))
            sb.appendLine(HEADER)
            for (node in pageNodes) appendElementRow(sb, node)
            sb.appendLine(HIERARCHY_HEADER)
            val closure = computeKeptAncestorClosure(windowData.tree, pageNodeIds)
            appendPageHierarchy(sb, windowData.tree, 0, closure)
        }

        /** Pre-order list of kept nodes (same order/filter as walkTree's TSV emission). */
        private fun collectKeptNodes(root: AccessibilityNodeData): List<AccessibilityNodeData> {
            val out = ArrayList<AccessibilityNodeData>()
            fun walk(node: AccessibilityNodeData) {
                if (shouldKeepNode(node)) out.add(node)
                for (child in node.children) walk(child)
            }
            walk(root)
            return out
        }

        /** Ids of kept nodes that are a page row OR a kept-ancestor of a page row. */
        private fun computeKeptAncestorClosure(
            root: AccessibilityNodeData,
            pageNodeIds: Set<String>,
        ): Set<String> {
            val closure = HashSet<String>()
            fun covers(node: AccessibilityNodeData): Boolean {
                var subtreeCovers = node.id in pageNodeIds
                for (child in node.children) {
                    if (covers(child)) subtreeCovers = true
                }
                if (subtreeCovers && shouldKeepNode(node)) closure.add(node.id)
                return subtreeCovers
            }
            covers(root)
            return closure
        }

        /**
         * Emits the hierarchy lines for nodes in [closure], using the SAME kept-depth indentation
         * as [walkTree]: depth increments for every kept node (whether or not it is emitted), so
         * emitted closure nodes land at exactly the indentation they have in the full hierarchy.
         */
        private fun appendPageHierarchy(
            sb: StringBuilder,
            node: AccessibilityNodeData,
            depth: Int,
            closure: Set<String>,
        ) {
            val isKept = shouldKeepNode(node)
            if (isKept && node.id in closure) {
                repeat(depth) { sb.append(HIERARCHY_INDENT) }
                sb.appendLine(node.id)
            }
            val childDepth = if (isKept) depth + 1 else depth
            for (child in node.children) appendPageHierarchy(sb, child, childDepth, closure)
        }

        private fun buildPaginationNote(
            snapshotId: String,
            page: Int,
            totalPages: Int,
        ): String =
            if (page < totalPages) {
                "note:more nodes available — call get_screen_state with cursor " +
                    "\"$snapshotId.${page + 1}\" to continue. You do NOT need to fetch every page; " +
                    "stop once you have found what you need. This cursor is tied to this screen " +
                    "snapshot; if the screen changed, call without a cursor for a fresh one."
            } else {
                "note:end of snapshot (page $page/$totalPages). You do NOT need to have fetched " +
                    "every page; stop once you have found what you need."
            }
```

> Implementation note: `HIERARCHY_INDENT` is the existing `private const val "  "`. The `pageNodes` sublist uses local window indices; `(windowEnd - kept.size)` is the window's global start index. The `DEGRADATION_NOTE` is emitted on **every page** when `result.degraded` (intentional: each page is a self-contained response repeating the full note/screen block, exactly as the other note lines do — this matches `formatMultiWindow`'s placement and does not change the note's text). A degraded single-window fallback can still exceed 200 kept nodes, so the degraded+paginated path is reachable and is covered by a test in Task 4.2.

**Definition of Done**:
- [x] `formatMultiWindowPage` reuses `buildWindowHeader`, `HEADER`, `appendElementRow`, `shouldKeepNode`, `HIERARCHY_HEADER`, `HIERARCHY_INDENT` — no duplicated formats.
- [x] Page 1 of a node set ≤ `PAGE_SIZE` is never produced via this method (caller uses `formatMultiWindow` for single page).
- [x] No detekt violations (method/parameter counts addressed via the helper split above).

---

## User Story 3: Tool handler pagination + wiring

`get_screen_state` accepts `cursor`, serves fresh page 1 or a cached page, validates cursors with normal-text guidance, and restricts the screenshot to the cursorless (page 1) call. The snapshot cache is cleared on server shutdown.

### Acceptance Criteria
- [x] `cursor` param exists in the tool input schema and description; tool description documents pagination, 200-row pages, screenshot-on-page-1-only, and stop-early guidance.
- [x] Cursorless: ≤200 rows ⇒ today's output + cache cleared; >200 rows ⇒ stored snapshot + page 1.
- [x] Cursor: malformed / stale-id / out-of-range ⇒ `untrustedTextResult` guidance (`isError=false`); valid ⇒ requested page from the frozen snapshot.
- [x] `include_screenshot` honored only on the cursorless call; on any cursor call it is ignored with a concise appended note.
- [x] Snapshot cache cleared in `McpServerService.onDestroy`.

### Task 3.1: GetScreenStateHandler

**Action 3.1.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt` (modify): add the cache to the constructor.

```kotlin
        private val nodeCache: AccessibilityNodeCache,
        private val screenStateSnapshotCache: ScreenStateSnapshotCache,
```

Add imports `com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache`, `com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshot`, `com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult`, `com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo`, `kotlinx.serialization.json.JsonElement`, `kotlinx.serialization.json.JsonNull`, `kotlinx.serialization.json.JsonPrimitive`, and `kotlinx.serialization.json.contentOrNull`.

> Note: adding this 8th constructor argument breaks compilation of the dependent call sites. Two distinct kinds exist and BOTH must be updated to supply a real `ScreenStateSnapshotCacheImpl()`:
> - `ScreenIntrospectionToolsTest.kt` — a direct `GetScreenStateHandler(...)` constructor call (~lines 148–156), updated by Action 4.3.
> - `McpIntegrationTestHelper.kt` — NOT a constructor call; it invokes `registerScreenIntrospectionTools(...)` (~line 156), updated by Action 4.4.1 (which forwards the new argument through that function).

**Action 3.1.2** — Modify same file: replace the body of `execute(...)` and the inline screenshot block with the dispatch + helpers below. The screenshot capture/annotate/encode logic (current steps 5) is MOVED verbatim into `buildScreenshotResult`, preserving its existing `@Suppress("ThrowsCount", "TooGenericExceptionCaught")` behavior. `getFreshWindows`, screenshot constants, and `collectOnScreenElements*` are unchanged.

```kotlin
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val includeScreenshot = parseIncludeScreenshot(arguments)
            val cursorElement = arguments?.get("cursor")
            // Absent, JSON null, or a blank string ⇒ fresh cursorless capture (settled behavior 1).
            // A present, non-blank value (INCLUDING a non-primitive object/array) ⇒ paged path,
            // where an unusable value yields INVALID_CURSOR_MESSAGE guidance rather than throwing.
            val isFresh =
                cursorElement == null ||
                    cursorElement is JsonNull ||
                    ((cursorElement as? JsonPrimitive)?.contentOrNull?.isBlank() == true)
            return if (isFresh) {
                handleFreshRequest(includeScreenshot)
            } else {
                McpToolUtils.untrustedTextResult(buildPagedText(cursorElement, includeScreenshot))
            }
        }

        private fun parseIncludeScreenshot(arguments: JsonObject?): Boolean =
            if (includeScreenshotEnabled) {
                arguments?.get("include_screenshot")?.jsonPrimitive?.booleanOrNull ?: false
            } else {
                false
            }

        private suspend fun handleFreshRequest(includeScreenshot: Boolean): CallToolResult {
            val result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            val screenInfo = accessibilityServiceProvider.getScreenInfo()
            val totalKept = compactTreeFormatter.countKeptNodes(result)
            val totalPages = ceilDiv(totalKept, CompactTreeFormatter.PAGE_SIZE)
            val compactOutput = buildFreshPageText(result, screenInfo, totalKept, totalPages)
            Log.d(TAG, "get_screen_state: includeScreenshot=$includeScreenshot pages=$totalPages")
            return if (includeScreenshot) {
                buildScreenshotResult(result, screenInfo, compactOutput)
            } else {
                McpToolUtils.untrustedTextResult(compactOutput)
            }
        }

        private fun buildFreshPageText(
            result: MultiWindowResult,
            screenInfo: ScreenInfo,
            totalKept: Int,
            totalPages: Int,
        ): String =
            if (totalPages <= 1) {
                screenStateSnapshotCache.clear()
                compactTreeFormatter.formatMultiWindow(result, screenInfo)
            } else {
                val snapshot =
                    ScreenStateSnapshot(
                        System.currentTimeMillis().toString(CURSOR_RADIX),
                        result, screenInfo, totalKept, totalPages,
                    )
                screenStateSnapshotCache.store(snapshot)
                compactTreeFormatter.formatMultiWindowPage(snapshot, 1)
            }

        private fun buildPagedText(
            cursorElement: JsonElement?,
            includeScreenshot: Boolean,
        ): String {
            // Non-primitive (object/array) ⇒ contentOrNull is null ⇒ parsed is null ⇒ invalid-cursor guidance.
            val parsed = (cursorElement as? JsonPrimitive)?.contentOrNull?.let { parseCursor(it) }
            val snapshot = parsed?.let { screenStateSnapshotCache.get(it.first) }
            val body =
                when {
                    parsed == null -> INVALID_CURSOR_MESSAGE
                    snapshot == null -> SNAPSHOT_GONE_MESSAGE
                    parsed.second < 1 || parsed.second > snapshot.totalPages ->
                        noSuchPageMessage(parsed.first, parsed.second, snapshot.totalPages)
                    else -> compactTreeFormatter.formatMultiWindowPage(snapshot, parsed.second)
                }
            // include_screenshot is ignored on ANY cursor call; when it was requested, append the
            // note to EVERY cursor response — valid page OR guidance — per agreed design point 9.
            return if (includeScreenshot) "$body\n$SCREENSHOT_ONLY_PAGE1_NOTE" else body
        }

        // Validates cursor FORMAT only (id present, page parses as an integer). Page RANGE
        // (1..totalPages) is validated by buildPagedText, so that page < 1 yields the
        // no-such-page guidance — NOT the invalid-cursor guidance.
        private fun parseCursor(cursor: String): Pair<String, Int>? {
            val dot = cursor.lastIndexOf('.')
            val page = cursor.substringAfterLast('.', "").toIntOrNull()
            return if (dot <= 0 || dot == cursor.length - 1 || page == null) {
                null
            } else {
                cursor.substring(0, dot) to page
            }
        }

        private fun ceilDiv(a: Int, b: Int): Int = if (a <= 0) 1 else (a + b - 1) / b
```

**Action 3.1.3** — Modify same file: extract the existing screenshot logic into `buildScreenshotResult(result, screenInfo, compactOutput)` (returns `untrustedTextAndImageResult`), keeping the permission check, bitmap capture, annotation over `result.windows`, encode, and `finally { recycle }` exactly as today.

- The extracted method MUST carry exactly the suppressions that previously applied to this code on `execute` — `@Suppress("ThrowsCount", "LongMethod", "TooGenericExceptionCaught")` — verbatim. These are PRE-EXISTING suppressions moved with the code, NOT new ones.
- After the refactor, `execute` is a small dispatcher and MUST NOT carry `@Suppress("ThrowsCount", "LongMethod", "TooGenericExceptionCaught")` anymore (the old annotation on `execute` is removed; its scope moves to `buildScreenshotResult`).

```kotlin
        @Suppress("ThrowsCount", "LongMethod", "TooGenericExceptionCaught")
        private suspend fun buildScreenshotResult(
            result: MultiWindowResult,
            screenInfo: ScreenInfo,
            compactOutput: String,
        ): CallToolResult {
            // ... permission check, captureScreenshotBitmap, annotate(result.windows),
            // ... encode, try/catch(McpToolException)/catch(Exception)/finally{recycle} — MOVED VERBATIM ...
        }
```

**Action 3.1.4** — Modify same file: add companion constants (alongside `TOOL_NAME`, `SCREENSHOT_MAX_SIZE`, `TAG`).

```kotlin
            internal const val CURSOR_RADIX = 36
            internal const val INVALID_CURSOR_MESSAGE =
                "note:invalid cursor. Call get_screen_state without a cursor to get a fresh " +
                    "screen-state snapshot starting at page 1."
            internal const val SNAPSHOT_GONE_MESSAGE =
                "note:this screen-state snapshot is no longer available (the screen state was " +
                    "refreshed since this cursor was issued). Call get_screen_state without a " +
                    "cursor to get a fresh snapshot."
            internal const val SCREENSHOT_ONLY_PAGE1_NOTE =
                "note:a screenshot can only be requested on page 1 (call get_screen_state without " +
                    "a cursor / without specifying a page)."

            internal fun noSuchPageMessage(id: String, page: Int, totalPages: Int): String =
                "note:page $page does not exist — snapshot $id has $totalPages page(s). Call " +
                    "get_screen_state without a cursor for a fresh snapshot, or request a valid page."
```

**Action 3.1.5** — Modify same file: in `register(...)`, add the `cursor` property to `inputSchema`. It MUST be placed INSIDE `buildJsonObject { ... }` but OUTSIDE (after) the existing `if (includeScreenshotParamEnabled) { ... }` block, so `cursor` is ALWAYS present and is NOT gated by param permissions:

```kotlin
                        properties =
                            buildJsonObject {
                                if (includeScreenshotParamEnabled) {
                                    putJsonObject("include_screenshot") { /* unchanged */ }
                                }
                                putJsonObject("cursor") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Pagination cursor from a previous response (format " +
                                            "\"<id>.<page>\"). Omit to capture a fresh screen state " +
                                            "starting at page 1. A cursor is tied to one screen " +
                                            "snapshot; if the screen changed you will be told to " +
                                            "request a fresh one.",
                                    )
                                }
                            },
```

Replace the existing `description = "..."` string for this tool with exactly the following (the first six lines are the current description verbatim; the final four lines are the pagination addition):

```kotlin
                description =
                    "Returns the current screen state: app info, screen dimensions, " +
                        "and a compact UI node list (text/desc truncated to 100 chars, use " +
                        "${toolNamePrefix}get_node_details to retrieve full values). Optionally includes a " +
                        "low-resolution screenshot (only request the screenshot when the node " +
                        "list alone is not sufficient to understand the screen layout). " +
                        "Includes a hierarchy section showing node nesting via indentation. " +
                        "Large screens are split into pages of 200 nodes: the response includes a " +
                        "'page:N/total' line and a cursor; call again with that cursor to fetch the " +
                        "next page. You do NOT need to fetch every page — stop once you have found " +
                        "what you need. A screenshot can only be requested on page 1 (without a cursor).",
```

**Definition of Done**:
- [x] `execute` and every new helper are ≤60 lines and ≤2 `return` statements (detekt LongMethod/ReturnCount).
- [x] No new `@Suppress` added except the pre-existing ones carried over onto `buildScreenshotResult`.
- [x] Tool description and `cursor` schema present; `include_screenshot` schema unchanged.

### Task 3.2: Registration function

**Action 3.2.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt` (modify): add `screenStateSnapshotCache: ScreenStateSnapshotCache` to `registerScreenIntrospectionTools(...)` parameters (after `nodeCache`) and pass it into the `GetScreenStateHandler(...)` constructor call.

**Definition of Done**:
- [x] `registerScreenIntrospectionTools` forwards the cache to the handler.

### Task 3.3: Service wiring + lifecycle

**Action 3.3.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt` (modify): add injected field near `nodeCache`.

```kotlin
    @Inject lateinit var screenStateSnapshotCache: ScreenStateSnapshotCache
```

**Action 3.3.2** — Modify same file: pass `screenStateSnapshotCache` into `registerScreenIntrospectionTools(...)` (after `nodeCache`).

**Action 3.3.3** — Modify same file: add `screenStateSnapshotCache.clear()` as the **first statement** of `onDestroy()`. Ordering is functionally irrelevant (it is a `@Volatile` reference swap with no dependency on server/coroutine state); the explicit anchor removes implementer latitude. Do not change any other line of `onDestroy()`.

Add import for `ScreenStateSnapshotCache`.

**Definition of Done**:
- [x] App compiles; snapshot cache cleared on service destroy.

---

## User Story 4: Tests

Cover the cache, the paginated formatter (boundaries, ancestor-closure hierarchy, multi-window page spanning, single-page parity), the handler dispatch/guidance/screenshot rules, and an end-to-end integration path.

### Acceptance Criteria
- [x] New cache unit tests pass.
- [x] Formatter pagination unit tests pass, including byte-equality of a single-page (≤200) result with the existing `formatMultiWindow` output.
- [x] Handler unit tests cover cursorless (single/multi), cursor success, all three guidance cases, and screenshot rules.
- [x] Integration test exercises pagination over the real SDK routing.

### Task 4.1: Cache unit tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ScreenStateSnapshotCacheImplTest.kt` (create)

**Setup**: `cache = ScreenStateSnapshotCacheImpl()`; build `ScreenStateSnapshot` via a small helper with an empty/leaf `MultiWindowResult`.

| Test | Verifies |
|------|----------|
| `get returns stored snapshot on id match` | `store` then `get(id)` returns same instance |
| `get returns null on id mismatch` | `get("other")` returns null |
| `store replaces previous snapshot` | After second `store`, `get(firstId)` is null, `get(secondId)` returns the second |
| `clear removes snapshot` | After `clear`, `get(id)` is null |

### Task 4.2: Shared pagination test infrastructure + formatter pagination unit tests

**Action 4.2.1** — Create `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/PaginationTestTrees.kt` (create). This is SHARED test infrastructure reused by Tasks 4.3 and 4.4 (defined here, before its first use); provided in full per the testing rule.

```kotlin
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
```

**Action 4.2.2** — Modify `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatterTest.kt` (modify): add the tests below.

**Setup**: build trees via `PaginationTestTrees.keptNodeWindow(...)` / `.result(...)` (and the existing `makeNode` for small trees). Construct `ScreenStateSnapshot` instances (id, result, screenInfo, totalKeptNodes via `countKeptNodes`, totalPages via ceil) to drive `formatMultiWindowPage(snapshot, page)`.

| Test | Verifies |
|------|----------|
| `countKeptNodes matches formatMultiWindow row count` | Count equals number of TSV rows produced by `formatMultiWindow` for the same tree |
| `formatMultiWindowPage page 1 contains first 200 rows` | Rows 1–200 present, row 201 absent; `page:1/N` line correct |
| `formatMultiWindowPage page 2 contains rows 201..400` | Correct slice and `nodes:201-400/total` line |
| `last page has end note` | Final page emits the "end of snapshot" note; non-final emits next-cursor note with `id.page+1` |
| `page hierarchy includes kept ancestors of page rows` | A page whose rows are deep descendants still shows their kept-ancestor chain, indented identically to the full hierarchy. **Setup**: ancestor kept-nodes placed on page 1, descendant rows on page 2; assert ancestors appear in page 2 hierarchy but NOT in page 2 TSV |
| `page hierarchy indentation equals full hierarchy` | For nodes present on a page, their indentation matches their indentation in `formatMultiWindow` |
| `window header repeats when window spans pages` | A single large window split across pages emits its window header + TSV header on each page |
| `multi-window page boundary splits correctly` | Global ordering across windows; a page boundary inside window 2 yields window-2-only rows with correct local slice |
| `single page output unchanged` | For ≤200 kept nodes, the handler path uses `formatMultiWindow`; assert `formatMultiWindow` output is byte-identical to the pre-change golden string (no `page:` line) |
| `degraded result paginates with degradation note on each page` | `MultiWindowResult(degraded=true)` with >200 kept nodes ⇒ each page begins with `DEGRADATION_NOTE`, then the standard note/screen/`page:` block |

### Task 4.3: Handler unit tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionToolsTest.kt` (modify)

**Setup** — two distinct handler configurations:
- **Existing tests** keep the strict `mockCompactTreeFormatter`. Update EVERY existing `GetScreenStateHandler(...)` construction (the ~lines 148–156 call) to pass the new `screenStateSnapshotCache` argument (a real `ScreenStateSnapshotCacheImpl()`). In `setupReadyService` (the shared setup), add `every { mockCompactTreeFormatter.countKeptNodes(any()) } returns 0` so existing tests stay on the single-page `formatMultiWindow` path and their stubbed assertions are unchanged.
- **New pagination tests** MUST construct a separate `GetScreenStateHandler` with a **real** `CompactTreeFormatter()` and a real `ScreenStateSnapshotCacheImpl()`. Rationale: the snapshot id is generated internally (`System.currentTimeMillis().toString(CURSOR_RADIX)`) and is only observable inside the real page-1 note (`snapshot:<id>`); the strict mock cannot surface it and the cache interface exposes no id accessor. Mock `accessibilityServiceProvider`/`treeParser` so the captured `MultiWindowResult` is `PaginationTestTrees.keptNodeWindow(...)`/`.result(...)` (Action 4.2.1) with a controllable kept-node count. For the cursor tests, parse the real `snapshot:<id>` token out of the page-1 note and build the cursor `"<id>.2"` from it.

| Test | Verifies |
|------|----------|
| `cursorless small screen returns single page no cursor` | ≤200 nodes ⇒ output has no `page:` line; cache `get` after call returns null (cleared) |
| `cursorless large screen returns page 1 and stores snapshot` | >200 nodes ⇒ `page:1/N` present; cache now holds a snapshot whose id is in the page-1 note |
| `cursor returns requested page from snapshot` | After cursorless capture, calling with `"<id>.2"` returns `page:2/N` text without re-capturing (verify `getFreshWindows` deps not called again) |
| `malformed cursor returns invalid-cursor guidance` | `arguments cursor="garbage"` ⇒ text contains the invalid-cursor note; `isError` not true |
| `non-primitive cursor returns invalid-cursor guidance` | `cursor` passed as a JSON object/array ⇒ invalid-cursor note, `isError` not true, NO exception thrown (verifies the `as? JsonPrimitive` extraction) |
| `stale snapshot id returns snapshot-gone guidance` | Cursor with unknown id ⇒ snapshot-gone note; not error |
| `out of range page returns no-such-page guidance` | Cursor `"<id>.999"` AND `"<id>.0"` ⇒ no-such-page note naming total pages; not error (verifies `page < 1` routes to no-such-page, NOT invalid-cursor) |
| `screenshot honored on cursorless page 1` | `include_screenshot=true`, no cursor, capture available ⇒ result has image content |
| `screenshot ignored with cursor and note appended` | `include_screenshot=true` + valid cursor ⇒ text-only result containing the screenshot-only-page-1 note |
| `screenshot note appended on cursor guidance paths` | `include_screenshot=true` + a stale-id / invalid / out-of-range cursor ⇒ text-only guidance result that ALSO contains the screenshot-only-page-1 note (verifies the note appears on ANY cursor call, not only valid pages) |

### Task 4.4: Integration test

**Action 4.4.1** — Modify `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt` (modify):
- Add a `screenStateSnapshotCache: ScreenStateSnapshotCache` field to the `MockDependencies` data class.
- In `createMockDependencies()` construct it as a real `ScreenStateSnapshotCacheImpl()`.
- Pass `deps.screenStateSnapshotCache` into the `registerScreenIntrospectionTools(...)` call (after `deps.nodeCache`).
- Add imports for `com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache` and `...ScreenStateSnapshotCacheImpl`.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt` (modify)

**Setup**: build the window via `PaginationTestTrees.keptNodeWindow(keptLeafCount = >200, ...)` (Action 4.2.1) and pass its tree to `setupMultiWindowMock` so `get_screen_state` yields a real >200-row snapshot through the live SDK routing. For the small-screen test, use `keptNodeWindow(keptLeafCount = small)` (≤200 total kept nodes).

| Test | Verifies |
|------|----------|
| `get_screen_state paginates over streamable http` | First call (no cursor) returns `page:1/N` + a cursor; second call with that cursor returns `page:2/N`; out-of-range page returns no-such-page guidance — all over the real SDK `callTool` path |
| `get_screen_state small screen returns no cursor` | ≤200 kept nodes ⇒ response has no `page:` line |

**Definition of Done**:
- [x] All new/updated tests pass under `./gradlew :app:test`.
- [x] No existing test left broken (fix any breakage caused by the new constructor/registration parameters).

---

## User Story 5: Quality gates and ground-up verification

Run the full quality gates, then re-verify the entire implementation from scratch against this plan and the discussed behavior.

### Acceptance Criteria
- [x] `make lint` clean (ktlint + detekt), no new suppressions.
- [x] `./gradlew :app:test` green.
- [x] `./gradlew build` succeeds without warnings.
- [x] code-reviewer (plan-compliance mode) reports no issues.
- [x] Ground-up re-verification completed and documented.

### Task 5.1: Linting

**Action 5.1.1** — Run `make lint 2>&1 | tee /tmp/p47-lint.log | tail -40`; fix every violation at the root (no `@Suppress` beyond the carried-over screenshot ones).

**Definition of Done**:
- [x] Zero ktlint/detekt violations.

### Task 5.2: Tests

**Action 5.2.1** — Run `make test 2>&1 | tee /tmp/p47-test.log | tail -40`; fix any failure (including pre-existing breakage surfaced by the new parameters).

**Definition of Done**:
- [x] Full suite green.

### Task 5.3: Build

**Action 5.3.1** — Run `./gradlew build 2>&1 | tee /tmp/p47-build.log | tail -60`.

**Definition of Done**:
- [x] Build succeeds, no warnings.

### Task 5.4: Plan-compliance review

**Action 5.4.1** — Spawn the `code-reviewer` subagent in plan-compliance mode against this plan; fix ALL reported findings (CRITICAL, WARNING, INFO); re-run until clean.

**Definition of Done**:
- [x] code-reviewer clean. (Two independent adversarial plan-compliance reviews → PASS, 0/0/0.)

### Task 5.5: Ground-up double-check (FINAL)

**Action 5.5.1** — Re-verify the ENTIRE implementation from the ground up, independently of the checkmarks above. Read every created/modified file end-to-end and confirm, point by point:
- [x] The hierarchy block representation is byte-for-byte identical to the pre-change format (indented `node_id`-only, kept nodes only, structural collapsed, 2-space kept-depth); only the set of ids on a page differs (page rows + kept-ancestors). (Verified: only diff to the formatter's existing code is the two visibility-modifier swaps; `walkTree`/`appendElementRow` body unchanged.)
- [x] TSV row format, flags, window-header format, and note lines are unchanged.
- [x] Cursorless ≤200 output is byte-identical to the previous behavior, and the snapshot cache is cleared in that case. (`buildFreshPageText` totalPages≤1 branch → `formatMultiWindow` + `clear()`.)
- [x] Cursorless >200 stores a snapshot keyed by `base36(System.currentTimeMillis())` and returns page 1.
- [x] Cursor calls never re-capture, never touch `nodeCache`, and serve only from the frozen snapshot. (Verified: `buildPagedText` references neither `getFreshWindows` nor `nodeCache`.)
- [x] The three guidance cases (malformed / stale id / out-of-range) return `isError=false` normal text via `untrustedTextResult`.
- [x] `include_screenshot` is honored only on the cursorless call; on any cursor call it is ignored and the screenshot-only-page-1 note is appended.
- [x] Page size is 200; `page:` line and pagination note are present only when paginating (totalPages > 1).
- [x] Snapshot holds no live `AccessibilityNodeInfo` references; cache cleared on `McpServerService.onDestroy` (first statement).
- [x] No behavior diverges from this plan or the agreed discussion; OUT OF SCOPE items were not touched.

**Definition of Done**:
- [x] Every checkbox in Action 5.5.1 verified true; any discrepancy fixed and the gates (5.1–5.4) re-run.
