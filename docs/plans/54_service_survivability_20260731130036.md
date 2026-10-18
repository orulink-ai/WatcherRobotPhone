<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 54 — MCP server survivability (battery-optimization exemption + service restart hardening)

Make the foreground MCP server survive aggressive OEM (ColorOS/MIUI/…) background killing via two capabilities agreed with the user:
- **US1** — let the user grant the Doze battery-optimization exemption from inside the app (flavor-gated: gms = one-tap direct request; foss = settings list, no permission).
- **US2** — restart the server after the app is swiped from recents and after an app update, when it was running.

US1 is sequenced before US2 because the two user stories are code-independent and US1 delivers the exemption that makes US2's **`onTaskRemoved`** restart reliable. Note: `ACTION_MY_PACKAGE_REPLACED` is itself an FGS-background-start-exempt broadcast (same class as `BOOT_COMPLETED`, which `BootCompletedReceiver` already relies on), so the `MY_PACKAGE_REPLACED` restart does NOT depend on the battery exemption — only `onTaskRemoved` (task-swipe is not an exempt trigger) does.

**Agreed decisions (do NOT deviate):**
- Battery UI = a card on the **Server screen**, shown **only when the app is NOT exempt**, with a one-tap fix button. **No** start-time prompt/dialog.
- Restart condition = **"if it was running"** via a **new persisted `server_running` flag** encoding the user's start/stop **intent** — set `true` on `ACTION_START`, `false` on `ACTION_STOP` (user-ratified 2026-07-31; supersedes the earlier "true while running" wording, adopted to make the write deterministic/race-free and to also cover a server killed mid-startup). `onTaskRemoved` uses the live in-memory status; `MY_PACKAGE_REPLACED` reads the persisted flag.
- `onTaskRemoved` = **always attempt** restart when running, **catching** `ForegroundServiceStartNotAllowedException`.
- `START_STICKY` is already implemented — NOT in scope.

**Explicitly OUT of scope:** OEM auto-start deep-links; WorkManager watchdog; keep-alive health screen; any change to `EventChannelService` restart behavior (`MY_PACKAGE_REPLACED` restarts ONLY the MCP server).

---

## User Story 1 — In-app battery-optimization exemption (flavor-gated hybrid)

**Why**: Doze battery optimization is the primary cause of the server being killed in the background, and the exemption also makes US2's `onTaskRemoved` restart succeed. F-Droid flags `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, so only the `gms` flavor declares it; `foss` opens the settings list — mirroring the existing `LocationProvider` flavor split.

**Acceptance criteria**
- [ ] `gms`: one tap fires the system "ignore battery optimizations?" dialog.
- [ ] `foss`: the button opens the battery-optimization settings list; the merged `foss` manifest contains NO `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- [ ] The Server screen shows the fix card ONLY when not exempt; it disappears after the exemption is granted (re-checked on return to the app).

### Task 1.1 — Interface + shared state helper (main)

- [x] **Action** — CREATE `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/power/BatteryOptimizationManager.kt`
  ```kotlin
  package com.danielealbano.androidremotecontrolmcp.services.power

  /** Flavor-gated access to the Doze battery-optimization exemption. */
  interface BatteryOptimizationManager {
      /** True when the app is exempt from Doze battery optimization. */
      fun isIgnoringBatteryOptimizations(): Boolean

      /**
       * Launch the flavor-appropriate exemption flow (direct request on gms, settings list on foss).
       * MUST be called while the app is in the foreground (invoked from a button tap). Implementations
       * start the Activity from the injected application context with FLAG_ACTIVITY_NEW_TASK, so no
       * Activity reference is passed in.
       */
      fun requestExemption()
  }
  ```
  (Rationale for no `Activity` param: the codebase avoids holding `Activity` in ViewModels; the Singleton impl already has an injected `@ApplicationContext`, and the request is triggered from a foreground button tap, so a background-activity-start restriction does not apply.)
- [x] **Action** — CREATE `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/power/BatteryOptimizationState.kt` (shared check, avoids duplication across flavor impls)
  ```kotlin
  package com.danielealbano.androidremotecontrolmcp.services.power

  import android.content.Context
  import android.os.PowerManager

  internal fun Context.isIgnoringBatteryOptimizations(): Boolean =
      (getSystemService(Context.POWER_SERVICE) as PowerManager)
          .isIgnoringBatteryOptimizations(packageName)
  ```

**Definition of Done**
- [x] Both files compile; the helper is `internal` and used by both flavor impls.

### Task 1.2 — gms implementation + permission + DI

- [x] **Action** — CREATE `app/src/gms/kotlin/com/danielealbano/androidremotecontrolmcp/services/power/GmsBatteryOptimizationManagerImpl.kt`
  ```kotlin
  package com.danielealbano.androidremotecontrolmcp.services.power

  import android.content.ActivityNotFoundException
  import android.content.Context
  import android.content.Intent
  import android.net.Uri
  import android.provider.Settings
  import android.util.Log
  import dagger.hilt.android.qualifiers.ApplicationContext
  import javax.inject.Inject

  /** gms flavor: one-tap system exemption dialog. */
  class GmsBatteryOptimizationManagerImpl
      @Inject
      constructor(
          @param:ApplicationContext private val context: Context,
      ) : BatteryOptimizationManager {
          override fun isIgnoringBatteryOptimizations(): Boolean =
              context.isIgnoringBatteryOptimizations()

          override fun requestExemption() {
              try {
                  context.startActivity(
                      Intent(
                          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                          Uri.parse("package:${context.packageName}"),
                      ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                  )
              } catch (e: ActivityNotFoundException) {
                  Log.w(TAG, "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog unavailable; opening settings list", e)
                  context.startActivity(
                      Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                  )
              }
          }

          private companion object {
              const val TAG = "MCP:BatteryOpt"
          }
      }
  ```
  (The catch logs `e` before falling back — the project's detekt `SwallowedException` rule requires it; matches `IntentDispatcherImpl` / `AppManagerImpl`.)
- [x] **Action** — MODIFY `app/src/gms/AndroidManifest.xml`: add, at the top-level `<manifest>` scope (beside the existing `ACCESS_BACKGROUND_LOCATION`), `<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />`. MUST NOT be added to `src/main` or `src/foss`.
- [x] **Action** — CREATE `app/src/gms/kotlin/com/danielealbano/androidremotecontrolmcp/di/GmsBatteryModule.kt` (mirror `GmsLocationModule`)
  ```kotlin
  package com.danielealbano.androidremotecontrolmcp.di

  import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManager
  import com.danielealbano.androidremotecontrolmcp.services.power.GmsBatteryOptimizationManagerImpl
  import dagger.Binds
  import dagger.Module
  import dagger.hilt.InstallIn
  import dagger.hilt.components.SingletonComponent
  import javax.inject.Singleton

  @Module
  @InstallIn(SingletonComponent::class)
  abstract class GmsBatteryModule {
      @Binds
      @Singleton
      abstract fun bindBatteryOptimizationManager(
          impl: GmsBatteryOptimizationManagerImpl,
      ): BatteryOptimizationManager
  }
  ```

**Definition of Done**
- [x] gms variant compiles; the new file introduces no lint/detekt violations (gates executed in Task 3.2); merged gms manifest contains `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (verified in US3).

### Task 1.3 — foss implementation + DI

- [x] **Action** — CREATE `app/src/foss/kotlin/com/danielealbano/androidremotecontrolmcp/services/power/FossBatteryOptimizationManagerImpl.kt`
  ```kotlin
  package com.danielealbano.androidremotecontrolmcp.services.power

  import android.content.Context
  import android.content.Intent
  import android.provider.Settings
  import dagger.hilt.android.qualifiers.ApplicationContext
  import javax.inject.Inject

  /** foss flavor: opens the battery-optimization settings list (no special permission). */
  class FossBatteryOptimizationManagerImpl
      @Inject
      constructor(
          @param:ApplicationContext private val context: Context,
      ) : BatteryOptimizationManager {
          override fun isIgnoringBatteryOptimizations(): Boolean =
              context.isIgnoringBatteryOptimizations()

          override fun requestExemption() {
              context.startActivity(
                  Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              )
          }
      }
  ```
- [x] **Action** — CREATE `app/src/foss/kotlin/com/danielealbano/androidremotecontrolmcp/di/FossBatteryModule.kt` (mirror `FossLocationModule`, binding `FossBatteryOptimizationManagerImpl` → `BatteryOptimizationManager`, `@Binds @Singleton`).

**Definition of Done**
- [x] foss variant compiles; merged foss manifest has NO `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (verified in US3).

### Task 1.4 — String resources

- [x] **Action** — MODIFY `app/src/main/res/values/strings.xml`: add (match existing naming style)
  - `battery_optimization_card_title` = "Keep the server running"
  - `battery_optimization_card_body` = "Android battery optimization can stop the MCP server in the background. Disable it for this app so the server stays reachable."
  - `battery_optimization_card_action` = "Disable battery optimization"

**Definition of Done**
- [x] Strings resolve; no hardcoded UI text in the composable.

### Task 1.5 — MainViewModel: battery state + refresh + request

- [x] **Action** — MODIFY `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`
  - Add constructor param `private val batteryOptimizationManager: BatteryOptimizationManager` (append to the existing `@Inject constructor(...)` list).
  - Add state flow (mirror the existing `_isCameraPermissionGranted` pattern):
    ```kotlin
    private val _isBatteryOptimizationIgnored = MutableStateFlow(false)
    val isBatteryOptimizationIgnored: StateFlow<Boolean> = _isBatteryOptimizationIgnored.asStateFlow()
    ```
  - Inside the existing `refreshPermissionStatus(context: Context)` body, add:
    ```kotlin
    _isBatteryOptimizationIgnored.value = batteryOptimizationManager.isIgnoringBatteryOptimizations()
    ```
  - Add (no `Activity`/`Context` param — the manager uses its injected application context):
    ```kotlin
    fun requestBatteryOptimizationExemption() {
        batteryOptimizationManager.requestExemption()
    }
    ```
- [x] **Action** — MODIFY `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt`: update EVERY existing `MainViewModel(...)` construction site (currently at lines ~95, ~442, ~476, ~1031) to pass a `batteryOptimizationManager` mock as the new argument. Without this the existing test file will not compile.

**Definition of Done**
- [x] `isBatteryOptimizationIgnored` reflects the current exemption; `refreshPermissionStatus` (already invoked from `MainActivity` on the relevant lifecycle events) updates it; no new lifecycle wiring added; existing `MainViewModelTest` compiles.

### Task 1.6 — Server screen card

- [x] **Action** — CREATE `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/BatteryOptimizationCard.kt`: a stateless composable `BatteryOptimizationCard(onRequestExemption: () -> Unit, modifier: Modifier = Modifier)` styled to match `ServerStatusCard`/`ConnectionInfoCard` (`ElevatedCard`, title + body via `stringResource(R.string.battery_optimization_card_*)`, a button calling `onRequestExemption`).
- [x] **Action** — MODIFY `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt`
  - Add import: `com.danielealbano.androidremotecontrolmcp.ui.components.BatteryOptimizationCard`.
  - Collect state: `val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()`.
  - In the screen's scrolling `Column`, render the card ONLY when not exempt (no `Activity` cast needed):
    ```kotlin
    if (!isBatteryOptimizationIgnored) {
        BatteryOptimizationCard(
            onRequestExemption = { viewModel.requestBatteryOptimizationExemption() },
        )
    }
    ```
  - Place it near the top of the screen content (adjacent to the server-status/permission area); do NOT introduce business logic into the composable.

**Definition of Done**
- [x] Card appears when not exempt and is hidden once exempt; introduces no lint/detekt violations (gates executed in Task 3.2); no logic beyond delegating to the ViewModel.

### Task 1.7 — Tests (US1)

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/power/BatteryOptimizationStateTest.kt`
**Setup**: MockK `Context` returning a mocked `PowerManager` from `getSystemService(POWER_SERVICE)`; stub `packageName`.

| Test | Verifies |
|------|----------|
| `returns true when PowerManager reports exempt` | Extension returns the `PowerManager.isIgnoringBatteryOptimizations` result. **Setup**: stub returns true. |
| `returns false when PowerManager reports not exempt` | Same, false path. |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt` (extend the existing file)
**Setup**: `@MockK BatteryOptimizationManager` added to the existing mocks; Turbine on `isBatteryOptimizationIgnored`.

| Test | Verifies |
|------|----------|
| `refreshPermissionStatus reflects not-exempt` | Flow = false after refresh when manager returns false. **Setup**: `isIgnoringBatteryOptimizations()`=false. |
| `refreshPermissionStatus reflects exempt after grant` | Flow flips false→true across two refreshes. **Setup**: manager returns false then true. |
| `requestBatteryOptimizationExemption delegates to manager` | `verify { batteryOptimizationManager.requestExemption() }`. |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/manifest/BatteryPermissionManifestTest.kt` (parse checked-in manifests, mirror `ExportedComponentsManifestTest`'s XML-parse approach)
**Setup**: parse `src/gms/AndroidManifest.xml`, `src/main/AndroidManifest.xml`, and `src/foss/AndroidManifest.xml` (if present) for `<uses-permission>` names.

| Test | Verifies |
|------|----------|
| `gms manifest declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Present in `src/gms`. |
| `main manifest does not declare it` | Absent in `src/main`. |
| `foss manifest does not declare it` | Absent in `src/foss` (or foss manifest absent entirely). |

**Note**: the flavor impls' real intent dispatch (`requestExemption`) launches an Activity from the platform and is covered by Manual QA, not a JVM unit test.

**Definition of Done**
- [x] All listed tests exist and are written to pass, and the existing `MainViewModelTest` still compiles (gates executed in Task 3.2).

---

## User Story 2 — Service restart hardening

**Why**: `START_STICKY` is already present; the remaining gaps are OEM task-swipe kills and the server staying down after an app update. `onTaskRemoved` runs after the app leaves the foreground and therefore benefits from the US1 battery exemption to start the FGS; `MY_PACKAGE_REPLACED` is an FGS-background-start-exempt broadcast and works independently of the exemption.

**Acceptance criteria**
- [x] Persisted `server_running` encodes the user's start/stop intent — set `true` on `ACTION_START`, `false` on `ACTION_STOP` (a failed start leaves the intent `true`, which a later restart trigger simply retries).
- [ ] Swiping the app from recents while running attempts a restart (guaranteed to succeed when battery-exempt; safely caught otherwise).
- [ ] After an app update (`MY_PACKAGE_REPLACED`), the MCP server restarts iff `server_running` is true.
- [x] An explicit stop is never resurrected by either path (the `false` write deterministically wins over any prior `true`).

### Task 2.1 — Persisted `server_running` flag

- [x] **Action** — MODIFY `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`: add
  ```kotlin
  val serverRunning: Flow<Boolean>
  suspend fun updateServerRunning(running: Boolean)
  ```
- [x] **Action** — MODIFY `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`
  - Add key beside the others: `private val SERVER_RUNNING_KEY = booleanPreferencesKey("server_running")`.
  - Add (mirror `updateAutoStartOnBoot` / `serverConfig` read style):
    ```kotlin
    override val serverRunning: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[SERVER_RUNNING_KEY] ?: false }

    override suspend fun updateServerRunning(running: Boolean) {
        dataStore.edit { prefs -> prefs[SERVER_RUNNING_KEY] = running }
    }
    ```

**Definition of Done**
- [x] Default is `false`; the flag persists across process death.

### Task 2.2 — Persist the flag from the service (deterministic, action-based)

- [x] **Action** — MODIFY `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt` (uses the existing `@Inject lateinit var settingsRepository`)
  - Add a private helper + timeout constant. `runBlocking` and `withTimeout` are ALREADY imported in this file (used by `onDestroy`) — do NOT re-add them (ktlint flags duplicate imports); only add companion `private const val FLAG_WRITE_TIMEOUT_MS = 2_000L`. Wrap the bounded write in try/catch that catches ONLY specific exception types so a slow/failed DataStore write logs and proceeds instead of crashing this frequently-invoked callback — and so that NO `@Suppress("TooGenericExceptionCaught")` is needed (keeping Task 3.2's "no new suppression" gate intact):
    ```kotlin
    private fun persistServerRunning(running: Boolean) {
        try {
            runBlocking { withTimeout(FLAG_WRITE_TIMEOUT_MS) { settingsRepository.updateServerRunning(running) } }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Timed out persisting server_running=$running", e)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to persist server_running=$running", e)
        }
    }
    ```
    (`TAG` is the existing `McpServerService` companion tag; `Log` is already imported. Add `import kotlinx.coroutines.TimeoutCancellationException` and `import java.io.IOException` only if not already present — `onDestroy` references `TimeoutCancellationException` by FQN, so the simple-name import is likely absent. Rationale: `withTimeout` throws `TimeoutCancellationException`; the DataStore write throws `IOException` (incl. `CorruptionException`); no other checked failure is expected, so a generic `catch (e: Exception)` — and the new suppression it would require — is unnecessary.)
  - In `onStartCommand`, persist the flag SYNCHRONOUSLY per action (intents are delivered sequentially on the main thread → deterministic last-writer-wins, no async race):
    - `ACTION_STOP` branch, BEFORE `stopSelf()`: `persistServerRunning(false)`.
    - `ACTION_START, null` branch, BEFORE the `serverActive.compareAndSet(...)` check: `persistServerRunning(true)`.
  - Do NOT write the flag from `startServer` (this REMOVES the async `true`-write entirely — the source of the race with the stop-path `false`-write).
  - Do NOT clear the flag in `onDestroy` — it also runs on OEM/system kills, where the flag MUST stay `true` for restart-if-running.
  - Rationale: the flag encodes the user's start/stop INTENT. Because both writes are synchronous and issued from `onStartCommand`, an explicit `ACTION_STOP` deterministically commits `false` after any prior `ACTION_START` `true`; a stop can never be overwritten by an in-flight `true`. A short bounded main-thread block for one DataStore edit is acceptable (`onDestroy` already blocks up to ~9s per its existing comment). The reviewer confirmed every explicit-stop caller (`MainViewModel` stop, `AdbServiceTrampolineActivity`, `AdbConfigHandler.handleStopServer`) converges on this single `ACTION_STOP` branch.

**Definition of Done**
- [x] `ACTION_START` durably sets the flag true and `ACTION_STOP` durably sets it false, both before returning from `onStartCommand`; the write is bounded by `withTimeout`; an OEM/system kill leaves the flag true.

### Task 2.3 — Shared restart helpers + `onTaskRemoved`

- [x] **Action** — CREATE `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerRestart.kt` — `internal` helpers so the restart DECISIONS are unit-testable (only the raw platform callbacks remain untestable). `ForegroundServiceStartNotAllowedException` is API 31+, safe on `minSdk = 33`:
  ```kotlin
  package com.danielealbano.androidremotecontrolmcp.services.mcp

  import android.app.ForegroundServiceStartNotAllowedException
  import android.content.Context
  import android.content.Intent
  import android.util.Log
  import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
  import kotlinx.coroutines.flow.first

  private const val TAG = "MCP:ServerRestart"

  /** Re-issue an ACTION_START to the MCP foreground service. */
  internal fun restartMcpServer(context: Context) {
      context.startForegroundService(
          Intent(context, McpServerService::class.java).apply { action = McpServerService.ACTION_START },
      )
  }

  /** Task-removal restart: attempt only when the server is running; swallow the FGS-not-allowed case. */
  internal fun restartMcpServerIfForeground(context: Context, isServerRunning: Boolean) {
      if (!isServerRunning) return
      try {
          restartMcpServer(context)
      } catch (e: ForegroundServiceStartNotAllowedException) {
          Log.w(TAG, "Cannot restart on task removal (app not battery-exempt)", e)
      }
  }

  /** Package-replaced restart: attempt only when the persisted intent flag is true. */
  internal suspend fun restartMcpServerIfRunning(context: Context, settingsRepository: SettingsRepository) {
      if (settingsRepository.serverRunning.first()) {
          restartMcpServer(context)
      }
  }
  ```
- [x] **Action** — MODIFY `McpServerService.kt`: override `onTaskRemoved` to delegate to the helper (passing the live companion status):
  ```kotlin
  override fun onTaskRemoved(rootIntent: Intent?) {
      restartMcpServerIfForeground(this, _serverStatus.value is ServerStatus.Running)
      super.onTaskRemoved(rootIntent)
  }
  ```

**Definition of Done**
- [x] Swiping with a running server re-issues the start via `restartMcpServerIfForeground`; the FGS-not-allowed case is caught; the decision is unit-tested (Task 2.6).

### Task 2.4 — `MY_PACKAGE_REPLACED` receiver

- [x] **Action** — CREATE `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/PackageReplacedReceiver.kt`. Mirror `BootCompletedReceiver` (`@AndroidEntryPoint`, `@Inject SettingsRepository`, `goAsync()` + detached `CoroutineScope(SupervisorJob() + Dispatchers.IO)` + `withTimeout`). `onReceive`: ignore any action other than `Intent.ACTION_MY_PACKAGE_REPLACED`; otherwise `goAsync()` and, in the detached coroutine, `withTimeout(SETTINGS_READ_TIMEOUT_MS) { restartMcpServerIfRunning(context, settingsRepository) }` (the shared helper from Task 2.3), finishing the pending result in `finally`. Do NOT touch `EventChannelService` (out of scope). The receiver's `onReceive` is a thin wrapper (no unit test — the decision it delegates to is unit-tested in Task 2.6).
- [x] **Action** — MODIFY `app/src/main/AndroidManifest.xml`: register beside `BootCompletedReceiver`
  ```xml
  <receiver
      android:name=".services.mcp.PackageReplacedReceiver"
      android:exported="false">
      <intent-filter>
          <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
      </intent-filter>
  </receiver>
  ```
  (`MY_PACKAGE_REPLACED` targets only this app; no `data` scheme required.)

**Definition of Done**
- [x] Updating the APK with `server_running` true restarts the MCP server; a prior explicit stop leaves it stopped; the restart decision lives in the testable `restartMcpServerIfRunning`.

### Task 2.5 — Keep exported-components guard green

- [x] **Action** — VERIFY (no code change expected): `PackageReplacedReceiver` is `exported="false"`, so `ExportedComponentsManifestTest` (from the GHSA DUMP-gate work) still passes. If the receiver is ever exported, it MUST be gated/allow-listed there with rationale — but it MUST remain `exported="false"`.

### Task 2.6 — Tests (US2)

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryServerRunningTest.kt` — reuse the existing `SettingsRepositoryImplTest` DataStore setup (temp/in-memory DataStore).

| Test | Verifies |
|------|----------|
| `serverRunning defaults to false` | Default value. |
| `updateServerRunning true then false round-trips` | Flow reflects each write. |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerRestartTest.kt` — unit-test the extracted restart decisions with `runTest`. Assert with `any()` — a real `Intent`'s action reads back `null` under `unitTests.isReturnDefaultValues = true`, so NEVER assert on `it.action`; use `verify` (not `coVerify`) since `startForegroundService` is not suspend. This matches the `AdbConfigHandlerTest` precedent (`verify(exactly = 1) { startForegroundService(any()) }`, mocked `Intent`).
**Setup**: `@MockK(relaxed = true) Context`; `@MockK SettingsRepository` with `every { serverRunning } returns flowOf(...)`.

| Test | Verifies |
|------|----------|
| `restartMcpServerIfRunning starts service when flag true` | `verify(exactly = 1) { context.startForegroundService(any()) }`. **Setup**: `serverRunning` = `flowOf(true)`. |
| `restartMcpServerIfRunning does not start when flag false` | `verify(exactly = 0) { context.startForegroundService(any()) }`. **Setup**: `flowOf(false)`. |
| `restartMcpServerIfForeground starts when running` | `verify(exactly = 1) { context.startForegroundService(any()) }`. **Setup**: `isServerRunning = true`. |
| `restartMcpServerIfForeground is a no-op when not running` | `verify(exactly = 0) { context.startForegroundService(any()) }`. **Setup**: `isServerRunning = false`. |
| `restartMcpServerIfForeground swallows FGS-not-allowed` | No exception propagates. **Setup**: `context.startForegroundService(any())` throws `ForegroundServiceStartNotAllowedException`; `isServerRunning = true`. |

**Coverage boundary**: the restart DECISIONS (`restartMcpServerIfRunning`, `restartMcpServerIfForeground`) and the persisted-flag round-trip (Task 2.1) are unit-tested. Only the raw platform-callback wiring — `onStartCommand` receiving `ACTION_START`/`ACTION_STOP` and `onTaskRemoved` receiving the OS callback with the live companion status — is exercised via Manual QA (Task 3.4). This is an inherent JVM-unit-test limit (no Robolectric in the module), not an accepted logic gap: all branchable logic is extracted and covered.

**Definition of Done**
- [x] All listed tests exist and are written to pass (gates executed in Task 3.2).

---

## User Story 3 — Ground-up verification (MUST be performed last)

**Why**: Final, from-scratch verification that everything implemented matches this plan exactly, with zero divergence, zero assumptions, and all quality gates green.

**Acceptance criteria**
- [x] Every created/modified file reviewed against this plan; no extra, missing, or divergent changes.
- [x] All quality gates pass; merged-manifest and flavor behavior confirmed.

### Task 3.1 — Re-read every change against the plan

> **Implementation amendments (2026-07-31, forced by existing detekt gate, behavior-preserving):**
> - `persistServerRunning` was relocated from an `McpServerService` private member to an `internal` helper in `services/mcp/McpServerRestart.kt` (called as `persistServerRunning(settingsRepository, running)`). Reason: `McpServerService` already had 10 functions; the required `onTaskRemoved` override made it 11, and adding `persistServerRunning` as a member would have made it 12, tripping detekt `TooManyFunctions` (cap 10). Placing it with the other server-lifecycle helpers keeps behavior identical and makes it testable. Task 2.2's flag-write semantics/timeout/exception-handling are unchanged.
> - The trivial one-line `emitLogEntry` wrapper in `McpServerService` was inlined to its two call sites (`_serverLogEvents.tryEmit(...)`), removing one function so the class returns to 10 and `TooManyFunctions` passes without any new `@Suppress`. No behavior change.
> - `BatteryOptimizationCard` ships without a `@Preview` (sibling cards suppress `UnusedPrivateMember` file-wide; the plan's "no new @Suppress" gate is stricter, so the optional preview was omitted instead).
> - `PackageReplacedReceiver` (Task 2.4) catches the specific `TimeoutCancellationException` (timeout) and `IOException` (DataStore read) rather than `BootCompletedReceiver`'s generic `catch (e: Exception)` + `@Suppress("TooGenericExceptionCaught")` — this honors the "no new @Suppress" gate while still closing the crash path. `MY_PACKAGE_REPLACED` is FGS-background-start-exempt, so `startForegroundService` does not raise `ForegroundServiceStartNotAllowedException` on this path; the two caught types are the only realistic failures, and the `pendingResult.finish()` runs in `finally` regardless.
>
- [x] Re-read each file below and confirm it matches the corresponding action exactly (no TODOs, no dead code, no out-of-scope edits):
  - `services/power/BatteryOptimizationManager.kt`, `services/power/BatteryOptimizationState.kt`
  - `src/gms/.../services/power/GmsBatteryOptimizationManagerImpl.kt`, `src/gms/.../di/GmsBatteryModule.kt`, `src/gms/AndroidManifest.xml`
  - `src/foss/.../services/power/FossBatteryOptimizationManagerImpl.kt`, `src/foss/.../di/FossBatteryModule.kt`
  - `res/values/strings.xml`, `ui/viewmodels/MainViewModel.kt`, `ui/components/BatteryOptimizationCard.kt`, `ui/screens/ServerScreen.kt`
  - `data/repository/SettingsRepository.kt`, `data/repository/SettingsRepositoryImpl.kt`
  - `services/mcp/McpServerService.kt`, `services/mcp/McpServerRestart.kt`, `services/mcp/PackageReplacedReceiver.kt`, `src/main/AndroidManifest.xml`
  - All new/modified test files (incl. the `MainViewModelTest.kt` constructor updates).
- [x] Confirm NO files outside this list were changed.

### Task 3.2 — Quality gates

- [x] `make lint` → zero ktlint/detekt issues (no new `@Suppress`; confirm the gms catch logs the exception so `SwallowedException` does not fire).
- [x] `make test-unit` → all tests pass, including every new test in US1/US2 and the updated `MainViewModelTest`.
- [x] `./gradlew assembleGmsDebug assembleFossDebug assembleGmsRelease assembleFossRelease` → all four build without errors/warnings.

### Task 3.3 — Merged-manifest & flavor verification

- [x] Dump the merged manifest of each built APK (aapt2) and confirm:
  - gms debug + gms release contain `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
  - foss debug + foss release do NOT contain it.
  - `PackageReplacedReceiver` is present in all four with `exported=false` and the `MY_PACKAGE_REPLACED` intent-filter.
- [x] Confirm `ExportedComponentsManifestTest` is green.

### Task 3.4 — Manual QA (labeled — NOT a substitute for automated tests)

> **Not performed in this implementation session — requires a physical device.** These three on-device checks are left for the user to run on a real handset (per direction, the Realme device was out of scope this session). All automated equivalents (viewmodel/state/manifest/restart-decision unit tests, merged-manifest flavor checks, four-variant assembly) pass.

- [ ] **Manual Test — battery exemption (gms)**: Server screen shows the card when not exempt → tap → system dialog → grant → return to app → card disappears.
- [ ] **Manual Test — battery exemption (foss)**: tap → battery-optimization settings list opens (no crash, no permission prompt) → exempt the app → return → card disappears.
- [ ] **Manual Test — restart hardening (ColorOS/Realme device)**: start server → swipe app from recents → server notification persists/reappears. Update the APK (`adb install -r`) with the server running → server restarts after update. Explicitly stop the server, then update → server stays stopped.

### Task 3.5 — Plan-compliance review

- [x] Spawn the `code-reviewer` subagent in plan-compliance mode over the entire implementation; fix ALL reported findings (CRITICAL/WARNING/INFO); re-run until clean. **Result: PASS (0 CRITICAL / 0 WARNING / 0 INFO) on the first pass.**

**Definition of Done**
- [ ] Lint clean, all tests pass, all four variants build, merged-manifest checks pass, manual QA passes, and `code-reviewer` reports no findings.

---

## Review history

- **2026-07-31 — plan-reviewer pass 1**: FAIL (0 CRITICAL / 5 WARNING / 6 INFO). Findings P54-001…P54-011 all addressed: corrected the FGS-exemption rationale (P54-001); log the swallowed `ActivityNotFoundException` (P54-002); added the `MainViewModelTest` constructor-update action (P54-003); extracted the receiver decision (P54-004); durable stop-write (P54-005); corrected `minSdk 33` (P54-006); corrected `SettingsRepositoryImplTest` reference (P54-007); switched `requestExemption` to the injected app context so no `Activity` enters the ViewModel (P54-008, P54-009); coverage note (P54-010); added Definition of Done to the test tasks (P54-011).
- **2026-07-31 — plan-reviewer pass 2**: FAIL (0 CRITICAL / 3 WARNING / 1 INFO); confirmed 8/11 pass-1 fixes solid. Remaining findings addressed: **P54-012** — receiver test asserted `it.action` on a real `Intent` (reads `null` in JVM tests) → changed to `verify(exactly = 1) { startForegroundService(any()) }` per the `AdbConfigHandlerTest` precedent, in a new `McpServerRestartTest`. **P54-005 (reopened)** — the stop-write still raced the async `true`-write → removed the `startServer` async write entirely; both flag writes are now synchronous and action-based in `onStartCommand`. **P54-010 (reopened)** — extracted the restart decisions into `McpServerRestart.kt` and unit-tested all branches; only raw OS-callback wiring remains Manual-QA. **P54-013** — bounded the `runBlocking` flag write with `withTimeout`.
- **2026-07-31 — plan-reviewer pass 3**: FAIL (0 CRITICAL / 2 WARNING / 1 INFO); confirmed P54-005/010/012 genuinely resolved. Remaining findings addressed: **P54-014** — the bounded `runBlocking { withTimeout {...} }` lacked exception handling (a slow/failed write would crash the service) → wrapped in the same try/catch shape `onDestroy` uses. **P54-015** — the race-free fix changed the flag from "true while running" to intent-based ("true on `ACTION_START`"), diverging from agreed decision 4 and contradicting the decisions block → **user ratified the intent-based semantics on 2026-07-31**; the decisions block is reconciled accordingly. **P54-016** — `runBlocking`/`withTimeout` are already imported → the plan now says not to re-add them (only add `FLAG_WRITE_TIMEOUT_MS`), avoiding a ktlint duplicate-import failure.
- **2026-07-31 — plan-reviewer pass 4**: FAIL (0 CRITICAL / 0 WARNING / 1 INFO); confirmed P54-014/015/016 genuinely resolved and no new correctness/codebase defects. Remaining finding addressed: **P54-017** — per-task Definition-of-Done items in Tasks 1.2/1.6/1.7/2.6 named the gate commands (`make lint` / `make test-unit`), whereas the project rule requires gates to run only once at the end (Task 3.2) → reworded those DoDs to outcome descriptions.
- **2026-07-31 — plan-reviewer pass 5**: FAIL (0 CRITICAL / 1 WARNING / 0 INFO); confirmed P54-017 resolved. Remaining finding addressed: **P54-018** — Task 2.2 instructed replicating `onDestroy`'s generic `catch (e: Exception)` + `@Suppress("TooGenericExceptionCaught")`, which directly contradicts Task 3.2's DoD forbidding any new `@Suppress` (and the project's no-suppression rule) → changed `persistServerRunning` to catch only specific types (`TimeoutCancellationException` for the timeout, `IOException` for the DataStore write), removing the generic catch so no suppression is needed and no contradiction remains.
- **2026-07-31 — plan-reviewer pass 6**: **PASS** (0 CRITICAL / 0 WARNING / 0 INFO). P54-018 verified resolved (specific-exception catches, no new suppression, no contradiction). Full re-scan (structure, ordering, forward-dependencies, DoD phrasing, test feasibility, codebase accuracy, resurrection safety, and all 8 AGREED DECISIONS as amended by the ratified intent-based flag) surfaced zero findings. Plan is finalized and ready for implementation on user approval.

## Post-implementation code-review findings

- **2026-07-31 — code-reviewer pass 1 (plan-compliance)**: PASS (0/0/0).
- **2026-07-31 — code-reviewer pass 2 (adversarial)**: FAIL (0 CRITICAL / 1 WARNING / 1 INFO).
  - **W-1 (FIXED)** — `restartMcpServerIfRunning` (package-replaced path) called `restartMcpServer` → `startForegroundService` WITHOUT catching `ForegroundServiceStartNotAllowedException`. On an OEM that delivers `MY_PACKAGE_REPLACED` but does not honor its FGS-background-start exemption, the uncaught `IllegalStateException` subclass would crash the app right after an update (`PackageReplacedReceiver`'s catch only covered `TimeoutCancellationException`/`IOException`). **Fix:** both restart entry points now funnel through a shared `restartMcpServerSwallowingFgs(context)` that logs-and-proceeds on `ForegroundServiceStartNotAllowedException` (no generic catch, no new `@Suppress`). Added `McpServerRestartTest.restartMcpServerIfRunning swallows FGS-not-allowed`.
  - **I-2 (MITIGATED per user decision 2026-07-31)** — the US2 acceptance criterion "An explicit stop is never resurrected" is worded absolutely, but the `false` flag write is a **bounded** (2s) synchronous main-thread write per the user-ratified ANR-safe, race-free design (P54-005/P54-014). A pathological >2s DataStore stall on the exact STOP could abandon the `false` write, leaving `server_running=true`, and a later `MY_PACKAGE_REPLACED` could then restart a stopped server. An absolute guarantee is physically incompatible with the ratified constraints (an unbounded write violates ANR-safety; an async fallback reintroduces the P54-005 race; a same-instant retry does not guarantee commit). **User chose the belt-and-suspenders mitigation:** `McpServerService` now tracks `lastIntentWasStop` (set in each `onStartCommand` branch) and, in `onDestroy`, re-attempts `persistServerRunning(false)` **only when the last action was an explicit stop** — a second bounded write at a materially later time that recovers transient stalls that clear within the ~9s `onDestroy` budget. It is gated so an OEM/system kill (last action = start → `lastIntentWasStop=false`) still never clears the flag, preserving restart-if-running. Both writes are on the main thread, so determinism (P54-005) is preserved. This shrinks the window to near-zero; the residual (both bounded writes stalling >2s across the whole shutdown) remains best-effort by physical necessity, which is inherent to any bounded write.
