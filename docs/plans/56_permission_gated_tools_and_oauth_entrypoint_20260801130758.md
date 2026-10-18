<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 56 — Permission-gated tools, main-screen gating & OAuth pending entry point

## Agreed decisions (source of truth — do NOT diverge)

1. **Timing**: the effective tool set is computed **at server start** only (restart model). No live re-registration, no `tools/list_changed`.
2. **Gating scope — optional permissions only**:
   - **Whole-category (tool-level) gates**: **Camera** (`CAMERA`), **Location** (`ACCESS_FINE_LOCATION`), **Notifications** (notification-listener access). When the permission is missing at start, those tools are absent from `tools/list`.
   - **Parameter-level gate**: **Microphone** (`RECORD_AUDIO`) gates only the `audio` parameter of `save_camera_video` via the existing `disabledParams`/`isParamEnabled` path (`audioParamEnabled=false` → param dropped from the input schema and forced `false` at call time). `save_camera_video` itself is gated only by `CAMERA`.
   - **NOT gated**: File Operations, Sharing (they function on app-scoped storage without `READ_MEDIA_*`), and all accessibility-dependent categories (accessibility gates *start*, not `tools/list`). Confirmed by user.
3. **Accessibility gate on start**: when accessibility is disabled, **both** start buttons on the Server screen — MCP Server *and* Event Channel — are disabled (Stop stays enabled). The main-screen permission warning shows **only** when accessibility is missing. Confirmed by user. Notes on intended consequences (explicit decisions, NOT limitations): (a) the Event Channel does not itself use the accessibility service, but gating its start too is the user's explicit choice ("the 2 start buttons"); (b) `POST_NOTIFICATIONS` is intentionally no longer surfaced by the main-screen warning — it stays grantable in the Permissions settings screen, and the pending-approvals card (decision 5) removes the need for it to reach OAuth requests.
4. **Settings UI**: per-**category** grey-out — amber triangle + note at the category header (rows' switches disabled), tapping the header navigates to the Permissions screen; the `audio` param row shows its own amber triangle + note when mic is missing. The stored enable flags are **never** modified.
5. **OAuth entry point**: a card on the Server screen shown **only when pending > 0**, tapping opens the existing `ApprovalActivity`.

### Optional-permission → tool mapping (whole-category)

| Permission | Tools |
|---|---|
| CAMERA | `list_cameras`, `list_camera_photo_resolutions`, `list_camera_video_resolutions`, `take_camera_photo`, `save_camera_photo`, `save_camera_video` |
| LOCATION | `get_location` |
| NOTIFICATION_LISTENER | `notification_list`, `notification_open`, `notification_dismiss`, `notification_snooze`, `notification_action`, `notification_reply` |

### Optional-permission → parameter mapping (param-level)

| Permission | Tool → Param |
|---|---|
| MICROPHONE | `save_camera_video` → `audio` |

### Test-strategy note (repo reality)
The repo has **no Compose UI test infrastructure** (no `createComposeRule`, no `androidTest`, no Robolectric). Following the established convention (JUnit 5 + MockK unit tests + Ktor `testApplication` integration tests), all gating logic is **derived from a pure, unit-tested shared model** and the server behavior is covered by an **integration test** driving `tools/list`. Composable rendering (triangle/grey-out/navigation visuals) is covered by clearly-labelled **Manual QA** at the end; no new UI-test framework is added.

---

## User Story 1 — Compute the effective tool set at server start (server-side gating)

**Why**: tools/params whose optional permission is missing must be absent from `tools/list` (not merely error at call time). Reuses the existing denylist (`ToolPermissionsConfig.disabledTools`/`disabledParams` → the per-tool `if (perms.isToolEnabled(name))` guards and `save_camera_video`'s `audioParamEnabled`), so **no registrar file is modified**.

**Acceptance criteria**:
- [ ] A single shared model maps each optional permission to its tools (and, for mic, its param) and computes the effective config from a granted set.
- [ ] At server start, missing-permission tools are unioned into an effective `disabledTools` and missing-mic params merged into an effective `disabledParams`; the stored `ToolPermissionsConfig` is untouched.
- [ ] When Camera/Location/Notification-listener permission is missing at start, its tools are absent from `tools/list`; when present, they appear (subject to the user's own toggles).
- [ ] When mic is missing at start, `save_camera_video` is still listed but its `audio` param is absent from the input schema.

### Task 1.1 — Create the shared optional-permission model

- [x] **Create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/OptionalToolPermission.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/** Optional Android permissions that gate MCP tools (whole category) or a single tool parameter (Plan 56). */
enum class OptionalToolPermission {
    CAMERA,
    LOCATION,
    NOTIFICATION_LISTENER,
    MICROPHONE,
}

/** Single source of truth mapping each optional permission to the tools (or params) it gates. */
object OptionalToolPermissions {
    /** Whole-category (tool-level) gates. MICROPHONE intentionally absent — it gates a param only. */
    val TOOLS_BY_PERMISSION: Map<OptionalToolPermission, Set<String>> =
        mapOf(
            OptionalToolPermission.CAMERA to
                setOf(
                    "list_cameras",
                    "list_camera_photo_resolutions",
                    "list_camera_video_resolutions",
                    "take_camera_photo",
                    "save_camera_photo",
                    "save_camera_video",
                ),
            OptionalToolPermission.LOCATION to setOf("get_location"),
            OptionalToolPermission.NOTIFICATION_LISTENER to
                setOf(
                    "notification_list",
                    "notification_open",
                    "notification_dismiss",
                    "notification_snooze",
                    "notification_action",
                    "notification_reply",
                ),
        )

    /** Param-level gates: permission -> (toolName -> paramNames). */
    val PARAMS_BY_PERMISSION: Map<OptionalToolPermission, Map<String, Set<String>>> =
        mapOf(
            OptionalToolPermission.MICROPHONE to mapOf("save_camera_video" to setOf("audio")),
        )

    /** Tool names whose gating permission is NOT in [granted]. */
    fun toolsMissingPermission(granted: Set<OptionalToolPermission>): Set<String> =
        TOOLS_BY_PERMISSION.filterKeys { it !in granted }.values.flatten().toSet()

    /** Params (toolName -> paramNames) whose gating permission is NOT in [granted]. */
    fun paramsMissingPermission(granted: Set<OptionalToolPermission>): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        PARAMS_BY_PERMISSION.filterKeys { it !in granted }.values.forEach { toolParams ->
            toolParams.forEach { (tool, params) -> result.getOrPut(tool) { mutableSetOf() }.addAll(params) }
        }
        return result.mapValues { it.value.toSet() }
    }

    /** The optional permission gating [toolName], or null if the tool is not permission-gated. */
    fun permissionForTool(toolName: String): OptionalToolPermission? =
        TOOLS_BY_PERMISSION.entries.firstOrNull { toolName in it.value }?.key

    /** The optional permission gating [paramName] of [toolName], or null. */
    fun permissionForParam(
        toolName: String,
        paramName: String,
    ): OptionalToolPermission? =
        PARAMS_BY_PERMISSION.entries.firstOrNull { paramName in (it.value[toolName] ?: emptySet()) }?.key

    /** Builds the granted-permission set from individual permission booleans (pure; unit-testable). */
    fun grantedPermissions(
        camera: Boolean,
        microphone: Boolean,
        location: Boolean,
        notificationListener: Boolean,
    ): Set<OptionalToolPermission> =
        buildSet {
            if (camera) add(OptionalToolPermission.CAMERA)
            if (microphone) add(OptionalToolPermission.MICROPHONE)
            if (location) add(OptionalToolPermission.LOCATION)
            if (notificationListener) add(OptionalToolPermission.NOTIFICATION_LISTENER)
        }

    /**
     * Effective config for registration: stored denylist plus the tools/params whose optional
     * permission is not in [granted]. The stored [stored] instance is NOT mutated.
     */
    fun effectivePermissions(
        stored: ToolPermissionsConfig,
        granted: Set<OptionalToolPermission>,
    ): ToolPermissionsConfig {
        val missingParams = paramsMissingPermission(granted)
        val mergedParams =
            (stored.disabledParams.keys + missingParams.keys).associateWith { tool ->
                stored.disabledParams[tool].orEmpty() + missingParams[tool].orEmpty()
            }
        return stored.copy(
            disabledTools = stored.disabledTools + toolsMissingPermission(granted),
            disabledParams = mergedParams,
        )
    }
}
```

**DoD**:
- [x] Enum has exactly four values; MICROPHONE appears only in `PARAMS_BY_PERMISSION`.
- [x] Tool/param names match the handler `TOOL_NAME`/param constants and the `McpToolsSettingsScreen` catalog strings.
- [x] `effectivePermissions` returns a new object; `stored` is never mutated.

### Task 1.2 — Use the effective config at server start

- [x] **Modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

Replace the current registration call (`registerAllTools(sdkServer, toolNamePrefix, config.toolPermissionsConfig, config.fileSizeLimitMb)`, line ~265) with:

```kotlin
// ALL four signals MUST come from PermissionUtils — the SAME source the UI uses
// (MainViewModel.refreshPermissionStatus) — so the server-start gate and the settings grey-out
// never diverge. Do NOT use cameraProvider.isCameraPermissionGranted()/isMicrophonePermissionGranted()
// or notificationProvider.isReady() here.
val grantedOptionalPermissions =
    OptionalToolPermissions.grantedPermissions(
        camera = PermissionUtils.isCameraPermissionGranted(this@McpServerService),
        microphone = PermissionUtils.isMicrophonePermissionGranted(this@McpServerService),
        location = PermissionUtils.isLocationPermissionGranted(this@McpServerService),
        notificationListener =
            PermissionUtils.isNotificationListenerEnabled(
                this@McpServerService,
                McpNotificationListenerService::class.java,
            ),
    )
val effectivePerms =
    OptionalToolPermissions.effectivePermissions(config.toolPermissionsConfig, grantedOptionalPermissions)
registerAllTools(sdkServer, toolNamePrefix, effectivePerms, config.fileSizeLimitMb)
```

Add imports: `com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermissions`, `com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils`, `com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService`. (The `OptionalToolPermission` enum is not referenced directly here, so it is NOT imported.)

**DoD**:
- [x] `config.toolPermissionsConfig` is never mutated; only local `effectivePerms` carries the extra disabled tools/params.
- [x] No `register*Tools` function is modified.
- [x] All four granted signals come from `PermissionUtils` — identical to the UI — NOT from `cameraProvider`/`notificationProvider`.

### Task 1.3 — Tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/OptionalToolPermissionTest.kt`

**Setup**: pure functions; no mocks. `ALL = setOf(CAMERA, LOCATION, NOTIFICATION_LISTENER, MICROPHONE)`.

| Test | Verifies |
|------|----------|
| `toolsMissingPermission returns all gated tools when none granted` | `emptySet()` → union of the 3 tool-level sets (13 names) |
| `toolsMissingPermission returns empty when all granted` | `ALL` → empty |
| `missing camera hides only the 6 camera tools` | Granted = ALL − CAMERA → exactly the 6 camera names |
| `missing location hides only get_location` | Granted = ALL − LOCATION → `{"get_location"}` |
| `missing notification listener hides only the 6 notification tools` | Granted = ALL − NOTIFICATION_LISTENER → exactly the 6 notification names |
| `MICROPHONE gates no tools` | For every granted set, `toolsMissingPermission` never contains a tool only because MICROPHONE is absent |
| `tool names are unique across permissions` | No tool name appears under two permissions |
| `paramsMissingPermission returns audio when mic missing` | Granted = ALL − MICROPHONE → `{"save_camera_video" to setOf("audio")}` |
| `paramsMissingPermission empty when mic granted` | Granted contains MICROPHONE → empty map |
| `permissionForTool maps known tools and null otherwise` | `"tap"` → null; `"take_camera_photo"` → CAMERA; `"get_location"` → LOCATION; `"notification_list"` → NOTIFICATION_LISTENER |
| `permissionForParam maps audio and null otherwise` | `("save_camera_video","audio")` → MICROPHONE; `("save_camera_video","resolution")` → null |
| `grantedPermissions maps booleans to enum set` | `(true,false,false,false)` → `{CAMERA}`; `(false,true,false,false)` → `{MICROPHONE}`; `(false,false,true,false)` → `{LOCATION}`; `(false,false,false,true)` → `{NOTIFICATION_LISTENER}`; all-true → the 4-value set; all-false → empty |
| `effectivePermissions unions disabled tools and merges params` | stored `disabledTools={"tap"}`, `disabledParams={"get_screen_state" to {"include_screenshot"}}`, granted = ALL − CAMERA − MICROPHONE → disabledTools contains `"tap"` + 6 camera; disabledParams contains the stored entry AND `"save_camera_video" to {"audio"}` |
| `effectivePermissions does not mutate stored` | The passed-in `stored` object's `disabledTools`/`disabledParams` are unchanged after the call |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/PermissionGatedToolsIntegrationTest.kt`

**Setup**: mirror `ToolPermissionsIntegrationTest` — `McpIntegrationTestHelper.createMockDependencies()`, then `withTestApplication(deps, perms = OptionalToolPermissions.effectivePermissions(ToolPermissionsConfig(), granted)) { client, _ -> ... }`. List tools via the SDK client's list-tools call; inspect `save_camera_video`'s `inputSchema` properties for the `audio` key. **Note**: `tools/list` returns tool names WITH the helper's `toolNamePrefix` (e.g. `android_`), exactly as `ToolPermissionsIntegrationTest` asserts (`android_tap`, `android_save_camera_video`). Assertions below use the unprefixed name for brevity but MUST match against the prefixed name (`android_<tool>`).

| Test | Verifies |
|------|----------|
| `camera tools absent from tools_list when camera not granted` | granted = {LOCATION, NOTIFICATION_LISTENER} → none of the 6 `android_`-prefixed camera tool names present |
| `location tool absent when location not granted` | granted omits LOCATION → `android_get_location` absent |
| `notification tools absent when listener not granted` | granted omits NOTIFICATION_LISTENER → the 6 `android_`-prefixed notification tools absent |
| `all optional tools present when all granted` | granted = all four → the 13 `android_`-prefixed optional tools present |
| `save_camera_video lists audio param when mic granted` | granted includes CAMERA and MICROPHONE → `audio` present in `android_save_camera_video` inputSchema |
| `save_camera_video omits audio param when mic missing` | granted includes CAMERA, omits MICROPHONE → `android_save_camera_video` present but `audio` absent from its inputSchema |

---

## User Story 2 — Per-category (and mic-param) permission grey-out in the MCP Tools settings screen

**Why**: users must see *why* a category/param is unavailable and reach the grant flow in one tap, without the app flipping their stored enable flags. Gating is **derived from `OptionalToolPermissions`** (the same source of truth as US1) so the two never drift.

**Acceptance criteria**:
- [ ] Camera / Location / Notifications category headers show an amber warning triangle + note when their permission is missing.
- [ ] Rows under a permission-missing category are greyed (switches disabled) regardless of server state; the checked value still reflects the stored flag.
- [ ] The `save_camera_video` `audio` param row is greyed with its own amber triangle + note when mic is missing (and camera is granted, so the row is visible).
- [ ] Tapping a permission-missing category header (or the `audio` param note) navigates to the Permissions settings screen.
- [ ] Permission state refreshes when the screen resumes.

### Task 2.1 — Add strings (defined before their first use)

- [x] **Modify** `app/src/main/res/values/strings.xml` — add:

```xml
<string name="settings_mcp_tools_missing_permission">Permission not granted — these tools are hidden from clients. Tap to grant.</string>
<string name="settings_mcp_tools_param_missing_permission">Permission not granted — this option is unavailable. Tap to grant.</string>
```

**DoD**:
- [x] String keys are unique in the file.
- [x] The category note uses `settings_mcp_tools_missing_permission` (tools hidden); the param note uses `settings_mcp_tools_param_missing_permission` (option unavailable — the tool is NOT hidden).

### Task 2.2 — Add a shared warning-amber color to the theme (single source of truth)

- [x] **Modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/theme/Color.kt` — add:

```kotlin
/** Amber used for advisory warnings (yellow triangle). ARGB 0xFFF9A825. */
val WarningAmber = Color(0xFFF9A825)
```

- [x] **Modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt`:
  - Remove the now-duplicate private definitions `private const val WARNING_AMBER_ARGB = 0xFFF9A825L` and `private val WarningAmber = Color(WARNING_AMBER_ARGB)` (lines ~54-55).
  - Add import `com.danielealbano.androidremotecontrolmcp.ui.theme.WarningAmber`. The two existing usages (`tint = WarningAmber`, lines ~286 and ~308) then resolve to the shared token.
  - Remove the now-unused import `androidx.compose.ui.graphics.Color` (line ~34). After deleting line 55, `Color` has NO other use in this file (verified: `Color` appears only at the import and the deleted definition — no `: Color` types, no other `Color(...)`/`Color.*` usages), so leaving the import triggers ktlint `standard:no-unused-imports` / detekt `UnusedImports`.
  - This migration is a direct consequence of introducing the shared color — it prevents two identical amber constants coexisting.

**DoD**:
- [x] `WarningAmber` is public and importable from `ui.theme`; `Color.kt` already has `@file:Suppress("MagicNumber")`.
- [x] `ConnectionInfoCard.kt` no longer defines its own `WarningAmber`/`WARNING_AMBER_ARGB`, imports the shared token, has NO unused `Color` import, and its rendered color is unchanged (same ARGB).

### Task 2.3 — Wire permission gating into `McpToolsSettingsScreen`

- [x] **Modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/McpToolsSettingsScreen.kt`

Changes (the private `ALL_TOOL_CATEGORIES` catalog and the `ToolEntry`/`ParamEntry`/`ToolCategory` data classes are **NOT** modified — gating is derived from `OptionalToolPermissions`):

1. Add parameter `onNavigateToPermissions: () -> Unit` to `McpToolsSettingsScreen`.
2. Collect permission flows + refresh on resume, and add a local granted-check:

```kotlin
val context = LocalContext.current
val lifecycleOwner = LocalLifecycleOwner.current
val cameraGranted by viewModel.isCameraPermissionGranted.collectAsStateWithLifecycle()
val locationGranted by viewModel.isLocationPermissionGranted.collectAsStateWithLifecycle()
val notificationListenerGranted by viewModel.isNotificationListenerEnabled.collectAsStateWithLifecycle()
val microphoneGranted by viewModel.isMicrophonePermissionGranted.collectAsStateWithLifecycle()

// Refresh permissions on ON_RESUME — SAME pattern as PermissionsSettingsScreen (do NOT use LifecycleEventEffect).
DisposableEffect(lifecycleOwner) {
    val observer =
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionStatus(context)
            }
        }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}

fun isGranted(permission: OptionalToolPermission): Boolean =
    when (permission) {
        OptionalToolPermission.CAMERA -> cameraGranted
        OptionalToolPermission.LOCATION -> locationGranted
        OptionalToolPermission.NOTIFICATION_LISTENER -> notificationListenerGranted
        OptionalToolPermission.MICROPHONE -> microphoneGranted
    }
```

3. Compute the permission-derived gating **inside** the lazy `item {}` / `items {}` slots (NOT in the `forEach` body) so a permission change recomposes only the affected slots. Derive from the shared model:

```kotlin
// static list of the distinct permissions this category's tools require (from OptionalToolPermissions)
val categoryPermissions = category.tools.mapNotNull { OptionalToolPermissions.permissionForTool(it.toolName) }.distinct()
```

   In the header `item(key = "header_${category.header}") { }` slot, read the state and render:
   - `val categoryMissingPermission = categoryPermissions.firstOrNull { !isGranted(it) }`.
   - When `categoryMissingPermission == null`: render the header exactly as today (single `Text`, no icon, not clickable).
   - When `categoryMissingPermission != null`: render a `Column` containing (a) a `Row(modifier = Modifier.clickable { onNavigateToPermissions() }, verticalAlignment = Alignment.CenterVertically)` with the existing header `Text` + `Icon(Icons.Default.Warning, tint = WarningAmber, contentDescription = null)`, then (b) a note `Text(stringResource(R.string.settings_mcp_tools_missing_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))`. The `Column` wrapper avoids sibling overlap inside the lazy item. The icon is decorative (`contentDescription = null`) — the adjacent note `Text` already conveys the meaning to TalkBack, so the icon must NOT repeat it.
4. In the `items(category.tools) { tool -> }` slot: `val categoryGranted = categoryPermissions.all { isGranted(it) }`; the tool row `Switch` `enabled = isEnabled && categoryGranted` (keep `checked = toolEnabled` from the stored flag).
5. Param sub-rows render on the stored `toolEnabled` flag (`perms.isToolEnabled(...)`), independent of `categoryGranted`. For each **param** sub-row, derive `val paramPermission = OptionalToolPermissions.permissionForParam(tool.toolName, param.paramName)` and `val paramGranted = paramPermission == null || isGranted(paramPermission)`:
   - `Switch` `enabled = isEnabled && categoryGranted && paramGranted` (keep `checked` from the stored flag).
   - Show the param note ONLY when `categoryGranted && !paramGranted` (i.e., the tool is available but its option is not) — this prevents the audio note showing at the same time as the Camera category note when both camera and mic are missing. In that case, on the param `ListItem`, add `leadingContent = { Icon(Icons.Default.Warning, tint = WarningAmber, contentDescription = null) }`, `supportingContent = { Text(stringResource(R.string.settings_mcp_tools_param_missing_permission), color = MaterialTheme.colorScheme.onSurfaceVariant) }`, and extend its `modifier` with `.clickable { onNavigateToPermissions() }` (keep the existing `padding(start = 32.dp)`). The icon is decorative (`contentDescription = null`) — the `supportingContent` text conveys the meaning.

Add imports: `com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermission`, `com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermissions`, `com.danielealbano.androidremotecontrolmcp.ui.theme.WarningAmber`, `androidx.compose.material.icons.filled.Warning`, `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.Row`, `androidx.compose.ui.Alignment`, `androidx.compose.runtime.DisposableEffect`, `androidx.compose.ui.platform.LocalContext`, `androidx.lifecycle.compose.LocalLifecycleOwner`, `androidx.lifecycle.LifecycleEventObserver`, `androidx.lifecycle.Lifecycle`. (`Column`, `Icon`, `Text`, `MaterialTheme`, `Modifier`, `padding`, `collectAsStateWithLifecycle`, `stringResource`, `dp` are already imported.)

**DoD**:
- [x] `viewModel.updateToolEnabled` / `updateParamEnabled` are never called as a side effect of permission state (stored flags untouched).
- [x] Header note + triangle appear only for gated categories with a missing permission; the `audio` param row shows its own note + triangle when mic is missing.
- [x] No new ktlint/detekt violations.

### Task 2.4 — Pass the navigate-to-Permissions callback

- [x] **Modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/SettingsScreen.kt` — the `SettingsRoute.McpTools` composable:

```kotlin
composable(SettingsRoute.McpTools.route) {
    McpToolsSettingsScreen(
        onBack = { navController.popBackStack() },
        onNavigateToPermissions = { navController.navigate(SettingsRoute.Permissions.route) },
    )
}
```

**DoD**:
- [x] Navigation lands on the existing `SettingsRoute.Permissions` destination.

---

## User Story 3 — Main screen: accessibility gate + OAuth pending card

**Why**: accessibility is the one required permission (block start, warn only about it); optional-permission tools self-manage via US1/US2. Pending OAuth requests must be reachable without the notification permission.

**Acceptance criteria**:
- [ ] The main-screen permission warning shows **only** when accessibility is missing.
- [ ] Both start buttons (MCP Server, Event Channel) are disabled while accessibility is missing; Stop stays enabled.
- [ ] A pending-approvals card appears only when count > 0 and opens `ApprovalActivity` on tap.

### Task 3.1 — Strings (defined before their first use)

- [x] **Modify** `app/src/main/res/values/strings.xml`:
  - CHANGE the value of the existing `permission_warning_message` (currently "Some permissions are not granted. Tap to review.") to the accessibility-specific text. This string is referenced ONLY by the main-screen `PermissionWarningCard`, which is now accessibility-only — so repurposing it (rather than adding a new key) avoids leaving an orphaned/unused resource:
    ```xml
    <string name="permission_warning_message">Accessibility permission is required. Tap to grant.</string>
    ```
  - ADD:
    ```xml
    <string name="server_pending_approvals_message">%1$d pending connection approval(s). Tap to review.</string>
    ```

**DoD**:
- [x] `permission_warning_message` is repurposed in place (no new accessibility string added, no orphaned/unused string left behind).
- [x] `server_pending_approvals_message` key is unique in the file.

### Task 3.2 — Expose pending-approval count from `MainViewModel`

- [x] **Modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`

1. Add constructor param `private val approvalCoordinator: OAuthApprovalCoordinator,` as the **last** parameter, immediately AFTER `@param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,`. (Position matters: the four test construction sites pass positional args ending in the dispatcher — see Task 3.5.)
2. Expose (reusing the existing `FLOW_TIMEOUT_MS` constant, line ~569; `map`/`stateIn`/`SharingStarted` are already imported):

```kotlin
val pendingApprovalCount: StateFlow<Int> =
    approvalCoordinator
        .observePending()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), 0)
```

Add import: `com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthApprovalCoordinator` (only this import is new).

**DoD**:
- [x] Hilt still constructs `MainViewModel` (coordinator is already bound in `AppModule`).

### Task 3.3 — Gate both start buttons in `ServerStatusCard`

- [x] **Modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ServerStatusCard.kt`

1. Add parameter `startEnabled: Boolean,` **before** `modifier: Modifier = Modifier` in `ServerStatusCard`.
2. Add two pure, `internal` top-level helpers (unit-testable, no Compose):

```kotlin
internal fun mcpStartStopButtonEnabled(
    status: ServerStatus,
    startEnabled: Boolean,
): Boolean =
    when (status) {
        is ServerStatus.Running -> true
        is ServerStatus.Stopped -> startEnabled
        else -> false
    }

internal fun channelStartStopButtonEnabled(
    channelEnabled: Boolean,
    startEnabled: Boolean,
): Boolean = if (channelEnabled) true else startEnabled
```

3. MCP Server row `buttonEnabled = mcpStartStopButtonEnabled(serverStatus, startEnabled)`.
4. Event Channel row `buttonEnabled = channelStartStopButtonEnabled(channelEnabled, startEnabled)`.
5. Update the `@Preview` to pass `startEnabled = true`.

**DoD**:
- [x] Stop actions remain enabled when running; start actions require `startEnabled`.
- [x] Behavior identical to before when `startEnabled = true`.

### Task 3.4 — Update `ServerScreen`

- [x] **Modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt`

1. Remove the `hasAllPermissions` block (lines ~75-80) and the now-unused permission collects `isNotificationPermissionGranted`, `isCameraPermissionGranted`, `isMicrophonePermissionGranted`, `isNotificationListenerEnabled` (they are used ONLY by `hasAllPermissions`). Keep `isAccessibilityEnabled` and `isBatteryOptimizationIgnored`.
2. Warning gate becomes accessibility-only:

```kotlin
if (!isAccessibilityEnabled) {
    PermissionWarningCard(onClick = onNavigateToPermissions)
    Spacer(Modifier.height(16.dp))
}
```

3. Collect the pending count and render a card **above** `ServerStatusCard` when > 0:

```kotlin
val pendingApprovalCount by viewModel.pendingApprovalCount.collectAsStateWithLifecycle()
if (pendingApprovalCount > 0) {
    PendingApprovalsCard(
        count = pendingApprovalCount,
        onClick = { context.startActivity(Intent(context, ApprovalActivity::class.java)) },
    )
    Spacer(Modifier.height(16.dp))
}
```

4. Pass `startEnabled = isAccessibilityEnabled` to `ServerStatusCard`.
5. The existing private `PermissionWarningCard` keeps referencing `R.string.permission_warning_message` — NO change to the `stringResource` call; the string's TEXT is updated in Task 3.1.
6. Add a private `PendingApprovalsCard(count: Int, onClick: () -> Unit)` — an `ElevatedCard` (`clickable(onClick = onClick)`) with `Icon(Icons.Default.Notifications, tint = MaterialTheme.colorScheme.primary, contentDescription = null)` + `Text(stringResource(R.string.server_pending_approvals_message, count))`.

Add imports: `com.danielealbano.androidremotecontrolmcp.ui.ApprovalActivity`, `androidx.compose.material.icons.filled.Notifications`. (`Intent`, `LocalContext`, `Icons` are already imported.)

**DoD**:
- [x] No unused variables / no new ktlint/detekt warnings after removing the extra collects.
- [x] Card visible only when count > 0; tap launches `ApprovalActivity`.

### Task 3.5 — Tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt` (extend)

**Setup**: add a `mockk<OAuthApprovalCoordinator>()` whose `observePending()` returns a test-controlled `MutableStateFlow<List<PendingApproval>>(emptyList())`. Pass this mock as the **last positional argument** (immediately after `testDispatcher`) at **all four** `MainViewModel(...)` construction sites (lines ~101, ~455, ~496, ~1058) so the module compiles and the eager `pendingApprovalCount` initializer is safe. Use Turbine for the new assertions.

| Test | Verifies |
|------|----------|
| `pendingApprovalCount is zero when no pending approvals` | Empty list → 0 |
| `pendingApprovalCount reflects list size` | 2-item list → 2 (build `PendingApproval` items with minimal required fields) |
| `pendingApprovalCount updates on coordinator emission` | Emit 0 → 1 → 2; count follows each emission |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ServerStatusCardTest.kt`

**Setup**: pure functions; no mocks; construct `ServerStatus` variants directly.

| Test | Verifies |
|------|----------|
| `mcp start enabled only when stopped and startEnabled` | Stopped+true → true; Stopped+false → false |
| `mcp stop always enabled when running` | Running+false → true; Running+true → true |
| `mcp disabled while starting or stopping` | Starting/Stopping/Error → false regardless of startEnabled |
| `channel start requires startEnabled` | channelEnabled=false: true→true, false→false |
| `channel stop always enabled` | channelEnabled=true → true regardless of startEnabled |

---

## User Story 4 — Quality gates & final ground-up double-check (LAST)

**Why**: bring the whole change to green and then audit everything from scratch. The ground-up double-check (Task 4.3) is the final item of the plan.

### Task 4.1 — Quality gates

- [x] `make lint` clean (ktlint + detekt), no suppressions added.
- [x] Full unit + integration suite green, piped through `tee` to a `/tmp/p56-*.log` file (never re-run to re-grep).
- [x] `./gradlew build` succeeds for both `gms` and `foss` flavors; no NEW warnings introduced by this change (residual `w:` deprecation warnings are pre-existing baseline present on `main`).
- [ ] **Manual QA** (labelled; not a substitute for automated tests) on a device/emulator — REQUIRES a device/emulator; deferred to manual verification:
  - Revoke accessibility → both start buttons greyed, main warning shows; grant → buttons enabled, warning gone.
  - Revoke Camera → Camera category header shows amber triangle + note, rows greyed, tools absent from a client's `tools/list`; grant → restored after server restart.
  - Revoke mic (Camera granted) → `audio` param row greyed with note; `save_camera_video` still present but `audio` absent from schema.
  - Trigger an OAuth authorize → pending card appears on the Server screen; tap opens the approval screen; approve/deny → card disappears.

### Task 4.2 — Plan-compliance code review

- [x] Spawn `code-reviewer` in plan-compliance mode; fix ALL findings; re-run until clean. (2 rounds: round 1 raised 2 INFO — the mandatory detekt-driven divergences, recorded as accepted in Review Findings; round 2 = PASS, 0 findings.)

### Task 4.3 — Final ground-up double-check (LAST ITEM — do after 4.1 and 4.2)

- [x] Re-read, from scratch, each created/modified file and confirm it matches its task exactly and the final state is coherent:
  - `data/model/OptionalToolPermission.kt` (new)
  - `services/mcp/McpServerService.kt`
  - `ui/theme/Color.kt`
  - `ui/components/ConnectionInfoCard.kt` (migrated to the shared `WarningAmber` token)
  - `ui/screens/settings/McpToolsSettingsScreen.kt`
  - `ui/screens/SettingsScreen.kt`
  - `ui/viewmodels/MainViewModel.kt`
  - `ui/components/ServerStatusCard.kt`
  - `ui/screens/ServerScreen.kt`
  - `res/values/strings.xml`
  - test files: `OptionalToolPermissionTest.kt`, `PermissionGatedToolsIntegrationTest.kt`, `MainViewModelTest.kt`, `ServerStatusCardTest.kt`
- [x] Confirm the five agreed decisions hold: start-time computation only; only Camera/Location/Notifications gate tools + mic gates the `audio` param; File Ops/Sharing NOT gated; both start buttons disabled without accessibility & main warning accessibility-only; OAuth card only when pending>0.
- [x] Confirm NO registrar (`register*Tools`) file was modified and NO stored `ToolPermissionsConfig` write path was touched (stored flags never mutated by permission state).
- [x] Confirm NO file outside this plan's scope was altered.
- [x] Confirm the tool/param name strings in `OptionalToolPermissions` still exactly match the handler `TOOL_NAME`/param constants and the `McpToolsSettingsScreen` catalog.
- [x] Confirm all quality gates (4.1) are green and the code review (4.2) is clean.

### Definition of Done (whole plan)
- [x] Every task above checked; all acceptance criteria met; all quality gates green; code review clean; final ground-up double-check (Task 4.3) complete. (Manual QA on a device is the one deferred item — it requires a physical device/emulator.)

---

## Review Findings (accepted, mandatory divergences from the plan's literal code)

These two divergences were introduced DURING implementation to satisfy the quality gates (detekt) and are **behavior-preserving**. Reverting them to the plan's literal code would REINTRODUCE detekt failures, so they MUST be preserved.

- **F56-001 — Reverse-index maps in `OptionalToolPermission.kt`** (Task 1.1). The plan's literal `permissionForTool`/`permissionForParam` used inline `entries.firstOrNull { ... }`. ktlint (no `max_line_length` set) collapses those expression bodies onto single lines >120 cols, which detekt `MaxLineLength` (120) rejects. Fixed by adding two private `TOOL_TO_PERMISSION` / `PARAM_TO_PERMISSION` reverse maps (computed once) that the functions delegate to. Behavior-identical, O(1) lookup. **Accepted.**
- **F56-002 — Extracted composables in `McpToolsSettingsScreen.kt`** (Task 2.3). The plan's inline `item {}`/`items {}` gating produced a composable with cyclomatic complexity 20 (> detekt limit 14). Fixed by extracting `ToolCategoryHeader`, `ToolRow`, `ToolParamRow` composables and a top-level `isPermissionGranted` helper; `isGranted` is a `val` lambda; `isEnabled` renamed `controlsEnabled`; the header `clickable` wraps the `Column` (note also tappable). Recomposition scoping preserved (permission state read only inside the slot-invoked composables); stored flags never modified. Behavior-preserving. **Accepted.**

Also fixed during gates (behavior-preserving, in-scope): the `pendingApprovalCount reflects list size` test subscribes-then-awaits (deterministic under `StandardTestDispatcher`); the transient `.editorconfig` `max_line_length` experiment was reverted and the two `gms` Geofence files it briefly reformatted were restored to `main`.
