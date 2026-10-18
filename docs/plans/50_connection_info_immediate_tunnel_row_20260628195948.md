<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 50 — Immediate, aligned tunnel status row in the Connection Info card

## Context (agreed decisions — do not diverge)

The "Connection Info" card ([ConnectionInfoCard.kt](../../app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt)) currently receives `tunnelUrl: String?` and renders the Public URL row only when that URL is non-null, i.e. only after the tunnel reaches `TunnelStatus.Connected`. While connecting nothing shows; on failure nothing shows. This must change to the behavior agreed with the user:

- The **Public URL** row MUST appear **immediately** when the MCP server is started (`ServerStatus.Starting` OR `ServerStatus.Running`) **and** remote access is enabled (`ServerConfig.tunnelEnabled == true`).
- While the tunnel address is not yet available (`TunnelStatus.Disconnected` or `TunnelStatus.Connecting`), the row's value MUST show a **small spinning loader**.
- On `TunnelStatus.Connected`, the row's value MUST show the public address (`<url>/mcp`), as today.
- On `TunnelStatus.Error`, the row's value MUST show **red** error text reusing `R.string.remote_access_status_error` ("Error: %1$s") with the error message.
- When remote access is **off**, or the server is **not** Starting/Running, the row MUST stay **hidden**.
- **All rows** (IP, Port, URL, Public URL, Token) MUST use an **aligned two-column layout**: a label column whose width **auto-fits the widest currently-visible label**, and a value column whose left edge is the same for every row. The connecting spinner MUST sit at that value-column left edge (left-aligned with the other values), NOT pushed to the card's right edge.
- The Copy-all / Share connection string MUST include the tunnel line **only** when `TunnelStatus.Connected` (unchanged format: `\nTunnel: <url>/mcp`).
- All row labels MUST render **without** a trailing colon (the current `"<label>: "` colon is removed); the auto-fit label column plus the inter-column spacing provide the separation.

No new string resources are required. No SDK/dependency changes. JVM-only unit tests (no Compose runtime, no new test infra) following the existing `ConnectionInfoCardTest` pattern.

---

## User Story 1 — Render the tunnel row immediately with spinner / address / error and aligned columns

**Why:** The visibility of the tunnel address must be driven by combined server + tunnel state (not just `Connected`), so the user gets immediate feedback (spinner) the moment the server starts and a clear red error on failure. Column alignment is a usability requirement for the whole card.

**Acceptance criteria:**
- [x] Public URL row is hidden when `tunnelEnabled == false`.
- [x] Public URL row is hidden when server status is not `Starting` and not `Running`, regardless of tunnel state.
- [x] Public URL row appears with a spinner when `tunnelEnabled == true`, server is `Starting`/`Running`, and tunnel status is `Disconnected` or `Connecting`.
- [x] Public URL row shows `<url>/mcp` when tunnel status is `Connected`.
- [x] Public URL row shows red `Error: <message>` when tunnel status is `Error`.
- [x] Every visible row's value starts at the same horizontal position; the label column auto-fits the widest visible label.
- [x] The connecting spinner is left-aligned at the value column, not at the card's right edge.
- [x] Copy-all / Share string includes the tunnel line only when `Connected`, format `\nTunnel: <url>/mcp`.
- [x] `./gradlew build`, `make lint`, and the unit test suite pass with no warnings or errors. (Only the env-gated `NgrokTunnelIntegrationTest`, which FAILs by design without `NGROK_AUTHTOKEN`, fails — explicitly out of scope and ignored per user instruction; the new/affected `ConnectionInfoCardTest` and all 1841 other tests pass; ktlint + detekt clean.)

### Task 1.1 — Add the tunnel-row presentation model and pure mapping function

**Action 1.1.1** — modify [ConnectionInfoCard.kt](../../app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt): add an `internal` sealed model and a pure `internal` function (top-level, same file, placed above `ConnectionInfoCard`). These contain the entire visibility/state decision so it is unit-testable without a Compose runtime.

```kotlin
/** Visual state of the Public URL row in [ConnectionInfoCard]. */
internal sealed interface TunnelRowState {
    /** Row hidden: remote access off, or server not Starting/Running. */
    data object Hidden : TunnelRowState

    /** Server started and remote access enabled, address not yet available — show a spinner. */
    data object Loading : TunnelRowState

    /** Tunnel connected — show the public address. */
    data class Connected(val url: String) : TunnelRowState

    /** Tunnel failed — show the error message in red. */
    data class Failed(val message: String) : TunnelRowState
}

/**
 * Computes the Public URL row state from the combined server + tunnel state.
 *
 * The row becomes visible as soon as the server is Starting or Running and
 * remote access is enabled, showing [TunnelRowState.Loading] (a spinner) until
 * the tunnel reports [TunnelStatus.Connected] or [TunnelStatus.Error].
 */
internal fun tunnelRowState(
    tunnelEnabled: Boolean,
    serverStatus: ServerStatus,
    tunnelStatus: TunnelStatus,
): TunnelRowState {
    val serverActive =
        serverStatus is ServerStatus.Running || serverStatus is ServerStatus.Starting
    if (!tunnelEnabled || !serverActive) return TunnelRowState.Hidden
    return when (tunnelStatus) {
        is TunnelStatus.Connected -> TunnelRowState.Connected(tunnelStatus.url)
        is TunnelStatus.Error -> TunnelRowState.Failed(tunnelStatus.message)
        TunnelStatus.Connecting, TunnelStatus.Disconnected -> TunnelRowState.Loading
    }
}
```

**Action 1.1.2** — modify [ConnectionInfoCard.kt](../../app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt): add the required imports.

```kotlin
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
```

**Action 1.1.3** — modify [ConnectionInfoCard.kt](../../app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt): extract the connection-string builder into a pure `internal` top-level function (placed next to `tunnelRowState`) so the exact production string — including the empty-token conditional — is unit-testable without a Compose runtime and is the single source of truth shared by the composable and the test.

```kotlin
/**
 * Builds the Copy-all / Share connection string. The tunnel line is included
 * only when [tunnelUrl] is non-null (i.e. the tunnel is connected); the bearer
 * token line is included only when [bearerToken] is non-empty.
 */
internal fun buildConnectionString(
    serverUrl: String,
    tunnelUrl: String?,
    bearerToken: String,
): String =
    buildString {
        append("URL: $serverUrl")
        tunnelUrl?.let { append("\nTunnel: $it/mcp") }
        if (bearerToken.isNotEmpty()) {
            append("\nBearer Token: $bearerToken")
        }
    }
```

**Definition of Done:**
- [x] `TunnelRowState` and `tunnelRowState(...)` exist exactly as specified; the `when` is exhaustive over `TunnelStatus`.
- [x] `buildConnectionString(...)` exists exactly as specified, with both the tunnel-line and bearer-token conditionals.
- [x] Imports added; no unused imports introduced.

### Task 1.2 — Refactor the card to auto-fit aligned columns and render the tunnel row by state

**Action 1.2.1** — modify the `ConnectionInfoCard` signature: replace `tunnelUrl: String? = null` with the three inputs the decision needs. Keep parameter ordering valid (non-default before default).

```kotlin
@Composable
fun ConnectionInfoCard(
    bindingAddress: BindingAddress,
    ipAddress: String,
    port: Int,
    httpsEnabled: Boolean,
    bearerToken: String,
    onCopyAll: (String) -> Unit,
    tunnelEnabled: Boolean = false,
    serverStatus: ServerStatus = ServerStatus.Stopped,
    tunnelStatus: TunnelStatus = TunnelStatus.Disconnected,
    onShare: (String) -> Unit = {},
    modifier: Modifier = Modifier,
)
```

**Action 1.2.2** — inside the card, compute the row state and the auto-fit label column width from the currently-visible labels (IP, Port, URL always; Public URL only when the row is not `Hidden`; Token only when `bearerToken` is non-empty). Measure with `rememberTextMeasurer` in the row label text style (`bodyMedium`).

```kotlin
val rowState = tunnelRowState(tunnelEnabled, serverStatus, tunnelStatus)

val labelStyle = MaterialTheme.typography.bodyMedium
val measurer = rememberTextMeasurer()
val density = LocalDensity.current

val ipLabel = stringResource(R.string.connection_info_ip)
val portLabel = stringResource(R.string.connection_info_port)
val urlLabel = stringResource(R.string.connection_info_url)
val publicUrlLabel = stringResource(R.string.remote_access_public_url_label)
val tokenLabel = stringResource(R.string.connection_info_token)

val visibleLabels =
    buildList {
        add(ipLabel)
        add(portLabel)
        add(urlLabel)
        if (rowState != TunnelRowState.Hidden) add(publicUrlLabel)
        if (bearerToken.isNotEmpty()) add(tokenLabel)
    }
val labelColumnWidth: Dp =
    remember(visibleLabels, labelStyle, density) {
        with(density) {
            visibleLabels.maxOf { measurer.measure(it, labelStyle).size.width }.toDp()
        }
    }
```

**Action 1.2.3** — replace the private `ConnectionInfoRow(label, value)` helper with the aligned-column version (auto-fit label-column width + `RowScope` value slot). The label renders with **no** trailing colon; vertical centering keeps the 48dp `IconButton` aligned with single-line rows.

```kotlin
@Composable
private fun ConnectionInfoRow(
    label: String,
    labelWidth: Dp,
    modifier: Modifier = Modifier,
    value: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(labelWidth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        value()
    }
}
```

**Action 1.2.4** — replace the three simple rows, the conditional tunnel row, and the Token row so they all use the shared `ConnectionInfoRow(label, labelWidth) { value }` helper defined in Action 1.2.3. The value slot is a `RowScope` lambda so the Token row can use `Modifier.weight(1f)` and the tunnel row can host a spinner / text / error.

Simple rows:

```kotlin
ConnectionInfoRow(label = ipLabel, labelWidth = labelColumnWidth) {
    Text(text = displayAddress, style = MaterialTheme.typography.bodyMedium)
}
ConnectionInfoRow(label = portLabel, labelWidth = labelColumnWidth) {
    Text(text = port.toString(), style = MaterialTheme.typography.bodyMedium)
}
ConnectionInfoRow(label = urlLabel, labelWidth = labelColumnWidth) {
    Text(text = serverUrl, style = MaterialTheme.typography.bodyMedium)
}
```

Tunnel row (hidden when `Hidden`; spinner / address / red error otherwise):

```kotlin
if (rowState != TunnelRowState.Hidden) {
    ConnectionInfoRow(label = publicUrlLabel, labelWidth = labelColumnWidth) {
        when (rowState) {
            TunnelRowState.Loading ->
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )

            is TunnelRowState.Connected ->
                Text(
                    text = "${rowState.url}/mcp",
                    style = MaterialTheme.typography.bodyMedium,
                )

            is TunnelRowState.Failed ->
                Text(
                    text = stringResource(R.string.remote_access_status_error, rowState.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

            TunnelRowState.Hidden -> Unit
        }
    }
}
```

Token row (aligned to the same label column; value = masked/real token taking remaining width + visibility toggle):

```kotlin
if (bearerToken.isNotEmpty()) {
    ConnectionInfoRow(label = tokenLabel, labelWidth = labelColumnWidth) {
        Text(
            text = displayToken,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { showToken = !showToken }) {
            Icon(
                imageVector =
                    if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription =
                    if (showToken) {
                        stringResource(R.string.config_token_hide)
                    } else {
                        stringResource(R.string.config_token_show)
                    },
            )
        }
    }
}
```

**Action 1.2.5** — update the Copy-all / Share connection string to call the extracted `buildConnectionString` (Action 1.1.3), deriving the tunnel URL from `rowState` so the tunnel line is included only when `Connected`:

```kotlin
val connectionString =
    buildConnectionString(
        serverUrl = serverUrl,
        tunnelUrl = (rowState as? TunnelRowState.Connected)?.url,
        bearerToken = bearerToken,
    )
```

**Action 1.2.6** — update the `@Preview` (`ConnectionInfoCardPreview`) to the new parameters so it renders a connected tunnel row:

```kotlin
ConnectionInfoCard(
    bindingAddress = BindingAddress.LOCALHOST,
    ipAddress = "192.168.1.100",
    port = 8080,
    httpsEnabled = false,
    bearerToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    tunnelEnabled = true,
    serverStatus = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1"),
    tunnelStatus =
        TunnelStatus.Connected(
            url = "https://random-words.trycloudflare.com",
            providerType = TunnelProviderType.CLOUDFLARE,
        ),
    onCopyAll = {},
    onShare = {},
)
```

**Definition of Done:**
- [x] `tunnelUrl` parameter fully removed; card now takes `tunnelEnabled`, `serverStatus`, `tunnelStatus`.
- [x] All rows render through the aligned `ConnectionInfoRow(label, labelWidth) { value }` helper.
- [x] Label column width is computed by measuring the currently-visible labels; values left-align in one column.
- [x] Spinner uses `Modifier.size(16.dp)` + `strokeWidth = 2.dp` at the value-column left edge.
- [x] Error uses `R.string.remote_access_status_error` in `colorScheme.error`.
- [x] Connection string is produced by `buildConnectionString(...)` with `tunnelUrl` derived from `rowState` (non-null only for `Connected`).
- [x] `@Preview` compiles with the new parameters.

### Task 1.3 — Update the ServerScreen call site

**Action 1.3.1** — modify [ServerScreen.kt:128-147](../../app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt#L128): pass the new parameters and remove the `tunnelUrl = (tunnelStatus as? TunnelStatus.Connected)?.url` line.

```kotlin
ConnectionInfoCard(
    bindingAddress = serverConfig.bindingAddress,
    ipAddress = deviceIp,
    port = serverConfig.port,
    httpsEnabled = serverConfig.httpsEnabled,
    bearerToken = serverConfig.bearerToken,
    tunnelEnabled = serverConfig.tunnelEnabled,
    serverStatus = serverStatus,
    tunnelStatus = tunnelStatus,
    onCopyAll = { text ->
        clipboardManager.setText(AnnotatedString(text))
        Toast.makeText(context, copiedToClipboardMessage, Toast.LENGTH_SHORT).show()
    },
    onShare = { text ->
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        context.startActivity(Intent.createChooser(intent, null))
    },
)
```

**Action 1.3.2** — modify [ServerScreen.kt:44](../../app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt#L44): remove the now-unused `import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus` (the `TunnelStatus.Connected` cast is the only reference and it is being removed). The `serverStatus` and `tunnelStatus` values are already collected at lines 64 and 66 and need no new imports.

**Definition of Done:**
- [x] ServerScreen passes `tunnelEnabled`, `serverStatus`, `tunnelStatus`; no `tunnelUrl` argument remains.
- [x] Unused `TunnelStatus` import removed; no other reference to it remains in the file.

### Task 1.4 — Unit tests for the tunnel-row mapping

**File:** `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCardTest.kt`

**Setup:**
- `tunnelRowState`, `TunnelRowState`, and `buildConnectionString` are `internal` top-level declarations in the same package (`com.danielealbano.androidremotecontrolmcp.ui.components`) as this test, so they are callable directly with no import.
- Add imports for `com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus`, `TunnelStatus`, and `TunnelProviderType`. Use `ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")` and `TunnelStatus.Connected(url, TunnelProviderType.CLOUDFLARE)` where a populated state is needed.
- **Remove** the test's private `buildConnectionString(serverUrl, bearerToken, tunnelUrl)` helper (lines 19-28) — it is a re-implementation that diverged from production by always appending the bearer-token line. The four existing connection-string tests (`tunnelUrl includes mcp suffix in connection string`, `copyAll always uses real bearer token`, `connectionString format without tunnel`, `connectionString format with tunnel`) MUST be updated to call the production `buildConnectionString`. The production function has **no default** for `tunnelUrl`, so the two calls that previously omitted it — `copyAll always uses real bearer token` and `connectionString format without tunnel` — MUST be updated to pass `tunnelUrl = null` explicitly; the other two already pass a `tunnelUrl` value. All four use named arguments matching the production parameter names and their assertions remain valid (all use non-empty tokens).
- The private `buildServerUrl(scheme, displayAddress, port)` helper and its two tests (`serverUrl includes mcp suffix`, `serverUrl includes mcp suffix with https`) stay unchanged (out of scope — not flagged).

Add the cases below as new `@Test` functions:

| Test | Verifies |
|------|----------|
| `tunnelRowState hidden when remote access disabled` | `tunnelEnabled = false`, server Running, tunnel Connected → `Hidden` |
| `tunnelRowState hidden when server stopped` | enabled, `ServerStatus.Stopped`, tunnel Connecting → `Hidden` |
| `tunnelRowState hidden when server stopping` | enabled, `ServerStatus.Stopping`, tunnel Connected → `Hidden` |
| `tunnelRowState hidden when server error` | enabled, `ServerStatus.Error("x")`, tunnel Connected → `Hidden` |
| `tunnelRowState loading when starting and disconnected` | enabled, `ServerStatus.Starting`, `TunnelStatus.Disconnected` → `Loading` |
| `tunnelRowState loading when running and connecting` | enabled, Running, `TunnelStatus.Connecting` → `Loading` |
| `tunnelRowState connected exposes url when running` | enabled, Running, `TunnelStatus.Connected(url)` → `Connected(url)` (asserts the exact url) |
| `tunnelRowState failed exposes message when running` | enabled, Running, `TunnelStatus.Error("boom")` → `Failed("boom")` (asserts the exact message) |
| `connection string includes tunnel line only when connected` | Production `buildConnectionString` with `tunnelUrl` from `Connected.url` contains `\nTunnel: <url>/mcp`; with `tunnelUrl = null` (Loading/Failed/Hidden) it does not |
| `connection string omits bearer token when empty` | Production `buildConnectionString(serverUrl, tunnelUrl = null, bearerToken = "")` equals `"URL: <serverUrl>"` and contains no `Bearer Token` line — covers the empty-token conditional that previously diverged |

**Definition of Done:**
- [x] The test's private `buildConnectionString` re-implementation is removed; the four affected tests call the production `buildConnectionString` and pass.
- [x] New `tunnelRowState` and empty-token tests added and passing.
- [x] No test references the removed `tunnelUrl` composable parameter.

---

## User Story 2 — Ground-up verification of the entire implementation

**Why:** Final gate required by the project rules — re-validate every action against this plan from scratch and confirm all quality gates before the work is considered done.

**Acceptance criteria:**
- [x] Every action in User Story 1 is re-checked line-by-line against the implemented code.
- [x] All quality gates pass with zero warnings/errors (excluding the explicitly-ignored env-gated `NgrokTunnelIntegrationTest`).
- [x] `code-reviewer` (plan-compliance mode) reports clean.

### Task 2.1 — Re-check from the ground up and run all quality gates

**Action 2.1.1** — Re-read the final diff of [ConnectionInfoCard.kt](../../app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt), [ServerScreen.kt](../../app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt), and `ConnectionInfoCardTest.kt` and confirm each against Tasks 1.1–1.4: signature change, removed `tunnelUrl`, `tunnelRowState` mapping (all 4 outcomes), auto-fit label column over visible labels, spinner at value-column left edge with `size(16.dp)`/`strokeWidth = 2.dp`, red error via `remote_access_status_error`, connection string tunnel line only for `Connected`, removed unused import.

**Action 2.1.2** — Run the quality gates, capturing output to `/tmp/` per the global rule:
- `make lint 2>&1 | tee /tmp/p50-lint.log | tail -20`
- `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.ui.components.ConnectionInfoCardTest" 2>&1 | tee /tmp/p50-test-card.log | tail -20`
- `./gradlew build 2>&1 | tee /tmp/p50-build.log | tail -30`

**Action 2.1.3** — Fix any failure found by 2.1.1–2.1.2 at the root cause (no suppression), then re-run the affected gate from its captured-log workflow until clean.

**Action 2.1.4** — Spawn the `code-reviewer` subagent in plan-compliance mode against this plan. Fix ALL reported findings (CRITICAL, WARNING, INFO). Re-run `code-reviewer` until clean.

**Definition of Done:**
- [x] `make lint` passes with no warnings/errors.
- [x] `ConnectionInfoCardTest` passes (existing + new cases).
- [x] `./gradlew build` succeeds (compilation + all in-scope tests; only the env-gated `NgrokTunnelIntegrationTest` fails by design without `NGROK_AUTHTOKEN`, ignored per user instruction).
- [x] `code-reviewer` (plan-compliance mode) reports no outstanding findings.
- [x] Every checkbox in this plan is ticked.
