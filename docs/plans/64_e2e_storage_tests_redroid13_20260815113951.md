<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 64 — E2E storage-tool tests on redroid 13 (core suite + edge-case suite)

**Motivation**: all three storage bugs of 2026-08-14/15 (#154 path filtering, #155 DCIM coverage, partial photo access) were invisible to CI because unit/integration tests mock MediaStore and `PermissionChecker`. The redroid E2E suite has zero storage coverage. This plan adds it against the real MediaProvider.

**User decisions (confirmed)**:
- Image stays `redroid/redroid:13.0.0-latest` (API 33). Partial-access (`READ_MEDIA_VISUAL_USER_SELECTED`) selection tests are OUT of scope (permission does not exist on API 33); the API 33 graceful-degradation behavior IS in scope.
- Both tiers in one plan: Tier 1 core suite (tool coverage × permission states) + Tier 2 edge-case suite (adversarial fixtures, provider states, protocol boundaries, concurrency).
- The mid-session permission-revoke lifecycle test IS included.

**Key infrastructure facts (verified)**:
- `E2EConfigReceiver` already supports `storage_location_id` + `storage_allow_write`/`storage_allow_delete` broadcast extras. This plan touches the `e2e-tests` module plus ONE debug-source-set addition (new `download_timeout_seconds` and `allow_http_downloads` extras in `E2EConfigReceiver` — required because `ServerConfig.allowHttpDownloads` defaults to `false` and `DownloadUrlValidator` rejects `http://` URLs before connecting, and because the default 60s download timeout would make the slow-endpoint test take >60s). The receiver lives in `app/src/debug` and is absent from the release APK. Main-source-set production code is untouched.
- `AndroidContainerSetup.execAdb` is `private`; it becomes `internal` so the new storage helper can use it. App package in E2E is `com.danielealbano.androidremotecontrolmcp.gms.debug`; tool prefix `android_`; bearer token `AndroidContainerSetup.E2E_BEARER_TOKEN`; `McpClient(baseUrl, bearerToken)`; `stripUntrustedWarning()` exists for device-content responses.
- Files seeded via adb shell are NON-OWNED (owner = media provider); files written through the app's MCP tools are OWNED. Seeded files are indexed only after an explicit media scan, and land in the MediaStore collection implied by their file extension.
- **`builtin:downloads` is permanently owned-only** (its collection has `readMediaPermission = null`, so the owner filter always applies): seeded non-owned files under `Download/` are INVISIBLE to the app by design. Therefore ALL seeded-fixture listing/read tests use permissioned locations (`Pictures/`, `DCIM/`, `Music/`, `Recordings/`) with media extensions (`.jpg`/`.mp4`/`.mp3`/`.m4a` — content bytes are irrelevant to indexing), and `Download/` is exercised via app-OWNED write flows plus one dedicated test asserting the seeded-file invisibility itself.
- `pm revoke` of a granted runtime permission KILLS the app process — any test revoking must restart the server and refresh the SHARED client (`SharedAndroidContainer.mcpClient` is cached; its session dies with the process, and later test classes use the cached instance).
- `Testcontainers.exposeHostPorts` must be called BEFORE container start; `SharedAndroidContainer` gains that call so the fixture HTTP server is reachable at `http://host.testcontainers.internal:<port>`; documented fallback: the container's default-gateway IP (from `ip route` inside the container).
- `settingsRepository.updateDownloadTimeout(seconds)` and `updateAllowHttpDownloads(enabled)` exist. Permission checks, display names, access levels, allow-toggles, and `ServerConfig` are all evaluated live per request — no server restart is needed after `pm grant` or config broadcasts (only after `pm revoke`, which kills the process).

**Empirical-verification procedure — authorized deviations from "no tests during implementation"**: some mechanics can only be determined against a live redroid 13 container, and a subset of tests deliberately PIN observed platform behavior. For these, targeted single-test e2e runs DURING implementation are explicitly part of this plan's procedure (CLAUDE.md: use the sandbox to gain clarity instead of assuming). The full quality gates still run only in the final user story.
1. Media scan: primary `content call --uri content://media/none --method scan_volume --arg external_primary`; if unavailable on redroid 13, fallback to per-file `--method scan_file --arg <abs path>`; if both fail, legacy `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE`. Whichever works first is kept in the helper with a comment naming the verified mechanism.
2. Fixture-server reachability: primary `host.testcontainers.internal`; fallback gateway IP. Same keep-and-comment rule.
3. Observed-behavior pinning (marked "PINNED" in the tables): implement the test with the assertion left failing-by-construction, run it once, pin the assertion to the actual redroid 13 behavior, and add a comment `// Observed redroid 13 (API 33) behavior — documents the platform, revisit on image upgrade`.
4. MediaStore row injection (`insertPendingRow`/`insertDuplicateRow`): verify shell `content insert` against `content://media/external_primary/file` on redroid 13; if the files-table URI rejects shell inserts, use `content://media/external_primary/images/media` with an image display name instead. Whichever works is kept, the other removed.

**Fixture naming constraint**: seeded names MUST NOT contain `"` or `` ` `` or `$` (the seeding helper quotes with double quotes).

---

## User Story 1: Storage E2E infrastructure

Why: shared fixtures/permissions/scan/toggle/restart/HTTP-server plumbing used by both test classes; `execAdb` must become module-visible; the revoke flow must refresh the cached shared client so later classes stay healthy. Tasks are ordered so every cross-file CODE reference points to an EARLIER task: `FixtureHttpServer` (self-contained, owns the port constant) → `SharedAndroidContainer` (uses `FixtureHttpServer.FIXTURE_HTTP_PORT`) → `StorageE2E` (uses `SharedAndroidContainer.refreshMcpClient`).

**Acceptance criteria**:
- [x] `StorageE2E` helper object provides seeding, scanning, permission control, toggle + download-settings configuration, process-death wait, server restart with shared-client refresh, and pending/duplicate row insertion
- [x] `FixtureHttpServer` serves 200/404/slow/truncated endpoints on the fixed exposed port
- [x] `SharedAndroidContainer` exposes the fixture port before container start and can refresh its cached client
- [x] `E2EConfigReceiver` gains `download_timeout_seconds` and `allow_http_downloads` extras (debug source set only)
- [x] No main-source-set production code modified

### Task 1.1: execAdb visibility

**Action 1.1.1** — Modify `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`: change `private fun execAdb(vararg args: String): String` to `internal fun execAdb(vararg args: String): String`. No other change.

**Definition of Done**:
- [x] Visibility changed; no callers broken

### Task 1.2: Fixture HTTP server

**Action 1.2.1** — Create `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/FixtureHttpServer.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Minimal host-side HTTP server for download_from_url E2E tests.
 * Bound on 0.0.0.0:[FIXTURE_HTTP_PORT] (the port is exposed to the container
 * before it starts).
 *
 * Endpoints:
 * - GET /fixture.txt   -> 200, body [FIXTURE_CONTENT], Content-Type text/plain
 * - GET /missing.txt   -> 404
 * - GET /slow.txt      -> sleeps [SLOW_DELAY_MS], then 200 (exceeds the 10s test download timeout)
 * - GET /truncated.txt -> declares Content-Length 1000, writes 10 bytes, closes (mid-stream cut)
 */
class FixtureHttpServer {
    private var server: HttpServer? = null

    fun start() {
        val s = HttpServer.create(InetSocketAddress("0.0.0.0", FIXTURE_HTTP_PORT), 0)
        s.createContext("/fixture.txt") { exchange ->
            val body = FIXTURE_CONTENT.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.createContext("/missing.txt") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        s.createContext("/slow.txt") { exchange ->
            Thread.sleep(SLOW_DELAY_MS)
            val body = "late".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.createContext("/truncated.txt") { exchange ->
            exchange.sendResponseHeaders(200, 1000L)
            exchange.responseBody.write(ByteArray(10))
            exchange.close()
        }
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /**
     * Base URL as reachable FROM INSIDE the container. Primary: host.testcontainers.internal.
     * Fallback (verified empirically per the plan's procedure): the container's default
     * gateway IP (from `ip route` run inside the container).
     */
    fun containerReachableBaseUrl(): String = "http://host.testcontainers.internal:$FIXTURE_HTTP_PORT"

    companion object {
        /** Fixed host port, exposed to the container by SharedAndroidContainer before start. */
        const val FIXTURE_HTTP_PORT = 18923
        const val FIXTURE_CONTENT = "e2e-download-fixture-content"
        const val SLOW_DELAY_MS = 15_000L
    }
}
```

### Task 1.3: SharedAndroidContainer — port exposure + client refresh

**Action 1.3.1** — Modify `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/SharedAndroidContainer.kt`:

1. In `ensureInitialized()`, BEFORE the container is created/started, add:

```kotlin
                org.testcontainers.Testcontainers.exposeHostPorts(FixtureHttpServer.FIXTURE_HTTP_PORT)
```

2. Add a shared-client refresh function (closes the cached client best-effort, builds and connects a fresh one, replaces the cache):

```kotlin
    /**
     * Replaces the cached MCP client with a freshly connected one. Required after the
     * app process is killed (e.g. by a runtime-permission revoke): the cached client's
     * MCP session dies with the process, and later test classes read [mcpClient].
     */
    fun refreshMcpClient(): McpClient {
        ensureInitialized()
        synchronized(lock) {
            try {
                runBlocking { _mcpClient?.close() }
            } catch (_: Exception) {
                // Best-effort close of a dead session
            }
            val client = McpClient(mcpServerUrl, AndroidContainerSetup.E2E_BEARER_TOKEN)
            runBlocking { client.connect() }
            _mcpClient = client
            return client
        }
    }
```

Constraint: the construction/connect call MUST mirror how `ensureInitialized()` builds the original client (adapt to the actual code; intent: identical client, fresh session).

### Task 1.4: StorageE2E helper

**Action 1.4.1** — Create `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/StorageE2E.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import java.util.Base64

/**
 * Shared storage-E2E infrastructure: MediaStore fixture seeding, media scanning,
 * READ_MEDIA_* permission control, builtin allow-write/allow-delete toggles,
 * download settings, app-process lifecycle helpers, and MediaStore row injection.
 *
 * Files seeded via adb shell are NON-OWNED from the app's perspective; files created
 * through the app's MCP tools are OWNED. Seeded files become visible to MediaStore
 * only after [scanVolume]/[scanPath], in the collection implied by their extension.
 *
 * Fixture names MUST NOT contain double quotes, backticks, or '$' (double-quote shell quoting).
 */
object StorageE2E {
    const val APP_PACKAGE = "com.danielealbano.androidremotecontrolmcp.gms.debug"
    const val PERM_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val PERM_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val PERM_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    private const val SDCARD = "/storage/emulated/0"
    private const val E2E_ACTION_BASE = "com.danielealbano.androidremotecontrolmcp.debug"
    private const val E2E_CONFIG_RECEIVER =
        "$APP_PACKAGE/com.danielealbano.androidremotecontrolmcp.debug.E2EConfigReceiver"

    fun shell(cmd: String): String = AndroidContainerSetup.execAdb("shell", "sh", "-c", cmd)

    fun grantMediaPermission(permission: String) {
        AndroidContainerSetup.execAdb("shell", "pm", "grant", APP_PACKAGE, permission)
    }

    fun grantAllMediaPermissions() {
        grantMediaPermission(PERM_IMAGES)
        grantMediaPermission(PERM_VIDEO)
        grantMediaPermission(PERM_AUDIO)
    }

    /**
     * Revokes a granted runtime permission. WARNING: this KILLS the app process.
     * Callers MUST afterwards call [waitForAppProcessDeath], then
     * [restartServerAndRefreshClient].
     */
    fun revokeMediaPermission(permission: String) {
        AndroidContainerSetup.execAdb("shell", "pm", "revoke", APP_PACKAGE, permission)
    }

    /** Seeds a file with the given text content at an /sdcard-relative path (parents created). */
    fun seedFile(relativePath: String, content: String) {
        val path = "$SDCARD/$relativePath"
        val dir = path.substringBeforeLast('/')
        val b64 = Base64.getEncoder().encodeToString(content.toByteArray())
        shell("mkdir -p \"$dir\" && echo $b64 | base64 -d > \"$path\"")
    }

    /** Seeds [count] one-byte files named <prefix>NNN.jpg under an /sdcard-relative directory. */
    fun seedBulk(dirRelativePath: String, count: Int, prefix: String = "bulk") {
        val dir = "$SDCARD/$dirRelativePath"
        val d = "\$"
        shell(
            "mkdir -p \"$dir\" && i=0; while [ ${d}i -lt $count ]; do " +
                "printf x > \"$dir/$prefix${d}(printf %03d ${d}i).jpg\"; i=${d}((i+1)); done",
        )
    }

    /** Removes a single seeded file from disk (plain rm, no recursion). */
    fun removeFromDisk(relativePath: String) {
        shell("rm \"$SDCARD/$relativePath\"")
    }

    /** Removes a seeded fixture directory tree. ONLY for paths created by this suite. */
    fun removeFixtureTree(relativePath: String) {
        require(relativePath.isNotBlank() && !relativePath.contains("..")) { "unsafe path" }
        shell("rm -rf \"$SDCARD/$relativePath\"")
    }

    /**
     * Triggers a MediaStore scan. Mechanism verified empirically against redroid 13
     * per the plan's procedure (scan_volume primary; scan_file per path; legacy
     * broadcast last). The verified mechanism is kept, the others removed.
     */
    fun scanVolume() {
        AndroidContainerSetup.execAdb(
            "shell", "content", "call", "--uri", "content://media/none",
            "--method", "scan_volume", "--arg", "external_primary",
        )
    }

    fun scanPath(relativePath: String) {
        AndroidContainerSetup.execAdb(
            "shell", "content", "call", "--uri", "content://media/none",
            "--method", "scan_file", "--arg", "$SDCARD/$relativePath",
        )
    }

    /** Sets builtin location allow-write/allow-delete via the E2E config broadcast. */
    fun configureStorageLocation(locationId: String, allowWrite: Boolean, allowDelete: Boolean) {
        AndroidContainerSetup.execAdb(
            "shell", "am", "broadcast", "--include-stopped-packages",
            "-a", "$E2E_ACTION_BASE.E2E_CONFIGURE",
            "-n", E2E_CONFIG_RECEIVER,
            "--es", "storage_location_id", locationId,
            "--ez", "storage_allow_write", allowWrite.toString(),
            "--ez", "storage_allow_delete", allowDelete.toString(),
        )
        Thread.sleep(1_000)
    }

    /** Sets download timeout + HTTP-download allowance via the E2E config broadcast. */
    fun configureDownloadSettings(timeoutSeconds: Int, allowHttp: Boolean) {
        AndroidContainerSetup.execAdb(
            "shell", "am", "broadcast", "--include-stopped-packages",
            "-a", "$E2E_ACTION_BASE.E2E_CONFIGURE",
            "-n", E2E_CONFIG_RECEIVER,
            "--ei", "download_timeout_seconds", timeoutSeconds.toString(),
            "--ez", "allow_http_downloads", allowHttp.toString(),
        )
        Thread.sleep(1_000)
    }

    /** Inserts a MediaStore row with IS_PENDING=1 (no backing content written). */
    fun insertPendingRow(displayName: String, relativePathWithSlash: String) {
        AndroidContainerSetup.execAdb(
            "shell", "content", "insert", "--uri", "content://media/external_primary/file",
            "--bind", "_display_name:s:$displayName",
            "--bind", "relative_path:s:$relativePathWithSlash",
            "--bind", "is_pending:i:1",
        )
    }

    /** Inserts a second MediaStore row with the same display name + relative path. */
    fun insertDuplicateRow(displayName: String, relativePathWithSlash: String) {
        AndroidContainerSetup.execAdb(
            "shell", "content", "insert", "--uri", "content://media/external_primary/file",
            "--bind", "_display_name:s:$displayName",
            "--bind", "relative_path:s:$relativePathWithSlash",
        )
    }

    /** Polls until the app process is gone (used after [revokeMediaPermission]). */
    fun waitForAppProcessDeath(timeoutMs: Long = 15_000L) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val pid = try {
                shell("pidof $APP_PACKAGE || true")
            } catch (_: Exception) {
                ""
            }
            if (pid.isBlank()) return
            Thread.sleep(500)
        }
        error("App process still alive ${timeoutMs}ms after permission revoke")
    }

    /**
     * Restarts the MCP server and REFRESHES the shared cached client
     * (SharedAndroidContainer.mcpClient), so both this suite and any test class
     * running afterwards see a live session. Returns the refreshed client.
     */
    fun restartServerAndRefreshClient(): McpClient {
        AndroidContainerSetup.startMcpServer()
        AndroidContainerSetup.waitForServerReady(SharedAndroidContainer.mcpServerUrl)
        return SharedAndroidContainer.refreshMcpClient()
    }
}
```

### Task 1.5: E2EConfigReceiver download-settings extras

**Action 1.5.1** — Modify `app/src/debug/kotlin/com/danielealbano/androidremotecontrolmcp/debug/E2EConfigReceiver.kt` (debug source set — absent from release APK): add a private helper and call it from `handleConfigure`'s coroutine next to `applyAuthFlags(intent)` (extracted as a helper so the already-long `handleConfigure` does not grow — no lint suppression permitted):

```kotlin
    private suspend fun applyDownloadSettings(intent: Intent) {
        val downloadTimeout = intent.getIntExtra(EXTRA_DOWNLOAD_TIMEOUT_SECONDS, -1)
        if (downloadTimeout in
            ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS..ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS
        ) {
            settingsRepository.updateDownloadTimeout(downloadTimeout)
            Log.i(TAG, "Download timeout updated to $downloadTimeout s")
        }
        if (intent.hasExtra(EXTRA_ALLOW_HTTP_DOWNLOADS)) {
            val allowHttp = intent.getBooleanExtra(EXTRA_ALLOW_HTTP_DOWNLOADS, false)
            settingsRepository.updateAllowHttpDownloads(allowHttp)
            Log.i(TAG, "Allow HTTP downloads updated to $allowHttp")
        }
    }
```

and in the companion object:

```kotlin
        private const val EXTRA_DOWNLOAD_TIMEOUT_SECONDS = "download_timeout_seconds"
        private const val EXTRA_ALLOW_HTTP_DOWNLOADS = "allow_http_downloads"
```

**Definition of Done** (Tasks 1.2–1.5):
- [x] All files in place; helper compiles against actual `McpClient`/`AndroidContainerSetup`/`SharedAndroidContainer` internals; port exposed before container start; receiver extras validated and applied via the extracted helper

---

## User Story 2: Tier 1 — core storage suite

Why: end-to-end coverage of all 8 file tools against real MediaStore, in the regression classes of #154/#155/#156 and the permission/toggle matrix.

**Acceptance criteria**:
- [x] All 8 file tools exercised end-to-end at least once
- [x] The #154 (path filter), #155 (multi-collection), #156 (recordings) regression classes each have a live-MediaStore test
- [x] Owned-vs-non-owned filtering verified against real `OWNER_PACKAGE_NAME` values in both directions, including the `builtin:downloads` seeded-file invisibility
- [x] Toggle gating (allow-write/allow-delete off → denied) verified
- [x] All download edge scenarios (success, 404, refused, timeout, truncated) exercised through the fixture server

### Task 2.1: E2EStorageToolsTest

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2EStorageToolsTest.kt` (create; `@TestInstance(PER_CLASS)` + `@TestMethodOrder(MethodOrderer.OrderAnnotation::class)` with `@Order(n)` equal to the table row number — required because rows 12/13/16/17/18 form a mutable-file chain on `e2e-rt.txt`; uses `SharedAndroidContainer.mcpClient`; tool names prefixed `android_`; device-content responses unwrapped with `stripUntrustedWarning`)

**Class setup (`@BeforeAll`)**: `grantAllMediaPermissions()`; `configureStorageLocation` allowWrite+allowDelete=true for `builtin:downloads`, `builtin:pictures`, `builtin:dcim`, `builtin:recordings`, `builtin:music` (`builtin:movies` deliberately left false for denial tests); `configureDownloadSettings(10, allowHttp = true)`; start a class-held `FixtureHttpServer` and resolve `<base>` via `containerReachableBaseUrl()` (fallback per header procedure 2); seed the core fixture tree; scan. NO server restart: granting does not kill the process, and all settings/permissions are evaluated live per request.

**Core fixture tree** (all seeded = non-owned; media extensions so files land in permissioned collections — see the `builtin:downloads` fact in the header):

| Path | Content |
|------|---------|
| `DCIM/Camera/photo1.jpg` | `"jpeg-fixture-1"` |
| `DCIM/Camera/video1.mp4` | `"mp4-fixture-1"` |
| `DCIM/Screenshots/shot1.png` | `"png-fixture-1"` |
| `Pictures/Vacation/pic1.jpg` | `"jpeg-fixture-2"` |
| `Pictures/clip.mp4` | `"mp4-fixture-2"` |
| `Pictures/readable.jpg` | `"line one\nline two"` |
| `Pictures/depth/notes.jpg` | `"notes"` |
| `Pictures/bulk/bulk000..209.jpg` | 210 one-byte files via `seedBulk` |
| `Music/Album/song.mp3` | `"mp3-fixture"` |
| `Recordings/memo.m4a` | `"m4a-fixture"` |
| `Download/seeded.txt` | `"invisible"` (for the owned-only invisibility test) |

**Class teardown (`@AfterAll`)**: stop the `FixtureHttpServer`; `removeFixtureTree`/`removeFromDisk` for every path seeded above plus files created by write/download tests; scan; reset the five locations' toggles to false via `configureStorageLocation`; `configureDownloadSettings(60, allowHttp = false)` (restore defaults).

| # | Test | Verifies |
|---|------|----------|
| 1 | `list_storage_locations returns six builtins with access levels` | ids in order downloads/pictures/movies/music/dcim/recordings; pictures/movies/dcim/music/recordings `access_level=="full"` + name "- All files"; downloads `owned_only` + "- Only owned files"; response text begins with the untrusted warning |
| 2 | `dcim root synthesizes camera and screenshots directories` | dirs `Camera`, `Screenshots` present, `is_directory` true |
| 3 | `dcim camera merges images and videos` | `photo1.jpg` AND `video1.mp4` in one listing (#155) |
| 4 | `pictures root includes video stored under pictures` | `clip.mp4` listed (#155) |
| 5 | `single segment path constrains listing` | `pictures` path=`Vacation` → exactly `pic1.jpg` (#154) |
| 6 | `nested path constrains listing` | `pictures` path=`depth` → `notes.jpg` only; `pictures` root listing shows `depth` as a directory, not `notes.jpg` as a file |
| 7 | `unknown path returns empty listing` | `dcim` path=`DoesNotExist` → `total_count==0` |
| 8 | `recordings lists seeded audio` | `memo.m4a` listed (#156) |
| 9 | `music lists seeded audio` | `song.mp3` under path=`Album` |
| 10 | `read_file reads non-owned file in all-files mode` | `pictures` `readable.jpg` → content `"line one\nline two"`, 2 lines |
| 11 | `downloads hides non-owned seeded files` | `Download/seeded.txt` (seeded + scanned) absent from `downloads` listing — live proof of the permanent owner filter |
| 12 | `write_file then read_file round trip` | `downloads` write `e2e-rt.txt` → read back identical content (owned path) |
| 13 | `written file appears in listing without manual scan` | `e2e-rt.txt` visible in `downloads` root listing |
| 14 | `write_file routes image to dcim and lists it` | write `e2e-shot.jpg` to `dcim` succeeds; appears in `dcim` root listing |
| 15 | `write_file rejects wrong mime with accepted types` | `x.txt` to `pictures` → `isError`, message lists `images, videos` |
| 16 | `append_file appends to owned file` | append to `e2e-rt.txt` → read shows appended line |
| 17 | `file_replace replaces text in owned file` | replace in `e2e-rt.txt` → read shows replacement |
| 18 | `delete_file removes owned file and repeat errors not found` | delete `e2e-rt.txt` → gone from listing; second delete → `isError` "not found" (idempotent retry) |
| 19 | `write denied when allow write disabled` | write to `builtin:movies` → `isError` permission denied |
| 20 | `delete denied when allow delete disabled` | delete on `builtin:movies` → `isError` permission denied |
| 21 | `delete of non-owned file reports not found` | delete seeded `Pictures/clip.mp4` → `isError` "not found" — deletes resolve OWNED files only (`findOwnedFileOrThrow`), a security property: the tool can never touch non-owned files |
| 22 | `write_file to existing non-owned name` | write `readable.jpg` to `pictures` → PINNED (clean error, or new/auto-renamed row; no crash) |
| 23 | `download_from_url downloads fixture file` | download `<base>/fixture.txt` to `downloads` → read back `FIXTURE_CONTENT` |
| 24 | `download_from_url 404 returns error and leaves no file` | `<base>/missing.txt` → `isError` mentioning the HTTP failure (NOT the http-disabled gate); target name absent from listing |
| 25 | `download_from_url connection refused returns error` | URL on an unused port of the same host → `isError`, no file |
| 26 | `download_from_url times out on slow server` | `<base>/slow.txt` with the configured 10s timeout → `isError` within < `SLOW_DELAY_MS` + margin; no file left |
| 27 | `download_from_url handles truncated response` | `<base>/truncated.txt` (content-length lie, mid-stream close) → PINNED (clean error, or file with observed size — no crash); server healthy afterwards |
| 28 | `pagination returns stable non-overlapping pages` | `pictures` path=`bulk`, limit=100: pages 100/100/10; union of names == 210 seeded, no duplicates; `total_count==210` on each page; `has_more` true/true/false |
| 29 | `pagination offset beyond end returns empty page` | offset=1000 → 0 files, `has_more==false`, `total_count==210` |
| 30 | `limit above cap is coerced to 200` | limit=500 → exactly 200 entries, `has_more==true` |
| 31 | `newly seeded file appears after scan` | seed `Pictures/late.jpg` + `scanPath` → visible in `pictures` root listing |

**Definition of Done**:
- [x] 31 tests implemented with `@Order` = row number; fixture-server lifecycle owned by the class; every assertion on device-content responses unwraps the untrusted warning

---

## User Story 3: Tier 2 — edge-case suite

Why: adversarial inputs and provider states that mocks cannot reproduce; a fixture matrix auditable row by row.

**Acceptance criteria**:
- [x] Every fixture-matrix row is exercised by at least one test
- [x] All PINNED tests carry the observed-behavior comment
- [x] Concurrency tests use a second `McpClient` session
- [x] The revoke lifecycle test runs LAST and leaves full grants, a healthy server, and a refreshed shared client

### Task 3.1: E2EStorageEdgeCasesTest

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2EStorageEdgeCasesTest.kt` (create; same conventions as Task 2.1 including `@TestMethodOrder` + `@Order` = row number — the revoke test MUST be last)

**Class setup (`@BeforeAll`)**: `grantAllMediaPermissions()`; toggles allowWrite+allowDelete=true for `builtin:downloads` and `builtin:pictures`; seed the adversarial matrix; scan. NO restart needed (see header facts); uses `SharedAndroidContainer.mcpClient` (the revoke test refreshes it via `restartServerAndRefreshClient()`).
**Class teardown (`@AfterAll`)**: re-grant all media permissions (the revoke test may leave IMAGES revoked on failure); `StorageE2E.restartServerAndRefreshClient()` — the revoke test kills the process even on success, and the SHARED cached client must be live for any later class; `removeFixtureTree("Pictures/edge")` plus owned leftovers; scan; reset toggles to false.

**Adversarial fixture matrix** (all under `Pictures/edge/` — a permissioned location; `.jpg` names so rows land in the Images collection):

| Row | Fixture | Purpose |
|-----|---------|---------|
| F1 | `a_b/f1.jpg`, `a%b/f2.jpg`, `axb/f3.jpg` | LIKE `_`/`%` escaping — positive proof |
| F2 | `sp ace/s.jpg` | space in directory name |
| F3 | `ünïcødé/u1.jpg`, `照片/c1.jpg` | unicode dir names |
| F4 | `Case/x.jpg`, `case/y.jpg` | ASCII case pair (SQLite LIKE case-insensitivity) |
| F5 | `a/b/c/d/e/deep.jpg` | deep nesting + dir synthesis per level |
| F6 | `.hidden.jpg` | dotfile (media scanner hidden-file convention) |
| F7 | `report.v2.final.jpg` | multi-dot name |
| F8 | `empty.jpg` (0 bytes, via `seedFile(..., "")`) | zero-byte file |

| # | Test | Verifies |
|---|------|----------|
| 1 | `underscore dir does not match percent dir` | path=`edge/a_b` → exactly `f1.jpg`; path=`edge/a%b` → exactly `f2.jpg`; path=`edge/axb` → exactly `f3.jpg` (F1 — the escaping proof impossible on real phones) |
| 2 | `directory with space lists and reads` | path=`edge/sp ace` → `s.jpg`; `read_file` succeeds (F2) |
| 3 | `unicode directories list and read` | both F3 dirs list their file; read one content back |
| 4 | `ascii case pair listing` | path=`edge/Case` vs `edge/case` → PINNED (does LIKE case-insensitivity bleed the listings together?) (F4) |
| 5 | `deep nested path resolves` | path=`edge/a/b/c/d/e` → `deep.jpg`; path=`edge/a` synthesizes dir `b` (F5) |
| 6 | `hidden dotfile listing` | `.hidden.jpg` in path=`edge` listing → PINNED (scanner likely skips dotfiles entirely) (F6) |
| 7 | `multi dot name round trips` | read F7; `write_file` `out.v1.draft.txt` to `downloads` then read back (owned) |
| 8 | `zero byte file lists and reads empty` | `empty.jpg` → PINNED (scanner may skip 0-byte files; if listed: size==0 and read returns empty content) (F8) |
| 9 | `duplicate display name resolution` | `insertDuplicateRow("f1.jpg", "Pictures/edge/a_b/")` → `read_file` of `edge/a_b/f1.jpg` → PINNED (which row resolves; no crash) |
| 10 | `pending row invisible to listing` | `insertPendingRow("pending.jpg", "Pictures/edge/")` → absent from path=`edge` listing |
| 11 | `stale row read fails cleanly` | `removeFromDisk("Pictures/edge/report.v2.final.jpg")` (row still present) → `read_file` → `isError`, clean message; after `scanPath` the row disappears from listing |
| 12 | `unscanned file invisible until scan` | seed `Pictures/edge/unscanned.jpg` WITHOUT scan → absent; after `scanPath` → present |
| 13 | `path traversal rejected end to end` | path=`../Music`, path=`/etc`, path with `\n` → each `isError` invalid params over real JSON-RPC |
| 14 | `missing or wrongly typed params rejected` | `list_files` without `location_id` → `isError` invalid params; `list_files` with `path` passed as an integer → `isError` invalid params |
| 15 | `unknown location id rejected` | `location_id="builtin:nope"` → `isError` "not found" |
| 16 | `negative offset and zero limit` | offset=-1, then limit=0 → PINNED (clean error or clamped result; no crash, no 500) |
| 17 | `parallel writes to same filename` | second `McpClient`; two concurrent `write_file` of `race.txt` to `downloads` → both calls complete without transport error; subsequent listing PINNED (row count for `race.txt`); server healthy |
| 18 | `delete during read does not break server` | concurrent `read_file` loop + `delete_file` on the same owned file via second client → no transport errors; each call returns success or clean not-found; server healthy afterwards |
| 19 | `list during writes stays consistent` | second client writes 10 files sequentially to `downloads` while the first client polls `list_files` — every listing call succeeds (no transport error, no `isError`), `total_count` is monotonically non-decreasing across polls |
| 20 | `revoke mid session kills process and server recovers` | `revokeMediaPermission(PERM_IMAGES)` → `waitForAppProcessDeath()` → `restartServerAndRefreshClient()` → `list_storage_locations` shows pictures name `"Pictures - All videos, owned images"` and `access_level=="partial"` (per-type mixed grant, live) → re-grant IMAGES → `restartServerAndRefreshClient()` → pictures back to `"Pictures - All files"`/`"full"` |

**Definition of Done**:
- [x] 20 tests implemented with `@Order` = row number (revoke last); every PINNED assertion carries the observed-behavior comment; teardown restores grants, toggles, server, shared client, and disk state

---

## User Story 4: Final verification — ground-up double check

Why: mandatory last step; quality gates run only here (except the authorized targeted runs defined in the header procedure).

**Acceptance criteria**:
- [x] Every action of this plan re-verified against the actual code from the ground up
- [x] All quality gates pass, including the full E2E suite

### Task 4.1: Ground-up double check

- [x] Re-read this plan from disk, action by action, and verify each change exists as specified (visibility change, all new/modified infra files, the receiver extras, both test classes with `@Order` numbering, every test in the tables, every fixture row seeded, every PINNED test carrying its comment, teardown restoration steps)
- [x] Verify NO file outside this plan's scope was modified (`git status` / `git diff --stat`) — the only app-module change is the debug-source-set `E2EConfigReceiver` addition; main source set untouched
- [x] Verify no TODOs, placeholders, or commented-out code were introduced

### Task 4.2: Quality gates

- [x] `make lint` — zero warnings/errors (pipe through `tee` to `/tmp/p64-lint.log`)
- [x] `make test-unit` — full suite passes (pipe through `tee` to `/tmp/p64-test-unit.log`)
- [x] `make test-e2e` — full E2E suite passes including both new classes AND all pre-existing E2E classes (pipe through `tee` to `/tmp/p64-test-e2e.log`)
- [x] `./gradlew build` — builds without errors or warnings (pipe through `tee` to `/tmp/p64-build.log`)

**Definition of Done**:
- [x] All checks above pass; any failure fixed at the root cause and gates re-run
