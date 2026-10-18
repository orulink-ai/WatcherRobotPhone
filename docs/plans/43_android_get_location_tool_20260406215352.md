<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 43 — `android_get_location` MCP Tool

## User Story 1: Add Google Play Services Location dependency

**Why**: The project currently has no Google Play Services dependency. `FusedLocationProviderClient` requires `play-services-location`. This must be added before any location code can compile.

### Acceptance Criteria

- [x] `play-services-location` version declared in `gradle/libs.versions.toml`
- [x] Library alias declared in `[libraries]` section of `libs.versions.toml`
- [x] `implementation(libs.play.services.location)` added to `app/build.gradle.kts`

---

### Task 1.1: Add `play-services-location` to version catalog and build script

**Action 1** — Modify `gradle/libs.versions.toml`

In `[versions]` section, add after the `camerax` line:

```
play-services-location = "21.3.0"
```

In `[libraries]` section, add after the `camerax-video` line:

```
play-services-location = { group = "com.google.android.gms", name = "play-services-location", version.ref = "play-services-location" }
```

**Action 2** — Modify `app/build.gradle.kts`

In the `dependencies` block, add after the CameraX dependencies:

```kotlin
// Google Play Services Location
implementation(libs.play.services.location)
```

**Definition of Done**:

- [x] Version catalog contains `play-services-location` version and library entry
- [x] `build.gradle.kts` references `libs.play.services.location`

---

## User Story 2: Add `ACCESS_FINE_LOCATION` permission and `uses-feature` declaration

**Why**: `FusedLocationProviderClient` requires `ACCESS_FINE_LOCATION` runtime permission. The feature must be declared as `required="false"` so the app can install on devices without GPS hardware.

### Acceptance Criteria

- [x] `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` declared in `AndroidManifest.xml`
- [x] `android.hardware.location.gps` feature declared with `required="false"`

---

### Task 2.1: Add permission and feature declarations to AndroidManifest.xml

**Action 1** — Modify `app/src/main/AndroidManifest.xml`

Add after the existing media read permissions:

```xml
<!-- Location -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

Add after the existing `<uses-feature>` declarations:

```xml
<uses-feature android:name="android.hardware.location.gps" android:required="false" />
```

**Definition of Done**:

- [x] Both location permissions declared in manifest
- [x] GPS feature declared as not required

---

## User Story 3: Create `LocationProvider` interface and implementation

**Why**: Location logic must be abstracted behind an interface for Hilt injection and test mocking.

### Acceptance Criteria

- [x] `LocationProvider` interface created with `getLocation(freshFix: Boolean): Result<LocationData>` method
- [x] `LocationData` data class created with `latitude: Double`, `longitude: Double`, `accuracyMeters: Float`, `street: String?`
- [x] `LocationProviderImpl` created using `FusedLocationProviderClient`
- [x] Play Services availability checked on invocation via `GoogleApiAvailability`
- [x] Location permission checked on invocation
- [x] `freshFix=false` returns last known location; `freshFix=true` requests a fresh fix with 10-second timeout
- [x] `Geocoder` used for reverse geocoding; failure is non-fatal (street returns `null`)
- [x] Hilt binding added in `ServiceModule` (in `AppModule.kt`)
- [x] Injected into `McpServerService`

---

### Task 3.1: Create `LocationData` data class

**Action 1** — Create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/LocationData.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val street: String?,
)
```

**Definition of Done**:

- [x] `LocationData` data class exists with all four fields

---

### Task 3.2: Create `LocationProvider` interface

**Action 1** — Create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/location/LocationProvider.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.location

import com.danielealbano.androidremotecontrolmcp.data.model.LocationData

interface LocationProvider {
    companion object {
        /** Timeout for requesting a fresh GPS fix, in milliseconds. */
        const val FRESH_FIX_TIMEOUT_MS = 10_000L
    }

    /**
     * Retrieves the device's current location.
     *
     * @param freshFix If true, requests a fresh GPS fix (may take up to [FRESH_FIX_TIMEOUT_MS]).
     *                 If false, returns the last known location (fast but possibly stale).
     * @return [Result.success] with [LocationData] on success,
     *         [Result.failure] with descriptive exception on failure.
     *         Failure cases:
     *         - Google Play Services not available
     *         - Location permission not granted
     *         - No location available (e.g., GPS disabled, no last known location)
     *         - Timeout waiting for fresh fix
     */
    suspend fun getLocation(freshFix: Boolean): Result<LocationData>
}
```

**Definition of Done**:

- [x] `LocationProvider` interface exists with `getLocation` method and companion constants

---

### Task 3.3: Create `LocationProviderImpl`

**Action 1** — Create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/location/LocationProviderImpl.kt`

Note on `@SuppressLint("MissingPermission")`: **Justified exception per CLAUDE.md no-suppression rule** — Android lint cannot statically verify the runtime permission check that occurs earlier in `getLocation()`. The permission IS checked programmatically before these calls. This is the standard Android pattern when permission is verified at runtime before the annotated call site.

Note on `@Suppress("TooGenericExceptionCaught")`: **Justified exception per CLAUDE.md no-suppression rule** — Used on `getLocation()` and `reverseGeocode()`. Google Play Services and Android Geocoder can throw various undocumented runtime exceptions (beyond the specific `TimeoutCancellationException` handled separately). Catching generic `Exception` at these boundaries is an established project pattern (36 occurrences across 18 files) and the only practical approach since GMS does not declare specific exception types. Note: `CancellationException` is explicitly re-thrown before the generic catch to preserve structured concurrency.

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocationProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LocationProvider {
        private val fusedLocationClient: FusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(context)

        @Suppress("TooGenericExceptionCaught")
        override suspend fun getLocation(freshFix: Boolean): Result<LocationData> {
            val playServicesStatus =
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (playServicesStatus != ConnectionResult.SUCCESS) {
                return Result.failure(
                    IllegalStateException("Google Play Services not available"),
                )
            }

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return Result.failure(
                    SecurityException(
                        "Location permission not granted. " +
                            "Please grant ACCESS_FINE_LOCATION in Android Settings.",
                    ),
                )
            }

            val location =
                try {
                    if (freshFix) {
                        requestFreshLocation()
                    } else {
                        getLastKnownLocation()
                    }
                } catch (e: TimeoutCancellationException) {
                    return Result.failure(
                        IllegalStateException(
                            "Timed out waiting for fresh GPS fix " +
                                "(${LocationProvider.FRESH_FIX_TIMEOUT_MS}ms)",
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Result.failure(
                        IllegalStateException("Failed to get location: ${e.message}", e),
                    )
                }

            if (location == null) {
                return Result.failure(
                    IllegalStateException(
                        "No last known location available. Try with fresh_fix=true.",
                    ),
                )
            }

            val street = reverseGeocode(location.latitude, location.longitude)

            return Result.success(
                LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    street = street,
                ),
            )
        }

        @SuppressLint("MissingPermission")
        private suspend fun getLastKnownLocation(): Location? =
            fusedLocationClient.lastLocation.await()

        @SuppressLint("MissingPermission")
        private suspend fun requestFreshLocation(): Location =
            withTimeout(LocationProvider.FRESH_FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val request =
                        LocationRequest
                            .Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L)
                            .setMaxUpdates(1)
                            .build()

                    val callback =
                        object : LocationCallback() {
                            override fun onLocationResult(result: LocationResult) {
                                fusedLocationClient.removeLocationUpdates(this)
                                val loc = result.lastLocation
                                if (loc != null) {
                                    cont.resume(loc)
                                } else {
                                    cont.resumeWithException(
                                        IllegalStateException("Location result was null"),
                                    )
                                }
                            }
                        }

                    // Main looper is the standard GMS pattern for location callbacks.
                    // The callback is lightweight (only resumes a coroutine), so no
                    // main-thread performance concern.
                    fusedLocationClient.requestLocationUpdates(
                        request,
                        callback,
                        Looper.getMainLooper(),
                    )

                    cont.invokeOnCancellation {
                        fusedLocationClient.removeLocationUpdates(callback)
                    }
                }
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun reverseGeocode(
            latitude: Double,
            longitude: Double,
        ): String? {
            if (!Geocoder.isPresent()) {
                Log.d(TAG, "Geocoder not present on this device")
                return null
            }

            return try {
                suspendCancellableCoroutine { cont ->
                    val geocoder = Geocoder(context, Locale.getDefault())
                    geocoder.getFromLocation(
                        latitude,
                        longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: List<Address>) {
                                cont.resume(addresses.firstOrNull()?.getAddressLine(0))
                            }

                            override fun onError(errorMessage: String?) {
                                Log.d(TAG, "Geocoder onError: $errorMessage")
                                cont.resume(null)
                            }
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Reverse geocoding failed: ${e.message}")
                null
            }
        }

        private suspend fun <T> Task<T>.await(): T? =
            suspendCancellableCoroutine { cont ->
                addOnSuccessListener { result -> cont.resume(result) }
                addOnFailureListener { e -> cont.resumeWithException(e) }
                addOnCanceledListener { cont.cancel() }
            }

        companion object {
            private const val TAG = "MCP:LocationProvider"
        }
    }
```

**Definition of Done**:

- [x] `LocationProviderImpl` created matching the code above

---

### Task 3.4: Add Hilt binding and inject into McpServerService

**Action 1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`

In the `ServiceModule` abstract class, add after `bindPermissionChecker`:

```kotlin
@Binds
@Singleton
abstract fun bindLocationProvider(impl: LocationProviderImpl): LocationProvider
```

Add corresponding imports for `LocationProvider` and `LocationProviderImpl`.

**Action 2** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

Add import:

```kotlin
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
```

Add an `@Inject lateinit var locationProvider: LocationProvider` field alongside the other injected dependencies.

Note: The `registerLocationTools()` call in `registerAllTools()` is added in Task 4.2 (after `registerLocationTools` is defined in Task 4.1).

**Definition of Done**:

- [x] Hilt `@Binds` for `LocationProvider` added to `ServiceModule`
- [x] `locationProvider` injected into `McpServerService`

---

## User Story 4: Create `LocationTools.kt` with `get_location` handler

**Why**: This is the core MCP tool that exposes location functionality to MCP clients.

### Acceptance Criteria

- [x] `GetLocationHandler` created in `LocationTools.kt` following the existing handler pattern
- [x] Tool name: `get_location`
- [x] Input parameter: `fresh_fix` (optional boolean, default `false`)
- [x] `fresh_fix` parameter is force-overridden to `false` when the user has disabled it via `freshFixParamEnabled=false`
- [x] Returns JSON with `latitude`, `longitude`, `accuracy_meters`, `street` (nullable)
- [x] Uses `untrustedTextResult()` (device-derived content)
- [x] Play Services unavailable → `McpToolException.ActionFailed`
- [x] Permission not granted → `McpToolException.PermissionDenied`
- [x] No location available → `McpToolException.ActionFailed`
- [x] Tool description mentions 10s timeout for fresh fix, nullable street, permission requirement
- [x] `registerLocationTools()` function created with `perms.isToolEnabled` check
- [x] `registerLocationTools()` called from `McpServerService.registerAllTools()`
- [x] Tool registered in `ALL_TOOL_CATEGORIES` in `McpToolsSettingsScreen.kt` with `fresh_fix` param toggle

---

### Task 4.1: Create `LocationTools.kt`

**Action 1** — Create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/LocationTools.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// get_location
// ─────────────────────────────────────────────────────────────────────────────

class GetLocationHandler
    @Inject
    constructor(
        private val locationProvider: LocationProvider,
    ) {
        suspend fun execute(
            arguments: JsonObject?,
            freshFixParamEnabled: Boolean,
        ): CallToolResult {
            Log.d(TAG, "Executing get_location")

            val requestedFreshFix =
                McpToolUtils.optionalBoolean(arguments, "fresh_fix", false)
            val freshFix = if (freshFixParamEnabled) requestedFreshFix else false

            val result = locationProvider.getLocation(freshFix)

            if (result.isFailure) {
                val exception = result.exceptionOrNull()!!
                val message = exception.message ?: "Unknown error"
                if (exception is SecurityException) {
                    throw McpToolException.PermissionDenied(message)
                }
                throw McpToolException.ActionFailed(message)
            }

            val locationData = result.getOrThrow()
            val jsonResult =
                buildJsonObject {
                    put("latitude", locationData.latitude)
                    put("longitude", locationData.longitude)
                    put("accuracy_meters", locationData.accuracyMeters)
                    put("street", locationData.street)
                }

            return McpToolUtils.untrustedTextResult(jsonResult.toString())
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
            freshFixParamEnabled: Boolean,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Retrieves the device's current location including coordinates, accuracy, " +
                        "and street address. Returns latitude, longitude, accuracy in meters " +
                        "(68% confidence radius), and street address (may be null if reverse " +
                        "geocoding is unavailable). Parameter 'fresh_fix': if true, requests a " +
                        "fresh GPS fix which may take up to 10 seconds; if false (default), " +
                        "returns the last known location which is faster but may be stale. " +
                        "Requires ACCESS_FINE_LOCATION permission to be granted on the device. " +
                        "Requires Google Play Services to be available on the device.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("fresh_fix") {
                                    put("type", "boolean")
                                    put(
                                        "description",
                                        "If true, requests a fresh GPS fix (may take up to " +
                                            "10 seconds). If false (default), returns last " +
                                            "known location (faster but possibly stale).",
                                    )
                                }
                            },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments, freshFixParamEnabled) }
        }

        companion object {
            const val TOOL_NAME = "get_location"
            private const val TAG = "MCP:GetLocationHandler"
        }
    }

fun registerLocationTools(
    server: Server,
    locationProvider: LocationProvider,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(GetLocationHandler.TOOL_NAME)) {
        GetLocationHandler(locationProvider).register(
            server,
            toolNamePrefix,
            freshFixParamEnabled =
                perms.isParamEnabled(
                    GetLocationHandler.TOOL_NAME,
                    "fresh_fix",
                ),
        )
    }
}
```

**Definition of Done**:

- [x] `LocationTools.kt` created matching the code above

---

### Task 4.2: Wire `registerLocationTools` into `McpServerService`

**Action 1** — Modify `McpServerService.kt`

Add import:

```kotlin
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerLocationTools
```

In `registerAllTools()` method, add after the `registerNotificationTools(...)` call:

```kotlin
registerLocationTools(server, locationProvider, toolNamePrefix, perms)
```

**Definition of Done**:

- [x] Import for `registerLocationTools` added
- [x] `registerLocationTools` called in `registerAllTools()`

---

### Task 4.3: Add location tool to `McpToolsSettingsScreen.kt`

**Action 1** — Modify `McpToolsSettingsScreen.kt`

Add a new `ToolCategory` entry in `ALL_TOOL_CATEGORIES` list, after the "Notifications" category:

```kotlin
ToolCategory(
    "Location",
    listOf(
        ToolEntry(
            "get_location",
            "Get Location",
            listOf(ParamEntry("fresh_fix", "Allow fresh GPS fix")),
        ),
    ),
),
```

**Definition of Done**:

- [x] "Location" category appears in MCP Tools settings screen
- [x] `get_location` tool has enable/disable toggle
- [x] `fresh_fix` param has its own toggle (visible when tool is enabled)

---

## User Story 5: Add location permission to Permissions Settings UI

**Why**: Location needs UI permission management like camera/microphone.

### Acceptance Criteria

- [x] `isLocationPermissionGranted` function added to `PermissionUtils`
- [x] `isLocationPermissionGranted` StateFlow added to `MainViewModel`
- [x] `refreshPermissionStatus` checks location permission
- [x] Location permission row added to `PermissionsSettingsScreen`
- [x] `onRequestLocationPermission` callback plumbed from `MainActivity` through `MainScreen` and `SettingsScreen`
- [x] Location permission launcher registered in `MainActivity`
- [x] String resource `permission_location` added to `strings.xml`

---

### Task 5.1: Add `isLocationPermissionGranted` to `PermissionUtils`

**Action 1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/PermissionUtils.kt`

Add after `isMicrophonePermissionGranted`:

```kotlin
fun isLocationPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
```

**Definition of Done**:

- [x] `isLocationPermissionGranted` function added to `PermissionUtils`

---

### Task 5.2: Add location permission state to `MainViewModel`

**Action 1** — Modify `MainViewModel.kt`

Add StateFlow:

```kotlin
private val _isLocationPermissionGranted = MutableStateFlow(false)
val isLocationPermissionGranted: StateFlow<Boolean> = _isLocationPermissionGranted.asStateFlow()
```

In `refreshPermissionStatus`, add:

```kotlin
_isLocationPermissionGranted.value =
    PermissionUtils.isLocationPermissionGranted(context)
```

**Definition of Done**:

- [x] `isLocationPermissionGranted` StateFlow exposed from ViewModel
- [x] `refreshPermissionStatus` updates it

---

### Task 5.3: Add location permission row to `PermissionsSettingsScreen`

**Action 1** — Modify `PermissionsSettingsScreen.kt`

Add `onRequestLocationPermission: () -> Unit` parameter to `PermissionsSettingsScreen` composable (after `onRequestMicrophonePermission`).

Add state collection:

```kotlin
val isLocationPermissionGranted by viewModel.isLocationPermissionGranted.collectAsStateWithLifecycle()
```

Add a new `PermissionRow` after the microphone row (with `Spacer` before it):

```kotlin
Spacer(modifier = Modifier.height(8.dp))

PermissionRow(
    label = stringResource(R.string.permission_location),
    isEnabled = isLocationPermissionGranted,
    buttonText =
        if (isLocationPermissionGranted) {
            stringResource(R.string.permission_granted)
        } else {
            stringResource(R.string.permission_grant)
        },
    onAction = onRequestLocationPermission,
    actionEnabled = !isLocationPermissionGranted,
)
```

**Definition of Done**:

- [x] Location permission row displays in Permissions settings
- [x] Shows granted/not-granted status
- [x] Grant button triggers permission request callback

---

### Task 5.4: Add string resource

**Action 1** — Modify `app/src/main/res/values/strings.xml`

Add after `permission_notification_listener`:

```xml
<string name="permission_location">Location</string>
```

**Definition of Done**:

- [x] `permission_location` string resource exists

---

### Task 5.5: Plumb `onRequestLocationPermission` through the UI navigation

**Action 1** — Modify `MainActivity.kt`

Add launcher:

```kotlin
private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>
```

In `onCreate`, register it (after microphone launcher):

```kotlin
locationPermissionLauncher =
    registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.refreshPermissionStatus(this)
    }
```

Add method:

```kotlin
private fun requestLocationPermission() {
    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
}
```

Pass to `MainScreen`:

```kotlin
MainScreen(
    onRequestNotificationPermission = ::requestNotificationPermission,
    onRequestCameraPermission = ::requestCameraPermission,
    onRequestMicrophonePermission = ::requestMicrophonePermission,
    onRequestLocationPermission = ::requestLocationPermission,
)
```

**Action 2** — Modify `MainScreen.kt`

Add `onRequestLocationPermission: () -> Unit` parameter to `MainScreen` composable. Pass it through to `SettingsScreen`.

**Action 3** — Modify `SettingsScreen.kt`

Add `onRequestLocationPermission: () -> Unit` parameter to `SettingsScreen` composable. Pass it to `PermissionsSettingsScreen` in the navigation composable.

**Definition of Done**:

- [x] `locationPermissionLauncher` registered in `MainActivity`
- [x] `requestLocationPermission` method launches `ACCESS_FINE_LOCATION`
- [x] Callback plumbed through `MainScreen` → `SettingsScreen` → `PermissionsSettingsScreen`

---

## User Story 6: Update `McpIntegrationTestHelper` and add integration tests

**Why**: The integration test infrastructure must include `LocationProvider` in `MockDependencies` and call `registerLocationTools()`. Integration tests verify the tool works end-to-end through the MCP HTTP stack.

### Acceptance Criteria

- [x] `MockDependencies` includes `locationProvider: LocationProvider`
- [x] `createMockDependencies()` mocks `LocationProvider`
- [x] `registerAllTools()` calls `registerLocationTools()`
- [x] Integration tests cover: success (last known), success (fresh fix), permission denied, Play Services unavailable, no location available, Geocoder failure (street null), `fresh_fix` param disabled by user
- [x] Import for `registerLocationTools` added to helper

---

### Task 6.1: Update `McpIntegrationTestHelper`

**Action 1** — Modify `McpIntegrationTestHelper.kt`

Add `locationProvider: LocationProvider` field to `MockDependencies` data class.

In `createMockDependencies()`, add:

```kotlin
locationProvider = mockk(relaxed = true),
```

In `registerAllTools()`, add after the `registerNotificationTools` call:

```kotlin
registerLocationTools(server, deps.locationProvider, toolNamePrefix, perms)
```

Add import for `registerLocationTools` and `LocationProvider`.

**Definition of Done**:

- [x] `MockDependencies` has `locationProvider` field
- [x] `createMockDependencies` creates mock for it
- [x] `registerAllTools` calls `registerLocationTools`

---

### Task 6.2: Create `LocationToolsIntegrationTest.kt`

**Action 1** — Create `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/LocationToolsIntegrationTest.kt`

**Setup**: Mock `LocationProvider` via `MockDependencies`. Use `McpIntegrationTestHelper.withTestApplication` for full HTTP stack.

| Test | Verifies |
|------|----------|
| `get_location returns last known location with street` | `fresh_fix=false` (default), provider returns `LocationData(lat, lng, accuracy, street)`, response JSON has all four fields, uses untrusted warning |
| `get_location returns location without street when geocoder fails` | Provider returns `LocationData` with `street=null`, response JSON has `street: null` |
| `get_location with fresh_fix true calls provider with freshFix true` | `fresh_fix=true` passed to tool, verify provider called with `freshFix=true` |
| `get_location returns permission denied when location permission not granted` | Provider returns `Result.failure(SecurityException(...))`, tool returns `isError=true` with permission denied message |
| `get_location returns error when play services unavailable` | Provider returns `Result.failure(IllegalStateException("Google Play Services not available"))`, tool returns `isError=true` |
| `get_location returns error when no location available` | Provider returns `Result.failure(IllegalStateException("No last known location available..."))`, tool returns `isError=true` |
| `get_location forces fresh_fix false when param disabled` | `ToolPermissionsConfig` with `fresh_fix` param disabled, client sends `fresh_fix=true`, verify provider called with `freshFix=false`. **Setup**: `perms = ToolPermissionsConfig(disabledParams = mapOf("get_location" to setOf("fresh_fix")))` |
| `get_location with invalid fresh_fix type returns error` | `fresh_fix="yes"` (string instead of boolean), tool returns `isError=true` with invalid params message |

**Definition of Done**:

- [x] All 8 integration tests implemented and passing

---

## User Story 7: Add unit tests for `GetLocationHandler` and `LocationProviderImpl`

**Why**: Unit tests verify handler parameter extraction/error mapping and provider logic in isolation.

### Acceptance Criteria

- [x] `LocationToolsTest.kt` covers handler logic
- [x] `LocationProviderImplTest.kt` covers provider logic

---

### Task 7.1: Create `LocationToolsTest.kt`

**Action 1** — Create `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/LocationToolsTest.kt`

**Setup**: Mock `LocationProvider` via MockK. Instantiate `GetLocationHandler` directly.

| Test | Verifies |
|------|----------|
| `get_location with default params calls provider with freshFix false` | No `fresh_fix` param → provider called with `freshFix=false` |
| `get_location with fresh_fix true calls provider with freshFix true` | `fresh_fix=true` → provider called with `freshFix=true` |
| `get_location returns JSON with all fields` | Provider returns full `LocationData`, response JSON contains latitude, longitude, accuracy_meters, street |
| `get_location returns JSON with null street` | Provider returns `LocationData(street=null)`, response JSON contains `"street":null` |
| `get_location throws PermissionDenied on SecurityException` | Provider returns `Result.failure(SecurityException(...))` → `McpToolException.PermissionDenied` thrown |
| `get_location throws ActionFailed on IllegalStateException` | Provider returns `Result.failure(IllegalStateException(...))` → `McpToolException.ActionFailed` thrown |
| `get_location with fresh_fix non-boolean throws InvalidParams` | `fresh_fix="yes"` → `McpToolException.InvalidParams` thrown |
| `get_location result contains untrusted content warning` | Response text starts with `UNTRUSTED_CONTENT_WARNING` |
| `get_location with freshFixParamEnabled false forces freshFix false` | `fresh_fix=true` in arguments but `freshFixParamEnabled=false` → provider called with `freshFix=false` |
| `register adds tool with correct name and description` | `register()` calls `server.addTool()` with name containing `get_location`, description mentioning 10 seconds, nullable street, `ACCESS_FINE_LOCATION`, and Google Play Services. **Setup**: mock `Server`, capture `addTool` arguments |

**Definition of Done**:

- [x] All 10 unit tests implemented and passing

---

### Task 7.2: Create `LocationProviderImplTest.kt`

**Action 1** — Create `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/location/LocationProviderImplTest.kt`

**Setup**: Mock `Context`, `FusedLocationProviderClient`, `GoogleApiAvailability`, `Geocoder` via MockK. Static mocks required: `mockkStatic(GoogleApiAvailability::class)`, `mockkStatic(ContextCompat::class)`, `mockkStatic(LocationServices::class)`, `mockkStatic(Geocoder::class)`, `mockkStatic(android.util.Log::class)`. Unmock all in `@AfterEach`.

| Test | Verifies |
|------|----------|
| `getLocation returns failure when Play Services unavailable` | `GoogleApiAvailability.isGooglePlayServicesAvailable` returns non-SUCCESS → `Result.failure` with descriptive message |
| `getLocation returns failure when permission not granted` | `ContextCompat.checkSelfPermission` returns `PERMISSION_DENIED` → `Result.failure(SecurityException(...))` |
| `getLocation with freshFix false returns last known location` | `lastLocation` Task succeeds → `Result.success(LocationData(...))`. **Setup**: mock Task completion |
| `getLocation with freshFix false returns failure when no last known` | `lastLocation` Task returns null → `Result.failure` with "No last known location" message |
| `getLocation with freshFix true requests location update` | Verify `requestLocationUpdates` called with `PRIORITY_HIGH_ACCURACY`. **Setup**: mock LocationCallback invocation |
| `getLocation returns street from Geocoder` | Async `GeocodeListener.onGeocode` called with Address list → street populated in `LocationData`. **Setup**: mock `Geocoder`, capture `GeocodeListener` from `getFromLocation` call, invoke `onGeocode` with mock Address |
| `getLocation returns null street when Geocoder not present` | `Geocoder.isPresent()` returns false → `street=null` in result |
| `getLocation returns null street when Geocoder onError` | Async `GeocodeListener.onError` called → `street=null`, result still success with lat/lng/accuracy. **Setup**: capture listener, invoke `onError` |
| `getLocation returns null street when Geocoder returns empty list` | Async `GeocodeListener.onGeocode` called with empty list → `street=null` |
| `getLocation with freshFix true returns failure on timeout` | When location callback is not invoked within timeout period, `Result.failure` is returned with descriptive timeout message. **Setup**: mock LocationCallback that never fires, use test dispatcher to advance time past `FRESH_FIX_TIMEOUT_MS` |
| `getLocation with freshFix true returns failure when LocationResult lastLocation is null` | `LocationCallback.onLocationResult` fires with `result.lastLocation == null` → `Result.failure` with "Location result was null" message. **Setup**: mock `LocationResult` with `lastLocation` returning null |
| `getLocation rethrows CancellationException` | When coroutine is cancelled externally, `CancellationException` propagates (not wrapped in `Result.failure`). **Setup**: cancel the coroutine scope during `getLocation` execution |

**Definition of Done**:

- [x] All 12 unit tests implemented and passing

---

## User Story 8: Update documentation

**Why**: `docs/MCP_TOOLS.md` must document the new tool. `docs/PROJECT.md` and `docs/ARCHITECTURE.md` may need updates for the new dependency and service.

### Acceptance Criteria

- [x] `get_location` tool documented in `docs/MCP_TOOLS.md`
- [x] Location category added to tool count/overview table in `docs/MCP_TOOLS.md`
- [x] `docs/PROJECT.md` updated: Frameworks & Libraries, Folder Structure, Permission Handling, Integration Tests mocking list, permissions list
- [x] `docs/ARCHITECTURE.md` updated: Permission Model table, Component Diagram (if location service warrants a node)

---

### Task 8.1: Update `docs/MCP_TOOLS.md`

**Action 1** — Modify `docs/MCP_TOOLS.md`

Add a new section for the "Location" tool category. Follow the existing documentation format. Include:

- Tool name: `get_location`
- Description
- Input parameters: `fresh_fix` (boolean, optional, default `false`)
- Output: JSON object with `latitude`, `longitude`, `accuracy_meters`, `street` (nullable)
- Error handling: permission denied, Play Services unavailable, no location, timeout
- Note about 10s timeout for fresh fix
- Update the tool count in the overview section
- Add "Location" to the table of contents and categories table

**Definition of Done**:

- [x] `get_location` fully documented in `MCP_TOOLS.md`
- [x] Overview section updated with new tool count and category

---

### Task 8.2: Update `docs/PROJECT.md`

**Action 1** — Modify `docs/PROJECT.md`

The following sections MUST be updated:

1. **Frameworks & Libraries** (line ~107): Add `- **Google Play Services Location**: Device location via FusedLocationProviderClient` after `Accompanist` entry.

2. **Folder Structure** (line ~134 area): Add `services/location/` entry: `- services/location/ — LocationProvider.kt, LocationProviderImpl.kt`. Add `LocationData.kt` to the `data/model/` line. Add `LocationTools.kt` to the `mcp/tools/` line.

3. **Permission Handling** (line ~368 area): Add after `RECORD_AUDIO` entry: `- **ACCESS_FINE_LOCATION**: Runtime permission required for device location. Requested via UI permission launcher`

4. **Integration Tests — Scope** (line ~509): Update `all 10 tool categories` to `all 11 tool categories`.

5. **Integration Tests — Mocking** (line ~510): Add `LocationProvider` to the mocked services list.

6. **Permissions list** (line ~621): Add `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` to the permissions list.

**Definition of Done**:

- [x] `play-services-location` added to Frameworks & Libraries
- [x] `services/location/`, `LocationData.kt`, `LocationTools.kt` added to Folder Structure
- [x] `ACCESS_FINE_LOCATION` added to Permission Handling section
- [x] Integration Tests sections updated with location provider
- [x] Permissions summary list updated

---

### Task 8.3: Update `docs/ARCHITECTURE.md`

**Action 1** — Modify `docs/ARCHITECTURE.md`

1. **Permission Model table** (line ~233): Add row after `RECORD_AUDIO`:

```
| ACCESS_FINE_LOCATION     | Runtime       | System dialog                      | Device location tool      |
| ACCESS_COARSE_LOCATION   | Runtime       | Declared (implied by FINE)         | Device location fallback  |
```

2. **Component Diagram** (line ~11): If the diagram lists individual service providers, add `LocationProv["LocationProvider"]` under an appropriate subgraph. If location is lightweight enough to group under existing services, add it to the `StorageSvc` subgraph (which already groups miscellaneous services) and add an `SDK --> LocationProv` edge. Validate the updated Mermaid diagram with `mmdc`.

**Definition of Done**:

- [x] Permission Model table includes location permissions
- [x] Component diagram updated if applicable (validate with `mmdc`)

---

## User Story 9: Quality gates and final verification

**Why**: All changes must pass linting, build, and tests before the work is considered done.

### Acceptance Criteria

- [x] `make lint` passes with no warnings or errors
- [x] `./gradlew build` succeeds with no warnings or errors
- [x] All unit tests pass (`./gradlew test`)
- [x] All existing tests still pass (no regressions)
- [x] Code review subagent passes in plan compliance mode

---

### Task 9.1: Run linting and fix any issues

- [x] Run `make lint`
- [x] Fix any lint/format violations

---

### Task 9.2: Run full build

- [x] Run `./gradlew build`
- [x] Fix any build errors or warnings

---

### Task 9.3: Run full test suite

- [x] Run `make test-unit`
- [x] Fix any failing tests

---

### Task 9.4: Full implementation review from the ground up

Perform a complete review of EVERY file changed or created in this plan, verifying:

- [x] All files match the plan specification exactly
- [x] No divergence from what was discussed with the user
- [x] `LocationProviderImpl` checks Play Services on invocation (not at startup)
- [x] `LocationProviderImpl` checks permission on invocation (not at startup)
- [x] `fresh_fix` default is `false`
- [x] `fresh_fix` is forced to `false` when user disables the param toggle
- [x] Tool uses `untrustedTextResult()` for device-derived content
- [x] Tool is toggleable via existing enable/disable system
- [x] Location permission appears in Permissions settings screen
- [x] Geocoder failure is non-fatal
- [x] 10-second timeout for fresh fix
- [x] Tool description mentions timeout, nullable street, permission requirement
- [x] No new unnecessary dependencies added
- [x] All error paths mapped correctly (SecurityException → PermissionDenied, others → ActionFailed)
- [x] Hilt bindings correct
- [x] Integration test helper updated
- [x] `docs/PROJECT.md` updated (Frameworks, Folder Structure, Permissions, Integration Tests, permissions list)
- [x] `docs/ARCHITECTURE.md` updated (Permission Model table, Component Diagram if applicable)
- [x] `docs/MCP_TOOLS.md` updated with `get_location` tool documentation
- [x] All tests pass

---

### Task 9.5: Spawn `code-reviewer` subagent

- [x] Spawn `code-reviewer` in plan compliance mode to verify entire implementation matches this plan
- [x] Address all findings
