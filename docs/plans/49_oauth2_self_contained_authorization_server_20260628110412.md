<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 49 — Self-Contained OAuth 2.1 Authorization Server (Claude.ai custom connectors) + request-derived base URL

**Why:** Claude.ai / Claude Desktop custom connectors speak ONLY OAuth 2.1 + PKCE (no static-token field), so to be usable as a connector the app must BE its own OAuth Authorization Server. Because the app is simultaneously Authorization Server and Resource Server, tokens are HS256-signed with a device-held secret and validated locally (no JWKS). The same request-host derivation that OAuth needs also fixes the existing share-content URLs, which must be reachable by the agent.

**Empirical grounding (DO NOT re-derive — verified via spike against real Claude.ai on 2026-06-28; see memory `oauth2-self-contained-as-design`):**
- Claude's `/register` body: `{redirect_uris:["https://claude.ai/api/mcp/auth_callback"], token_endpoint_auth_method:"none", grant_types:["authorization_code","refresh_token"], response_types:["code"], scope:"mcp", client_name:"Claude", application_type:"web"}`. NO `logo_uri`.
- Claude reads `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource/mcp"` and fetches that exact path-suffixed PRM; fetches bare `/.well-known/oauth-authorization-server`.
- `/authorize` is a browser full-page GET (user's IP) with PKCE `S256`, `state`, `resource=https://HOST/mcp`. `/token` (backend) sends `code_verifier` + `redirect_uri` + `resource`. `/mcp` accepts plain `application/json` (no SSE). protocolVersion `2025-11-25`.

**Locked decisions:** java-jwt `4.5.2`; access TTL 24h / refresh TTL 90d / auth-code TTL 60s; HS256 secret generated once in DataStore; client registry persisted with cap 20 (evict oldest-by-last-used); 2-digit match code, 5-minute approval window; heads-up notification → in-app approval screen; dual-accept (OAuth JWT OR static bearer), open when both off; dedicated Access settings screen; UI confirm dialog on dangerous transition (NOT adb); public base URL auto-detected from the request PLUS an optional `publicUrlOverride` setting (empty = auto; applies to OAuth metadata AND share links; adb-configurable); docs updated.

**Conventions:** New OAuth code lives under `mcp/oauth/`. New settings (auth flags, signing secret, public-URL override) reuse the existing settings `DataStore<Preferences>` via `SettingsRepository`; the OAuth client registry uses a DEDICATED Preferences DataStore (name `oauth_clients`) via `OAuthClientRepository` (per the agreed design — separate store, not the settings store). Anti-prompt-injection warnings are NOT applicable (no new device-content MCP tools are added). Per the plan workflow, linting/tests/build run ONLY in Story 7. Never log tokens, codes, secrets, verifiers, or full URLs.

---

## User Story 1 — Request-derived base URL helper (+ optional override) + share-content URL fix

**Why:** Every absolute URL the server hands out (OAuth metadata, share links) must use the host the client actually connected on (`X-Forwarded-Host`/`Host` + `X-Forwarded-Proto`), or the user-set override, so it is reachable across all topologies (cloudflared, ngrok, Tailscale Funnel, router/DDNS). The MCP SDK 0.8.3 dispatches tool handlers INLINE on the Ktor call coroutine (verified: `StreamableHttpServerTransport.handlePostRequest` → `_onMessage` → `Protocol.onRequest` `handler(request, RequestHandlerExtra())` awaited inline, no scope hand-off), so a coroutine-context element set around `handlePostRequest` propagates to tool handlers.

**Acceptance criteria:**
- [x] `deriveBaseUrl(call)` returns a normalized `scheme://host[:port]` from forwarded/Host headers; `effectiveBaseUrl(call, override)` returns the normalized override when non-empty, else `deriveBaseUrl(call)`.
- [x] Share-content tool URLs (`get_shared_content`, `share_file_via_web`) reflect `X-Forwarded-Host`/`X-Forwarded-Proto` (or the override), falling back to the existing provider when no request context is present.
- [x] Existing share-content behavior is preserved when forwarded headers are absent (unit tests / non-HTTP callers).

### Task 1.1 — Base-URL derivation helpers + coroutine-context element
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/RequestBaseUrl.kt`
  - `fun deriveBaseUrl(call: ApplicationCall): String` — scheme = first comma-separated value of the `X-Forwarded-Proto` header, else `call.request.origin.scheme`; hostport = first value of `X-Forwarded-Host` header, else `call.request.host()` + (`:` + `call.request.port()` when non-default). Pass through `normalizeBaseUrl`.
  - `fun normalizeBaseUrl(raw: String): String` — lowercase scheme + host; strip a trailing `/`; omit the port when it equals the scheme default (80 http / 443 https). Returns `"$scheme://$hostport"`.
  - `fun effectiveBaseUrl(call: ApplicationCall, override: String): String = override.trim().ifEmpty { null }?.let { normalizeBaseUrl(it) } ?: deriveBaseUrl(call)` — the override (when non-empty) wins (also acts as a hostname pin).
  - `class RequestBaseUrlElement(val baseUrl: String) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<RequestBaseUrlElement> }`
  - `suspend fun currentRequestBaseUrl(fallback: suspend () -> String): String = coroutineContext[RequestBaseUrlElement]?.baseUrl ?: fallback()`
  - `fun canonicalResource(baseUrl: String): String = "$baseUrl/mcp"`
  - Imports: `io.ktor.server.plugins.origin` (for `call.request.origin.scheme`), `io.ktor.server.request.host`, `io.ktor.server.request.port`, `kotlin.coroutines.coroutineContext`, `kotlin.coroutines.CoroutineContext`, `kotlin.coroutines.AbstractCoroutineContextElement`.
  - **Security rationale (forwarded-header trust) — by design, not a limitation:** the derivation trusts `X-Forwarded-Host`/`-Proto` and these are client-settable, but this is safe because the response is PER-CONNECTION — a spoofed host only changes the response sent back to the spoofer (no cross-client/cached poisoning): (1) OAuth metadata and `aud` are returned to whoever made the request; a token minted with a spoofed `aud` is REJECTED at the real `/mcp` (aud-binding), so spoofing is self-defeating; (2) the OAuth path requires a tunnel, which IS the trusted proxy that sets these headers; (3) share-content URLs are returned to the calling agent over that same connection (the agent reaches the server on the real host via the tunnel — a separate LAN attacker is not the agent); (4) `publicUrlOverride`, when set, pins the host and ignores forwarded headers entirely — recommended for any deployment exposed on `0.0.0.0` without a trusted proxy. This trust model is the agreed design (auto-detect + override pin).
- [x] **DoD:** Pure functions, no Android deps beyond Ktor `ApplicationCall`; compiles.

### Task 1.2 — Propagate the base URL into MCP tool dispatch
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpStreamableHttpExtension.kt`:
  - Add a parameter to the extension: `fun Application.mcpStreamableHttp(publicUrlOverride: String = "", block: () -> Server)` (default `""` keeps callers that don't pass it working).
  - Wrap the dispatch at the existing call site (`transport.handlePostRequest(null, call)`):
    ```kotlin
    withContext(RequestBaseUrlElement(effectiveBaseUrl(call, publicUrlOverride))) {
        transport.handlePostRequest(null, call)
    }
    ```
  - Add imports `kotlinx.coroutines.withContext`, `…mcp.RequestBaseUrlElement`, `…mcp.effectiveBaseUrl`.
- [x] **DoD:** Only the dispatch line is wrapped; session-creation logic unchanged; existing no-arg call sites compile via the default.

### Task 1.3 — Share tool uses request-derived base URL
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SharingTools.kt` — at every URL construction site (the 3 `baseUrlProvider()` usages in `GetSharedContentHandler` and `ShareFileViaWebHandler`), replace `baseUrlProvider()` with `currentRequestBaseUrl { baseUrlProvider() }` (handlers are `suspend`). Keep the `baseUrlProvider: () -> String` constructor params as the fallback; wrap the fallback to adapt `() -> String` to `suspend () -> String`.
- [x] **DoD:** Share URLs prefer the request-context base URL; fallback preserved; no signature change to `registerSharingTools` callers.

### Task 1.4 — Tests (Story 1)
- [x] **Action:** create `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/RequestBaseUrlTest.kt`

  **Setup:** mock `ApplicationCall`/`ApplicationRequest` headers via MockK (`call.request.headers`, `origin.scheme`, `host()`, `port()`).

  | Test | Verifies |
  |------|----------|
  | `uses X-Forwarded-Host and X-Forwarded-Proto when present` | `https://tunnel.example/...` derived from forwarded headers |
  | `falls back to Host header and origin scheme` | No forwarded headers → uses `Host` + listener scheme |
  | `omits default port` | `:443` (https) / `:80` (http) stripped; non-default port kept |
  | `lowercases scheme and host and strips trailing slash` | Normalization per RFC examples |
  | `takes first value of comma-separated forwarded header` | `X-Forwarded-Host: a, b` → `a` |
  | `effectiveBaseUrl returns normalized override when set` | Non-empty override wins over forwarded headers |
  | `effectiveBaseUrl falls back to derive when override blank` | Empty/whitespace override → `deriveBaseUrl` |
  | `currentRequestBaseUrl returns element value, else fallback` | Context element read; fallback used when absent |
  | `context element survives an inner withContext(Dispatchers.IO)` | Install `RequestBaseUrlElement`, then read `currentRequestBaseUrl` from inside a nested `withContext(Dispatchers.IO)` — value preserved (proves the propagation the share-URL/OAuth fix relies on) |

- [x] **Action:** add to the integration suite `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/SharingIntegrationTest.kt`

  | Test | Verifies |
  |------|----------|
  | `share_file_via_web URL reflects X-Forwarded-Host and -Proto` | Tool response URL host/scheme = forwarded headers |
  | `share URL falls back to provider when no forwarded headers` | Default behavior unchanged |

  Note: `SharingIntegrationTest.runSharingApp` currently builds `StreamableHttpClientTransport(client, url="/mcp")` with NO `requestBuilder`, so it cannot set `X-Forwarded-Host`/`-Proto`; it MUST be parameterized to inject request headers via the transport `requestBuilder` for these tests.
- [x] **DoD:** New tests defined; existing sharing tests still valid (no behavior regression).

---

## User Story 2 — Auth-model refactor: explicit `oauthEnabled` + `bearerTokenEnabled`, signing secret, public-URL override

**Why:** Replace the implicit "empty bearer value = disabled" semantics with explicit independent toggles, enabling dual-accept and a clear "no auth when both off" state, all adb-configurable. Add the HS256 signing secret accessor and the optional public-URL override.

**Acceptance criteria:**
- [x] `ServerConfig` exposes `oauthEnabled` (default false), `bearerTokenEnabled` (default true), `publicUrlOverride` (default ""); `bearerToken` remains the value.
- [x] One-time migration preserves current behavior: existing non-empty token → bearer enabled; previously-cleared token → bearer disabled; fresh install → bearer enabled + auto-generated token. Migration runs ONCE (idempotent; never overrides a later explicit toggle).
- [x] Disabling bearer keeps the value; enabling bearer with empty value auto-generates one.
- [x] HS256 signing secret is generated once (SecureRandom) and persisted.
- [x] `oauth_enabled`, `bearer_token_enabled`, `public_url_override` are settable via adb.

### Task 2.1 — `ServerConfig` fields
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt` — add `val oauthEnabled: Boolean = false`, `val bearerTokenEnabled: Boolean = true`, and `val publicUrlOverride: String = ""` — insert all three immediately BEFORE the existing trailing `toolPermissionsConfig` field (do not disturb it). Update KDoc.
- [x] **DoD:** Compiles; defaults set.

### Task 2.2 — `SettingsRepository` interface additions
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`:
  - `suspend fun updateOauthEnabled(enabled: Boolean)`
  - `suspend fun updateBearerTokenEnabled(enabled: Boolean)` (KDoc: enabling with empty value auto-generates a token; disabling preserves the value)
  - `suspend fun updatePublicUrlOverride(url: String)`
  - `fun validatePublicUrlOverride(url: String): Result<String>` (KDoc: UNLIKE `validateEndpointUrl` — which rejects blank — empty IS valid here and means auto-detect → `Result.success("")`; otherwise apply the same http/https protocol check as `validateEndpointUrl`. Non-suspending pure validation.)
  - `suspend fun ensureAuthModelMigrated()` (KDoc: runs the one-time bearer-enabled migration AND the bearer-token auto-generation; idempotent; MUST be invoked before the auth model is consumed by the server or UI)
  - `suspend fun getOrCreateJwtSigningSecret(): String` (KDoc: returns a stable base64url secret, generated once on first read)
- [x] **DoD:** Interface compiles.

### Task 2.3 — `SettingsRepositoryImpl` — keys, mapping, migration, override, secret
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`:
  - Add keys: `OAUTH_ENABLED_KEY = booleanPreferencesKey("oauth_enabled")`, `BEARER_TOKEN_ENABLED_KEY = booleanPreferencesKey("bearer_token_enabled")`, `BEARER_TOKEN_ENABLED_INITIALIZED_KEY = booleanPreferencesKey("bearer_token_enabled_initialized")`, `PUBLIC_URL_OVERRIDE_KEY = stringPreferencesKey("public_url_override")`, `JWT_SIGNING_SECRET_KEY = stringPreferencesKey("jwt_signing_secret")`.
  - In `mapPreferencesToServerConfig`: `oauthEnabled = prefs[OAUTH_ENABLED_KEY] ?: false`, `bearerTokenEnabled = prefs[BEARER_TOKEN_ENABLED_KEY] ?: true`, `publicUrlOverride = prefs[PUBLIC_URL_OVERRIDE_KEY] ?: ""`.
  - Extract the migration + token-init into `ensureAuthModelMigrated()` (a single `dataStore.edit`). EXPLICITLY DELETE the existing inline `dataStore.edit { … BEARER_TOKEN_INITIALIZED_KEY … }` block currently in `getServerConfig()` and replace the whole `getServerConfig()` body with exactly `ensureAuthModelMigrated(); return mapPreferencesToServerConfig(dataStore.data.first())` — do NOT leave the old inline block in place (that would double-run token-init). (Moving the token-init into `ensureAuthModelMigrated()` preserves current behavior while making the migration callable eagerly — see Task 4.5 + the `McpApplication` action below — so a previously-cleared-token user is NOT regressed into a token-required state when the server reads the `serverConfig` Flow before any `getServerConfig` consumer runs.)
    ```kotlin
    override suspend fun ensureAuthModelMigrated() {
        dataStore.edit { prefs ->
            // One-time bearer-enabled migration (idempotent; guarded).
            if (prefs[BEARER_TOKEN_ENABLED_INITIALIZED_KEY] != true) {
                val wasInitialized = prefs[BEARER_TOKEN_INITIALIZED_KEY] == true
                val hadToken = !prefs[BEARER_TOKEN_KEY].isNullOrEmpty()
                prefs[BEARER_TOKEN_ENABLED_KEY] = if (wasInitialized) hadToken else true
                prefs[BEARER_TOKEN_ENABLED_INITIALIZED_KEY] = true
            }
            // Bearer-token auto-generation: only when bearer is enabled and empty.
            if (prefs[BEARER_TOKEN_INITIALIZED_KEY] != true) {
                if (prefs[BEARER_TOKEN_ENABLED_KEY] == true && prefs[BEARER_TOKEN_KEY].isNullOrEmpty()) {
                    prefs[BEARER_TOKEN_KEY] = generateTokenString()
                }
                prefs[BEARER_TOKEN_INITIALIZED_KEY] = true
            }
        }
    }
    ```
  - `updateOauthEnabled`: `dataStore.edit { it[OAUTH_ENABLED_KEY] = enabled }`.
  - `updateBearerTokenEnabled`:
    ```kotlin
    dataStore.edit { prefs ->
        prefs[BEARER_TOKEN_ENABLED_KEY] = enabled
        if (enabled && prefs[BEARER_TOKEN_KEY].isNullOrEmpty()) {
            prefs[BEARER_TOKEN_KEY] = generateTokenString()
        }
    }
    ```
  - `updatePublicUrlOverride`: `dataStore.edit { it[PUBLIC_URL_OVERRIDE_KEY] = url }`.
  - `validatePublicUrlOverride`: empty → `Result.success("")`; else parse via `java.net.URL`, require protocol `http`/`https`, else `Result.failure(IllegalArgumentException(...))` (mirror existing `validateEndpointUrl`).
  - `getOrCreateJwtSigningSecret` (read-FIRST to avoid a write transaction on every call; use `java.util.Base64` — pure JVM, no Android stub in tests):
    ```kotlin
    dataStore.data.first()[JWT_SIGNING_SECRET_KEY]?.takeIf { it.isNotEmpty() }?.let { return it }
    dataStore.edit { prefs ->
        if (prefs[JWT_SIGNING_SECRET_KEY].isNullOrEmpty()) {
            val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            prefs[JWT_SIGNING_SECRET_KEY] = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        }
    }
    return dataStore.data.first()[JWT_SIGNING_SECRET_KEY]!! // read-AFTER-edit: key is guaranteed present, so !! is always safe
    ```
    (The `JwtTokenServiceImpl` additionally memoizes the resulting `Algorithm`, so this accessor is effectively called once per process.)
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/McpApplication.kt` — add `@Inject lateinit var settingsRepository: SettingsRepository` and, in `onCreate()`, `CoroutineScope(Dispatchers.IO).launch { settingsRepository.ensureAuthModelMigrated() }` so the UI Flow reflects the migrated auth model promptly at app startup (idempotent; the server start path additionally guarantees it via `getServerConfig()` in Task 4.5).
- [x] **DoD:** Migration runs once and is idempotent; runs before the server or UI consume the auth model; fresh install secure-by-default; override persisted/validated; secret stable across reads and JVM-test-safe.

### Task 2.4 — adb extras
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandler.kt`:
  - Add `internal const val EXTRA_OAUTH_ENABLED = "oauth_enabled"`, `EXTRA_BEARER_TOKEN_ENABLED = "bearer_token_enabled"`, `EXTRA_PUBLIC_URL_OVERRIDE = "public_url_override"`.
  - Add `applyOauthEnabled` / `applyBearerTokenEnabled` (`hasExtra → getBooleanExtra → settingsRepository.updateX`; log applied value; when `bearer_token_enabled=false` log a warning that the server may be unauthenticated) and `applyPublicUrlOverride` (`getStringExtra` → `validatePublicUrlOverride().fold(...)` → `updatePublicUrlOverride`; ignore+log invalid). Call all three from `handleConfigure`.
  - Update the existing `applyBearerToken` empty-value branch to additionally warn that clearing the token while `bearer_token_enabled=true` makes `/mcp` fail closed (401) until a token is set or bearer is disabled.
- [x] **DoD:** Three keys applied via broadcast; invalid override ignored with a log; no UI dialog gating (adb is the automation path).

### Task 2.5 — Tests (Story 2)
- [x] **Action:** extend `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt` (use the existing in-memory/temp DataStore harness in that file)

  | Test | Verifies |
  |------|----------|
  | `fresh install enables bearer with generated token` | New store → bearerTokenEnabled true, non-empty token, oauthEnabled false |
  | `migration preserves enabled when existing token present` | Pre-seed initialized+token → enabled true |
  | `migration disables when previously cleared` | Pre-seed initialized + empty token → enabled false |
  | `migration is idempotent` | With `bearer_token_enabled_initialized`=true and enabled=false pre-set, getServerConfig does NOT flip enabled back |
  | `migration with token but no initialized flag` | Pre-seed a token with `BEARER_TOKEN_INITIALIZED` unset → after `getServerConfig`/`ensureAuthModelMigrated`: enabled=true, token preserved (not regenerated) |
  | `updateBearerTokenEnabled(true) generates token when empty` | Enabling with empty value yields a token |
  | `updateBearerTokenEnabled(false) keeps value` | Disabling preserves stored token |
  | `re-enabling bearer with existing value does not regenerate` | Disable (value kept) → enable while value non-empty → SAME token (no regeneration) |
  | `getOrCreateJwtSigningSecret is stable` | Two calls return same non-empty secret |
  | `updateOauthEnabled persists` | Flag round-trips |
  | `publicUrlOverride round-trips and validates` | Persists; `validatePublicUrlOverride` accepts empty + https URL, rejects `ftp://`/garbage |

- [x] **Action:** extend `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandlerTest.kt`

  | Test | Verifies |
  |------|----------|
  | `applies oauth_enabled extra` | `--ez oauth_enabled true` → updateOauthEnabled(true) |
  | `applies bearer_token_enabled extra` | `--ez bearer_token_enabled false` → updateBearerTokenEnabled(false) |
  | `applies valid public_url_override` | valid URL → updatePublicUrlOverride(url) |
  | `ignores invalid public_url_override` | garbage → no update |
  | `absent extras are no-ops` | No call when extra missing |

- [x] **DoD:** Tests defined.

---

## User Story 3 — OAuth core domain (PKCE, JWT, client registry, auth-code store, approval coordinator)

**Why:** The pure, HTTP-independent building blocks of the Authorization Server. Isolated for unit testing.

**Acceptance criteria:**
- [x] java-jwt 4.5.2 added via version catalog.
- [x] Access (24h) + refresh (90d) JWTs issued/verified with HS256; claims include `iss`, `aud` (canonical resource), `sub`/`client_id`, `jti`, token-type; rejects tampered/expired/wrong-type.
- [x] PKCE S256 verification; non-S256 rejected.
- [x] Persisted, revocable client registry (cap 20, evict oldest-unapproved-by-last-used) capturing `client_name`, `redirect_uris`, `application_type`, `logo_uri`, created/last-used/current-refresh-jti, with an in-memory snapshot so `/mcp` lookups do not parse from disk per request.
- [x] Single-use, 60s authorization-code store.
- [x] Approval coordinator: 2-digit code, 5-minute window, pending StateFlow, approve/deny, status.

### Task 3.1 — Add dependencies (java-jwt + Coil)
- [x] **Action:** modify `gradle/libs.versions.toml` — under `[versions]` add `java-jwt = "4.5.2"` and `coil = "3.2.0"` (Coil 3.x, pinned exactly — no dynamic range); under `[libraries]` add `java-jwt = { group = "com.auth0", name = "java-jwt", version.ref = "java-jwt" }` and `coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }` and `coil-network = { group = "io.coil-kt.coil3", name = "coil-network-okhttp", version.ref = "coil" }` (Coil 3 needs a network backend for remote images).
- [x] **Action:** modify `app/build.gradle.kts` — add `implementation(libs.java.jwt)`, `implementation(libs.coil.compose)`, `implementation(libs.coil.network)` in the dependencies block. (Coil is used ONLY by the SSRF-guarded client-logo rendering in Task 5.3; pin the version, do not use a dynamic/`latest` range.)
- [x] **DoD:** Gradle resolves the dependency (verified in Story 7 build). minSdk 33 → `java.time` available; no desugaring needed. Note: java-jwt 4.5.2 pulls `jackson-databind` transitively — confirm in the Story 7 build there is no version clash and no R8/duplicate-class issue (minify is disabled, so duplicate-class risk is low).

### Task 3.2 — Redirect allowlist, resource matching, PKCE
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/OAuthPolicy.kt`
  - `fun isAllowedRedirectUri(uri: String): Boolean` — a CLOSED-SET allowlist (security boundary): parse with `java.net.URI(uri)` (return false on parse failure / null host). True ONLY if `uri == CLAUDE_REDIRECT_URI`, OR (`uri.scheme == "http"` AND `uri.host` is EXACTLY `"localhost"` or `"127.0.0.1"` — exact host equality via `URI.host`, any/no port, ignore userinfo). Reject everything else, including `[::1]`, `0.0.0.0`, other loopback IPs, and deceptive hosts (`localhost.evil.com`, `localhost@evil.com`).
  - `fun resourceMatches(requested: String, canonical: String): Boolean` — case-insensitive on scheme+host, trailing-slash-insensitive, exact otherwise; `false` when `requested` is blank.
  - companion constants: `CLAUDE_REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback"`, `MAX_OAUTH_CLIENTS = 20`, `ACCESS_TOKEN_TTL_SECONDS = 86_400L`, `REFRESH_TOKEN_TTL_SECONDS = 7_776_000L`, `AUTH_CODE_TTL_MS = 60_000L`, `APPROVAL_WINDOW_MS = 300_000L`, `MATCH_CODE_MODULO = 100` (2-digit, zero-padded), `MAX_PENDING_APPROVALS = 10`.
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/Pkce.kt`
  - `fun verifyS256(verifier: String, challenge: String): Boolean` — compute `computed = base64url(sha256(verifier.toByteArray(US-ASCII)))` (no padding); return `false` on blank inputs; compare `computed` to `challenge` with a constant-time comparison (`MessageDigest.isEqual(computed.toByteArray(), challenge.toByteArray())`) for parity with the project's `constantTimeEquals` convention.
- [x] **DoD:** Pure functions; compiles.

### Task 3.3 — JWT token service
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/JwtTokenService.kt` (interface) + `JwtTokenServiceImpl.kt`
  - Data: `data class AccessTokenClaims(val clientId: String, val audience: String)`, `data class RefreshTokenClaims(val clientId: String, val jti: String)`.
  - Interface: `suspend fun issueAccessToken(clientId: String, resource: String): String`; `suspend fun issueRefreshToken(clientId: String, jti: String, resource: String): String`; `suspend fun verifyAccessToken(token: String): AccessTokenClaims?`; `suspend fun verifyRefreshToken(token: String): RefreshTokenClaims?`.
  - Impl (`@Inject constructor(private val settingsRepository: SettingsRepository)`): MEMOIZE the `Algorithm` — a `@Volatile private var algorithm: Algorithm?` lazily initialized once under a `Mutex` via `Algorithm.HMAC256(settingsRepository.getOrCreateJwtSigningSecret())`, so neither `issue` nor (hot-path) `verify` calls the repository/DataStore per request. Issue with `JWT.create().withIssuer(ISS).withSubject(clientId).withClaim("client_id", clientId).withClaim("typ", "access"|"refresh").withAudience(resource).withJWTId(jti|random).withIssuedAt(Date()).withExpiresAt(Date(now + ttl*1000)).sign(alg)` — BOTH access AND refresh carry `aud = resource` (per the agreed design) and `client_id`; refresh additionally carries the `jti`. `ISS = "android-remote-control-mcp"`. Verify with `JWT.require(alg).withIssuer(ISS).withClaim("typ", expectedType).build().verify(token)` inside `runCatching { … }.getOrNull()`, then map claims (access → `clientId` + `audience` first value; refresh → `clientId` + `jti`); reject when `typ`/structure mismatch.
- [x] **DoD:** Round-trips; rejects tampered/expired/wrong-typ.

### Task 3.4 — Client registry (persisted, revocable, in-memory snapshot)
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/OAuthClient.kt`
  - `@Serializable data class OAuthClient(val clientId: String, val clientName: String? = null, val redirectUris: List<String>, val applicationType: String? = null, val logoUri: String? = null, val createdAtMs: Long, val lastUsedAtMs: Long, val currentRefreshJti: String? = null)`
  - `logoUri` is captured from DCR (`logo_uri`) and rendered in the clients row WITH SSRF mitigations (see Task 5.3); fall back to initials when absent/invalid. `applicationType` kept (user-requested for the clients row).
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/OAuthClientRepository.kt` (interface) + `OAuthClientRepositoryImpl.kt`
  - Interface: `fun observeClients(): Flow<List<OAuthClient>>`; `suspend fun getClients(): List<OAuthClient>`; `suspend fun getClient(clientId: String): OAuthClient?`; `suspend fun register(clientName: String?, redirectUris: List<String>, applicationType: String?, logoUri: String?, nowMs: Long): OAuthClient`; `suspend fun revoke(clientId: String)`; `suspend fun touchLastUsed(clientId: String, nowMs: Long)`; `suspend fun setRefreshJti(clientId: String, jti: String)`.
  - Impl (`@Inject constructor(@OAuthClientsDataStore private val dataStore: DataStore<Preferences>)` — a DEDICATED Preferences DataStore, NOT the shared settings store, per the agreed design): key `OAUTH_CLIENTS_KEY = stringPreferencesKey("oauth_clients")`; lenient `Json { ignoreUnknownKeys = true }`. Maintain an in-memory `MutableStateFlow<List<OAuthClient>>` snapshot seeded lazily on first access (load+parse from DataStore once, guarded by a `Mutex`+flag); every write updates DataStore AND the snapshot write-through. `observeClients()` returns the snapshot StateFlow; `getClient`/`getClients` read the in-memory snapshot (NO per-call JSON-decode-from-disk on the `/mcp` hot path). `register`: `clientId = "arc-" + UUID.randomUUID()`; append; if size > `MAX_OAUTH_CLIENTS` evict the oldest-by-`lastUsedAtMs` among entries with `currentRefreshJti == null` (never completed a token grant); only if EVERY entry has a `currentRefreshJti` evict the overall oldest-by-`lastUsedAtMs` — so unauthenticated `/register` spam cannot evict an already-approved client. `touchLastUsed`/`setRefreshJti` mutate the named entry; `revoke` removes by id.
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`:
  - Add a `@Qualifier @Retention(BINARY) annotation class OAuthClientsDataStore`.
  - In `AppModule` add a dedicated DataStore provider mirroring `provideDataStore`: a `private val Context.oauthClientsDataStore: DataStore<Preferences> by preferencesDataStore(name = "oauth_clients")` extension + `@Provides @Singleton @OAuthClientsDataStore fun provideOAuthClientsDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.oauthClientsDataStore`.
  - In `RepositoryModule` add `@Binds @Singleton abstract fun bindOAuthClientRepository(impl: OAuthClientRepositoryImpl): OAuthClientRepository`.
- [x] **DoD:** Persists across new instances (restart); cap+eviction honored; revoke effective; hot-path lookups served from the in-memory snapshot.

### Task 3.5 — Authorization-code store
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/AuthorizationCodeStore.kt` (interface) + `AuthorizationCodeStoreImpl.kt`
  - `data class AuthorizationCode(val code: String, val clientId: String, val redirectUri: String, val codeChallenge: String, val resource: String, val scope: String, val expiresAtMs: Long)`
  - Interface: `suspend fun create(clientId, redirectUri, codeChallenge, resource, scope, nowMs): String`; `suspend fun consume(code: String, nowMs: Long): AuthorizationCode?` (single-use: remove on read; null if missing/expired).
  - Impl (`@Inject constructor()`): `Mutex` + `LinkedHashMap<String, AuthorizationCode>`; `create` purges expired then inserts a random `"code-" + secureRandomHex` (16+ bytes from `SecureRandom`); `consume` removes and returns only if not expired.
- [x] **DoD:** Single-use + TTL enforced; thread-safe.

### Task 3.6 — Approval coordinator
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/OAuthApprovalCoordinator.kt` (interface) + `OAuthApprovalCoordinatorImpl.kt` (`@Singleton`)
  - `enum class ApprovalState { PENDING, APPROVED, DENIED, EXPIRED }`
  - `data class PendingApproval(val id: String, val clientName: String, val redirectHost: String, val matchCode: String, val expiresAtMs: Long)`
  - Interface: `fun observePending(): StateFlow<List<PendingApproval>>`; `suspend fun createPending(clientName: String, redirectHost: String, nowMs: Long): PendingApproval`; `suspend fun approve(id: String)`; `suspend fun deny(id: String)`; `suspend fun stateOf(id: String, nowMs: Long): ApprovalState`.
  - Impl (`@Inject constructor()`, `@Singleton`): `Mutex`-guarded map id → (PendingApproval + ApprovalState); `MutableStateFlow<List<PendingApproval>>` of currently-PENDING entries. `createPending`: first purge expired pendings; if still at `MAX_PENDING_APPROVALS`, drop the OLDEST pending (prevents an unauthenticated `/authorize` spammer from flooding the device with approval notifications). `matchCode` = `(SecureRandom().nextInt(MATCH_CODE_MODULO))` zero-padded to 2 digits, RE-DRAWN until it does NOT collide with any currently-pending code (so the user can never see two pending requests with the same code — guaranteed possible since `MAX_PENDING_APPROVALS` (10) < `MATCH_CODE_MODULO` (100)); `expiresAtMs = nowMs + APPROVAL_WINDOW_MS`; `id` = a high-entropy unguessable token (16+ bytes hex from `SecureRandom`) — NOT a sequential/short value, because `/authorize/status` is unauthenticated and returns the minted auth code keyed solely by this id; emit. `approve`/`deny` set state and remove from the pending flow. `stateOf` returns EXPIRED when past window (and removes from flow).
- [x] **Action:** modify `AppModule.kt` `ServiceModule` — add `@Binds @Singleton` for `JwtTokenService`, `AuthorizationCodeStore`, `OAuthApprovalCoordinator`. (`ServiceModule` already has a pre-existing `@Suppress("TooManyFunctions")` that covers these new bindings — do NOT add any new suppression.)
- [x] **DoD:** Concurrency-safe; pending flow reflects PENDING set; expiry transitions correct.

### Task 3.7 — Tests (Story 3)
- [x] **Action:** create the following unit test files. **Setup (shared):** `MockK` for `SettingsRepository.getOrCreateJwtSigningSecret` returning a fixed secret; in-memory/temp DataStore for the repository (same harness style as `SettingsRepositoryImplTest`); inject explicit `nowMs` for time control (no `System.currentTimeMillis` in pure logic).

  **File:** `mcp/oauth/JwtTokenServiceImplTest.kt`
  | Test | Verifies |
  |------|----------|
  | `access token round-trips claims` | clientId + aud recovered |
  | `refresh token round-trips jti` | jti + clientId recovered |
  | `rejects tampered token` | Mutated token → null |
  | `rejects wrong type` | refresh verified as access → null and vice-versa |
  | `rejects expired token` | exp in past → null (issue pre-expired) |

  **File:** `mcp/oauth/OAuthPolicyTest.kt`
  | Test | Verifies |
  |------|----------|
  | `allows claude callback and localhost` | claude.ai callback + http localhost/127.0.0.1 any port allowed |
  | `rejects other redirect uris` | https other-host rejected |
  | `rejects deceptive localhost-like hosts` | `http://localhost.evil.com`, `http://127.0.0.1.evil.com`, `http://localhost@evil.com` all rejected (host parsed via `URI.host`, exact match only) |
  | `rejects other loopback and wildcard hosts` | `http://[::1]/cb`, `http://0.0.0.0/cb`, `http://127.0.0.2/cb`, `https://localhost/cb` (https non-Claude) all rejected (closed-set: only literal localhost/127.0.0.1 over http, plus the Claude URI) |
  | `resourceMatches is host-case and trailing-slash insensitive` | `HTTPS://Host/mcp/` matches `https://host/mcp`; blank → false |

  **File:** `mcp/oauth/PkceTest.kt`
  | Test | Verifies |
  |------|----------|
  | `valid S256 verifier matches challenge` | base64url(sha256(verifier)) == challenge |
  | `mismatched verifier rejected` | false; blank → false |

  **File:** `mcp/oauth/OAuthClientRepositoryImplTest.kt`
  | Test | Verifies |
  |------|----------|
  | `register then get round-trips fields` | name/redirects/appType/logoUri/timestamps persisted |
  | `persists across new instance` | New repo over same store returns clients (restart) |
  | `getClient served from snapshot after first load` | After load, getClient does not require a fresh disk read (assert value correct across repeated calls) |
  | `evicts oldest unapproved beyond cap` | When full, a new registration evicts the oldest entry with `currentRefreshJti == null`, NOT an approved client |
  | `evicts overall oldest when all approved` | When every entry has a `currentRefreshJti`, evicts the overall oldest-by-lastUsed |
  | `revoke removes client` | getClient → null |
  | `touchLastUsed and setRefreshJti update` | Fields mutated and observable via snapshot |

  **File:** `mcp/oauth/AuthorizationCodeStoreImplTest.kt`
  | Test | Verifies |
  |------|----------|
  | `create then consume returns entry once` | Second consume → null (single-use) |
  | `expired code not consumable` | nowMs past expiry → null |

  **File:** `mcp/oauth/OAuthApprovalCoordinatorImplTest.kt`
  | Test | Verifies |
  |------|----------|
  | `createPending appears in pending flow with 2-digit code` | matchCode length 2; present in StateFlow |
  | `approve transitions and clears pending` | stateOf APPROVED; removed from flow |
  | `deny transitions to DENIED` | stateOf DENIED |
  | `expiry yields EXPIRED` | nowMs past window → EXPIRED; removed from flow |

- [x] **DoD:** All defined; deterministic via injected `nowMs`.

---

## User Story 4 — OAuth HTTP layer: metadata, DCR, authorize+approval, token, combined auth

**Why:** Expose the AS over HTTP exactly as Claude expects, and make `/mcp` accept either an issued JWT or the static bearer token, returning the discovery-triggering 401.

**Acceptance criteria:**
- [x] Serves PRM (path-suffixed `…/mcp` and bare), AS metadata (bare + `openid-configuration` + path-suffixed alias) — all built from `effectiveBaseUrl(call, override)`.
- [x] `/mcp` 401 carries `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource/mcp"` ONLY when OAuth is enabled.
- [x] DCR persists the client; `/authorize` validates client+redirect+PKCE+resource, creates a pending approval, serves a polling consent page; `/token` handles `authorization_code` (PKCE, bound to the code's client) and `refresh_token` (rotation; stale-jti → reject only).
- [x] Combined `/mcp` auth: `(bearerTokenEnabled && static-token match) OR (oauthEnabled && valid access JWT with aud==canonical resource && client in registry)`; open when both disabled.
- [x] Last-used updated on `/mcp` (debounced); revoked client → its tokens rejected immediately.

### Task 4.1 — OAuth metadata documents
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/OAuthMetadata.kt`
  - `fun protectedResourceMetadata(baseUrl: String): String` → JSON `{resource: "<base>/mcp", authorization_servers: ["<base>"], bearer_methods_supported: ["header"], scopes_supported: ["mcp"]}`.
  - `fun authorizationServerMetadata(baseUrl: String): String` → JSON `{issuer:"<base>", authorization_endpoint:"<base>/authorize", token_endpoint:"<base>/token", registration_endpoint:"<base>/register", response_types_supported:["code"], grant_types_supported:["authorization_code","refresh_token"], code_challenge_methods_supported:["S256"], token_endpoint_auth_methods_supported:["none"], scopes_supported:["mcp"]}`.
  - Build with `kotlinx.serialization` `buildJsonObject` (same style as `SettingsRepositoryImpl`).
  - Field sets are RFC 9728 (PRM) / RFC 8414 (AS metadata) derived; the spike server returned exactly these documents and Claude completed discovery+auth against them (the spike confirms Claude accepts this shape, not that every field is individually required).
- [x] **Action:** create `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/OAuthMetadataTest.kt` — unit-assert the EXACT JSON field sets for both documents given a base URL: PRM = `resource` (`<base>/mcp`), `authorization_servers` (`[<base>]`), `bearer_methods_supported` (`["header"]`), `scopes_supported` (`["mcp"]`); AS = `issuer`, `authorization_endpoint`, `token_endpoint`, `registration_endpoint`, `response_types_supported` (`["code"]`), `grant_types_supported` (`["authorization_code","refresh_token"]`), `code_challenge_methods_supported` (`["S256"]`), `token_endpoint_auth_methods_supported` (`["none"]`), `scopes_supported` (`["mcp"]`).
- [x] **DoD:** Stable JSON; fields exactly as listed; unit test pins the field sets.

### Task 4.2 — OAuth routes (metadata, DCR, authorize, status, token)
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/OAuthRoutes.kt`
  - `class OAuthRouteDeps(val clientRepository: OAuthClientRepository, val tokenService: JwtTokenService, val authorizationCodeStore: AuthorizationCodeStore, val approvalCoordinator: OAuthApprovalCoordinator, val publicUrlOverride: String, val nowMs: () -> Long = { System.currentTimeMillis() })`.
  - `private data class PendingAuthorizeRequest(val clientId: String, val redirectUri: String, val codeChallenge: String, val resource: String, val scope: String, val state: String, val expiresAtMs: Long)`. Inside `installOAuthRoutes`, hold a server-lifetime `Mutex` + `mutableMapOf<String /*approvalId*/, PendingAuthorizeRequest>()`; `put` on `/authorize`; single-use remove (consume) on `/authorize/status` when APPROVED; purge entries past `expiresAtMs` on each access. Base URL for all responses = `effectiveBaseUrl(call, deps.publicUrlOverride)`. (`host(uri)` used below = exception-safe host extraction `runCatching { java.net.URI(uri).host }.getOrNull()` — returns null on malformed input so the allowlist/400 path is taken, never a 500.)
  - `fun Route.installOAuthRoutes(deps: OAuthRouteDeps)` registering (all unauthenticated — see Task 4.3 exclusions):
    - `get("/.well-known/oauth-protected-resource")` and `get("/.well-known/oauth-protected-resource/{tail...}")` → `protectedResourceMetadata(effectiveBaseUrl(call, deps.publicUrlOverride))`.
    - `get("/.well-known/oauth-authorization-server")`, `get("/.well-known/oauth-authorization-server/{tail...}")`, `get("/.well-known/openid-configuration")` → `authorizationServerMetadata(effectiveBaseUrl(call, deps.publicUrlOverride))`.
    - `post("/register")`: parse JSON body; extract `client_name`, `redirect_uris`, `application_type`, `logo_uri`; reject (400 `invalid_redirect_uri`) if `redirect_uris` is empty or any fails `isAllowedRedirectUri`; `clientRepository.register(clientName, redirectUris, applicationType, logoUri, now)`; respond 201 `{client_id, client_id_issued_at, token_endpoint_auth_method:"none", redirect_uris, grant_types:["authorization_code","refresh_token"], response_types:["code"], client_name?, application_type?, logo_uri?}` (echo all registered metadata per RFC 7591 §3.2.1).
    - `get("/authorize")`: read `response_type` (must be `code`), `client_id`, `redirect_uri`, `code_challenge`, `code_challenge_method` (must be `S256`), `state`, `scope`, `resource`. Let `canonical = canonicalResource(effectiveBaseUrl(call, deps.publicUrlOverride))`. The `resource` parameter is OPTIONAL (RFC 8707; required for Claude but not for allowlisted localhost test clients): if present require `resourceMatches(resource, canonical)`, else default `resource = canonical`. Validate: client exists; `redirect_uri` ∈ the client's registered `redirectUris` AND `isAllowedRedirectUri`. On invalid client/redirect → 400 HTML error (do NOT redirect). On other invalid params → redirect to `redirect_uri` with `error`. On valid → resolve a non-null display name `displayName = client.clientName ?: host(redirect_uri) ?: "Unknown"` (the agreed fallback, since `clientName` is nullable and `createPending`/consent/approval surfaces require a non-null name); `approval = approvalCoordinator.createPending(displayName, host(redirect_uri), now)`; store a `PendingAuthorizeRequest` under `approval.id` (with `clientId`, `redirect_uri`, `code_challenge`, the resolved `resource`, `scope`, `state`, `expiresAtMs = approval.expiresAtMs`); serve the consent HTML page (Task 4.4) embedding `approval.id` + `approval.matchCode`.
    - `get("/authorize/status")`: query `id`; `state = approvalCoordinator.stateOf(id, now)`; if APPROVED: consume the `PendingAuthorizeRequest` (single-use); `code = authorizationCodeStore.create(clientId, redirectUri, codeChallenge, resource, scope, now)`; build the redirect with `URLBuilder(redirectUri).apply { parameters.append("code", code); parameters.append("state", state) }.buildString()` (MANDATORY — do NOT raw-concatenate `?code=…&state=…`, which would break a registered `redirect_uri` that already carries a query string, e.g. a localhost test client's `…/cb?foo=bar`, and would not encode `state`). `state` is client-supplied opaque text that may contain `&`/`=`/`#`/spaces; `URLBuilder` percent-encodes it. Respond `{state:"approved", redirect:"<built url>"}`. PENDING → `{state:"pending"}`; DENIED/EXPIRED (or missing pending request) → that state. (This endpoint is unauthenticated; its security boundary is the unguessable 16+ byte SecureRandom `id` from Task 3.6 + the 5-minute window + single-use consumption.)
    - `post("/token")`: parse form. `grant_type=authorization_code`: `code = authorizationCodeStore.consume(form.code, now)` (null → 400 `invalid_grant`); require `form.redirect_uri == code.redirectUri`; require `form.client_id` is PRESENT and `== code.clientId` (public clients MUST send `client_id` per RFC 6749 §3.2.1 — 400 `invalid_request` if absent, `invalid_grant` if mismatched); if `form.resource` present require `resourceMatches(form.resource, code.resource)` (resource is optional on the token request — RFC 8707); `verifyS256(form.code_verifier, code.codeChallenge)`; on any failure 400 `invalid_grant`. On success mint for `code.clientId`: `jti = random`; `clientRepository.setRefreshJti(code.clientId, jti)`; `clientRepository.touchLastUsed(code.clientId, now)`; respond `{access_token: issueAccessToken(code.clientId, code.resource), token_type:"Bearer", expires_in: ACCESS_TOKEN_TTL_SECONDS, refresh_token: issueRefreshToken(code.clientId, jti, code.resource), scope:"mcp"}` with `Cache-Control: no-store`. `grant_type=refresh_token`: `claims = verifyRefreshToken(form.refresh_token)` (null → 400); if `form.client_id` present require `form.client_id == claims.clientId` (else 400 `invalid_grant`); `client = clientRepository.getClient(claims.clientId)` (null → 400 `invalid_grant`); if `claims.jti != client.currentRefreshJti` → 400 `invalid_grant` (stale/rotated — reject only, do NOT revoke client); else rotate (`newJti`, `setRefreshJti`), `touchLastUsed`, and issue a new access + refresh pair both with `resource = canonicalResource(effectiveBaseUrl(call, deps.publicUrlOverride))` (`issueAccessToken(claims.clientId, resource)` + `issueRefreshToken(claims.clientId, newJti, resource)`). Other grant_type → 400 `unsupported_grant_type`.
  - Never log tokens, codes, secrets, verifiers, or full URLs.
  - **Host-stability note:** an access token's `aud` is bound to the effective base URL at issuance. If `publicUrlOverride` is empty and the public host changes between issuance and use (e.g., a new quick-tunnel hostname), the `/mcp` validator's `resourceMatches` will reject the now-stale token (→ 401) and the client must re-authorize — this is correct behavior, not a bug. For stable deployments the user sets `publicUrlOverride` to pin the host. The `wrong-aud` test (Task 4.6) exercises this mechanism.
- [x] **DoD:** Endpoints behave as the spike trace requires; redirect allowlist + PKCE + resource(optional) + client binding enforced; pending-authorize store is single-use and expiry-bounded.

### Task 4.3 — Combined auth plugin (supersede bearer-only plugin)
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/auth/BearerTokenAuth.kt` → introduce `McpAuthPlugin` (rename `BearerTokenAuthPlugin` → `McpAuthPlugin`; keep `constantTimeEquals`, `AuthErrorResponse`). Rename `BearerTokenAuthConfig` → `McpAuthConfig` with: `bearerTokenEnabled: Boolean = true`, `expectedToken: String = ""`, `oauthEnabled: Boolean = false`, `validateOAuthToken: (suspend (token: String, canonicalResource: String) -> Boolean)? = null`, `baseUrlOf: (ApplicationCall) -> String = { deriveBaseUrl(it) }`, plus existing `excludedPaths`/`excludedPathPrefixes`. (These are mutable `var` properties of the Ktor `createApplicationPlugin` config class — like the existing `BearerTokenAuthConfig` — NOT constructor params.) Intercept logic:
  1. If `!bearerTokenEnabled && !oauthEnabled` → `return@intercept` (open).
  2. Excluded path / prefix → `return@intercept`.
  3. Extract the Bearer token: if the `Authorization` header is missing OR does not start with `Bearer ` (case-insensitive), treat the provided token as ABSENT — do NOT emit a separate missing/malformed 401 (this REMOVES the old plugin's two early 401 responses). Fall through to steps 4–5 (which fail with an absent token) and the SINGLE final 401 in step 6.
  4. If `bearerTokenEnabled && expectedToken.isNotEmpty() && constantTimeEquals(expectedToken, provided)` → allow.
  5. Else if `oauthEnabled && validateOAuthToken != null && validateOAuthToken(provided, canonicalResource(baseUrlOf(call)))` → allow.
  6. Else → 401; when `oauthEnabled`, add header `WWW-Authenticate: Bearer resource_metadata="${baseUrlOf(call)}/.well-known/oauth-protected-resource/mcp"`; `finish()`.
  - **Enabled-empty behavior (defined, fail-closed):** `bearerTokenEnabled=true` with `expectedToken=""` provides NO bearer path (step 4 requires non-empty) → the request fails closed (401 when OAuth is also unavailable). This is intentional — an enabled-but-unconfigured method MUST NOT open the server. The normal paths never produce it (enabling auto-generates a token; the UI regenerate keeps it non-empty); the adb token-clear path logs a warning (Task 2.4).
  - Imports to add to `BearerTokenAuth.kt` for the cross-package symbols used above: `com.danielealbano.androidremotecontrolmcp.mcp.deriveBaseUrl`, `com.danielealbano.androidremotecontrolmcp.mcp.canonicalResource`, `io.ktor.server.application.ApplicationCall`.
- [x] **Action:** update ALL `BearerTokenAuthPlugin`/`BearerTokenAuthConfig` references to `McpAuthPlugin`/`McpAuthConfig`. The new model REMOVES the old "empty `expectedToken` = auth disabled" semantics (now controlled by `bearerTokenEnabled`), so call sites and tests that relied on it MUST be updated, not just renamed:
  - `mcp/McpServer.kt` — symbol rename ONLY; the existing install keeps its current fields (`expectedToken`, `excludedPaths`, `excludedPathPrefixes`) and compiles via `McpAuthConfig` defaults (`bearerTokenEnabled=true`, `oauthEnabled=false`, `baseUrlOf` default, `validateOAuthToken=null`) in the interim; the FULL config (validator, baseUrlOf, OAuth path exclusions) is rewired in Task 4.5.
  - `app/src/test/.../integration/McpIntegrationTestHelper.kt` — both `withTestApplication` and `withRawTestApplication` installs use `expectedToken = TEST_BEARER_TOKEN` (non-empty) with defaults `bearerTokenEnabled = true`, `oauthEnabled = false` (behavior preserved — token enforced).
  - `app/src/test/.../integration/SharingIntegrationTest.kt` (the `install` at line ~424 with `expectedToken = ""`) — MUST set `bearerTokenEnabled = false` (and leave `oauthEnabled = false`) to preserve the "open `/mcp`" behavior the test relies on (otherwise enabled+empty → 401 breaks the whole suite).
  - `app/src/test/.../mcp/auth/BearerTokenAuthTest.kt` — rewrite the `plugin skips authentication when token is empty` test (line ~177) to assert open behavior via `bearerTokenEnabled = false` (NOT empty token); keep/adapt all other tests to the new config.
  - `app/src/test/.../mcp/auth/BearerTokenAuthPrefixTest.kt` — rewrite the empty-token test (locate it by its `@DisplayName("empty expectedToken makes all paths reachable")`, ~line 99 — NOT by a function literally named `emptyTokenAllowsAll`) to assert open behavior via `bearerTokenEnabled = false`.
- [x] **Action:** extend `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/auth/BearerTokenAuthTest.kt` with dual-accept/new-config coverage:

  | Test | Verifies |
  |------|----------|
  | `open when both methods disabled` | `bearerTokenEnabled=false, oauthEnabled=false` → request passes without token |
  | `valid OAuth token allowed via validateOAuthToken` | `oauthEnabled=true`, stub validator true → allowed |
  | `401 has WWW-Authenticate only when oauth enabled` | oauth on → header present; bearer-only → header absent |
  | `bearer enabled with empty token fails closed` | `bearerTokenEnabled=true, expectedToken="", oauthEnabled=false` → 401 (NOT open; no bearer path because the token is empty) — defines the enabled-empty behavior |
  | `malformed Authorization header with oauth on returns single 401 with header` | header missing the `Bearer ` prefix, `oauthEnabled=true` → one 401 carrying `WWW-Authenticate` (no early/duplicate 401) |
  | `static bearer rejected when only oauth enabled` | `bearerTokenEnabled=false, oauthEnabled=true`; presenting the static token → rejected (only OAuth JWTs accepted) |

- [x] **DoD:** Dual-accept logic correct; `WWW-Authenticate` only when OAuth on; open when both off; all renamed call sites compile and pass.

### Task 4.4 — Consent HTML page (polling)
- [x] **Action:** within `OAuthRoutes.kt` (or `OAuthConsentPage.kt`), add `fun consentPageHtml(approvalId: String, matchCode: String, clientName: String): String` — minimal self-contained HTML+JS served at `/authorize`: shows "Approve **<clientName>** to connect" + the 2-digit match code + "Open the Android Remote Control app and approve the request with this code"; JS polls `/authorize/status?id=<approvalId>` every 2s; on `approved` sets `window.location = redirect` (from the status JSON); on `denied`/`expired` shows an error message. No external resources. Escape `clientName` for HTML. The page MUST NOT contain any approve/submit control — it only displays the code and polls; the sole approval gate is the explicit on-device action in the app (the match code is a UX confirmation, not a secret).
- [x] **Action:** create `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/ConsentPageTest.kt` — assert a malicious `clientName` (e.g. `<script>alert(1)</script>`) is HTML-escaped in `consentPageHtml` output (no raw `<script>`).
- [x] **DoD:** Page renders standalone; polls; redirects on approval; never auto-approves; `clientName` HTML-escaped (test-pinned).

### Task 4.5 — Wire OAuth + combined auth into the server
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/OAuthServerDeps.kt` — `class OAuthServerDeps(val jwtTokenService: JwtTokenService, val oauthClientRepository: OAuthClientRepository, val authorizationCodeStore: AuthorizationCodeStore, val approvalCoordinator: OAuthApprovalCoordinator)` (groups the OAuth collaborators so `McpServer`'s constructor stays under detekt's `LongParameterList` threshold of 7 — no `@Suppress` needed).
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpServer.kt`:
  - Constructor adds a SINGLE param `oauth: OAuthServerDeps` (→ 6 total params). Read `oauthEnabled`/`bearerTokenEnabled`/`publicUrlOverride` from the EXISTING `config: ServerConfig` (`config.oauthEnabled`, etc.) — do NOT add them as separate params (avoids duplicate sources of truth and keeps the param count down).
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/OAuthAccessValidator.kt` — extract the `/mcp` OAuth-token validation (so it is unit-testable and shared by `McpServer` and the test helper, NOT duplicated): `class OAuthAccessValidator(private val tokenService: JwtTokenService, private val clientRepository: OAuthClientRepository, private val debounceMs: Long = 60_000L, private val nowMs: () -> Long = { System.currentTimeMillis() })` with `suspend fun validate(token: String, canonicalResource: String): Boolean` = `verifyAccessToken(token)`; if null → false; if `!resourceMatches(c.audience, canonicalResource)` or `clientRepository.getClient(c.clientId) == null` → false; else debounced `touchLastUsed` (in-memory `ConcurrentHashMap<String, Long>`, at most once per `debounceMs` per clientId via `nowMs()`) and return true. BOUND the debounce map so it cannot grow unboundedly as clients are revoked/re-registered (each gets a new `arc-UUID`): on each touch, drop entries whose `clientId` is no longer in the registry snapshot (or hard-cap the map at `MAX_OAUTH_CLIENTS`). (`resourceMatches` is same-package — `mcp.oauth` — so no extra import.)
- [x] **Action:** modify `McpServer.kt`: hold `private val accessValidator = OAuthAccessValidator(oauth.jwtTokenService, oauth.oauthClientRepository)`. In `configureApplication`: install `McpAuthPlugin` with `bearerTokenEnabled = config.bearerTokenEnabled`, `expectedToken = config.bearerToken`, `oauthEnabled = config.oauthEnabled`, `baseUrlOf = { effectiveBaseUrl(it, config.publicUrlOverride) }`, `validateOAuthToken = { token, resource -> accessValidator.validate(token, resource) }`, `excludedPaths = setOf("/health", "/register", "/token", "/authorize", "/authorize/status")`, `excludedPathPrefixes = setOf(EphemeralFileLinkService.PATH_PREFIX, "/.well-known/")`. (Use EXACT paths for the OAuth endpoints — NOT prefixes — to avoid `startsWith` over-matching sibling routes and silently exempting them from auth; only the reserved `/.well-known/` namespace and the `/s/` capability route use prefix matching.)
  - In `routing { … }`: when `config.oauthEnabled`, call `installOAuthRoutes(OAuthRouteDeps(oauth.oauthClientRepository, oauth.jwtTokenService, oauth.authorizationCodeStore, oauth.approvalCoordinator, config.publicUrlOverride))`. (Mount ONLY when OAuth is on so metadata/endpoints are absent when OAuth is off.)
  - Change the `mcpStreamableHttp { mcpSdkServer }` call to `mcpStreamableHttp(publicUrlOverride = config.publicUrlOverride) { mcpSdkServer }`.
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`:
  - `@Inject` add `jwtTokenService`, `oauthClientRepository`, `authorizationCodeStore`, `approvalCoordinator`.
  - In `startServer()`, replace the config read `settingsRepository.serverConfig.first()` with `settingsRepository.getServerConfig()` — this guarantees `ensureAuthModelMigrated()` (migration + bearer-token generation) has run before the server reads the auth model, preventing the cleared-token-user open-server regression.
  - Pass a single `oauth = OAuthServerDeps(jwtTokenService, oauthClientRepository, authorizationCodeStore, approvalCoordinator)` into the `McpServer(...)` constructor (the auth flags + `publicUrlOverride` are read from `config` inside `McpServer`, not passed separately).
- [x] **DoD:** Server builds; OAuth routes live only when enabled; `/mcp` enforces combined auth; debounced last-used works; override threaded into metadata + share-link context.

### Task 4.6 — Tests (Story 4)
- [x] **Action:** extend `McpIntegrationTestHelper.kt` with `withOAuthTestApplication(...)`. Full composition (do NOT just copy the minimal `withRawTestApplication`): install `ContentNegotiation(McpJson)`; install `McpAuthPlugin` with `oauthEnabled = true`, `baseUrlOf = { effectiveBaseUrl(it, override) }`, `validateOAuthToken` delegating to a real `OAuthAccessValidator`, and the SAME exclusions as production (Task 4.5) — `excludedPaths = setOf("/health","/register","/token","/authorize","/authorize/status")`, `excludedPathPrefixes = setOf(EphemeralFileLinkService.PATH_PREFIX, "/.well-known/")` (WITHOUT these the plugin 401s the OAuth endpoints and the whole suite fails); mount `installOAuthRoutes(OAuthRouteDeps(...))`; `mcpStreamableHttp { sdkServer }`; expose a raw HTTP client. **Setup:** real `JwtTokenServiceImpl` (mock `SettingsRepository.getOrCreateJwtSigningSecret`), real in-memory `AuthorizationCodeStoreImpl`/`OAuthApprovalCoordinatorImpl`, real `OAuthClientRepositoryImpl` over a temp dedicated DataStore; the helper's `McpAuthPlugin.validateOAuthToken` delegates to a real `OAuthAccessValidator` (same code as production); allow overriding `publicUrlOverride` per test. The helper registers the existing `registerAllTools` set, which does NOT include sharing tools; therefore the full-dance `callTool` step MUST target a tool that `registerAllTools` actually registers (e.g., `tap`), NOT `share_file_via_web`. Per-request base-URL propagation through the SDK to a tool handler — including across the tool's own IO hop — is covered separately by the `share_file_via_web URL reflects X-Forwarded-Host` test (Task 1.4, which registers sharing tools) plus the `RequestBaseUrlElement survives inner withContext(IO)` unit test.

- [x] **Action:** create `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/OAuthFlowIntegrationTest.kt`

  | Test | Verifies |
  |------|----------|
  | `unauthenticated /mcp returns 401 with WWW-Authenticate when oauth on` | Header points to path-suffixed PRM |
  | `401 has no WWW-Authenticate when only bearer enabled` | bearer on + oauth off → 401 without the header |
  | `serves PRM at path-suffixed and bare paths` | Both 200; `resource` = request-derived `…/mcp` |
  | `serves AS metadata at bare, openid-configuration, and path-suffixed` | All 200; required fields incl. `code_challenge_methods_supported:["S256"]` |
  | `metadata reflects X-Forwarded-Host and -Proto` | PRM/AS issuer/resource use injected forwarded headers |
  | `metadata honors publicUrlOverride` | With override set, metadata uses the override host regardless of forwarded headers |
  | `full DCR→authorize→approve→token→/mcp succeeds` | Drive the spike sequence; approve via coordinator; SDK `callTool` (a `registerAllTools` tool, e.g. `tap`) with issued JWT works |
  | `register rejects disallowed redirect_uri` | 400 invalid_redirect_uri |
  | `authorize rejects non-S256 challenge method` | error |
  | `token rejects wrong PKCE verifier` | 400 invalid_grant |
  | `token rejects client_id mismatch against code` | 400 invalid_grant |
  | `token rejects missing client_id` | authorization_code grant without `client_id` → 400 invalid_request |
  | `replayed auth code rejected` | 400 invalid_grant on second use |
  | `refresh_token rotates and old jti rejected` | New tokens issued; reusing prior refresh → 400 invalid_grant; client NOT revoked |
  | `revoked client token rejected on /mcp` | After `revoke`, prior access JWT → 401 |
  | `wrong-aud access token rejected on /mcp` | Token minted for other resource → 401 |
  | `static bearer token still accepted (dual-accept)` | With bearer+oauth on, static token authorizes /mcp |
  | `open when both disabled` | bearer off + oauth off → /mcp without token succeeds |
  | `authorize rejects present-but-mismatched resource` | `resource` present and ≠ canonical → error (not approved) |
  | `authorize omits resource defaults to canonical` | No `resource` (localhost test client) → flow succeeds; issued token aud = canonical |
  | `token rejects present-but-mismatched resource` | `form.resource` present and ≠ `code.resource` → 400 invalid_grant |
  | `authorize with disallowed redirect_uri returns 400 HTML no Location` | Open-redirect guard: unregistered/dis-allowed `redirect_uri` → 400 HTML page, never a `Location` redirect |
  | `last-used updated once then throttled across two /mcp calls` | two authenticated `/mcp` calls in quick succession → `touchLastUsed` invoked EXACTLY once (debounce holds on the real `/mcp` path; verify via repository spy) |
  | `authorize redirect percent-encodes state` | a `state` containing `&`, `=`, and a space round-trips correctly in the `/authorize/status` redirect (decoded back to the original) |

- [x] **Action:** create `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/OAuthAccessValidatorTest.kt` (unit; inject `nowMs` + mock `JwtTokenService`/`OAuthClientRepository`):

  | Test | Verifies |
  |------|----------|
  | `valid token for registered client passes` | verify ok + aud match + client present → true |
  | `wrong aud rejected` | `resourceMatches` false → false |
  | `revoked client rejected` | `getClient` null → false |
  | `touchLastUsed called once then throttled within window` | First validate touches; immediate second (within `debounceMs`) does NOT; after window elapses, touches again |

- [x] **DoD:** All defined; the full-dance test plays Claude's client role end-to-end in-process; the shared `OAuthAccessValidator` (production code) is exercised by both the integration helper and its unit test.

---

## User Story 5 — UI: Access screen, Connected-clients screen, on-device approval

**Why:** Surface the two auth methods + the public-URL override, the client registry with revocation, and the on-device number-match approval. Follows existing Material 3 + ViewModel + nav patterns.

**Acceptance criteria:**
- [x] Dedicated **Access** screen: OAuth toggle (+ tunnel-required note + Connected-clients link), bearer toggle, bearer token field (show/hide/copy/regenerate), optional public-URL override field, warning banner when both off, confirm dialog whenever a toggle would leave BOTH methods off (UNCONDITIONAL — no network/tunnel condition). Bearer-token controls relocated out of General settings.
- [x] **Main screen** shows a warning whenever both auth methods are disabled.
- [x] **Connected clients** screen: list rows = avatar (SSRF-guarded `logo_uri` when safe, else initials) | name-or-redirect-host (top) + application_type (bottom) | created + last-used stacked right; Revoke (with confirm); empty state.
- [x] **Approval**: heads-up notification when a pending approval exists → opens an in-app approval screen listing pending request(s) with match code + Approve/Deny.

### Task 5.1 — Routes + index entry
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/navigation/Routes.kt` — add `data object Access : SettingsRoute("settings/access")` and `data object OAuthClients : SettingsRoute("settings/access/clients")`.
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/SettingsIndexScreen.kt` — add a `SettingsEntry` for Access (icon `Icons.Default.Key`, title/subtitle from new string resources), navigating to `SettingsRoute.Access.route`.
- [x] **DoD:** Routes added; Access entry shown in the settings index. (The `SettingsScreen.kt` `composable` wiring that references the new screens is added in Task 5.3, AFTER the screens exist — see ordering note there.)

### Task 5.2 — Access screen + ViewModel
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/AccessViewModel.kt` (`@HiltViewModel`, inject `SettingsRepository`): call `ensureAuthModelMigrated()` in `init` BEFORE exposing state (so the screen never shows un-migrated auth defaults — closes the migration/UI race), then expose `serverConfig` state (oauthEnabled, bearerTokenEnabled, bearerToken, publicUrlOverride, bindingAddress — `bindingAddress` is read-only, used ONLY for the auto-detect security warning below, NOT for the confirm dialog). Methods: `requestSetOauthEnabled(Boolean)` and `requestSetBearerTokenEnabled(Boolean)` — when the requested change would leave BOTH methods disabled, emit a confirm-needed UI event instead of applying (UNCONDITIONAL — NOT gated on binding/tunnel); otherwise apply immediately; `confirmDisableLastAuth()` applies the pending toggle after the user confirms; `regenerateBearerToken()`; `setPublicUrlOverride(String)` (validate via `validatePublicUrlOverride`; expose an error message on failure, persist on success); `copyBearerToken(context)` — implement a self-contained `ClipboardManager` copy inside this ViewModel (do NOT reference `MainViewModel`; no shared util extraction). Backed by `updateOauthEnabled`/`updateBearerTokenEnabled`/`updatePublicUrlOverride`/`generateNewBearerToken`. (No tunnel/binding dependency — resolves both the migration-UI race and the "active tunnel" ambiguity.)
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/AccessSettingsScreen.kt` — `TopAppBar` + sections: (1) OAuth `Switch` with supporting text "Requires an active tunnel (HTTPS, public reachability)" and, when enabled, a clickable "Connected clients" row → `onNavigateClients`; (2) Bearer-token `Switch`; bearer token `OutlinedTextField` (read-only) with show/hide, copy, regenerate trailing actions, enabled only when bearer enabled; (3) public-URL override `OutlinedTextField` (optional) with helper "Leave empty to auto-detect from the request; overrides the host used for OAuth metadata and share links" + inline validation error; (4) a prominent warning `Card`/banner shown whenever `!oauthEnabled && !bearerTokenEnabled`; (5) an `AlertDialog` shown when a toggle would leave both methods off ("This leaves the server with NO authentication. Continue?") whose confirm calls `confirmDisableLastAuth()` — unconditional, no binding/tunnel check; (6) an auto-detect security warning shown when `oauthEnabled && bindingAddress == NETWORK && publicUrlOverride.isBlank()` ("OAuth metadata and links are auto-detected from request headers; on a network binding without a trusted proxy a client could spoof the host — set a Public URL override to pin it") — satisfies the project's 0.0.0.0-exposure warning requirement for the OAuth auto-detect path.
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/GeneralSettingsScreen.kt` — remove the bearer-token display/controls block (relocated to Access). Remove now-unused bearer state/imports there.
- [x] **Action:** audit `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt` — `GeneralSettingsScreen` (being stripped) is the ONLY caller of `MainViewModel.clearBearerToken`/`generateNewBearerToken`/`copyToClipboard` (verified: `ServerScreen` copies via a LOCAL `clipboardManager.setText(...)`, not `viewModel.copyToClipboard`), so after relocation all three become orphaned and MUST be removed to avoid detekt/ktlint unused-symbol findings. Re-verify there is no remaining caller before deletion. (`showBearerToken` is local `remember` state in `GeneralSettingsScreen`, removed together with the relocated block — not a ViewModel member.)
- [x] **Action:** add a main-screen warning: modify the home/server screen `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt` (fed by `MainViewModel`, which already observes `serverConfig`) to render a prominent warning `Card` whenever `!serverConfig.oauthEnabled && !serverConfig.bearerTokenEnabled` ("Server has NO authentication — anyone who can reach it has full control"). Add the new string resource. (If `MainViewModel` does not already expose `oauthEnabled`/`bearerTokenEnabled` from its `serverConfig`, surface them; and have `MainViewModel` call `ensureAuthModelMigrated()` in `init` — like `AccessViewModel` — so the warning reflects the migrated auth model, not transient pre-migration defaults.)
- [x] **DoD:** Access screen drives both toggles + token + override; General no longer shows the token; no orphaned MainViewModel members; the confirm dialog fires on any toggle that would leave both methods off (UI only); the main screen warns when both are off.

### Task 5.3 — Connected-clients screen + ViewModel
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/OAuthClientsViewModel.kt` (`@HiltViewModel`, inject `OAuthClientRepository`): expose `clients: StateFlow<List<OAuthClient>>` from `observeClients()`; `revoke(clientId)`.
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/LogoUrlPolicy.kt` — `fun isSafeLogoUrl(url: String?): Boolean` (SSRF guard for the remote-logo fetch): non-null; scheme `https` only; host is NOT a literal IP in a private/loopback/link-local range (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, `::1`, `fc00::/7`, `fe80::/10`) and NOT `localhost`. (Redirects MUST NOT be followed and the response size/timeout MUST be capped by the loader — see below.) Unit-test this in `LogoUrlPolicyTest.kt` (https-only; private/loopback/link-local/localhost rejected; ordinary https host accepted).
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/OAuthClientsScreen.kt` — `LazyColumn` of client rows; each row: leading avatar = **the `logoUri` image when `isSafeLogoUrl(logoUri)` is true, else a circular initials badge** from `clientName` first 1–2 letters (or the redirect host's first letters when `clientName` is null), color derived from `clientId.hashCode()`. Load the remote logo via Coil (added in Task 3.1) configured to NOT follow redirects, with a small size/timeout cap, and fall back to the initials badge `onError`/while loading. Primary text = `clientName ?: redirectUris.firstOrNull()` host; secondary text = `applicationType ?: "—"`; right column (two lines) = "Created <date>" and "Last used <date>" (absolute, via the app's date formatting); trailing `IconButton` "Revoke" → `AlertDialog` confirm → `viewModel.revoke`. Empty-state text when list is empty.
- [x] **Action:** now that `AccessSettingsScreen` (Task 5.2) and `OAuthClientsScreen` (this task) exist, wire navigation in `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/SettingsScreen.kt` — add `composable(SettingsRoute.Access.route)` → `AccessSettingsScreen(onBack = { navController.popBackStack() }, onNavigateClients = { navController.navigate(SettingsRoute.OAuthClients.route) })` and `composable(SettingsRoute.OAuthClients.route)` → `OAuthClientsScreen(onBack = { navController.popBackStack() })`. (Placed here, after both screens are created, to avoid referencing not-yet-existing composables — resolves the ordering.)
- [x] **DoD:** Rows match the agreed layout; revoke confirms then removes; empty state shown; navigation wired (Access + Connected-clients reachable).

### Task 5.4 — Approval notification + in-app approval screen
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/McpApplication.kt` — add a high-importance notification channel `OAUTH_APPROVAL_CHANNEL_ID` (heads-up). (Visibility relies on the app's existing `POST_NOTIFICATIONS` handling used for the foreground-service notification; no new permission.)
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/ApprovalActivity.kt` (`@AndroidEntryPoint ComponentActivity`) hosting a Compose `ApprovalScreen`; launched from the approval notification.
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/ApprovalViewModel.kt` (`@HiltViewModel`, inject `OAuthApprovalCoordinator`): expose `pending: StateFlow<List<PendingApproval>>`; `approve(id)`, `deny(id)`.
- [x] **Action:** create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ApprovalScreen.kt` — lists pending approvals; each shows client name + the 2-digit match code prominently + "Approve"/"Deny" buttons. When none pending, show "No pending requests".
- [x] **Action:** register `ApprovalActivity` in `app/src/main/AndroidManifest.xml` (`exported=false`).
- [x] **Action:** modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt` — in `startServer()` (which runs as a `coroutineScope` job after `onStartCommand` has already called `startForeground(...)` at line ~163; do NOT add another `startForeground` call), launch a tracked Job in `coroutineScope` that collects `approvalCoordinator.observePending()` (reusing the `approvalCoordinator` field already injected into `McpServerService` in Task 4.5 — do NOT add a duplicate injection): when non-empty post a SINGLE heads-up notification (ONE fixed notification id, not one-per-request — content e.g. "N connection request(s) pending — tap to review"; update the count as the list changes) whose content intent opens `ApprovalActivity`; cancel it when the list becomes empty. (Single collapsed notification + the `MAX_PENDING_APPROVALS` cap prevent notification flooding from `/authorize` spam.) Store the Job in a field and cancel it in `onDestroy` (in addition to `coroutineScope.cancel()`).
- [x] **Action:** add string resources to `app/src/main/res/values/strings.xml` for all new Access/clients/approval/notification labels.
- [x] **Note:** the `observePending()` → post/cancel-notification mapping depends on Android `NotificationManager` and is verified by manual QA (Task 7.5) and the e2e test (Task 7.4), not a JVM unit test; the collector Job cancellation is covered by the existing `coroutineScope.cancel()` in `onDestroy` plus the explicit Job field cancel.
- [x] **DoD:** Pending approval raises a heads-up notification after the service is foregrounded; tapping opens the approval screen; approve/deny drive the coordinator; notification cleared when no pending requests; collector cancelled on destroy.

### Task 5.5 — Tests (Story 5)
- [x] **Action:** create ViewModel tests. **Setup:** MockK repositories; Turbine for StateFlow; `MainDispatcherRule` if present in the project (else `Dispatchers.setMain`).

  **File:** `ui/viewmodels/AccessViewModelTest.kt`
  | Test | Verifies |
  |------|----------|
  | `enabling oauth calls repository` | updateOauthEnabled(true) |
  | `disabling the last method requests confirm` | Turning off the only remaining enabled method → NO repo write; confirm event emitted (unconditional — no binding/tunnel dependency) |
  | `confirmDisableLastAuth applies the change` | updateBearerTokenEnabled(false) after confirm |
  | `disabling one method while other on applies directly` | No confirm needed |
  | `regenerate calls generateNewBearerToken` | Repo invoked |
  | `valid override persists, invalid surfaces error` | updatePublicUrlOverride called only for valid input |

  **File:** `ui/viewmodels/OAuthClientsViewModelTest.kt`
  | Test | Verifies |
  |------|----------|
  | `exposes clients from repository` | Flow mirrored |
  | `revoke delegates to repository` | revoke(id) called |

  **File:** `ui/viewmodels/ApprovalViewModelTest.kt`
  | Test | Verifies |
  |------|----------|
  | `exposes pending approvals` | Flow mirrored |
  | `approve and deny delegate to coordinator` | approve/deny called |

- [x] **DoD:** Tests defined.

---

## User Story 6 — Documentation

**Why:** Keep the source-of-truth docs accurate for the new auth model and OAuth AS.

**Acceptance criteria:**
- [x] PROJECT.md, ARCHITECTURE.md, README.md reflect the new auth model, OAuth AS, approval flow, public-URL override, and adb keys. Mermaid diagrams validated with `mmdc`.

### Task 6.1 — PROJECT.md
- [x] **Action:** modify `docs/PROJECT.md` — document the auth model (`oauth_enabled` default false, `bearer_token_enabled` default true, `bearer_token` value, `public_url_override`; "no auth when both off"), the migration, the new DataStore keys, the JWT signing secret, token TTLs, client-registry cap, and the OAuth AS endpoints. Update the Authorization section to reflect dual-accept. CRITICAL: the new model REMOVES the old "empty bearer token = auth disabled" semantics — locate and rewrite EVERY stale assertion of it, including (search for the current text; line numbers approximate) ~line 175 ("When the bearer token is empty, the plugin skips authentication entirely"), ~lines 582–583 ("Clearing the token disables bearer-token authentication" / "When the bearer token is empty, the server skips authentication"), and ~line 654 (`--es bearer_token ""` to disable authentication). Replace with: authentication is controlled by `bearer_token_enabled`/`oauth_enabled`; clearing the token while `bearer_token_enabled=true` fails CLOSED (401), it does NOT open the server.
- [x] **DoD:** Settings/defaults documented; consistent with implementation.

### Task 6.2 — ARCHITECTURE.md
- [x] **Action:** modify `docs/ARCHITECTURE.md` — add the OAuth AS components (`mcp/oauth/*`, `OAuthClientRepository`, approval coordinator, combined auth plugin, request-base-URL helper) and a Mermaid sequence diagram of the discovery→DCR→authorize(approval)→token→/mcp flow. Validate every Mermaid diagram with `mmdc` (load nvm first if needed).
- [x] **DoD:** Diagram validated with `mmdc`; components described.

### Task 6.3 — README.md
- [x] **Action:** modify `README.md` — add a "Connect from Claude.ai" section: requires a tunnel (HTTPS, public reachability); enter the connector URL as `https://<host>/mcp`; leave OAuth Client ID/Secret blank (DCR); approve the connection on the device using the 2-digit code; manage/revoke clients in Settings → Access → Connected clients; optional public-URL override for non-standard topologies.
- [x] **DoD:** Accurate user-facing steps.

### Task 6.4 — MCP_TOOLS.md (only if needed)
- [x] **Action:** modify `docs/MCP_TOOLS.md` ONLY to note that share-content URLs are now derived from the request host / override (no new tools added). If nothing else changes, leave untouched.
- [x] **DoD:** No stale tool docs; no new tools claimed.

---

## User Story 7 — Quality gates + full ground-up verification

**Why:** Per the plan workflow, linting/tests/build run once at the end; then verify the ENTIRE implementation against this plan and the spike-confirmed behavior. This is the final, mandatory double-check.

**Acceptance criteria:**
- [x] `make lint` clean (ktlint + detekt; no suppressions added to mask issues).
- [x] Full unit + integration suite passes (`make test`), captured to a log.
- [x] `./gradlew build` succeeds with no warnings/errors.
- [x] OAuth e2e (redroid) test passes — a scripted client drives DCR→authorize→approve→token→/mcp on a real Android runtime.
- [ ] Manual QA against real Claude.ai performed and recorded.
- [x] A meticulous ground-up review confirms every action below is implemented and matches the plan + spike facts; `code-reviewer` (plan-compliance mode) returns clean.

### Task 7.1 — Lint
- [x] **Action:** run `make lint 2>&1 | tee /tmp/p49-lint.log | tail -40`; fix all violations at the root (no `@Suppress` to silence). Re-run until clean (overwrite the same log path).
- [x] **DoD:** Zero ktlint/detekt violations.

### Task 7.2 — Tests
- [x] **Action:** run `make test 2>&1 | tee /tmp/p49-test.log | tail -40`; inspect the captured log; fix all failures (including any unrelated broken tests encountered). Re-run once per fix cycle (same log path).
- [x] **DoD:** All unit + JVM integration tests pass.

### Task 7.3 — Build
- [x] **Action:** run `./gradlew build 2>&1 | tee /tmp/p49-build.log | tail -60`; resolve any warnings/errors (java-jwt resolution included).
- [x] **DoD:** Build succeeds, no warnings/errors.

### Task 7.4 — E2E (redroid): OAuth flow
- [x] **Action:** add a headless approval hook for e2e that is GENUINELY release-absent. IMPORTANT: the existing `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/debug/E2EConfigReceiver.kt` ships in `main` (it IS in the release APK) and is `exported="true"` with NO `BuildConfig.DEBUG` runtime guard — it MUST NOT host an OAuth-approval bypass (that would let any local app/adb caller approve authorizations in release = auth bypass). Instead create a DEBUG-SOURCE-SET-ONLY receiver `app/src/debug/kotlin/com/danielealbano/androidremotecontrolmcp/debug/OAuthApprovalTestReceiver.kt` (`@AndroidEntryPoint`, injects `OAuthApprovalCoordinator`) registered ONLY in a new `app/src/debug/AndroidManifest.xml`, that approves a pending request by `id` extra (or approve-all-pending). Because it lives in the `debug` source set it does NOT exist in the release APK at all; additionally early-return when `!BuildConfig.DEBUG` (belt-and-suspenders). The bypass MUST NOT be reachable in release.
- [x] **Action:** add an OAuth e2e test under `e2e-tests/src/test/kotlin/` (new file, e.g. `OAuthFlowE2ETest.kt`) that, against the app running in the redroid container with OAuth enabled (configure via the existing adb/E2E config path: `oauth_enabled=true`, `bearer_token_enabled=false`), acts as the OAuth client over the container's forwarded HTTP port (the test is its own client, so plain HTTP + an `http://localhost/callback` redirect from the allowlist are fine — no tunnel needed): `GET /.well-known/oauth-protected-resource/mcp` → `GET /.well-known/oauth-authorization-server` → `POST /register` → build PKCE → `GET /authorize` (parse the approval id from the consent page) → trigger the debug approval broadcast → poll `GET /authorize/status` → extract `code` → `POST /token` → connect the MCP SDK client to `/mcp` with the access token and `callTool` a basic tool (e.g. a screen/utility tool) → assert success. Tear down the container (follow the existing `e2e-tests` Testcontainers patterns).
- [x] **DoD:** E2E test drives the full OAuth flow on a real Android runtime and passes; the debug approval hook is debug/E2E-only. (This also exercises java-jwt + jackson on-device, covering the I-04/transitive-dependency runtime risk.)

### Task 7.5 — Manual QA (real Claude.ai) — **Manual Test**
- [ ] **Action:** install the debug APK; enable OAuth in Settings → Access; start the server with a tunnel; add the connector in Claude.ai (URL `https://<host>/mcp`, OAuth fields blank); verify the heads-up approval notification appears and tapping it opens the in-app approval screen showing the matching 2-digit code; approve on-device; confirm the notification clears; confirm tools list and a tool call succeed; confirm the client appears under Connected clients (name "Claude", application_type "web", created/last-used populated); revoke and confirm Claude loses access. **This step also verifies the residual risk: SDK 0.8.3 negotiates a protocolVersion Claude's `2025-11-25` client accepts on the real `/mcp`.**
- [ ] **DoD:** Documented PASS/FAIL with observations; any failure fixed and re-verified.

### Task 7.6 — Ground-up double-check (FINAL)
- [x] **Action:** Re-read THIS plan and the memory `oauth2-self-contained-as-design` from disk. Walk EVERY action in Stories 1–6 and verify, in the actual code, that it is implemented and matches: (a) request-base-URL derivation + override + share-URL fix + coroutine-context propagation; (b) explicit auth flags + migration (idempotent) + override + adb; (c) JWT/PKCE/registry(+snapshot)/auth-code/approval domain + TTLs + cap; (d) metadata exactly as the spike trace requires (path-suffixed PRM is the `WWW-Authenticate` target; bare AS metadata + aliases), DCR fields (client_name, redirect_uris, application_type, logo_uri with SSRF-guarded rendering), authorize+approval, token client-binding + rotation/reject-only, combined auth + open-when-both-off + dual-accept; (e) UI Access/clients/approval per the agreed layouts; (f) docs. Confirm NO out-of-scope files were altered and NO plan/`docs/plans` files were modified except checkmarks.
- [x] **Action:** spawn the `code-reviewer` subagent in plan-compliance mode against the full diff. Fix ALL findings (CRITICAL/WARNING/INFO). Re-run `code-reviewer` until clean. If any finding cannot be resolved or the implementation intentionally diverges (e.g., a bug fix), STOP and report to the user.
- [ ] **DoD:** Every action verified implemented and matching; `code-reviewer` returns a clean PASS; quality gates (7.1–7.3) green; e2e (7.4) passing; manual QA (7.5) recorded.

---

## Out-of-scope / untouched (guardrails)
- Do NOT modify `docs/plans/42_adb_pem_certificate_import_20260314235254.md` or any other plan file (except this plan's checkmarks/findings).
- Do NOT change the `/s/` capability-link semantics beyond switching its base-URL source (Story 1).
- No new device-content MCP tools are added, so no `UNTRUSTED_CONTENT_WARNING` changes are required.
- HTTPS/cert (`SecuritySettingsScreen`), tunnel providers, and existing tools remain unchanged except where explicitly listed.
- The existing `E2EConfigReceiver` (in `main`, `exported="true"`, with a KDoc that inaccurately claims it is "ONLY included in debug builds" and NO `BuildConfig.DEBUG` guard) is a PRE-EXISTING concern NOT fixed by this plan. This plan deliberately does NOT host the OAuth-approval bypass there — Task 7.4 uses a separate debug-source-set receiver. Tightening/relocating `E2EConfigReceiver` itself should be raised as a separate task.
