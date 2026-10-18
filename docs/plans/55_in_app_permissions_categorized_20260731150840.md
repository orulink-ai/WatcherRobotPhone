<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 55 — Categorize the in-app Permissions screen (Required / Required if / Optional)

## Context

The in-app Permissions screen (`ui/screens/settings/PermissionsSettingsScreen.kt`) currently renders a flat list of permission rows, each showing only a status icon, a label, and a grant/enable button — with no explanation of *why* a permission is needed. This makes the app appear to request many permissions "for the sake of it". This plan reorganizes the screen into three necessity-based groups, each with a short intro, and adds one concise rationale line per permission. The Location rationale is flavor-aware (the GMS build mentions geofences, the FOSS build does not), and the GMS-only Background Location row moves from hardcoded literals to string resources.

## Scope

**In scope**
- `PermissionsSettingsScreen.kt`: group the existing rows into three sections with headers; add a rationale line to each row.
- `PermissionRow`: add a `rationale` parameter and render it under the label.
- New string resources: three section titles + three section subtitles, and one rationale string per permission (default/FOSS values in `main`, GMS override for Location).
- GMS `BackgroundLocationPermissionRow`: pass a rationale, convert its hardcoded `"Background Location"` label and `"Open Settings"` button to string resources, and own the pre-row spacer so FOSS shows no dangling gap.

**Out of scope (MUST NOT change)**
- Permission-grant behavior, status detection, `MainViewModel`, navigation, manifest, and any tool logic.
- Any file not listed in the actions below.

## Decisions (agreed with user — MUST NOT diverge)

- Buckets: **Required** = Accessibility only; **Required if you use a feature** = Notifications, Notification Listener, Camera, Location, Background Location; **Optional** = Microphone.
- Rationale copy (verbatim):
  | Permission | Rationale |
  |---|---|
  | Accessibility Service | `Reads the screen and performs taps, swipes, and typing.` |
  | Notifications | `OAuth approval prompts, Event Channel alerts, and server status.` |
  | Notification Listener | `Read and act on device notifications.` |
  | Camera | `Take photos and record video.` |
  | Location (main/FOSS) | `Read GPS position.` |
  | Location (GMS override) | `Read GPS position and trigger geofences.` |
  | Background Location (GMS only) | `Fire geofence events while in the background.` |
  | Microphone | `Adds audio to recorded video.` |
- Section intros (verbatim): Required → `Needed for the app to control your device.`; Required if you use a feature → `Grant only the capabilities you plan to use.`; Optional → `Nice to have; everything works without it.`
- Flavor-aware Location copy via **string-resource override** (`main/res` default + `gms/res` override).
- **Testing: no automated tests** (the `**/ui/**` layer has no test harness and is excluded from coverage). Verification is `./gradlew build` (both flavors) + `make lint` + the documented Manual QA checklist in User Story 2.
- Section header style matches existing settings screens: title = `MaterialTheme.typography.labelLarge`; subtitle = `MaterialTheme.typography.bodySmall` in `MaterialTheme.colorScheme.onSurfaceVariant`.

---

## User Story 1 — Reorganize the Permissions screen into necessity-based groups

**Why:** Grouping permissions by necessity and stating a one-line reason per permission communicates that each request is purposeful, addressing the perception that the app over-requests permissions. This intent cannot be derived from the current code.

**Acceptance criteria**
- [x] The screen shows three labelled sections in order: **Required**, **Required if you use a feature**, **Optional**, each with its intro line.
- [x] Each permission row shows its concise rationale (verbatim from Decisions) under the label.
- [x] Grouping matches Decisions exactly (Accessibility → Required; Notifications, Notification Listener, Camera, Location, Background Location → Required if; Microphone → Optional).
- [x] Location rationale is flavor-aware: GMS reads `Read GPS position and trigger geofences.`; FOSS reads `Read GPS position.`
- [x] Background Location row appears only on GMS; its label, button, and rationale come from string resources; FOSS shows no dangling spacer where the row would be.
- [x] Grant/enable actions, button texts, and status detection are unchanged.
- [x] `./gradlew build` succeeds for both flavors with no warnings; `make lint` is clean.

### Task 1 — Add default (main) string resources

**Action 1.1** — modify `app/src/main/res/values/strings.xml`

Insert immediately after the `permission_location` entry (currently line ~106), before `permission_warning_message`:

```xml
<!-- Permissions screen: section headers -->
<string name="permission_section_required_title">Required</string>
<string name="permission_section_required_subtitle">Needed for the app to control your device.</string>
<string name="permission_section_required_if_title">Required if you use a feature</string>
<string name="permission_section_required_if_subtitle">Grant only the capabilities you plan to use.</string>
<string name="permission_section_optional_title">Optional</string>
<string name="permission_section_optional_subtitle">Nice to have; everything works without it.</string>
<!-- Permissions screen: per-permission rationale (default / foss values) -->
<string name="permission_accessibility_rationale">Reads the screen and performs taps, swipes, and typing.</string>
<string name="permission_notifications_rationale">OAuth approval prompts, Event Channel alerts, and server status.</string>
<string name="permission_notification_listener_rationale">Read and act on device notifications.</string>
<string name="permission_camera_rationale">Take photos and record video.</string>
<string name="permission_location_rationale">Read GPS position.</string>
<string name="permission_microphone_rationale">Adds audio to recorded video.</string>
```

**Definition of Done**
- [x] All 12 strings above exist in `main` with the exact verbatim values.
- [x] No duplicate string names; file still valid XML.

### Task 2 — Add GMS string overrides and Background Location strings

**Action 2.1** — modify `app/src/gms/res/values/strings.xml`

Add inside `<resources>` (after the existing `event_channel_subtitle`):

```xml
<!-- gms: Location rationale mentions geofences (overrides the main default) -->
<string name="permission_location_rationale">Read GPS position and trigger geofences.</string>
<!-- gms: Background Location permission row (geofence-only) -->
<string name="permission_background_location">Background Location</string>
<string name="permission_background_location_open_settings">Open Settings</string>
<string name="permission_background_location_rationale">Fire geofence events while in the background.</string>
```

**Definition of Done**
- [x] `permission_location_rationale` override present in `gms/res` with the geofence wording.
- [x] `permission_background_location`, `permission_background_location_open_settings`, `permission_background_location_rationale` present in `gms/res` only.
- [x] File still valid XML.

### Task 3 — Add rationale rendering to `PermissionRow` and a section-header composable

**Action 3.1** — modify `PermissionRow` in `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/PermissionsSettingsScreen.kt`

Add a `rationale: String` parameter and render the label + rationale as a two-line `Column` in the weighted slot (Icon and Button unchanged):

```kotlin
@Composable
internal fun PermissionRow(
    label: String,
    rationale: String,
    isEnabled: Boolean,
    buttonText: String,
    onAction: () -> Unit,
    actionEnabled: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isEnabled) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = if (isEnabled) "$label enabled" else "$label disabled",
            tint = if (isEnabled) enabledColor() else disabledColor(),
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = onAction, enabled = actionEnabled) {
            Text(text = buttonText)
        }
    }
}
```

**Action 3.2** — add a private section-header composable in the same file (near `PermissionRow`):

```kotlin
@Composable
private fun PermissionSectionHeader(title: String, subtitle: String) {
    Text(text = title, style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
}
```

> Note: `androidx.compose.foundation.layout.Column` is ALREADY imported in this file (line 8, used by the outer scaffold `Column`). Do NOT add it again — a duplicate import fails the ktlint gate. The new `Column` usage inside `PermissionRow` compiles with the existing import.

**Definition of Done**
- [x] `PermissionRow` has the new `rationale` parameter and renders it as a `bodySmall` / `onSurfaceVariant` line under the `bodyLarge` label.
- [x] `PermissionSectionHeader` exists with the agreed styles.
- [x] No new import added (`Column` already present at line 8); no unused imports; file compiles.

### Task 4 — Regroup the screen body into the three sections

**Action 4.1** — modify the inner scrollable `Column` body in `PermissionsSettingsScreen` (the block currently rendering the six `PermissionRow` calls, the inter-row `Spacer`s, and `BackgroundLocationPermissionRow()`), replacing it with the grouped layout below. Do NOT change the enclosing `Column(modifier = Modifier.weight(1f)...verticalScroll...padding(16.dp))`, the `TopAppBar`, the `collectAsStateWithLifecycle` state, or the `DisposableEffect`.

```kotlin
// ---- Required ----
PermissionSectionHeader(
    title = stringResource(R.string.permission_section_required_title),
    subtitle = stringResource(R.string.permission_section_required_subtitle),
)
PermissionRow(
    label = stringResource(R.string.permission_accessibility),
    rationale = stringResource(R.string.permission_accessibility_rationale),
    isEnabled = isAccessibilityEnabled,
    buttonText =
        if (isAccessibilityEnabled) {
            stringResource(R.string.permission_enabled)
        } else {
            stringResource(R.string.permission_enable)
        },
    onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    actionEnabled = !isAccessibilityEnabled,
)

Spacer(modifier = Modifier.height(16.dp))

// ---- Required if you use a feature ----
PermissionSectionHeader(
    title = stringResource(R.string.permission_section_required_if_title),
    subtitle = stringResource(R.string.permission_section_required_if_subtitle),
)
PermissionRow(
    label = stringResource(R.string.permission_notifications),
    rationale = stringResource(R.string.permission_notifications_rationale),
    isEnabled = isNotificationPermissionGranted,
    buttonText =
        if (isNotificationPermissionGranted) {
            stringResource(R.string.permission_granted)
        } else {
            stringResource(R.string.permission_grant)
        },
    onAction = onRequestNotificationPermission,
    actionEnabled = !isNotificationPermissionGranted,
)

Spacer(modifier = Modifier.height(8.dp))

PermissionRow(
    label = stringResource(R.string.permission_notification_listener),
    rationale = stringResource(R.string.permission_notification_listener_rationale),
    isEnabled = isNotificationListenerEnabled,
    buttonText =
        if (isNotificationListenerEnabled) {
            stringResource(R.string.permission_enabled)
        } else {
            stringResource(R.string.permission_enable)
        },
    onAction = { PermissionUtils.openNotificationListenerSettings(context) },
    actionEnabled = !isNotificationListenerEnabled,
)

Spacer(modifier = Modifier.height(8.dp))

PermissionRow(
    label = stringResource(R.string.permission_camera),
    rationale = stringResource(R.string.permission_camera_rationale),
    isEnabled = isCameraPermissionGranted,
    buttonText =
        if (isCameraPermissionGranted) {
            stringResource(R.string.permission_granted)
        } else {
            stringResource(R.string.permission_grant)
        },
    onAction = onRequestCameraPermission,
    actionEnabled = !isCameraPermissionGranted,
)

Spacer(modifier = Modifier.height(8.dp))

PermissionRow(
    label = stringResource(R.string.permission_location),
    rationale = stringResource(R.string.permission_location_rationale),
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

// Background Location is geofence-only; rendered via a flavor seam (gms only).
// The gms seam owns its own leading spacer so foss shows no dangling gap.
BackgroundLocationPermissionRow()

Spacer(modifier = Modifier.height(16.dp))

// ---- Optional ----
PermissionSectionHeader(
    title = stringResource(R.string.permission_section_optional_title),
    subtitle = stringResource(R.string.permission_section_optional_subtitle),
)
PermissionRow(
    label = stringResource(R.string.permission_microphone),
    rationale = stringResource(R.string.permission_microphone_rationale),
    isEnabled = isMicrophonePermissionGranted,
    buttonText =
        if (isMicrophonePermissionGranted) {
            stringResource(R.string.permission_granted)
        } else {
            stringResource(R.string.permission_grant)
        },
    onAction = onRequestMicrophonePermission,
    actionEnabled = !isMicrophonePermissionGranted,
)
```

**Definition of Done**
- [x] Three sections render in order with headers and intros.
- [x] Rows appear in exactly the grouping/order above; Microphone is under Optional; no `Spacer` is placed directly before `BackgroundLocationPermissionRow()` in this file.
- [x] Every row passes the correct `rationale` string; all other row arguments (button text, `onAction`, `actionEnabled`) are unchanged from the current behavior.
- [x] No leftover references to the old flat-list layout.

### Task 5 — Update the GMS Background Location seam

**Action 5.1** — modify `app/src/gms/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/BackgroundLocationPermissionRow.kt`

Prepend a leading `Spacer`, pass the rationale, and replace the hardcoded `"Background Location"` and `"Open Settings"` literals with string resources:

```kotlin
Spacer(modifier = Modifier.height(8.dp))
PermissionRow(
    label = stringResource(R.string.permission_background_location),
    rationale = stringResource(R.string.permission_background_location_rationale),
    isEnabled = granted,
    buttonText =
        if (granted) {
            stringResource(R.string.permission_granted)
        } else {
            stringResource(R.string.permission_background_location_open_settings)
        },
    onAction = {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        context.startActivity(intent)
    },
    actionEnabled = !granted,
)
```

Add the imports needed for the leading spacer (the current gms file imports none of these; keep all existing imports):

```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
```

> Note: The functional change is the leading `Spacer(modifier = Modifier.height(8.dp))`, the `rationale` argument, and the two `stringResource` replacements. The permission check, lifecycle observer, and `onAction` intent MUST remain unchanged. Do NOT import `width` — it is not used in this file.

**Definition of Done**
- [x] GMS Background Location row renders a leading 8dp spacer, then the row with its rationale.
- [x] Label and button text come from `permission_background_location` and `permission_background_location_open_settings`; no hardcoded English literals remain in this file.
- [x] The FOSS `BackgroundLocationPermissionRow` remains intentionally empty (no spacer, no row) — file untouched.
- [x] GMS flavor compiles.

---

## User Story 2 — Verify the entire implementation from the ground up

**Why:** A final, independent pass guarantees every action was applied exactly as specified, both flavors build cleanly, and the visible result matches the agreed design on both GMS and FOSS.

**Acceptance criteria**
- [x] Every action in User Story 1 is confirmed applied and matches this plan verbatim.
- [ ] Both flavors build; lint is clean; Manual QA passes on GMS and FOSS.

### Task 6 — Ground-up verification

**Action 6.1** — Re-read each modified file against this plan and confirm, line by line:
- `main/res/values/strings.xml` — 12 new strings, verbatim values.
- `gms/res/values/strings.xml` — 4 new strings (Location override + 3 background-location), verbatim values.
- `PermissionsSettingsScreen.kt` — `PermissionRow` signature/rendering, `PermissionSectionHeader`, `Column` used in `PermissionRow` (import already present at line 8, none added), three-section body, correct grouping/order/rationale, no pre-`BackgroundLocationPermissionRow()` spacer.
- GMS `BackgroundLocationPermissionRow.kt` — leading spacer, rationale, string-resource label/button, unchanged permission/lifecycle/intent logic.
- FOSS `BackgroundLocationPermissionRow.kt` — unchanged.
- No file outside this plan's scope modified.

**Action 6.2** — Build (both flavors, all variants — matches the User Story 1 acceptance criterion and the project DoD) and lint:
```bash
./gradlew build 2>&1 | tee /tmp/p55-build.log | tail -20
make lint 2>&1 | tee /tmp/p55-lint.log | tail -20
```

**Action 6.3 — Manual QA Steps** (label: **Manual QA**; not a substitute for the build/lint gates):
- GMS build: open **Settings → Permissions**. Confirm three sections (Required / Required if you use a feature / Optional) with their intro lines; Accessibility under Required; Notifications, Notification Listener, Camera, Location, Background Location under Required if; Microphone under Optional. Confirm each row shows the verbatim rationale. Confirm Location reads `Read GPS position and trigger geofences.` and Background Location is present with label/button from resources.
- FOSS build: repeat; confirm Background Location row is absent, there is no dangling gap where it would be, and Location reads `Read GPS position.`
- Both builds: tap each grant/enable button and confirm the existing behavior is unchanged (opens the correct settings / requests the runtime permission), and status icons update on return (ON_RESUME refresh).

**Definition of Done**
- [x] Action 6.1 checklist fully verified.
- [x] `./gradlew build` succeeds for both flavors with no warnings (`/tmp/p55-build.log`).
- [x] `make lint` is clean (`/tmp/p55-lint.log`).
- [ ] Manual QA passes on both GMS and FOSS per Action 6.3.
- [x] `code-reviewer` (plan-compliance mode) run after all gates pass, and all findings addressed.

---

## Post-implementation review

- **2026-07-31 — plan-reviewer (pre-implementation)**: pass 1 FAIL (1 CRITICAL / 2 WARNING / 1 INFO, findings P55-001…P55-004 — import accuracy in Actions 3.3/5.1 and the Action 6.2 build command); pass 2 **PASS** (0/0/0) after fixes.
- **2026-07-31 — code-reviewer (plan-compliance, post-implementation)**: **PASS** (0 CRITICAL / 0 WARNING / 0 INFO). Verified all strings verbatim, bucket/section/row order, flavor-override mechanism, GMS seam conversion with unchanged permission/lifecycle/intent logic, FOSS seam untouched, scope limited to the 4 in-scope files, and `make lint` clean. The one-line→multi-line `PermissionSectionHeader` signature was a ktlint-mandated formatting normalization, not a divergence.
- **2026-07-31 — code-reviewer (adversarial, post-implementation)**: pass 1 FAIL (0 CRITICAL / 0 WARNING / 1 INFO). **A55-001** — the new section headers lacked `heading()` semantics, so TalkBack could not navigate the Required / Required if you use a feature / Optional grouping by heading (the project's accessibility rules require semantic composables and logical focus order). **Fixed** by adding `Modifier.semantics { heading() }` to the `PermissionSectionHeader` title (imports `androidx.compose.ui.semantics.heading` / `semantics`). Pass 2 **PASS** (0/0/0) — A55-001 resolved; fresh adversarial sweep (correctness, recomposition, a11y, theming, flavor behavior, strings, quality) found no new or remaining defects; both flavors compile with zero warnings; ktlint/detekt clean on a forced re-run.
- **Implementation note (behavior-preserving)**: the plan wrote `PermissionSectionHeader` with a single-line signature; the project's ktlint `function-signature` rule forces multi-line signatures for 2+ parameters (as with `PermissionRow`), so the signature was formatted multi-line. No change to parameters, types, order, visibility, or behavior.
- **Manual QA (Action 6.3)**: NOT performed in this session — the three on-device visual checks require a physical device/emulator and are left for the user. All automated gates (four-variant `./gradlew build`, full test suite, `make lint`) pass.
