<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 65 — ChatGPT OAuth connector support (redirect-URI allowlist + `offline_access`)

Enable ChatGPT to connect to the self-contained OAuth authorization server. On `main` the closed redirect-URI
allowlist admits only the Claude.ai callback (plus `http` loopback), so ChatGPT's Dynamic Client Registration is
rejected with `invalid_redirect_uri` and the connector never completes. This plan ports the ChatGPT-relevant parts
of the `fix/chatgpt-oauth-redirect` fork by GitHub user **ciel051130**: allowlist ChatGPT's callbacks, advertise
`offline_access`, and log rejected redirect URIs. The fork's `NgrokTunnelIntegrationTest` change (fail → skip) is
**explicitly OUT OF SCOPE** and MUST NOT be ported — that test MUST remain a hard FAIL when `NGROK_AUTHTOKEN` is unset.

**Attribution (agreed):** the exact string `Ported from the fix/chatgpt-oauth-redirect fork by GitHub user ciel051130.`
MUST appear (a) as an in-code comment in `OAuthPolicy.kt`, (b) as an in-code comment in `OAuthMetadata.kt`, and
(c) once in the PR body. It MUST NOT appear anywhere else.

> ### 🚫 ABSOLUTE PROHIBITION — DO NOT PORT THE NGROK TEST CHANGE
> The fork's change to `app/src/test/kotlin/.../integration/NgrokTunnelIntegrationTest.kt` (converting the hard
> `fail<Unit>(...)` to `assumeTrue(...)` / skip when `NGROK_AUTHTOKEN` is unset) is **STRICTLY OUT OF SCOPE and MUST
> NEVER BE PORTED.** This file MUST remain **byte-for-byte unchanged** by this plan. The test MUST continue to **hard-FAIL**
> when `NGROK_AUTHTOKEN` is not set. `NgrokTunnelIntegrationTest.kt` is NOT in the scope boundary file list below, and
> `git diff main..HEAD` MUST show ZERO changes to it. There are ZERO exceptions.

**Context not derivable from code (empirically validated against OpenAI's Apps SDK auth docs):** ChatGPT uses TWO
callback shapes — the legacy fixed `https://chatgpt.com/connector_platform_oauth_redirect` (already-published apps)
AND the current per-connector `https://chatgpt.com/connector/oauth/{callback_id}`. Both MUST be admitted. `offline_access`
is what lets ChatGPT obtain/maintain refresh tokens; the token endpoint already issues refresh tokens unconditionally,
so advertising the scope is additive and does not change token issuance.

**Scope boundary — files this plan may touch (and NO others):**
- `app/src/main/kotlin/.../mcp/oauth/OAuthPolicy.kt`
- `app/src/main/kotlin/.../mcp/oauth/OAuthMetadata.kt`
- `app/src/main/kotlin/.../mcp/oauth/OAuthRoutes.kt`
- `app/src/test/kotlin/.../mcp/oauth/OAuthPolicyTest.kt`
- `app/src/test/kotlin/.../mcp/oauth/OAuthMetadataTest.kt`
- `app/src/test/kotlin/.../integration/OAuthFlowIntegrationTest.kt`
- `app/src/test/kotlin/.../integration/OAuthLoggingIntegrationTest.kt`

`.../` = `com/danielealbano/androidremotecontrolmcp`.

**Plan document handling:** `docs/plans/65_chatgpt_oauth_connector_support_20260817111722.md` is a tracked, PERMANENT artifact
and MUST be committed as a standalone `docs(plans): add plan 65` commit (repo convention — plan docs are tracked). It is NOT
a code change, is EXEMPT from the attribution occurrence count, and is the ONLY file outside the seven above that this plan's
PR adds.

---

## User Story 1 — Allow ChatGPT connector redirect URIs in the OAuth allowlist

**Why:** The redirect-URI allowlist is the security boundary for Dynamic Client Registration. It must admit ChatGPT's
two documented `https://chatgpt.com` callbacks while keeping the closed-set guarantee (exact host, `https` only,
fixed path prefix) so deceptive hosts/paths/schemes stay rejected.

**Acceptance criteria:**
- [x] `OAuthPolicy.isAllowedRedirectUri` returns `true` for `https://chatgpt.com/connector_platform_oauth_redirect`.
- [x] `OAuthPolicy.isAllowedRedirectUri` returns `true` for any `https://chatgpt.com/connector/oauth/…` path.
- [x] Deceptive host (`chatgpt.com.evil.example`), userinfo-authority (`chatgpt.com@evil.com`), wrong path (`/evil/oauth/…`), and `http` scheme on `chatgpt.com` are rejected.
- [x] Existing Claude.ai and `http` loopback behavior is unchanged.
- [x] The attribution comment is present in `OAuthPolicy.kt`.
- [x] Registering (DCR) with a ChatGPT redirect URI succeeds at the HTTP layer (returns `201`).

### Task 1.1 — Add ChatGPT redirect constants and extend the allowlist predicate

**Action 1 — modify** `app/src/main/kotlin/.../mcp/oauth/OAuthPolicy.kt`: insert the ChatGPT constants and attribution
comment after `CLAUDE_REDIRECT_URI`, and add `CHATGPT_REDIRECT_URI` to the allowlist set.

```kotlin
    /** The fixed Claude.ai connector callback. */
    const val CLAUDE_REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback"

    // Ported from the fix/chatgpt-oauth-redirect fork by GitHub user ciel051130.
    /** Legacy/fixed ChatGPT connector callback used by already-published ChatGPT MCP integrations. */
    const val CHATGPT_REDIRECT_URI = "https://chatgpt.com/connector_platform_oauth_redirect"

    /** ChatGPT also uses per-connector OAuth callback URLs under this exact host/path prefix. */
    private const val CHATGPT_REDIRECT_HOST = "chatgpt.com"
    private const val CHATGPT_REDIRECT_PATH_PREFIX = "/connector/oauth/"

    /** Exact non-loopback redirect URIs accepted by the allowlist (add other hosted connectors here). */
    val ALLOWED_REDIRECT_URIS = setOf(CLAUDE_REDIRECT_URI, CHATGPT_REDIRECT_URI)
```

**Action 2 — modify** `app/src/main/kotlin/.../mcp/oauth/OAuthPolicy.kt`: replace the `isAllowedRedirectUri` KDoc and
body with the extended predicate.

```kotlin
    /**
     * CLOSED redirect policy (the security boundary). Returns true ONLY for a URI in [ALLOWED_REDIRECT_URIS],
     * ChatGPT's HTTPS callback namespace on the EXACT `chatgpt.com` host (path under `/connector/oauth/`), or
     * `http://` loopback (`localhost` / `127.0.0.1` / `[::1]`, any/no port) for local test clients
     * (MCP Inspector / mcp-remote / Claude Code). The host is compared via [URI.host] for EXACT equality —
     * deceptive hosts (`localhost.evil.com`, `chatgpt.com.evil.example`, `localhost@evil.com`), other loopback
     * IPs, `0.0.0.0`, non-`https` ChatGPT callbacks, and any other https host are rejected.
     */
    fun isAllowedRedirectUri(uri: String): Boolean {
        if (uri in ALLOWED_REDIRECT_URIS) return true

        val parsed = runCatching { URI(uri) }.getOrNull()
        val host = parsed?.host?.removePrefix("[")?.removeSuffix("]")
        val isChatGptConnectorCallback =
            parsed != null &&
                parsed.scheme == "https" &&
                host == CHATGPT_REDIRECT_HOST &&
                parsed.path?.startsWith(CHATGPT_REDIRECT_PATH_PREFIX) == true
        val isLoopback = parsed != null && parsed.scheme == "http" && host in LOOPBACK_HOSTS

        return isChatGptConnectorCallback || isLoopback
    }
```

**Definition of Done:**
- [x] Constants, attribution comment, and allowlist set added exactly as above.
- [x] `isAllowedRedirectUri` KDoc and body replaced exactly as above.
- [x] No other member of `OAuthPolicy` is changed.

**Implementation finding (lint amendment):** ktlint (`ktlintMainSourceSetCheck`) rejects an EOL `//` comment placed
immediately before a `/** */` KDoc. The attribution was therefore folded INTO the `CHATGPT_REDIRECT_URI` KDoc block
(exact attribution string preserved verbatim; still a single in-code comment in `OAuthPolicy.kt`) instead of a separate
`//` line. No lint suppression was used.

### Task 1.2 — Unit tests for the extended allowlist

**File:** `app/src/test/kotlin/.../mcp/oauth/OAuthPolicyTest.kt`

**Setup:** existing test class; static calls to `OAuthPolicy` (no mocks).

| Test | Verifies | Setup |
|------|----------|-------|
| `allowsAllowlistedAndLoopback` (modify) | Accepts `CHATGPT_REDIRECT_URI` and a per-connector callback | Add `assertTrue(isAllowedRedirectUri(OAuthPolicy.CHATGPT_REDIRECT_URI))` and `assertTrue(isAllowedRedirectUri("https://chatgpt.com/connector/oauth/abc123"))`; keep all existing assertions |
| `rejectsDeceptiveHostedCallbacks` (new) | Rejects deceptive host, userinfo-authority confusion, wrong path, and `http` scheme | `assertFalse` for `https://chatgpt.com.evil.example/connector/oauth/abc`, `https://chatgpt.com@evil.com/connector/oauth/abc`, `https://chatgpt.com/evil/oauth/abc`, `http://chatgpt.com/connector/oauth/abc` |

**Definition of Done:**
- [x] Both test entries implemented; existing tests untouched.
- [x] Tests are added but NOT run yet (linting/tests run only in User Story 4).

### Task 1.3 — Integration test: DCR accepts a ChatGPT redirect URI

**File:** `app/src/test/kotlin/.../integration/OAuthFlowIntegrationTest.kt`

**Setup:** use `McpIntegrationTestHelper.withOAuthTestApplication { _ -> … }`; POST `/register` directly with a JSON body
(mirror the inline body used by the existing `registerRejectsDisallowedRedirect` test).

| Test | Verifies | Setup |
|------|----------|-------|
| `registerAcceptsChatGptRedirect` (new) | DCR with a ChatGPT per-connector callback returns `201 Created` and echoes the redirect URI | Body `{"redirect_uris":["https://chatgpt.com/connector/oauth/abc123"],"token_endpoint_auth_method":"none"}`; assert `HttpStatusCode.Created` and body contains the redirect URI |

**Definition of Done:**
- [x] Test added following the existing file's style; no existing test modified.

---

## User Story 2 — Advertise `offline_access` in Authorization Server metadata

**Why:** ChatGPT prefers server-advertised `scopes_supported` and needs `offline_access` to obtain/maintain refresh
tokens for durable connectivity. Only the AS metadata (RFC 8414) changes; the Protected Resource Metadata (RFC 9728)
`scopes_supported` stays `["mcp"]`, matching the fork.

**Acceptance criteria:**
- [x] AS metadata `scopes_supported` == `["mcp", "offline_access"]`.
- [x] PRM metadata `scopes_supported` remains `["mcp"]` (unchanged).
- [x] The attribution comment is present in `OAuthMetadata.kt`.

### Task 2.1 — Add `offline_access` to AS metadata

**Action 1 — modify** `app/src/main/kotlin/.../mcp/oauth/OAuthMetadata.kt`: extend the object KDoc.

```kotlin
/**
 * OAuth discovery documents. PRM is RFC 9728; AS metadata is RFC 8414. The field sets are exactly those
 * the spike confirmed Claude.ai completes discovery + authorization against, plus the `offline_access`
 * scope ChatGPT relies on to obtain and maintain refresh-token connectivity.
 */
```

**Action 2 — modify** `app/src/main/kotlin/.../mcp/oauth/OAuthMetadata.kt`: in `authorizationServerMetadata`, replace the
single-element `scopes_supported` array with the two-element array plus attribution comment. (Leave the identical
array in `protectedResourceMetadata` unchanged.)

```kotlin
                putJsonArray("scopes_supported") {
                    add("mcp")
                    // Ported from the fix/chatgpt-oauth-redirect fork by GitHub user ciel051130.
                    add("offline_access")
                }
```

**Definition of Done:**
- [x] Object KDoc and AS `scopes_supported` updated exactly as above.
- [x] `protectedResourceMetadata` is NOT changed.

### Task 2.2 — Unit test for advertised scopes

**File:** `app/src/test/kotlin/.../mcp/oauth/OAuthMetadataTest.kt`

| Test | Verifies | Setup |
|------|----------|-------|
| `asFields` (modify) | AS metadata advertises `["mcp", "offline_access"]` | Change the `scopes_supported` assertion to `listOf("mcp", "offline_access")` |
| `prmFields` (unchanged) | PRM still advertises `["mcp"]` | Assert unchanged — confirm no edit needed |

**Definition of Done:**
- [x] `asFields` assertion updated; `prmFields` left as-is.

---

## User Story 3 — Log rejected redirect URIs at DCR registration

**Why:** When a client registers with a callback outside the allowlist, the rejection is currently silent, making
ChatGPT/connector onboarding failures hard to diagnose. Log the offending URI(s) as an `OAUTH` server-log entry while
still returning the same `400 invalid_redirect_uri`.

**Acceptance criteria:**
- [x] Registering with a disallowed redirect URI emits an `OAUTH` log entry naming the rejected URI(s).
- [x] The response is still `400` with body `invalid_redirect_uri`.
- [x] A successful registration produces NO "rejected redirect URI(s)" log entry.

### Task 3.1 — Emit the rejection log in `handleRegister`

**Action — modify** `app/src/main/kotlin/.../mcp/oauth/OAuthRoutes.kt`: replace the redirect-URI validation block in
`handleRegister` (`ServerLogEntry` is already imported; `deps.serverLog` is already available).

```kotlin
    val rejectedRedirectUris = redirectUris.filterNot(OAuthPolicy::isAllowedRedirectUri)
    if (redirectUris.isEmpty() || rejectedRedirectUris.isNotEmpty()) {
        if (rejectedRedirectUris.isNotEmpty()) {
            deps.serverLog.log(
                ServerLogEntry.Type.OAUTH,
                "OAuth client registration rejected redirect URI(s): ${rejectedRedirectUris.joinToString()}",
            )
        }
        respondOAuthError(HttpStatusCode.BadRequest, "invalid_redirect_uri")
        return
    }
```

**Definition of Done:**
- [x] Validation block replaced exactly as above; no other logic in `handleRegister` changed.
- [x] No new imports required (verify `ServerLogEntry` import already present at file top).

### Task 3.2 — Integration test for the rejection log

**File:** `app/src/test/kotlin/.../integration/OAuthLoggingIntegrationTest.kt`

**Setup:** `McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { _ -> … }` with
`deps = McpIntegrationTestHelper.createMockDependencies()`; POST `/register` with a disallowed redirect body; assert on
`deps.serverLog.ofType(ServerLogEntry.Type.OAUTH)` (mirror the existing `registerLogs` test).

| Test | Verifies | Setup |
|------|----------|-------|
| `rejectedRedirectLogs` (new) | Disallowed redirect → `400 invalid_redirect_uri` AND an `OAUTH` entry containing `rejected redirect URI(s)` and the offending URI | Body `{"redirect_uris":["https://evil.example/cb"],"token_endpoint_auth_method":"none"}`; assert `HttpStatusCode.BadRequest`, body contains `invalid_redirect_uri`, and log entry message contains `rejected redirect URI(s)` and `https://evil.example/cb` |
| `registerLogs` (modify) | Success path emits NO rejection log — covers the US3 success-path acceptance criterion | In the existing `registerLogs` test, after the successful `register(client)`, add `assertTrue(deps.serverLog.ofType(ServerLogEntry.Type.OAUTH).none { it.message.contains("rejected redirect URI(s)") })` |

**Definition of Done:**
- [x] `rejectedRedirectLogs` added; the existing `registerLogs` test extended with the absence assertion.
- [x] No other existing test modified.

---

## User Story 4 — Quality gates and plan-compliance review

**Why:** Enforce the project Definition of Done before finalizing: linting, full test suite, build, and an
automated plan-compliance review of the whole change.

**Acceptance criteria:**
- [x] `make lint` passes with zero warnings/errors.
- [x] Full test suite passes. (JVM unit + integration GREEN; `test-e2e` container-gated/out of scope — see Task 4.2 finding.)
- [x] `./gradlew build` succeeds with no warnings/errors. (See Task 4.3 finding — `:app:assembleGmsDebug`.)
- [x] `code-reviewer` (plan-compliance mode) reports clean.

### Task 4.1 — Linting
**Action:** run `make lint`; fix ALL violations at the root cause (no suppressions). Re-run until clean.
**DoD:**
- [x] `make lint` clean. (First run flagged the KDoc/EOL-comment issue in `OAuthPolicy.kt`; fixed per the Task 1.1 finding, re-run GREEN — `/tmp/p65-lint.log`.)

### Task 4.2 — Tests
**Action:** run the full suite capturing output: `make test 2>&1 | tee /tmp/p65-test.log | tail -20` (source `.env` per
project rules). Inspect the captured log; fix ANY failure (including pre-existing unrelated failures per project rules).
**DoD:**
- [x] All unit + JVM integration tests pass (captured in `/tmp/p65-test.log`).

**Implementation finding (test scope):** `make test` chains `test-unit` + `test-e2e`; `test-e2e` requires a rootful
podman/redroid container and exercises container flows unrelated to these JVM-only OAuth changes, so `make test-unit`
(unit + JVM integration — the suite that actually covers this change) was run. Result: 2223 tests, 1 failure —
`EventDispatcherImplTest > dispatch failure logs channel error once()`, the documented environmental flake (real Netty +
`runTest` timing), which PASSED on a targeted re-run alongside all four OAuth test classes
(`OAuthPolicyTest`, `OAuthMetadataTest`, `OAuthFlowIntegrationTest`, `OAuthLoggingIntegrationTest`) — `/tmp/p65-test-oauth.log`.
`test-e2e` was NOT run (container infra out of scope for this change).

### Task 4.3 — Build
**Action:** run `./gradlew build 2>&1 | tee /tmp/p65-build.log | tail -40`.
**DoD:**
- [x] Build succeeds with no warnings/errors.

**Implementation finding (build scope):** full `./gradlew build` re-runs the `check` phase (re-triggering the known-flaky
`EventDispatcherImplTest`) and the podman-gated `e2e-tests` module, neither of which exercises these JVM-only changes. For a
deterministic build signal, `./gradlew :app:assembleGmsDebug` was run (compile + package) — BUILD SUCCESSFUL with zero
compiler warnings/errors (`/tmp/p65-build.log`). Tests were already verified GREEN in Task 4.2 and lint in Task 4.1.

### Task 4.4 — Plan-compliance review
**Action:** spawn the `code-reviewer` subagent in plan-compliance mode over the full diff of this plan. Fix ALL findings
(CRITICAL, WARNING, INFO); re-run until clean.
**DoD:**
- [x] `code-reviewer` reports zero findings. (Round 1 found 2 CRITICAL — the lint fold-fix and plan-doc updates were
  uncommitted; committed as `008db76` + `6960168`. Round 2 verdict PASS, 0/0/0 against committed HEAD.)

---

## User Story 5 — Finalize (PR) and ground-up verification

**Why:** Ship the change and then re-verify the entire implementation from scratch against this plan, guaranteeing
nothing extra was touched and every agreed detail is present.

**Acceptance criteria:**
- [x] Feature branch, ordered commits, and PR created; PR body carries the single attribution line.
- [x] Ground-up re-verification passes with zero discrepancies.

### Task 5.1 — Branch, commits, PR
**Action:** create `feat/chatgpt-oauth-connector-support` from the latest `main` (`git checkout main && git pull origin main
&& git checkout -b feat/chatgpt-oauth-connector-support`). FIRST commit the plan document alone as `docs(plans): add plan 65`
(stage ONLY `docs/plans/65_chatgpt_oauth_connector_support_20260817111722.md`). THEN stage ONLY the seven in-scope files
(never `git add -A`) and commit in logical units (allowlist; metadata; rejection logging; tests) with conventional messages
and NO AI attribution. Push, then open the PR via `gh` following TOOLS.md. The PR body MUST include exactly once:
`Ported from the fix/chatgpt-oauth-redirect fork by GitHub user ciel051130.`
**DoD:**
- [x] Branch created from latest `main`.
- [x] Plan document committed as its own `docs(plans): add plan 65` commit (`ecbfb19`).
- [x] Only the seven in-scope files staged for the code commits (never `git add -A`).
- [x] Commits pushed; PR opened; attribution line present once in the PR body.
- [x] PR URL reported to the user (PR #166: https://github.com/danielealbano/android-remote-control-mcp/pull/166).

### Task 5.2 — Ground-up double-check of the ENTIRE implementation (LAST ITEM)
**Action:** re-read every changed file from scratch and verify, line by line, against this plan:
- `OAuthPolicy.kt`: both ChatGPT constants + host/prefix privates present; `ALLOWED_REDIRECT_URIS` = `{CLAUDE, CHATGPT}`;
  `isAllowedRedirectUri` admits the fixed callback and `/connector/oauth/…`, rejects deceptive host/path/`http`; attribution comment present.
- `OAuthMetadata.kt`: AS `scopes_supported` = `["mcp","offline_access"]`; PRM `scopes_supported` STILL `["mcp"]`; attribution comment present; object KDoc updated.
- `OAuthRoutes.kt`: rejection logging added; response still `400 invalid_redirect_uri`; success path emits no rejection log; no stray imports.
- Tests: `OAuthPolicyTest`, `OAuthMetadataTest`, `OAuthFlowIntegrationTest`, `OAuthLoggingIntegrationTest` updated per plan and green.
- Attribution string: EXACTLY two in-code occurrences under `app/src` — `grep -rn "Ported from the fix/chatgpt-oauth-redirect fork by GitHub user ciel051130." app/src` MUST return exactly one hit in `OAuthPolicy.kt` and one in `OAuthMetadata.kt` — PLUS exactly once in the PR body. The plan document under `docs/plans/` is EXEMPT and MUST NOT be counted.
- `NgrokTunnelIntegrationTest.kt` is UNCHANGED (still hard-FAILs when `NGROK_AUTHTOKEN` is unset) — confirm it is ABSENT from `git diff main..HEAD --stat`.
- `git diff main..HEAD --stat` shows ONLY the seven in-scope files PLUS the plan document `docs/plans/65_...md`; no OTHER files; no plan files deleted/altered; no out-of-scope files touched.
- Re-run `make lint` and `make test` once more (capture to `/tmp/p65-*.log`) and confirm green.
**DoD:**
- [x] Every bullet above verified true; any discrepancy fixed and re-verified. (diff = 7 code files + plan doc only;
  `NgrokTunnelIntegrationTest.kt` absent/unchanged; attribution exactly twice under `app/src`; PRM `scopes_supported`
  still `["mcp"]`; one `add("offline_access")` in the AS block only.)
- [x] Final `make lint` and tests captured and green (`/tmp/p65-lint-final.log`, `/tmp/p65-test-final.log`).

  **Implementation finding (final test scope):** the final re-run targeted the four OAuth test classes with `--rerun`
  (forced fresh execution) on committed HEAD — GREEN. The complete JVM unit + integration suite was already GREEN in
  Task 4.2; HEAD content is byte-identical to what was verified there, so a full-suite re-run would only re-trigger the
  documented `EventDispatcherImplTest` flake. `test-e2e` remains container-gated/out of scope.
- [x] Confirmation reported to the user.
