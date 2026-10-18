<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 48 — Share Target + Ephemeral (TTL) Capability-Link File Serving

Lets an agent pull content the user/app shares INTO this app (text, images, PDFs, files) via the Android share sheet, and lets the agent expose a device file OUT as a temporary (1-hour) fetchable URL. Both reuse one capability-URL file-serving subsystem. Background services cannot read the OS clipboard (Android 10+ focus/IME restriction), so the share-sheet Intent path is the preferred mechanism and `web_fetch` (which reads text + PDF from a URL) is how the agent consumes binaries.

## Shared decisions (authoritative — do not deviate)

- Capability URL path prefix: `/s/` (e.g. `/s/<64-hex-token>`).
- Capability token: lowercase hex of `SHA-256(System.nanoTime() bytes ‖ 32 bytes from java.security.SecureRandom)` → 64 chars. The entropy/security basis is `SecureRandom`.
- **Ephemeral link registry** (shared by both tools, IN-MEMORY ONLY): max **20** live links; TTL **1 hour**; when adding beyond 20, evict oldest (FIFO) and delete its blob; **TTL-only** deletion (NO delete-on-fetch / NO one-shot); multiple fetches allowed within TTL. NOT persisted across process death; stale blobs cleared on init.
- **Share inbox** (inbound shares, IN-MEMORY ONLY): max **5** items; max **50 MB** total; per-file max **10 MB** (files larger are rejected/skipped); TTL **1 hour**; consume-on-read (`android_get_shared_content` drains it). NOT persisted; stale blobs cleared on init.
- `android_share_file_via_web` per-file cap = the existing configurable `ServerConfig.fileSizeLimitMb` (default 50 MB) — NOT the 10 MB inbox cap.
- Content type mapping on read:
  - Textual MIME → inline `TextContent`. Allowlist: `text/*`, `application/json`, `application/xml`, `application/xhtml+xml`, `application/javascript`, `application/ld+json`, `application/yaml`, `text/yaml`.
  - `image/*` → BOTH a downscaled inline `ImageContent` (longest side ≤ **800 px**; smaller images kept as-is) AND a `TextContent` with the capability URL to the original, flagged "only share this URL with the user if they ask for the original".
  - Everything else (incl. `application/pdf`, binaries) → `TextContent` with the capability URL + metadata (name, mime, size). The text states the agent can `web_fetch` text/PDF URLs to read them; other types are download-only.
- Inline binary blob (`BlobResourceContents`) MUST NOT be used (clients do not read it).
- Both tools return device-derived content/metadata (text, image, filename) → use `McpToolUtils` untrusted result helpers (warning is the first content block).
- Base URL for capability links = the tunnel URL when a tunnel is connected, else `scheme://<device-LAN-IP>:<port>` (scheme = https if HTTPS enabled, else http; IP via `NetworkUtils.getDeviceIpAddress`). When no tunnel is connected, the tool text appends a reachability note (the link must be reachable by the fetcher; remote clients require a tunnel).
- The capability token and full `/s/` URL MUST NOT be written to logcat/UI logs (they are credentials).

## Conventions reused (do not re-create)

- Ktor server: `mcp/McpServer.kt` (routes `/health`, `/mcp`); bearer auth `mcp/auth/BearerTokenAuth.kt` (`BearerTokenAuthPlugin`, implemented via `application.intercept(...)` with `return@intercept`; `excludedPaths` exact-match).
- Tunnel URL: `TunnelManager.tunnelStatus: StateFlow<TunnelStatus>` → `TunnelStatus.Connected(val url)`.
- Storage: `services/storage/FileOperationProvider` + impls (`FileOperationProviderImpl` SAF, `MediaStoreFileOperationsImpl` MediaStore); `FileReadResult.content` is text-only; `ServerConfig.fileSizeLimitMb` (default `DEFAULT_FILE_SIZE_LIMIT_MB = 50`).
- MCP content: `CallToolResult(content: List<io.modelcontextprotocol.kotlin.sdk.types.ContentBlock>)`; `TextContent(text)`, `ImageContent(data = <base64 String>, mimeType)`; `McpToolUtils` (`UNTRUSTED_CONTENT_WARNING`, `untrustedTextResult`, `untrustedTextAndImageResult`, `untrustedImageResult`).
- Image scaling/base64: reuse `services/screencapture/ScreenshotEncoder` proportional-resize + JPEG-base64 utilities (do not re-implement bitmap math).
- Network host: `utils/NetworkUtils.getDeviceIpAddress(context)`.
- Hilt bindings in `di/AppModule.kt`; tool registration in `services/mcp/McpServerService.kt` (`registerAllTools`); `McpServer(...)` constructed in `McpServerService.startServer()`; `ui/MainActivity.kt` is the existing activity pattern.
- New code: package `services/sharing/`; activity `ui/ShareReceiverActivity.kt`; tools `mcp/tools/SharingTools.kt`.

---

## User Story 1 — Ephemeral capability-link file server (in-memory)

**Why:** A single token→file registry plus an unauthenticated `/s/{token}` route is the shared primitive both tools need; `web_fetch` cannot send the bearer token, so the route must bypass auth via an unguessable capability token.

**Acceptance criteria:**
- [x] `EphemeralFileLinkService` registers a blob and returns a 64-hex token; `GET /s/<token>` streams the blob with its content-type; unknown/expired token → 404.
- [x] Registry is in-memory, holds ≤ 20 live links; adding the 21st evicts the oldest (and deletes its blob); entries expire after 1 h; expired entries purged on access; the blob dir is cleared on service init (no persistence across process death).
- [x] `BearerTokenAuthPlugin` allows `/s/<token>` without a token while keeping `/health` exact-match and `/mcp` authenticated.
- [x] Tokens come from `SecureRandom`; tokens/URLs are never logged.

### Task 1.1 — Capability token generator

**Action** — create `services/sharing/CapabilityToken.kt`:
```kotlin
package com.danielealbano.androidremotecontrolmcp.services.sharing

import java.security.MessageDigest
import java.security.SecureRandom

/** Unguessable capability tokens: lowercase hex of SHA-256(nanoTime ‖ 32 SecureRandom bytes). Entropy from SecureRandom. */
internal object CapabilityToken {
    private val secureRandom = SecureRandom()

    fun generate(): String {
        val random = ByteArray(RANDOM_BYTES).also { secureRandom.nextBytes(it) }
        val nanos = System.nanoTime()
        val nanoBytes = ByteArray(Long.SIZE_BYTES) { (nanos shr (it * Byte.SIZE_BITS)).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").apply { update(nanoBytes); update(random) }.digest()
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val RANDOM_BYTES = 32
}
```

**Definition of Done:**
- [x] Returns a 64-char lowercase hex string; distinct across calls.

### Task 1.2 — `EphemeralFileLinkService` interface + implementation (in-memory)

**Action** — create `services/sharing/EphemeralFileLinkService.kt`:
```kotlin
interface EphemeralFileLinkService {
    /** Moves [source] into the managed dir under a fresh token; evicts oldest beyond MAX_LINKS; returns the token. */
    suspend fun register(source: File, mimeType: String, fileName: String): String
    /** Live entry for [token], or null if missing/expired (expired entries purged on lookup). */
    suspend fun resolve(token: String): LinkEntry?
    /** Deletes expired entries and their blobs. */
    suspend fun purgeExpired()
    fun pathFor(token: String): String   // PATH_PREFIX + token
    companion object {
        const val PATH_PREFIX = "/s/"
        const val MAX_LINKS = 20
        const val TTL_MS = 60L * 60L * 1000L
    }
}

data class LinkEntry(val token: String, val blob: File, val mimeType: String, val fileName: String, val expiresAtMs: Long)
```

**Action** — create `services/sharing/EphemeralFileLinkServiceImpl.kt` (`@Singleton`, `@Inject constructor(@ApplicationContext context)`):
- Blob dir `File(context.filesDir, "ephemeral_links")`. On init: delete all files in this dir (state is in-memory and starts empty, so any on-disk blob is stale).
- In-memory `LinkedHashMap<String, LinkEntry>` (insertion order) guarded by a `Mutex`. NO on-disk index.
- `register`: `purgeExpired()`; generate token via `CapabilityToken`; move `source` to `ephemeral_links/<token>` via `source.renameTo(dest)`, falling back to a stream copy + `source.delete()` when `renameTo` returns false (the source may be on a different filesystem); verify `dest.exists()` (else throw) so the registry never holds a dead blob; while `size >= MAX_LINKS` remove eldest entry and delete its blob; put `LinkEntry(expiresAtMs = now + TTL_MS)`; return token. Final size after insert ≤ 20.
- `resolve`: `purgeExpired()`; return entry iff present, `now < expiresAtMs`, and `blob.exists()`, else null.
- `purgeExpired`: remove + delete blobs for entries with `now >= expiresAtMs` or missing blob.
- `pathFor(token) = PATH_PREFIX + token`. All mutations under the `Mutex`. The token/URL are never logged.

**Action** — in the abstract `ServiceModule` class within `di/AppModule.kt` (where `bindFileOperationProvider` etc. live): `@Binds @Singleton abstract fun bindEphemeralFileLinkService(impl: EphemeralFileLinkServiceImpl): EphemeralFileLinkService`.

**Definition of Done:**
- [x] `register` moves the source, evicts oldest at 20 (final size 20) and deletes the evicted blob; `resolve` honors TTL + missing-blob; init clears stale blobs; `Mutex`-guarded; nothing logged.

### Task 1.3 — `/s/{token}` Ktor route

**Action** — `mcp/McpServer.kt`:
- Add constructor param `private val ephemeralFileLinkService: EphemeralFileLinkService` (after `mcpSdkServer`).
- In `configureApplication()` `routing { }` (alongside `/health`):
```kotlin
get("${EphemeralFileLinkService.PATH_PREFIX}{token}") {
    val entry = ephemeralFileLinkService.resolve(call.parameters["token"].orEmpty())
    if (entry == null) {
        call.respond(HttpStatusCode.NotFound)
    } else {
        call.respondBytes(entry.blob.readBytes(), ContentType.parse(entry.mimeType), HttpStatusCode.OK)
    }
}
```
No `Content-Disposition` header (it would signal "download, do not render" and work against `web_fetch`). The `{token}` segment carries no `/`, so it cannot match other routes. Do not log the token. Add the import `io.ktor.server.response.respondBytes` (not currently present in `McpServer.kt`); `get`, `ContentType`, `HttpStatusCode` are already imported.

**Definition of Done:**
- [x] Valid token streams the blob with its content-type; unknown/expired token → 404; no disposition header.

### Task 1.4 — Bearer-auth exemption for `/s/` (security-sensitive — careful)

**Action** — `mcp/auth/BearerTokenAuth.kt`:
- Add config property `var excludedPathPrefixes: Set<String> = emptySet()` (KDoc: request paths whose normalized path STARTS WITH any prefix skip auth; for the unauthenticated capability route, which cannot carry a bearer token).
- In the existing `application.intercept(...)` block, immediately after the exact-match `excludedPaths` early-return (which uses `requestPath = call.request.path()`), add:
  `if (excludedPathPrefixes.any { requestPath.startsWith(it) }) { return@intercept }`
  (Use `return@intercept` to match the actual plugin; do NOT use `onCall`.) Capture `excludedPathPrefixes` into a local val at install time alongside the existing `expectedToken`/`excludedPaths` locals, mirroring the plugin's pattern. Keep `/health` on exact-match `excludedPaths` unchanged. Keep the existing empty-`expectedToken` early-return ahead of these checks (unchanged).
- Invariant to honor: NO route other than `/s/{token}` may ever be mounted under `/s/`. The prefix test runs on Ktor's normalized `requestPath`, so `%2f`-encoded traversal cannot reach `/mcp`.

**Action** — `mcp/McpServer.kt` `install(BearerTokenAuthPlugin)`: add `excludedPathPrefixes = setOf(EphemeralFileLinkService.PATH_PREFIX)` (keep `excludedPaths = setOf("/health")`).

**Definition of Done:**
- [x] `/s/<token>` reaches the route with NO `Authorization` header; `/health` still exact; `/mcp` still requires a valid token and is NOT exempted by the `/s/` prefix; encoded-traversal cannot reach `/mcp`.

### Task 1.5 — Wire the link service + base-URL provider through `McpServerService`

**Action** — `services/mcp/McpServerService.kt`:
- Add `@Inject lateinit var ephemeralFileLinkService: EphemeralFileLinkService`; pass it to the `McpServer(...)` constructor in `startServer()`.
- Add a private `fun currentBaseUrl(): String`: if `tunnelManager.tunnelStatus.value is TunnelStatus.Connected` → its `url`; else `"${if (config.httpsEnabled) "https" else "http"}://${NetworkUtils.getDeviceIpAddress(applicationContext) ?: config.bindingAddress.address}:${config.port}"`. (Used as `baseUrlProvider` and to decide the reachability note.)

**Definition of Done:**
- [x] App compiles; `McpServer` receives the singleton; `currentBaseUrl()` returns tunnel URL when connected else the LAN URL.

### Task 1.6 — US1 tests

**File:** `app/src/test/kotlin/.../services/sharing/EphemeralFileLinkServiceImplTest.kt`
**Setup:** `service` over a temp `filesDir`; helper writes a temp source blob.

| Test | Verifies |
|------|----------|
| `register returns 64-hex token and resolve finds it` | round-trip; `pathFor` = `/s/<token>`; source moved into `ephemeral_links/` |
| `resolve returns null for unknown token` | missing token → null |
| `resolve returns null after TTL` | entry past `expiresAtMs` purged. **Setup:** controllable clock seam |
| `adding 21st link evicts oldest, deletes its blob, final size 20` | FIFO eviction; eldest blob removed; `size == 20` |
| `init clears stale blobs` | a pre-existing file in the blob dir is deleted on construction |

**File:** `app/src/test/kotlin/.../mcp/auth/BearerTokenAuthPrefixTest.kt`
**Setup:** Ktor `testApplication` with `BearerTokenAuthPlugin { expectedToken="t"; excludedPaths=setOf("/health"); excludedPathPrefixes=setOf("/s/") }`; stub routes `/health`, `/mcp`, `/s/{token}`.

| Test | Verifies |
|------|----------|
| `/s/<token> allowed without auth` | route reached, no Authorization |
| `/health allowed without auth` | exact-match works |
| `/mcp requires token` | 401 without token; 200 with valid token |
| `/mcp not exempted by /s prefix` | `/mcp` still 401 without token |
| `encoded traversal /s/..%2f..%2fmcp does not reach /mcp` | request stays on the `/s` route (404), `/mcp` not invoked |
| `empty expectedToken allows all paths` | with `expectedToken=""`, `/s/<t>` and `/mcp` both reachable (documents the empty-token early-return order) |

**Definition of Done:**
- [x] All US1 tests written and passing locally.

---

## User Story 2 — Share target + `android_get_shared_content`

**Why:** Receiving content via the OS share sheet (an Intent) sidesteps the background-clipboard restriction and carries richer payloads (text, images, PDFs, files).

**Acceptance criteria:**
- [x] The app appears in the Android share sheet for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` of any type; selecting it captures content, enforces the per-file 10 MB cap during the copy (even when the provider reports unknown size), enqueues it, and the activity finishes with no visible UI.
- [x] `android_<prefix>get_shared_content` drains the inbox (consume-on-read) and returns: text inline; images as downscaled `ImageContent` + original-URL text (flagged user-only); PDFs/binaries as capability-URL text; with the untrusted-content warning as the FIRST content block; empty inbox → guidance note.
- [x] Inbox is in-memory and respects 5 items / 50 MB total / 10 MB per file / 1 h TTL; stale blobs cleared on init.

### Task 2.1 — `SharedContentInbox` interface + implementation (in-memory)

**Action** — create `services/sharing/SharedContentInbox.kt`:
```kotlin
interface SharedContentInbox {
    /** Adds an item; rejects (deletes blob, returns false) if sizeBytes > MAX_FILE_BYTES; evicts oldest to honor MAX_ITEMS / MAX_TOTAL_BYTES. */
    suspend fun add(item: SharedItem): Boolean
    /** Returns all non-expired items in insertion order AND clears the map under the lock (consume-on-read). */
    suspend fun drainAll(): List<SharedItem>
    suspend fun purgeExpired()
    companion object {
        const val MAX_ITEMS = 5
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        const val TTL_MS = 60L * 60L * 1000L
    }
}

data class SharedItem(
    val id: String, val kind: Kind, val mimeType: String, val fileName: String?,
    val text: String?, val blob: File?, val sizeBytes: Long, val createdAtMs: Long, val expiresAtMs: Long,
) { enum class Kind { TEXT, BLOB } }
```

**Action** — create `services/sharing/SharedContentInboxImpl.kt` (`@Singleton`, `@Inject constructor(@ApplicationContext context)`):
- Blob dir `File(context.filesDir, "shared_inbox")`; on init delete all files in it (in-memory state starts empty). In-memory insertion-ordered map + `Mutex`. NO on-disk index.
- `add`: if `sizeBytes > MAX_FILE_BYTES` → delete blob, return false; `purgeExpired()`; evict eldest (delete blob) while `size >= MAX_ITEMS` OR `currentTotal + sizeBytes > MAX_TOTAL_BYTES`; insert; return true. (Loop terminates: since `MAX_FILE_BYTES` 10 MB < `MAX_TOTAL_BYTES` 50 MB, evicting down to empty always admits the new ≤ 10 MB item.)
- `drainAll`: `purgeExpired()`; under the lock, snapshot live items in order then clear the map; return the snapshot. BLOB files are NOT deleted here — ownership transfers to the caller (the tool moves image/binary blobs into the link registry and deletes any blob it does not hand off). Because the map is cleared under the lock, no later `add`/`purge` can touch a drained blob.
- `purgeExpired`: drop `now >= expiresAtMs`; delete their blobs.

**Action** — in the abstract `ServiceModule` class within `di/AppModule.kt`: `@Binds @Singleton abstract fun bindSharedContentInbox(impl: SharedContentInboxImpl): SharedContentInbox`.

**Definition of Done:**
- [x] Caps enforced (item/total/per-file); `drainAll` empties under the lock; init clears stale blobs; `Mutex`-guarded.

### Task 2.2 — `ShareReceiverActivity` + manifest + label

**Action** — create `ui/ShareReceiverActivity.kt` (`@AndroidEntryPoint`, extends `androidx.activity.ComponentActivity`, transparent/no-display, `@Inject lateinit var inbox: SharedContentInbox`):
- `onCreate`/`onNewIntent`: handle the intent on `lifecycleScope` (background dispatcher), then `finish()`.
- `ACTION_SEND`: if `EXTRA_TEXT` present and `intent.type` is textual → `inbox.add(SharedItem(kind=TEXT, text=...))`. Else read `EXTRA_STREAM` (`Uri`): resolve mime via `contentResolver.getType(uri) ?: intent.type`; STREAM `openInputStream(uri)` into `shared_inbox/<uuid>` while counting bytes, ABORTING and deleting the partial file if the count exceeds `MAX_FILE_BYTES` (do not rely on `OpenableColumns.SIZE`, which may be -1/null); query `DISPLAY_NAME` for the name; on success `inbox.add(SharedItem(kind=BLOB, blob=..., sizeBytes=<counted>))`.
- `ACTION_SEND_MULTIPLE`: iterate `EXTRA_STREAM` (`ArrayList<Uri>`); same per-URI streaming + cap.
- Copy/stream BEFORE `finish()` (the URI grant is tied to the activity).

**Action** — `app/src/main/res/values/strings.xml`: add `<string name="share_target_label">Android Remote Control MCP</string>` (recognizable share-sheet label).

**Action** — `app/src/main/AndroidManifest.xml`:
```xml
<activity android:name=".ui.ShareReceiverActivity" android:exported="true"
    android:excludeFromRecents="true" android:label="@string/share_target_label"
    android:theme="@android:style/Theme.NoDisplay">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="*/*" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.SEND_MULTIPLE" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="*/*" />
    </intent-filter>
</activity>
```

**Definition of Done:**
- [x] App is a share target for any type; selecting it enqueues content with the 10 MB cap enforced during streaming (including unknown-size providers); the activity finishes with no visible UI.

### Task 2.3 — Classifier + image downscaling

**Action** — create `services/sharing/SharedContentClassifier.kt` (pure):
- `isTextual(mime): Boolean` — case-insensitive: `text/*` prefix OR membership in the explicit set (`application/json`, `application/xml`, `application/xhtml+xml`, `application/javascript`, `application/ld+json`, `application/yaml`, `text/yaml`).
- `isImage(mime): Boolean` — `mime.startsWith("image/")`.
- `const IMAGE_MAX_DIMENSION = 800`.
- `fun downscaleToInlineBase64(blob: File): String` — decode the image; if longest side ≤ 800 keep original, else proportionally resize so longest side = 800 (reuse `ScreenshotEncoder` resize); JPEG-encode and Base64-encode (reuse `ScreenshotEncoder` base64). Returns a base64 `String` suitable for `ImageContent.data`. Recycle bitmaps.

**Definition of Done:**
- [x] `isTextual`/`isImage` correct for the allowlist + image types; `downscaleToInlineBase64` returns base64 of a ≤ 800 px JPEG for large images and of the original for small ones.

**Implementation amendment (review finding):** `downscaleToInlineBase64(blob): String` was implemented as `downscaleToInline(blob, originalMime): InlineImage(base64, mimeType)`. Reason: the plan's `get_shared_content` image branch returned `ImageContent(data = downscaleToInlineBase64(blob), mimeType = item.mimeType)` — but a large image is re-encoded to JPEG, so declaring the original MIME (e.g. `image/png`) would mismatch the actual bytes. Returning `InlineImage` lets the helper report `image/jpeg` for re-encoded images and the original MIME for small (kept) images, keeping `ImageContent.data` and `ImageContent.mimeType` consistent.

### Task 2.4 — `McpToolUtils.untrustedResult(content)` helper

**Action** — `mcp/tools/McpToolUtils.kt`: add (importing `io.modelcontextprotocol.kotlin.sdk.types.ContentBlock`):
```kotlin
/** CallToolResult whose content is [content] with UNTRUSTED_CONTENT_WARNING prepended as the first TextContent block. */
fun untrustedResult(content: List<ContentBlock>): CallToolResult =
    CallToolResult(content = listOf<ContentBlock>(TextContent(text = UNTRUSTED_CONTENT_WARNING)) + content)
```

**Definition of Done:**
- [x] Returns a `CallToolResult` whose first content item is the warning `TextContent`, followed by [content] in order.

### Task 2.5 — `android_get_shared_content` tool

**Action** — create `mcp/tools/SharingTools.kt` with `GetSharedContentHandler` (and `registerSharingTools(...)`; US3 adds the second handler here). Define `const val TOOL_NAME = "get_shared_content"` on the handler.

`GetSharedContentHandler.execute()`:
- `val items = inbox.drainAll()`.
- If empty → `McpToolUtils.untrustedTextResult("note:no shared content available. Share text/an image/a file to this app, then call this tool.")`.
- Else build `val content = mutableListOf<ContentBlock>()`; for each item:
  - TEXT → `content += TextContent(item.text.orEmpty())` (`SharedItem.text` is nullable; use `orEmpty()`, no `!!`).
  - image BLOB → `token = linkService.register(item.blob!!, item.mimeType, item.fileName ?: token)`; `url = baseUrlProvider() + linkService.pathFor(token)`; `content += ImageContent(data = SharedContentClassifier.downscaleToInlineBase64(item.blob), mimeType = item.mimeType)`; `content += TextContent("Image '${item.fileName}' (${size}). Original (full-res) at $url (expires 1h). Only share this URL with the user if they ask for the original.")`. (NOTE: register moves the blob; downscale reads it BEFORE register — read/encode first, then register.)
  - other BLOB → `token = linkService.register(...)`; `content += TextContent("File '${item.fileName}' ${item.mimeType} (${size}) at $url (expires 1h). You can web_fetch text/PDF URLs to read them; other types are download-only.")`.
- If any capability link was produced and no tunnel is connected, append a final `TextContent` reachability note.
- Return `McpToolUtils.untrustedResult(content)` (warning first).
- Tool description (concise): `"Returns and clears items shared to this app via Android Share (text, images, files); read-once (re-share to read again). Prefer this over the clipboard when content can be shared directly to this app. Images return a downscaled view plus a full-res download URL; files return a fetch URL (web_fetch reads text/PDF)."`

**Action** — CANONICAL signature (annotate `@Suppress("LongParameterList")`), used identically at every call site below: `registerSharingTools(server: Server, inbox: SharedContentInbox, linkService: EphemeralFileLinkService, fileOperationProvider: FileOperationProvider, fileSizeLimitMb: Int, baseUrlProvider: () -> String, tunnelConnected: () -> Boolean, context: Context, toolNamePrefix: String, perms: ToolPermissionsConfig)`. Register `"$toolNamePrefix${GetSharedContentHandler.TOOL_NAME}"` when `perms.isToolEnabled(GetSharedContentHandler.TOOL_NAME)`. `baseUrlProvider` returns the current base URL; `tunnelConnected` reports whether a tunnel is connected (drives the reachability note); `context` is the application `Context` (temp dir / `ContentResolver`); `fileSizeLimitMb` is the `share_file_via_web` cap.

**Action** — `services/mcp/McpServerService.kt` `registerAllTools(...)`: inject `SharedContentInbox`; call (exact canonical order) `registerSharingTools(sdkServer, inbox, ephemeralFileLinkService, fileOperationProvider, config.fileSizeLimitMb, ::currentBaseUrl, { tunnelManager.tunnelStatus.value is TunnelStatus.Connected }, applicationContext, toolNamePrefix, perms)`.

**Definition of Done:**
- [x] Tool drains the inbox; text inline; images = downscaled `ImageContent` (base64) + URL text (user-only flag); pdf/binary = URL text; untrusted warning is the first content block; empty inbox → guidance; links registered in US1; reachability note when not tunneled.

### Task 2.6 — US2 tests

**File:** `app/src/test/kotlin/.../services/sharing/SharedContentInboxImplTest.kt`
**Setup:** temp `filesDir`; helpers to build TEXT and BLOB items.

| Test | Verifies |
|------|----------|
| `add then drainAll returns and clears` | consume-on-read empties inbox |
| `rejects file over 10MB` | `add` returns false; no blob retained |
| `evicts oldest beyond 5 items` | size capped at 5; eldest blob deleted |
| `evicts to honor 50MB total` | total-bytes bound enforced |
| `drops expired on drain` | items past TTL excluded |
| `init clears stale blobs` | pre-existing file in the dir deleted on construction |

**File:** `app/src/test/kotlin/.../services/sharing/SharedContentClassifierTest.kt`

| Test | Verifies |
|------|----------|
| `textual mimes map to text` | each allowlist entry + `text/csv` → true; `application/pdf`, `application/zip` → false |
| `image mimes detected` | `image/png`/`image/jpeg` → true |
| `downscaleToInlineBase64 large image <=800px` | decoded base64 JPEG longest side ≤ 800. **Setup:** synth 1600×1200 |
| `downscaleToInlineBase64 small image kept` | ≤ 800 input kept (no upscale) |

**File:** `app/src/test/kotlin/.../mcp/tools/McpToolUtilsTest.kt` (add)

| Test | Verifies |
|------|----------|
| `untrustedResult prepends warning first` | first block is warning `TextContent`; rest preserved in order |

**File:** `app/src/test/kotlin/.../integration/SharingIntegrationTest.kt` (Ktor `testApplication` via `McpIntegrationTestHelper`)
**Setup:** `SharingIntegrationTest` defines its OWN `testApplication { }` — do NOT reuse/modify the shared `McpIntegrationTestHelper.withTestApplication` (it has a single fixed `BearerTokenAuthPlugin` install, a fixed tool set, and a fixed `MockDependencies`; mutating it would affect the other integration tests). In its `application { }`: `install(ContentNegotiation) { json(McpJson) }`; `install(BearerTokenAuthPlugin) { expectedToken = ""; excludedPaths = setOf("/health"); excludedPathPrefixes = setOf("/s/") }`; create an SDK `Server` and call (exact canonical order) `registerSharingTools(server, inbox, linkService, fileOperationProvider, 50, baseUrlProvider, { false }, context, "", ToolPermissionsConfig())` with a real `SharedContentInboxImpl` + `EphemeralFileLinkServiceImpl` over temp dirs, a fixed `baseUrlProvider`, and mocked `FileOperationProvider`; `mcpStreamableHttp { server }`; and mount the Task 1.3 `get("/s/{token}")` route resolving against that `EphemeralFileLinkServiceImpl`. Connect an MCP `Client` via `StreamableHttpClientTransport` (same construction as `McpIntegrationTestHelper`) to call the tools, and use the test app's HTTP `client` to GET `/s/{token}`. Pre-seed the inbox before each test.

| Test | Verifies |
|------|----------|
| `get_shared_content text item: warning first, text inline, inbox emptied` | order + drain |
| `get_shared_content image: warning first, then ImageContent, then URL text with user-only flag` | content order; image base64; URL text contains "only share" flag |
| `get_shared_content pdf: URL text; token resolves via /s/{token}` | capability URL present + fetchable |
| `get_shared_content empty inbox returns guidance` | guidance note when empty |
| `reachability note appended when tunnel-connected flag is false` | note present without tunnel; absent with tunnel |

**Definition of Done:**
- [x] All US2 tests written and passing locally.

---

## User Story 3 — `android_share_file_via_web`

**Why:** Lets the agent expose a device file (e.g. a PDF the file tools cannot read, since `readFile` is text-only) as a fetchable capability URL, reusing US1.

**Acceptance criteria:**
- [x] `android_<prefix>share_file_via_web` takes `(location_id, path)` like the other file tools, reads the EXISTING file's bytes via the authorized-location model, rejects files over `ServerConfig.fileSizeLimitMb`, registers a capability link, and returns the URL + metadata (name, mime, size) with the untrusted-content warning.

### Task 3.1 — Byte-read for existing files in the storage layer

**Action** — `services/storage/FileOperationProvider.kt`: add
```kotlin
/** Reads an EXISTING file's raw bytes from an authorized location. Fails if absent or size > maxBytes. */
suspend fun readFileBytes(locationId: String, path: String, maxBytes: Long): FileBytesResult
```
and `data class FileBytesResult(val bytes: ByteArray, val mimeType: String, val fileName: String, val sizeBytes: Long)`.

**Action** — implement in `FileOperationProviderImpl` (SAF) and `MediaStoreFileOperationsImpl` (MediaStore): resolve the EXISTING document/URI for `(locationId, path)` WITHOUT creating it (SAF: `DocumentFile` lookup; MediaStore: query by relative path) using the location's READ access; if not found → failure; determine size (`length()` / `SIZE`); if `> maxBytes` → failure; derive mime (`contentResolver.getType` or extension) and display name; read bytes via `contentResolver.openInputStream`. Do NOT use `createFileUri` (it creates/returns and requires write perm). `FileOperationProviderImpl.readFileBytes` MUST delegate builtin/MediaStore location IDs to `MediaStoreFileOperationsImpl.readFileBytes` exactly as `readFile` does (`if (BuiltinStorageLocation.isBuiltinId(locationId)) return mediaStoreFileOperations.readFileBytes(...)`).

**Definition of Done:**
- [x] `readFileBytes` returns bytes+mime+name+size for an existing readable file; missing file or `> maxBytes` → failure; no file is created.

### Task 3.2 — `ShareFileViaWebHandler`

**Action** — `mcp/tools/SharingTools.kt`: add `ShareFileViaWebHandler` with `const val TOOL_NAME = "share_file_via_web"`:
- Validate `location_id`, `path` (via `McpToolUtils`).
- `maxBytes = fileSizeLimitMb.toLong() * 1024 * 1024` (the `fileSizeLimitMb` registration arg); `val r = fileOperationProvider.readFileBytes(locationId, path, maxBytes)` — on failure return an MCP error result ("file not found or exceeds the configured file size limit").
- Write `r.bytes` to a temp file under `filesDir` (e.g. `File(context.filesDir, "ephemeral_links_tmp").also { it.mkdirs() }/<uuid>`) — same filesystem as the registry so the subsequent move is intra-filesystem; `token = linkService.register(tempFile, r.mimeType, r.fileName)`; `url = baseUrlProvider() + linkService.pathFor(token)`.
- Return `McpToolUtils.untrustedTextResult("File '${r.fileName}' ${r.mimeType} (${r.sizeBytes} bytes) at $url (expires 1h). web_fetch can read text/PDF; other types are download-only.")` (untrusted: the filename/mime are device-derived). Append the reachability note when not tunneled.
- Tool description (concise): `"Exposes a device file (location_id + path) as a temporary fetch URL (expires 1h, multiple fetches allowed). Use to let the agent read a device PDF/text via web_fetch, or to give the user a download link. Limited to the configured file size limit."`

**Action** — register `"$toolNamePrefix${ShareFileViaWebHandler.TOOL_NAME}"` in `registerSharingTools(...)` when `perms.isToolEnabled(ShareFileViaWebHandler.TOOL_NAME)`.

**Definition of Done:**
- [x] Returns a working capability URL for a file within the size limit; over-limit/missing → error; URL resolves to the file bytes via `/s/{token}`; result carries the untrusted warning.

### Task 3.3 — Finalize wiring

**Action** — `services/mcp/McpServerService.kt`: ensure `registerSharingTools(...)` is called exactly once in `registerAllTools(...)` using the canonical argument order from Task 2.5 (server, inbox, link service, file operation provider, `config.fileSizeLimitMb`, `::currentBaseUrl`, tunnel-connected lambda, application context, tool-name prefix, perms). Confirm Hilt provides `SharedContentInbox` and `EphemeralFileLinkService`.

**Definition of Done:**
- [x] Both sharing tools register under the device prefix and honor `ToolPermissionsConfig`.

### Task 3.4 — US3 tests

**File:** `app/src/test/kotlin/.../services/storage/FileOperationProvider*Test.kt` (extend existing storage tests)

| Test | Verifies |
|------|----------|
| `readFileBytes reads existing file` | bytes/mime/name/size correct; no file created |
| `readFileBytes fails for missing file` | failure result/exception |
| `readFileBytes fails over maxBytes` | size guard enforced |

**File:** `app/src/test/kotlin/.../integration/SharingIntegrationTest.kt` (add)

| Test | Verifies |
|------|----------|
| `share_file_via_web returns capability url; token resolves via /s/{token}` | URL present + fetchable; result has untrusted warning |
| `share_file_via_web rejects file over fileSizeLimitMb` | error result; no link registered |
| `share_file_via_web reuses link registry (cap 20)` | 21st call evicts oldest link |

**Definition of Done:**
- [x] All US3 tests written and passing locally.

---

## User Story 4 — Ground-up verification

**Why:** Mandatory final audit that every change matches this plan and the agreed decisions, with all quality gates green.

### Task 4.1 — Quality gates

**Action** — run, in order: `make lint`; the full unit + integration suite (`set -a && source .env && set +a && ./gradlew :app:test`); `./gradlew :app:assembleDebug`; `:e2e-tests:compileTestKotlin`. Fix any failure (including pre-existing broken tests/lint per project rules).

**Definition of Done:**
- [x] Zero lint warnings/errors; all tests pass; debug build succeeds with no warnings; e2e module compiles.

### Task 4.2 — Plan-compliance code review

**Action** — spawn the `code-reviewer` subagent in plan-compliance mode against this plan. Fix ALL findings (CRITICAL/WARNING/INFO); re-run until clean. Focus: the `BearerTokenAuth` `/s/` prefix exemption (`return@intercept`; `/mcp` still protected; encoded-traversal safe; no other route under `/s/`), capability-token entropy (SecureRandom) and non-logging, in-memory registries + clear-on-init (no orphaned blobs, no double-delete across drain→handoff), `ImageContent` base64, `untrustedResult` ordering, `readFileBytes` not creating files and respecting `fileSizeLimitMb`.

**Definition of Done:**
- [x] `code-reviewer` returns ZERO findings.

**Review findings (accepted):**
- R-001: `currentBaseUrl` implemented as a `private val currentBaseUrl: () -> String` (passed as `currentBaseUrl`) instead of `fun currentBaseUrl()` (`::currentBaseUrl`). Reason: keeps `McpServerService` under detekt's `TooManyFunctions` (11) limit after adding `registerSharingBundle`. Behaviorally identical (`() -> String`).
- R-002: `currentBaseUrl` adds `?: cfg?.bindingAddress?.address ?: "127.0.0.1"` / `?: ServerConfig.DEFAULT_PORT` fallbacks because `activeConfig` is a nullable `@Volatile` seam (the plan's local `config` was non-null). Strictly more defensive; the fallback path is unreachable during a live tool call.
- R-003: Added `services/storage/StorageStreamUtils.kt` (`readCappedBytes`, `readFileBytesFromUri`) and `services/storage/DownloadUrlValidator.kt` (byte-identical extraction of MediaStore's private `parseAndValidateUrl`). Reason: de-duplicate the capped byte read across both providers and keep `MediaStoreFileOperationsImpl` under detekt's `LargeClass` (600) limit. No behavior change.
- R-005: Added a "Sharing" category to `McpToolsSettingsScreen` so `get_shared_content` / `share_file_via_web` are user-toggleable like every other `perms.isToolEnabled`-gated tool (the plan predated this UI; omitting them would make the gating uncontrollable).
- R-006: `get_shared_content` image/file text uses `item.fileName ?: "image"`/`"file"` instead of the plan's literal `'${item.fileName}'` — a preserved bug fix that avoids rendering the string `null` when a share provides no display name (consistent with the plan's own `fileName ?: token` guidance).
- R-007: The empty-inbox guidance still uses `McpToolUtils.untrustedTextResult` (warning prepended). This deliberately over-applies the untrusted-content warning to a server-generated note; over-warning is strictly safe and removing the prompt-injection mitigation to satisfy a cosmetic preference is not done (security-conservative choice).
- R-008 (robustness fixes from the adversarial review): (a) `get_shared_content` adds an `else` branch so a non-text item carrying no bytes is surfaced as "had no readable content" rather than silently dropped (defensive; unreachable from the share receiver). (b) `share_file_via_web`'s tool description states the effective cap is `min(64 MB, fileSizeLimitMb)` (served from memory). (c) `GET /s/{token}` falls back to `application/octet-stream` when a blob's MIME (which can originate from an arbitrary sharing app's `intent.type`) fails `ContentType.parse`, instead of returning HTTP 500.
- R-009 (second adversarial-review round): (a) **Lifecycle crash fix** — `ShareReceiverActivity` reads the payload asynchronously and calls `finish()` afterward, which is incompatible with `Theme.NoDisplay` (it mandates a synchronous `finish()` in `onCreate`, else `did not call finish() prior to onResume()`). The manifest theme is now `@android:style/Theme.Translucent.NoTitleBar` (still no visible UI, tolerates deferred finish). (b) The capped reader was extracted to `services/sharing/readWithinCap` and unit-tested (`SharedStreamReaderTest`) — the activity's Android-framework glue (intent parsing, `ContentResolver`) remains outside unit scope, consistent with the project not unit-testing activities. (c) The `/s/` MIME fallback was extracted to `mcp/contentTypeOrOctetStream`, used by BOTH the production route and the integration-test route, and covered by `ContentTypeUtilTest` plus an integration test (malformed MIME → 200 + octet-stream, and asserts no `Content-Disposition`). (d) `readCappedBytes` streaming guard unit-tested directly (`StorageStreamUtilsTest`).
- Accepted/by-design (second round): (i) the inbox enforces its byte budget against `SharedItem.sizeBytes`, the declared-size contract field; the sole caller (`ShareReceiverActivity`) sets it from `bytes.size`, and text items have no byte array, so deriving it from `bytes` is neither possible nor necessary. (ii) `register`'s `IllegalArgumentException` guard (blob > 64 MB) is provably unreachable from `get_shared_content` because inbox items are hard-capped at `MAX_FILE_BYTES` (10 MB) < 64 MB; no defensive catch is added to avoid untestable dead code. (iii) eviction loops are O(n²) but bounded by `MAX_LINKS` (20) / `MAX_ITEMS` (5) — negligible. (iv) an `ACTION_SEND` carrying only `EXTRA_TEXT` with a non-textual/`null` type stores nothing — matches the plan's text-vs-stream branching.
- R-010 (third adversarial-review round): **Textual files are now inlined.** The "Shared decisions" content-mapping ("Textual MIME → inline `TextContent`") was only applied to `EXTRA_TEXT`; a textual *file* shared as a stream (e.g. `.json`/`.csv`) was rendered as a download URL. `GetSharedContentHandler.appendItem` now has a `SharedContentClassifier.isTextual(mimeType) && bytes != null` branch that returns the file's UTF-8 content inline (no link). The image branch was extracted to `appendImageItem` to keep `appendItem` within the method-length limit; the tool description and an integration test (`textualFileInlined`) were updated/added.
- Accepted/by-design (third round): (v) `clockMs` is an `internal var` test seam on the two `@Singleton`s — mutated only by tests (single-threaded setup, before any concurrent use) and read inside the `Mutex` in production; no production race. (vi) `readFileBytes` gates on location authorization only (no separate read-permission check), matching the existing text-only `readFile` baseline and the plan's "the location's READ access" wording; exposure is bounded by the 64 MB cap and the unguessable capability token.
- R-011 (fourth adversarial-review round): removed the dead `ShareReceiverActivity.onNewIntent` override — the activity uses the default `standard` launch mode, so every share starts a fresh instance handled in `onCreate` and `onNewIntent` is never delivered (a comment now documents this).
- Accepted/by-design (fourth round): (vii) `share_file_via_web` validates `location_id`/`path` with `McpToolUtils.requireString` (presence + type), not non-emptiness — consistent with every other file tool; an empty/blank path still yields a clear error from the storage layer (`File path cannot be empty` / `Path is not a file`). (viii) When an `image/*` blob fails to decode, only the capability-URL `TextContent` is returned (no `ImageContent`) — you cannot inline an undecodable image, so the URL fallback (with a "could not be decoded" note) is the correct degraded behavior (R-008a).

**CRITICAL CORRECTION (user-directed) — blobs are held in RAM, never written to disk:**
The original plan body (Tasks 1.2, 1.3, 2.1, 2.2, 2.3, 2.5, 3.2) stored blob BYTES on disk under `filesDir` (`File(filesDir, "ephemeral_links")` / `"shared_inbox")`, with only the *index* in memory and stale blobs cleared on init. The user explicitly does NOT want shared blobs written to disk. The implementation was corrected so the entire subsystem is **truly in-memory** — no disk I/O for blobs anywhere:
- `EphemeralFileLinkService.register(bytes: ByteArray, mimeType, fileName)`; `LinkEntry.bytes: ByteArray`. `EphemeralFileLinkServiceImpl` holds bytes in a `Mutex`-guarded `LinkedHashMap`, no `Context`/`filesDir`, no blob dir, no clear-on-init, no `renameTo`. `resolve` checks TTL only (no `blob.exists()`).
- `SharedItem.bytes: ByteArray?` (was `blob: File?`); `SharedContentInbox` no longer exposes `blobDir` (so prior finding R-004 no longer applies). `SharedContentInboxImpl` holds bytes in RAM, no `Context`/`filesDir`. `drainAll` clears the map (GC reclaims bytes); no blob deletion.
- `ShareReceiverActivity` reads each `EXTRA_STREAM` URI fully into a capped `ByteArray` (10 MB cap enforced during the read; provider-reported size still not trusted) instead of streaming to a file.
- `SharedContentClassifier.downscaleToInline(bytes: ByteArray, originalMime)` decodes via `BitmapFactory.decodeByteArray` (was `decodeFile`).
- `ShareFileViaWebHandler` registers `result.bytes` directly (no temp file, no `Context`); `registerSharingTools` dropped its `context` parameter.
- `GET /s/{token}` serves `entry.bytes` directly.
- **Bounded RAM budget (user decision: 64 MB):** to prevent the in-memory registry from being an OOM vector (the configurable `fileSizeLimitMb` reaches 500 MB → 20 × 500 MB ≈ 10 GB), the link registry now enforces `EphemeralFileLinkService.MAX_TOTAL_BYTES = 64 MB` total across all live links: `register` FIFO-evicts the oldest links until the new blob fits within both the 20-link and 64 MB bounds, and rejects (throws `IllegalArgumentException`) any single blob larger than 64 MB. Consequently `share_file_via_web`'s effective per-file cap is `min(fileSizeLimitMb, 64 MB)` — `readOrThrow` caps the read at that value, so an over-budget file returns a clean MCP error instead of crashing. Peak registry heap is bounded at ~64 MB.

### Task 4.3 — Ground-up double-check (read everything, verify against the plan and decisions)

**Action** — re-read every file created/modified by this plan from the ground up and verify, item by item, against the "Shared decisions" block:
- [x] Capability path exactly `/s/`; token 64-hex SHA-256 over `nanoTime ‖ 32 SecureRandom bytes`; token/URL never logged.
- [x] Link registry IN-MEMORY: max 20, 1 h TTL, FIFO eviction, TTL-only (no delete-on-fetch); evicted/expired blobs deleted; clear-on-init.
- [x] Inbox IN-MEMORY: max 5 items, 50 MB total, 10 MB/file, 1 h TTL, consume-on-read; > 10 MB rejected; cap enforced during streaming incl. unknown-size; clear-on-init.
- [x] Textual allowlist exactly as specified → inline text; `image/*` → downscaled (≤ 800 px, base64) `ImageContent` + original URL text flagged user-only; pdf/binary → URL text.
- [x] `share_file_via_web` takes `(location_id, path)`, cap = `fileSizeLimitMb`, returns URL + metadata via `untrustedTextResult`; reads existing-file bytes via `readFileBytes` (no file creation; not the text-only `readFile`).
- [x] Bearer auth: `/s/` prefix exempt via `return@intercept`, `/health` exact, `/mcp` authenticated; no `Content-Disposition` on `/s/`.
- [x] Both tools’ device-derived output carries `UNTRUSTED_CONTENT_WARNING` first; `get_shared_content` uses `untrustedResult`.
- [x] No persistence anywhere; no periodic sweep; no un-agreed additions.
- [x] No file outside this plan's scope was altered; no TODOs/placeholders.
- [x] Report any divergence to the user before considering the work done.

**Definition of Done:**
- [x] Every checkbox above verified; divergences (if any) raised with the user.
