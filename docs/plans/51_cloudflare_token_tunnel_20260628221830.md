<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 51 — Cloudflare Token (Named) Tunnel Mode

## Context (verified facts — non-derivable from code)

Adds a second Cloudflare tunnel mode ("With Token") alongside the existing quick tunnel ("Free"), giving a **static** public hostname. All facts below were verified empirically against the bundled cloudflared **2026.2.0** with a real dashboard token; the implementation MUST NOT deviate from them.

- Free mode command (unchanged): `tunnel --url http://localhost:<port> --output json`.
- Token mode command (EXACT order, `--output json` BEFORE `run`, **NO `--url`** — passing `--url` breaks the control stream and the tunnel never connects): `tunnel --output json run --token <TOKEN>`.
- `--output json` makes cloudflared emit JSON log objects on stderr. Some lines are NOT JSON (e.g. a quic-go UDP-buffer warning) and MUST be skipped.
- Token mode opens 4 HA connections, each logging `{"message":"Registered tunnel connection", ...}`. These MUST NOT flip status to Connected.
- The static hostname(s) arrive in a single JSON log object whose `message` is `"Updated to new configuration"`. Its `config` field is an ESCAPED JSON string, e.g. `{"ingress":[{"hostname":"pixel8.mcp.android-remote-control.phonr.dev","service":"http://localhost:8080"},{"service":"http_status:404"}],"warp-routing":{"enabled":false}}`. Only `ingress[]` entries that have a `hostname` are public hostnames; the catch-all (`http_status:404`) has none.
- A token tunnel requires the user to own a domain on Cloudflare and configure a **Public Hostname** (not Private) routing to `http://localhost:<port>`; cloudflared receives that route from the edge (no REST API call, no extra credential).

## Agreed behavioral decisions (binding)

- Modes labeled **Free** and **With Token**; mode applies only to the Cloudflare provider.
- Token stored masked in DataStore (like the ngrok authtoken).
- Token-mode status flow: stay **Connecting** on `Registered tunnel connection`; on `Updated to new configuration`, parse all hostname-bearing ingress entries and **validate each entry's `service`**. Expected service: scheme `http`, host `localhost` OR `127.0.0.1`, port `== <port>`, path empty or `/`.
  - All hostname entries valid → **Connected** with the list of `https://<hostname>`.
  - Any hostname entry invalid, OR zero hostname entries → **Error** + **stop the tunnel** (kill process).
  - No valid config within **10 s from process start** → **Error** + **stop the tunnel**.
  - **Re-validate on every later `Updated to new configuration`** push; stop if it becomes misconfigured.
- Empty token in token mode → Error (mirrors ngrok's "authtoken is required").
- `TunnelStatus.Connected.url: String` becomes `urls: List<String>` (Free/ngrok produce a single-element list).
- The **Tunnel Settings screen shows NO hostname/URL** (no field; status indicator shows state only). The **home Connection Info card lists ALL hostnames** and includes all tunnel lines in the copy/share string.
- Request-served base URLs (OAuth metadata, share links) are request-derived and unaffected; the tunnel-status fallback in `currentBaseUrl` uses `urls.firstOrNull()`.
- When the server's **HTTPS toggle is ON**, the **entire** remote-access section (Free, Token, ngrok) is disabled, a warning banner is shown at the top of the Tunnel Settings screen, and `McpServerService` MUST NOT start a tunnel (stop any active one).

---

## User Story 1 — Persist Cloudflare tunnel mode and token

**Why:** Token mode needs two new persisted settings; without them the provider and UI cannot branch. Storage MUST go through `SettingsRepository` (project rule).

**Acceptance criteria:**
- [x] `CloudflareTunnelMode` enum exists with `FREE`, `TOKEN`.
- [x] `ServerConfig` exposes `cloudflareTunnelMode` (default `FREE`) and `cloudflareTunnelToken` (default `""`).
- [x] `SettingsRepository` persists and restores both, with correct defaults and enum fallback.

### Task 1.1 — Add `CloudflareTunnelMode` enum

- [x] **Action 1.1.1** — create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/CloudflareTunnelMode.kt`:
```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/** Cloudflare tunnel operating mode. */
enum class CloudflareTunnelMode {
    /** Quick Tunnel — no account, random *.trycloudflare.com URL per session. */
    FREE,

    /** Named tunnel run with a dashboard token — static hostname(s) from remote config. */
    TOKEN,
}
```

**DoD:**
- [x] Enum compiles; same package as `TunnelProviderType`.

### Task 1.2 — Extend `ServerConfig`

- [x] **Action 1.2.1** — modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt`: add fields after `ngrokDomain` and matching KDoc `@property` lines:
```kotlin
    val cloudflareTunnelMode: CloudflareTunnelMode = CloudflareTunnelMode.FREE,
    val cloudflareTunnelToken: String = "",
```

**DoD:**
- [x] Field defaults are `FREE` / `""`; KDoc updated; no import needed (same package).

### Task 1.3 — Extend `SettingsRepository` interface

- [x] **Action 1.3.1** — modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`: add import for `CloudflareTunnelMode` and methods near the other tunnel methods:
```kotlin
    /** Updates the Cloudflare tunnel mode (Free quick tunnel vs token-based named tunnel). */
    suspend fun updateCloudflareTunnelMode(mode: CloudflareTunnelMode)

    /** Updates the Cloudflare tunnel token (required when using token mode). */
    suspend fun updateCloudflareTunnelToken(token: String)
```

**DoD:**
- [x] Interface compiles.

### Task 1.4 — Implement persistence in `SettingsRepositoryImpl`

- [x] **Action 1.4.1** — modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`:
  - Add import for `CloudflareTunnelMode`.
  - Add keys near the other tunnel keys:
```kotlin
            private val CLOUDFLARE_TUNNEL_MODE_KEY = stringPreferencesKey("cloudflare_tunnel_mode")
            private val CLOUDFLARE_TUNNEL_TOKEN_KEY = stringPreferencesKey("cloudflare_tunnel_token")
```
  - Add update methods near `updateNgrokDomain`:
```kotlin
        override suspend fun updateCloudflareTunnelMode(mode: CloudflareTunnelMode) {
            dataStore.edit { prefs -> prefs[CLOUDFLARE_TUNNEL_MODE_KEY] = mode.name }
        }

        override suspend fun updateCloudflareTunnelToken(token: String) {
            dataStore.edit { prefs -> prefs[CLOUDFLARE_TUNNEL_TOKEN_KEY] = token }
        }
```
  - In the `serverConfig` mapping (alongside `tunnelProvider`/`ngrokAuthtoken`), resolve and set:
```kotlin
                val cloudflareTunnelModeName =
                    prefs[CLOUDFLARE_TUNNEL_MODE_KEY] ?: CloudflareTunnelMode.FREE.name
                // ...
                cloudflareTunnelMode =
                    CloudflareTunnelMode.entries.firstOrNull { it.name == cloudflareTunnelModeName }
                        ?: CloudflareTunnelMode.FREE,
                cloudflareTunnelToken = prefs[CLOUDFLARE_TUNNEL_TOKEN_KEY] ?: "",
```

**DoD:**
- [x] Both settings round-trip; unknown stored mode falls back to `FREE`.

### Task 1.5 — Tests for new settings

- [x] **Action 1.5.1** — modify `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt`.

**Setup:** existing repository test harness (in-memory/temp DataStore).

| Test | Verifies |
|------|----------|
| `cloudflareTunnelMode defaults to FREE` | Default when unset |
| `cloudflareTunnelToken defaults to empty` | Default when unset |
| `updateCloudflareTunnelMode persists` | TOKEN round-trips |
| `updateCloudflareTunnelToken persists` | Token value round-trips |
| `unknown stored cloudflare mode falls back to FREE` | Enum fallback. **Setup:** write a bogus string into `cloudflare_tunnel_mode` then read |

**DoD:**
- [x] Tests added (run in US6).

---

## User Story 2 — Multi-hostname tunnel status model

**Why:** A token tunnel can expose multiple public hostnames. `TunnelStatus.Connected` must carry a list, the home card must show all, and the settings screen must stop showing the URL.

**Acceptance criteria:**
- [x] `TunnelStatus.Connected` holds `urls: List<String>`.
- [x] All producers/consumers compile and behave correctly with a list.
- [x] Tunnel Settings status indicator shows state only (no URL); Connection Info card lists all URLs and includes all in copy/share.

### Task 2.1 — Change the model

- [x] **Action 2.1.1** — modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/TunnelStatus.kt`:
```kotlin
    data class Connected(
        val urls: List<String>,
        val providerType: TunnelProviderType,
    ) : TunnelStatus()
```
Update the KDoc (`@property urls The public HTTPS URL(s) ...`).

**DoD:**
- [x] Model compiles.

### Task 2.2 — Update producers

- [x] **Action 2.2.1** — modify `CloudflareTunnelProvider.kt` free-path success: `TunnelStatus.Connected(urls = listOf(url), providerType = TunnelProviderType.CLOUDFLARE)`.
- [x] **Action 2.2.2** — modify `NgrokTunnelProvider.kt` success: `TunnelStatus.Connected(urls = listOf(url), providerType = TunnelProviderType.NGROK)`.

**DoD:**
- [x] Both providers compile against the list model.

### Task 2.3 — Update `McpServerService` consumers

- [x] **Action 2.3.1** — modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt` (MINIMAL diff — do NOT restructure surrounding lines):
  - In `currentBaseUrl` (the existing `if (tunnel is TunnelStatus.Connected) { tunnel.url } else { … }` block at ~lines 350-362), change ONLY the connected branch value `tunnel.url` → `tunnel.urls.firstOrNull()`, and make the `if` test handle the now-nullable value. Concretely, replace the `val tunnel = …; if (tunnel is TunnelStatus.Connected) { tunnel.url } else { … }` with `val tunnelUrl = (tunnel as? TunnelStatus.Connected)?.urls?.firstOrNull(); if (tunnelUrl != null) { tunnelUrl } else { …existing else body unchanged… }`. Keep the existing `else` body (scheme/host/port) byte-for-byte.
  - Tunnel logging: replace `${status.url}` with `${status.urls.joinToString()}` (both the `Log.i` at ~line 293 and the `ServerLogEntry` message at ~line 298).

**DoD:**
- [x] Service compiles; fallback uses first URL.

### Task 2.4 — Update Connection Info card to list all hostnames

- [x] **Action 2.4.1** — modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt`:
  - `TunnelRowContent.Connected`:
```kotlin
    data class Connected(
        val urls: List<String>,
    ) : TunnelRowContent
```
  - `tunnelRowContent`: `is TunnelStatus.Connected -> TunnelRowContent.Connected(tunnelStatus.urls)`.
  - `buildConnectionString`: replace the single `tunnelUrl: String?` param with `tunnelUrls: List<String>` and append one `\nTunnel: <url>/mcp` line per URL (skip when empty):
```kotlin
internal fun buildConnectionString(
    serverUrl: String,
    tunnelUrls: List<String>,
    bearerToken: String,
): String =
    buildString {
        append("URL: $serverUrl")
        tunnelUrls.forEach { append("\nTunnel: $it/mcp") }
        if (bearerToken.isNotEmpty()) {
            append("\nBearer Token: $bearerToken")
        }
    }
```
  - Update the call site to pass `(rowContent as? TunnelRowContent.Connected)?.urls ?: emptyList()`.
  - `TunnelRowValue` `Connected` branch: render a `Column` with one `Text("$it/mcp")` per URL.

**DoD:**
- [x] Card lists every hostname; copy/share includes one tunnel line per hostname.

### Task 2.5 — Remove URL from the Tunnel Settings status indicator

- [x] **Action 2.5.1** — modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/TunnelSettingsScreen.kt` `TunnelStatusIndicator` `Connected` branch: render only the "Connected" status `Text`; delete the `Spacer` + `status.url` `Text`.

**DoD:**
- [x] Settings screen no longer renders any tunnel URL/hostname.

### Task 2.6 — Update model/consumer tests

- [x] **Action 2.6.1** — update `.url` → `.urls` (list form: `url = "x"` → `urls = listOf("x")`, and adjust assertions) in EVERY test that constructs or reads `TunnelStatus.Connected`: `data/model/TunnelStatusTest.kt`, `services/tunnel/TunnelManagerTest.kt`, `services/tunnel/NgrokTunnelProviderTest.kt`, `integration/NgrokTunnelIntegrationTest.kt`, `integration/CloudflareTunnelIntegrationTest.kt`, `ui/components/ConnectionInfoCardTest.kt`, and `ui/viewmodels/MainViewModelTest.kt` (constructs `TunnelStatus.Connected(url = "https://test.trycloudflare.com", …)` and asserts on it — both construction and expected value must migrate to `urls = listOf(…)`).

| Test (new/updated) | Verifies |
|------|----------|
| `Connected holds multiple urls` (TunnelStatusTest) | List preserved in order |
| `connected helper uses urls list` (ConnectionInfoCardTest) | Helper builds `Connected(listOf(...))` |
| `buildConnectionString includes one line per tunnel url` | N hostnames → N `Tunnel:` lines; empty list → none |
| `tunnelRowContent maps Connected to urls` | Mapping passes the full list |
| Integration assertions | Quick tunnel asserts `urls.single()` starts with `https://` and contains `.trycloudflare.com` |

**DoD:**
- [x] All updated tests reference the list model (run in US6).

---

## User Story 3 — Cloudflare token-mode provider

**Why:** Core feature. The provider must branch on mode, run the token command, parse cloudflared's JSON log stream, validate the routed service, and enforce the safety rules.

**Acceptance criteria:**
- [x] Free mode behavior is unchanged.
- [x] Token mode runs `tunnel --output json run --token <TOKEN>` (no `--url`); empty token → Error.
- [x] Non-JSON log lines are skipped without error.
- [x] On `Updated to new configuration`: all hostname services validated; all-valid → Connected(list); any-invalid or zero hostnames → Error + stop.
- [x] No valid config within 10 s of process start → Error + stop.
- [x] Re-validation on every later config push.

### Task 3.1 — Add internal parsing/validation helpers

- [x] **Action 3.1.1** — modify `CloudflareTunnelProvider.kt`: add imports (`kotlinx.serialization.json.Json`, `jsonObject`, `jsonArray`, `jsonPrimitive`, `contentOrNull`, `java.net.URI`) and an internal route type + helpers (all `internal` for unit testing):
```kotlin
        internal data class IngressRoute(val hostname: String, val service: String)

        // companion:
        internal val LOG_JSON = Json { ignoreUnknownKeys = true }
        internal const val MSG_UPDATED_CONFIG = "Updated to new configuration"
        internal const val TOKEN_CONFIG_TIMEOUT_MS = 10_000L

        /** Returns the top-level `message` field of a cloudflared JSON log line, or null for non-JSON. */
        internal fun logMessageOf(line: String): String? =
            runCatching { LOG_JSON.parseToJsonElement(line).jsonObject["message"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()

        /** Extracts the escaped `config` string of an `Updated to new configuration` line, or null. */
        internal fun configPayloadOf(line: String): String? =
            runCatching { LOG_JSON.parseToJsonElement(line).jsonObject["config"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()

        /** Parses the nested config JSON into the ingress entries that declare a hostname. */
        internal fun ingressRoutesOf(configJson: String): List<IngressRoute> =
            runCatching {
                LOG_JSON.parseToJsonElement(configJson).jsonObject["ingress"]?.jsonArray.orEmpty()
                    .mapNotNull { el ->
                        val o = el.jsonObject
                        val host = o["hostname"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        IngressRoute(host, o["service"]?.jsonPrimitive?.contentOrNull ?: "")
                    }
            }.getOrDefault(emptyList())

        /** True when the routed service points at our MCP server: http://(localhost|127.0.0.1):<port>. */
        internal fun isServiceValid(service: String, expectedPort: Int): Boolean {
            val uri = runCatching { URI(service) }.getOrNull() ?: return false
            if (uri.scheme != "http") return false
            val host = uri.host ?: return false
            if (host != "localhost" && host != "127.0.0.1") return false
            if (uri.port != expectedPort) return false
            val path = uri.path
            return path.isNullOrEmpty() || path == "/"
        }
```

**DoD:**
- [x] Helpers are pure and `internal`; no Android dependencies (JVM-testable).

### Task 3.2 — Branch `start()` on mode and add token teardown

- [x] **Action 3.2.1** — modify `CloudflareTunnelProvider.kt`:
  - Add state: `private var timeoutJob: Job? = null` and `@Volatile private var terminating = false`.
  - In `start()`, after resolving the binary and setting `Connecting`, branch on `config.cloudflareTunnelMode`:
    - `FREE` → build the existing free `ProcessBuilder` (`"tunnel","--url","http://localhost:$localPort","--output","json"`), `startFreeStderrReader(proc)`, `startProcessMonitor(proc)`.
    - `TOKEN` → if `config.cloudflareTunnelToken.isEmpty()` → `_status.value = Error("Cloudflare tunnel token is required")`; `return`. Otherwise build `ProcessBuilder(binaryPath, "tunnel", "--output", "json", "run", "--token", config.cloudflareTunnelToken)` (EXACT order; NO `--url`), `startTokenStderrReader(proc, localPort)`, `startProcessMonitor(proc)`, `startTokenConfigTimeout()`.
  - Reset `terminating = false` at the start of `start()`.
- [x] **Action 3.2.2** — rename the existing stderr reader to `startFreeStderrReader` (keep the existing `TUNNEL_URL_REGEX` logic, now producing `urls = listOf(url)`). NOTE: free mode INTENTIONALLY keeps regex-based URL extraction (the quick-tunnel URL is embedded in a JSON `message` box and the regex matches it); do NOT switch free mode to the new JSON helpers.
- [x] **Action 3.2.3** — add `startTokenStderrReader(proc, localPort)`: same read loop as free, but per line:
```kotlin
    val message = logMessageOf(line) ?: continue  // skip non-JSON
    if (message == MSG_UPDATED_CONFIG) {
        val payload = configPayloadOf(line) ?: continue
        handleTokenConfig(payload, localPort)
    }
    // "Registered tunnel connection" and others: ignore — stay Connecting
```
- [x] **Action 3.2.4** — add `handleTokenConfig(configJson, localPort)` (runs on EVERY push → re-validation):
```kotlin
    val routes = ingressRoutesOf(configJson)
    if (routes.isEmpty()) {
        terminateWithError("No public hostname configured for this tunnel")
        return
    }
    val invalid = routes.firstOrNull { !isServiceValid(it.service, localPort) }
    if (invalid != null) {
        terminateWithError("Tunnel route misconfigured: ${invalid.hostname} -> ${invalid.service}")
        return
    }
    timeoutJob?.cancel()
    timeoutJob = null
    _status.value = TunnelStatus.Connected(
        urls = routes.map { "https://${it.hostname}" },
        providerType = TunnelProviderType.CLOUDFLARE,
    )
```
- [x] **Action 3.2.5** — add a test-overridable timeout field and `startTokenConfigTimeout()`. Do NOT add a constructor parameter (the class is `@Inject constructor` and Hilt cannot provide a `Long`); instead add an `internal var configTimeoutMs: Long = TOKEN_CONFIG_TIMEOUT_MS` property that tests set directly (e.g. `provider.configTimeoutMs = 100`):
```kotlin
    private fun startTokenConfigTimeout() {
        timeoutJob = scope.launch {
            delay(configTimeoutMs)
            if (_status.value is TunnelStatus.Connecting) {
                terminateWithError("No public hostname configured for this tunnel")
            }
        }
    }
```
- [x] **Action 3.2.6** — add a single mutex-guarded teardown used by BOTH error-termination and `stop()`, to avoid races on `process`/job fields and deadlock. Synchronization contract: all of `process`, `stderrReaderJob`, `processMonitorJob`, `timeoutJob` are mutated ONLY inside `mutex.withLock`; `terminating` is `@Volatile`. `terminateWithError` is invoked from the reader coroutine but MUST NOT take the mutex inline (the reader holds no lock, but teardown cancels the reader job) — it sets state then LAUNCHES the guarded teardown on `scope`:
```kotlin
    private fun terminateWithError(message: String) {
        terminating = true
        _status.value = TunnelStatus.Error(message)
        scope.launch { shutdownProcess(setDisconnected = false) }
    }

    private suspend fun shutdownProcess(setDisconnected: Boolean) {
        mutex.withLock {
            timeoutJob?.cancel(); timeoutJob = null
            processMonitorJob?.cancel(); processMonitorJob = null
            stderrReaderJob?.cancel(); stderrReaderJob = null
            process?.let { proc ->
                proc.destroy()
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    proc.waitFor()
                } ?: proc.destroyForcibly()
            }
            process = null
            if (setDisconnected) {
                _status.value = TunnelStatus.Disconnected
            }
        }
    }
```
- [x] **Action 3.2.7** — guard `startProcessMonitor` so it never overwrites an intentional Error: emit "exited unexpectedly" only when `!terminating && (_status.value is TunnelStatus.Connecting || _status.value is TunnelStatus.Connected)`. (Because `terminating` is set before the process is destroyed, the monitor observing the exit will skip the overwrite.) RETAIN the monitor's existing `mutex.withLock { process = null }` INSIDE this guarded branch (it runs only on a genuine unexpected exit, which is mutually exclusive with `terminating`, so it never conflicts with `shutdownProcess`); do NOT add a second teardown path in the monitor.
- [x] **Action 3.2.8** — refactor `stop()` to delegate to the shared teardown: `mutex` is taken inside `shutdownProcess`, so `stop()` calls `shutdownProcess(setDisconnected = true)` then sets `terminating = false`. `start()` MUST set `terminating = false` at its very beginning so a later restart is clean. (Deadlock-safety: teardown runs on `scope`, not on the reader coroutine; cancelling `stderrReaderJob` from teardown is safe because the reader holds no lock.)

**DoD:**
- [x] Token command has the exact argument order and no `--url`.
- [x] Mismatch / no-config / empty-token paths set Error and kill the process.
- [x] Re-validation runs on every config push.
- [x] No coroutine leaks (`timeoutJob` cancelled on connect, error, and stop).
- [x] `process` and all job fields are mutated only under `mutex`; `terminating` is `@Volatile`; teardown runs on `scope` (never inline in the reader) so `stop()` and `terminateWithError` cannot deadlock.

### Task 3.3 — Provider unit tests (no network, no credentials)

- [x] **Action 3.3.1** — modify `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/CloudflareTunnelProviderTest.kt`.

**Setup — real captured fixtures (constants in the test):**
- `CONFIG_LINE_VALID` = the real `Updated to new configuration` line (single hostname `service":"http://localhost:8080"`).
- `CONFIG_LINE_MISMATCH` = same shape but `service":"http://localhost:9999"`.
- `CONFIG_LINE_MULTI` = two hostname entries (both `http://localhost:8080`) + catch-all `http_status:404`.
- `REGISTERED_LINE` = the real `Registered tunnel connection` line.
- `NON_JSON_LINE` = `2026/06/28 21:07:30 failed to sufficiently increase receive buffer size ...`.
- Fake-binary tests reuse the existing temp shell-script pattern; the script `printf`s JSON lines to stderr then `sleep`s.

| Test | Verifies |
|------|----------|
| `logMessageOf returns message for json line` | Parses `message` |
| `logMessageOf returns null for non-json line` | Skips `NON_JSON_LINE` |
| `ingressRoutesOf extracts only hostname entries` | Catch-all excluded; multi parsed in order |
| `ingressRoutesOf returns empty for malformed json` | No crash |
| `isServiceValid accepts localhost and 127.0.0.1` | Both loopback hosts, correct port |
| `isServiceValid rejects wrong port` | port 9999 vs expected 8080 |
| `isServiceValid rejects https and non-loopback host` | scheme/host strictness |
| `isServiceValid accepts trailing slash` | `http://localhost:8080/` |
| `token mode with empty token sets error` | "Cloudflare tunnel token is required" |
| `token mode valid config sets Connected with hostname` | Fake binary emits `CONFIG_LINE_VALID` → `urls == ["https://pixel8...phonr.dev"]`. **Setup:** `localPort = 8080` |
| `token mode multiple hostnames all valid` | `CONFIG_LINE_MULTI` → two urls, in order |
| `token mode service mismatch errors and stops` | `CONFIG_LINE_MISMATCH` → Error("Tunnel route misconfigured...") and process destroyed |
| `token mode registered-only stays connecting` | Only `REGISTERED_LINE` emitted → status stays Connecting |
| `token mode no config within timeout errors` | Script registers but never pushes config → after the timeout, Error("No public hostname...") + stopped. **Setup:** use the test-only short timeout override (see Note); `advanceUntilIdle()` after it elapses |
| `token mode re-validation stops on later invalid push` | Valid push then mismatch push → ends Error + stopped |
| `token mode error status survives process exit` | After a mismatch triggers `terminateWithError`, the process-monitor does NOT overwrite the status; final status stays the misconfigured Error (verifies the `terminating` guard, Action 3.2.7) |
| `non-json lines are ignored in token mode` | `NON_JSON_LINE` interleaved does not error |
| `isServiceValid rejects service with no port` | `http://localhost` (no port) → false (missing port is invalid) |

> Note (timeout testability): the production scope uses `Dispatchers.IO`, so `runTest` virtual time CANNOT fast-forward the timeout `delay`. The REQUIRED approach is the `internal var configTimeoutMs` property (Action 3.2.5); the test sets `provider.configTimeoutMs = 100` before `start()`. Do NOT use a constructor parameter (Hilt cannot provide a `Long`). Do NOT change the production default (10 s). Do NOT rely on `runTest` virtual time for this `delay`.

**DoD:**
- [x] Pure-helper and fake-binary tests added (run in US6).

---

## User Story 4 — Disable remote access when HTTPS is enabled (service side)

**Why:** Tunnels always target an `http://localhost` origin; running one while the server serves HTTPS would break or mis-expose it. The server must never start a tunnel under HTTPS.

**Acceptance criteria:**
- [x] When `config.httpsEnabled` is true, `McpServerService` does not start a tunnel and stops any active one.

### Task 4.1 — Guard tunnel start in `McpServerService`

- [x] **Action 4.1.1** — modify `McpServerService.kt` where `tunnelManager.start(config.port)` is called:
```kotlin
            if (config.httpsEnabled) {
                Log.i(TAG, "Remote access tunnel disabled while HTTPS is enabled")
                tunnelManager.stop()
            } else {
                @Suppress("TooGenericExceptionCaught")
                try {
                    tunnelManager.start(config.port)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start tunnel (server continues without tunnel)", e)
                }
            }
```
  - The `@Suppress("TooGenericExceptionCaught")` above is PRESERVED VERBATIM from the existing call site (it already wraps the current `try { tunnelManager.start(...) }`); it is relocated into the new `else`, NOT newly introduced.

> Note (no live-tunnel gap): the binding decision "stop any active one" is fully satisfied. The HTTPS toggle is disabled while the server is Running/Starting (`SecuritySettingsScreen.kt` `isEnabled = serverStatus !is Running && !is Starting`, applied to the HTTPS `Switch`), and a tunnel only exists while the server runs. Therefore HTTPS can never be enabled while a tunnel is live; the user must stop the server first, and on the next start this guard refuses to start the tunnel (`tunnelManager.stop()` is the defensive teardown). This is a proven correctness fact, NOT an accepted limitation.

**DoD:**
- [x] No tunnel is started when HTTPS is enabled; `tunnelManager.stop()` invoked in that branch.
- [x] The relocated `@Suppress` is the pre-existing one (no new suppression added).

### Task 4.2 — Service-level test

- [x] **Action 4.2.1** — add/extend the relevant `McpServerService` test (or `TunnelManager` interaction test) — compressed:

| Test | Verifies |
|------|----------|
| `tunnel not started when https enabled` | With `httpsEnabled = true`, `tunnelManager.start` is never invoked and `stop` is invoked. **Setup:** mock `TunnelManager`, verify interactions |

**DoD:**
- [x] Test added (run in US6).

---

## User Story 5 — Tunnel settings UI for Cloudflare mode + HTTPS warning

**Why:** The user configures mode/token here, needs the exact dashboard service URL, and must be warned + blocked when HTTPS is on.

**Acceptance criteria:**
- [x] Cloudflare provider shows a Free/With-Token selector; token mode shows a masked token field and the suggested dashboard service URL with a copy button.
- [x] When HTTPS is enabled, a warning banner appears at the top and the entire section is disabled.

### Task 5.1 — ViewModel additions

- [x] **Action 5.1.1** — modify `MainViewModel.kt`:
  - Add `_cloudflareTokenInput` `MutableStateFlow("")` + public `cloudflareTokenInput`.
  - In the config-collection block (where `_ngrokAuthtokenInput` is set), set `_cloudflareTokenInput.value = config.cloudflareTunnelToken`.
  - Add:
```kotlin
        fun updateCloudflareTunnelMode(mode: CloudflareTunnelMode) {
            viewModelScope.launch { settingsRepository.updateCloudflareTunnelMode(mode) }
        }

        fun updateCloudflareTunnelToken(token: String) {
            _cloudflareTokenInput.value = token
            viewModelScope.launch { settingsRepository.updateCloudflareTunnelToken(token) }
        }
```
  - Add import for `CloudflareTunnelMode`.

**DoD:**
- [x] ViewModel exposes token input + update methods (mirrors the ngrok pattern).

### Task 5.2 — Compose UI

- [x] **Action 5.2.1** — modify `TunnelSettingsScreen.kt`:
  - Collect `cloudflareTokenInput`; read `serverConfig.httpsEnabled`, `serverConfig.cloudflareTunnelMode`, `serverConfig.port`.
  - Compute `val sectionEnabled = isEnabled && !serverConfig.httpsEnabled`.
  - At the top of the scrollable `Column`, when `serverConfig.httpsEnabled`, render a warning banner (e.g. an `ElevatedCard`/`Text` with `colorScheme.error`) using `R.string.remote_access_https_disabled_warning`.
  - Replace `enabled = isEnabled` with `enabled = sectionEnabled` on the enable `Switch`, the provider radios, and the ngrok fields.
  - When `serverConfig.tunnelProvider == CLOUDFLARE` (inside the `tunnelEnabled` visibility), render a Cloudflare mode selector (radio group over `CloudflareTunnelMode.entries`, labels Free/With-Token via string resources, `enabled = sectionEnabled`, `onClick = { viewModel.updateCloudflareTunnelMode(it) }`).
  - When `provider == CLOUDFLARE && cloudflareTunnelMode == TOKEN`, render a new `CloudflareTokenFields` composable:
    - Masked `OutlinedTextField` for the token (show/hide trailing icon, mirrors `NgrokConfigFields`), `enabled = sectionEnabled`, `onValueChange = viewModel::updateCloudflareTunnelToken`.
    - A read-only suggested service URL row: label `remote_access_cloudflare_service_url_help`, value `"http://localhost:${serverConfig.port}"`, and a copy `IconButton` (`Icons.Default.ContentCopy`) using `LocalClipboardManager.current.setText(AnnotatedString(...))`.
- [x] **Action 5.2.2** — add the `CloudflareTokenFields` private composable in the same file.

**DoD:**
- [x] Free/Token selector visible only for Cloudflare; token field + suggested URL + copy visible only in token mode; whole section disabled + warning shown when HTTPS on.

### Task 5.3 — String resources

- [x] **Action 5.3.1** — modify `app/src/main/res/values/strings.xml`, add:
```xml
    <string name="remote_access_cloudflare_mode_label">Cloudflare Mode</string>
    <string name="remote_access_cloudflare_mode_free">Free</string>
    <string name="remote_access_cloudflare_mode_free_desc">Random temporary URL, no account</string>
    <string name="remote_access_cloudflare_mode_token">With Token</string>
    <string name="remote_access_cloudflare_mode_token_desc">Static hostname via your Cloudflare account</string>
    <string name="remote_access_cloudflare_token_label">Cloudflare Tunnel Token</string>
    <string name="remote_access_cloudflare_service_url_help">In your Cloudflare dashboard, set this tunnel\'s Public Hostname → Service to:</string>
    <string name="remote_access_cloudflare_service_url_copy">Copy service URL</string>
    <string name="remote_access_https_disabled_warning">Remote access is unavailable while HTTPS is enabled. Disable HTTPS to use a tunnel.</string>
```

**DoD:**
- [x] All new UI strings resolved from resources (no hardcoded user-facing text in composables).

### Task 5.4 — UI/ViewModel tests

- [x] **Action 5.4.1** — modify `MainViewModelTest.kt` (and add a `TunnelSettingsScreen`/`ConnectionInfoCard` UI test if the project has Compose UI tests; otherwise cover via the ViewModel + pure helpers).

| Test | Verifies |
|------|----------|
| `updateCloudflareTunnelMode persists via repository` | Repo called with mode |
| `updateCloudflareTunnelToken updates input and persists` | Input flow + repo called |
| `cloudflareTokenInput initialized from config` | Seeded from `serverConfig.cloudflareTunnelToken` |

**DoD:**
- [x] ViewModel tests added (run in US6).

---

## User Story 6 — Ground-up verification of the entire implementation

**Why:** Mandatory final pass to confirm the whole feature matches this plan and the agreed decisions, with all quality gates green.

**Acceptance criteria (perform in order):**
- [x] Re-read EVERY file changed/created in US1–US5 against this plan; confirm each action was applied exactly and nothing out of scope was modified.
- [x] Confirm token-mode command is exactly `tunnel --output json run --token <TOKEN>` with NO `--url`.
- [x] Confirm non-JSON lines are skipped; `Registered tunnel connection` does not flip to Connected; only valid config push flips to Connected.
- [x] Confirm validation: `http`, host `localhost`/`127.0.0.1`, exact port; any invalid hostname route OR zero hostnames → Error + process killed; 10 s-from-start no-config → Error + process killed; re-validation on later pushes.
- [x] Confirm `TunnelStatus.Connected.urls` list propagated everywhere; settings screen shows no URL; Connection Info card lists all hostnames and copy/share has one line per hostname.
- [x] Confirm HTTPS: section disabled + warning banner in UI; `McpServerService` does not start a tunnel under HTTPS.
- [x] Confirm no AI attribution anywhere; no TODOs/placeholders; no linting suppressions added.
- [x] Run `make lint` — zero warnings/errors (fix any, including pre-existing per project rule).
- [x] Run the full test suite (`make test` / unit + JVM integration) — all green (fix any broken tests).
- [x] Run `./gradlew build` — succeeds with no warnings/errors.
- [x] Spawn the `code-reviewer` subagent in plan-compliance mode over the full diff; fix ALL findings; re-run until clean.
- [x] Document Manual QA steps (real device, real token): set token, configure dashboard Public Hostname to the suggested service URL, start server, confirm hostname(s) appear in the Connection Info card and the MCP endpoint is reachable; verify a wrong dashboard service stops the tunnel with the misconfigured error; verify enabling HTTPS shows the warning and prevents the tunnel.

**DoD:**
- [x] All checkboxes above ticked; PR created per TOOLS.md; PR URL reported to the user. (Copilot-reviewer step removed from the workflow per user decision — `gh pr edit --add-reviewer copilot` fails in this environment.)

---

## Review Findings — adversarial ground-up pass (post-implementation)

A second, adversarial `code-reviewer` pass produced the following. Behavior-preserving bug-fixes were applied; deviations that are improvements or were user-authorized are recorded here as accepted (code intentionally differs from the plan snippets).

**Fixed (genuine defects, behavior preserved):**
- [x] **C-1/C-2** — `timeoutJob` was cancelled/nulled from the stderr-reader coroutine OUTSIDE `mutex`, violating the Action 3.2.6 synchronization contract (data race). Fixed: `handleTokenConfig` is now `suspend` and performs validation + status transition + `timeoutJob` cancellation entirely under `mutex.withLock`, so `timeoutJob` is only ever touched while holding the lock.
- [x] **W-2** — race where the config-timeout could overwrite a just-set `Connected` with a spurious `Error`. Fixed: the timeout's check-and-act now runs under the same `mutex` as the success path (`terminateWithError` became `failLocked`, callable only while holding the mutex).
- [x] **I-8** — the stderr `BufferedReader` is now closed via `use { }` on every exit path.
- [x] **W-4** — fixed a stale KDoc link `[terminateWithError]` (renamed to `[failLocked]`) on `shutdownProcess`.
- [x] **I-4/I-5** — per explicit user direction, `isServiceValid` now compares scheme/host case-insensitively (RFC 3986) and rejects any service carrying userinfo, a query, or a fragment; unit tests added (`accepts uppercase scheme and host`, `rejects userinfo query and fragment`). This intentionally extends slightly beyond the original exact-match contract, approved by the user.

**Accepted as-is (NOT changed — would deviate from the agreed design/contract; none are defects):**
- **W-1** — "stop any active tunnel under HTTPS" is exercised via the `TunnelManager` interaction test (`start with https enabled does not start tunnel`), which Action 4.2.1 EXPLICITLY permits ("or `TunnelManager` interaction test"). `McpServerService` is an Android `Service` with no JVM unit-test harness in this project; its guard is unchanged from Action 4.1.1, and the "stop any active one" is a provable no-op given HTTPS cannot be toggled while a tunnel is live (Task 4.1 Note). No architecture change made (would deviate).
- **W-3** — `CLAUDE.md`/`docs/TOOLS.md` Copilot-reviewer removal: explicitly user-authorized (see US6 DoD).
- **I-1** — `MainViewModel` update methods use `viewModelScope.launch(ioDispatcher)` (matches every other update method in the file) rather than the plan snippet's bare `launch {}`. Justified consistency improvement; preserved.
- **I-2** — `TunnelManager.start()` carries a defense-in-depth `if (config.httpsEnabled) return` guard (not in the plan). Strengthens the invariant; preserved.
- **I-3** — the Cloudflare mode selector was extracted into a `CloudflareModeSelector` composable (detekt complexity). No behavior change; preserved.
- **I-6/I-7** — two-hop JSON parse matches the captured shape; token-mode tests use real-process timing per Action 3.3.1's mandated approach and are robust by construction. No change.
