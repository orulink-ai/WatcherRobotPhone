<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 46 — Optional Auth Tokens

## Context

Two distinct auth tokens currently REQUIRE non-empty values, which prevents MCP clients that cannot set custom `Authorization` headers (e.g. Claude Desktop) from connecting, and prevents the event channel from talking to endpoints that do not require auth.

In scope:
1. **MCP bearer token** (`ServerConfig.bearerToken`) — allow empty (auth skipped). Backend already supports empty in `BearerTokenAuthPlugin`; UI, repository regeneration, and the ADB headless config path block it.
2. **Event channel auth token** (`EventChannelConfig.authToken`) — allow empty (no `Authorization` header sent on outgoing dispatch).

Locked design decisions (from discussion):
- Bearer token: keep one-shot install-time generation (guarded by a new `BEARER_TOKEN_INITIALIZED` flag in DataStore). After that, clearing sticks; no auto-regeneration.
- Upgrade-safe: users upgrading from a version without the flag MUST keep any existing non-empty bearer token (the flag is set on first read, but the existing token is preserved).
- UI: Add a Clear icon button to the bearer token field; the existing Regenerate icon button is reused (no new "Generate" CTA). Both Clear and Regenerate stay gated on the existing `isEnabled` (server-not-running) clause, matching the pre-existing UI pattern for token mutations.
- Connection info: hide the Bearer Token row entirely when empty; omit the corresponding line from the copy-all string.
- Persistent warning banner on the General Settings screen above the bearer token label when token is empty.
- Repository API for clearing: reuse `updateBearerToken("")` (no new repo method). The ViewModel exposes a thin `clearBearerToken()` helper that calls it — this is NOT a new repository method.
- ADB headless config (`--es bearer_token ""`) MUST also be able to clear the token, so the existing "ignore empty" early-exit in `AdbConfigHandler.applyBearerToken` is removed.
- Event channel: remove the three `authToken.isBlank()` gates and make the outgoing `Authorization` header conditional.

Out of scope:
- HTTPS / certificate behavior.
- ngrok authtoken behavior (unrelated to MCP / event-channel auth).
- Any change to the event-channel `endpointUrl` requirement (still mandatory).
- Compose UI tests (the project does not currently use Compose UI testing). Banner and Clear-button rendering are verified manually in Story 4.

---

## User Story 1 — Optional MCP bearer token

**Why**: Allow the MCP server to accept connections from clients that cannot send `Authorization` headers (e.g. Claude Desktop). The Ktor auth plugin already skips auth when `expectedToken.isEmpty()`; this story removes the silent auto-regeneration in `getServerConfig()`, removes the empty-string ignore in `AdbConfigHandler`, and exposes a Clear control in the UI.

### Acceptance criteria
- [ ] On fresh install (no `BEARER_TOKEN_INITIALIZED_KEY` set and no stored token), `getServerConfig()` generates a UUID exactly once, persists it, and sets the initialized flag to `true`.
- [ ] On upgrade from a previous version (no `BEARER_TOKEN_INITIALIZED_KEY` set BUT a stored non-empty bearer token exists), `getServerConfig()` preserves the existing token, sets the initialized flag to `true`, and does NOT regenerate.
- [ ] Once `BEARER_TOKEN_INITIALIZED_KEY == true`, an empty `bearerToken` is returned as-is from `getServerConfig()` (no regeneration).
- [ ] `updateBearerToken("")` persists an empty string. A subsequent `getServerConfig()` returns `bearerToken = ""`.
- [ ] `AdbConfigHandler.applyBearerToken` accepts an empty string and persists it (clearing the token) instead of ignoring it.
- [ ] The bearer token field in `GeneralSettingsScreen` has a Clear icon button (in addition to Visibility, Copy, Regenerate). The Clear button uses `enabled = isEnabled && serverConfig.bearerToken.isNotEmpty()` (same `isEnabled` gating as Regenerate — disabled while server is Running or Starting).
- [ ] When `bearerToken.isEmpty()`, a persistent warning banner is rendered immediately ABOVE the bearer token label in `GeneralSettingsScreen` with the wording defined in Task 1.6.
- [ ] When `bearerToken.isEmpty()`, `ConnectionInfoCard` does NOT render the Bearer Token row, and the copy-all `connectionString` does NOT include the `Bearer Token:` line.
- [ ] When `bearerToken.isEmpty()` AND the server is NOT running, tapping Regenerate generates a fresh UUID and persists it. The banner copy MUST instruct the user that the server may need to be stopped first.
- [ ] All existing unit and integration tests pass, including `BearerTokenAuthTest.plugin skips authentication when token is empty` (regression line for the entire feature).

### Task 1.1 — Add `BEARER_TOKEN_INITIALIZED_KEY` Preferences key

**Definition of Done**:
- [x] A new `booleanPreferencesKey("bearer_token_initialized")` exists in [SettingsRepositoryImpl.kt](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt) alongside the other `*_KEY` constants.

**Actions**:

- **Modify** [SettingsRepositoryImpl.kt](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt) — add the new key in the same companion-key block where `BEARER_TOKEN_KEY` is declared:

  ```kotlin
  private val BEARER_TOKEN_INITIALIZED_KEY = booleanPreferencesKey("bearer_token_initialized")
  ```

### Task 1.2 — Rewrite `getServerConfig()` (atomic, upgrade-safe, no auto-regen)

**Definition of Done**:
- [x] The first-time generation runs **inside** a single `dataStore.edit { }` block to avoid the TOCTOU race that exists in the current implementation (current code reads via `data.first()` and then writes — concurrent callers can both pass the check).
- [x] When `BEARER_TOKEN_INITIALIZED_KEY` is not present:
  - if the stored `BEARER_TOKEN_KEY` is non-empty, it is preserved and the flag is set to `true` (upgrade path),
  - if the stored `BEARER_TOKEN_KEY` is missing or empty, a fresh UUID is generated and both keys are persisted (fresh-install path).
- [x] When `BEARER_TOKEN_INITIALIZED_KEY == true`, the stored `bearerToken` (including the empty string) is returned as-is. No regeneration. No writes.

**Actions**:

- **Modify** `SettingsRepositoryImpl.getServerConfig()` (current implementation at [SettingsRepositoryImpl.kt:54-67](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt#L54-L67)) — replace the body with an atomic edit-and-read pattern:

  ```kotlin
  override suspend fun getServerConfig(): ServerConfig {
      dataStore.edit { prefs ->
          if (prefs[BEARER_TOKEN_INITIALIZED_KEY] != true) {
              val existing = prefs[BEARER_TOKEN_KEY].orEmpty()
              if (existing.isEmpty()) {
                  prefs[BEARER_TOKEN_KEY] = generateTokenString()
              }
              prefs[BEARER_TOKEN_INITIALIZED_KEY] = true
          }
      }
      return mapPreferencesToServerConfig(dataStore.data.first())
  }
  ```

  Notes for the implementer:
  - `dataStore.edit { }` is atomic w.r.t. concurrent writers (serialized internally by DataStore).
  - The second `dataStore.data.first()` reads the post-edit state and feeds the existing mapper. No regeneration logic remains outside the edit block.

### Task 1.3 — Update `SettingsRepository` interface KDoc for `getServerConfig` and `updateBearerToken`

**Definition of Done**:
- [x] The `getServerConfig` KDoc no longer claims auto-regeneration; it documents the one-shot install-time generation and the upgrade-preservation path.
- [x] The `updateBearerToken` KDoc explicitly mentions that an empty string clears the token and disables bearer-token authentication on the MCP server.

**Actions**:

- **Modify** [SettingsRepository.kt:30-34](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt#L30-L34):

  ```kotlin
  /**
   * Returns the current server configuration as a one-shot read.
   * On the first call after install (or after upgrade from a version that
   * predates the BEARER_TOKEN_INITIALIZED flag), the bearer token is
   * initialized exactly once: any existing non-empty token is preserved;
   * an empty token is replaced with a freshly generated UUID. On subsequent
   * calls, the stored value (including an empty string, which disables
   * bearer-token authentication) is returned as-is — no regeneration.
   */
  suspend fun getServerConfig(): ServerConfig
  ```

- **Modify** [SettingsRepository.kt:46-51](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt#L46-L51):

  ```kotlin
  /**
   * Updates the bearer token used for MCP request authentication.
   * Passing an empty string clears the token and disables bearer-token
   * authentication on the MCP server (the auth plugin skips auth when
   * the expected token is empty).
   *
   * @param token The new bearer token value (empty string clears).
   */
  suspend fun updateBearerToken(token: String)
  ```

### Task 1.4 — Allow empty `bearer_token` in `AdbConfigHandler`

**Definition of Done**:
- [x] `AdbConfigHandler.applyBearerToken` no longer early-exits when the extra value is empty.
- [x] When `bearer_token` extra is missing from the intent (`getStringExtra(...) == null`), the handler still returns without modifying state (matches existing behavior of other extras).
- [x] When `bearer_token` extra is present and equal to `""`, the handler calls `settingsRepository.updateBearerToken("")` (clearing the token).
- [x] Log message updated to reflect the new semantics (no longer says "Ignoring empty bearer_token"; instead logs either "Bearer token cleared" for empty or "Bearer token updated (length=N)" for non-empty).

**Actions**:

- **Modify** [AdbConfigHandler.kt:66-74](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandler.kt#L66-L74) — replace the function body:

  ```kotlin
  private suspend fun applyBearerToken(intent: Intent) {
      val value = intent.getStringExtra(EXTRA_BEARER_TOKEN) ?: return
      settingsRepository.updateBearerToken(value)
      if (value.isEmpty()) {
          Log.i(TAG, "Bearer token cleared")
      } else {
          Log.i(TAG, "Bearer token updated (length=${value.length})")
      }
  }
  ```

### Task 1.5 — Add `clearBearerToken()` helper on `MainViewModel`

**Definition of Done**:
- [x] `MainViewModel` exposes `fun clearBearerToken()` that launches a coroutine on the file's existing `ioDispatcher` (used by every other repository-mutating method in this file) and calls `settingsRepository.updateBearerToken("")`.
- [x] No new method is added to `SettingsRepository` (per locked decision: reuse `updateBearerToken("")`; the VM helper is not a repository method, only a UI-side convenience wrapper).

**Actions**:

- **Modify** [MainViewModel.kt](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt) — add a method directly after `generateNewBearerToken()` (currently lines 207-211):

  ```kotlin
  fun clearBearerToken() {
      viewModelScope.launch(ioDispatcher) {
          settingsRepository.updateBearerToken("")
      }
  }
  ```

### Task 1.6 — Add string resources for Clear button and warning banner

**Definition of Done**:
- [x] `app/src/main/res/values/strings.xml` contains the new string keys listed below.
- [x] Strings are concise and aligned in tone with existing `config_token_*` resources.
- [x] The warning-banner body explicitly references stopping the server before pressing Regenerate (because Regenerate is gated on `isEnabled` while the server is Running or Starting — see Story 1 acceptance criterion).

**Actions**:

- **Modify** [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml) — add near the other `config_token_*` entries (currently around line 69-73):

  ```xml
  <string name="config_token_clear">Clear token</string>
  <string name="config_bearer_token_empty_warning_title">Authentication disabled</string>
  <string name="config_bearer_token_empty_warning_body">The bearer token is empty, so the MCP server accepts unauthenticated requests from anyone who can reach it. Stop the server (if running), then press Regenerate to enable authentication.</string>
  ```

### Task 1.7 — Add Clear icon button to bearer token field

**Definition of Done**:
- [x] A new Clear `IconButton` is rendered inside the bearer token `OutlinedTextField` trailing-icon `Row`, positioned BETWEEN the Copy button and the Regenerate button. Order in the row: Visibility → Copy → **Clear** → Regenerate.
- [x] `enabled = isEnabled && serverConfig.bearerToken.isNotEmpty()` (consistent with the existing pattern: token-mutating actions are gated on the same `isEnabled` clause as Regenerate).
- [x] `onClick = viewModel::clearBearerToken`.
- [x] Icon: `Icons.Default.Clear`. Content description: `stringResource(R.string.config_token_clear)`.
- [x] Semantic content description on the IconButton: `"Clear bearer token"` (matches existing pattern used for Copy and Regenerate at lines 228 and 241).

**Actions**:

- **Modify** [GeneralSettingsScreen.kt](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/GeneralSettingsScreen.kt) — add the required import at the top of the file (alongside existing `material.icons` imports):

  ```kotlin
  import androidx.compose.material.icons.filled.Clear
  ```

- **Modify** the trailing-icon `Row` around lines 205-251 — insert a new `IconButton` between the Copy and Regenerate buttons:

  ```kotlin
  IconButton(
      onClick = viewModel::clearBearerToken,
      enabled = isEnabled && serverConfig.bearerToken.isNotEmpty(),
      modifier = Modifier.semantics {
          contentDescription = "Clear bearer token"
      },
  ) {
      Icon(
          imageVector = Icons.Default.Clear,
          contentDescription = stringResource(R.string.config_token_clear),
      )
  }
  ```

### Task 1.8 — Render empty-token warning banner in `GeneralSettingsScreen`

**Definition of Done**:
- [x] When `serverConfig.bearerToken.isEmpty()`, a persistent warning card/banner is rendered immediately ABOVE the bearer token label (the `Text(stringResource(R.string.config_bearer_token_label))` at line 189-192).
- [x] When `serverConfig.bearerToken.isNotEmpty()`, the banner is not rendered (no space reserved).
- [x] The banner uses Material 3 tonal styling: `Card` with `colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)`; text uses `MaterialTheme.colorScheme.onErrorContainer`; leading icon `Icons.Default.Warning`.
- [x] The banner is purely informational — no buttons or interaction.

**Actions**:

- **Modify** [GeneralSettingsScreen.kt](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/GeneralSettingsScreen.kt) — add the required imports at the top of the file (alongside existing imports):

  ```kotlin
  import androidx.compose.material.icons.filled.Warning
  import androidx.compose.material3.Card
  import androidx.compose.material3.CardDefaults
  ```

- **Modify** the bearer-token section (currently at lines 186-194) — insert the banner between the existing `Spacer(modifier = Modifier.height(16.dp))` and the label `Text(text = stringResource(R.string.config_bearer_token_label) ...)`:

  ```kotlin
  if (serverConfig.bearerToken.isEmpty()) {
      Card(
          colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.errorContainer,
          ),
          modifier = Modifier.fillMaxWidth(),
      ) {
          Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Icon(
                  imageVector = Icons.Default.Warning,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onErrorContainer,
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column {
                  Text(
                      text = stringResource(R.string.config_bearer_token_empty_warning_title),
                      style = MaterialTheme.typography.titleSmall,
                      color = MaterialTheme.colorScheme.onErrorContainer,
                  )
                  Text(
                      text = stringResource(R.string.config_bearer_token_empty_warning_body),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onErrorContainer,
                  )
              }
          }
      }
      Spacer(modifier = Modifier.height(8.dp))
  }
  ```

### Task 1.9 — Hide Bearer Token row + omit copy-all line when empty

**Definition of Done**:
- [x] In `ConnectionInfoCard`, the `Row` displaying the Bearer Token label, value, and visibility toggle is NOT rendered when `bearerToken.isEmpty()`.
- [x] The `connectionString` produced by `buildString` does NOT include the `"\nBearer Token: $bearerToken"` line when `bearerToken.isEmpty()`.
- [x] No new string resource is required.
- [x] No refactor of the existing inline `buildString` is performed (composition stays inline inside the composable; the empty-token behavior is verified manually in Story 4).

**Actions**:

- **Modify** [ConnectionInfoCard.kt](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt) — wrap the bearer-token `Row` currently at lines 93-122 in a conditional:

  ```kotlin
  if (bearerToken.isNotEmpty()) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
      ) {
          // ... existing Row contents unchanged ...
      }
  }
  ```

- **Modify** [ConnectionInfoCard.kt:126-131](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt#L126-L131) — change the `buildString` block:

  ```kotlin
  val connectionString =
      buildString {
          append("URL: $serverUrl")
          tunnelUrl?.let { append("\nTunnel: $it/mcp") }
          if (bearerToken.isNotEmpty()) {
              append("\nBearer Token: $bearerToken")
          }
      }
  ```

### Task 1.10 — Unit tests for repository auto-gen / upgrade behavior

**File**: [app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt](app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt)

**Setup**: Existing test infra in the file (in-memory `PreferenceDataStoreFactory` or test-DataStore wrapper, JUnit 5 + MockK as used elsewhere in the file).

| Test | Verifies |
|------|----------|
| `getServerConfig generates token on first call when none stored` | Fresh DataStore → first `getServerConfig()` returns a non-empty UUID-format token AND the `BEARER_TOKEN_INITIALIZED_KEY` is now `true`. **Replaces the existing `auto-generates bearer token when empty` test (around line 95-101).** |
| `getServerConfig preserves existing token on upgrade path` | DataStore pre-seeded with `BEARER_TOKEN_KEY = "legacy-token"` and NO `BEARER_TOKEN_INITIALIZED_KEY`. Call `getServerConfig()`. Assert returned token equals `"legacy-token"` AND `BEARER_TOKEN_INITIALIZED_KEY` is now `true`. |
| `getServerConfig returns empty token after explicit clear` | Call `getServerConfig()` once (token generated, flag set), then `updateBearerToken("")`, then `getServerConfig()` again — second call returns `bearerToken == ""` and the flag remains `true`. No regeneration. |
| `getServerConfig is idempotent after first generation` | Two consecutive `getServerConfig()` calls on the same fresh DataStore return the SAME token. (Existing `auto-generated bearer token is persisted` around line 115-122 — update only if assertion semantics drift.) |
| `serverConfig Flow does not generate bearer token` | Flow path remains unchanged: emits whatever is in DataStore without side effects. (Existing `Flow does not auto-generate bearer token when empty` around line 391 — keep as-is.) |

**Definition of Done**:
- [x] The existing `auto-generates bearer token when empty` test is renamed and updated to assert the one-shot semantics described above (`BEARER_TOKEN_INITIALIZED_KEY` flag set).
- [x] A new test `getServerConfig preserves existing token on upgrade path` is added.
- [x] A new test `getServerConfig returns empty token after explicit clear` is added.
- [x] The existing `Flow does not auto-generate bearer token when empty` continues to pass unchanged.
- [x] Existing tests with names that reference "auto-generated" (e.g. `auto-generated bearer token is UUID format` ~line 103-113, `auto-generated bearer token is persisted` ~line 115-122, `Flow reflects token after getServerConfig auto-generates it` ~line 403-417) are LEFT UNCHANGED. Rationale: keep diff minimal; the names are honest as long as no token is pre-stored. If the implementer prefers, names may be updated to "generated on first call" wording — but this is optional and not required for plan completion.
- [x] No other tests in this file are altered unless they assert behavior contradicted by this change.

### Task 1.11 — Unit test for `MainViewModel.clearBearerToken`

**File**: [app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt](app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt)

**Setup**: Existing test infra with MockK on `SettingsRepository` and `TestDispatcher` for `ioDispatcher`.

| Test | Verifies |
|------|----------|
| `clearBearerToken delegates to repository with empty string` | Calling `viewModel.clearBearerToken()` then `advanceUntilIdle()` results in `coVerify(exactly = 1) { settingsRepository.updateBearerToken("") }`. **Setup**: ensure `ioDispatcher` is wired to the test `TestDispatcher` so the launched coroutine executes deterministically (this is already how `generateNewBearerToken` is tested in the file — mirror that pattern). |

**Definition of Done**:
- [x] Test added and follows the file's existing arrange-act-assert + `runTest` + `advanceUntilIdle()` pattern.

### Task 1.12 — Unit test for `AdbConfigHandler` empty bearer token

**File**: [app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandlerTest.kt](app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandlerTest.kt)

**Setup**: Existing test infra in the file (MockK on `SettingsRepository`, real Intent constructed with extras).

| Test | Verifies |
|------|----------|
| `empty bearer_token clears the stored token` | An intent with `--es bearer_token ""` triggers `coVerify { settingsRepository.updateBearerToken("") }`. **Replaces the existing `empty bearer_token is ignored` test at line 156-164.** |
| `missing bearer_token extra leaves token untouched` | An intent without the `bearer_token` extra triggers `coVerify(exactly = 0) { settingsRepository.updateBearerToken(any()) }`. |
| `non-empty bearer_token is applied as before` | Existing `bearer_token is applied when provided` test around line 144-152 — keep unchanged. |

**Definition of Done**:
- [x] The existing `empty bearer_token is ignored` test is replaced with `empty bearer_token clears the stored token` reflecting the new semantics.
- [x] A new test `missing bearer_token extra leaves token untouched` is added to cover the `?: return` branch.
- [x] Other related tests at lines 516, 532, and 740 are re-examined; if any of them rely on the "ignore empty" behavior, they MUST be updated to match the new semantics. (Re-examined: line 516 asserts non-empty token update, line 532 `noExtras` and line 740 `unknownActionIgnored` assert `exactly = 0` updates when the extra is absent — none rely on the old "ignore empty when present" behavior. No changes required.)

---

## User Story 2 — Optional event channel auth token

**Why**: Allow the event channel to dispatch events to endpoints that do not require auth. Today, three call sites refuse to operate on a blank `authToken`, and the dispatcher always attaches `Authorization: Bearer ` (broken header) without a guard.

### Acceptance criteria
- [ ] `EventChannelService.handleStart()` starts the dispatcher when `endpointUrl` is non-blank, regardless of `authToken`.
- [ ] `ServerScreen` channel Start button triggers the channel when `endpointUrl` is non-blank, regardless of `authToken`. The "not configured" dialog text refers only to the endpoint URL.
- [ ] `BootCompletedReceiver` auto-starts the channel on boot when `enabled && endpointUrl.isNotBlank()`, regardless of `authToken`.
- [ ] `EventDispatcherImpl.dispatch()` attaches `Authorization: Bearer <token>` only when `authToken.isNotEmpty()`. When empty, no `Authorization` header is sent.
- [ ] Existing event channel UI fields (`ChannelSettingsScreen.kt`) require no change — the field is already editable and persistence already accepts empty.

### Task 2.1 — Update dialog string resources

**Definition of Done**:
- [x] New string resources exist in `strings.xml` and will be referenced from `ServerScreen.kt` in Task 2.3.
- [x] The existing dialog literals in `ServerScreen.kt` (lines 155-165) were NOT previously string resources, so no orphan keys are introduced.

**Actions**:

- **Modify** [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml) — add the three new resources at the end of the file (the file does not currently have an "Event Channel" group of string resources — `notification_channel_mcp_server_*` belongs to the Android Notification Channel, not the event channel). Group the new keys together with a comment header:

  ```xml
  <!-- Event Channel — start/stop dialogs -->
  <string name="channel_not_configured_dialog_title">Endpoint URL not set</string>
  <string name="channel_not_configured_dialog_body">Configure the event channel endpoint URL in Settings → Event Channel before starting the service. The auth token is optional and can be left empty if your endpoint does not require it.</string>
  <string name="channel_not_configured_dialog_ok">OK</string>
  ```

### Task 2.2 — Remove `authToken` gate in `EventChannelService.handleStart()`

**Definition of Done**:
- [x] The `if` condition that previously gated service start on `endpointUrl.isBlank() || authToken.isBlank()` now only checks `endpointUrl.isBlank()`.
- [x] Log message is updated to reflect the new gate ("endpoint URL is empty" only).

**Actions**:

- **Modify** [EventChannelService.kt:68-72](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelService.kt#L68-L72):

  ```kotlin
  if (config.endpointUrl.isBlank()) {
      Logger.e(TAG, "Cannot start: endpoint URL is empty")
      stopSelf()
      return@launch
  }
  ```

### Task 2.3 — Remove `authToken` gate in `ServerScreen` start handler + update dialog

**Definition of Done**:
- [x] `onChannelStartClick` only opens the dialog when `endpointUrl.isBlank()`.
- [x] Dialog `title` and `text` use the string resources from Task 2.1.
- [x] Dialog `confirmButton` text uses `R.string.channel_not_configured_dialog_ok`.

**Actions**:

- **Modify** [ServerScreen.kt:111-117](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt#L111-L117):

  ```kotlin
  onChannelStartClick = {
      if (channelConfig.endpointUrl.isBlank()) {
          showChannelNotConfiguredDialog = true
      } else {
          channelViewModel.startChannel()
      }
  },
  ```

- **Modify** [ServerScreen.kt:152-168](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt#L152-L168) — replace hard-coded dialog strings with the new resources from Task 2.1:

  ```kotlin
  if (showChannelNotConfiguredDialog) {
      AlertDialog(
          onDismissRequest = { showChannelNotConfiguredDialog = false },
          title = { Text(stringResource(R.string.channel_not_configured_dialog_title)) },
          text = { Text(stringResource(R.string.channel_not_configured_dialog_body)) },
          confirmButton = {
              TextButton(onClick = { showChannelNotConfiguredDialog = false }) {
                  Text(stringResource(R.string.channel_not_configured_dialog_ok))
              }
          },
      )
  }
  ```

### Task 2.4 — Remove `authToken` gate in `BootCompletedReceiver`

**Definition of Done**:
- [x] The `if` condition that auto-starts the channel on boot only checks `enabled` and `endpointUrl.isNotBlank()` (the `authToken.isNotBlank()` clause is removed).
- [x] The change is verified manually in Story 4 (Task 4.4) — broadcast receivers in this project are not currently unit-tested (no precedent for Robolectric or instrumented receiver tests), so introducing a test infrastructure for them is out of scope for this plan.

**Actions**:

- **Modify** [BootCompletedReceiver.kt:58-69](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/BootCompletedReceiver.kt#L58-L69):

  ```kotlin
  val channelConfig = settingsRepository.getEventChannelConfig()
  if (channelConfig.enabled && channelConfig.endpointUrl.isNotBlank()) {
      val channelIntent =
          Intent(context, EventChannelService::class.java).apply {
              action = EventChannelService.ACTION_START
          }
      context.startForegroundService(channelIntent)
      Log.i(TAG, "Event channel auto-started on boot")
  }
  ```

### Task 2.5 — Conditional `Authorization` header in `EventDispatcherImpl.dispatch()`

**Definition of Done**:
- [x] The `Authorization` header is only set when `authToken.isNotEmpty()`.
- [x] `EventDispatcherImpl.healthCheck()` is unchanged (already attaches no auth header — verified at [EventDispatcherImpl.kt:107-131](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventDispatcherImpl.kt#L107-L131)).

**Actions**:

- **Modify** [EventDispatcherImpl.kt:82-87](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventDispatcherImpl.kt#L82-L87):

  ```kotlin
  val response: HttpResponse =
      httpClient.post("$endpointUrl/event") {
          contentType(ContentType.Application.Json)
          if (authToken.isNotEmpty()) {
              header("Authorization", "Bearer $authToken")
          }
          setBody(event)
      }
  ```

### Task 2.6 — Unit tests for `EventChannelConfig` validation contract

**File**: [app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelServiceTest.kt](app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelServiceTest.kt)

**Scope note (honesty disclaimer)**: This file's existing header explicitly states the JVM-only constraint: `EventChannelService` cannot be instantiated without the Android framework, so these tests verify only the `EventChannelConfig` shape and the validation contract. The actual `handleStart()` gate change is verified manually in Story 4 (Task 4.4) by reading the diff and running the app.

**Setup**: Existing pattern in the file — construct `EventChannelConfig` instances and assert their blank/non-blank shapes.

| Test | Verifies | Action |
|------|----------|--------|
| `empty auth token is blank` (around line 30-39) | `EventChannelConfig(authToken = "").authToken.isBlank() == true`. Still factually true under the new contract. | **KEEP UNCHANGED.** The assertion is honest and does not conflate auth-token-blank with start-blocking. |
| `valid config has non-blank endpoint and token` (around line 41-51) | Old wording asserted that "valid for start" requires both `endpointUrl` AND `authToken` to be non-blank. This conflates the new contract. | **REPLACE** with a new test `config with blank endpoint is an unstartable shape regardless of token` that asserts: for `(endpointUrl = "", authToken = "")` and `(endpointUrl = "", authToken = "x")`, `endpointUrl.isBlank() == true`. |
| `config with non-blank endpoint and empty authToken is a startable shape` | NEW test. An `EventChannelConfig(endpointUrl = "https://example", authToken = "", enabled = true)` satisfies the new shape: `endpointUrl.isNotBlank() == true`, `authToken.isBlank() == true`. The shape itself does not block start (the live `handleStart()` gate is covered manually in Story 4). | **ADD** as a new test. |

**Definition of Done**:
- [x] The existing `empty auth token is blank` test (around line 30-39) is kept unchanged.
- [x] The existing `valid config has non-blank endpoint and token` test (around line 41-51) is replaced with `config with blank endpoint is an unstartable shape regardless of token`.
- [x] A new test `config with non-blank endpoint and empty authToken is a startable shape` is added.
- [x] Test names reflect that they verify config shape, not the live `handleStart()` gate (which is covered by manual review in Story 4).

### Task 2.7 — Unit test for `EventDispatcherImpl` dispatch header

**File**: [app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventDispatcherImplTest.kt](app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventDispatcherImplTest.kt)

**Setup**: Existing embedded-Netty pattern at lines 84-129. The test spins up `embeddedServer(Netty, port = 0)` and captures `call.request.header("Authorization")` into an `AtomicReference<String?>`. Add a new test that mirrors the existing setup but starts the dispatcher with an empty auth token and asserts the captured header is `null`. (`MockEngine` is NOT used in this file — do not substitute it.)

| Test | Verifies |
|------|----------|
| `dispatch with empty auth token sends no Authorization header` | After `dispatcher.start(endpointUrl, "")`, calling `dispatcher.dispatch(event)` produces a request where the captured `Authorization` header value is `null`. |
| `dispatch with non-empty auth token sends Bearer header` | Existing test `dispatch sends POST with correct headers and body` (around line 84-129) — keep unchanged. |

**Definition of Done**:
- [x] New test added using the embedded-Netty + `AtomicReference` pattern from the existing test.
- [x] The existing positive-path test is not altered.

### Task 2.8 — `ChannelViewModel` source and test review (no expected changes)

**File**: [app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/ChannelViewModel.kt](app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/ChannelViewModel.kt) and [app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/ChannelViewModelTest.kt](app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/ChannelViewModelTest.kt)

**Definition of Done**:
- [x] Open `ChannelViewModel.kt` and confirm that `startChannel()` (around lines 74-79) contains NO early-exit / gate on `authToken.isBlank()` or `authToken.isEmpty()`. The current implementation simply sets `updateEventChannelEnabled(true)` and calls `startChannelService()` — already authToken-agnostic. **This verification has already been performed during plan authoring; this DoD bullet is a defensive re-check during implementation.** (Confirmed: lines 74-79 only call `settingsRepository.updateEventChannelEnabled(true)` inside `viewModelScope.launch(ioDispatcher)` and then `startChannelService()` — no authToken gate.)
- [x] If — contrary to the previous bullet — an `authToken` gate is discovered, escalate to the user before modifying `ChannelViewModel.kt`. `ChannelViewModel.kt` is NOT in the Files in scope list; modifying it requires user approval. (N/A — no gate found; no escalation needed.)
- [x] No source changes for `ChannelViewModel` are otherwise required by this plan (the VM already accepts empty `authToken` via `updateAuthToken`).
- [x] No test changes required. The existing tests are re-run as part of Task 4.2 to confirm they still pass after Story 2 changes.

---

## User Story 3 — Documentation

**Why**: Surface the new capability and security implications to users, and keep the project documentation aligned with the new one-shot generation semantics. Every existing categorical claim that authentication is mandatory or that the token is always auto-generated MUST be qualified to reflect the new optional-token behavior.

### Acceptance criteria
- [ ] `README.md` documents that the bearer token can be left empty for clients like Claude Desktop, with a security warning.
- [ ] `README.md` Headless ADB section documents that `--es bearer_token ""` clears the stored token (now truthful because Task 1.4 enables this).
- [ ] `README.md` no longer contains categorical "Every MCP request requires …" wording or table rows that imply the bearer token is always required (lines 194, 206, 364 — see Task 3.2).
- [ ] `docs/PROJECT.md` reflects the new optional-token semantics, including the categorical claim at line 175.
- [ ] `docs/ARCHITECTURE.md` reflects the new optional-token semantics at line 202, 204, AND 211.

### Task 3.1 — Update README Connect section

**Definition of Done**:
- [x] The Connect section in `README.md` includes a callout paragraph noting that the bearer token field can be cleared in-app to disable authentication for clients that cannot send custom `Authorization` headers (e.g. Claude Desktop).
- [x] The callout includes a security warning that an empty token makes the server open to anyone who can reach it.

**Actions**:

- **Modify** [README.md](README.md) — locate the `### Claude Desktop / Claude Code` subsection (search for `Claude Desktop / Claude Code`). Immediately AFTER the `.mcp.json` JSON example in that subsection, insert a blockquote:

  > **Note for clients without custom-header support (e.g. Claude Desktop):** if your MCP client cannot send an `Authorization` header, you can clear the bearer token in the app (Settings → General → Bearer Token → Clear). When the token is empty, the server skips authentication entirely. Only do this on a network you trust — anyone who can reach the server will be able to use it.

### Task 3.2 — Update categorical-claim lines in README

**Definition of Done**:
- [x] README line 194 ("The bearer token is displayed in the app's connection info section…") is qualified to say the token is displayed only when configured (non-empty).
- [x] README line 206 (defaults table row "Bearer Token | Auto-generated UUID | …") is updated to reflect the one-shot generation and clearable semantics.
- [x] README line 364 ("Every MCP request requires an `Authorization: Bearer <token>` header. The token is auto-generated on first launch (UUID) …") is rewritten to reflect that authentication is only enforced when the token is non-empty and that the user can clear it.

**Actions**:

- **Modify** [README.md](README.md) around line 194 — replace "The bearer token is displayed in the app's connection info section. You can copy it directly from the app." with: "When configured (non-empty), the bearer token is displayed in the app's connection info section and can be copied directly from the app. When the token is cleared, the row is hidden and the server accepts unauthenticated requests."

- **Modify** [README.md](README.md) around line 206 — replace the defaults-table row `| Bearer Token | Auto-generated UUID | Authentication token for MCP requests |` with: `| Bearer Token | Auto-generated UUID (one-shot, clearable) | Authentication token for MCP requests. Cleared = authentication disabled. |`.

- **Modify** [README.md](README.md) around line 364 — replace "Every MCP request requires an `Authorization: Bearer <token>` header. The token is auto-generated on first launch (UUID) and can be viewed, copied, and regenerated in the app." with: "When the bearer token is configured (non-empty), every MCP request must carry an `Authorization: Bearer <token>` header. The token is auto-generated once on first launch (UUID, preserved across app upgrades) and can be viewed, copied, regenerated, or cleared in the app. When the token is empty, the MCP server skips authentication entirely — see the security note in the Connect section."

### Task 3.3 — Update README Headless ADB section

**Definition of Done**:
- [x] The Headless ADB section in `README.md` documents that `--es bearer_token ""` clears the stored token (disabling auth).
- [x] The reference table for extras (around `README.md:318`) is updated if it mentions or implies that the value cannot be empty.

**Actions**:

- **Modify** [README.md](README.md) — in the Headless Setup via ADB block (search for `--es bearer_token`), add a line below the existing `--es bearer_token "my-secret-token"` example documenting the empty-string behavior:

  ```
  # Clear the bearer token (disables authentication; see Security section)
  adb shell am broadcast \
    -a com.danielealbano.androidremotecontrolmcp.action.CONFIGURE \
    --es bearer_token ""
  ```

  Add a short note immediately after: "Passing `--es bearer_token \"\"` clears the stored token. The MCP server skips authentication when the token is empty — use only on trusted networks."

- **Modify** [README.md](README.md) around line 318 (the extras reference-table row for `bearer_token`) — append " (empty string clears the token and disables auth)" to the Description column.

### Task 3.4 — Update `docs/PROJECT.md`

**Definition of Done**:
- [x] Line 175 categorical claim "all routes require authentication (no unauthenticated endpoints)" is qualified to reflect the new optional-token semantics and the existing `/health` exception.
- [x] Around line 581 (and any other mentions of "Auto-generated UUID on first launch" for the bearer token) are updated to specify one-shot semantics and the user's ability to clear.
- [x] Line 582 categorical claim "Every MCP request must include `Authorization: Bearer <token>` header" is qualified to reflect the new optional-token semantics.
- [x] Around line 653 (the security-defaults section), the same wording is updated.

**Actions**:

- **Modify** [docs/PROJECT.md](docs/PROJECT.md) around line 175 — replace "Global Application-level bearer token (`Authorization: Bearer <token>`) — all routes require authentication (no unauthenticated endpoints)" with: "Global Application-level bearer token (`Authorization: Bearer <token>`) is enforced on all routes EXCEPT `/health` when the token is configured (non-empty). When the bearer token is empty, the `BearerTokenAuthPlugin` skips authentication entirely (intended for clients that cannot send custom headers, e.g. Claude Desktop). The `/health` endpoint remains unauthenticated in all states."

- **Modify** [docs/PROJECT.md](docs/PROJECT.md) around line 581 — replace "Auto-generated UUID on first launch; user can view/copy/regenerate via UI" with: "Auto-generated UUID exactly once on first launch (preserved across app upgrades); user can view, copy, regenerate, or clear via the UI. Clearing the token disables bearer-token authentication on the MCP server."

- **Modify** [docs/PROJECT.md](docs/PROJECT.md) around line 582 — replace "Every MCP request must include `Authorization: Bearer <token>` header" with: "When the bearer token is configured (non-empty), every MCP request must include an `Authorization: Bearer <token>` header. When the bearer token is empty, the server skips authentication entirely."

- **Modify** [docs/PROJECT.md](docs/PROJECT.md) around line 653 — replace "Auto-generated UUID on first launch" with: "Auto-generated UUID once on first launch (persisted, upgrade-safe); can be cleared in-app or via `--es bearer_token \"\"` to disable authentication."

### Task 3.5 — Update `docs/ARCHITECTURE.md`

**Definition of Done**:
- [x] Line 202 categorical claim "Every request requires `Authorization: Bearer <token>` header" is qualified to reflect optional-token semantics.
- [x] Line 204 is updated to reflect the one-shot, upgrade-safe semantics.
- [x] Line 211 ("No external firewall; relies on Android's app sandbox and bearer token") is qualified so readers do not get a false sense of security when the token is empty.

**Actions**:

- **Modify** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) around line 202 — replace "Every request requires `Authorization: Bearer <token>` header (global Application-level plugin)" with: "When the token is configured (non-empty), every request requires an `Authorization: Bearer <token>` header (global Application-level plugin). When the token is empty, the plugin skips authentication entirely."

- **Modify** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) around line 204 — replace "Token auto-generated (UUID) on first launch, user can regenerate" with: "Token auto-generated (UUID) once on first launch (existing tokens preserved on upgrade), persisted; user can regenerate or clear. When the token is empty, the `BearerTokenAuthPlugin` skips authentication entirely."

- **Modify** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) around line 211 — replace "No external firewall; relies on Android's app sandbox and bearer token" with: "No external firewall; relies on Android's app sandbox, the network-binding choice (loopback by default), and the bearer token. When the bearer token is empty, the network binding is the only remaining layer — stay on loopback unless you trust the network."

---

## User Story 4 — Final ground-up verification

**Why**: User explicitly required the last item of this plan to double-check every change end-to-end. This step is REQUIRED to declare the plan complete and is performed AFTER all stories 1–3 are implemented.

### Acceptance criteria
- [ ] `make lint` (ktlintCheck + detekt) passes with zero warnings and zero errors. Any new violation introduced by stories 1–3 is fixed at the root cause (no `@Suppress`).
- [ ] `make test-unit` and `make test-integration` both pass; every test added/modified by this plan is green.
- [ ] `./gradlew build` succeeds with zero warnings.
- [ ] Every file listed in "Files in scope" was modified; no file outside that list was modified.
- [ ] Acceptance criteria of stories 1, 2, and 3 are checked off.
- [ ] `code-reviewer` subagent in plan compliance mode reports zero CRITICAL and zero WARNING findings.

### Task 4.1 — Run linting and fix violations

**Definition of Done**:
- [x] `make lint` exits 0.
- [x] Any new violation introduced by this plan is fixed in the responsible file from stories 1–3.
- [x] Unrelated pre-existing violations discovered during this run are also fixed (per project rule "Fix broken linting"). (Resolution: Android Lint's `lintDebug` task — not run by CI's `lint` job, but run by `./gradlew build` — flagged a pre-existing `MissingPermission` on `WifiEventListener.kt:140` (`wifiManager.scanResults`). Fixed at the root cause by wrapping the access in `try/catch SecurityException` and logging — Lint's accepted resolution. File added to scope with explicit user authorization.)

### Task 4.2 — Run the full automated test suite

**Definition of Done**:
- [x] `make test-unit` exits 0.
- [x] `make test-integration` exits 0.
- [x] Every test added or modified by Tasks 1.10, 1.11, 1.12, 2.6, 2.7 is green.
- [x] Existing `BearerTokenAuthTest.plugin skips authentication when token is empty` is green (regression line for the entire feature).
- [x] Any broken pre-existing test is fixed in place (per project rule "Fix broken tests"). (No pre-existing tests broke during this plan.)

### Task 4.3 — Run the full build

**Definition of Done**:
- [x] `./gradlew build` exits 0 with zero warnings. (`BUILD SUCCESSFUL in 4m 7s — 234 actionable tasks: 100 executed, 134 up-to-date`. The WifiEventListener `lintDebug` regression discovered during this task was fixed before this checkbox was marked.)

### Task 4.4 — Ground-up manual review against this plan

**Definition of Done**:
- [x] Walk through stories 1, 2, and 3 sequentially. For each Action, open the affected file and verify the diff matches the plan exactly. (Walked through all source/test/doc Actions; every change matches the plan verbatim. See verification snippets captured during Task 4.4 execution.)
- [x] For each user story's acceptance criteria, verify the criterion holds against the running app/code (not the plan). (Automated coverage: `SettingsRepositoryImplTest.getServerConfig generates token on first call when none stored`, `getServerConfig preserves existing token on upgrade path`, `getServerConfig returns empty token after explicit clear`, `MainViewModelTest.clearBearerToken delegates to repository with empty string`, `AdbConfigHandlerTest.empty bearer_token clears the stored token`, `BearerTokenAuthTest.plugin skips authentication when token is empty`, `EventChannelServiceTest.config with non-blank endpoint and empty authToken is a startable shape`, `EventDispatcherImplTest.dispatch with empty auth token sends no Authorization header` — all green.)
- [ ] Manually verify (in the app or via adb logcat) that:
  - Fresh-install flow generates a token and sets the initialized flag.
  - Upgrade simulation (manually preload DataStore with a non-empty token and no init flag) preserves the existing token.
  - In-app Clear button persists empty token and shows the warning banner.
  - `ConnectionInfoCard` hides the Bearer Token row when empty AND the "Copy all" connection string does not include the `Bearer Token:` line.
  - With the bearer token empty, an MCP client without an `Authorization` header can call `POST /mcp` and receive a normal response (the `BearerTokenAuthPlugin` skips authentication — already covered by `BearerTokenAuthTest`, but verify end-to-end against the running app).
  - On the Server tab with the event channel endpoint URL empty, tapping the channel Start button shows the new dialog (verify the title `R.string.channel_not_configured_dialog_title`, body `R.string.channel_not_configured_dialog_body`, and OK button text `R.string.channel_not_configured_dialog_ok` from Task 2.1).
  - `EventChannelService.handleStart()` starts with `endpointUrl` non-blank and `authToken` empty (logcat shows successful start, no `stopSelf()` due to auth-token blank).
  - With `EventChannelService` running and `authToken` empty, dispatch a synthetic event (e.g., generate a notification that triggers the listener) and verify on the receiving end (a test HTTP endpoint or packet capture) that NO `Authorization` header is present in the outgoing POST.
  - `BootCompletedReceiver` auto-starts the channel after a reboot with empty `authToken` (manually rebooted device/emulator, with `channelConfig.enabled = true` and `endpointUrl` non-blank).
  - `--es bearer_token ""` clears the token (verify via logcat — should log "Bearer token cleared" — and via the in-app display showing the warning banner).

  **Status**: Pending in-device manual verification by the user. The behaviour is covered indirectly by the automated tests above; live device/emulator verification belongs to the user as it requires a physical device or emulator session.
- [x] Confirm via `git status` and `git diff --stat origin/main...HEAD` that NO file outside the "Files in scope" list has been modified. (Confirmed. Only one file outside the original scope was modified — `WifiEventListener.kt` — with explicit user authorization to fix a pre-existing `MissingPermission` Android Lint error that surfaced during Task 4.3. Documented in Task 4.1 DoD and committed as `fix(wifi): handle SecurityException on scanResults access`.)
- [x] Confirm `BearerTokenAuthTest.plugin skips authentication when token is empty` still passes after the changes. (Green in `make test-unit` and `./gradlew build`.)

### Task 4.5 — Spawn `code-reviewer` in plan compliance mode

**Definition of Done**:
- [x] A single `code-reviewer` subagent is spawned to audit the implementation against this plan.
- [x] All reported findings (CRITICAL, WARNING, INFO) are addressed per the project rule "Handling review findings". (Reviewer reported zero findings — implementation matches the plan verbatim, including the WifiEventListener authorized out-of-scope fix.)
- [x] If any finding cannot be resolved or a deviation from the plan is preferred, the user is consulted; the user makes the final call. (N/A — no findings.)
- [x] The `code-reviewer` is re-spawned after fixes until it returns clean (zero CRITICAL and zero WARNING). (First pass clean. Verdict: **PASS**.)

---

## Files in scope (declaratively)

Only these paths may be modified by this plan. Any change outside this list requires user approval before proceeding.

**Source**:
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandler.kt`
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/GeneralSettingsScreen.kt`
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt`
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt`
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelService.kt`
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventDispatcherImpl.kt`
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/BootCompletedReceiver.kt`
- `app/src/main/res/values/strings.xml`

**Tests**:
- `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt`
- `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt`
- `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandlerTest.kt`
- `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelServiceTest.kt`
- `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventDispatcherImplTest.kt`

**Docs**:
- `README.md`
- `docs/PROJECT.md`
- `docs/ARCHITECTURE.md`

---

## Plan Review Findings (round 1 — addressed)

The first plan-reviewer pass (against the original draft of this plan) reported 2 CRITICAL, 8 WARNING, and 7 INFO findings. All findings have been incorporated into the revised plan above:

| ID | Severity | Resolution |
|----|----------|------------|
| P46-001 | CRITICAL | New Task 1.4 modifies `AdbConfigHandler.applyBearerToken` to accept empty string; `AdbConfigHandler.kt` and `AdbConfigHandlerTest.kt` added to Files in scope; new Task 1.12 covers the test; Task 3.2 now references actual supported behavior. |
| P46-002 | CRITICAL | Task 2.7 setup reworded: embedded Netty + `AtomicReference`, NOT `MockEngine`. |
| P46-003 | WARNING | Original Task 1.11 (unplanned `buildConnectionString` refactor) removed entirely; copy-all string behavior is now inline in Task 1.9 and verified manually in Story 4. `ConnectionInfoCardTest.kt` removed from Files in scope. |
| P46-004 | WARNING | Task 1.2 rewritten to use a single atomic `dataStore.edit { }` block, eliminating the TOCTOU race. |
| P46-005 | WARNING | Task 1.5's `clearBearerToken` now uses `viewModelScope.launch(ioDispatcher)`, matching the rest of `MainViewModel`. |
| P46-006 | WARNING | Tasks 1.7 and 1.8 now include explicit import-add actions for `Icons.Default.Clear`, `Icons.Default.Warning`, `Card`, and `CardDefaults`. Icon choice locked to `Icons.Default.Clear`. |
| P46-007 | WARNING | Task 2.6 reworded with an explicit honesty disclaimer: it verifies `EventChannelConfig` shape only; the live `handleStart()` gate is covered by manual verification in Task 4.4. Test names renamed accordingly. |
| P46-008 | WARNING | Task 2.4 documents that broadcast-receiver behavior is verified manually in Task 4.4 (no precedent for receiver tests in this codebase). |
| P46-009 | WARNING | Story 1 AC and the warning-banner body string now explicitly state that the user may need to stop the server before pressing Regenerate (because the existing `isEnabled` gate disables both Clear and Regenerate while the server is Running or Starting). |
| P46-010 | WARNING | Clear button gating locked to `isEnabled && serverConfig.bearerToken.isNotEmpty()`, mirroring Regenerate's existing pattern — documented explicitly in the Context section and Story 1 AC. |
| P46-011 | INFO | No change (line drift is acceptable per project rule). |
| P46-012 | INFO | Task 1.8 DoD wording updated: banner is "immediately ABOVE the bearer token label". |
| P46-013 | INFO | Task 1.3 now also updates the `updateBearerToken` KDoc. |
| P46-014 | INFO | Task 1.2 preserves existing non-empty tokens on the upgrade path (covered by a new test in Task 1.10). |
| P46-015 | INFO | Resolved as part of P46-001 (the documented ADB behavior is now truthful because Task 1.4 enables it). |
| P46-016 | INFO | No change (already compliant). |
| P46-017 | INFO | No change (already compliant). |

## Plan Review Findings (round 2 — addressed)

The second plan-reviewer pass (against the round-1-resolved revision) confirmed ALL 17 round-1 findings RESOLVED and reported 4 NEW WARNING and 6 NEW INFO findings, all documentation-completeness in nature. All have been incorporated into the plan above:

| ID | Severity | Resolution |
|----|----------|------------|
| P46-R2-001 | WARNING | Task 3.4 (formerly 3.3) now explicitly updates PROJECT.md line 175 categorical auth claim. |
| P46-R2-002 | WARNING | Task 3.5 (formerly 3.4) now explicitly updates ARCHITECTURE.md line 202 categorical auth claim. |
| P46-R2-003 | WARNING | Task 3.2 (new) updates README.md line 364 categorical auth claim with optional-token wording. |
| P46-R2-004 | WARNING | Task 3.2 updates README.md line 206 defaults-table row to reflect one-shot + clearable. |
| P46-R2-005 | INFO | Task 3.2 qualifies README.md line 194 ("connection info section") with the empty-token hidden-row behavior. |
| P46-R2-006 | INFO | Task 3.5 updates ARCHITECTURE.md line 211 to emphasize loopback-only protection when token is empty. |
| P46-R2-007 | INFO | Task 1.10 DoD explicitly documents that existing test names containing "auto-generated" are deliberately left unchanged for diff minimality. |
| P46-R2-008 | INFO | Task 4.4 manual-verification checklist now includes a dedicated bullet for the new dialog text resources from Task 2.1. |
| P46-R2-009 | INFO | Task 4.4 manual-verification checklist now includes a dedicated bullet for verifying the absence of the `Authorization` header on outgoing dispatch when `authToken` is empty. |
| P46-R2-010 | INFO | Task 2.1 placement instructions corrected: new strings are added at the end of `strings.xml` with an "Event Channel" comment header (there is no existing `channel_*` block — `notification_channel_mcp_server_*` belongs to the Android Notification Channel). |

## Plan Review Findings (round 3 — addressed)

The third plan-reviewer pass confirmed ALL 27 prior findings (round-1 + round-2) RESOLVED and reported 2 NEW WARNING and 13 NEW INFO findings. All have been addressed in the plan above (most INFO findings were self-corrected false positives or "no defect" notes recorded by the reviewer for completeness).

| ID | Severity | Resolution |
|----|----------|------------|
| P46-R3-001 | INFO | Task 3.4 now adds a fourth action explicitly updating PROJECT.md line 582 ("Every MCP request must include …" → optional-token wording). |
| P46-R3-002 | INFO | Task 3.4 first action expanded to explicitly state "The `/health` endpoint remains unauthenticated in all states." for full clarity. |
| P46-R3-003 | INFO | False positive on reviewer's side (MainViewModelTest IS in Files in scope). No change. |
| P46-R3-004 | INFO | No defect (companion constants accessible in `dataStore.edit { }` lambda). No change. |
| P46-R3-005 | INFO | Task 2.6 wording disambiguated: existing `empty auth token is blank` test is KEPT UNCHANGED (factually true), existing `valid config has non-blank endpoint and token` test is REPLACED with `config with blank endpoint is an unstartable shape regardless of token`, NEW test `config with non-blank endpoint and empty authToken is a startable shape` is ADDED. Three explicit DoD bullets clarify the action per test. |
| P46-R3-006 | INFO | Reviewer self-downgraded to INFO ("no action required"). No change. |
| P46-R3-007 | INFO | Reviewer: "No action required — the bullet is acceptable as-is." No change. |
| P46-R3-008 | INFO | No defect. No change. |
| P46-R3-009 | INFO | No defect. No change. |
| P46-R3-010 | INFO | Consistency confirmed. No change. |
| P46-R3-011 | INFO | No defect. No change. |
| P46-R3-012 | WARNING | Task 2.8 now mandates a defensive re-check of `ChannelViewModel.startChannel()` during implementation. Source file already inspected during plan authoring: lines 74-79 contain no `authToken` gate. Documented in Task 2.8 DoD. |
| P46-R3-013 | WARNING | `ChannelViewModel.kt` is intentionally NOT in Files in scope because the inspection confirmed no modification is needed. Task 2.8 DoD escalates to the user if a gate is discovered during implementation — preventing a workflow disruption without bloating scope. |
| P46-R3-014 | INFO | No defect (Ktor `request.header("Authorization")` returns `null` when omitted). No change. |
| P46-R3-015 | INFO | No defect (DataStore `edit` block is mutex-serialized; one-time init pattern is safe). No change. |
